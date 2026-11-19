package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.LocalizedTest
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportIssueCount
import com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity
import com.bettermifitness.sync.data.api.MiHealthImportResponseSummary
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.api.MiPendingImportProbeResult
import com.bettermifitness.sync.data.api.MiPendingRecoveryDiagnostic
import com.bettermifitness.sync.data.api.MiPendingReconcileDiagnostic
import com.bettermifitness.sync.data.api.MiRecoveryAttemptDiagnostic
import com.bettermifitness.sync.data.api.MiRecoveryReadbackStatus
import com.bettermifitness.sync.data.preferences.HealthImportPendingSnapshot
import com.bettermifitness.sync.data.preferences.HealthImportRecoveryAttempt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiHealthImportDiagnosticTextTest : LocalizedTest() {
    @Test fun selectedSkipPolicyUsesANeutralCountAndKeepsSupportDiagnostics() {
        val diagnostic = MiHealthImportDiagnostic("heart_rate", MiHealthImportStage.COMPLETE,
            verifiedRawRows = 760, issueCounts = listOf(MiHealthImportIssueCount(
                "HEART_RATE_SOURCE_POINT_EXCLUDED", MiHealthImportIssueSeverity.WARNING, 1, 2)))
        val details = diagnostic.userFacingDetails()
        assertTrue("Skipped 1 conflicting seconds" in details)
        assertFalse("Warning" in details)
        assertFalse("Rejected" in details)
        assertTrue("excluded_source_points=1" in diagnostic.copyableText("test"))
        assertTrue("outcome=COMPLETE_WITH_EXCLUSIONS" in diagnostic.copyableText("test"))
    }

    @Test
    fun affectedCountIsExplicitlyLabeledAndCannotLookLikeTheBusinessCode() {
        val error = MiHealthImportRunException(MiHealthImportFailureReason.REJECTED, 0, 100,
            diagnostic = MiHealthImportDiagnostic("heart_rate", MiHealthImportStage.UPLOAD,
                uploadResponse = MiHealthImportResponseSummary(businessCode = 7, accepted = false)))
        val text = error.userMessage()
        assertTrue("100 records affected" in text)
        assertTrue("Xiaomi business code: 7" in text)
        assertFalse("(100)" in text)
    }

    @Test
    fun failedPrecheckDoesNotTellTheUserRecordsWereSubmitted() {
        val error = MiHealthImportRunException(MiHealthImportFailureReason.UNVERIFIED, 0, 5,
            diagnostic = MiHealthImportDiagnostic("sleep", MiHealthImportStage.PRECHECK))
        assertTrue("was not uploaded" in error.userMessage())
    }

    @Test
    fun aPartialSourceRejectionKeepsCloudSuccessVisibleWithoutClaimingComplete() {
        val error = MiHealthImportRunException(MiHealthImportFailureReason.SOURCE_REJECTED, 1, 24,
            diagnostic = MiHealthImportDiagnostic("resting_heart_rate", MiHealthImportStage.PREPARE,
                verifiedRawRows = 1, failureReason = "SOURCE_REJECTED"))
        val text = error.userMessage()
        assertTrue("Some records are already verified" in text)
        assertTrue("24 records affected" in text)
        assertTrue("1 records verified" in text)
        assertFalse("Xiaomi rejected this upload" in text)
    }

    @Test
    fun issueDetailsExplainExcludedHrvSamplesAndShowDistinctCounts() {
        val text = MiHealthImportDiagnostic("hrv", MiHealthImportStage.COMPLETE,
            verifiedRawRows = 10, alreadyPresentRawRows = 10,
            issueCounts = listOf(MiHealthImportIssueCount("HRV_NO_MATCHING_SLEEP",
                MiHealthImportIssueSeverity.WARNING, 170, 170))).userFacingDetails()
        assertTrue("Notice:" in text)
        assertTrue("excluded from sleep summaries" in text)
        assertTrue("Matched sleep summaries can still be synced" in text)
        assertTrue("Occurrences: 170; distinct source records: 170" in text)
        assertTrue("Do not add these group counts together" in text)
        assertFalse("Not converted:" in text)
    }

    @Test
    fun pendingProbeReportsObservationsWithoutInflatingVerifiedProgress() {
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.PRECHECK,
            plannedRawRows = 219, verifiedRawRows = 72, alreadyPresentRawRows = 72,
            notAttemptedRawRows = 147, blockedPendingRows = 147, failureReason = "PENDING",
            pendingProbe = MiPendingImportProbeResult(pendingRows = 147, pendingMissing = 147,
                broadReadGroups = 2, probeSelected = 3, probeFound = 1, probeMissing = 2,
                broadVsNarrowDisagreement = 1))
        val copied = diagnostic.copyableText("1.0.6-healthlab.5 (1000011)")
        assertTrue("verified_raw_rows=72\n" in copied)
        assertTrue("newly_verified_raw_rows=0\n" in copied)
        assertTrue("blocked_pending_rows=147\n" in copied)
        assertTrue("pending_probe.read_only=true\n" in copied)
        assertTrue("pending_probe.narrow_found=1\n" in copied)
        assertTrue("pending_probe.broad_narrow_disagreement=1\n" in copied)
        val details = diagnostic.userFacingDetails()
        assertTrue("No pending lock was cleared" in details)
        assertTrue("do not prove that data was never saved" in details)
    }

    @Test
    fun dailyMinimumIsAWarnedConversionAndNotASourceRejection() {
        val diagnostic = MiHealthImportDiagnostic("resting_heart_rate", MiHealthImportStage.PREPARE,
            issueCounts = listOf(MiHealthImportIssueCount("RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED",
                MiHealthImportIssueSeverity.WARNING, 1, 3)))
        val copied = diagnostic.copyableText("test")
        assertTrue("issue.0.code=RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED" in copied)
        assertTrue("issue.0.severity=WARNING" in copied)
        assertFalse("Not converted:" in diagnostic.userFacingDetails())
        assertFalse("UNKNOWN" in copied)
    }

    @Test
    fun incompleteNativeHistoryKeepsUnknownDistinctFromMissingAndFromNewlyVerified() {
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.PRECHECK,
            verifiedRawRows = 63, alreadyPresentRawRows = 63, blockedPendingRows = 132,
            pendingProbe = MiPendingImportProbeResult(pendingRows = 132, pendingMissing = 132,
                nativeSelected = 132, nativeFound = 1, nativeUnqueried = 131, nativePagesRead = 5,
                missingWithOtherSourceRows = 2, missingWithDeletedRows = 3))
        val copied = diagnostic.copyableText("test")
        assertTrue("pending_probe.native_found=1\n" in copied)
        assertTrue("pending_probe.native_missing=0\n" in copied)
        assertTrue("pending_probe.native_unqueried=131\n" in copied)
        assertTrue("pending_probe.native_complete_queries=0\n" in copied)
        assertTrue("pending_probe.missing_with_other_source_rows=2\n" in copied)
        assertTrue("pending_probe.missing_with_deleted_rows=3\n" in copied)
        assertTrue("verified_raw_rows=63\n" in copied)
        assertTrue("newly_verified_raw_rows=0\n" in copied)
    }

    @Test
    fun recoveryDiagnosticAllowsOnlyFixedCodesAndDoesNotExportArbitraryStoredText() {
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.PRECHECK,
            pendingRecovery = MiPendingRecoveryDiagnostic("private-source-unsafe", true,
                candidateRows = 132, previouslyConsumed = true, legacyWireMetadataUnknown = true,
                historyPagesRead = 32, historyComplete = false, previousOutcome = "private-account-unsafe"))
        val text = diagnostic.copyableText("test")
        assertTrue("pending_recovery.status=UNKNOWN\n" in text)
        assertTrue("pending_recovery.previous_outcome=UNKNOWN\n" in text)
        assertTrue("pending_recovery.previously_consumed=true\n" in text)
        assertTrue("pending_recovery.consumed_this_run=false\n" in text)
        assertTrue("pending_recovery.legacy_row_metadata_unknown=true\n" in text)
        assertFalse("private-source" in text)
        assertFalse("private-account" in text)
    }

    @Test
    fun reconciliationCountsObservedMatchesWithoutClaimingACloudUpload() {
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.COMPLETE,
            plannedRawRows = 132, verifiedRawRows = 100, alreadyPresentRawRows = 100,
            notAttemptedRawRows = 32, blockedPendingRows = 32, failureReason = "PENDING",
            pendingReconcile = MiPendingReconcileDiagnostic("PARTIAL", true,
                candidateRows = 132, reconciledRows = 100, unresolvedRows = 32,
                missingRows = 30, journalChangedRows = 2, historySelectedRows = 132,
                historyPagesRead = 2, historyComplete = true))
        val copied = diagnostic.copyableText("test")
        assertTrue("pending_reconcile.cloud_read_only=true\n" in copied)
        assertTrue("pending_reconcile.updates_local_journal=true\n" in copied)
        assertTrue("pending_reconcile.reconciled_rows=100\n" in copied)
        assertTrue("pending_reconcile.unresolved_rows=32\n" in copied)
        assertTrue("attempted_raw_rows=0\n" in copied)
        assertTrue("newly_verified_raw_rows=0\n" in copied)
        assertTrue("already_present_raw_rows=100\n" in copied)
        assertFalse("pending_recovery.consumed_this_run=true" in copied)
        val details = diagnostic.userFacingDetails()
        assertTrue(details.isNotBlank())
        assertFalse("Xiaomi accepted this upload" in details)
    }

    @Test
    fun reconciliationExportsOnlyAllowlistedStatusAndCounters() {
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.PRECHECK,
            pendingReconcile = MiPendingReconcileDiagnostic("private-account-secret", true,
                candidateRows = 132, unresolvedRows = 132, unqueriedRows = 132,
                historySelectedRows = 132, historyPagesRead = 32, historyComplete = false))
        val copied = diagnostic.copyableText("test")
        assertTrue("pending_reconcile.status=UNKNOWN\n" in copied)
        assertTrue("pending_reconcile.missing_rows=0\n" in copied)
        assertTrue("pending_reconcile.unqueried_rows=132\n" in copied)
        assertTrue("pending_reconcile.history_complete=false\n" in copied)
        assertFalse("private-account" in copied)
        assertFalse("private-account" in diagnostic.userFacingDetails())
    }

    @Test
    fun reconciliationDisplaysOldestHistoricalResultUnderItsOwnNamespaceWithoutLeakingJournalDetails() {
        val timestamp = 1_700_009_876L
        val previous = HealthImportPendingSnapshot(
            targetIdentity = "RAW|default|spo2|$timestamp|", pendingToken = "private-pending-token",
            contentFingerprint = "private-content", sourceDigest = "private-source", zoneOffsetSeconds = null,
            valid = true, recoveryConsumed = true,
            recoveryAttempt = HealthImportRecoveryAttempt(token = "private-reservation-token", createdAtEpochSeconds = timestamp,
                contentFingerprint = "private-content", sourceDigest = "private-source", wireFingerprint = "private-wire",
                legacyWireMetadataUnknown = true, outcome = "READBACK_UNVERIFIED", accepted = true,
                businessCode = 0, readbackStatus = "MISSING"),
        ).previousRecoveryDiagnostic()
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.PRECHECK,
            pendingReconcile = MiPendingReconcileDiagnostic("INCOMPLETE", true,
                candidateRows = 133, unresolvedRows = 133, missingRows = 133, oldestRecoveryAttempt = previous))
        val copied = diagnostic.copyableText("test")
        assertTrue("pending_reconcile.oldest_previous_metadata_available=true" in copied)
        assertTrue("pending_reconcile.oldest_previous_outcome=READBACK_UNVERIFIED" in copied)
        assertTrue("pending_reconcile.oldest_previous_accepted=true" in copied)
        assertTrue("pending_reconcile.oldest_previous_business_code=0" in copied)
        assertTrue("pending_reconcile.oldest_previous_readback_status=MISSING" in copied)
        assertTrue("pending_reconcile.oldest_previous_attempt_legacy_metadata_unknown=true" in copied)
        assertTrue("attempted_raw_rows=0\n" in copied)
        assertFalse("pending_recovery." in copied)
        assertFalse("upload_business_code=" in copied)
        val human = diagnostic.userFacingDetails()
        assertTrue("Previous recovery record: marked as accepted" in human)
        assertTrue("without an explicit acceptance result" in human)
        assertTrue("does not prove cloud storage" in human)
        assertTrue("Previous recovery business code: 0" in human)
        assertTrue("Previous readback: the query did not return" in human)
        assertFalse("Xiaomi accepted this upload" in human)
        for (secret in listOf("private-pending-token", "private-reservation-token", "private-content", "private-source",
            "private-wire", timestamp.toString(), "RAW|default|spo2|")) {
            assertFalse(secret in copied)
            assertFalse(secret in human)
        }
    }

    @Test
    fun unavailableOldestHistoryRemainsUnknownAndCannotImplyRejectionOrVerification() {
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.PRECHECK,
            pendingReconcile = MiPendingReconcileDiagnostic("INCOMPLETE", true,
                candidateRows = 2, unresolvedRows = 2, missingRows = 2,
                oldestRecoveryAttempt = MiRecoveryAttemptDiagnostic(false, accepted = false, businessCode = 100,
                    readbackStatus = MiRecoveryReadbackStatus.VERIFIED)))
        val copied = diagnostic.copyableText("test")
        assertTrue("pending_reconcile.oldest_previous_metadata_available=false" in copied)
        assertTrue("pending_reconcile.oldest_previous_outcome=UNKNOWN" in copied)
        assertTrue("pending_reconcile.oldest_previous_accepted=unknown" in copied)
        assertTrue("pending_reconcile.oldest_previous_business_code=unknown" in copied)
        assertTrue("pending_reconcile.oldest_previous_readback_status=UNKNOWN" in copied)
        assertFalse("previous_accepted=false" in copied)
        assertFalse("previous_business_code=100" in copied)
        assertFalse("previous_readback_status=VERIFIED" in copied)
        val human = diagnostic.userFacingDetails()
        assertTrue("Previous recovery: no readable result details were retained" in human)
        assertFalse("Previous recovery business code" in human)
        assertFalse("Previous recovery response: Xiaomi returned an unsuccessful result" in human)
    }
}
