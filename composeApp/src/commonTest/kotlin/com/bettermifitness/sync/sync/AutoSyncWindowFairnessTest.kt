package com.bettermifitness.sync.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoSyncWindowFairnessTest {
    private fun window(startDay: Long, endDay: Long) = AutoSyncWindow(startDay * AUTO_WINDOW_SECONDS, endDay * AUTO_WINDOW_SECONDS)

    @Test fun persistentFailuresRotatePastLaterCompletedIntervalsWithoutMovingTheFrontier() {
        val boundary = window(6, 7).until
        val oldest = window(0, 1)
        val checkpoint = AutoSyncCheckpoint(0, listOf(window(2, 3), window(5, 6)))
        assertEquals(window(1, 2), checkpoint.alternateHistoricalWindow(oldest, boundary))
        val attempted = checkpoint.attemptedAlternate(window(1, 2).until)
        assertEquals(window(3, 4), attempted.alternateHistoricalWindow(oldest, boundary))
        val completed = attempted.confirm(window(3, 4)).attemptedAlternate(window(3, 4).until)
        assertEquals(0L, completed.contiguousUntil)
        assertEquals(listOf(window(2, 4), window(5, 6)), completed.later)
        assertEquals(window(4, 5), completed.alternateHistoricalWindow(oldest, boundary))
        val wrapped = completed.confirm(window(5, 7)).attemptedAlternate(window(5, 6).until)
        assertEquals(window(1, 2), wrapped.alternateHistoricalWindow(oldest, boundary))
    }

    @Test fun noAlternateIsScheduledWhenThereIsOnlyOneUncoveredWindow() {
        val oldest = window(0, 1)
        val checkpoint = AutoSyncCheckpoint(0, listOf(window(1, 3)))
        assertNull(checkpoint.alternateHistoricalWindow(oldest, window(2, 3).until))
    }
}
