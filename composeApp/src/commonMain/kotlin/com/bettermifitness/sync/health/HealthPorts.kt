package com.bettermifitness.sync.health

import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.data.api.ActiveCaloriesSample
import com.bettermifitness.sync.data.api.BloodPressureSample
import com.bettermifitness.sync.data.api.DistanceSample
import com.bettermifitness.sync.data.api.HeartRateSample
import com.bettermifitness.sync.data.api.HrvSample
import com.bettermifitness.sync.data.api.SleepSession
import com.bettermifitness.sync.data.api.SpO2Sample
import com.bettermifitness.sync.data.api.StepsRecord
import com.bettermifitness.sync.data.api.TemperatureSample
import com.bettermifitness.sync.data.api.Vo2MaxSample
import com.bettermifitness.sync.data.api.WeightMeasurement
import com.bettermifitness.sync.data.api.WorkoutSession
import kotlin.time.Instant

enum class WorkoutContributionMetric { STEPS, DISTANCE, CALORIES }

/**
 * ISP: write-only surface for metric samples (used by [com.bettermifitness.sync.data.repository.HealthRepository]).
 */
interface HealthSampleWriter {
    suspend fun writeHeartRate(samples: List<HeartRateSample>)
    suspend fun writeRestingHeartRate(samples: List<HeartRateSample>)
    suspend fun writeSleep(sessions: List<SleepSession>)
    suspend fun writeSteps(records: List<StepsRecord>)
    suspend fun writeDistance(samples: List<DistanceSample>)
    suspend fun writeActiveCalories(samples: List<ActiveCaloriesSample>)
    suspend fun writeSpO2(samples: List<SpO2Sample>)
    suspend fun writeWeight(measurements: List<WeightMeasurement>)
    suspend fun writeWorkouts(sessions: List<WorkoutSession>)
    suspend fun writeBloodPressure(samples: List<BloodPressureSample>)
    suspend fun writeTemperature(samples: List<TemperatureSample>)
    suspend fun writeVo2Max(samples: List<Vo2MaxSample>)
    /** Overnight HRV (ms). Empty list is a no-op — non-capable devices send no samples. */
    suspend fun writeHrv(samples: List<HrvSample>)
}

/**
 * Best-effort readiness of the platform health store for writing our metrics.
 */
data class HealthReadiness(
    val available: Boolean,
    val permissionsGranted: Boolean,
    val serviceName: String,
    val hint: String?,
) {
    val isReady: Boolean get() = available && permissionsGranted

    val statusTitle: String
        get() = when {
            !available -> L10n.text(L10n.healthNotAvailable)
            !permissionsGranted -> L10n.text(L10n.healthPermissionsIncomplete)
            else -> L10n.text(L10n.healthReady)
        }

    val statusDetail: String
        get() = when {
            !available ->
                hint ?: L10n.text(L10n.outcomeHealthDetail)
            !permissionsGranted ->
                L10n.textFmt(L10n.homeAllowAccessDetail, serviceName)
            else -> L10n.text(L10n.healthReady)
        }
}

/**
 * ISP: availability / branding / deep-link into the platform health store.
 */
interface HealthAvailability {
    suspend fun isAvailable(): Boolean
    suspend fun availabilityHint(): String?
    fun healthServiceName(): String
    fun openHealthService()

    /**
     * Best-effort check that write permissions for our metrics are granted.
     * On iOS this is limited by HealthKit privacy (may be false until first grant).
     */
    suspend fun hasWritePermissions(): Boolean

    suspend fun readiness(): HealthReadiness {
        val available = isAvailable()
        val granted = if (available) hasWritePermissions() else false
        return HealthReadiness(
            available = available,
            permissionsGranted = granted,
            serviceName = healthServiceName(),
            hint = availabilityHint(),
        )
    }
}

/**
 * ISP: permission prompts (may show UI on foreground only).
 */
