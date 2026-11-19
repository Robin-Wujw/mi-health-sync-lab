package com.bettermifitness.sync.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncActivityPersistenceTest {
    @Test
    fun failedRunActivitySurvivesFileReopenWithoutInventingSuccessfulSync(): Unit = runBlocking {
        val directory = Files.createTempDirectory("healthlab-sync-activity-").toFile()
        val file = directory.resolve("sync.preferences_pb")
        val writerJob = SupervisorJob()
        val readerJob = SupervisorJob()
        try {
            val writer = SyncPreferences(PreferenceDataStoreFactory.createWithPath(
                scope = CoroutineScope(writerJob + Dispatchers.IO), produceFile = { file.path.toPath() }))
            writer.updateLastSyncOutcome("failed", "Network unavailable")
            writer.updateLastSyncActivity("2026-11-05T04:00:00Z")
            writerJob.cancelAndJoin()

            val reader = SyncPreferences(PreferenceDataStoreFactory.createWithPath(
                scope = CoroutineScope(readerJob + Dispatchers.IO), produceFile = { file.path.toPath() }))
            assertEquals("2026-11-05T04:00:00Z", reader.lastSyncActivityTime.first())
            assertEquals("failed", reader.lastSyncStatus.first())
            assertEquals(null, reader.lastSyncTime.first())
        } finally {
            writerJob.cancelAndJoin()
            readerJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
