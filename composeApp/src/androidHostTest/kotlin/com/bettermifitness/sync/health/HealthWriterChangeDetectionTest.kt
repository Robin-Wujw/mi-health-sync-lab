package com.bettermifitness.sync.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord as HcStepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import com.bettermifitness.sync.LocalizedTest
import com.bettermifitness.sync.data.api.ActiveCaloriesSample
import com.bettermifitness.sync.data.api.BloodPressureSample
import com.bettermifitness.sync.data.api.DistanceSample
import com.bettermifitness.sync.data.api.HeartRateSample
import com.bettermifitness.sync.data.api.HrvAlgorithm
import com.bettermifitness.sync.data.api.HrvSample
import com.bettermifitness.sync.data.api.SleepSession
import com.bettermifitness.sync.data.api.SleepStage
import com.bettermifitness.sync.data.api.SpO2Sample
import com.bettermifitness.sync.data.api.StepsRecord
import com.bettermifitness.sync.data.api.TemperatureSample
import com.bettermifitness.sync.data.api.Vo2MaxSample
import com.bettermifitness.sync.data.api.WeightMeasurement
import com.bettermifitness.sync.data.api.WorkoutRoutePoint
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.api.WorkoutTimedSample
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

/**
 * Drives the real [HealthWriter] and its record builders against a fake Health Connect that
 * follows the documented upsert rule, so a dropped or needless write shows up in the stored data.
 */
class HealthWriterChangeDetectionTest : LocalizedTest() {
    private val store = FakeHealthConnect()
    private val writer by lazy { HealthWriter(RuntimeEnvironment.getApplication()) { store } }

    @Test
    fun unchangedData_isWrittenOnlyOnce() = runBlocking<Unit> {
        val syncs = linkedMapOf<String, suspend () -> Unit>(
            "heart rate" to { writer.writeHeartRate(listOf(heartRate(T0, 70), heartRate(T0 + 60, 72))) },
            "resting heart rate" to { writer.writeRestingHeartRate(listOf(heartRate(T0, 55))) },
            "sleep" to { writer.writeSleep(listOf(sleep())) },
            "steps" to { writer.writeSteps(listOf(steps(1_000))) },
            "distance" to { writer.writeDistance(listOf(distance(800.0))) },
            "calories" to { writer.writeActiveCalories(listOf(calories(50.0))) },
            "spo2" to { writer.writeSpO2(listOf(SpO2Sample(T0, 97))) },
            "weight" to { writer.writeWeight(listOf(weight(72.5))) },
            "running" to { writer.writeWorkouts(listOf(fullWorkout())) },
            "cycling" to { writer.writeWorkouts(listOf(fullWorkout().copy(activityType = "outdoor_riding"))) },
            "blood pressure" to { writer.writeBloodPressure(listOf(BloodPressureSample(T0, 120, 80))) },
            "temperature" to { writer.writeTemperature(listOf(TemperatureSample(T0, 36.6, 33.0))) },
            "vo2 max" to { writer.writeVo2Max(listOf(Vo2MaxSample(T0, 45.0))) },
            "hrv" to { writer.writeHrv(listOf(HrvSample(T0, 42.0, algorithm = HrvAlgorithm.RMSSD))) },
        )
        for ((metric, sync) in syncs) {
            store.clear()
            sync()
            assertTrue(store.inserted.isNotEmpty(), "$metric: the first sync must write")
            store.inserted.clear()
            sync()
            assertEquals(emptyList(), store.inserted, "$metric: an identical sync must not write")
        }
    }

    @Test
    fun everyRecordOfAWorkoutAndABodyMeasurement_carriesAFingerprint() = runBlocking<Unit> {
        writer.writeWeight(listOf(weight(72.5)))
        writer.writeWorkouts(listOf(fullWorkout()))
        writer.writeTemperature(listOf(TemperatureSample(T0, 36.6, 33.0)))

        assertEquals(
            emptyList(),
            store.all().filter { stampedContentFingerprint(it.metadata.clientRecordVersion) == null },
        )
        assertEquals(
            setOf(
                WeightRecord::class, BodyFatRecord::class, BoneMassRecord::class, BodyWaterMassRecord::class,
                LeanBodyMassRecord::class, BasalMetabolicRateRecord::class, ExerciseSessionRecord::class,
                DistanceRecord::class, ActiveCaloriesBurnedRecord::class, HcStepsRecord::class,
                ElevationGainedRecord::class, HeartRateRecord::class, SpeedRecord::class, StepsCadenceRecord::class,
                PowerRecord::class, BodyTemperatureRecord::class, SkinTemperatureRecord::class,
            ),
            store.all().map { it::class }.toSet(),
        )
    }

