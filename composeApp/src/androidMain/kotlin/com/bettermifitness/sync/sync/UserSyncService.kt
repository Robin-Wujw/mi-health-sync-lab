package com.bettermifitness.sync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.bettermifitness.sync.i18n.L10n
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

/** Owns explicit user syncs after permission UI. Leaving a screen only detaches its observer. */
class UserSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ownedRequest: PendingUserSync? = null
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        showNotification(L10n.text(L10n.syncStatusSyncing))
    }

    private fun showNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val label = L10n.text(L10n.syncStatusSyncing)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, label, NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            notification.setContentIntent(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification.build())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.userSyncRunId()
        if (started) return if (id == ownedRequest?.id) START_REDELIVER_INTENT else START_NOT_STICKY
        if (id == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val accepted = synchronized(requestLock) {
            when {
                pending == null -> PendingUserSync(id).also { pending = it }
                pending?.id == id && pending?.attached == false -> pending
                else -> null
            }?.also {
                it.attached = true
                it.dispatched.complete(true)
            }
        } ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        started = true
        ownedRequest = accepted
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var outcome: SyncOutcome? = null
            var taskSummary: SyncTaskSummary? = null
            var journal: UserSyncJournal? = null
            try {
                val koin = KoinPlatform.getKoin()
                journal = koin.get<UserSyncJournal>()
                val claimed = journal.claimTask(id, processId) ?: return@launch
                val request = claimed.request
                if (LiveTestSessionGate.isActive) {
                    outcome = SyncOutcome.Skipped
                    return@launch
                }
                showNotification(L10n.textFmt(L10n.syncActiveRange, request.selection.rangeDays))
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "BetterMiFitnessSync:user-sync").also {
                    it.setReferenceCounted(false)
                    it.acquire(TimeUnit.MINUTES.toMillis(15))
                }
                outcome = withContext(Dispatchers.Default) {
                    koin.get<SyncCoordinator>().run(
                        requestHealthPermissions = false,
                        resetProgress = true,
                        request = request,
                        source = claimed.source,
                        onTaskFinished = { taskSummary = it },
                    )
                }
            } catch (_: UserSyncStopped) {
                outcome = SyncOutcome.Cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                outcome = SyncOutcome.Failed(L10n.text(L10n.syncFailed), retryable = true)
            } finally {
                withContext(NonCancellable) {
                    var result = if (accepted.stopRequested) SyncOutcome.Cancelled else outcome ?: interrupted()
                    try {
                        journal?.finish(id, outcome, processId, taskSummary)
                    } catch (_: Exception) {
                        result = interrupted()
                    } finally {
                        accepted.completion.complete(result)
                        releaseWakeLock()
                        if (!accepted.destroyed) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        synchronized(requestLock) {
            accepted.job = job
            if (accepted.stopRequested) job.cancel(UserSyncStopped())
        }
        job.invokeOnCompletion {
            synchronized(requestLock) {
                accepted.finished = true
                releaseIfFinished(accepted)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        synchronized(requestLock) {
            ownedRequest?.let {
                it.destroyed = true
                releaseIfFinished(it)
            }
        }
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "active_user_sync"
        private const val NOTIFICATION_ID = 101
        private val requestLock = Any()
        private val processId = UUID.randomUUID().toString()
        private var pending: PendingUserSync? = null
        private fun interrupted() = SyncOutcome.Failed(L10n.text(L10n.syncFailed), retryable = true)

        private fun releaseIfFinished(request: PendingUserSync) {
            if (!request.destroyed || !request.finished) return
            if (pending === request) pending = null
            request.released.complete(Unit)
        }

        private fun releaseUndispatched(request: PendingUserSync, outcome: SyncOutcome) {
            request.dispatched.complete(false)
            synchronized(requestLock) { if (pending === request) pending = null }
            request.completion.complete(outcome)
            request.released.complete(Unit)
        }

        suspend fun stopPreviousService() {
            val current = synchronized(requestLock) { pending } ?: return
            withContext(NonCancellable) {
                if (!current.dispatched.await()) return@withContext
                KoinPlatform.getKoin().get<UserSyncJournal>().stopping(current.id)
                synchronized(requestLock) {
                    current.stopRequested = true
                    current.job?.cancel(UserSyncStopped())
                }
            }
        }

        suspend fun awaitPreviousServiceExit() {
            synchronized(requestLock) { pending }?.released?.await()
        }

        suspend fun startAndAwait(
            context: Context,
            request: UserSyncRequest,
            fromQueue: Boolean = false,
            source: SyncTaskSource = SyncTaskSource.MANUAL,
            canDispatch: () -> Boolean = { true },
        ): SyncOutcome {
            val accepted = synchronized(requestLock) {
                if (pending != null || !canDispatch()) null else PendingUserSync(request.runId).also { pending = it }
            } ?: return SyncOutcome.AlreadyRunning
            withContext(NonCancellable) {
                var journal: UserSyncJournal? = null
                var stored = false
                try {
                    journal = KoinPlatform.getKoin().get<UserSyncJournal>()
                    stored = journal.accept(request, fromQueue, source)
                    if (!stored) {
                        releaseUndispatched(accepted, SyncOutcome.AlreadyRunning)
                        return@withContext
                    }
                    if (!canDispatch()) {
                        journal.finish(request.runId, SyncOutcome.Cancelled)
                        releaseUndispatched(accepted, SyncOutcome.Cancelled)
                        return@withContext
                    }
                    context.startForegroundService(userSyncIntent(context, request.runId))
                    accepted.dispatched.complete(true)
                } catch (_: Exception) {
                    try {
                        if (stored) journal?.finish(request.runId, null)
                    } catch (_: Exception) {
                        accepted.completion.complete(interrupted())
                    } finally {
                        releaseUndispatched(accepted, interrupted())
                    }
                }
            }
            return accepted.completion.await()
        }
    }
}

private class PendingUserSync(val id: String) {
    val dispatched = CompletableDeferred<Boolean>()
    val completion = CompletableDeferred<SyncOutcome>()
    val released = CompletableDeferred<Unit>()
    var attached = false
    var destroyed = false
    var finished = false
    @Volatile var stopRequested = false
    var job: Job? = null
}

internal fun userSyncIntent(context: Context, id: String): Intent =
    Intent(context, UserSyncService::class.java).putExtra("run_id", id)

internal fun Intent.userSyncRunId(): String? = getStringExtra("run_id")?.takeIf(::validRunId)
