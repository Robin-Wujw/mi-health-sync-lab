package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mifitness.miclient.auth.MiCredentials
import com.mifitness.miclient.auth.MiRegion
import com.mifitness.miclient.auth.PassportAuthUtils
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persisted Mi Account credentials and session identity (SRP: auth storage only).
 */
class CredentialsStore(
    private val dataStore: DataStore<Preferences>,
) : CredentialsPort {
    override val token: Flow<String?> = dataStore.data.map { it[TOKEN_KEY] }

    val miUserId: Flow<String?> = dataStore.data.map { it[MI_USER_ID_KEY] }

    /** Effective region used by the API client (after discovery or STS provisional). */
    val region: Flow<String?> = dataStore.data.map { prefs ->
        MiRegion.normalizeCode(prefs[REGION_KEY] ?: prefs[LOGIN_REGION_KEY])
    }

    val loginRegion: Flow<String?> = dataStore.data.map { it[LOGIN_REGION_KEY] }

    val regionDiscoverySource: Flow<String?> =
        dataStore.data.map { it[REGION_DISCOVERY_SOURCE_KEY] }

    val regionLatestEpoch: Flow<String?> =
        dataStore.data.map { it[REGION_LATEST_EPOCH_KEY] }

    override suspend fun saveCredentials(credentials: MiCredentials) {
        saveNewAccount(credentials)
    }

    internal suspend fun saveNewAccount(credentials: MiCredentials): Long = dataStore.edit { preferences ->
        preferences[ACCOUNT_GENERATION_KEY] = generationOf(preferences) + 1
        preferences.remove(REGION_KEY)
        preferences.remove(REGION_DISCOVERY_SOURCE_KEY)
        preferences.remove(REGION_LATEST_EPOCH_KEY)
        persistCredentials(preferences, credentials)
    }.let(::generationOf)

    override suspend fun accountGeneration(): Long = generationOf(dataStore.data.first())

    internal suspend fun refreshCredentials(generation: Long, credentials: MiCredentials): Boolean =
        editAccount(generation) { persistCredentials(it, credentials) }

    internal suspend fun refreshPassToken(generation: Long, passToken: String): Boolean =
        editAccount(generation) { it[PASS_TOKEN_KEY] = passToken }

    internal suspend fun markReauthenticationRequired(generation: Long): Boolean =
        editAccount(generation) { it[REAUTHENTICATION_REQUIRED_KEY] = true }

    private suspend fun editAccount(generation: Long, action: (MutablePreferences) -> Unit): Boolean {
        val updated = dataStore.edit { preferences ->
            if (generationOf(preferences) == generation && preferences[TOKEN_KEY] != null) action(preferences)
        }
        return generationOf(updated) == generation && updated[TOKEN_KEY] != null
    }

    private fun generationOf(preferences: Preferences): Long = preferences[ACCOUNT_GENERATION_KEY] ?: 0L

    private fun persistCredentials(preferences: MutablePreferences, credentials: MiCredentials) {
        preferences[SESSION_REFRESHED_AT_KEY] = Clock.System.now().epochSeconds.toString()
        preferences[TOKEN_KEY] = credentials.serviceToken
        preferences[MI_USER_ID_KEY] = credentials.userId
        preferences[SSECURITY_KEY] = credentials.ssecurity
        preferences[PASS_TOKEN_KEY] = credentials.passToken
        preferences.remove(REAUTHENTICATION_REQUIRED_KEY)
        if (credentials.deviceId.isNotBlank()) {
            preferences[DEVICE_ID_KEY] = credentials.deviceId
        }
        if (credentials.cUserId.isNotBlank()) {
            preferences[C_USER_ID_KEY] = credentials.cUserId
        } else {
            preferences.remove(C_USER_ID_KEY)
        }

        val loginFromSts = MiRegion.normalizeCode(credentials.region)
        preferences[LOGIN_REGION_KEY] = loginFromSts
        if (preferences[REGION_KEY].isNullOrBlank()) {
            preferences[REGION_KEY] = loginFromSts
        }
        preferences.remove(REGION_MODE_KEY)
    }

    suspend fun requiresReauthentication(): Boolean =
        dataStore.data.first()[REAUTHENTICATION_REQUIRED_KEY] == true

    suspend fun markReauthenticationRequired() { markReauthenticationRequired(accountGeneration()) }

    override suspend fun loadCredentials(): MiCredentials? = decodeCredentials(dataStore.data.first())

    internal suspend fun loadAccount(): StoredMiAccount? {
        val prefs = dataStore.data.first()
        val credentials = decodeCredentials(prefs) ?: return null
        return StoredMiAccount(generationOf(prefs), credentials, prefs[REAUTHENTICATION_REQUIRED_KEY] == true)
    }

    private fun decodeCredentials(prefs: Preferences): MiCredentials? {
        val serviceToken = prefs[TOKEN_KEY] ?: return null
        val userId = prefs[MI_USER_ID_KEY] ?: return null
        val ssecurity = prefs[SSECURITY_KEY] ?: return null
        // passToken may be blank on legacy installs; still load for in-memory API until re-login.
        val passToken = prefs[PASS_TOKEN_KEY] ?: ""
        val deviceId = prefs[DEVICE_ID_KEY] ?: ""
        val cUserId = prefs[C_USER_ID_KEY] ?: ""
        val effective = MiRegion.normalizeCode(
            prefs[REGION_KEY] ?: prefs[LOGIN_REGION_KEY],
        )
        return MiCredentials(
            userId = userId,
            ssecurity = ssecurity,
            serviceToken = serviceToken,
            passToken = passToken,
            deviceId = deviceId,
            region = effective,
            cUserId = cUserId,
        )
    }

    override suspend fun lastSessionRefreshEpochSeconds(): Long? =
        dataStore.data.first()[SESSION_REFRESHED_AT_KEY]?.toLongOrNull()

    /**
     * Stable device identity Xiaomi uses to decide whether email+password needs OTP.
     * Generated once and reused for every login, OTP, browser URL, and refresh,
     * mirroring the official app's cached hashed device id. Never blank after return.
     */
    override suspend fun ensureDeviceId(): String {
        val prefs = dataStore.data.first()
        prefs[DEVICE_ID_KEY]?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = PassportAuthUtils.generateDeviceId()
        dataStore.edit { it[DEVICE_ID_KEY] = generated }
        return generated
    }

    /** Current stored device identity, or empty when never generated. */
    override suspend fun loadDeviceId(): String = dataStore.data.first()[DEVICE_ID_KEY] ?: ""

    /**
     * Restores a previously trusted device identity without minting a new one.
     * Used by logout, which wipes tokens but must keep presenting the same
     * install to Xiaomi on the next sign-in.
     */
    suspend fun restoreDeviceId(deviceId: String) {
        if (deviceId.isBlank()) return
        dataStore.edit { it[DEVICE_ID_KEY] = deviceId }
    }

    /**
     * Persist auto-discovery winner and metadata for Settings.
     */
    internal suspend fun setDiscoveredRegion(generation: Long, result: MiRegion.DiscoveryResult): Boolean =
        editAccount(generation) { preferences ->
            preferences[REGION_KEY] = MiRegion.normalizeCode(result.region)
            preferences[REGION_DISCOVERY_SOURCE_KEY] = result.source.name
            if (result.latestEpochSec != null) {
                preferences[REGION_LATEST_EPOCH_KEY] = result.latestEpochSec.toString()
            } else {
                preferences.remove(REGION_LATEST_EPOCH_KEY)
            }
            preferences.remove(REGION_MODE_KEY)
        }

    /** Clears session credentials but keeps the stable device identity. */
    suspend fun clearCredentials() {
        dataStore.edit { preferences ->
            preferences[ACCOUNT_GENERATION_KEY] = generationOf(preferences) + 1
            preferences.remove(TOKEN_KEY)
            preferences.remove(MI_USER_ID_KEY)
            preferences.remove(REGION_KEY)
            preferences.remove(REGION_MODE_KEY)
            preferences.remove(LOGIN_REGION_KEY)
            preferences.remove(REGION_DISCOVERY_SOURCE_KEY)
            preferences.remove(REGION_LATEST_EPOCH_KEY)
            preferences.remove(SSECURITY_KEY)
            preferences.remove(PASS_TOKEN_KEY)
            preferences.remove(C_USER_ID_KEY)
            preferences.remove(SESSION_REFRESHED_AT_KEY)
            preferences.remove(REAUTHENTICATION_REQUIRED_KEY)
        }
    }

    companion object {
        internal fun signedInGeneration(preferences: Preferences): Long? =
            (preferences[ACCOUNT_GENERATION_KEY] ?: 0L).takeUnless { preferences[TOKEN_KEY].isNullOrBlank() }

        private val ACCOUNT_GENERATION_KEY = longPreferencesKey("mi_account_generation")
        private val TOKEN_KEY = stringPreferencesKey("bearer_token")
        private val MI_USER_ID_KEY = stringPreferencesKey("mi_user_id")
        private val REGION_KEY = stringPreferencesKey("region")
        private val REGION_MODE_KEY = stringPreferencesKey("region_mode") // legacy
        private val LOGIN_REGION_KEY = stringPreferencesKey("login_region")
        private val REGION_DISCOVERY_SOURCE_KEY = stringPreferencesKey("region_discovery_source")
        private val REGION_LATEST_EPOCH_KEY = stringPreferencesKey("region_latest_epoch")
        private val SSECURITY_KEY = stringPreferencesKey("ssecurity")
        private val PASS_TOKEN_KEY = stringPreferencesKey("pass_token")
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        private val C_USER_ID_KEY = stringPreferencesKey("c_user_id")
        private val SESSION_REFRESHED_AT_KEY = stringPreferencesKey("session_refreshed_at")
        private val REAUTHENTICATION_REQUIRED_KEY = booleanPreferencesKey("mi_reauthentication_required")
    }
}

internal data class StoredMiAccount(val generation: Long, val credentials: MiCredentials, val rejected: Boolean)