    @Test
    fun correctedLowerValues_replaceWhatIsStored() = runBlocking<Unit> {
        writer.writeRestingHeartRate(listOf(heartRate(T0, 60)))
        writer.writeRestingHeartRate(listOf(heartRate(T0, 52)))
        assertEquals(52L, store.single<RestingHeartRateRecord>().beatsPerMinute)

        // Workout overlap subtraction lowers an hour's steps after they were first written.
        writer.writeSteps(listOf(steps(5_000)))
        writer.writeSteps(listOf(steps(1_200)))
        assertEquals(1_200L, store.single<HcStepsRecord>().count)

        writer.writeDistance(listOf(distance(4_000.0)))
        writer.writeDistance(listOf(distance(900.0)))
        assertEquals(900.0, store.single<DistanceRecord>().distance.inMeters, 0.001)

        writer.writeSpO2(listOf(SpO2Sample(T0, 99)))
        writer.writeSpO2(listOf(SpO2Sample(T0, 95)))
        assertEquals(95.0, store.single<OxygenSaturationRecord>().percentage.value, 0.001)
    }

    @Test
    fun anyChangedValue_isWritten() = runBlocking<Unit> {
        val changes = listOf(
            Change(
                "heart rate value",
                { writer.writeHeartRate(listOf(heartRate(T0, 70), heartRate(T0 + 60, 72))) },
                { writer.writeHeartRate(listOf(heartRate(T0, 70), heartRate(T0 + 60, 73))) },
            ),
            Change(
                "heart rate time",
                { writer.writeHeartRate(listOf(heartRate(T0 + 60, 70))) },
                { writer.writeHeartRate(listOf(heartRate(T0 + 61, 70))) },
            ),
            Change(
                "time zone",
                { writer.writeRestingHeartRate(listOf(heartRate(T0, 55, tz = 32))) },
                { writer.writeRestingHeartRate(listOf(heartRate(T0, 55, tz = 28))) },
            ),
            Change(
                "sleep stage",
                { writer.writeSleep(listOf(sleep(stage = 2))) },
                { writer.writeSleep(listOf(sleep(stage = 3))) },
            ),
            Change(
                "sleep end",
                { writer.writeSleep(listOf(sleep(end = T0 + 7_200))) },
                { writer.writeSleep(listOf(sleep(end = T0 + 7_500))) },
            ),
            Change(
                "body fat",
                { writer.writeWeight(listOf(weight(72.5, fat = 20.0))) },
                { writer.writeWeight(listOf(weight(72.5, fat = 21.0))) },
            ),
            Change(
                "diastolic",
                { writer.writeBloodPressure(listOf(BloodPressureSample(T0, 120, 80))) },
                { writer.writeBloodPressure(listOf(BloodPressureSample(T0, 120, 81))) },
            ),
            Change(
                "skin temperature",
                { writer.writeTemperature(listOf(TemperatureSample(T0, skinCelsius = 33.0))) },
                { writer.writeTemperature(listOf(TemperatureSample(T0, skinCelsius = 33.5))) },
            ),
            Change(
                "calories",
                { writer.writeActiveCalories(listOf(calories(50.0))) },
                { writer.writeActiveCalories(listOf(calories(49.5))) },
            ),
            Change(
                "workout note",
                { writer.writeWorkouts(listOf(summaryWorkout().copy(trainLoad = 40.0))) },
                { writer.writeWorkouts(listOf(summaryWorkout().copy(trainLoad = 41.0))) },
            ),
            Change(
                "route point",
                { writer.writeWorkouts(listOf(fullWorkout())) },
                {
                    val workout = fullWorkout()
                    writer.writeWorkouts(listOf(workout.copy(route = workout.route.map { it.copy(longitude = 120.5) })))
                },
            ),
            Change(
                "workout distance",
                { writer.writeWorkouts(listOf(summaryWorkout())) },
                { writer.writeWorkouts(listOf(summaryWorkout().copy(distanceMeters = 4_900.0))) },
            ),
            Change(
                "workout speed sample",
                { writer.writeWorkouts(listOf(fullWorkout())) },
                { writer.writeWorkouts(listOf(fullWorkout().copy(speedSeries = series(3.5)))) },
            ),
        )
        for (change in changes) {
            store.clear()
            change.before()
            store.inserted.clear()
            change.after()
            assertTrue(store.inserted.isNotEmpty(), "${change.field}: a changed value must be written")
        }
    }

