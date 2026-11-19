package com.bettermifitness.sync.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SyncProgressCompletionTest {
    @Test fun endedRunCannotKeepZeppOrAnotherActiveMetricSpinning() {
        val progress = SyncProgress(heartRate = SyncState.InProgress, zeppCloudImport = SyncState.InProgress)
        val finished = progress.finishActiveTasks("Interrupted")
        assertEquals("Interrupted", assertIs<SyncState.Error>(finished.zeppCloudImport).message)
        assertEquals("Interrupted", assertIs<SyncState.Error>(finished.heartRate).message)
        assertSame(SyncState.Idle, finished.weight)
    }

    @Test fun terminalCountsErrorsAndAuthenticationStateArePreserved() {
        val success = SyncState.Success(1420)
        val error = SyncState.Error("Sign in", verifiedCount = 3, authenticationRequired = true)
        val noData = SyncState.NoData("No source")
        val progress = SyncProgress(heartRate = success, sleep = noData, zeppCloudImport = error)
        val finished = progress.finishActiveTasks("Interrupted")
        assertSame(success, finished.heartRate)
        assertSame(noData, finished.sleep)
        assertSame(error, finished.zeppCloudImport)
    }
}
