package com.bettermifitness.sync.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
class WorkoutRestoreLedgerDiskTest {
    @Test fun backupAndAttemptsSurviveReopenWithAccountIsolationAndNoBudgetReset(): Unit = runBlocking {
        val directory = Files.createTempDirectory("workout-restore-")
        val path = directory.resolve("restore.preferences_pb").toString().toPath()
        val target = MiWorkoutImportTarget("s", "1", "outdoor_running", "running", 100, 200, 0, JsonObject(emptyMap()))
        val backup = WorkoutRestoreEntry(target, target.value, mapOf("0" to "AQID"), 1, true)
        val firstJob = SupervisorJob()
        val first = WorkoutRestoreLedger(PreferenceDataStoreFactory.createWithPath(
            scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { path }))
        try {
            assertTrue(first.replace("a", null, backup))
            assertNull(first.load("b"))
            val attempted = backup.copy(fileAttempts = setOf("0"), summaryAttempted = true)
            assertTrue(first.replace("a", backup, attempted))
            assertFalse(first.replace("a", backup, attempted))
            firstJob.cancelAndJoin()
            val secondJob = SupervisorJob()
            val second = WorkoutRestoreLedger(PreferenceDataStoreFactory.createWithPath(
                scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { path }))
            try {
                assertEquals(attempted, second.load("a"))
                assertNull(second.load("b"))
                assertFailsWith<IllegalArgumentException> { second.replace("a", attempted, null) }
                assertFailsWith<IllegalArgumentException> { second.replace("a", attempted, backup) }
                assertTrue(second.replace("a", attempted, attempted.copy(verified = true)))
            } finally { secondJob.cancelAndJoin() }
        } finally {
            firstJob.cancelAndJoin()
            directory.toFile().deleteRecursively()
        }
    }
}