    @Test
    fun onlyTheChangedRecordsOfABatch_areWritten() = runBlocking<Unit> {
        writer.writeSteps(listOf(steps(100, hour = 0), steps(200, hour = 1), steps(300, hour = 2)))
        store.inserted.clear()

        writer.writeSteps(listOf(steps(100, hour = 0), steps(250, hour = 1), steps(300, hour = 2), steps(400, hour = 3)))

        assertEquals(
            listOf(HealthRecordIds.steps(hourStart(1)), HealthRecordIds.steps(hourStart(3))),
            store.inserted.map { it.metadata.clientRecordId },
        )
    }

    @Test
    fun fullerHeartRateWindow_replacesAPartialOne() = runBlocking<Unit> {
        writer.writeHeartRate(listOf(heartRate(T0, 70), heartRate(T0 + 60, 71)))
        writer.writeHeartRate(listOf(heartRate(T0, 70), heartRate(T0 + 60, 71), heartRate(T0 + 120, 72)))
        assertEquals(3, store.single<HeartRateRecord>().samples.size)
    }

    @Test
    fun partialHeartRateWindow_neverReplacesAFullerOne() = runBlocking<Unit> {
        writer.writeHeartRate(listOf(heartRate(T0, 70), heartRate(T0 + 60, 71), heartRate(T0 + 120, 72)))
        writer.writeHeartRate(listOf(heartRate(T0 + 120, 75)))
        assertEquals(listOf(70L, 71L, 72L), store.single<HeartRateRecord>().samples.map { it.beatsPerMinute })
    }

    @Test
    fun workoutResyncedWithoutItsDetails_keepsEverythingStored() = runBlocking<Unit> {
        writer.writeWorkouts(listOf(fullWorkout()))
        val before = store.all().associate { it.metadata.clientRecordId to it.metadata.clientRecordVersion }
        store.inserted.clear()

        // What a sync produces when every FDS download fails and heart rate cannot be read.
        writer.writeWorkouts(listOf(summaryWorkout()))

        assertEquals(emptyList(), store.inserted)
        assertEquals(before, store.all().associate { it.metadata.clientRecordId to it.metadata.clientRecordVersion })
        assertEquals(ROUTE_POINTS, store.single<ExerciseSessionRecord>().routePoints())
    }

    @Test
    fun workoutWithFewerHeartRateSamples_keepsTheFullerSeries() = runBlocking<Unit> {
        writer.writeWorkouts(listOf(fullWorkout()))
        val hrId = HealthRecordIds.workout(T0) + ":hr"
        val fullSize = store.byId<HeartRateRecord>(hrId).samples.size

        writer.writeWorkouts(listOf(fullWorkout().let { it.copy(heartRateSeries = it.heartRateSeries.takeLast(2)) }))

        assertEquals(fullSize, store.byId<HeartRateRecord>(hrId).samples.size)
    }

    @Test
    fun summaryWorkout_isFilledInWhenItsDetailsArrive() = runBlocking<Unit> {
        writer.writeWorkouts(listOf(summaryWorkout()))
        assertEquals(0, store.single<ExerciseSessionRecord>().routePoints())

        writer.writeWorkouts(listOf(fullWorkout()))

        assertEquals(ROUTE_POINTS, store.single<ExerciseSessionRecord>().routePoints())
        assertEquals(10, store.byId<HeartRateRecord>(HealthRecordIds.workout(T0) + ":hr").samples.size)
    }

    @Test
    fun workoutCorrectionWithTheSameDetail_isWritten() = runBlocking<Unit> {
        writer.writeWorkouts(listOf(fullWorkout()))
        writer.writeWorkouts(listOf(fullWorkout().copy(endTime = T0 + 3_700)))
        assertEquals(T0 + 3_700, store.single<ExerciseSessionRecord>().endTime.epochSecond)
    }

