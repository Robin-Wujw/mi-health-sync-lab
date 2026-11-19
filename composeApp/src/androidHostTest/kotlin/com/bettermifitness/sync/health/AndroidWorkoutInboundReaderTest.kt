package com.bettermifitness.sync.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidWorkoutInboundReaderTest {
    private val start = Instant.parse("2026-11-01T00:00:00Z")
    private val source = Source()
    private val reader = AndroidWorkoutInboundReader(source, "app-self", {})
    private val request = HealthInboundReadRequest(start.epochSecond, start.epochSecond + 86400, setOf(ZEPP))

    @Test
    fun samplesKeepUniqueSecondsAndExcludeConflictsEndBoundaryAndForeignOrigin() = runBlocking<Unit> {
        source.rows += session()
        source.rows += heart("heart", listOf(1.1 to 100L, 1.8 to 100L, 2.0 to 110L, 2.1 to 111L, 10.0 to 120L, 100.0 to 130L))
        source.rows += heart("foreign", listOf(15.0 to 250L), origin = "foreign")
        val result = reader.workouts(request).single()
        assertEquals(listOf(start.epochSecond + 1, start.epochSecond + 10), result.session.heartRateSeries.map { it.timeSec })
        assertEquals(listOf(100.0, 120.0), result.session.heartRateSeries.map { it.value })
        assertTrue("heart_rate_second_resolution" in result.omittedMetricFamilies)
        assertTrue("heart_rate_conflicting_seconds" in result.omittedMetricFamilies)
    }

    @Test
    fun exactIntervalsAreRequiredAndDailyOrDuplicateDistancesAreNotProrated() = runBlocking<Unit> {
        source.rows += session()
        source.rows += distance("daily", -100, 10000, 9000.0)
        source.rows += distance("exact", 0, 100, 123.4)
        assertEquals(123.4, reader.workouts(request).single().session.distanceMeters)
        source.rows += distance("duplicate", 0, 100, 125.0)
        val ambiguous = reader.workouts(request).single()
        assertEquals(null, ambiguous.session.distanceMeters)
        assertTrue("distance" in ambiguous.omittedMetricFamilies)
    }

    @Test
    fun overlappingSessionsLoseAmbiguousSamplesAndIntervalMetrics() = runBlocking<Unit> {
        source.rows += session(laps = listOf(lap(0, 100, 200.0)))
        source.rows += session("second", 50, 150)
        source.rows += heart("heart", listOf(10.0 to 100L, 60.0 to 120L, 120.0 to 130L))
        source.rows += distance("exact", 0, 100, 200.0)
        val results = reader.workouts(request).sortedBy { it.session.startTime }
        assertEquals(listOf(100.0), results[0].session.heartRateSeries.map { it.value })
        assertEquals(listOf(130.0), results[1].session.heartRateSeries.map { it.value })
        assertEquals(null, results[0].session.distanceMeters)
    }

    @Test
    fun completeLapsSupplyDistanceOnlyWhenExactDistanceIsAbsent() = runBlocking<Unit> {
        source.rows += session(laps = listOf(lap(0, 40, 80.0), lap(40, 100, 120.0)))
        source.rows += distance("daily", -100, 10000, 9000.0)
        assertEquals(200.0, reader.workouts(request).single().session.distanceMeters)
        source.rows += distance("exact", 0, 100, 210.0)
        assertEquals(210.0, reader.workouts(request).single().session.distanceMeters)
        source.rows += distance("conflicting", 0, 100, 220.0)
        assertEquals(null, reader.workouts(request).single().session.distanceMeters)
    }

    @Test
    fun partialAndGappedLapsNeverBecomeWholeSessionDistance() = runBlocking<Unit> {
        for (laps in listOf(listOf(lap(0, 40, 80.0)), listOf(lap(0, 40, 80.0), lap(41, 100, 120.0)))) {
            source.rows.clear()
            source.rows += session(laps = laps)
            assertEquals(null, reader.workouts(request).single().session.distanceMeters)
        }
    }

    @Test
    fun missingLapLengthIsNotFilledFromTheOtherLaps() = runBlocking<Unit> {
        source.rows += session(laps = listOf(lap(0, 40, 80.0), lap(40, 100, null)))
        assertEquals(null, reader.workouts(request).single().session.distanceMeters)
    }

    @Test
    fun pauseUnionIsDeductedAndRestIsRetained() = runBlocking<Unit> {
        source.rows += session(segments = listOf(
            ExerciseSegment(start.plusSeconds(10), start.plusSeconds(30), ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE),
            ExerciseSegment(start.plusSeconds(40), start.plusSeconds(50), ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST)))
        assertEquals(80L, reader.workouts(request).single().activeDurationSeconds)
    }

    @Test
    fun pagedNewestRevisionWinsAndEchoesAreExcluded() = runBlocking<Unit> {
        source.rows += session("same", 0, 100)
        source.rows += session("same", 0, 120, modified = 1)
        source.rows += session("echo", 200, 300, clientId = "mifit-workout-echo")
        val result = reader.workouts(request).single()
        assertEquals(start.epochSecond + 120, result.session.endTime)
        assertFalse(result.routeConsentRequired)
    }

    private fun session(id: String = "session", from: Long = 0, until: Long = 100, modified: Long = 0,
        clientId: String? = null, segments: List<ExerciseSegment> = emptyList(), laps: List<ExerciseLap> = emptyList(),
    ) = ExerciseSessionRecord(
        start.plusSeconds(from), ZoneOffset.ofHours(8), start.plusSeconds(until), ZoneOffset.ofHours(8),
        metadata(id, modified = modified, clientId = clientId), ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        segments = segments, laps = laps)

    private fun lap(from: Long, until: Long, meters: Double?) = ExerciseLap(
        start.plusSeconds(from), start.plusSeconds(until), meters?.let { Length.meters(it) })

    private fun distance(id: String, from: Long, until: Long, meters: Double) = DistanceRecord(start.plusSeconds(from), null,
        start.plusSeconds(until), null, Length.meters(meters), metadata(id))

    private fun heart(id: String, samples: List<Pair<Double, Long>>, origin: String = ZEPP) = HeartRateRecord(start.minusSeconds(10),
        null, start.plusSeconds(200), null, samples.map { (seconds, bpm) ->
            HeartRateRecord.Sample(start.plusNanos((seconds * 1e9).toLong()), bpm) }, metadata(id, origin))

    private fun metadata(id: String, origin: String = ZEPP, modified: Long = 0, clientId: String? = null): Metadata =
        Metadata::class.java.declaredConstructors.single { it.parameterCount == 7 }
            .newInstance(0, id, DataOrigin(origin), Instant.ofEpochSecond(modified), clientId, 0L, null) as Metadata

    private class Source : WorkoutInboundSource {
        val rows = mutableListOf<Record>()
        override suspend fun permissions() = AndroidWorkoutInspection.TYPES.values.map { HealthPermission.getReadPermission(it) }.toSet()
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Record> page(type: KClass<T>, request: HealthInboundReadRequest, token: String?): HealthReadPage<T> {
            val matching = rows.filter { it::class == type }
            val offset = token?.toInt() ?: 0
            return HealthReadPage(matching.drop(offset).take(1) as List<T>, (offset + 1).takeIf { it < matching.size }?.toString())
        }
    }

    private companion object { const val ZEPP = "com.huami.watch.hmwatchmanager" }
}
