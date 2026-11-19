package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiRecoveryAttemptDiagnostic
import com.bettermifitness.sync.data.api.MiRecoveryReadbackStatus
import com.bettermifitness.sync.data.api.MiRecoveryStoredOutcome
import com.bettermifitness.sync.data.preferences.HealthImportPendingSnapshot
import com.bettermifitness.sync.data.preferences.HealthImportRecoveryAttempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiRecoveryAttemptDiagnosticTextTest {
    private val privateTime = 1_700_012_345L
    private fun attempt() = HealthImportRecoveryAttempt(
        token = "private-token", createdAtEpochSeconds = privateTime,
        contentFingerprint = "private-content-fingerprint", sourceDigest = "private-source-digest",
        wireFingerprint = "private-wire-fingerprint", legacyWireMetadataUnknown = true,
        outcome = "READBACK_UNVERIFIED", accepted = true, businessCode = 0, readbackStatus = "MISSING",
    )
    private fun snapshot(attempt: HealthImportRecoveryAttempt? = attempt()) = HealthImportPendingSnapshot(
        targetIdentity = "RAW|default|spo2|$privateTime|", pendingToken = "private-pending-token",
        contentFingerprint = "private-pending-content", sourceDigest = "private-pending-source", zoneOffsetSeconds = null,
        valid = true, recoveryConsumed = true, recoveryAttempt = attempt,
    )

    @Test
    fun acceptedButMissingCanBeDistinguishedFromUnknownTransportWithoutInferringPersistence() {
        val accepted = snapshot().previousRecoveryDiagnostic()
        assertEquals(MiRecoveryStoredOutcome.READBACK_UNVERIFIED, accepted.outcome)
        assertEquals(true, accepted.accepted)
        assertEquals(0, accepted.businessCode)
        assertEquals(MiRecoveryReadbackStatus.MISSING, accepted.readbackStatus)
        val unknown = snapshot(attempt().copy(accepted = null, businessCode = null)).previousRecoveryDiagnostic()
        assertEquals(MiRecoveryStoredOutcome.READBACK_UNVERIFIED, unknown.outcome)
        assertNull(unknown.accepted)
        assertNull(unknown.businessCode)
        assertTrue("previous_accepted=true" in accepted.copyableText())
        assertTrue("previous_accepted=unknown" in unknown.copyableText())
        assertFalse("previous_accepted=false" in unknown.copyableText())
        assertTrue("previous_business_code=unknown" in unknown.copyableText())
    }

    @Test
    fun rejectedBusinessResultRemainsSeparateFromReadbackState() {
        val summary = snapshot(attempt().copy(outcome = "REJECTED", accepted = false,
            businessCode = 100, readbackStatus = "UNQUERIED")).previousRecoveryDiagnostic()
        assertEquals(MiRecoveryStoredOutcome.REJECTED, summary.outcome)
        assertEquals(false, summary.accepted)
        assertEquals(100, summary.businessCode)
        assertEquals(MiRecoveryReadbackStatus.UNQUERIED, summary.readbackStatus)
        assertTrue("pending_recovery.previous_business_code=100" in summary.copyableText())
        assertFalse("upload_response" in summary.copyableText())
    }

    @Test
    fun missingOrUnreadableAttemptDoesNotInventAResultOrReleaseConsumedAllowance() {
        val original = snapshot(null)
        val summary = original.previousRecoveryDiagnostic()
        assertFalse(summary.metadataAvailable)
        assertNull(summary.outcome)
        assertNull(summary.accepted)
        assertNull(summary.businessCode)
        assertNull(summary.readbackStatus)
        assertTrue(original.recoveryConsumed)
        assertTrue("previous_metadata_available=false" in summary.copyableText())
        assertTrue("previous_readback_status=UNKNOWN" in summary.copyableText())
    }

    @Test
    fun diagnosticNeverContainsJournalTokensTimesOrIdentityFingerprints() {
        val summary = snapshot().previousRecoveryDiagnostic()
        val output = summary.copyableText() + summary.toString()
        for (secret in listOf("private-token", "private-pending-token", "private-source-digest", "private-wire-fingerprint",
            "private-content-fingerprint", "private-pending-source", "private-pending-content", privateTime.toString(),
                "RAW|default|spo2|")) {
            assertFalse(secret in output)
        }
        assertEquals(5, summary.copyableText().lines().size)
        assertTrue("previous_attempt_legacy_metadata_unknown=true" in output)
    }

    @Test
    fun arbitraryStoredOutcomeAndReadbackStringsCannotEscapeTheEnumProjection() {
        val summary = snapshot(attempt().copy(outcome = "private-outcome\naccount=secret",
            readbackStatus = "private-readback\ntoken=secret")).previousRecoveryDiagnostic()
        assertEquals(MiRecoveryStoredOutcome.UNKNOWN, summary.outcome)
        assertEquals(MiRecoveryReadbackStatus.UNKNOWN, summary.readbackStatus)
        val output = summary.copyableText() + summary.toString()
        assertFalse("private-" in output)
        assertFalse("secret" in output)
        assertEquals(5, summary.copyableText().lines().size)
    }

    @Test
    fun reservedAndVerifiedOutcomesDoNotSupplyMissingAcceptanceEvidence() {
        for (outcome in listOf("RESERVED", "VERIFIED")) {
            val summary = snapshot(attempt().copy(outcome = outcome, accepted = null,
                businessCode = null, readbackStatus = null)).previousRecoveryDiagnostic()
            assertNull(summary.accepted)
            assertNull(summary.readbackStatus)
            assertTrue("previous_accepted=unknown" in summary.copyableText())
            assertTrue("previous_business_code=unknown" in summary.copyableText())
        }
    }

    @Test
    fun unavailableMetadataSuppressesAnyInconsistentDirectlyConstructedValues() {
        val summary = MiRecoveryAttemptDiagnostic(metadataAvailable = false, accepted = true,
            businessCode = 0, readbackStatus = MiRecoveryReadbackStatus.VERIFIED, legacyWireMetadataUnknown = false)
        val output = summary.copyableText()
        assertTrue("previous_accepted=unknown" in output)
        assertTrue("previous_business_code=unknown" in output)
        assertTrue("previous_readback_status=UNKNOWN" in output)
    }
}
