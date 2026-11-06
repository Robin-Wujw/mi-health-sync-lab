package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.mifitness.miclient.auth.MiCredentials
import kotlinx.coroutines.flow.Flow

/**
 * Façade over [CredentialsStore] + [SyncPreferences] for call sites that need both
 * (logout, full clear). Prefer injecting the focused stores when possible (ISP).
 */
class TokenStore(
    private val dataStore: DataStore<Preferences>,
    val credentials: CredentialsStore = CredentialsStore(dataStore),
    val sync: SyncPreferences = SyncPreferences(dataStore),
) {
    val token: Flow<String?> get() = credentials.token
    val miUserId: Flow<String?> get() = credentials.miUserId
    val region: Flow<String?> get() = credentials.region

    val lastSyncTime: Flow<String?> get() = sync.lastSyncTime
    val lastBackgroundSyncTime: Flow<String?> get() = sync.lastBackgroundSyncTime
    val autoSync: Flow<Boolean> get() = sync.autoSync
    val enabledMetrics: Flow<Set<String>> get() = sync.enabledMetrics
    val syncRangeDays: Flow<Int> get() = sync.syncRangeDays

    suspend fun saveCredentials(c: MiCredentials) = credentials.saveCredentials(c)

    suspend fun loadCredentials(): MiCredentials? = credentials.loadCredentials()

    suspend fun updateLastSync(timestamp: String) = sync.updateLastSync(timestamp)

    suspend fun updateLastBackgroundSync(timestamp: String) = sync.updateLastBackgroundSync(timestamp)

    suspend fun setAutoSync(enabled: Boolean) = sync.setAutoSync(enabled)

    suspend fun setMetricEnabled(key: String, enabled: Boolean) =
        sync.setMetricEnabled(key, enabled)

    suspend fun setSyncRangeDays(days: Int) = sync.setSyncRangeDays(days)

    /**
     * Logout wipe. Keeps the stable device identity so the next sign-in is
     * recognized as the same trusted install instead of a brand-new device.
     *
     * Also keeps the upload journals, which share this preferences file. They hold no
     * tokens or health values and are namespaced by account. Losing them would turn this
     * app's own uploads into records of unknown origin, which can never be corrected, and
     * would lift the lock that stops an uncertain upload from being repeated.
     */
    suspend fun clear() {
        // Logout wipes tokens but keeps the trusted device id, so the next
        // sign-in presents the same install instead of a new device (no repeat OTP).
        val deviceId = credentials.loadDeviceId()
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filterNot { HealthImportLedger.ownsPreference(it.name) || BodySyncLedger.ownsPreference(it.name) }
                .forEach { prefs.remove(it) }
        }
        credentials.restoreDeviceId(deviceId.ifBlank { credentials.ensureDeviceId() })
    }

    companion object {
        val ALL_METRIC_KEYS: Set<String> get() = SyncPreferences.ALL_METRIC_KEYS
    }
}
