package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.HrvAlgorithm
import com.bettermifitness.sync.data.api.HrvSample

/** Counts describe the rejected batch. No records have been written yet. */
class UnsupportedHrvAlgorithmException(
    val targetAlgorithm: HrvAlgorithm,
    val compatibleCount: Int,
    val unsupportedCount: Int,
    val unknownCount: Int,
) : IllegalArgumentException(
    "HRV export requires verified ${targetAlgorithm.name}: " +
        "$unsupportedCount unsupported record(s), including $unknownCount with unknown algorithm; " +
        "$compatibleCount compatible record(s). No HRV records were written.",
)

object HrvAlgorithmPolicy {
    /**
     * Validate the whole batch before any platform write. Do not quietly remove
     * unsupported rows from a mixed batch and report the entire batch as synced.
     */
    fun requireCompatible(
        samples: List<HrvSample>,
        targetAlgorithm: HrvAlgorithm,
    ): List<HrvSample> {
        require(targetAlgorithm != HrvAlgorithm.UNKNOWN) { "An HRV destination must specify its algorithm" }
        val unsupported = samples.count { it.algorithm != targetAlgorithm }
        if (unsupported > 0) {
            throw UnsupportedHrvAlgorithmException(
                targetAlgorithm = targetAlgorithm,
                compatibleCount = samples.size - unsupported,
                unsupportedCount = unsupported,
                unknownCount = samples.count { it.algorithm == HrvAlgorithm.UNKNOWN },
            )
        }
        return samples
    }
}
