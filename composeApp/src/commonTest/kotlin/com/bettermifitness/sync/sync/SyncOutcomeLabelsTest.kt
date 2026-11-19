package com.bettermifitness.sync.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncOutcomeLabelsTest : com.bettermifitness.sync.LocalizedTest() {

    @Test
    fun titles_forKnownStatuses() {
        assertEquals("You're up to date", SyncOutcomeLabels.title(SyncOutcome.STATUS_SUCCESS))
        assertEquals("Almost done", SyncOutcomeLabels.title(SyncOutcome.STATUS_PARTIAL_SUCCESS))
        assertEquals("Please sign in", SyncOutcomeLabels.title(SyncOutcome.STATUS_NOT_LOGGED_IN))
        assertEquals("Not synced yet", SyncOutcomeLabels.title(null))
    }

    @Test
    fun detail_prefersMessage() {
        assertEquals(
            "3 ok, 1 failed",
            SyncOutcomeLabels.detail(SyncOutcome.STATUS_PARTIAL_SUCCESS, "3 ok, 1 failed"),
        )
        assertTrue(
            SyncOutcomeLabels.detail(SyncOutcome.STATUS_FAILED, null)
                .contains("try again", ignoreCase = true),
        )
        assertEquals(
            "The selected sync directions completed",
            SyncOutcomeLabels.detail(SyncOutcome.STATUS_SUCCESS, null),
        )
    }

    @Test
    fun severityFlags() {
        assertTrue(SyncOutcomeLabels.isError(SyncOutcome.STATUS_FAILED))
        assertTrue(SyncOutcomeLabels.isWarning(SyncOutcome.STATUS_PARTIAL_SUCCESS))
        // Skipped is neutral: a no-op run must never render as a partial sync.
        assertFalse(SyncOutcomeLabels.isWarning(SyncOutcome.STATUS_SKIPPED))
        assertFalse(SyncOutcomeLabels.isError(SyncOutcome.STATUS_SUCCESS))
    }

    @Test
    fun completedWithSkipsHidesLegacyCountsAndUsesNormalSuccessPresentation() {
        val outcome = SyncOutcome.CompletedWithSkips("Verified 760. Skipped 1.")
        assertEquals("completed_with_skips", outcome.toStatusCode())
        assertEquals("You're up to date", SyncOutcomeLabels.title(outcome.toStatusCode()))
        assertEquals("The selected sync directions completed",
            SyncOutcomeLabels.detail(outcome.toStatusCode(), outcome.summary))
        assertFalse(SyncOutcomeLabels.isWarning(outcome.toStatusCode()))
        assertFalse(SyncOutcomeLabels.isError(outcome.toStatusCode()))
        assertFalse(outcome.shouldRetryBackground())
    }

    @Test
    fun missingSourceDataIsNeutralWhileActualFailuresStillShowTheirError() {
        assertFalse(SyncOutcomeLabels.isWarning(SyncOutcome.STATUS_NO_DATA))
        assertFalse(SyncOutcomeLabels.isError(SyncOutcome.STATUS_NO_DATA))
        assertEquals("No usable data", SyncOutcomeLabels.detail(SyncOutcome.STATUS_NO_DATA, "Old device-format warning"))
        assertEquals("Readback failed", SyncOutcomeLabels.detail(SyncOutcome.STATUS_FAILED, "Readback failed"))
        assertTrue(SyncOutcomeLabels.isError(SyncOutcome.STATUS_FAILED))
        assertTrue(SyncOutcomeLabels.isWarning(SyncOutcome.STATUS_PARTIAL_SUCCESS))
    }
}
