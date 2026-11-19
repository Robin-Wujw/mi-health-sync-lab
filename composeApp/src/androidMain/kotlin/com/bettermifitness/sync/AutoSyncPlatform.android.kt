package com.bettermifitness.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bettermifitness.sync.i18n.L10n
import com.bettermifitness.sync.sync.MiSyncWorker
import java.util.concurrent.TimeUnit

actual object AutoSyncPlatform {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context {
        check(::appContext.isInitialized) {
            "AutoSyncPlatform.init(context) must be called from Application.onCreate"
        }
        return appContext
    }

    actual fun scheduleBackgroundRefresh(runImmediately: Boolean) {
        val ctx = requireContext()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        // OS minimum for periodic work is 15 minutes; 1 hour is a battery-friendly default
        // (still not exact-time — Doze / app standby may delay).
        val request = PeriodicWorkRequestBuilder<MiSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            MiSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        if (runImmediately) {
            // A user enabling auto-sync should not wait for the first periodic
            // window. This is deliberately omitted during process restoration:
            // KEEP only prevents overlap while a prior one-time job is pending,
            // so a completed job would otherwise run again on every cold start.
            val immediate = OneTimeWorkRequestBuilder<MiSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                MiSyncWorker.UNIQUE_IMMEDIATE_NAME,
                ExistingWorkPolicy.KEEP,
                immediate,
            )
        }
        // Data syncs already roll the session forward, but the keepalive must also exist
        // on its own so the toggle-off path is covered.
        scheduleSessionRefresh()
    }

    actual fun scheduleSessionRefresh() {
        val ctx = requireContext()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        // Check twice within the 6-hour renewal interval. Android may defer work;
        // foreground checks provide another opportunity without syncing health data.
        val request = PeriodicWorkRequestBuilder<MiSyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(MiSyncWorker.KEY_REFRESH_ONLY to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            MiSyncWorker.UNIQUE_REFRESH_NAME,
            // Migrate the old 12-hour request without cancelling active work or
            // resetting its enqueue time. KEEP would silently retain its old cadence.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    actual fun cancelBackgroundRefresh() {
        if (!::appContext.isInitialized) return
        // Only the data-sync work is cancelled: the session-refresh keepalive uses a
        // separate unique name and intentionally survives so logins outlive the toggle.
        WorkManager.getInstance(appContext).apply {
            cancelUniqueWork(MiSyncWorker.UNIQUE_WORK_NAME)
            cancelUniqueWork(MiSyncWorker.UNIQUE_IMMEDIATE_NAME)
        }
    }

    actual fun backgroundRefreshStatusLabel(): String {
        return if (isDebuggable(requireContext()) || isAndroidEmulator()) {
            L10n.text(L10n.backgroundAndroidStatus)
        } else {
            ""
        }
    }

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun isAndroidEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("android sdk built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("genymotion", ignoreCase = true) ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)

    actual fun supportsOpportunisticRefreshTest(): Boolean = false

    actual fun runOpportunisticRefreshTest(onDone: (String) -> Unit) {
        onDone("skipped")
    }

    actual fun supportsShortcutsHelp(): Boolean = false
}
