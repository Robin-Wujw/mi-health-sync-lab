package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.LocalizedTest
import com.bettermifitness.sync.data.api.MiRecoveryAttemptDiagnostic
import com.bettermifitness.sync.data.api.MiRecoveryReadbackStatus
import com.bettermifitness.sync.data.api.MiRecoveryStoredOutcome
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiRecoveryAttemptUserTextTest : LocalizedTest() {
    @Test
    fun acceptedButMissingHistoryDoesNotClaimStorageOrCurrentUpload() {
        val text = MiRecoveryAttemptDiagnostic(true, MiRecoveryStoredOutcome.READBACK_UNVERIFIED,
            accepted = true, businessCode = 0, readbackStatus = MiRecoveryReadbackStatus.MISSING).userFacingDetails()
        assertTrue("Previous recovery record: marked as accepted" in text)
        assertTrue("without an explicit acceptance result" in text)
        assertTrue("does not prove cloud storage" in text)
        assertTrue("Previous recovery business code: 0" in text)
        assertTrue("Previous readback: the query did not return" in text)
    }

    @Test
    fun rejectionAndReadFailureAreExplainedAsSeparateHistoricalFacts() {
        val text = MiRecoveryAttemptDiagnostic(true, MiRecoveryStoredOutcome.REJECTED,
            accepted = false, businessCode = 100, readbackStatus = MiRecoveryReadbackStatus.UNQUERIED).userFacingDetails()
        assertTrue("Previous recovery response: Xiaomi returned an unsuccessful result" in text)
        assertTrue("Previous recovery business code: 100" in text)
        assertTrue("Previous readback: the lookup did not complete" in text)
        assertFalse("request was sent" in text)
    }

    @Test
    fun unknownAcceptanceAndUnreadableMetadataDoNotInventARejection() {
        val unknown = MiRecoveryAttemptDiagnostic(true, MiRecoveryStoredOutcome.READBACK_UNVERIFIED).userFacingDetails()
        assertTrue("no explicit acceptance or rejection was retained" in unknown)
        assertTrue("no recognized verification category was retained" in unknown)
        assertFalse("business code" in unknown)
        val unavailable = MiRecoveryAttemptDiagnostic(false, accepted = false, businessCode = 100).userFacingDetails()
        assertTrue("no readable result details" in unavailable)
        assertFalse("unsuccessful result" in unavailable)
        assertFalse("100" in unavailable)
    }

    @Test
    fun reservationAloneDoesNotClaimAnAttemptOccurred() {
        val text = MiRecoveryAttemptDiagnostic(true, MiRecoveryStoredOutcome.RESERVED).userFacingDetails()
        assertTrue("allowance was reserved" in text)
        assertTrue("does not prove that the request was sent" in text)
    }
}