    @Test
    fun workoutWithoutARoute_keepsItsFullerNotes() = runBlocking<Unit> {
        val detailed = summaryWorkout().copy(trainLoad = 40.0, recoverMinutes = 60)
        writer.writeWorkouts(listOf(detailed))

        writer.writeWorkouts(listOf(detailed.copy(recoverMinutes = null)))

        assertEquals(WorkoutNotes.build(detailed), store.single<ExerciseSessionRecord>().notes)
    }

    @Test
    fun moreWorkoutNotesCannotReplaceASavedRoute() = runBlocking<Unit> {
        val summary = summaryWorkout()
        val routed = summary.copy(route = fullWorkout().route.take(2))
        writer.writeWorkouts(listOf(routed))
        val manyNotes = summary.copy(trainLoad = 40.0, recoverMinutes = 60, avgPowerWatts = 100.0)
        writer.writeWorkouts(listOf(manyNotes))
        assertEquals(2, store.single<ExerciseSessionRecord>().routePoints())
    }

    @Test
    fun routeUsesOnlyUniquePointsInsideHalfOpenWorkoutInterval() = runBlocking<Unit> {
        val workout = summaryWorkout()
        fun point(time: Long) = WorkoutRoutePoint(time, 31.0, 121.0)
        val route = listOf(point(workout.endTime), point(T0 + 30), point(T0 - 1), point(T0), point(T0 + 30),
            point(T0 + 10).copy(latitude = Double.NaN))
        writer.writeWorkouts(listOf(workout.copy(route = route)))
        val written = assertIs<ExerciseRouteResult.Data>(store.single<ExerciseSessionRecord>().exerciseRouteResult)
        assertEquals(listOf(T0, T0 + 30), written.exerciseRoute.route.map { it.time.epochSecond })
    }

    @Test
    fun invalidRouteReportsPartialWriteWithoutBlockingOtherWorkouts() = runBlocking<Unit> {
        val workout = summaryWorkout()
        val invalid = workout.copy(route = listOf(
            WorkoutRoutePoint(workout.startTime - 1, 31.0, 121.0),
            WorkoutRoutePoint(workout.endTime, 31.0, 121.0),
        ))
        val next = workout.copy(startTime = workout.startTime + 86_400, endTime = workout.endTime + 86_400)
        val failure = assertFailsWith<HealthWriteIncompleteException> { writer.writeWorkouts(listOf(invalid, next)) }
        assertTrue(failure.skippedReasons.any { "fewer than two valid points" in it })
        assertEquals(2, store.inserted.filterIsInstance<ExerciseSessionRecord>().size)
    }

    @Test
    fun overlapUsesStoredWorkoutChildrenEvenWhenCurrentSyncDoesNotWriteWorkouts() = runBlocking<Unit> {
        val workout = summaryWorkout()
        writer.writeWorkouts(listOf(workout))
        writer.writeSteps(listOf(steps(100)))
        val from = kotlin.time.Instant.fromEpochSeconds(workout.startTime + 1_800)
        val until = kotlin.time.Instant.fromEpochSeconds(workout.endTime)
        val counts = assertNotNull(writer.readOwnWorkoutContributions(WorkoutContributionMetric.STEPS, from, until))
        assertEquals(workout.totalSteps, counts.single().totalSteps)
        assertEquals(workout.startTime, counts.single().startTime)
        val distance = assertNotNull(writer.readOwnWorkoutContributions(WorkoutContributionMetric.DISTANCE, from, until))
        val calories = assertNotNull(writer.readOwnWorkoutContributions(WorkoutContributionMetric.CALORIES, from, until))
        assertEquals(workout.distanceMeters, distance.single().distanceMeters)
        assertEquals(workout.caloriesKcal, calories.single().caloriesKcal)
        assertEquals(emptyList(), writer.readOwnWorkoutContributions(WorkoutContributionMetric.STEPS, until,
            kotlin.time.Instant.fromEpochSeconds(workout.endTime + 1_800)))
    }

    @Test
    fun sleepWithoutStages_neverReplacesOneWithStages() = runBlocking<Unit> {
        writer.writeSleep(listOf(sleep()))
        writer.writeSleep(listOf(SleepSession(T0, T0 + 7_200, tzIn15Min = 32)))
        assertEquals(1, store.single<SleepSessionRecord>().stages.size)
    }

