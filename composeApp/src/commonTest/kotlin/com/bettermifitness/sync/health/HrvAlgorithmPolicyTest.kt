package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.HrvAlgorithm
import com.bettermifitness.sync.data.api.HrvSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class HrvAlgorithmPolicyTest {
    private val time = 1_700_000_000L

    @Test
    fun genericMiHrvCannotBeRelabelledAsEitherPlatformAlgorithm() {
        for (target in listOf(HrvAlgorithm.RMSSD, HrvAlgorithm.SDNN)) {
            val error = assertFailsWith<UnsupportedHrvAlgorithmException> {
                HrvAlgorithmPolicy.requireCompatible(listOf(HrvSample(time, 42.0)), target)
            }
            assertEquals(1, error.unknownCount)
            assertEquals(0, error.compatibleCount)
            assertEquals(target, error.targetAlgorithm)
        }
    }

    @Test
    fun mixedBatchFailsBeforeAnyWriteAndReportsCounts() {
        val error = assertFailsWith<UnsupportedHrvAlgorithmException> {
            HrvAlgorithmPolicy.requireCompatible(
                listOf(
                    HrvSample(time, 30.0, algorithm = HrvAlgorithm.RMSSD),
                    HrvSample(time + 1, 40.0),
                    HrvSample(time + 2, 50.0, algorithm = HrvAlgorithm.SDNN),
                ),
                HrvAlgorithm.RMSSD,
            )
        }
        assertEquals(1, error.compatibleCount)
        assertEquals(2, error.unsupportedCount)
        assertEquals(1, error.unknownCount)
    }

    @Test
    fun compatibleDataIsReturnedWithoutChangingValues() {
        val input = listOf(HrvSample(time, 37.25, algorithm = HrvAlgorithm.SDNN))
        assertSame(input, HrvAlgorithmPolicy.requireCompatible(input, HrvAlgorithm.SDNN))
    }

    @Test
    fun normalizationPreservesUnknownAndKnownAtTheSameTime() {
        val input = listOf(
            HrvSample(time * 1000, 35.5, algorithm = HrvAlgorithm.RMSSD, sourceSid = "watch"),
            HrvSample(time, 41.5, rawSourceJson = "{\"avg_hrv\":41.5}", isAggregate = true),
        )
        val out = HealthDataNormalizer.normalizeHrv(input, nowEpochSeconds = time + 100)
        assertEquals(2, out.size)
        assertEquals(input[0].copy(timestamp = time), out[0])
        assertEquals(input[1], out[1])
    }

    @Test
    fun normalizationUsesNoArbitraryPhysiologicalCutoff() {
        val out = HealthDataNormalizer.normalizeHrv(
            listOf(
                HrvSample(time, 1.5, algorithm = HrvAlgorithm.RMSSD),
                HrvSample(time + 1, 400.0, algorithm = HrvAlgorithm.SDNN),
                HrvSample(time + 2, Double.NaN),
                HrvSample(time + 3, Double.POSITIVE_INFINITY),
                HrvSample(time + 4, 0.0),
            ),
            nowEpochSeconds = time + 100,
        )
        assertEquals(listOf(1.5, 400.0), out.map { it.hrvMs })
    }
}
