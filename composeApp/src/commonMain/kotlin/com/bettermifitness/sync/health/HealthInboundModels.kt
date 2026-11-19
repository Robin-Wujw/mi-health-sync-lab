package com.bettermifitness.sync.health

/** Only metrics with an explicit, experimental Health Connect -> Mi import path. */
enum class HealthInboundMetric(val metricKey: String) {
    HEART_RATE("heart_rate"),
    RESTING_HEART_RATE("resting_heart_rate"),
    SLEEP("sleep"),
    HRV("hrv"),
    OXYGEN_SATURATION("spo2"),
    WORKOUTS("workouts"),
    VO2_MAX("vo2_max"),
}

/**
 * An explicit source allowlist is required. An empty allowlist must never broaden a
 * request into reading every installed health app. Bounds are [from, until).
 */
data class HealthInboundReadRequest(
    val fromEpochSeconds: Long,
    val untilEpochSeconds: Long,
    val sourcePackages: Set<String>,
) {
    init {
        require(fromEpochSeconds >= 0 && untilEpochSeconds > fromEpochSeconds) {
            "The Health Connect import time range must have a positive duration."
        }
        require(untilEpochSeconds <= Long.MAX_VALUE / 1_000L) {
            "The Health Connect import time range cannot be represented in milliseconds."
        }
        require(sourcePackages.isNotEmpty() && sourcePackages.none { it.isBlank() }) {
            "Choose a Health Connect source before reading import data."
        }
    }

    val fromEpochMillis: Long get() = fromEpochSeconds * 1_000L
    val untilEpochMillis: Long get() = untilEpochSeconds * 1_000L
}

/** HC identity is retained separately from measurement time, including revisions. */
data class HealthRecordOrigin(
    val recordId: String,
    val sourcePackage: String,
    val clientRecordId: String?,
    val clientRecordVersion: Long,
    val lastModifiedEpochMillis: Long,
) {
    /** The same timestamp in two records or apps is not the same record. */
    val stableRecordKey: String get() = "$sourcePackage:$recordId"
}

data class HealthInboundHeartRateSample(
    val epochMillis: Long,
    val beatsPerMinute: Long,
)

data class HealthInboundHeartRateRecord(
    val origin: HealthRecordOrigin,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?,
    val samples: List<HealthInboundHeartRateSample>,
)

data class HealthInboundRestingHeartRateRecord(
    val origin: HealthRecordOrigin,
    val epochMillis: Long,
    val zoneOffsetSeconds: Int?,
    val beatsPerMinute: Long,
)

/** HC defines this quantity as RMSSD in milliseconds; it is never SDNN. */
data class HealthInboundHrvRecord(
    val origin: HealthRecordOrigin,
    val epochMillis: Long,
    val zoneOffsetSeconds: Int?,
    val rmssdMillis: Double,
)

/** Percent, e.g. 97.5; retain precision until the destination encoder decides. */
data class HealthInboundOxygenSaturationRecord(
    val origin: HealthRecordOrigin,
    val epochMillis: Long,
    val zoneOffsetSeconds: Int?,
    val percentage: Double,
)

/** These are HC meanings, deliberately not Xiaomi's numeric sleep-stage codes. */
enum class HealthInboundSleepStageKind {
    UNKNOWN,
    AWAKE,
    SLEEPING,
    OUT_OF_BED,
    AWAKE_IN_BED,
    LIGHT,
    DEEP,
    REM,
}

data class HealthInboundSleepStage(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val kind: HealthInboundSleepStageKind,
    /** Retain future provider values even when this app only knows UNKNOWN. */
    val rawStageType: Int? = null,
)

data class HealthInboundSleepRecord(
    val origin: HealthRecordOrigin,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?,
    val stages: List<HealthInboundSleepStage>,
)

/** A denied or unavailable read is not a successful read with no records. */
class HealthInboundReadUnavailableException(
    val metric: HealthInboundMetric?,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
