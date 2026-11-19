package com.bettermifitness.sync.lab

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.bettermifitness.sync.data.preferences.SyncPreferences
import kotlinx.coroutines.flow.first

/** Flags/counts only. Preferences are not changed except the normal once-only default migration. */
internal suspend fun inspectWorkoutSyncSettings(context: Context, prefs: SyncPreferences): String {
    val selected = prefs.enabledMetrics.first()
    val permissions = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    val recentBackground = runCatching {
        java.time.Duration.between(java.time.Instant.parse(prefs.lastBackgroundSyncTime.first()),
            java.time.Instant.now()).seconds in 0..300
    }.getOrDefault(false)
    val backgroundStatus = prefs.lastBackgroundSyncStatus.first()?.takeIf {
        it in setOf("success", "completed_with_skips", "no_data", "partial_success", "failed", "skipped")
    } ?: "UNKNOWN"
    val statusCodes = setOf("success", "completed_with_skips", "no_data", "partial_success", "failed", "skipped",
        "cancelled", "not_logged_in", "health_unavailable", "already_running")
    fun age(timestamp: String?): Long? = runCatching {
        java.time.Duration.between(java.time.Instant.parse(timestamp), java.time.Instant.now()).seconds
    }.getOrNull()
    val koin = org.koin.mp.KoinPlatform.getKoin()
    val selectedKeys = (selected intersect (SyncPreferences.ALL_METRIC_KEYS + SyncPreferences.INBOUND_METRIC_KEYS +
        SyncPreferences.ZEPP_CLOUD_METRIC_KEYS)).sorted().joinToString(",")
    return listOf(
        "normal_sync.last_status=" + (prefs.lastSyncStatus.first()?.takeIf { it in statusCodes } ?: "UNKNOWN"),
        "normal_sync.last_verified_seconds_ago=" + age(prefs.lastSyncTime.first()),
        "normal_sync.last_activity_seconds_ago=" + age(prefs.lastSyncActivityTime.first()),
        "normal_sync.range_days=${prefs.syncRangeDays.first()}",
        "normal_sync.selected_keys=$selectedKeys",
        "normal_sync.session_reauthentication_required=" +
            koin.get<com.bettermifitness.sync.data.MiSessionManager>().requiresReauthentication,
        "normal_sync.zepp_connected=" + koin.get<com.bettermifitness.sync.zepp.ZeppSessionStore>().connected.first(),
        "workout_settings.auto_sync=${prefs.autoSync.first()}",
        "workout_settings.workout_import=${SyncPreferences.WORKOUTS_IMPORT_KEY in selected}",
        "workout_settings.vo2max_import=${SyncPreferences.VO2_MAX_IMPORT_KEY in selected}",
        "workout_settings.background_read=" + ("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" in permissions),
        "workout_settings.background_result_recorded=${prefs.lastBackgroundSyncTime.first() != null}",
        "workout_settings.background_result_within_5min=$recentBackground",
        "workout_settings.background_status=$backgroundStatus",
        "workout_settings.acceptance_worker_state=" + backgroundAcceptanceState(context),
        "workout_settings.acceptance_worker_outcome=" + backgroundAcceptanceOutcome(context),
        "workout_settings.acceptance_worker_busy_owner=" + backgroundAcceptanceOutcome(context, "sync_busy_owner"),
    ).joinToString("\n")
}