    @Test
    fun sleepRevisedToFewerStages_isACorrection() = runBlocking<Unit> {
        val deepThenLight = sleep().copy(
            stages = listOf(SleepStage(T0, T0 + 3_600, 2), SleepStage(T0 + 3_600, T0 + 7_200, 3)),
        )
        writer.writeSleep(listOf(deepThenLight))
        assertEquals(2, store.single<SleepSessionRecord>().stages.size)

        writer.writeSleep(listOf(sleep()))

        assertEquals(1, store.single<SleepSessionRecord>().stages.size)
    }

    @Test
    fun recordDeletedFromHealthConnect_isWrittenAgain() = runBlocking<Unit> {
        writer.writeRestingHeartRate(listOf(heartRate(T0, 55)))
        store.clear()
        writer.writeRestingHeartRate(listOf(heartRate(T0, 55)))
        assertEquals(55L, store.single<RestingHeartRateRecord>().beatsPerMinute)
    }

    @Test
    fun recordFromAnOlderVersion_isUpdatedOnceAndThenLeftAlone() = runBlocking<Unit> {
        store.seed(legacyRestingHeartRate(55))

        writer.writeRestingHeartRate(listOf(heartRate(T0, 55)))
        assertEquals(1, store.inserted.size)
        val stamped = store.single<RestingHeartRateRecord>()
        assertTrue(stamped.metadata.clientRecordVersion > LEGACY_VERSION)
        assertNotNull(stampedContentFingerprint(stamped.metadata.clientRecordVersion))

        store.inserted.clear()
        writer.writeRestingHeartRate(listOf(heartRate(T0, 55)))
        assertEquals(emptyList(), store.inserted)
    }

    @Test
    fun fullerWindowFromAnOlderVersion_isNotReplacedByAPartialCopy() = runBlocking<Unit> {
        store.seed(legacyHeartRateWindow(listOf(70, 71, 72)))
        writer.writeHeartRate(listOf(heartRate(T0 + 120, 75)))
        assertEquals(LEGACY_VERSION, store.single<HeartRateRecord>().metadata.clientRecordVersion)
    }

    @Test
    fun failedComparisonReportsIncompleteInsteadOfOverwriting() = runBlocking<Unit> {
        writer.writeRestingHeartRate(listOf(heartRate(T0, 55)))
        store.readFailure = SecurityException("not allowed")
        store.inserted.clear()

        val failure = assertFailsWith<HealthWriteIncompleteException> {
            writer.writeRestingHeartRate(listOf(heartRate(T0, 57)))
        }
        assertEquals(0, failure.writtenRecordCount)
        assertEquals(emptyList(), store.inserted)
    }

    @Test
    fun cancellationWhileComparing_stopsTheWrite() = runBlocking<Unit> {
        store.readFailure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { writer.writeRestingHeartRate(listOf(heartRate(T0, 55))) }
        assertEquals(emptyList(), store.inserted)
    }

    @Test
    fun comparison_readsOnlyThisAppsRecords_acrossEveryCandidate() = runBlocking<Unit> {
        writer.writeSleep(listOf(sleep(), sleep(start = T0 + 86_400, end = T0 + 86_400 + 7_200)))

        val request = store.readRequests.single()
        assertEquals(SleepSessionRecord::class, request.recordType)
        assertEquals(setOf(DataOrigin(RuntimeEnvironment.getApplication().packageName)), request.dataOriginFilter)
        assertTrue(assertNotNull(request.timeRangeFilter.startTime) <= Instant.ofEpochSecond(T0))
        assertTrue(assertNotNull(request.timeRangeFilter.endTime) > Instant.ofEpochSecond(T0 + 86_400 + 7_200))
    }

    @Test
    fun comparison_followsEveryPageOfStoredRecords() = runBlocking<Unit> {
        store.pageSize = 2
        val rows = List(5) { steps(100, hour = it) }
        writer.writeSteps(rows)
        store.inserted.clear()
        store.readRequests.clear()

        writer.writeSteps(rows)

        assertEquals(emptyList(), store.inserted)
        assertEquals(3, store.readRequests.size)
    }

    @Test
    fun missingWritePermission_isStillReportedForChangedRecords() = runBlocking<Unit> {
        store.granted = emptySet()
        val failure = assertFailsWith<HealthWriteIncompleteException> {
            writer.writeRestingHeartRate(listOf(heartRate(T0, 55)))
        }
        assertEquals(0, failure.writtenRecordCount)
        assertEquals(1, failure.skippedReasons.size)
    }

