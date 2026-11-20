package com.bettermifitness.sync.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bettermifitness.sync.LocalizedTest
import com.bettermifitness.sync.sync.AutoSyncCheckpoint
import com.bettermifitness.sync.sync.AutoSyncContinuation
import com.bettermifitness.sync.sync.AutoSyncSlice
import com.bettermifitness.sync.sync.AutoSyncWindow
import com.bettermifitness.sync.zepp.ZeppStream
import com.mifitness.miclient.auth.MiCredentials
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutomaticPauseDiskTest : LocalizedTest() {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pauseSurvivesReopeningPreferencesAndEndsAtItsDeadline() = runBlocking {
        val path = temporary.root.resolve("sync.preferences_pb").absolutePath.toPath()
        val deadline = 1_795_088_400L
        val writer = Job()
        try {
            val first = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(writer + Dispatchers.IO)) { path }
            SyncPreferences(first).pauseAutomaticUntil(deadline)
        } finally {
            writer.cancelAndJoin()
        }
        val reader = Job()
        try {
            val reopened = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(reader + Dispatchers.IO)) { path }
            val timing = SyncPreferences(reopened).readAutoSyncTiming()
            assertEquals(deadline, timing.pausedUntil)
            assertFalse(timing.canStart(deadline - 1))
            assertTrue(timing.canStart(deadline))
        } finally {
            reader.cancelAndJoin()
        }
    }

    @Test fun metricCheckpointsAndDisjointCoverageSurviveReopeningPreferences() = runBlocking {
        val path = temporary.root.resolve("watermarks.preferences_pb").absolutePath.toPath()
        val checkpoints = mapOf("account-a-steps" to AutoSyncCheckpoint(100, listOf(AutoSyncWindow(200, 300)),
            historicalScanFrom = 300),
            "account-a-sleep" to AutoSyncCheckpoint(50))
        val writer = Job()
        try {
            val first = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(writer + Dispatchers.IO)) { path }
            SyncPreferences(first).writeAutomaticCheckpoints(checkpoints)
        } finally {
            writer.cancelAndJoin()
        }
        val reader = Job()
        try {
            val reopened = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(reader + Dispatchers.IO)) { path }
            assertEquals(checkpoints, SyncPreferences(reopened).readAutomaticCheckpoints(checkpoints.keys + "account-b-steps"))
        } finally {
            reader.cancelAndJoin()
        }
    }

    @Test fun continuationAndCoverageReopenTogetherAndRejectAnOldAccountWriter() = runBlocking {
        val path = temporary.root.resolve("automatic-plan.preferences_pb").absolutePath.toPath()
        val account = MiCredentials("account", "secret", "service", "pass", "device", "sg")
        val oldSlice = AutoSyncSlice(AutoSyncWindow(100, 200), setOf("steps"), historical = true, timeouts = 1)
        val zepp = SyncPreferences.ZEPP_CLOUD_IMPORT_KEY
        val zeppLeaf = AutoSyncSlice(AutoSyncWindow(150, 200), setOf(zepp), historical = true,
            zeppSplitDepth = 1, requiredZeppEvents = setOf(ZeppStream.PAI), failedLeafAttempts = 1)
        val checkpoints = mapOf("key" to AutoSyncCheckpoint(100, listOf(AutoSyncWindow(200, 300))))
        val writer = Job()
        val plan = try {
            val first = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(writer + Dispatchers.IO)) { path }
            val generation = CredentialsStore(first).saveNewAccount(account)
            val original = AutoSyncContinuation("original-plan", generation, setOf("steps", zepp),
                mapOf("steps" to "key", zepp to "zepp-key"), listOf(oldSlice, zeppLeaf))
            val preferences = SyncPreferences(first)
            preferences.setAutoSync(true)
            preferences.saveAutomaticProgress(generation, checkpoints, original)
            original
        } finally {
            writer.cancelAndJoin()
        }
        val reader = Job()
        try {
            val reopened = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(reader + Dispatchers.IO)) { path }
            val preferences = SyncPreferences(reopened)
            assertEquals(plan, preferences.readAutomaticContinuation())
            assertEquals(checkpoints, preferences.readAutomaticCheckpoints(setOf("key")))
            val current = CredentialsStore(reopened).saveNewAccount(account.copy(userId = "different"))
            assertFalse(plan.matches(current, plan.enabled, plan.checkpointKeys))
            preferences.setAutoSync(false)
            preferences.saveAutomaticProgress(current, emptyMap(), plan.copy(generation = current, id = "after-disable"))
            preferences.saveAutomaticProgress(plan.generation, mapOf("key" to AutoSyncCheckpoint(1000)), plan)
            assertNull(preferences.readAutomaticContinuation())
            assertEquals(checkpoints, preferences.readAutomaticCheckpoints(setOf("key")))
        } finally {
            reader.cancelAndJoin()
        }
    }
}
