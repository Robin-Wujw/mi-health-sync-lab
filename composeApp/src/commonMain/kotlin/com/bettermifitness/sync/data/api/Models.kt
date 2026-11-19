package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.data.time.resolveOffsetSecondsFromMiUnits
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- User Profile ---

@Serializable
data class MeResponse(
    val code: Int? = null,
    val message: String? = null,
    val result: UserProfile? = null,
)

@Serializable
data class UserProfile(
    val name: String? = null,
    val icon: String? = null,
    val sex: String? = null,
    val age: Int? = null,
    val height: Int? = null,
    val birth: String? = null,
    @SerialName("daily_step_goal") val dailyStepGoal: Int? = null,
    @SerialName("daily_cal_goal") val dailyCalGoal: Int? = null,
)

// --- Fitness Data ---

@Serializable
data class FitnessResponse<T>(
    val result: FitnessResult<T>? = null,
    val code: Int? = null,
    val message: String? = null,
)

@Serializable
data class FitnessResult<T>(
    @SerialName("data_list") val dataList: List<T> = emptyList(),
    val watermark: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_key") val nextKey: String? = null,
)

// --- Heart Rate / generic by-time row ---

@Serializable
data class HeartRateEntry(
    val key: String? = null,
    val time: Long = 0,
    val value: String = "",
    /** Mi cloud source identity, distinct from an Android package name. */
    val sid: String? = null,
)

@Serializable
data class HeartRateSample(
    val timestamp: Long,
    val bpm: Int,
    /** Mi `timezone` field: offset in units of 15 minutes (28 → UTC+7). */
    val tzIn15Min: Int? = null,
    /** Original Mi key/source retained for diagnostics, not a new measurement. */
    val sourceKey: String? = null,
    val sourceSid: String? = null,
)

// --- Sleep ---

@Serializable
data class SleepEntry(
    val key: String? = null,
    val time: Long = 0,
    val value: String = "",
    val sid: String? = null,
)

@Serializable
data class SleepSession(
    val startTime: Long,
    val endTime: Long,
    val inBedStart: Long = 0,
    val inBedEnd: Long = 0,
    val stages: List<SleepStage> = emptyList(),
    /**
     * Overnight HRV from a Mi sleep payload (ms), when the source provides it.
     * The field name alone does not identify RMSSD or SDNN.
     */
    val avgHrvMs: Int? = null,
    val minHrvMs: Int? = null,
    val maxHrvMs: Int? = null,
    /** Epoch seconds when Mi analyzed HRV; falls back to wake time when writing. */
    val hrvAnalysisTimeSec: Long? = null,
    /** Mi `timezone` field: offset in units of 15 minutes (28 → UTC+7). */
    val tzIn15Min: Int? = null,
)

@Serializable
data class SleepStage(
    val startTime: Long,
    val endTime: Long,
    val stage: Int, // 2=deep, 3=light/core, 4=REM, 5=awake (Mi codes)
)

/**
 * Algorithm identity must come from a verified source contract. A generic HRV
 * value cannot be converted between RMSSD and SDNN without beat intervals.
 */
@Serializable
enum class HrvAlgorithm { UNKNOWN, RMSSD, SDNN }

/**
 * HRV sample in milliseconds. Mi's generic overnight `avg_hrv` is retained with
 * UNKNOWN algorithm for diagnostics; it must not be exported as RMSSD or SDNN.
 */
@Serializable
data class HrvSample(
    val timestamp: Long,
    /** Heart-rate variability in milliseconds (Mi UI unit). */
    val hrvMs: Double,
    /** Mi sleep `timezone` (15-min units), when derived from a sleep session. */
    val tzIn15Min: Int? = null,
    val algorithm: HrvAlgorithm = HrvAlgorithm.UNKNOWN,
    val sourceKey: String? = null,
    val sourceSid: String? = null,
    val rawSourceJson: String? = null,
    /** True for a report average, not an individual beat-interval observation. */
    val isAggregate: Boolean = false,
)

// --- Steps ---

@Serializable
data class StepsRecord(
    val date: String,
    val steps: Int,
    val distance: Double = 0.0,
    val calories: Double = 0.0,
    /** Mi `timezone` from first sample in the hour bucket, if present. */
    val tzIn15Min: Int? = null,
    /**
     * True when workout-overlap steps were subtracted (issue #51). Writers use it
     * to bump the record version so the correction overwrites inflated history.
     */
    val workoutAdjusted: Boolean = false,
)

// --- SpO2 ---

@Serializable
data class SpO2Sample(
    val timestamp: Long,
    val percentage: Int,
    /** Mi `timezone` field: offset in units of 15 minutes. */
    val tzIn15Min: Int? = null,
)

// --- Distance (meters), interval start = epoch seconds ---

