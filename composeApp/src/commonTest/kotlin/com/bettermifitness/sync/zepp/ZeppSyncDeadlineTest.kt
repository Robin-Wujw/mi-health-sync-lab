package com.bettermifitness.sync.zepp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZeppSyncDeadlineTest {
    @Test fun successfulStageReturnsItsActualResult() = runTest {
        assertEquals(42, runBoundedZeppSync(timeoutMillis = 10) { 42 })
    }

    @Test fun parentCancellationIsNotConvertedIntoAnOwnStageTimeout() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) { runBoundedZeppSync(timeoutMillis = 1000) { awaitCancellation() } }
        }
    }
}
