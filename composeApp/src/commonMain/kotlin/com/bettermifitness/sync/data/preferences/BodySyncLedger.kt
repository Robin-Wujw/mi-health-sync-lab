package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Small, persistent attempt journal. A timed-out POST is never automatically
 * repeated; subsequent runs verify the same measurement first.
 */
class BodySyncLedger(
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private fun accountKey(account: String) =
        HealthRecordIds.version("body-account", account).toString()

    private fun attemptKey(account: String, fingerprint: String) =
        stringPreferencesKey(ATTEMPT_PREFIX + accountKey(account) + ":" + fingerprint)

    suspend fun attempt(account: String, fingerprint: String): String? =
        dataStore.data.first()[attemptKey(account, fingerprint)]?.substringAfter('|')

    suspend fun noteAttempt(account: String, fingerprint: String, status: String) {
        dataStore.edit { prefs ->
            val name = attemptKey(account, fingerprint)
            prefs[name] = "${now()}|$status"
        }
    }

    internal suspend fun beginUpload(account: String, fingerprint: String): BodyUploadDecision {
        val key = attemptKey(account, fingerprint)
        var decision: BodyUploadDecision = BodyUploadDecision.VerifyOnly
        dataStore.edit { prefs ->
            val previous = prefs[key]?.substringAfter('|')
            val rejection = previous?.split(':')?.takeIf { it.size == 3 && it[0] == RATE_LIMITED }
            val attempts = rejection?.get(1)?.toIntOrNull()
            val retryAt = rejection?.get(2)?.toLongOrNull()
            val timestamp = now()
            decision = when {
                previous != null && (attempts == null || retryAt == null) -> BodyUploadDecision.VerifyOnly
                attempts != null && attempts >= MAX_ATTEMPTS -> BodyUploadDecision.RetryLimit
                retryAt != null && timestamp < retryAt -> BodyUploadDecision.CoolingDown
                previous == null && !makeRoom(prefs) -> BodyUploadDecision.AtCapacity
                else -> BodyUploadDecision.Send((attempts ?: 0) + 1).also { prefs[key] = "$timestamp|pending" }
            }
        }
        return decision
    }

    private fun makeRoom(prefs: MutablePreferences): Boolean {
        val entries = prefs.asMap().entries.filter { it.key.name.startsWith(ATTEMPT_PREFIX) }
        val slotsNeeded = entries.size - MAX_ENTRIES + 1
        if (slotsNeeded <= 0) return true
        val safeToPrune = entries.filter { it.value.toString().substringAfter('|') == "verified" }
            .sortedBy { it.value.toString().substringBefore('|').toLongOrNull() ?: 0L }
        if (safeToPrune.size < slotsNeeded) return false
        safeToPrune.take(slotsNeeded).forEach { prefs.remove(stringPreferencesKey(it.key.name)) }
        return true
    }

    internal suspend fun noteRateLimit(account: String, fingerprint: String, sendCount: Int) {
        val retryAt = now() + RATE_LIMIT_DELAY_SECONDS * sendCount
        noteAttempt(account, fingerprint, "$RATE_LIMITED:$sendCount:$retryAt")
    }

    suspend fun forgetAttempt(account: String, fingerprint: String) {
        dataStore.edit { it.remove(attemptKey(account, fingerprint)) }
    }

    suspend fun lastExport(account: String): String? =
        dataStore.data.first()[stringPreferencesKey(EXPORT_PREFIX + accountKey(account))]

    suspend fun noteExport(account: String, fingerprint: String) {
        dataStore.edit { it[stringPreferencesKey(EXPORT_PREFIX + accountKey(account))] = fingerprint }
    }

    companion object {
        private const val ATTEMPT_PREFIX = "body_import_attempt:"
        private const val EXPORT_PREFIX = "body_export:"
        private const val RATE_LIMITED = "rejected429"
        private const val RATE_LIMIT_DELAY_SECONDS = 900L
        private const val MAX_ATTEMPTS = 3
        private const val MAX_ENTRIES = 128

        /** Whether a preference belongs to this journal. */
        fun ownsPreference(name: String): Boolean = name.startsWith(ATTEMPT_PREFIX) || name.startsWith(EXPORT_PREFIX)
    }
}

internal sealed interface BodyUploadDecision {
    data class Send(val attempt: Int) : BodyUploadDecision
    data object VerifyOnly : BodyUploadDecision
    data object CoolingDown : BodyUploadDecision
    data object RetryLimit : BodyUploadDecision
    data object AtCapacity : BodyUploadDecision
}
