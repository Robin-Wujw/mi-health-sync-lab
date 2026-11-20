package com.bettermifitness.sync.data

import com.bettermifitness.sync.data.api.MiDirectApi
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.data.preferences.SyncSessionPort
import com.bettermifitness.sync.data.repository.FitnessPageCache
import com.mifitness.miclient.api.MiDataClient
import com.mifitness.miclient.auth.MiAuth
import com.mifitness.miclient.auth.MiAuthException
import com.mifitness.miclient.auth.MiCredentials
import com.mifitness.miclient.auth.MiRegion
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the current Mi session (authenticated API client).
 * Can re-mint serviceToken via [refreshSession] using the stored passToken (APK force-refresh).
 */
class MiSessionManager(
    private val credentialsStore: CredentialsStore,
    private val miAuth: MiAuth,
    private val pageCache: FitnessPageCache? = null,
) : SyncSessionPort {
    private var dataClient: MiDataClient? = null
    private var directApi: MiDirectApi? = null
    private val refreshMutex = Mutex()
    private val accountMutex = Mutex()
    private val mutableReauthenticationRequired = MutableStateFlow(false)
    val reauthenticationRequired: StateFlow<Boolean> = mutableReauthenticationRequired
    override val requiresReauthentication: Boolean get() = mutableReauthenticationRequired.value
    private var lastProactiveAttempt: Long? = null
    private var lastProactiveResult: SessionRefreshResult? = null

    /** The active API client. Throws if not logged in. */
    val api: MiDirectApi
        get() {
            if (requiresReauthentication) throw MiApiException.AuthExpired()
            return directApi ?: throw IllegalStateException("Not logged in — call activate() first")
        }

    val isActive: Boolean get() = directApi != null

    /** Used only to namespace the local sync journal, never included in logs. */
    var currentUserId: String? = null
        private set

    internal suspend fun signIn(credentials: MiCredentials): Long = withContext(NonCancellable) {
        accountMutex.withLock {
            credentialsStore.saveNewAccount(credentials).also {
                pageCache?.clear()
                activate(credentials, requiresReauthentication = false)
            }
        }
    }

    suspend fun signOut() = withContext(NonCancellable) {
        accountMutex.withLock {
            credentialsStore.clearCredentials()
            clear()
            pageCache?.clear()
        }
    }

    private fun activate(credentials: MiCredentials, requiresReauthentication: Boolean) {
        currentUserId = credentials.userId
        dataClient?.close()
        dataClient = MiDataClient(credentials)
        directApi = MiDirectApi(dataClient!!)
        mutableReauthenticationRequired.value = requiresReauthentication
        lastProactiveAttempt = null
        lastProactiveResult = null
    }

    /** Clears the session (called on logout). */
    internal fun clear() {
        mutableReauthenticationRequired.value = false
        currentUserId = null
        dataClient?.close()
        dataClient = null
        directApi = null
        lastProactiveAttempt = null
        lastProactiveResult = null
    }

    /**
     * Ensures an in-memory session exists from persisted credentials.
     * @return false if there are no saved credentials
     */
    override suspend fun ensureActive(): Boolean = accountMutex.withLock {
        val account = credentialsStore.loadAccount() ?: return@withLock false
        mutableReauthenticationRequired.value = account.rejected
        if (isActive) return@withLock true
        val creds = account.credentials
        if (creds.serviceToken.isBlank() || creds.userId.isBlank() || creds.ssecurity.isBlank()) {
            return@withLock false
        }
        activate(creds, requiresReauthentication = account.rejected)
        true
    }

    /**
     * Rebuilds the in-memory client from persisted credentials (e.g. after region change).
     * @return false if not signed in
     */
    internal suspend fun applyDiscoveredRegion(generation: Long, result: MiRegion.DiscoveryResult) = accountMutex.withLock {
        if (credentialsStore.setDiscoveredRegion(generation, result)) {
            credentialsStore.loadAccount()?.takeIf { it.generation == generation }?.let {
                activate(it.credentials, requiresReauthentication = it.rejected)
            }
        }
    }

    /**
     * Uses passToken to obtain a new serviceToken, persists it, and re-activates the client.
     * Serialized so concurrent metric failures do not stampede passport (APK update lock).
     */
    suspend fun refreshSessionDetailed(): SessionRefreshResult = refreshMutex.withLock {
        val account = accountMutex.withLock {
            credentialsStore.loadAccount()?.also { mutableReauthenticationRequired.value = it.rejected }
        } ?: return@withLock SessionRefreshResult.NeedsReLogin("No saved credentials")
        if (account.rejected) {
            return@withLock SessionRefreshResult.NeedsReLogin("Previously rejected saved session")
        }
        val generation = account.generation
        val current = account.credentials
        if (current.passToken.isBlank()) {
            return@withLock requireReauthentication(generation, SessionRefreshResult.NeedsReLogin(
                "No passToken saved — sign in again to stay connected across days",
            ))
        }
        if (current.deviceId.isBlank()) {
            return@withLock requireReauthentication(generation, SessionRefreshResult.NeedsReLogin(
                "No deviceId saved — sign in again",
            ))
        }

        return@withLock try {
            val refreshed = miAuth.refreshWithPassToken(current) { rotated ->
                credentialsStore.refreshPassToken(generation, rotated)
            }
            accountMutex.withLock accountUpdate@{
                if (!credentialsStore.refreshCredentials(generation, refreshed)) return@accountUpdate accountChanged()
                val stored = credentialsStore.loadAccount()?.takeIf { it.generation == generation }
                    ?: return@accountUpdate accountChanged()
                activate(stored.credentials, requiresReauthentication = false)
                SessionRefreshResult.Success
            }
        } catch (e: MiAuthException) {
            val result = when (e.kind) {
                MiAuthException.Kind.MissingPassToken,
                MiAuthException.Kind.MissingDeviceId,
                MiAuthException.Kind.InvalidCredential,
                -> SessionRefreshResult.NeedsReLogin(e.message ?: "Sign in again")
                MiAuthException.Kind.NeedsVerification ->
                    SessionRefreshResult.NeedsVerification(
                        reason = e.message ?: "Xiaomi requires re-verification",
                        notificationUrl = e.notificationUrl,
                    )
                MiAuthException.Kind.StsFailed,
                MiAuthException.Kind.Generic,
                -> SessionRefreshResult.TransientFailure(e.message ?: "Session refresh failed")
            }
            if (result is SessionRefreshResult.NeedsReLogin || result is SessionRefreshResult.NeedsVerification) {
                requireReauthentication(generation, result)
            } else {
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionRefreshResult.TransientFailure(
                e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "Refresh error"),
            )
        }
    }

    private fun accountChanged() = SessionRefreshResult.NeedsReLogin("Account changed during session refresh")

    private suspend fun requireReauthentication(generation: Long, result: SessionRefreshResult): SessionRefreshResult =
        withContext(NonCancellable) {
            accountMutex.withLock {
                if (!credentialsStore.markReauthenticationRequired(generation)) return@withLock accountChanged()
                mutableReauthenticationRequired.value = true
                result
            }
        }

    private suspend fun storeProactiveResult(generation: Long, result: SessionRefreshResult) = accountMutex.withLock {
        if (credentialsStore.accountGeneration() == generation) lastProactiveResult = result
    }

    /**
     * @return true if refresh succeeded
     */
    suspend fun refreshSession(): Boolean = refreshSessionDetailed().isSuccess

    /**
     * Keeps the passToken rotation chain alive on installs that rarely hit a 401.
     *
     * Xiaomi hands back a rotated passToken on every `serviceLogin` and retires the previous
     * one shortly after. Lazy 401-only refresh is enough while the app syncs often, but a
     * long-valid serviceToken means no refresh happens at all — and by the time one is needed
     * the stored passToken can already be dead (code 70016 → forced re-login).
     *
     * Successful renewals are spaced by [PROACTIVE_REFRESH_AFTER]. Transient failures
     * use a shorter retry cooldown; hard rejections persist a fresh-sign-in requirement.
     */
    override suspend fun refreshSessionIfStale() {
        refreshSessionIfDue()
    }

    /** A null result means no renewal was due. Network failures remain retryable for WorkManager. */
    suspend fun refreshSessionIfDue(): SessionRefreshResult? {
        val generation = accountMutex.withLock {
            if (requiresReauthentication) return SessionRefreshResult.NeedsReLogin("Saved session requires sign-in")
            val now = Clock.System.now().epochSeconds
            if (!isSessionStale(credentialsStore.lastSessionRefreshEpochSeconds(), now)) return null
            val sinceAttempt = lastProactiveAttempt?.let { now - it }
            if (sinceAttempt != null && sinceAttempt in 0 until PROACTIVE_RETRY_AFTER.inWholeSeconds) {
                lastProactiveResult?.let { return it }
            }
            lastProactiveAttempt = now
            credentialsStore.accountGeneration()
        }
        return refreshSessionDetailed().also { storeProactiveResult(generation, it) }
    }

    companion object {
        /**
         * How long a session may go unrefreshed before we roll it forward.
         * Well inside Xiaomi's retirement window for a superseded passToken.
         */
        val PROACTIVE_REFRESH_AFTER: Duration = 6.hours
        private val PROACTIVE_RETRY_AFTER: Duration = 15.minutes

        /**
         * Whether the passport session should be re-minted.
         *
         * A missing stamp counts as stale: credentials were stored by a build without the
         * key, so the rotation chain has never been exercised and should start now.
         * A stamp in the future means the device clock moved backwards — refresh instead of
         * waiting out a bogus interval that could outlive the passToken.
         */
        fun isSessionStale(
            lastRefreshEpochSeconds: Long?,
            nowEpochSeconds: Long,
            maxAge: Duration = PROACTIVE_REFRESH_AFTER,
        ): Boolean {
            val age = lastRefreshEpochSeconds?.let { nowEpochSeconds - it } ?: return true
            return age < 0 || age >= maxAge.inWholeSeconds
        }
    }
}