    @Test
    fun unchangedRecords_doNotCountAsWritten() = runBlocking<Unit> {
        store.granted -= HealthPermission.getWritePermission(BodyFatRecord::class)
        writer.writeWeight(listOf(WeightMeasurement(T0, 72.5)))

        val failure = assertFailsWith<HealthWriteIncompleteException> {
            writer.writeWeight(listOf(WeightMeasurement(T0, 72.5, bodyFatPercent = 20.0)))
        }

        assertEquals(0, failure.writtenRecordCount)
    }

    private class Change(val field: String, val before: suspend () -> Unit, val after: suspend () -> Unit)

    private fun heartRate(time: Long, bpm: Int, tz: Int? = 32) = HeartRateSample(time, bpm, tzIn15Min = tz)

    private fun sleep(start: Long = T0, end: Long = T0 + 7_200, stage: Int = 2) = SleepSession(
        startTime = start,
        endTime = end,
        stages = listOf(SleepStage(start, end, stage)),
        tzIn15Min = 32,
    )

    private fun hourStart(hour: Int) = T0 - T0 % 3_600 + hour * 3_600L

    private fun steps(count: Int, hour: Int = 0) =
        StepsRecord(date = hourStart(hour).toString(), steps = count, tzIn15Min = 32)

    private fun distance(meters: Double) = DistanceSample(T0, T0 + 3_599, meters, 32)

    private fun calories(kcal: Double) = ActiveCaloriesSample(T0, T0 + 3_599, kcal, 32)

    private fun weight(kg: Double, fat: Double = 20.0) = WeightMeasurement(
        timestamp = T0,
        weightKg = kg,
        bodyFatPercent = fat,
        boneMassKg = 3.0,
        basalMetabolismKcal = 1_600.0,
        tzIn15Min = 32,
        bodyWaterMassKg = 40.0,
        leanBodyMassKg = 55.0,
    )

    private fun summaryWorkout() = WorkoutSession(
        startTime = T0,
        endTime = T0 + 3_600,
        activityType = "outdoor_running",
        distanceMeters = 5_000.0,
        caloriesKcal = 300.0,
        totalSteps = 6_000,
        elevationGainM = 20.0,
        tzIn15Min = 32,
    )

    private fun fullWorkout() = summaryWorkout().copy(
        trainLoad = 40.0,
        recoverMinutes = 60,
        route = List(ROUTE_POINTS) { i ->
            WorkoutRoutePoint(T0 + i * 60L, 31.0 + i / 1_000.0, 121.0, altitudeMeters = 5.0, horizontalAccuracyMeters = 3.0)
        },
        heartRateSeries = series(150.0),
        speedSeries = series(3.0),
        cadenceSeries = series(170.0),
        powerWattsSeries = series(250.0),
    )

    private fun series(value: Double) = List(10) { i -> WorkoutTimedSample(T0 + i * 60L, value + i / 10.0) }

    private fun legacyRestingHeartRate(bpm: Long) = RestingHeartRateRecord(
        time = Instant.ofEpochSecond(T0),
        zoneOffset = ZoneOffset.ofHours(8),
        beatsPerMinute = bpm,
        metadata = Metadata.unknownRecordingMethod(HealthRecordIds.restingHeartRate(T0), LEGACY_VERSION),
    )

    private fun legacyHeartRateWindow(bpms: List<Long>) = HeartRateRecord(
        startTime = Instant.ofEpochSecond(T0),
        startZoneOffset = ZoneOffset.ofHours(8),
        endTime = Instant.ofEpochSecond(T0 + 121),
        endZoneOffset = ZoneOffset.ofHours(8),
        samples = bpms.mapIndexed { i, bpm -> HeartRateRecord.Sample(Instant.ofEpochSecond(T0 + i * 60L), bpm) },
        metadata = Metadata.unknownRecordingMethod(
            HealthRecordIds.heartRateWindow(HealthRecordIds.heartRateWindowStart(T0)),
            LEGACY_VERSION,
        ),
    )

