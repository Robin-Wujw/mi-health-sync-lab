package com.bettermifitness.sync.health

/** A measurement's full time and writer identity; a nearby sample is a different measurement. */
internal data class BodyMeasurementKey(
    val epochSeconds: Long,
    val nanoseconds: Int,
    val sourcePackage: String,
)

internal data class BodyCompositionValue(
    val measurement: BodyMeasurementKey,
    val value: Double,
    val modifiedEpochSeconds: Long,
    val modifiedNanoseconds: Int = 0,
)

internal object BodyCompositionMatcher {
    /**
     * Attach only a value written by the same app at the identical measurement
     * Instant. If the source has revised that point, use its latest modification.
     */
    fun latestValue(anchor: BodyMeasurementKey, candidates: List<BodyCompositionValue>): Double? {
        if (anchor.sourcePackage.isBlank()) return null
        return candidates.asSequence()
            .filter { it.measurement == anchor && it.value.isFinite() }
            .maxWithOrNull(
                compareBy<BodyCompositionValue> { it.modifiedEpochSeconds }
                    .thenBy { it.modifiedNanoseconds },
            )?.value
    }
}
