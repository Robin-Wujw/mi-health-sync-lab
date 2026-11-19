package com.bettermifitness.sync.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class AndroidWorkoutInspectionTest {
    private val start = Instant.parse("2026-11-01T00:00:00Z")
    private val source = FakeSource()
    private val inspector = AndroidWorkoutInspection(source)

    @Test
    fun deniedReadIsDifferentFromCompletedEmptyRead() = runBlocking<Unit> {
        source.granted -= HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val report = inspect()
        assertLine(report, "sessions.status", "ACCESS_DENIED")
        assertLine(report, "distance.status", "COMPLETE")
        assertLine(report, "distance.records", 0)
        assertFalse(ExerciseSessionRecord::class in source.requested)
        assertFalse(report.contains("distance.contained_one_records="))
    }

    @Test
    fun readsEveryPageAndReportsSessionKindsWithoutValues() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += session(300, 400)
        val report = inspect()
        assertLine(report, "sessions.records", 2)
        assertLine(report, "sessions.pages", 2)
        assertLine(report, "sessions.type_running", 2)
        assertLine(report, "route.no_data", 2)
        assertLine(report, "cloud_requests", 0)
        assertLine(report, "import_ready", false)
        assertFalse(report.contains("2026-11"))
        assertFalse(report.contains("private-id"))
    }

    @Test
    fun excludesOtherOriginsAndEchoedMiRecords() = runBlocking<Unit> {
        source.rows += session(100, 200, origin = "com.xiaomi.wearable")
        source.rows += session(300, 400, clientId = "mifit-workout-private")
        assertLine(inspect(), "sessions.records", 0)
        assertLine(inspect(), "sessions.excluded_source_or_loop", 2)
    }

    @Test
    fun onlyContainedUnambiguousIntervalsAreCandidateAssociations() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += distance(100, 200)
        source.rows += distance(50, 150)
        source.rows += distance(200, 250)
        val report = inspect()
        assertLine(report, "distance.contained_one_records", 1)
        assertLine(report, "distance.crosses_boundary_records", 1)
        assertLine(report, "distance.outside_records", 1)
        assertLine(report, "association", "temporal_only_not_proven")
    }

    @Test
    fun overlappingSessionsPreventGuessingAnOwner() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += session(150, 250)
        source.rows += distance(160, 190)
        val report = inspect()
        assertLine(report, "distance.ambiguous_records", 1)
        assertLine(report, "distance.contained_one_records", 0)
    }

    @Test
    fun samplesAreClippedUsingHalfOpenSessionBoundaries() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += HeartRateRecord(start, null, start.plusSeconds(300), null,
            listOf(99L, 100L, 199L, 200L).map { HeartRateRecord.Sample(start.plusSeconds(it), 73) }, metadata("heart"))
        val report = inspect()
        assertLine(report, "heart_rate.crosses_boundary_records", 1)
        assertLine(report, "heart_rate.samples_in_one_session", 2)
        assertFalse(report.contains("73"))
    }

    @Test
    fun samplesInsideTwoSessionsAreNotCountedAsUniquelyAssociated() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += session(150, 250)
        source.rows += HeartRateRecord(start, null, start.plusSeconds(300), null,
            listOf(100L, 160L, 220L).map { HeartRateRecord.Sample(start.plusSeconds(it), 73) }, metadata("heart"))
        assertLine(inspect(), "heart_rate.samples_in_one_session", 2)
    }

    @Test
    fun dailyTotalCaloriesAreNeverRelabeledAsActiveWorkoutCalories() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += TotalCaloriesBurnedRecord(start, null, start.plusSeconds(86_400), null,
            Energy.kilocalories(2000.0), metadata("total"))
        source.rows += ActiveCaloriesBurnedRecord(start.plusSeconds(100), null, start.plusSeconds(200), null,
            Energy.kilocalories(27.0), metadata("active"))
        val report = inspect()
        assertLine(report, "total_calories.crosses_boundary_records", 1)
        assertLine(report, "active_calories.contained_one_records", 1)
        assertLine(report, "total_calories.use_as_active", false)
    }

    @Test
    fun crossingWindowSessionsAreReportedButNotUsedAsCompleteWorkouts() = runBlocking<Unit> {
        source.rows += session(-100, 200)
        source.rows += distance(100, 150)
        val report = inspect()
        assertLine(report, "sessions.crossing_window_records", 1)
        assertLine(report, "sessions.complete_window_records", 0)
        assertLine(report, "distance.contained_one_records", 0)
    }

    @Test
    fun repeatedPageTokenDiscardsPartialRead() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += session(300, 400)
        source.repeatToken = true
        val report = inspect()
        assertLine(report, "sessions.status", "READ_FAILED")
        assertLine(report, "sessions.records", 0)
        assertFalse(report.contains("distance.contained_one_records="))
    }

    @Test
    fun duplicateRecordOnDifferentPagesIsNotCountedTwice() = runBlocking<Unit> {
        val row = session(100, 200)
        source.rows += listOf(row, row)
        assertLine(inspect(), "sessions.status", "READ_FAILED")
        assertLine(inspect(), "sessions.records", 0)
    }

    @Test
    fun revokedPermissionAndReadFailureDoNotLookLikeEmptyData() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += session(300, 400)
        source.secondPageFailure = SecurityException("private failure details")
        assertLine(inspect(), "sessions.status", "ACCESS_DENIED")
        source.secondPageFailure = IllegalStateException("private failure details")
        val report = inspect()
        assertLine(report, "sessions.status", "READ_FAILED")
        assertLine(report, "sessions.records", 0)
        assertFalse(report.contains("private failure details"))
    }

    @Test
    fun cancellationPropagates() = runBlocking<Unit> {
        source.rows += session(100, 200)
        source.rows += session(300, 400)
        source.secondPageFailure = CancellationException()
        assertFailsWith<CancellationException> { inspect() }
    }

    @Test
    fun refusesInvalidWindowsBeforeReading() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { inspector.inspect(start, start) }
        assertFailsWith<IllegalArgumentException> { inspector.inspect(start, start.plusSeconds(28 * 86_400L)) }
        assertEquals(emptyList(), source.requested)
    }

    @Test
    fun routeConsentRequiredIsDifferentFromNoRoute() = runBlocking<Unit> {
        val routeResults = listOf(ExerciseRouteResult.ConsentRequired(), ExerciseRouteResult.Data(
            ExerciseRoute(listOf(ExerciseRoute.Location(start.plusSeconds(110), 1.0, 2.0))),
        ))
        for ((index, result) in routeResults.withIndex()) {
            // Provider-read constructor preserves consent state without requesting or granting it.
            val row = ExerciseSessionRecord::class.java.declaredConstructors.single {
                it.parameterCount == 12 && it.parameterTypes[10] == ExerciseRouteResult::class.java
            }.newInstance(start.plusSeconds(100), null, start.plusSeconds(200), null, metadata("route-$index"),
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, null, null, emptyList<Any>(), emptyList<Any>(), result, null)
            source.rows += row as ExerciseSessionRecord
        }
        val report = inspect()
        assertLine(report, "route.data", 1)
        assertLine(report, "route.consent_required", 1)
        assertLine(report, "route.no_data", 0)
        assertLine(report, "sessions.missing_zone", 2)
    }

    @Test
    fun sessionOffsetChangeIsVisibleWithoutAssumingPhoneTimezone() = runBlocking<Unit> {
        source.rows += ExerciseSessionRecord(start.plusSeconds(100), ZoneOffset.ofHours(8),
            start.plusSeconds(200), ZoneOffset.ofHours(9), metadata("zone"), ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)
        assertLine(inspect(), "sessions.offset_changed", 1)
    }

    private suspend fun inspect() = inspector.inspect(start, start.plusSeconds(86_400))

    private fun assertLine(report: String, key: String, value: Any) {
        assertEquals("workout_inspection.$key=$value", report.lineSequence().single { it.startsWith("workout_inspection.$key=") })
    }

    private fun session(from: Long, until: Long, origin: String = AndroidWorkoutInspection.ZEPP_PACKAGE, clientId: String? = null) =
        ExerciseSessionRecord(start.plusSeconds(from), ZoneOffset.UTC, start.plusSeconds(until), ZoneOffset.UTC,
            metadata("private-id-$from", origin, clientId), ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)

    private fun distance(from: Long, until: Long) = DistanceRecord(start.plusSeconds(from), null, start.plusSeconds(until), null,
        Length.meters(100.0), metadata("distance-$from"))

    // Simulates provider-assigned metadata, which apps cannot set through the public write factories.
    private fun metadata(id: String, origin: String = AndroidWorkoutInspection.ZEPP_PACKAGE, clientId: String? = null): Metadata =
        Metadata::class.java.declaredConstructors.single { it.parameterCount == 7 }
            .newInstance(0, id, DataOrigin(origin), Instant.EPOCH, clientId, 0L, null) as Metadata

    private class FakeSource : WorkoutInspectionSource {
        var granted = AndroidWorkoutInspection.TYPES.values.map { HealthPermission.getReadPermission(it) }.toSet()
        val rows = mutableListOf<Record>()
        val requested = mutableListOf<KClass<out Record>>()
        var repeatToken = false
        var secondPageFailure: Exception? = null

        override suspend fun permissions() = granted

        override suspend fun page(type: KClass<out Record>, range: TimeRangeFilter, token: String?): WorkoutInspectionPage {
            requested += type
            if (token != null) secondPageFailure?.let { throw it }
            val matching = rows.filter { it::class == type }
            val offset = token?.toInt() ?: 0
            val hasNext = offset + 1 < matching.size || repeatToken && matching.isNotEmpty()
            val next = if (hasNext) "${if (repeatToken) 1 else offset + 1}" else null
            return WorkoutInspectionPage(matching.drop(offset).take(1), next)
        }
    }
}