    private fun ExerciseSessionRecord.routePoints(): Int =
        (exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute?.route?.size ?: 0

    private companion object {
        /** 2023-11-14T22:15:00Z, the start of a five-minute heart-rate window. */
        const val T0 = 1_700_000_100L
        const val ROUTE_POINTS = 10

        /** A bare millisecond clock value, as healthlab.18 wrote it. */
        const val LEGACY_VERSION = 1_800_000_000_000L
    }

    /** Applies the documented rule: an upsert only happens when its version is higher than the stored one. */
    private class FakeHealthConnect : HealthConnectClient {
        private val stored = linkedMapOf<Pair<KClass<out Record>, String>, Record>()
        val inserted = mutableListOf<Record>()
        val readRequests = mutableListOf<ReadRecordsRequest<*>>()
        var readFailure: Exception? = null
        var pageSize = 1_000
        var granted: Set<String> = ALL_WRITE_PERMISSIONS

        fun all(): List<Record> = stored.values.toList()

        fun clear() {
            stored.clear()
            inserted.clear()
        }

        fun seed(record: Record) {
            stored[record::class to record.metadata.clientRecordId!!] = record
        }

        inline fun <reified R : Record> single(): R = all().filterIsInstance<R>().single()

        inline fun <reified R : Record> byId(id: String): R =
            all().filterIsInstance<R>().single { it.metadata.clientRecordId == id }

        override val permissionController = object : PermissionController {
            override suspend fun getGrantedPermissions(): Set<String> = granted
            override suspend fun revokeAllPermissions() = Unit
        }

        override val features = object : HealthConnectFeatures {
            override fun getFeatureStatus(feature: Int): Int = HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }

        override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
            inserted += records
            for (record in records) {
                val key = record::class to record.metadata.clientRecordId!!
                val existing = stored[key]
                if (existing == null || record.metadata.clientRecordVersion > existing.metadata.clientRecordVersion) {
                    stored[key] = record
                }
            }
            return InsertRecordsResponse(records.map { it.metadata.clientRecordId!! })
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R : Record> readRecords(request: ReadRecordsRequest<R>): ReadRecordsResponse<R> {
            readRequests += request
            readFailure?.let { throw it }
            val matching = stored.values.filter { it::class == request.recordType } as List<R>
            val offset = request.pageToken?.toInt() ?: 0
            val next = offset + pageSize
            return ReadRecordsResponse(matching.drop(offset).take(pageSize), next.takeIf { it < matching.size }?.toString())
        }

        override suspend fun updateRecords(records: List<Record>) {
            unused()
        }

        override suspend fun deleteRecords(
            recordType: KClass<out Record>,
            recordIdsList: List<String>,
            clientRecordIdsList: List<String>,
        ) {
            unused()
        }

        override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) {
            unused()
        }

        override suspend fun <R : Record> readRecord(recordType: KClass<R>, recordId: String): ReadRecordResponse<R> = unused()
        override suspend fun aggregate(request: AggregateRequest): AggregationResult = unused()
        override suspend fun aggregateGroupByDuration(
            request: AggregateGroupByDurationRequest,
        ): List<AggregationResultGroupedByDuration> = unused()
        override suspend fun aggregateGroupByPeriod(
            request: AggregateGroupByPeriodRequest,
        ): List<AggregationResultGroupedByPeriod> = unused()
        override suspend fun getChangesToken(request: ChangesTokenRequest): String = unused()
        override suspend fun getChanges(changesToken: String): ChangesResponse = unused()

        private fun unused(): Nothing = error("not used when writing records")

        private companion object {
            val ALL_WRITE_PERMISSIONS: Set<String> = listOf(
                HeartRateRecord::class, RestingHeartRateRecord::class, SleepSessionRecord::class, HcStepsRecord::class,
                DistanceRecord::class, ActiveCaloriesBurnedRecord::class, OxygenSaturationRecord::class,
                WeightRecord::class, BodyFatRecord::class, BodyWaterMassRecord::class, BoneMassRecord::class,
                LeanBodyMassRecord::class, BasalMetabolicRateRecord::class, ExerciseSessionRecord::class,
                ElevationGainedRecord::class, SpeedRecord::class, StepsCadenceRecord::class,
                CyclingPedalingCadenceRecord::class, PowerRecord::class, BloodPressureRecord::class,
                BodyTemperatureRecord::class, SkinTemperatureRecord::class, Vo2MaxRecord::class,
                HeartRateVariabilityRmssdRecord::class,
            ).map { HealthPermission.getWritePermission(it) }.toSet() + HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
        }
    }
}
