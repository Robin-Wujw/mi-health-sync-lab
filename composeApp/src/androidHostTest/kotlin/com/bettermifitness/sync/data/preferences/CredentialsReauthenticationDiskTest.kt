package com.bettermifitness.sync.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.sync.AutomaticSkip
import com.bettermifitness.sync.sync.AutomaticSkipReason
import com.mifitness.miclient.auth.MiCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CredentialsReauthenticationDiskTest {
    @Test fun lastAutomaticSkipSurvivesReopenAndRejectsLateWritesFromPreviousAccount(): Unit = runBlocking {
        val directory = Files.createTempDirectory("mi-automatic-skip-")
        val file = directory.resolve("session.preferences_pb")
        val firstJob = SupervisorJob()
        val first = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(firstJob + Dispatchers.IO),
            produceFile = { file.toString().toPath() })
        val account = MiCredentials("account", "security", "service", "pass", "device", "sg")
        try {
            val generation = CredentialsStore(first).saveNewAccount(account)
            val skipped = AutomaticSkip(AutomaticSkipReason.NOT_DUE, "2026-11-19T12:00:00Z", generation)
            SyncPreferences(first).storeAutomaticSkip(skipped)
            firstJob.cancelAndJoin()
            val secondJob = SupervisorJob()
            val reopened = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(secondJob + Dispatchers.IO),
                produceFile = { file.toString().toPath() })
            try {
                val preferences = SyncPreferences(reopened)
                assertEquals(skipped, preferences.lastAutomaticSkip.first())
                val replacement = CredentialsStore(reopened).saveNewAccount(account.copy(userId = "different"))
                assertNull(preferences.lastAutomaticSkip.first())
                val current = skipped.copy(reason = AutomaticSkipReason.DISABLED, generation = replacement)
                preferences.storeAutomaticSkip(current)
                preferences.storeAutomaticSkip(skipped)
                assertEquals(current, preferences.lastAutomaticSkip.first())
            } finally { secondJob.cancelAndJoin() }
        } finally {
            firstJob.cancelAndJoin()
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun rejectionSurvivesDiskReopenAndFreshLoginKeepsSettingsAndRecoveryBackup(): Unit = runBlocking {
        val directory = Files.createTempDirectory("mi-reauthentication-")
        val file = directory.resolve("session.preferences_pb")
        val firstJob = SupervisorJob()
        val first = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(firstJob + Dispatchers.IO),
            produceFile = { file.toString().toPath() })
        val credentials = MiCredentials("account", "security", "service", "pass", "device", "sg")
        val target = MiWorkoutImportTarget("s", "1", "outdoor_running", "running", 100, 200, 0, JsonObject(emptyMap()))
        val backup = WorkoutRestoreEntry(target, target.value, mapOf("0" to "AQID"), 1, true, verified = true)
        try {
            CredentialsStore(first).saveCredentials(credentials)
            SyncPreferences(first).setAutoSync(true)
            assertTrue(WorkoutRestoreLedger(first).replace("account", null, backup))
            CredentialsStore(first).markReauthenticationRequired()
            firstJob.cancelAndJoin()
            val secondJob = SupervisorJob()
            val second = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(secondJob + Dispatchers.IO),
                produceFile = { file.toString().toPath() })
            try {
                val reopened = CredentialsStore(second)
                assertTrue(reopened.requiresReauthentication())
                assertEquals(credentials, reopened.loadCredentials())
                reopened.saveCredentials(credentials.copy(serviceToken = "new-service", passToken = "new-pass"))
                assertFalse(reopened.requiresReauthentication())
                assertTrue(SyncPreferences(second).autoSync.first())
                assertEquals(backup, WorkoutRestoreLedger(second).load("account"))
                assertEquals("device", reopened.loadDeviceId())
            } finally { secondJob.cancelAndJoin() }
        } finally {
            firstJob.cancelAndJoin()
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
