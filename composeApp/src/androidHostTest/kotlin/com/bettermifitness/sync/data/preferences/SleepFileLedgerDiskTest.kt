package com.bettermifitness.sync.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SleepFileLedgerDiskTest {
    @Test fun pendingBodyReservationSurvivesDiskReopenAndCannotBeReset(): Unit = runBlocking {
        val directory = Files.createTempDirectory("sleep-file-ledger-").toFile()
        val file = directory.resolve("journal.preferences_pb")
        val jobs = mutableListOf<Job>()
        fun open() = SupervisorJob().let { job ->
            jobs += job
            PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { file.absolutePath.toPath() }) to job
        }
        try {
            val initial = SleepFileEntry("source-fingerprint", "body-fingerprint", 1_800_000_601, 1)
            val attempted = initial.copy(attempted = true)
            val (store, writerJob) = open()
            val writer = SleepFileLedger(store)
            assertTrue(writer.change("account-a", "sleep", null, initial))
            assertFalse(writer.change("account-a", "sleep", null, initial))
            assertTrue(writer.change("account-a", "sleep", initial, attempted))
            writerJob.cancelAndJoin()
            val (reopened, readerJob) = open()
            val reader = SleepFileLedger(reopened)
            assertEquals(attempted, reader.load("account-a", "sleep"))
            assertNull(reader.load("account-b", "sleep"))
            assertFailsWith<IllegalArgumentException> { reader.change("account-a", "sleep", attempted, initial) }
            assertFailsWith<IllegalArgumentException> {
                reader.change("account-a", "sleep", attempted, initial.copy(generation = 2))
            }
            assertTrue(reader.change("account-a", "sleep", attempted, attempted.copy(verified = true)))
            readerJob.cancelAndJoin()
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            directory.deleteRecursively()
        }
    }
}
