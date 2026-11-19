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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** Owns explicit user syncs after permission UI. Leaving a screen only detaches its observer. */
class UserSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var completion: CompletableDeferred<SyncOutcome>? = null
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        completion = synchronized(requestLock) { pending }
        val manager = getSystemService(NotificationManager::class.java)
        val label = L10n.text(L10n.syncStatusSyncing)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, label, NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(label)
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
        if (started) return START_REDELIVER_INTENT
        started = true
        completion = synchronized(requestLock) { pending ?: CompletableDeferred<SyncOutcome>().also { pending = it } }
        if (LiveTestSessionGate.isActive) {
            completion?.complete(SyncOutcome.Skipped)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "BetterMiFitnessSync:user-sync").also {
            it.setReferenceCounted(false)
            it.acquire(TimeUnit.MINUTES.toMillis(15))
        }
        scope.launch {
            val automatic = intent?.getBooleanExtra(EXTRA_AUTOMATIC, false) == true
            var outcome: SyncOutcome = interrupted()
            try {
                outcome = KoinPlatform.getKoin().get<SyncCoordinator>().run(
                    requestHealthPermissions = false,
                    requireAutoSync = automatic,
                    resetProgress = !automatic,
                    userInitiated = !automatic,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                outcome = SyncOutcome.Failed(L10n.text(L10n.syncFailed), retryable = true)
            } finally {
                completion?.complete(outcome)
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        // System process reclamation may redeliver this explicit run. Normal journals
        // reconcile any uncertain write; an app force-stop is never overridden.
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        completion?.complete(interrupted())
        scope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        completion?.complete(interrupted())
        synchronized(requestLock) { if (pending === completion) pending = null }
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_AUTOMATIC = "automatic"
        private const val CHANNEL = "active_user_sync"
        private const val NOTIFICATION_ID = 101
        private val requestLock = Any()
        private var pending: CompletableDeferred<SyncOutcome>? = null
        private fun interrupted() = SyncOutcome.Failed(L10n.text(L10n.syncFailed), retryable = true)

        suspend fun startAndAwait(context: Context, automatic: Boolean = false): SyncOutcome {
            val request = synchronized(requestLock) {
                if (pending != null) null else CompletableDeferred<SyncOutcome>().also { pending = it }
            } ?: return SyncOutcome.AlreadyRunning
            try {
                context.startForegroundService(Intent(context, UserSyncService::class.java).putExtra(EXTRA_AUTOMATIC, automatic))
            } catch (_: Exception) {
                synchronized(requestLock) { if (pending === request) pending = null }
                request.complete(interrupted())
            }
            // Await cancellation does not cancel the parentless service-owned request.
            return request.await()
        }
    }
}
