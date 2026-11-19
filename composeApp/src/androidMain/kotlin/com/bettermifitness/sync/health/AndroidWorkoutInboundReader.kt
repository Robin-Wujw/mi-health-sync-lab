package com.bettermifitness.sync.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.bettermifitness.sync.data.api.WorkoutRoutePoint
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.api.WorkoutTimedSample
import java.time.Instant
import kotlin.reflect.KClass

/** Source-attributed reads; optional metrics are never prorated or copied from another source. */
internal class AndroidWorkoutInboundReader(
    private val source: WorkoutInboundSource,
    private val selfPackage: String,
    private val guard: suspend () -> Unit,
    private val cachedRoute: (ExerciseSessionRecord) -> List<WorkoutRoutePoint>? = { null },
) {
    constructor(client: HealthConnectClient, selfPackage: String, guard: suspend () -> Unit,
        cachedRoute: (ExerciseSessionRecord) -> List<WorkoutRoutePoint>? = { null }) : this(object : WorkoutInboundSource {
        override suspend fun permissions() = client.permissionController.getGrantedPermissions()
        override suspend fun <T : Record> page(type: KClass<T>, request: HealthInboundReadRequest, token: String?): HealthReadPage<T> {
            val page = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(
                Instant.ofEpochSecond((request.fromEpochSeconds - 86_400).coerceAtLeast(0)),
                Instant.ofEpochSecond(request.untilEpochSeconds)),
                dataOriginFilter = request.sourcePackages.map { DataOrigin(it) }.toSet(), pageToken = token))
            return HealthReadPage(page.records, page.pageToken)
        }
    }, selfPackage, guard, cachedRoute)

    suspend fun workouts(request: HealthInboundReadRequest): List<InboundWorkout> {
        val sessions = read(ExerciseSessionRecord::class, request).filter {
            it.startTime.epochSecond >= request.fromEpochSeconds && it.endTime.epochSecond <= request.untilEpochSeconds
        }
        val distance = readOptional(DistanceRecord::class, request)
        val calories = readOptional(ActiveCaloriesBurnedRecord::class, request)
        val heart = readOptional(HeartRateRecord::class, request)
        val speed = readOptional(SpeedRecord::class, request)
        val cadence = readOptional(StepsCadenceRecord::class, request)
        val cycling = readOptional(CyclingPedalingCadenceRecord::class, request)
        val power = readOptional(PowerRecord::class, request)
        val elevation = readOptional(ElevationGainedRecord::class, request)
        return sessions.map { row ->
            val omissions = mutableSetOf<String>()
            fun uniqueAt(time: Instant) = sessions.count { time >= it.startTime && time < it.endTime } == 1
            fun belongs(start: Instant, end: Instant) = start == row.startTime && end == row.endTime &&
                sessions.count { start < it.endTime && end > it.startTime } == 1
            fun sample(time: Instant) = time >= row.startTime && time < row.endTime && uniqueAt(time)
            fun exact(values: List<Double>, family: String): Double? {
                if (values.size > 1) omissions += family
                return values.singleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            }
            fun series(values: List<Pair<Instant, Double>>, family: String): List<WorkoutTimedSample> {
                val grouped = values.filter { sample(it.first) }.groupBy { it.first.epochSecond }
                val conflicts = grouped.filterValues { it.map { v -> v.second }.distinct().size != 1 }
                if (conflicts.isNotEmpty()) omissions += "${family}_conflicting_seconds"
                if (grouped.values.any { it.any { v -> v.first.nano != 0 } }) omissions += "${family}_second_resolution"
                return grouped.filterKeys { it !in conflicts }.toSortedMap().map { (time, v) ->
                    WorkoutTimedSample(time, v.first().second)
                }
                    .filter { it.value.isFinite() && it.value >= 0 }
            }
            val sourceHeart = series(heart.flatMap { r -> r.samples.map { it.time to it.beatsPerMinute.toDouble() } }, "heart_rate")
            val hr = sourceHeart.filter { it.value in 1.0..255.0 }
            if (hr.size != sourceHeart.size) omissions += "heart_rate_native_range"
            val pauses = row.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }
                .map { it.startTime.epochSecond to it.endTime.epochSecond }.sortedBy { it.first }
            var covered = row.startTime.epochSecond
            var pauseSeconds = 0L
            for ((start, end) in pauses) {
                pauseSeconds += (end - maxOf(start, covered)).coerceAtLeast(0)
                covered = maxOf(covered, end)
            }
            val route = (row.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route.orEmpty()
            val wholeSecondRoute = route.all { it.time.nano == 0 }
            if (!wholeSecondRoute) omissions += "gps_subsecond"
            val offset = row.startZoneOffset
            if (offset == null || offset != row.endZoneOffset) omissions += "timezone"
            if (row.startTime.nano != 0 || row.endTime.nano != 0) omissions += "subsecond_session"
            val activity = TYPES[row.exerciseType] ?: "unsupported_hc_${row.exerciseType}"
            val exactDistances = distance.filter { belongs(it.startTime, it.endTime) }.map { it.distance.inMeters }
            val totalDistance = if (exactDistances.isEmpty() && belongs(row.startTime, row.endTime))
                completeLapDistance(row) else exact(exactDistances, "distance")
            InboundWorkout(row.metadata.origin(), WorkoutSession(
                row.startTime.epochSecond, row.endTime.epochSecond, activity,
                distanceMeters = totalDistance,
                caloriesKcal = exact(calories.filter { belongs(it.startTime, it.endTime) }.map { it.energy.inKilocalories }, "calories"),
                tzIn15Min = offset?.totalSeconds?.takeIf { it % 900 == 0 }?.div(900),
                heartRateSeries = hr,
                speedSeries = series(speed.flatMap { r -> r.samples.map { it.time to it.speed.inMetersPerSecond } }, "speed"),
                cadenceSeries = series(cadence.flatMap { r -> r.samples.map { it.time to it.rate } }, "cadence"),
                powerWattsSeries = series(power.flatMap { r -> r.samples.map { it.time to it.power.inWatts } }, "power"),
                elevationGainM = exact(elevation.filter { belongs(it.startTime, it.endTime) }.map { it.elevation.inMeters }, "elevation"),
                route = cachedRoute(row) ?: if (wholeSecondRoute) route.map {
                    WorkoutRoutePoint(it.time.epochSecond, it.latitude, it.longitude,
                        it.altitude?.inMeters, it.horizontalAccuracy?.inMeters)
                } else emptyList(),
                // An available HC cycling cadence is kept distinct from running steps cadence.
                avgCadenceSpm = if (activity.contains("riding")) cycling.flatMap { it.samples }.filter { sample(it.time) }
                    .map { it.revolutionsPerMinute }.takeIf { it.isNotEmpty() }?.average() else null,
            ), row.endTime.epochSecond - row.startTime.epochSecond - pauseSeconds,
                activity.startsWith("outdoor_") && row.exerciseRouteResult is ExerciseRouteResult.ConsentRequired &&
                    cachedRoute(row) == null, omissions)
        }
    }

    private fun completeLapDistance(row: ExerciseSessionRecord): Double? {
        val laps = row.laps.sortedBy { it.startTime }
        if (laps.isEmpty() || laps.first().startTime != row.startTime || laps.last().endTime != row.endTime ||
            laps.zipWithNext().any { (a, b) -> a.endTime != b.startTime }) return null
        val lengths = laps.map { it.length?.inMeters ?: return null }
        return lengths.takeIf { it.all { value -> value.isFinite() && value >= 0 } }?.sum()?.takeIf { it.isFinite() }
    }

    suspend fun vo2Max(request: HealthInboundReadRequest): List<InboundVo2Max> = read(Vo2MaxRecord::class, request).filter {
        it.time >= Instant.ofEpochSecond(request.fromEpochSeconds) && it.time < Instant.ofEpochSecond(request.untilEpochSeconds)
    }.map {
        InboundVo2Max(it.metadata.origin(), if (it.time.nano == 0) it.time.epochSecond else -1L, it.zoneOffset?.totalSeconds,
            it.vo2MillilitersPerMinuteKilogram, it.measurementMethod)
    }

    private suspend fun <T : Record> readOptional(type: KClass<T>, request: HealthInboundReadRequest): List<T> =
        if (HealthPermission.getReadPermission(type) in source.permissions()) read(type, request)
        else emptyList()

    private suspend fun <T : Record> read(type: KClass<T>, request: HealthInboundReadRequest): List<T> {
        val rows = collectHealthReadPages { token ->
            guard()
            val page = source.page(type, request, token)
            guard()
            page
        }
        return rows.filter { HealthInboundReadPolicy.allowsOrigin(it.metadata.origin(), request, selfPackage) }
            .groupBy { it.metadata.id }.values.map { revisions ->
                val newest = revisions.maxOf { it.metadata.lastModifiedTime }
                val selected = revisions.filter { it.metadata.lastModifiedTime == newest }.distinct()
                check(selected.size == 1) { "Conflicting source workout revisions" }
                selected.single()
            }
    }

    private fun Metadata.origin() = HealthRecordOrigin(id, dataOrigin.packageName, clientRecordId,
        clientRecordVersion, lastModifiedTime.toEpochMilli())

    private companion object {
        val TYPES = mapOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "outdoor_running",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "indoor_running",
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "outdoor_walking",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "outdoor_hiking",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "outdoor_riding",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "indoor_riding",
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "free_training",
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "strength_training",
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "weight_lifting",
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "rowing_machine",
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "elliptical_trainer",
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "yoga",
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "high_interval_training",
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "badminton",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "pool_swimming",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "open_swimming",
        )
    }
}

internal interface WorkoutInboundSource {
    suspend fun permissions(): Set<String>
    suspend fun <T : Record> page(type: KClass<T>, request: HealthInboundReadRequest, token: String?): HealthReadPage<T>
}
