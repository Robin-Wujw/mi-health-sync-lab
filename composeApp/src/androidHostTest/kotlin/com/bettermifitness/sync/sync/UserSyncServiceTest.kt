package com.bettermifitness.sync.sync

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Looper
import com.bettermifitness.sync.LocalizedTest
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserSyncServiceTest : LocalizedTest() {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun serviceEntersForegroundBeforeStartingAnyHealthWork() {
        val controller = Robolectric.buildService(UserSyncService::class.java).create()
        try {
            val notification = assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("active_user_sync", notification.channelId)
        } finally { controller.destroy() }
    }

    @Test fun redeliveryCarriesOnlyTheRunId() {
        val context = RuntimeEnvironment.getApplication()
        val original = userSyncIntent(context, "test-run")
        assertEquals(setOf("run_id"), original.extras?.keySet())
        assertEquals("test-run", Intent(original).userSyncRunId())
        assertEquals(null, Intent(original).putExtra("run_id", "../invalid").userSyncRunId())
        assertEquals(null, Intent(context, UserSyncService::class.java).userSyncRunId())
    }

    @Test fun detachingObserverLeavesDurableRequestAndReplacementWaitsForServiceTeardown() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val storeJob = Job()
        val journal = UserSyncJournal.create(temporary.root.resolve("journal.json").absolutePath.toPath(),
            CoroutineScope(storeJob + Dispatchers.IO))
        startKoin { modules(module { single { journal } }) }
        LiveTestSessionGate.activateForInstrumentation()
        val controller = Robolectric.buildService(UserSyncService::class.java).create()
        try {
            val end = Instant.parse("2026-11-19T12:00:00Z")
            val request = UserSyncRequest("detached", 21L, ActiveSyncSelection(1, setOf("sleep"), end - 1.days, end))
            val observer = async { UserSyncService.startAndAwait(application, request) }
            val intent = withTimeout(5_000) {
                var dispatched = shadowOf(application).nextStartedService
                while (dispatched == null) {
                    delay(10)
                    dispatched = shadowOf(application).nextStartedService
                }
                dispatched
            }
            observer.cancelAndJoin()
            assertEquals(SyncOutcome.AlreadyRunning, UserSyncService.startAndAwait(application, request))
            UserSyncService.stopPreviousService()
            controller.get().onStartCommand(intent, 0, 1)
            withTimeout(5_000) {
                while (journal.history().isEmpty()) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertEquals(SyncOutcome.STATUS_CANCELLED, journal.history().single().status)
            assertEquals(SyncOutcome.AlreadyRunning, UserSyncService.startAndAwait(application, request))
        } finally {
            controller.destroy()
            withTimeout(5_000) {
                val exit = async { UserSyncService.awaitPreviousServiceExit() }
                while (!exit.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            LiveTestSessionGate.resetForUnitTests()
            stopKoin()
            storeJob.cancelAndJoin()
        }
    }

    @Test fun systemRedeliveryOfReplacedServiceDoesNotDispatchUnpreparedCandidate() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val storeJob = Job()
        val journal = UserSyncJournal.create(temporary.root.resolve("journal.json").absolutePath.toPath(),
            CoroutineScope(storeJob + Dispatchers.IO))
        startKoin { modules(module { single { journal } }) }
        val controller = Robolectric.buildService(UserSyncService::class.java).create()
        try {
            val end = Instant.parse("2026-11-19T12:00:00Z")
            val older = UserSyncRequest("old", 21L, ActiveSyncSelection(30, setOf("sleep"), end - 30.days, end))
            val queued = UserSyncRequest("queued", 21L, ActiveSyncSelection(1, setOf("sleep"), end - 1.days, end))
            assertTrue(journal.accept(older))
            assertEquals(older, journal.claim(older.runId, "dead-process"))
            assertTrue(journal.stageReplacement(queued))
            controller.get().onStartCommand(userSyncIntent(application, older.runId), Service.START_FLAG_REDELIVERY, 1)
            withTimeout(5_000) {
                while (journal.history().isEmpty()) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertEquals("replaced", journal.history().single().status)
            assertEquals(queued, journal.recoverable.first())
            assertNull(shadowOf(application).nextStartedService)
        } finally {
            controller.destroy()
            withTimeout(5_000) {
                val exit = async { UserSyncService.awaitPreviousServiceExit() }
                while (!exit.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            stopKoin()
            storeJob.cancelAndJoin()
        }
    }
}
