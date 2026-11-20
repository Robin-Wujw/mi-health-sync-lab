package com.bettermifitness.sync.data.preferences

import com.mifitness.miclient.auth.MiCredentials
import com.bettermifitness.sync.sync.AutoSyncCheckpoint
import com.bettermifitness.sync.sync.AutoSyncContinuation
import com.bettermifitness.sync.sync.AutomaticRetry
import com.bettermifitness.sync.sync.AutomaticSkip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** ISP: auth token + credentials used by sync policy. */
interface CredentialsPort {
    val token: Flow<String?>
    suspend fun loadCredentials(): MiCredentials?
    suspend fun saveCredentials(credentials: MiCredentials)
    suspend fun accountGeneration(): Long = 0L

    /**
     * Epoch seconds of the last successful login or passport refresh, or null when unknown
     * (never signed in, or credentials stored by a build that predates this key).
     *
     * Used to roll the passport session forward before Xiaomi retires the current passToken.
     */
    suspend fun lastSessionRefreshEpochSeconds(): Long? = null

    /**
     * Stable device identity used across login, OTP, browser URL, and refresh.
     * Default is empty for fakes; the DataStore implementation generates once.
     */
    suspend fun loadDeviceId(): String = ""

    /** Same as [loadDeviceId] but generates and persists when missing. */
    suspend fun ensureDeviceId(): String = loadDeviceId()
}

data class SyncConfig(val rangeDays: Int, val enabledMetrics: Set<String>, val sourceCalendarZoneId: String? = null)

data class AutoSyncTiming(
    val lastAutomaticStart: Long? = null,
    val lastManualFinish: Long? = null,
    val pausedUntil: Long? = null,
    val retry: AutomaticRetry? = null,
) {
    fun isPaused(at: Long): Boolean = pausedUntil != null && pausedUntil - at in 1..3_600L

    fun isInManualCooldown(at: Long): Boolean = within(at, lastManualFinish, 900L)

    fun canRetry(at: Long): Boolean = !isPaused(at) && !isInManualCooldown(at)

    fun canStart(at: Long): Boolean = canRetry(at) && !within(at, lastAutomaticStart, 3_600L)

    private fun within(at: Long, previous: Long?, interval: Long): Boolean =
        previous != null && at - previous in 0 until interval
}

/** ISP: sync settings + last-run outcome persistence. */
interface SyncPreferencesPort {
    val autoSync: Flow<Boolean>
    val enabledMetrics: Flow<Set<String>>
    suspend fun captureSyncConfig(): SyncConfig = SyncConfig(syncRangeDays.first(), enabledMetrics.first(), readSourceCalendarZoneId())
    suspend fun readAutoSyncTiming(): AutoSyncTiming = AutoSyncTiming()
    suspend fun acceptAutomaticStart(at: Long): Boolean = autoSync.first()
    suspend fun recordManualFinish(at: Long) = Unit
    suspend fun pauseAutomaticUntil(at: Long) = Unit
    suspend fun storeAutomaticRetry(retry: AutomaticRetry?) = Unit
    suspend fun storeAutomaticSkip(skip: AutomaticSkip) = Unit
    suspend fun readAutomaticCheckpoints(keys: Set<String>): Map<String, AutoSyncCheckpoint> = emptyMap()
    suspend fun writeAutomaticCheckpoints(checkpoints: Map<String, AutoSyncCheckpoint>) = Unit
    suspend fun readAutomaticContinuation(): AutoSyncContinuation? = null
    suspend fun readSourceCalendarZoneId(): String? = null
    suspend fun saveAutomaticProgress(
        generation: Long,
        checkpoints: Map<String, AutoSyncCheckpoint>,
        continuation: AutoSyncContinuation?,
    ) {
        writeAutomaticCheckpoints(checkpoints)
    }
    val syncRangeDays: Flow<Int>
    /** Start/end of actual work, including failed or cancelled manual runs. */
    val lastSyncActivityTime: Flow<String?>
    suspend fun updateLastSyncActivity(timestamp: String)
    suspend fun updateLastSync(timestamp: String)
    suspend fun updateLastBackgroundSync(timestamp: String)
    suspend fun updateLastSyncOutcome(status: String, message: String?)
    suspend fun updateLastBackgroundSyncOutcome(status: String, message: String?)
}

/**
 * Single hot snapshot of user sync preferences.
 * [ready] is false until the first DataStore emission lands; screens must hold a
 * skeleton until then instead of rendering zero-metric placeholders.
 */
data class UserPrefsSnapshot(
    val ready: Boolean = false,
    val enabledMetrics: Set<String> = emptySet(),
    val syncRangeDays: Int = 7,
    val autoSync: Boolean = false,
    val lastSyncTime: String? = null,
    val lastBackgroundSyncTime: String? = null,
    val inboundTestMode: Boolean = false,
    val sourceCalendarZoneId: String? = null,
)

/** ISP: ensure Mi API session is usable. */
interface SyncSessionPort {
    suspend fun ensureActive(): Boolean

    val requiresReauthentication: Boolean get() = false

    /**
     * Re-mints the session when it has not been refreshed recently.
     *
     * Xiaomi rotates the passToken on every `serviceLogin` and keeps the superseded one
     * alive only briefly, so an install that sits idle (serviceToken still valid, no 401,
     * therefore no lazy refresh) can wake up holding a passToken the server has retired.
     * Refreshing on a cadence keeps the rotation chain alive the way Mi Fitness does.
     *
     * No-op when fresh, not signed in, or when the refresh fails — the lazy 401 path is
     * still responsible for surfacing errors to the user.
     */
    suspend fun refreshSessionIfStale() = Unit
}
