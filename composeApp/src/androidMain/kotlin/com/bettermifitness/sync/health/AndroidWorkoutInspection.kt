package com.bettermifitness.sync.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException

/** Source capability report only: no cloud client, writer, ledger or route-consent request. */
class AndroidWorkoutInspection internal constructor(private val source: WorkoutInspectionSource) {
    constructor(client: HealthConnectClient) : this(object : WorkoutInspectionSource {
        override suspend fun permissions(): Set<String> = client.permissionController.getGrantedPermissions()

        override suspend fun page(type: KClass<out Record>, range: TimeRangeFilter, token: String?): WorkoutInspectionPage {
            val response = client.readRecords(ReadRecordsRequest(type, range,
                dataOriginFilter = setOf(DataOrigin(ZEPP_PACKAGE)), pageToken = token))
            return WorkoutInspectionPage(response.records, response.pageToken)
        }
    })

    suspend fun inspect(from: Instant, until: Instant): String {
        require(from < until && Duration.between(from, until) <= Duration.ofDays(27))
        val granted = source.permissions()
        val range = TimeRangeFilter.between(from, until)
        val results = TYPES.map { (key, type) -> key to read(type, range, granted) }.toMap()
        val sessionResult = results.getValue("sessions")
        val allSessions = sessionResult.records.filterIsInstance<ExerciseSessionRecord>()
        val sessions = allSessions.filter { it.startTime >= from && it.endTime <= until }
        val lines = mutableListOf(
            "workout_inspection.schema=1", "workout_inspection.source=zepp",
            "workout_inspection.health_uploads=0", "workout_inspection.cloud_requests=0",
            "workout_inspection.local_journal_changes=0", "workout_inspection.values_withheld=true",
            "workout_inspection.window=rolling_instant", "workout_inspection.association=temporal_only_not_proven",
            "workout_inspection.snapshot=non_atomic", "workout_inspection.history_visibility=provider_and_existing_grants",
            "workout_inspection.import_ready=false", "workout_inspection.pai=NO_STANDARD_HC_MAPPING",
            "workout_inspection.vitality=RECOMPUTATION_UNVERIFIED",
        )
        for ((key, result) in results) {
            val prefix = "workout_inspection.$key"
            lines += "$prefix.status=${result.status}"
            lines += "$prefix.read_complete=${result.status == "COMPLETE"}"
            lines += "$prefix.pages=${result.pages}"
            lines += "$prefix.records=${result.records.size}"
            lines += "$prefix.excluded_source_or_loop=${result.excluded}"
            // Never treat a partially read session list as a complete association universe.
            if (key != "sessions" && key != "vo2_max" && result.status == "COMPLETE" && sessionResult.status == "COMPLETE") {
                val associations = result.records.map { association(it, sessions) }
                for (status in listOf("CONTAINED_ONE", "AMBIGUOUS", "CROSSES_BOUNDARY", "OUTSIDE")) {
                    lines += "$prefix.${status.lowercase()}_records=${associations.count { it == status }}"
                }
                lines += "$prefix.samples_in_one_session=${result.records.sumOf { record ->
                    sampleTimes(record).count { time -> time >= from && time < until &&
                        sessions.count { time >= it.startTime && time < it.endTime } == 1 }
                }}"
            }
        }
        lines += "workout_inspection.sessions.complete_window_records=${sessions.size}"
        lines += "workout_inspection.sessions.crossing_window_records=${allSessions.size - sessions.size}"
        lines += "workout_inspection.sessions.missing_zone=" + sessions.count {
            it.startZoneOffset == null || it.endZoneOffset == null
        }
        lines += "workout_inspection.sessions.offset_changed=${sessions.count {
            it.startZoneOffset != null && it.endZoneOffset != null && it.startZoneOffset != it.endZoneOffset
        }}"
        lines += "workout_inspection.sessions.with_title=${sessions.count { !it.title.isNullOrBlank() }}"
        lines += "workout_inspection.sessions.with_notes=${sessions.count { !it.notes.isNullOrBlank() }}"
        // Text markers identify investigation candidates, never a typed/scored source record.
        val scoreMarkers = Regex("(?i)pai|vitality|training[_ ]?load|recover[_ ]?time|train[_ ]?effect")
        lines += "workout_inspection.sessions.score_text_candidates=" + sessions.count {
            scoreMarkers.containsMatchIn(listOfNotNull(it.title, it.notes).joinToString(" "))
        }
        lines += "workout_inspection.sessions.score_text_import_enabled=false"
        lines += "workout_inspection.sessions.with_laps=${sessions.count { it.laps.isNotEmpty() }}"
        lines += "workout_inspection.sessions.complete_lap_distance=" + sessions.count { row ->
            val laps = row.laps.sortedBy { it.startTime }
            laps.isNotEmpty() && laps.first().startTime == row.startTime && laps.last().endTime == row.endTime &&
                laps.all { it.length != null } && laps.zipWithNext().all { (a, b) -> a.endTime == b.startTime }
        }
        lines += "workout_inspection.sessions.second_complete_lap_distance=" + sessions.count { row ->
            val laps = row.laps.sortedBy { it.startTime }
            laps.isNotEmpty() && laps.first().startTime.epochSecond == row.startTime.epochSecond &&
                laps.last().endTime.epochSecond == row.endTime.epochSecond && laps.all { it.length != null } &&
                laps.zipWithNext().all { (a, b) -> a.endTime.epochSecond == b.startTime.epochSecond }
        }
        val distances = results.getValue("distance").records.filterIsInstance<DistanceRecord>()
        lines += "workout_inspection.sessions.laps_all_have_length=" + sessions.count { row ->
            row.laps.isNotEmpty() && row.laps.all { it.length != null }
        }
        lines += "workout_inspection.distance.client_bound_sessions=" + sessions.count { row ->
            val id = row.metadata.clientRecordId
            id != null && distances.count { it.metadata.clientRecordId == id } == 1
        }
        lines += "workout_inspection.distance.same_start_unique_sessions=" + sessions.count { row ->
            distances.count { it.startTime == row.startTime } == 1
        }
        lines += "workout_inspection.distance.same_end_unique_sessions=" + sessions.count { row ->
            distances.count { it.endTime == row.endTime } == 1
        }
        lines += "workout_inspection.distance.unique_contained_whole_session=" + sessions.count { row ->
            distances.count { it.startTime >= row.startTime && it.endTime <= row.endTime } == 1
        }
        lines += "workout_inspection.distance.second_exact_sessions=" + sessions.count { row ->
            distances.count { it.startTime.epochSecond == row.startTime.epochSecond &&
                it.endTime.epochSecond == row.endTime.epochSecond } == 1
        }
        val sameStart = sessions.mapNotNull { row ->
            distances.singleOrNull { it.startTime == row.startTime }?.let { row to it }
        }
        val endRelations = sameStart.map { (row, value) ->
            val offset = Duration.between(row.endTime, value.endTime)
            when {
                offset.isZero -> "exact"
                offset.abs() < Duration.ofSeconds(1) -> "subsecond"
                offset == Duration.ofSeconds(-1) -> "one_second_before"
                offset == Duration.ofSeconds(1) -> "one_second_after"
                offset.isNegative && offset.abs() <= Duration.ofMinutes(1) -> "within_minute_before"
                offset.isNegative -> "over_minute_before"
                else -> "over_second_after"
            }
        }
        for (relation in listOf("exact", "subsecond", "one_second_before", "one_second_after",
            "within_minute_before", "over_minute_before", "over_second_after")) {
            lines += "workout_inspection.distance.same_start_end_$relation=" + endRelations.count { it == relation }
        }
        lines += "workout_inspection.distance.complete_lap_fallback_sessions=" + sessions.count { row ->
            val laps = row.laps.sortedBy { it.startTime }
            laps.isNotEmpty() && laps.first().startTime == row.startTime && laps.last().endTime == row.endTime &&
                laps.all { it.length != null } && laps.zipWithNext().all { (a, b) -> a.endTime == b.startTime } &&
                distances.none { it.startTime == row.startTime && it.endTime == row.endTime } &&
                sessions.count { it.startTime < row.endTime && it.endTime > row.startTime } == 1
        }
        val routes = sessions.mapNotNull { (it.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute }
        lines += "workout_inspection.route.with_initial_altitude=" + routes.count {
            it.route.firstOrNull()?.altitude != null
        }
        lines += "workout_inspection.sessions.with_segments=${sessions.count { it.segments.isNotEmpty() }}"
        for ((key, type) in SESSION_TYPES) {
            lines += "workout_inspection.sessions.type_$key=${sessions.count { it.exerciseType == type }}"
        }
        lines += "workout_inspection.sessions.type_other=${sessions.count { it.exerciseType !in SESSION_TYPES.values }}"
        lines += "workout_inspection.route.data=${sessions.count { it.exerciseRouteResult is ExerciseRouteResult.Data }}"
        lines += "workout_inspection.route.consent_required=" + sessions.count {
            it.exerciseRouteResult is ExerciseRouteResult.ConsentRequired
        }
        lines += "workout_inspection.route.no_data=${sessions.count { it.exerciseRouteResult is ExerciseRouteResult.NoData }}"
        lines += "workout_inspection.total_calories.use_as_active=false"
        return lines.joinToString("\n")
    }

    private suspend fun read(type: KClass<out Record>, range: TimeRangeFilter, granted: Set<String>): ReadResult {
        if (HealthPermission.getReadPermission(type) !in granted) return ReadResult("ACCESS_DENIED")
        val records = mutableListOf<Record>()
        val tokens = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        var token: String? = null
        var pages = 0
        var excluded = 0
        return try {
            do {
                val response = source.page(type, range, token)
                pages++
                check(records.size + response.records.size <= MAX_RECORDS)
                for (record in response.records) {
                    check(record::class == type)
                    if (record.metadata.dataOrigin.packageName != ZEPP_PACKAGE ||
                        record.metadata.clientRecordId?.startsWith("mifit-") == true) {
                        excluded++
                    } else {
                        check(record.metadata.id.isEmpty() || ids.add(record.metadata.id))
                        records += record
                    }
                }
                token = response.nextPageToken
                check(token == null || (tokens.add(token) && pages < MAX_PAGES))
            } while (token != null)
            ReadResult("COMPLETE", records, pages, excluded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ReadResult("ACCESS_DENIED", pages = pages)
        } catch (_: Exception) {
            // Discard partial pages so a truncated read cannot look like absent Zepp data.
            ReadResult("READ_FAILED", pages = pages)
        }
    }

    private fun association(record: Record, sessions: List<ExerciseSessionRecord>): String {
        val (start, end) = bounds(record) ?: return "OUTSIDE"
        val overlaps = sessions.filter { start < it.endTime && end > it.startTime }
        return when {
            overlaps.isEmpty() -> "OUTSIDE"
            overlaps.size > 1 -> "AMBIGUOUS"
            start >= overlaps.single().startTime && end <= overlaps.single().endTime -> "CONTAINED_ONE"
            else -> "CROSSES_BOUNDARY"
        }
    }

    private fun bounds(record: Record): Pair<Instant, Instant>? = when (record) {
        is DistanceRecord -> record.startTime to record.endTime
        is ActiveCaloriesBurnedRecord -> record.startTime to record.endTime
        is TotalCaloriesBurnedRecord -> record.startTime to record.endTime
        is ElevationGainedRecord -> record.startTime to record.endTime
        is HeartRateRecord -> record.startTime to record.endTime
        is SpeedRecord -> record.startTime to record.endTime
        is PowerRecord -> record.startTime to record.endTime
        is StepsCadenceRecord -> record.startTime to record.endTime
        is CyclingPedalingCadenceRecord -> record.startTime to record.endTime
        else -> null
    }

    private fun sampleTimes(record: Record): List<Instant> = when (record) {
        is HeartRateRecord -> record.samples.map { it.time }
        is SpeedRecord -> record.samples.map { it.time }
        is PowerRecord -> record.samples.map { it.time }
        is StepsCadenceRecord -> record.samples.map { it.time }
        is CyclingPedalingCadenceRecord -> record.samples.map { it.time }
        else -> emptyList()
    }

    private data class ReadResult(
        val status: String,
        val records: List<Record> = emptyList(),
        val pages: Int = 0,
        val excluded: Int = 0,
    )

    companion object {
        internal const val ZEPP_PACKAGE = "com.huami.watch.hmwatchmanager"
        private const val MAX_PAGES = 100
        private const val MAX_RECORDS = 100_000
        internal val TYPES: Map<String, KClass<out Record>> = linkedMapOf(
            "sessions" to ExerciseSessionRecord::class,
            "distance" to DistanceRecord::class,
            "active_calories" to ActiveCaloriesBurnedRecord::class,
            "total_calories" to TotalCaloriesBurnedRecord::class,
            "heart_rate" to HeartRateRecord::class,
            "speed" to SpeedRecord::class,
            "steps_cadence" to StepsCadenceRecord::class,
            "cycling_cadence" to CyclingPedalingCadenceRecord::class,
            "power" to PowerRecord::class,
            "elevation" to ElevationGainedRecord::class,
            "vo2_max" to Vo2MaxRecord::class,
        )
        private val SESSION_TYPES = linkedMapOf(
            "running" to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            "treadmill" to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            "cycling" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            "walking" to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            "swimming_pool" to ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        )
    }
}

internal interface WorkoutInspectionSource {
    suspend fun permissions(): Set<String>
    suspend fun page(type: KClass<out Record>, range: TimeRangeFilter, token: String?): WorkoutInspectionPage
}

internal data class WorkoutInspectionPage(val records: List<Record>, val nextPageToken: String? = null)