interface HealthPermissionRequester {
    suspend fun requestWorkoutRoutes(request: HealthInboundReadRequest): Int = 0
    fun clearCachedWorkoutRoutes() = Unit
    suspend fun requestPermissions()

    /** Ask only after the user enables these inbound metrics; never grants writes. */
    suspend fun requestReadPermissions(
        metrics: Set<HealthInboundMetric>,
        includeBackground: Boolean = false,
        includeHistory: Boolean = false,
    ): Set<HealthInboundMetric> = emptySet()
}

/**
 * ISP: read-only surface for weight latest value (bidirectional sync).
 * Returns the latest sample regardless of sync window (sparse monthly weigh-ins).
 */
interface HealthWeightReader {
    /** Latest weight from Health Connect / HealthKit, or null if none. Loop-filtered (excludes mifit-*). */
    suspend fun readLatestWeight(): WeightMeasurement?
}

/**
 * Raw, source-attributed inbound records. Empty means the authorized read completed
 * and found no eligible data. Unsupported platforms and denied reads throw instead.
 */
interface HealthMetricReader {
    suspend fun readWorkouts(request: HealthInboundReadRequest): List<InboundWorkout> =
        throw UnsupportedOperationException("Workout imports are not supported by this health store.")

    suspend fun readVo2Max(request: HealthInboundReadRequest): List<InboundVo2Max> =
        throw UnsupportedOperationException("VO2max imports are not supported by this health store.")

    suspend fun readHeartRate(request: HealthInboundReadRequest): List<HealthInboundHeartRateRecord> =
        throw UnsupportedOperationException("Heart-rate imports are not supported by this health store.")

    suspend fun readRestingHeartRate(
        request: HealthInboundReadRequest,
    ): List<HealthInboundRestingHeartRateRecord> =
        throw UnsupportedOperationException("Resting-heart-rate imports are not supported by this health store.")

    suspend fun readSleep(request: HealthInboundReadRequest): List<HealthInboundSleepRecord> =
        throw UnsupportedOperationException("Sleep imports are not supported by this health store.")

    suspend fun readHrv(request: HealthInboundReadRequest): List<HealthInboundHrvRecord> =
        throw UnsupportedOperationException("HRV imports are not supported by this health store.")

    suspend fun readOxygenSaturation(
        request: HealthInboundReadRequest,
    ): List<HealthInboundOxygenSaturationRecord> =
        throw UnsupportedOperationException("Oxygen-saturation imports are not supported by this health store.")
}

/** Full platform health façade, including explicit experimental metric reads. */
interface HealthStore : HealthSampleWriter, HealthWeightReader, HealthMetricReader,
    HealthAvailability, HealthPermissionRequester {
    suspend fun blockedSyncMetrics(enabled: Set<String>, from: Instant): Map<String, HealthAccessBlock> = emptyMap()
    suspend fun readOwnWorkoutContributions(metric: WorkoutContributionMetric, from: Instant, until: Instant):
        List<WorkoutSession>? = null
}

enum class HealthAccessBlock {
    UNAVAILABLE, WRITE_PERMISSION, READ_PERMISSION, BACKGROUND_UNAVAILABLE, BACKGROUND_PERMISSION, HISTORY_PERMISSION,
    CHECK_FAILED;

    fun message(): String = L10n.text(when (this) {
        UNAVAILABLE -> L10n.outcomeHealthDetail
        WRITE_PERMISSION -> L10n.syncWritePermissionMissing
        READ_PERMISSION -> L10n.syncReadPermissionMissing
        BACKGROUND_UNAVAILABLE -> L10n.syncBackgroundReadUnsupported
        BACKGROUND_PERMISSION -> L10n.syncBackgroundReadPermissionMissing
        HISTORY_PERMISSION -> L10n.inboundHistoryRequired
        CHECK_FAILED -> L10n.healthPermissionsIncomplete
    })
}
