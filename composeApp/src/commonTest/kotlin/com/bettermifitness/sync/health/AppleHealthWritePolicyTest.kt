package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.TemperatureSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class AppleHealthWritePolicyTest {
    @Test
    fun fallbackFailureDoesNotCountAsSavedButLaterWorkoutsCanSucceed() = runTest {
        val fallbackCalls = mutableListOf<String>()
        val failure = assertFailsWith<HealthWriteIncompleteException> {
            writeAppleWorkouts(listOf("both-fail", "legacy-saved", "builder-saved"), primary = { workout ->
                if (workout != "builder-saved") throw WorkoutNotYetSaved(IllegalStateException("preparation failed"))
                0
            }, fallback = { workout ->
                fallbackCalls += workout
                if (workout == "both-fail") error("save failed")
                0
            })
        }
        assertEquals(listOf("both-fail", "legacy-saved"), fallbackCalls)
        assertEquals(2, failure.writtenRecordCount)
        assertTrue(failure.skippedReasons.single().endsWith("1."))
    }

    @Test
    fun unknownCommitIsNotRetriedAndASavedWorkoutWithMissingDetailsIsPartial() = runTest {
        var fallbacks = 0
        val failure = assertFailsWith<HealthWriteIncompleteException> {
            writeAppleWorkouts(listOf("unknown", "partial"), primary = { workout ->
                if (workout == "unknown") error("commit result unavailable")
                1
            }, fallback = { fallbacks++; 0 })
        }
        assertEquals(0, fallbacks)
        assertEquals(1, failure.writtenRecordCount)
        assertEquals(2, failure.skippedReasons.size)
    }

    @Test
    fun cancellationIsNeverConvertedToFallbackOrPartialSuccess() = runTest {
        var fallbacks = 0
        assertFailsWith<CancellationException> {
            writeAppleWorkouts(listOf("test"), primary = {
                writeOptionalAppleDetail { throw CancellationException("stopped") }
            }, fallback = { fallbacks++; 0 })
        }
        assertEquals(0, fallbacks)
    }

    @Test
    fun skinTemperatureNeverBecomesBodyTemperature() {
        val plan = planAppleTemperatureWrites(listOf(
            TemperatureSample(1, skinCelsius = 33.0),
            TemperatureSample(2, bodyCelsius = 36.5, skinCelsius = 33.5),
            TemperatureSample(3, bodyCelsius = 36.8),
        ))
        assertEquals(2, plan.skippedSkin)
        assertEquals(listOf(AppleBodyTemperature(2, 36.5), AppleBodyTemperature(3, 36.8)), plan.body)
    }
}
