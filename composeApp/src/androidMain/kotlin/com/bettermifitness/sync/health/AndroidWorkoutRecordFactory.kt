package com.bettermifitness.sync.health

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord as HcStepsRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import com.bettermifitness.sync.data.api.WorkoutSession
import java.time.Instant
import java.time.ZoneOffset

/**
 * Builds Health Connect records for one workout (SRP: record construction only).
 */
class AndroidWorkoutRecordFactory {

    fun buildRecords(
        session: WorkoutSession,
        revision: Long,
        includeRoute: Boolean = true,
    ): List<androidx.health.connect.client.records.Record> {
        require(revision > 0L) { "Workout records require a persisted positive revision." }
        val zo = zoneOffsetOf(session)
        val start = Instant.ofEpochSecond(session.startTime)
        val end = Instant.ofEpochSecond(session.endTime)
        val mapping = SportTypeMapper.map(session.activityType)
        val notes = WorkoutNotes.build(session)
        val route = if (includeRoute) exerciseRouteOrNull(session) else null
        val meta = stampedMetadata(
            HealthRecordIds.workout(session.startTime), revision,
            session.endTime, zo, mapping.healthConnectType, mapping.title, notes,
            route?.route?.map {
                "${it.time.epochSecond}:${it.latitude}:${it.longitude}:" +
                    "${it.altitude?.inMeters}:${it.horizontalAccuracy?.inMeters}"
            },
        )
        val out = ArrayList<androidx.health.connect.client.records.Record>()
        out += if (route != null) {
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = zo,
                endTime = end,
                endZoneOffset = zo,
                metadata = meta,
                exerciseType = mapping.healthConnectType,
                title = mapping.title,
                notes = notes,
                exerciseRoute = route,
            )
        } else {
            ExerciseSessionRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = zo,
                endZoneOffset = zo,
                exerciseType = mapping.healthConnectType,
                title = mapping.title,
                notes = notes,
                metadata = meta,
            )
        }

        session.distanceMeters?.takeIf { it > 0 }?.let { d ->
            out += DistanceRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = zo,
                endZoneOffset = zo,
                distance = Length.meters(d),
                metadata = stampedMetadata(
                    HealthRecordIds.workout(session.startTime) + ":dist", revision,
                    session.endTime, zo, d,
                ),
            )
        }
        session.caloriesKcal?.takeIf { it > 0 }?.let { c ->
            out += ActiveCaloriesBurnedRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = zo,
                endZoneOffset = zo,
                energy = Energy.kilocalories(c),
                metadata = stampedMetadata(
                    HealthRecordIds.workout(session.startTime) + ":kcal", revision,
                    session.endTime, zo, c,
                ),
            )
        }
        session.totalSteps?.takeIf { it > 0 }?.let { steps ->
            out += HcStepsRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = zo,
                endZoneOffset = zo,
                count = steps.toLong(),
                metadata = stampedMetadata(
                    HealthRecordIds.workout(session.startTime) + ":steps", revision,
                    session.endTime, zo, steps,
                ),
            )
        }
        session.elevationGainM?.takeIf { it > 0 }?.let { gain ->
            try {
                out += ElevationGainedRecord(
                    startTime = start,
                    endTime = end,
                    startZoneOffset = zo,
                    endZoneOffset = zo,
                    elevation = Length.meters(gain),
                    metadata = stampedMetadata(
                        HealthRecordIds.workout(session.startTime) + ":elev", revision,
                        session.endTime, zo, gain,
                    ),
                )
            } catch (_: Exception) { /* optional */ }
        }
        addHeartRate(out, session, zo, revision)
        addSpeed(out, session, zo, revision)
        addCadence(out, session, zo, revision)
        addPower(out, session, zo, revision)
        return out
    }

    private fun addHeartRate(
        out: MutableList<androidx.health.connect.client.records.Record>,
        session: WorkoutSession,
        zo: ZoneOffset,
        revision: Long,
    ) {
        if (session.heartRateSeries.size < 2) return
        val samples = session.heartRateSeries.map {
            HeartRateRecord.Sample(
                time = Instant.ofEpochSecond(it.timeSec),
                beatsPerMinute = it.value.toLong().coerceIn(30, 250),
            )
        }
        val hrStart = Instant.ofEpochSecond(session.heartRateSeries.first().timeSec)
        val hrEnd = Instant.ofEpochSecond(session.heartRateSeries.last().timeSec).plusSeconds(1)
        if (!hrEnd.isAfter(hrStart)) return
        out += HeartRateRecord(
            startTime = hrStart,
            endTime = hrEnd,
            startZoneOffset = zo,
            endZoneOffset = zo,
            samples = samples,
            metadata = stampedMetadata(
                HealthRecordIds.workout(session.startTime) + ":hr", revision,
                zo, session.heartRateSeries.map { "${it.timeSec}:${it.value}" },
            ),
        )
    }

    private fun addSpeed(
        out: MutableList<androidx.health.connect.client.records.Record>,
        session: WorkoutSession,
        zo: ZoneOffset,
        revision: Long,
    ) {
        val series = WorkoutFormSeries.speed(session)
        if (series.size < 2) return
        try {
            val samples = series.map {
                SpeedRecord.Sample(
                    time = Instant.ofEpochSecond(it.timeSec),
                    speed = Velocity.metersPerSecond(it.value.coerceIn(0.0, 15.0)),
                )
            }
            val s0 = Instant.ofEpochSecond(series.first().timeSec)
            val s1 = Instant.ofEpochSecond(series.last().timeSec).plusSeconds(1)
            if (!s1.isAfter(s0)) return
            out += SpeedRecord(
                startTime = s0,
                endTime = s1,
                startZoneOffset = zo,
                endZoneOffset = zo,
                samples = samples,
                metadata = stampedMetadata(
                    HealthRecordIds.workout(session.startTime) + ":spd", revision,
                    zo, series.map { "${it.timeSec}:${it.value}" },
                ),
            )
        } catch (_: Exception) { /* optional */ }
    }

    /**
     * Cadence by sport (Health Connect data types):
     * - Cycling → [CyclingPedalingCadenceRecord] (RPM)
     * - Run / walk / other foot sports → [StepsCadenceRecord] (steps/min)
     * There is no separate "running cadence" type beyond steps cadence.
     */
    private fun addCadence(
        out: MutableList<androidx.health.connect.client.records.Record>,
        session: WorkoutSession,
        zo: ZoneOffset,
        revision: Long,
    ) {
        val series = WorkoutFormSeries.cadence(session)
        if (series.size < 2) return
        val s0 = Instant.ofEpochSecond(series.first().timeSec)
        val s1 = Instant.ofEpochSecond(series.last().timeSec).plusSeconds(1)
        if (!s1.isAfter(s0)) return
        val family = WorkoutMetricFamilies.classify(session.activityType)
        try {
            if (family == WorkoutMetricFamily.CYCLING) {
                val samples = series.map {
                    CyclingPedalingCadenceRecord.Sample(
                        time = Instant.ofEpochSecond(it.timeSec),
                        revolutionsPerMinute = it.value.coerceIn(0.0, 300.0),
                    )
                }
                out += CyclingPedalingCadenceRecord(
                    startTime = s0,
                    endTime = s1,
                    startZoneOffset = zo,
                    endZoneOffset = zo,
                    samples = samples,
                    metadata = stampedMetadata(
                        HealthRecordIds.workout(session.startTime) + ":pcad", revision,
                        zo, series.map { "${it.timeSec}:${it.value}" },
                    ),
                )
            } else {
                val samples = series.map {
                    StepsCadenceRecord.Sample(
                        time = Instant.ofEpochSecond(it.timeSec),
                        rate = it.value.coerceIn(0.0, 300.0),
                    )
                }
                out += StepsCadenceRecord(
                    startTime = s0,
                    endTime = s1,
                    startZoneOffset = zo,
                    endZoneOffset = zo,
                    samples = samples,
                    metadata = stampedMetadata(
                        HealthRecordIds.workout(session.startTime) + ":cad", revision,
                        zo, series.map { "${it.timeSec}:${it.value}" },
                    ),
                )
            }
        } catch (_: Exception) { /* optional on older HC */ }
    }

    private fun addPower(
        out: MutableList<androidx.health.connect.client.records.Record>,
        session: WorkoutSession,
        zo: ZoneOffset,
        revision: Long,
    ) {
        val series = session.powerWattsSeries
        if (series.size < 2) return
        try {
            val samples = series.map {
                PowerRecord.Sample(
                    time = Instant.ofEpochSecond(it.timeSec),
                    power = Power.watts(it.value.coerceIn(0.0, 2000.0)),
                )
            }
            val s0 = Instant.ofEpochSecond(series.first().timeSec)
            val s1 = Instant.ofEpochSecond(series.last().timeSec).plusSeconds(1)
            if (!s1.isAfter(s0)) return
            out += PowerRecord(
                startTime = s0,
                endTime = s1,
                startZoneOffset = zo,
                endZoneOffset = zo,
                samples = samples,
                metadata = stampedMetadata(
                    HealthRecordIds.workout(session.startTime) + ":pow", revision,
                    zo, series.map { "${it.timeSec}:${it.value}" },
                ),
            )
        } catch (_: Exception) { /* optional */ }
    }

    private fun exerciseRouteOrNull(session: WorkoutSession): ExerciseRoute? {
        if (session.route.size < 2) return null
        return try {
            val locations = session.route.map { p ->
                ExerciseRoute.Location(
                    time = Instant.ofEpochSecond(p.timeSec),
                    latitude = p.latitude,
                    longitude = p.longitude,
                    horizontalAccuracy = p.horizontalAccuracyMeters?.let { Length.meters(it) },
                    verticalAccuracy = null,
                    altitude = p.altitudeMeters?.let { Length.meters(it) },
                )
            }
            ExerciseRoute(locations)
        } catch (_: Exception) {
            null
        }
    }

    private fun zoneOffsetOf(session: WorkoutSession): ZoneOffset =
        ZoneOffsetResolver.fromMiOrSystem(
            tzIn15Min = session.tzIn15Min,
            gpsTzIn15Min = session.gpsTzIn15Min,
            at = Instant.ofEpochSecond(session.startTime),
        )
}