@Serializable
data class DistanceSample(
    val startTime: Long,
    val endTime: Long,
    val meters: Double,
    /** Mi `timezone` from first sample in the hour bucket, if present. */
    val tzIn15Min: Int? = null,
    /**
     * True when workout-overlap distance was subtracted (issue #51). Writers use it
     * to bump the record version so the correction overwrites inflated history.
     */
    val workoutAdjusted: Boolean = false,
)

// --- Active energy (kcal) ---

@Serializable
data class ActiveCaloriesSample(
    val startTime: Long,
    val endTime: Long,
    val kilocalories: Double,
    /** Mi `timezone` from first sample in the hour bucket, if present. */
    val tzIn15Min: Int? = null,
    /**
     * True when workout-overlap calories were subtracted (issue #51). Writers use it
     * to bump the record version so the correction overwrites inflated history.
     */
    val workoutAdjusted: Boolean = false,
)

// --- Weight / body composition ---

@Serializable
data class WeightMeasurement(
    val timestamp: Long,
    val weightKg: Double,
    val bodyFatPercent: Double? = null,
    val muscleMassKg: Double? = null,
    val boneMassKg: Double? = null,
    /** Basal metabolic rate in kilocalories per day. */
    val basalMetabolismKcal: Double? = null,
    /** Mi `timezone` field: offset in units of 15 minutes. */
    val tzIn15Min: Int? = null,
    val bodyWaterMassKg: Double? = null,
    val bodyWaterPercent: Double? = null,
    /** Fat-free body mass, not muscle mass. */
    val leanBodyMassKg: Double? = null,
    val skeletalMuscleMassKg: Double? = null,
    val visceralFatLevel: Double? = null,
    val proteinPercent: Double? = null,
    val proteinMassKg: Double? = null,
    val bmi: Double? = null,
    val bodyAgeYears: Int? = null,
    val bodyScore: Double? = null,
    /** Original cloud value JSON; never treated as an upload payload. */
    val rawSourceJson: String? = null,
    /** Android data-origin package when a measurement came from Health Connect. */
    val sourcePackage: String? = null,
)

/**
 * Reject invalid optional values without inventing missing measurements. Used by
 * both platform-write normalization and the Mi upload serializer. Bounds are
 * deliberately broad; constituent masses must not exceed the measured weight.
 */
internal fun WeightMeasurement.sanitizedComposition(): WeightMeasurement {
    fun Double?.within(range: ClosedFloatingPointRange<Double>): Double? =
        this?.takeIf { it.isFinite() && it in range }

    fun Double?.mass(range: ClosedFloatingPointRange<Double>): Double? =
        within(range)?.takeIf { it <= weightKg }

    return copy(
        bodyFatPercent = bodyFatPercent.within(1.0..70.0),
        muscleMassKg = muscleMassKg.mass(1.0..200.0),
        boneMassKg = boneMassKg.mass(0.1..50.0),
        basalMetabolismKcal = basalMetabolismKcal.within(200.0..10_000.0),
        bodyWaterMassKg = bodyWaterMassKg.mass(0.1..500.0),
        bodyWaterPercent = bodyWaterPercent.within(0.0..100.0),
        leanBodyMassKg = leanBodyMassKg.mass(0.1..500.0),
        skeletalMuscleMassKg = skeletalMuscleMassKg.mass(0.1..200.0),
        visceralFatLevel = visceralFatLevel.within(1.0..100.0),
        proteinPercent = proteinPercent.within(0.0..100.0),
        proteinMassKg = proteinMassKg.mass(0.1..500.0),
        bmi = bmi.within(1.0..200.0),
        bodyAgeYears = bodyAgeYears?.takeIf { it in 1..150 },
        bodyScore = bodyScore.within(0.0..100.0),
    )
}

// --- Workouts ---

/** One GPS track point for HealthKit / Health Connect workout routes. */
@Serializable
data class WorkoutRoutePoint(
    val timeSec: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
)

/** Scalar sample during a workout (HR, pace, cadence, …). */
@Serializable
data class WorkoutTimedSample(
    val timeSec: Long,
    val value: Double,
)

/** Whole-km split from Mi record series (or derived). */
@Serializable
data class WorkoutKmSplit(
    val kilometer: Int,
    val timeSec: Long,
    val paceSecPerKm: Double? = null,
)

