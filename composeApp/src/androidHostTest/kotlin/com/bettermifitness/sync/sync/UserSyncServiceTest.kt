package com.bettermifitness.sync.sync

import android.app.Notification
import com.bettermifitness.sync.LocalizedTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UserSyncServiceTest : LocalizedTest() {
    @Test fun serviceEntersForegroundBeforeStartingAnyHealthWork() {
        val controller = Robolectric.buildService(UserSyncService::class.java).create()
        try {
            val notification = assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("active_user_sync", notification.channelId)
        } finally { controller.destroy() }
    }

    @Test fun detachingUiDoesNotCancelTheServiceOwnedRequestAndDestroyReleasesIt() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val observer = async(start = CoroutineStart.UNDISPATCHED) { UserSyncService.startAndAwait(context) }
        observer.cancelAndJoin()
        assertEquals(SyncOutcome.AlreadyRunning, UserSyncService.startAndAwait(context))
        val controller = Robolectric.buildService(UserSyncService::class.java).create()
        controller.destroy()
        val next = async(start = CoroutineStart.UNDISPATCHED) { UserSyncService.startAndAwait(context) }
        assertEquals(false, next.isCompleted)
        next.cancelAndJoin()
        Robolectric.buildService(UserSyncService::class.java).create().destroy()
    }
}
