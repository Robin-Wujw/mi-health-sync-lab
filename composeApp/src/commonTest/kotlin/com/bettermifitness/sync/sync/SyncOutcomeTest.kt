package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.repository.SyncProgress
import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.data.repository.SyncState
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.preferences.SyncPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncOutcomeTest : com.bettermifitness.sync.LocalizedTest() {

    @Test fun selectedSkipPolicyCompletesNormallyAndNeverCountsExcludedSecondsAsVerified() {
        val diagnostic = MiHealthImportDiagnostic("heart_rate", MiHealthImportStage.COMPLETE, verifiedRawRows = 1327,
            issueCounts = listOf(com.bettermifitness.sync.data.api.MiHealthImportIssueCount("HEART_RATE_SOURCE_POINT_EXCLUDED",
                com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity.WARNING, 1, 1)))
        val result = SyncRunResult.from(SyncProgress(heartRateImport = SyncState.Success(1327, diagnostic = diagnostic)),
            listOf(SyncPreferences.HEART_RATE_IMPORT_KEY), emptyList(), false)
        assertEquals(1327, result.totalRecords)
        assertEquals(1, result.excludedSourcePoints)
        assertEquals(0, result.failed)
        assertTrue(result.isFullSuccess)
        assertTrue(result.isCompletedWithSkips)
        assertFalse(result.isPartialSuccess)
        assertTrue(result.warningMessages.single().contains("Skipped 1"))
    }

    @Test
    fun incompleteImportMetricsKeepVerifiedProgressWithoutBecomingCompletedTasks() {
        fun partial(metric: String, verified: Int, attempted: Int, affected: Int) = SyncState.Error(
            "Unresolved records", MiHealthImportDiagnostic(metric, MiHealthImportStage.PRECHECK,
                verifiedRawRows = verified, attemptedRawRows = attempted, affectedRows = affected),
        )
        val result = SyncRunResult.from(
            SyncProgress(
                heartRateImport = partial("heart_rate", 9322, 0, 218),
                spo2Import = partial("spo2", 411, 233, 171),
                hrvImport = partial("hrv", 10, 0, 170),
                restingHeartRateImport = partial("resting_heart_rate", 1, 0, 24),
            ),
            listOf(SyncPreferences.HEART_RATE_IMPORT_KEY, SyncPreferences.SPO2_IMPORT_KEY,
                SyncPreferences.HRV_IMPORT_KEY, SyncPreferences.RESTING_HEART_RATE_IMPORT_KEY),
            listOf(false, false, false, false), false,
        )
        assertEquals(4, result.attempted)
        assertEquals(0, result.succeeded)
        assertEquals(4, result.failed)
        assertEquals(9744, result.totalRecords)
        assertTrue(result.hasVerifiedProgress)
        assertTrue(result.isPartialSuccess)
        assertFalse(result.isTotalFailure)
        assertFalse(result.isFullSuccess)
        assertTrue(result.summary().contains("Partial"))
    }

    @Test
    fun acceptedOrAttemptedButUnverifiedRowsDoNotCreatePartialSuccess() {
        val result = SyncRunResult.from(
            SyncProgress(spo2Import = SyncState.Error("Readback unavailable",
                MiHealthImportDiagnostic("spo2", MiHealthImportStage.READBACK,
                    attemptedRawRows = 50, affectedRows = 50))),
            listOf(SyncPreferences.SPO2_IMPORT_KEY), listOf(false), false,
        )
        assertEquals(0, result.totalRecords)
        assertTrue(result.isTotalFailure)
        assertFalse(result.hasVerifiedProgress)
        assertFalse(result.isPartialSuccess)
    }

    @Test
    fun fullSuccess_fromProgress() {
        val progress = SyncProgress(
            heartRate = SyncState.Success(10),
            steps = SyncState.Success(3),
        )
        val result = SyncRunResult.from(
            progress = progress,
            metricKeys = listOf("heart_rate", "steps"),
            retryableFlags = emptyList(),
            authFailure = false,
        )
        assertTrue(result.isFullSuccess)
        assertEquals(2, result.succeeded)
        assertEquals(0, result.failed)
        assertEquals(13, result.totalRecords)
    }

    @Test
    fun partialSuccess_fromProgress() {
        val progress = SyncProgress(
            heartRate = SyncState.Success(5),
            sleep = SyncState.Error("timeout"),
        )
        val result = SyncRunResult.from(
            progress = progress,
            metricKeys = listOf("heart_rate", "sleep"),
            retryableFlags = listOf(true),
            authFailure = false,
        )
        assertTrue(result.isPartialSuccess)
        assertTrue(result.hadRetryableFailure)
        assertTrue(result.summary().contains("Partial"))
    }

    @Test
    fun totalAuthFailure_flags() {
        val progress = SyncProgress(
            heartRate = SyncState.Error("Session expired"),
            steps = SyncState.Error("Session expired"),
        )
        val result = SyncRunResult.from(
            progress = progress,
            metricKeys = listOf("heart_rate", "steps"),
            retryableFlags = listOf(false, false),
            authFailure = true,
        )
        assertTrue(result.isTotalFailure)
        assertTrue(result.hadAuthFailure)
        assertFalse(result.hadRetryableFailure)
    }

    @Test
    fun statusCodes_stable() {
        assertEquals("success", SyncOutcome.Success.toStatusCode())
        assertEquals(
            "partial_success",
            SyncOutcome.PartialSuccess(1, 1, "Partial").toStatusCode(),
        )
        assertEquals("failed", SyncOutcome.Failed("x", retryable = true).toStatusCode())
        assertEquals("already_running", SyncOutcome.AlreadyRunning.toStatusCode())
    }

    @Test
    fun backgroundRetry_onlyTransientFailures() {
        assertTrue(SyncOutcome.Failed("net", retryable = true).shouldRetryBackground())
        assertFalse(SyncOutcome.Failed("auth", retryable = false).shouldRetryBackground())
        assertFalse(
            SyncOutcome.PartialSuccess(2, 1, "Partial").shouldRetryBackground(),
        )
        assertFalse(SyncOutcome.Success.shouldRetryBackground())
        assertFalse(SyncOutcome.NotLoggedIn.shouldRetryBackground())
    }

    @Test
    fun unavailableDataIsNotCountedAsSuccessfulOrFailedTransfer() {
        val result = SyncRunResult.from(
            SyncProgress(heartRate = SyncState.NoData("No source heart rate")),
            listOf("heart_rate"), emptyList(), false,
        )
        assertTrue(result.isNoData)
        assertFalse(result.isFullSuccess)
        assertEquals(0, result.succeeded)
        assertEquals(0, result.failed)
        assertEquals(1, result.noData)
        assertEquals("no_data", SyncOutcome.NoData(result.summary()).toStatusCode())
        assertFalse(SyncOutcome.NoData(result.summary()).shouldRetryBackground())
    }

    @Test
    fun mixedSavedAndUnavailableMetricsCompleteWithSkips() {
        val result = SyncRunResult.from(
            SyncProgress(heartRate = SyncState.Success(12), hrv = SyncState.NoData("No compatible HRV")),
            listOf("heart_rate", "hrv"), emptyList(), false,
        )
        assertTrue(result.isCompletedWithSkips)
        assertFalse(result.isPartialSuccess)
        assertFalse(result.isFullSuccess)
        assertEquals(1, result.succeeded)
        assertEquals(1, result.noData)
        assertEquals(12, result.totalRecords)
        assertEquals("The selected sync directions completed", result.summary())
        assertFalse(result.summary().contains("No compatible HRV"))
    }

    @Test
    fun exclusionsNeverHideRealUnresolvedRecords() {
        val result = SyncRunResult(2, 1, 1, 12, false, false, listOf("Still pending"),
            excludedSourcePoints = 1)
        assertTrue(result.isPartialSuccess)
        assertFalse(result.isCompletedWithSkips)
        assertTrue(result.summary().contains("Still pending"))
    }
}
