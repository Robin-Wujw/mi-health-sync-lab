package com.bettermifitness.sync.health

import com.bettermifitness.sync.i18n.L10n
import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
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
import com.bettermifitness.sync.data.time.MiTimezone
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord as HcStepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.bettermifitness.sync.data.api.ActiveCaloriesSample
import com.bettermifitness.sync.data.api.BloodPressureSample
import com.bettermifitness.sync.data.api.DistanceSample
import com.bettermifitness.sync.data.api.HeartRateSample
import com.bettermifitness.sync.data.api.HrvAlgorithm
import com.bettermifitness.sync.data.api.HrvSample
import com.bettermifitness.sync.data.api.SleepSession
import com.bettermifitness.sync.data.api.SpO2Sample
import com.bettermifitness.sync.data.api.StepsRecord
import com.bettermifitness.sync.data.api.TemperatureSample
import com.bettermifitness.sync.data.api.Vo2MaxSample
import com.bettermifitness.sync.data.api.WeightMeasurement
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.preferences.SyncPreferences
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException

@Suppress("LargeClass") // The platform adapter, with one read and one write function per Health Connect record type.
actual class HealthWriter internal constructor(
    private val context: Context,
    clientProvider: () -> HealthConnectClient,
    private val routeCacheGeneration: suspend () -> Long,
) : HealthStore {
    internal constructor(context: Context, clientProvider: () -> HealthConnectClient) : this(context, clientProvider, { 0L })
    constructor(context: Context) : this(context, { HealthConnectClient.getOrCreate(context) }, { 0L })

    private val client by lazy(clientProvider)
    private val workoutRouteCache by lazy { AndroidWorkoutRouteCache(context) }
    private val changeFilter = HealthRecordChangeFilter { type, range ->
        readRecordsPaged(type, range, setOf(DataOrigin(context.packageName)))
    }

    /**
     * Write permissions for types this HC install supports.
     * Older phones (e.g. Galaxy S8 + old Health Connect) may not expose skin temperature.
     */
    private fun availableWritePermissions(): Set<String> {
        val perms = linkedSetOf(
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class),
            HealthPermission.getWritePermission(HcStepsRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getWritePermission(BodyFatRecord::class),
            HealthPermission.getWritePermission(BodyWaterMassRecord::class),
            HealthPermission.getWritePermission(BoneMassRecord::class),
            HealthPermission.getWritePermission(LeanBodyMassRecord::class),
            HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
            HealthPermission.getWritePermission(ElevationGainedRecord::class),
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.getWritePermission(StepsCadenceRecord::class),
            HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
            HealthPermission.getWritePermission(PowerRecord::class),
            HealthPermission.getWritePermission(BloodPressureRecord::class),
            HealthPermission.getWritePermission(BodyTemperatureRecord::class),
            HealthPermission.getWritePermission(Vo2MaxRecord::class),
            HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        )
        if (skinTemperatureAvailable()) {
            perms += HealthPermission.getWritePermission(SkinTemperatureRecord::class)
        }
        return perms
    }

    private fun skinTemperatureAvailable(): Boolean = try {
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (_: Exception) {
        false
    }

    private fun backgroundReadAvailable(): Boolean = try {
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (_: Exception) {
        false
    }

    private fun availableReadPermissions(): Set<String> = buildSet {
        add(HealthPermission.getReadPermission(WeightRecord::class))
        add(HealthPermission.getReadPermission(BodyFatRecord::class))
        add(HealthPermission.getReadPermission(BodyWaterMassRecord::class))
        add(HealthPermission.getReadPermission(BoneMassRecord::class))
        add(HealthPermission.getReadPermission(LeanBodyMassRecord::class))
        add(HealthPermission.getReadPermission(BasalMetabolicRateRecord::class))
        // This optional permission is unavailable on older HC providers. Its denial
        // must not block foreground imports or any Mi -> Health Connect writes.
        if (backgroundReadAvailable()) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }

    private fun availablePermissions(): Set<String> =
        availableWritePermissions() + availableReadPermissions()

    private fun hasUsablePermissions(granted: Set<String>): Boolean =
        availableWritePermissions().any {
            it != HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE && it in granted
        } ||
            HealthPermission.getReadPermission(WeightRecord::class) in granted ||
            HealthInboundMetric.entries.any { it.readPermission() in granted }

    private fun zoneOffsetToMiUnits(zoneOffset: java.time.ZoneOffset?): Int? {
        if (zoneOffset == null) return null
        val seconds = zoneOffset.totalSeconds
        val inRange = seconds in
            MiTimezone.MIN_OFFSET_SECONDS..MiTimezone.MAX_OFFSET_SECONDS
        if (!inRange) return null
        // 15-minute units
        return seconds / (15 * 60)
    }

    private fun isSelfWritten(clientRecordId: String?, packageName: String?): Boolean {
        if (clientRecordId?.startsWith("mifit-") == true) return true
        if (packageName != null && packageName == context.packageName) return true
        return false
    }

    actual override suspend fun writeHeartRate(samples: List<HeartRateSample>) {
        val sortedSamples = HealthDataNormalizer.normalizeHeartRate(samples)
        if (sortedSamples.isEmpty()) return
        val revision = nextRecordRevision()

        // Group into fixed windows so clientRecordId stays stable across re-syncs.
        val groups = sortedSamples.groupBy { HealthRecordIds.heartRateWindowStart(it.timestamp) }

        val records = groups.mapNotNull { (windowStart, group) ->
            val ordered = group.sortedBy { it.timestamp }
            val start = Instant.ofEpochSecond(ordered.first().timestamp)
            val end = Instant.ofEpochSecond(ordered.last().timestamp).plusSeconds(1)
            if (!end.isAfter(start)) return@mapNotNull null
            val zo = ZoneOffsetResolver.fromMiOrSystem(
                ordered.firstNotNullOfOrNull { it.tzIn15Min },
                start,
            )

            HeartRateRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = zo,
                endZoneOffset = zo,
                samples = ordered.map { sample ->
                    HeartRateRecord.Sample(
                        time = Instant.ofEpochSecond(sample.timestamp),
                        beatsPerMinute = sample.bpm.toLong(),
                    )
                },
                metadata = stampedMetadata(
                    HealthRecordIds.heartRateWindow(windowStart), revision,
                    zo, ordered.map { "${it.timestamp}:${it.bpm}" },
                ),
            )
        }

        insertChunked(records)
    }

    actual override suspend fun writeRestingHeartRate(samples: List<HeartRateSample>) {
        val clean = HealthDataNormalizer.normalizeHeartRate(samples)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val records = clean.map { sample ->
            val time = Instant.ofEpochSecond(sample.timestamp)
            val zo = ZoneOffsetResolver.fromMiOrSystem(sample.tzIn15Min, time)
            RestingHeartRateRecord(
                time = time,
                zoneOffset = zo,
                beatsPerMinute = sample.bpm.toLong(),
                metadata = stampedMetadata(HealthRecordIds.restingHeartRate(sample.timestamp), revision, zo, sample.bpm),
            )
        }
        insertChunked(records)
    }

    actual override suspend fun writeSleep(sessions: List<SleepSession>) {
        val clean = HealthDataNormalizer.normalizeSleep(sessions)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val records = clean.mapNotNull { session ->
            val start = Instant.ofEpochSecond(session.startTime)
            val end = Instant.ofEpochSecond(session.endTime)
            if (!end.isAfter(start)) return@mapNotNull null
            val zo = ZoneOffsetResolver.fromMiOrSystem(session.tzIn15Min, start)
            val stages = session.stages.mapNotNull { stage ->
                val s = Instant.ofEpochSecond(stage.startTime)
                val e = Instant.ofEpochSecond(stage.endTime)
                if (!e.isAfter(s)) return@mapNotNull null
                SleepSessionRecord.Stage(
                    startTime = s,
                    endTime = e,
                    stage = mapSleepStage(stage.stage),
                )
            }

            SleepSessionRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = zo,
                endZoneOffset = zo,
                stages = stages,
                metadata = stampedMetadata(
                    HealthRecordIds.sleepSession(session.startTime), revision,
                    session.endTime, zo, stages.map { "${it.startTime.epochSecond}:${it.endTime.epochSecond}:${it.stage}" },
                ),
            )
        }
        insertChunked(records)
    }

    actual override suspend fun writeSteps(records: List<StepsRecord>) {
        val clean = HealthDataNormalizer.normalizeSteps(records)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val hcRecords = clean.mapNotNull { record ->
            val ts = record.date.toLong()
            val start = Instant.ofEpochSecond(ts)
            // Clamp open hour end so HC never sees endTime in the future (issue #10 class).
            val end = minOf(start.plusSeconds(3599), Instant.now())
            if (!end.isAfter(start)) return@mapNotNull null
            val zo = ZoneOffsetResolver.fromMiOrSystem(record.tzIn15Min, start)

            HcStepsRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = zo,
                endZoneOffset = zo,
                count = record.steps.toLong(),
                // The end of the open hour follows the clock, so it does not count as a change.
                metadata = stampedMetadata(HealthRecordIds.steps(ts), revision, zo, record.steps),
            )
        }
        insertChunked(hcRecords)
    }

    actual override suspend fun writeDistance(samples: List<DistanceSample>) {
        val clean = HealthDataNormalizer.normalizeDistance(samples)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val records = clean.map { s ->
            val start = Instant.ofEpochSecond(s.startTime)
            val zo = ZoneOffsetResolver.fromMiOrSystem(s.tzIn15Min, start)
            DistanceRecord(
                startTime = start,
                endTime = Instant.ofEpochSecond(s.endTime),
                startZoneOffset = zo,
                endZoneOffset = zo,
                distance = Length.meters(s.meters),
                metadata = stampedMetadata(HealthRecordIds.distance(s.startTime), revision, zo, s.endTime, s.meters),
            )
        }
        insertChunked(records)
    }

    actual override suspend fun writeActiveCalories(samples: List<ActiveCaloriesSample>) {
        val clean = HealthDataNormalizer.normalizeActiveCalories(samples)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val records = clean.map { s ->
            val start = Instant.ofEpochSecond(s.startTime)
            val zo = ZoneOffsetResolver.fromMiOrSystem(s.tzIn15Min, start)
            ActiveCaloriesBurnedRecord(
                startTime = start,
                endTime = Instant.ofEpochSecond(s.endTime),
                startZoneOffset = zo,
                endZoneOffset = zo,
                energy = Energy.kilocalories(s.kilocalories),
                metadata = stampedMetadata(HealthRecordIds.activeCalories(s.startTime), revision, zo, s.endTime, s.kilocalories),
            )
        }
        insertChunked(records)
    }

    actual override suspend fun writeWeight(measurements: List<WeightMeasurement>) {
        val clean = HealthDataNormalizer.normalizeWeight(measurements)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val records = mutableListOf<Record>()
        for (m in clean) {
            val time = Instant.ofEpochSecond(m.timestamp)
            val zoneOffset = ZoneOffsetResolver.fromMiOrSystem(m.tzIn15Min, time)
            records += WeightRecord(
                time = time,
                zoneOffset = zoneOffset,
                weight = Mass.kilograms(m.weightKg),
                metadata = stampedMetadata(HealthRecordIds.weight(m.timestamp), revision, zoneOffset, m.weightKg),
            )
            m.bodyFatPercent?.let { fat ->
                records += BodyFatRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    percentage = Percentage(fat),
                    metadata = stampedMetadata(HealthRecordIds.bodyFat(m.timestamp), revision, zoneOffset, fat),
                )
            }
            m.boneMassKg?.let { bone ->
                records += BoneMassRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(bone),
                    metadata = stampedMetadata("mifit-bonemass-${m.timestamp}", revision, zoneOffset, bone),
                )
            }
            // HC stores mass; only convert a percentage against this same
            // measurement's weight, never against a different latest weigh-in.
            val waterKg = m.bodyWaterMassKg
                ?: m.bodyWaterPercent?.let { m.weightKg * it / 100.0 }
            waterKg?.let { water ->
                records += BodyWaterMassRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(water),
                    metadata = stampedMetadata("mifit-bodywater-${m.timestamp}", revision, zoneOffset, water),
                )
            }
            // Lean mass is distinct from muscle mass; do not substitute it.
            m.leanBodyMassKg?.let { lean ->
                records += LeanBodyMassRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(lean),
                    metadata = stampedMetadata("mifit-leanmass-${m.timestamp}", revision, zoneOffset, lean),
                )
            }
            m.basalMetabolismKcal?.let { bmr ->
                records += BasalMetabolicRateRecord(
                    time = time,
                    zoneOffset = zoneOffset,
                    basalMetabolicRate = Power.kilocaloriesPerDay(bmr),
                    metadata = stampedMetadata("mifit-bmr-${m.timestamp}", revision, zoneOffset, bmr),
                )
            }
        }
        // Central permission planning reports skipped types after writing those
        // the user allowed; optional body metrics never silently disappear.
        insertChunked(records)
    }

    override suspend fun readOwnWorkoutContributions(
        metric: WorkoutContributionMetric, from: kotlin.time.Instant, until: kotlin.time.Instant,
    ): List<WorkoutSession>? {
        val start = Instant.ofEpochSecond(from.epochSeconds)
        val end = Instant.ofEpochSecond(until.epochSeconds)
        val range = TimeRangeFilter.between(start.minusSeconds(86_400), end.plusSeconds(86_400))
        val origins = setOf(DataOrigin(context.packageName))
        val records: List<Record> = when (metric) {
            WorkoutContributionMetric.STEPS -> readRecordsPaged(HcStepsRecord::class, range, origins)
            WorkoutContributionMetric.DISTANCE -> readRecordsPaged(DistanceRecord::class, range, origins)
            WorkoutContributionMetric.CALORIES -> readRecordsPaged(ActiveCaloriesBurnedRecord::class, range, origins)
        }
        return records.mapNotNull { record ->
            val workout = when (record) {
                is HcStepsRecord -> workoutContribution(record, record.startTime, record.endTime, "steps")
                    ?.copy(totalSteps = record.count.toInt())
                is DistanceRecord -> workoutContribution(record, record.startTime, record.endTime, "dist")
                    ?.copy(distanceMeters = record.distance.inMeters)
                is ActiveCaloriesBurnedRecord -> workoutContribution(record, record.startTime, record.endTime, "kcal")
                    ?.copy(caloriesKcal = record.energy.inKilocalories)
                else -> null
            }
            workout?.takeIf { it.startTime < until.epochSeconds && it.endTime > from.epochSeconds }
        }
    }

    private fun workoutContribution(record: Record, start: Instant, end: Instant, suffix: String): WorkoutSession? {
        if (record.metadata.clientRecordId != "${HealthRecordIds.workout(start.epochSecond)}:$suffix") return null
        return WorkoutSession(startTime = start.epochSecond, endTime = end.epochSecond, activityType = "workout")
    }

    actual override suspend fun writeWorkouts(sessions: List<WorkoutSession>) {
        val clean = HealthDataNormalizer.normalizeWorkouts(sessions)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val granted = client.permissionController.getGrantedPermissions()
        val includeRoute = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE in granted
        val factory = AndroidWorkoutRecordFactory()
        var invalidWorkouts = 0
        var invalidRoutes = 0
        val batch = clean.flatMap { workout ->
            try {
                factory.buildRecords(workout, revision, includeRoute).also { records ->
                    if (includeRoute && workout.route.size >= 2 && records.filterIsInstance<ExerciseSessionRecord>()
                            .none { it.exerciseRouteResult is ExerciseRouteResult.Data }) {
                        invalidRoutes++
                    }
                }
            } catch (_: IllegalArgumentException) {
                invalidWorkouts++
                emptyList()
            }
        }
        val omittedRoutes = if (includeRoute) 0 else clean.count { it.route.size >= 2 }
        val skipped = buildList {
            if (omittedRoutes > 0) add("Exercise routes ($omittedRoutes): write access is required in Health Connect.")
            if (invalidRoutes > 0) add("Exercise routes ($invalidRoutes): fewer than two valid points within the workout.")
            if (invalidWorkouts > 0) add("Workouts ($invalidWorkouts): invalid data for Health Connect.")
        }
        insertChunked(batch, chunkSize = 50, additionalSkippedReasons = skipped)
    }

    actual override suspend fun writeSpO2(samples: List<SpO2Sample>) {
        val clean = HealthDataNormalizer.normalizeSpO2(samples)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val records = clean.map { sample ->
            val time = Instant.ofEpochSecond(sample.timestamp)
            val zo = ZoneOffsetResolver.fromMiOrSystem(sample.tzIn15Min, time)
            OxygenSaturationRecord(
                time = time,
                zoneOffset = zo,
                percentage = Percentage(sample.percentage.toDouble()),
                metadata = stampedMetadata(HealthRecordIds.spo2(sample.timestamp), revision, zo, sample.percentage),
            )
        }
        insertChunked(records)
    }

    actual override suspend fun writeBloodPressure(samples: List<BloodPressureSample>) {
        val clean = HealthDataNormalizer.normalizeBloodPressure(samples)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val records = clean.map { s ->
            val time = Instant.ofEpochSecond(s.timestamp)
            val zo = ZoneOffsetResolver.fromMiOrSystem(s.tzIn15Min, time)
            BloodPressureRecord(
                time = time,
                zoneOffset = zo,
                systolic = Pressure.millimetersOfMercury(s.systolicMmhg.toDouble()),
                diastolic = Pressure.millimetersOfMercury(s.diastolicMmhg.toDouble()),
                metadata = stampedMetadata(
                    HealthRecordIds.bloodPressure(s.timestamp), revision, zo, s.systolicMmhg, s.diastolicMmhg,
                ),
            )
        }
        insertChunked(records)
    }

    actual override suspend fun writeTemperature(samples: List<TemperatureSample>) {
        val clean = HealthDataNormalizer.normalizeTemperature(samples)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val bodyRecords = clean.mapNotNull { s ->
            val body = s.bodyCelsius ?: return@mapNotNull null
            val time = Instant.ofEpochSecond(s.timestamp)
            val zo = ZoneOffsetResolver.fromMiOrSystem(s.tzIn15Min, time)
            BodyTemperatureRecord(
                time = time,
                zoneOffset = zo,
                temperature = Temperature.celsius(body),
                metadata = stampedMetadata(HealthRecordIds.bodyTemperature(s.timestamp), revision, zo, body),
            )
        }

        // SkinTemperatureRecord needs a baseline + deltas; write as single-point baseline.
        val supportsSkin = skinTemperatureAvailable()
        val skinRecords = if (!supportsSkin) emptyList() else clean.mapNotNull { s ->
            val skin = s.skinCelsius ?: return@mapNotNull null
            val start = Instant.ofEpochSecond(s.timestamp)
            val end = start.plusSeconds(1)
            val zo = ZoneOffsetResolver.fromMiOrSystem(s.tzIn15Min, start)
            SkinTemperatureRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = zo,
                endZoneOffset = zo,
                deltas = listOf(
                    SkinTemperatureRecord.Delta(
                        time = start,
                        delta = TemperatureDelta.celsius(0.0),
                    ),
                ),
                baseline = Temperature.celsius(skin),
                metadata = stampedMetadata(HealthRecordIds.skinTemperature(s.timestamp), revision, zo, skin),
            )
        }
        val omittedSkin = if (supportsSkin) 0 else clean.count { it.skinCelsius != null }
        val skipped = if (omittedSkin == 0) emptyList() else listOf(
            "Skin temperature is unsupported by this Health Connect provider " +
                "($omittedSkin record(s) skipped). Update Health Connect to enable this data type.",
        )
        insertChunked(bodyRecords + skinRecords, additionalSkippedReasons = skipped)
    }

    actual override suspend fun writeVo2Max(samples: List<Vo2MaxSample>) {
        val clean = HealthDataNormalizer.normalizeVo2Max(samples)
        if (clean.isEmpty()) return
        val revision = nextRecordRevision()
        val records = clean.map { s ->
            val time = Instant.ofEpochSecond(s.timestamp)
            val zo = ZoneOffsetResolver.fromMiOrSystem(s.tzIn15Min, time)
            Vo2MaxRecord(
                time = time,
                zoneOffset = zo,
                vo2MillilitersPerMinuteKilogram = s.mlPerKgMin,
                measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER,
                metadata = stampedMetadata(HealthRecordIds.vo2Max(s.timestamp), revision, zo, s.mlPerKgMin),
            )
        }
        insertChunked(records)
    }

    actual override suspend fun writeHrv(samples: List<HrvSample>) {
        val clean = HrvAlgorithmPolicy.requireCompatible(
            HealthDataNormalizer.normalizeHrv(samples),
            HrvAlgorithm.RMSSD,
        )
        if (clean.isEmpty()) return
        val outOfRange = clean.count { it.hrvMs !in 1.0..200.0 }
        if (outOfRange > 0) {
            throw HealthWriteIncompleteException(
                writtenRecordCount = 0,
                skippedReasons = listOf(
                    "HRV (RMSSD): $outOfRange record(s) fall outside the Health Connect " +
                        "supported range of 1–200 ms. No HRV records were written; values were not clamped.",
                ),
            )
        }
        val revision = nextRecordRevision()
        // A millisecond unit alone does not identify the HRV algorithm. Generic
        // Mi avg_hrv and SDNN must never be relabeled as Health Connect RMSSD.
        val records = clean.map { s ->
            val time = Instant.ofEpochSecond(s.timestamp)
            val zo = ZoneOffsetResolver.fromMiOrSystem(s.tzIn15Min, time)
            HeartRateVariabilityRmssdRecord(
                time = time,
                zoneOffset = zo,
                heartRateVariabilityMillis = s.hrvMs,
                metadata = stampedMetadata(HealthRecordIds.hrv(s.timestamp), revision, zo, s.hrvMs),
            )
        }
        insertChunked(records)
    }

    actual override suspend fun readHeartRate(
        request: HealthInboundReadRequest,
    ): List<HealthInboundHeartRateRecord> = withInboundRead(HealthInboundMetric.HEART_RATE, request) {
        val records = readInboundCalendarPages(HeartRateRecord::class, request, HealthInboundMetric.HEART_RATE,
            includeIntervalBoundary = true).map { record ->
            HealthInboundHeartRateRecord(
                origin = record.metadata.toInboundOrigin(),
                startEpochMillis = record.startTime.toEpochMilli(),
                endEpochMillis = record.endTime.toEpochMilli(),
                startZoneOffsetSeconds = record.startZoneOffset?.totalSeconds,
                endZoneOffsetSeconds = record.endZoneOffset?.totalSeconds,
                samples = record.samples.map { sample ->
                    HealthInboundHeartRateSample(sample.time.toEpochMilli(), sample.beatsPerMinute)
                },
            )
        }
        HealthInboundReadPolicy.heartRate(records, request, context.packageName)
    }

    actual override suspend fun readRestingHeartRate(
        request: HealthInboundReadRequest,
    ): List<HealthInboundRestingHeartRateRecord> =
        withInboundRead(HealthInboundMetric.RESTING_HEART_RATE, request) {
            val records = readRecordsPaged(
                RestingHeartRateRecord::class,
                inboundTimeRange(request),
                request.sourcePackages.map { DataOrigin(it) }.toSet(),
                readGuard = { ensureInboundAccess(HealthInboundMetric.RESTING_HEART_RATE) },
            ).map { record ->
                HealthInboundRestingHeartRateRecord(
                    origin = record.metadata.toInboundOrigin(),
                    epochMillis = record.time.toEpochMilli(),
                    zoneOffsetSeconds = record.zoneOffset?.totalSeconds,
                    beatsPerMinute = record.beatsPerMinute,
                )
            }
            HealthInboundReadPolicy.restingHeartRate(records, request, context.packageName)
        }

    actual override suspend fun readSleep(
        request: HealthInboundReadRequest,
    ): List<HealthInboundSleepRecord> = withInboundRead(HealthInboundMetric.SLEEP, request) {
        val records = readInboundCalendarPages(SleepSessionRecord::class, request, HealthInboundMetric.SLEEP,
            includeIntervalBoundary = true).map { record ->
            HealthInboundSleepRecord(
                origin = record.metadata.toInboundOrigin(),
                startEpochMillis = record.startTime.toEpochMilli(),
                endEpochMillis = record.endTime.toEpochMilli(),
                startZoneOffsetSeconds = record.startZoneOffset?.totalSeconds,
                endZoneOffsetSeconds = record.endZoneOffset?.totalSeconds,
                stages = record.stages.map { stage ->
                    HealthInboundSleepStage(
                        startEpochMillis = stage.startTime.toEpochMilli(),
                        endEpochMillis = stage.endTime.toEpochMilli(),
                        kind = inboundSleepStage(stage.stage),
                        rawStageType = stage.stage,
                    )
                },
            )
        }
        HealthInboundReadPolicy.sleep(records, request, context.packageName)
    }

    actual override suspend fun readHrv(
        request: HealthInboundReadRequest,
    ): List<HealthInboundHrvRecord> = withInboundRead(HealthInboundMetric.HRV, request) {
        val records = readRecordsPaged(
            HeartRateVariabilityRmssdRecord::class,
            inboundTimeRange(request),
            request.sourcePackages.map { DataOrigin(it) }.toSet(),
            readGuard = { ensureInboundAccess(HealthInboundMetric.HRV) },
        ).map { record ->
            HealthInboundHrvRecord(
                origin = record.metadata.toInboundOrigin(),
                epochMillis = record.time.toEpochMilli(),
                zoneOffsetSeconds = record.zoneOffset?.totalSeconds,
                rmssdMillis = record.heartRateVariabilityMillis,
            )
        }
        HealthInboundReadPolicy.hrv(records, request, context.packageName)
    }

    actual override suspend fun readOxygenSaturation(
        request: HealthInboundReadRequest,
    ): List<HealthInboundOxygenSaturationRecord> = withInboundRead(HealthInboundMetric.OXYGEN_SATURATION, request) {
        val records = readInboundCalendarPages(OxygenSaturationRecord::class, request, HealthInboundMetric.OXYGEN_SATURATION)
            .map { record ->
            HealthInboundOxygenSaturationRecord(
                origin = record.metadata.toInboundOrigin(),
                epochMillis = record.time.toEpochMilli(),
                zoneOffsetSeconds = record.zoneOffset?.totalSeconds,
                percentage = record.percentage.value,
            )
        }
        HealthInboundReadPolicy.oxygenSaturation(records, request, context.packageName)
    }

    private suspend fun <T : Record> readInboundCalendarPages(
        type: KClass<T>, request: HealthInboundReadRequest, metric: HealthInboundMetric, includeIntervalBoundary: Boolean = false,
    ): List<T> {
        val origins = request.sourcePackages.map { DataOrigin(it) }.toSet()
        return request.calendarReadSlices().flatMap { slice ->
            readRecordsPaged(type, inboundTimeRange(slice, includeIntervalBoundary), origins,
                readGuard = { ensureInboundAccess(metric) })
        }
    }

    private fun inboundTimeRange(
        request: HealthInboundReadRequest,
        includeIntervalBoundary: Boolean = false,
    ): TimeRangeFilter {
        // Keep the previous night's full session and daily HR containers even on
        // providers that select interval records by their start. The selection
        // policy subsequently applies the exact user window without cutting sleep.
        val from = if (includeIntervalBoundary) {
            (request.fromEpochSeconds - INBOUND_BOUNDARY_LOOKBACK_SECONDS).coerceAtLeast(0L)
        } else {
            request.fromEpochSeconds
        }
        return TimeRangeFilter.between(
            Instant.ofEpochSecond(from),
            Instant.ofEpochSecond(request.untilEpochSeconds),
        )
    }

    private fun Metadata.toInboundOrigin() = HealthRecordOrigin(
        recordId = id,
        sourcePackage = dataOrigin.packageName,
        clientRecordId = clientRecordId,
        clientRecordVersion = clientRecordVersion,
        lastModifiedEpochMillis = lastModifiedTime.toEpochMilli(),
    )

    private fun inboundSleepStage(stage: Int): HealthInboundSleepStageKind = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> HealthInboundSleepStageKind.AWAKE
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> HealthInboundSleepStageKind.SLEEPING
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> HealthInboundSleepStageKind.OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> HealthInboundSleepStageKind.AWAKE_IN_BED
        SleepSessionRecord.STAGE_TYPE_LIGHT -> HealthInboundSleepStageKind.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> HealthInboundSleepStageKind.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> HealthInboundSleepStageKind.REM
        else -> HealthInboundSleepStageKind.UNKNOWN
    }

    actual override suspend fun readWorkouts(request: HealthInboundReadRequest): List<InboundWorkout> =
        withInboundRead(HealthInboundMetric.WORKOUTS, request) {
            val generation = routeCacheGeneration()
            workoutRouteCache.prune(generation)
            AndroidWorkoutInboundReader(client, context.packageName,
                { ensureInboundAccess(HealthInboundMetric.WORKOUTS) },
                { record -> when (record.exerciseRouteResult) {
                    is ExerciseRouteResult.ConsentRequired -> workoutRouteCache.read(record, generation)
                    is ExerciseRouteResult.NoData -> {
                        workoutRouteCache.discard(record)
                        null
                    }
                    else -> null
                } })
                .workouts(request)
        }

    override fun clearCachedWorkoutRoutes() = workoutRouteCache.clear()

    internal suspend fun refreshWorkoutRouteCache() {
        workoutRouteCache.prune(routeCacheGeneration())
        if (!isAvailable()) return
        try {
            observeWorkoutRoutePermissions(client.permissionController.getGrantedPermissions())
        } catch (_: SecurityException) {
            workoutRouteCache.clear()
        }
    }

    private fun observeWorkoutRoutePermissions(granted: Set<String>) {
        workoutRouteCache.observePermissions(HealthInboundMetric.WORKOUTS.readPermission() in granted,
            "android.permission.health.READ_EXERCISE_ROUTES" in granted)
    }

    actual override suspend fun requestWorkoutRoutes(request: HealthInboundReadRequest): Int =
        withInboundRead(HealthInboundMetric.WORKOUTS, request) {
            val generation = routeCacheGeneration()
            workoutRouteCache.prune(generation)
            val rows = readRecordsPaged(ExerciseSessionRecord::class, inboundTimeRange(request),
                request.sourcePackages.map { DataOrigin(it) }.toSet(),
                readGuard = { ensureInboundAccess(HealthInboundMetric.WORKOUTS) })
                .filter { HealthInboundReadPolicy.allowsOrigin(it.metadata.toInboundOrigin(), request, context.packageName) }
            var granted = 0
            for (record in rows) {
                if (routeCacheGeneration() != generation) return@withInboundRead granted
                val direct = record.exerciseRouteResult as? ExerciseRouteResult.Data
                if (direct != null) workoutRouteCache.save(record, direct.exerciseRoute, generation)
            }
            for (record in rows.filter { it.exerciseType in setOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                    ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                    ExerciseSessionRecord.EXERCISE_TYPE_HIKING) &&
                    it.exerciseRouteResult is ExerciseRouteResult.ConsentRequired &&
                    workoutRouteCache.read(it, generation) == null }.sortedByDescending { it.startTime }) {
                val launcher = HealthConnectPermissionBridge.requestRoute
                    ?: throw IllegalStateException("Open the app to authorize exercise routes.")
                val route = launcher(record.metadata.id) ?: break
                if (routeCacheGeneration() != generation) break
                ensureInboundAccess(HealthInboundMetric.WORKOUTS)
                workoutRouteCache.save(record, route, generation)
                granted++
            }
            granted
        }

    actual override suspend fun readVo2Max(request: HealthInboundReadRequest): List<InboundVo2Max> =
        withInboundRead(HealthInboundMetric.VO2_MAX, request) {
            AndroidWorkoutInboundReader(client, context.packageName, { ensureInboundAccess(HealthInboundMetric.VO2_MAX) })
                .vo2Max(request)
        }

    private fun HealthInboundMetric.readPermission(): String = when (this) {
        HealthInboundMetric.HEART_RATE -> HealthPermission.getReadPermission(HeartRateRecord::class)
        HealthInboundMetric.RESTING_HEART_RATE -> HealthPermission.getReadPermission(RestingHeartRateRecord::class)
        HealthInboundMetric.SLEEP -> HealthPermission.getReadPermission(SleepSessionRecord::class)
        HealthInboundMetric.HRV -> HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
        HealthInboundMetric.OXYGEN_SATURATION -> HealthPermission.getReadPermission(OxygenSaturationRecord::class)
        HealthInboundMetric.WORKOUTS -> HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        HealthInboundMetric.VO2_MAX -> HealthPermission.getReadPermission(Vo2MaxRecord::class)
    }

    override suspend fun blockedSyncMetrics(enabled: Set<String>, from: kotlin.time.Instant): Map<String, HealthAccessBlock> {
        val healthKeys = enabled.intersect(SyncPreferences.ALL_METRIC_KEYS + SyncPreferences.INBOUND_METRIC_KEYS)
        if (healthKeys.isEmpty()) return emptyMap()
        if (!isAvailable()) return healthKeys.associateWith { HealthAccessBlock.UNAVAILABLE }
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return healthKeys.associateWith { HealthAccessBlock.CHECK_FAILED }
        }
        val foreground = isAppForeground()
        val backgroundFeature = foreground || backgroundReadAvailable()
        val needsHistory = from.epochSeconds < Instant.now().epochSecond - INBOUND_LOOKBACK_DAYS * 86_400L
        if ("workouts" in healthKeys || SyncPreferences.WORKOUTS_IMPORT_KEY in healthKeys) observeWorkoutRoutePermissions(granted)
        return healthKeys.mapNotNull { key ->
            val inbound = HealthInboundMetric.entries.firstOrNull { "${it.metricKey}_import_experimental" == key }
            val block = if (inbound != null) {
                val required = if (inbound == HealthInboundMetric.HRV) listOf(inbound, HealthInboundMetric.SLEEP) else listOf(inbound)
                required.firstNotNullOfOrNull { metric ->
                    androidExternalReadBlock(granted, metric.readPermission(), foreground, backgroundFeature, needsHistory)
                }
            } else {
                val required = exportPermissions(key)
                HealthAccessBlock.WRITE_PERMISSION.takeIf { required.isNotEmpty() && required.none { it in granted } }
            }
            block?.let { key to it }
        }.toMap()
    }

    private fun exportPermissions(key: String): Set<String> {
        val record = when (key) {
            "heart_rate" -> HeartRateRecord::class
            "resting_heart_rate" -> RestingHeartRateRecord::class
            "sleep" -> SleepSessionRecord::class
            "hrv" -> HeartRateVariabilityRmssdRecord::class
            "steps" -> HcStepsRecord::class
            "distance" -> DistanceRecord::class
            "active_calories" -> ActiveCaloriesBurnedRecord::class
            "spo2" -> OxygenSaturationRecord::class
            "workouts" -> ExerciseSessionRecord::class
            "blood_pressure" -> BloodPressureRecord::class
            "temperature" -> BodyTemperatureRecord::class
            "vo2_max" -> Vo2MaxRecord::class
            else -> return emptySet()
        }
        return if (key == "temperature" && skinTemperatureAvailable()) {
            setOf(HealthPermission.getWritePermission(record), HealthPermission.getWritePermission(SkinTemperatureRecord::class))
        } else setOf(HealthPermission.getWritePermission(record))
    }

    private fun isAppForeground(): Boolean = HealthConnectPermissionBridge.hasVisibleActivity &&
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

    private suspend fun ensureInboundAccess(metric: HealthInboundMetric): Set<String> {
        val granted = client.permissionController.getGrantedPermissions()
        if (metric == HealthInboundMetric.WORKOUTS) observeWorkoutRoutePermissions(granted)
        val foreground = isAppForeground()
        androidExternalReadBlock(granted, metric.readPermission(), foreground, foreground || backgroundReadAvailable())?.let {
            throw HealthInboundReadUnavailableException(metric, it.message(), accessBlock = it)
        }
        return granted
    }

    private suspend fun <T> withInboundRead(
        metric: HealthInboundMetric,
        request: HealthInboundReadRequest,
        read: suspend () -> T,
    ): T {
        if (!isAvailable()) {
            throw HealthInboundReadUnavailableException(metric, "Health Connect is unavailable for importing data.")
        }
        try {
            val granted = ensureInboundAccess(metric)
            // The real HC limit is 30 days before the first grant, not a rolling
            // window. We do not know that grant date, so this initial importer
            // deliberately accepts only a safe recent range without history
            // access. The additional 36-hour interval lookback still fits in 30 days.
            val safeEarliest = Instant.now().epochSecond - INBOUND_LOOKBACK_DAYS * 86_400L
            if (request.fromEpochSeconds < safeEarliest &&
                HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY !in granted
            ) {
                throw HealthInboundReadUnavailableException(
                    metric,
                    L10n.text(L10n.inboundHistoryRequired),
                    accessBlock = HealthAccessBlock.HISTORY_PERMISSION,
                )
            }
            return read()
        } catch (e: SecurityException) {
            if (metric == HealthInboundMetric.WORKOUTS) workoutRouteCache.clear()
            throw HealthInboundReadUnavailableException(
                metric,
                "Health Connect denied reading ${L10n.metric(metric.metricKey)}. " +
                    "Review read permissions and retry while the app is open. No empty-data result was recorded.",
                e,
                accessBlock = HealthAccessBlock.READ_PERMISSION,
            )
        }
    }

    actual override suspend fun readLatestWeight(): WeightMeasurement? {
        if (!isAvailable()) {
            val reason = HealthAccessBlock.UNAVAILABLE
            throw HealthWeightReadUnavailableException(reason.message(), accessBlock = reason)
        }
        return try {
            val granted = ensureBodyReadAccess(WeightRecord::class)
            val now = Instant.now()
            // No history permission is requested. Stay inside HC's default 30-day
            // window instead of issuing an EPOCH query that can fail authorization.
            val records = readRecordsPaged(
                WeightRecord::class,
                TimeRangeFilter.between(now.minus(WEIGHT_LOOKBACK_DAYS, ChronoUnit.DAYS), now),
                readGuard = { ensureBodyReadAccess(WeightRecord::class) },
            )
            val r = records.asSequence()
                .filterNot { isSelfWritten(it.metadata.clientRecordId, it.metadata.dataOrigin.packageName) }
                .filter { it.weight.inKilograms in 1.0..500.0 }
                .maxWithOrNull(compareBy<WeightRecord> { it.time }.thenBy { it.metadata.lastModifiedTime })
                ?: return null
            val ts = r.time.epochSecond
            val kg = r.weight.inKilograms
            val fat = readAssociatedBodyValue(r, granted, BodyFatRecord::class, { it.time }) {
                it.percentage.value
            }
            val bone = readAssociatedBodyValue(r, granted, BoneMassRecord::class, { it.time }) {
                it.mass.inKilograms
            }
            val water = readAssociatedBodyValue(r, granted, BodyWaterMassRecord::class, { it.time }) {
                it.mass.inKilograms
            }
            val lean = readAssociatedBodyValue(r, granted, LeanBodyMassRecord::class, { it.time }) {
                it.mass.inKilograms
            }
            val bmr = readAssociatedBodyValue(r, granted, BasalMetabolicRateRecord::class, { it.time }) {
                it.basalMetabolicRate.inKilocaloriesPerDay
            }
            WeightMeasurement(
                timestamp = ts,
                weightKg = kg,
                bodyFatPercent = fat,
                boneMassKg = bone,
                bodyWaterMassKg = water,
                bodyWaterPercent = water?.let { it / kg * 100.0 },
                leanBodyMassKg = lean,
                basalMetabolismKcal = bmr,
                tzIn15Min = zoneOffsetToMiUnits(r.zoneOffset),
                sourcePackage = r.metadata.dataOrigin.packageName,
            ).let { listOf(it) }.let { HealthDataNormalizer.normalizeWeight(it).firstOrNull() }
        } catch (e: SecurityException) {
            // A denied/revoked read is not an empty HC store. The caller may still
            // run Mi -> HC, but must surface the reverse-sync failure separately.
            throw HealthWeightReadUnavailableException(
                "Health Connect denied reading weight or body composition. " +
                    "Review the app's read permissions and retry in the foreground.",
                e,
                accessBlock = HealthAccessBlock.READ_PERMISSION,
            )
        }
    }

    private suspend fun ensureBodyReadAccess(recordType: KClass<out Record>): Set<String> {
        val granted = client.permissionController.getGrantedPermissions()
        val foreground = isAppForeground()
        androidExternalReadBlock(granted, HealthPermission.getReadPermission(recordType),
            foreground, foreground || backgroundReadAvailable())?.let {
            throw HealthWeightReadUnavailableException(it.message(), accessBlock = it)
        }
        return granted
    }

    private suspend fun <T : Record> readAssociatedBodyValue(
        weight: WeightRecord,
        granted: Set<String>,
        recordType: KClass<T>,
        time: (T) -> Instant,
        value: (T) -> Double,
    ): Double? {
        if (HealthPermission.getReadPermission(recordType) !in granted) return null
        val source = weight.metadata.dataOrigin.packageName
        if (source.isBlank()) return null
        // Query a narrow window, then match exact Instants (including nanos).
        // A nearby value from another weigh-in or app must never be attached.
        val candidates = readRecordsPaged(
            recordType,
            TimeRangeFilter.between(weight.time.minusSeconds(1), weight.time.plusSeconds(1)),
            setOf(DataOrigin(source)),
            readGuard = { ensureBodyReadAccess(recordType) },
        ).filterNot { isSelfWritten(it.metadata.clientRecordId, it.metadata.dataOrigin.packageName) }
            .map { record ->
                val measurementTime = time(record)
                BodyCompositionValue(
                    measurement = BodyMeasurementKey(
                        measurementTime.epochSecond,
                        measurementTime.nano,
                        record.metadata.dataOrigin.packageName,
                    ),
                    value = value(record),
                    modifiedEpochSeconds = record.metadata.lastModifiedTime.epochSecond,
                    modifiedNanoseconds = record.metadata.lastModifiedTime.nano,
                )
            }
        return BodyCompositionMatcher.latestValue(
            BodyMeasurementKey(weight.time.epochSecond, weight.time.nano, source),
            candidates,
        )
    }

    private suspend fun <T : Record> readRecordsPaged(
        recordType: KClass<T>,
        timeRangeFilter: TimeRangeFilter,
        dataOrigins: Set<DataOrigin> = emptySet(),
        readGuard: suspend () -> Unit = {},
    ): List<T> = collectHealthReadPages { pageToken ->
        readGuard()
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = timeRangeFilter,
                dataOriginFilter = dataOrigins,
                ascendingOrder = false,
                pageSize = 1_000,
                pageToken = pageToken,
            ),
        )
        // Some HC providers return only our own data in the background instead
        // of throwing. Recheck so losing foreground access cannot masquerade
        // as a successful empty/partial read from the selected external source.
        readGuard()
        HealthReadPage(response.records, response.pageToken)
    }

    actual override suspend fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    actual override suspend fun hasWritePermissions(): Boolean {
        if (!isAvailable()) return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            hasUsablePermissions(granted)
        } catch (_: Exception) {
            false
        }
    }

    actual override suspend fun requestPermissions() {
        val launcher = HealthConnectPermissionBridge.requestPermissions
            ?: throw IllegalStateException(L10n.text(L10n.healthPermissionsIncomplete))
        val needed = availablePermissions()
        if (needed.isEmpty()) {
            throw Exception(L10n.text(L10n.healthNotAvailable))
        }
        val granted = launcher(needed)
        if (!hasUsablePermissions(granted)) {
            throw Exception(L10n.text(L10n.healthPermissionsIncomplete))
        }
    }

    actual override suspend fun requestReadPermissions(
        metrics: Set<HealthInboundMetric>,
        includeBackground: Boolean,
        includeHistory: Boolean,
    ): Set<HealthInboundMetric> {
        if (metrics.isEmpty()) return emptySet()
        if (!isAvailable()) {
            throw HealthInboundReadUnavailableException(null, "Health Connect is unavailable for importing data.")
        }
        val needed = metrics.map { it.readPermission() }.toMutableSet()
        if (HealthInboundMetric.WORKOUTS in metrics) {
            needed += listOf(DistanceRecord::class, ActiveCaloriesBurnedRecord::class, HeartRateRecord::class,
                SpeedRecord::class, StepsCadenceRecord::class, CyclingPedalingCadenceRecord::class,
                PowerRecord::class, ElevationGainedRecord::class).map { HealthPermission.getReadPermission(it) }
        }
        if (includeHistory) {
            val historyAvailable = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            if (!historyAvailable) throw HealthInboundReadUnavailableException(null,
                L10n.text(L10n.inboundHistoryUnavailable))
            needed += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        }
        if (includeBackground && backgroundReadAvailable()) {
            needed += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        }
        val previouslyGranted = client.permissionController.getGrantedPermissions()
        val missing = needed - previouslyGranted
        if (missing.isNotEmpty()) {
            val launcher = HealthConnectPermissionBridge.requestPermissions
                ?: throw HealthInboundReadUnavailableException(
                    null,
                    "Open Better Mi Fitness Sync to grant read access for the enabled imports.",
                )
            // No write permissions, weight permissions, or unselected metrics
            // belong in this prompt. A denial never enables an inbound metric.
            launcher(missing)
        }
        val granted = client.permissionController.getGrantedPermissions()
        if (includeHistory && HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY !in granted)
            throw HealthInboundReadUnavailableException(null, L10n.text(L10n.inboundHistoryRequired))
        return metrics.filterTo(linkedSetOf()) { it.readPermission() in granted }
    }

    actual override fun healthServiceName(): String = "Health Connect"

    actual override suspend fun availabilityHint(): String? {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE ->
                if (hasWritePermissions()) {
                    null
                } else {
                    L10n.textFmt(L10n.homeAllowAccessDetail, healthServiceName())
                }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                L10n.text(L10n.healthNotAvailable)
            else ->
                L10n.text(L10n.healthNotAvailable)
        }
    }

    actual override fun openHealthService() {
        HealthConnectPermissionBridge.openHealthConnect?.invoke()
    }

    /** Uses the shared raw Mi state mapping so Android and iOS stay consistent. */
    private fun mapSleepStage(stage: Int): Int =
        when (HealthDataNormalizer.miSleepStageKind(stage)) {
            HealthDataNormalizer.MiSleepStageKind.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
            HealthDataNormalizer.MiSleepStageKind.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
            HealthDataNormalizer.MiSleepStageKind.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
            HealthDataNormalizer.MiSleepStageKind.REM -> SleepSessionRecord.STAGE_TYPE_REM
            HealthDataNormalizer.MiSleepStageKind.UNKNOWN -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
        }

    /**
     * Write only new or changed records of the types whose permissions were granted,
     * but report partial completion explicitly. A caller must never checkpoint a
     * filtered batch as complete. IDs and revisions stay unchanged across transport chunks.
     */
    private suspend fun insertChunked(
        records: List<Record>,
        chunkSize: Int = HEALTH_WRITE_CHUNK_SIZE,
        additionalSkippedReasons: List<String> = emptyList(),
    ) {
        if (records.isEmpty() && additionalSkippedReasons.isEmpty()) return
        val changed = changeFilter.changedOnly(records)
        val granted = client.permissionController.getGrantedPermissions()
        val plan = planHealthWrites(
            candidates = changed.records.map { record ->
                HealthWriteCandidate(
                    record = record,
                    label = recordLabel(record),
                    requiredPermissions = setOf(HealthPermission.getWritePermission(record::class)),
                )
            },
            grantedPermissions = granted,
            missingAccessMessage = { label, count ->
                "$label ($count): ${L10n.text(L10n.healthPermissionsIncomplete)}"
            },
        )
        val skipped = changed.skippedReasons + plan.skippedReasons + additionalSkippedReasons
        var written = 0
        try {
            plan.writableRecords.chunked(chunkSize).forEach { chunk ->
                client.insertRecords(chunk)
                written += chunk.size
            }
        } catch (e: SecurityException) {
            throw HealthWriteIncompleteException(
                writtenRecordCount = written,
                skippedReasons = skipped +
                    L10n.text(L10n.healthPermissionsIncomplete),
                cause = e,
            )
        }
        if (skipped.isNotEmpty()) {
            throw HealthWriteIncompleteException(written, skipped)
        }
    }

    private fun recordLabel(record: Record): String = when (record) {
        is HeartRateRecord -> L10n.metric("heart_rate")
        is RestingHeartRateRecord -> L10n.metric("resting_heart_rate")
        is SleepSessionRecord -> L10n.metric("sleep")
        is HcStepsRecord -> L10n.metric("steps")
        is DistanceRecord -> L10n.metric("distance")
        is ActiveCaloriesBurnedRecord -> L10n.metric("active_calories")
        is WeightRecord -> L10n.metric("weight")
        is BodyFatRecord -> "Body fat"
        is BoneMassRecord -> "Bone mass"
        is BodyWaterMassRecord -> "Body water mass"
        is LeanBodyMassRecord -> "Lean body mass"
        is BasalMetabolicRateRecord -> "Basal metabolic rate"
        is OxygenSaturationRecord -> L10n.metric("spo2")
        is BloodPressureRecord -> L10n.metric("blood_pressure")
        is BodyTemperatureRecord -> "Body temperature"
        is SkinTemperatureRecord -> "Skin temperature"
        is Vo2MaxRecord -> L10n.metric("vo2_max")
        is HeartRateVariabilityRmssdRecord -> "${L10n.metric("hrv")} (RMSSD)"
        is ExerciseSessionRecord -> L10n.metric("workouts")
        is ElevationGainedRecord -> "Elevation gain"
        is SpeedRecord -> "Speed"
        is StepsCadenceRecord -> "Step cadence"
        is CyclingPedalingCadenceRecord -> "Cycling cadence"
        is PowerRecord -> "Power"
        else -> record::class.simpleName ?: "Health record"
    }

    private fun nextRecordRevision(): Long = synchronized(RECORD_VERSION_LOCK) {
        // Keep the previous body-only preference name so its revision survives
        // upgrades, and now allocate every Android record type from this clock.
        val prefs = context.getSharedPreferences("hc_body_record_versions", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_version", 0L)
        val next = nextHealthRecordRevision(last, System.currentTimeMillis())
        check(prefs.edit().putLong("last_version", next).commit()) {
            "Could not persist the Health Connect record revision."
        }
        next
    }

    companion object {
        /** Transport batch size for Health Connect inserts (workouts use 50 for route-heavy batches). */
        const val HEALTH_WRITE_CHUNK_SIZE = 300
        private const val WEIGHT_LOOKBACK_DAYS = 28L
        private const val INBOUND_LOOKBACK_DAYS = 28L
        private const val INBOUND_BOUNDARY_LOOKBACK_SECONDS = 36L * 60L * 60L
        private val RECORD_VERSION_LOCK = Any()
    }
}
