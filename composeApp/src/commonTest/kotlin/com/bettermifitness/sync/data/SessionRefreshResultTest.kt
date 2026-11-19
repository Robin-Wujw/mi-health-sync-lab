package com.bettermifitness.sync.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRefreshResultTest : com.bettermifitness.sync.LocalizedTest() {
    @Test
    fun successFlags() {
        assertTrue(SessionRefreshResult.Success.isSuccess)
        assertFalse(SessionRefreshResult.NeedsReLogin("x").isSuccess)
        assertFalse(SessionRefreshResult.TransientFailure("y").isSuccess)
    }

    @Test
    fun userMessagesExplainTheActionWithoutDisplayingRawPassportDiagnostics() {
        assertEquals(
            "Your Xiaomi sign-in has expired. Sign in again to continue. Sync history and workout backups are retained.",
            SessionRefreshResult.NeedsReLogin("Saved Mi session was rejected (70016)").userMessage,
        )
        assertEquals(
            "Xiaomi requires account verification. Sign in again to continue.",
            SessionRefreshResult.NeedsVerification("verify", notificationUrl = "https://x").userMessage,
        )
        assertFalse(SessionRefreshResult.NeedsVerification("security diagnostic", "https://private-verification")
            .userMessage.contains("https://"))
    }
}