@Serializable
data class WorkoutSession(
    val startTime: Long,
    val endTime: Long,
    /** Mi category / sport type string (e.g. running, cycling). */
    val activityType: String,
    val distanceMeters: Double? = null,
    val caloriesKcal: Double? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val minHeartRateBpm: Int? = null,
    val totalSteps: Int? = null,
    /** Pace in seconds per kilometer (Mi avg_pace / max_pace / min_pace). */
    val avgPaceSecPerKm: Double? = null,
    val maxPaceSecPerKm: Double? = null,
    val minPaceSecPerKm: Double? = null,
    val avgCadenceSpm: Double? = null,
    val maxCadenceSpm: Double? = null,
    val maxSpeedMps: Double? = null,
    val avgStrideCm: Double? = null,
    /** Running power watts (Mi rarely provides on phone GPS). */
    val avgPowerWatts: Double? = null,
    val maxPowerWatts: Double? = null,
    /** Form metrics when Mi/watch provides them (ms / cm). */
    val avgGroundContactMs: Double? = null,
    val avgVerticalOscillationCm: Double? = null,
    val elevationGainM: Double? = null,
    val elevationLossM: Double? = null,
    val maxElevationM: Double? = null,
    val minElevationM: Double? = null,
    val avgElevationM: Double? = null,
    /** Seconds spent in Mi HR zones (warm-up → extreme). */
    val hrZoneWarmupSec: Int? = null,
    val hrZoneFatBurnSec: Int? = null,
    val hrZoneAerobicSec: Int? = null,
    val hrZoneAnaerobicSec: Int? = null,
    val hrZoneExtremeSec: Int? = null,
    val trainEffect: Double? = null,
    val trainLoad: Double? = null,
    val recoverMinutes: Int? = null,
    val vo2Max: Double? = null,
    /** Mi `timezone` field: offset in units of 15 minutes (28 → UTC+7). */
    val tzIn15Min: Int? = null,
    /** GPS route points (empty when indoor / download failed / no GPS file). */
    val route: List<WorkoutRoutePoint> = emptyList(),
    /** In-workout time series (from FDS record +/or Mi HR by time). */
    val heartRateSeries: List<WorkoutTimedSample> = emptyList(),
    val paceSeries: List<WorkoutTimedSample> = emptyList(),
    val cadenceSeries: List<WorkoutTimedSample> = emptyList(),
    val speedSeries: List<WorkoutTimedSample> = emptyList(),
    val elevationSeries: List<WorkoutTimedSample> = emptyList(),
    /** Stride length in **meters** over time (for HK RunningStrideLength / notes). */
    val strideMetersSeries: List<WorkoutTimedSample> = emptyList(),
    val powerWattsSeries: List<WorkoutTimedSample> = emptyList(),
    val groundContactMsSeries: List<WorkoutTimedSample> = emptyList(),
    val verticalOscillationCmSeries: List<WorkoutTimedSample> = emptyList(),
    val kmSplits: List<WorkoutKmSplit> = emptyList(),
    val recoverHeartRateSeries: List<WorkoutTimedSample> = emptyList(),
    /**
     * FDS download keys from the sport report (not written to Health).
     * Set when report `version > 0` so sync can fetch GPS/record/recover files.
     */
    val gpsDeviceSid: String? = null,
    val gpsTimestampSec: Long? = null,
    val gpsTzIn15Min: Int? = null,
    val gpsProtoType: Int? = null,
) {
    /**
     * Zone offset seconds from Mi timezone fields.
     * Prefer [tzIn15Min], then [gpsTzIn15Min]; missing → 0 (callers may apply device fallback).
     */
    fun zoneOffsetSeconds(): Int =
        resolveOffsetSecondsFromMiUnits(tzIn15Min ?: gpsTzIn15Min, fallbackSeconds = 0)
}

@Serializable
data class SportRecordEntry(
    val key: String? = null,
    val time: Long = 0,
    val value: String = "",
    val category: String? = null,
    val sid: String? = null,
    val zoneOffsetSeconds: Int? = null,
    val deleted: Boolean = false,
)

// --- P2: from Mi FitnessPersistKey + item models (decompiled APK) ---

/** Mi BloodPressureItem: systolic_pressure / diastolic_pressure (mmHg). */
@Serializable
data class BloodPressureSample(
    val timestamp: Long,
    val systolicMmhg: Int,
    val diastolicMmhg: Int,
    val pulseBpm: Int? = null,
    /** Mi `timezone` field: offset in units of 15 minutes. */
    val tzIn15Min: Int? = null,
)

/** Mi TemperatureItem: body_temperature / skin_temperature (°C). */
@Serializable
data class TemperatureSample(
    val timestamp: Long,
    val bodyCelsius: Double? = null,
    val skinCelsius: Double? = null,
    /** Mi `timezone` field: offset in units of 15 minutes. */
    val tzIn15Min: Int? = null,
)

/** Mi Vo2MaxItem: vo2_max (mL/kg/min as integer in APK). */
@Serializable
data class Vo2MaxSample(
    val timestamp: Long,
    val mlPerKgMin: Double,
    /** Mi `timezone` field: offset in units of 15 minutes. */
    val tzIn15Min: Int? = null,
)
