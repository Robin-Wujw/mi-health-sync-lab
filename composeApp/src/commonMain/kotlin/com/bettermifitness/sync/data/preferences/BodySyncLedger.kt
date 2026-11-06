package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Small, persistent attempt journal. A timed-out POST is never automatically
 * repeated; subsequent runs verify the same measurement first.
 */
class BodySyncLedger(private val dataStore: DataStore<Preferences>) {
    private fun accountKey(account: String) =
        HealthRecordIds.version("body-account", account).toString()

    private fun attemptKey(account: String, fingerprint: String) =
        stringPreferencesKey(ATTEMPT_PREFIX + accountKey(account) + ":" + fingerprint)

    suspend fun attempt(account: String, fingerprint: String): String? =
        dataStore.data.first()[attemptKey(account, fingerprint)]?.substringAfter('|')

    suspend fun noteAttempt(account: String, fingerprint: String, status: String) {
        dataStore.edit { prefs ->
            val name = attemptKey(account, fingerprint)
            prefs[name] = Clock.System.now().epochSeconds.toString() + "|" + status
            val entries = prefs.asMap().entries
                .filter { it.key.name.startsWith(ATTEMPT_PREFIX) }
                .sortedBy { it.value.toString().substringBefore('|').toLongOrNull() ?: 0L }
            entries.take((entries.size - 128).coerceAtLeast(0)).forEach {
                prefs.remove(stringPreferencesKey(it.key.name))
            }
        }
    }

    suspend fun forgetAttempt(account: String, fingerprint: String) {
        dataStore.edit { it.remove(attemptKey(account, fingerprint)) }
    }

    suspend fun resetPendingImports() {
        dataStore.edit { prefs ->
            prefs.asMap().entries.filter {
                it.key.name.startsWith(EXPORT_PREFIX) ||
                    (it.key.name.startsWith(ATTEMPT_PREFIX) && it.value.toString().substringAfter('|') != "verified")
            }.map { it.key.name }.forEach { prefs.remove(stringPreferencesKey(it)) }
        }
    }

    suspend fun lastExport(account: String): String? =
        dataStore.data.first()[stringPreferencesKey(EXPORT_PREFIX + accountKey(account))]

    suspend fun noteExport(account: String, fingerprint: String) {
        dataStore.edit { it[stringPreferencesKey(EXPORT_PREFIX + accountKey(account))] = fingerprint }
    }

    companion object {
        private const val ATTEMPT_PREFIX = "body_import_attempt:"
        private const val EXPORT_PREFIX = "body_export:"
    }
}
