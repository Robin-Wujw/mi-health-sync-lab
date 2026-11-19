package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.mifitness.miclient.auth.MiCredentials
import kotlinx.coroutines.flow.Flow

/**
 * Façade over [CredentialsStore] + [SyncPreferences] for call sites that need both
 * (logout, full clear). Prefer injecting the focused stores when possible (ISP).
 */
class TokenStore(
    dataStore: DataStore<Preferences>,
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
     * Logout clears credentials and activity, preserving sync settings and the stable
     * device identity so the next sign-in is recognized as the same trusted install.
     *
     * Also keeps the account-scoped upload journals and explicit workout recovery backup
     * in this app-private preferences file. Losing the upload journals would turn this
     * app's own uploads into records of unknown origin, which can never be corrected, and
     * would lift the lock that stops an uncertain upload from being repeated.
     */
    suspend fun clear() {
        credentials.clearCredentials()
        sync.clearSyncHistory()
    }

    companion object {
        val ALL_METRIC_KEYS: Set<String> get() = SyncPreferences.ALL_METRIC_KEYS
    }
}
