package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.preferences.AutoSyncTiming
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutomaticRetryTest {
    @Test fun restartedSchedulerUsesPersistedRetryInsteadOfHourlyOrDuplicateWork() {
        val start = 30_000L
        val owner = "scheduler"
        val generation = 7L
        val retry = checkNotNull(nextAutomaticRetry(AutomaticWorkAttempt(owner, 0), generation, start, null, true))
        val restored = Json.decodeFromString<AutomaticRetry>(Json.encodeToString(retry))
        val timing = AutoSyncTiming(lastAutomaticStart = start, retry = restored)
        val attempt = resumedAutomaticAttempt(timing, owner, generation, start + 20)
        assertEquals(1, attempt.number)
        assertEquals(880L, scheduledAutomaticDelay(timing, owner, generation, start + 20))
        assertEquals(AutomaticDue.RETRY_DEFERRED, automaticDue(timing, start + 20, generation, attempt))
        assertEquals(AutomaticDue.SKIP, automaticDue(timing, start + 900, generation, AutomaticWorkAttempt("other", 0)))
        assertEquals(AutomaticDue.RETRY, automaticDue(timing, start + 900, generation, attempt))
        val later = checkNotNull(nextAutomaticRetry(attempt, generation, start + 900, restored, true))
        assertEquals(2, later.nextAttempt)
        assertEquals(start + 2_700, later.notBefore)
        assertEquals(retry.expiresAt, later.expiresAt)
        assertEquals(0, resumedAutomaticAttempt(timing, owner, generation + 1, start + 900).number)
        assertEquals(2_700L, scheduledAutomaticDelay(timing, owner, generation + 1, start + 900))
        assertEquals(0, resumedAutomaticAttempt(timing, owner, generation, retry.expiresAt).number)
        assertEquals(2_500L, scheduledAutomaticDelay(timing.copy(pausedUntil = start + 2_600), owner, generation, start + 100))
        assertEquals(900L, scheduledAutomaticDelay(timing.copy(lastManualFinish = start + 100), owner, generation, start + 100))
    }

    @Test fun retryPersistedBeforeSchedulerCounterWasAddedCanStillBeRead() {
        val restored = Json.decodeFromString<AutomaticRetry>(
            """{"owner":"scheduler","generation":7,"notBefore":30900,"expiresAt":40800}""",
        )
        assertEquals(1, restored.nextAttempt)
    }

    @Test fun retryCannotCrossAccountsOrBypassExplicitPause() {
        val start = 30_000L
        val first = AutomaticWorkAttempt("work", 0)
        val pending = checkNotNull(nextAutomaticRetry(first, 7L, start, null, retryable = true))
        val retry = first.copy(number = 1)
        val timing = AutoSyncTiming(lastAutomaticStart = start, pausedUntil = start + 3_600, retry = pending)
        assertEquals(AutomaticDue.RETRY_DEFERRED, automaticDue(timing, start + 900, 7, retry))
        assertEquals(AutomaticDue.SKIP, automaticDue(timing, start + 900, 8, retry))
        assertEquals(AutomaticDue.RETRY, automaticDue(timing, start + 3_600, 7, retry))
        assertEquals(AutomaticDue.SKIP, automaticDue(timing, pending.expiresAt, 7, retry))
        assertEquals(AutomaticDue.NORMAL, automaticDue(timing, pending.expiresAt, 7, first))
        assertNull(nextAutomaticRetry(first.copy(number = 3), 7, start + 3_600, pending, retryable = true))
        assertNull(nextAutomaticRetry(retry, 7, start + 900, pending, retryable = false))
    }
}
