package com.bettermifitness.sync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.mifitness.miclient.auth.MiAuth
import com.mifitness.miclient.auth.MiCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the real passport refresh against a fake account server. Xiaomi rotates the passToken on
 * every serviceLogin and soon retires the old one, so losing the new value kills the session.
 */
class MiSessionRotationTest {
    private val store = MemoryStore()
    private val credentials = CredentialsStore(store)
    private val server = FakePassport()
    private val session = MiSessionManager(
        credentials,
        MiAuth(
            httpClientFactory = { cookies ->
                HttpClient(MockEngine { request -> server.handle(this, request) }) {
                    followRedirects = false
                    install(HttpCookies) { storage = cookies }
                }
            },
        ),
    )

    private suspend fun signedIn() = credentials.saveCredentials(
        MiCredentials(
            userId = "1",
            ssecurity = "c2VjdXJpdHk=",
            serviceToken = "service-old",
            passToken = "pass-1",
            deviceId = "device",
            region = "sg",
        ),
    )

    private suspend fun storedPassToken() = credentials.loadCredentials()!!.passToken

    @Test
    fun successfulRefresh_storesTheRotatedToken() = runBlocking<Unit> {
        signedIn()

        assertIs<SessionRefreshResult.Success>(session.refreshSessionDetailed())

        assertEquals("pass-2", storedPassToken())
        assertEquals("service-new", credentials.loadCredentials()!!.serviceToken)
        session.clear()
    }

    @Test
    fun failedStsStep_stillKeepsTheRotatedToken() = runBlocking<Unit> {
        signedIn()
        server.stsFailure = IllegalStateException("connection reset")

        assertIs<SessionRefreshResult.TransientFailure>(session.refreshSessionDetailed())

        assertEquals("pass-2", storedPassToken())
        assertEquals("service-old", credentials.loadCredentials()!!.serviceToken)
    }

    @Test
    fun cancellationDuringStsStep_stillKeepsTheRotatedToken() = runBlocking<Unit> {
        signedIn()
        server.stsFailure = CancellationException("worker stopped")

        assertFailsWith<CancellationException> { session.refreshSessionDetailed() }

        assertEquals("pass-2", storedPassToken())
    }

    @Test
    fun retryAfterAFailedStep_presentsTheRotatedToken() = runBlocking<Unit> {
        signedIn()
        server.stsFailure = IllegalStateException("connection reset")
        session.refreshSessionDetailed()
        server.stsFailure = null

        assertIs<SessionRefreshResult.Success>(session.refreshSessionDetailed())

        assertEquals(listOf("pass-1", "pass-2"), server.presentedTokens)
        assertEquals("pass-3", storedPassToken())
        session.clear()
    }

    @Test
    fun retiredOldToken_doesNotEndTheSession() = runBlocking<Unit> {
        signedIn()
        server.stsFailure = IllegalStateException("connection reset")
        server.retireSupersededTokens = true
        session.refreshSessionDetailed()
        server.stsFailure = null

        // Presenting the retired token again would get 70016 and force a new sign-in.
        assertIs<SessionRefreshResult.Success>(session.refreshSessionDetailed())
        session.clear()
    }

    @Test
    fun secondRotationWhileFetchingSsecurity_isAlsoKept() = runBlocking<Unit> {
        signedIn()
        server.sendSsecurityOnFirstLogin = false
        server.stsFailure = IllegalStateException("connection reset")

        assertIs<SessionRefreshResult.TransientFailure>(session.refreshSessionDetailed())

        assertEquals("pass-3", storedPassToken())
    }

    @Test
    fun failedRefresh_staysStaleSoTheNextTickRetries() = runBlocking<Unit> {
        signedIn()
        store.setSessionRefreshedAt(LONG_AGO)
        server.stsFailure = IllegalStateException("connection reset")

        session.refreshSessionDetailed()

        assertEquals(LONG_AGO, credentials.lastSessionRefreshEpochSeconds())
    }

    @Test
    fun rejectedToken_asksForANewSignInAndKeepsTheStoredToken() = runBlocking<Unit> {
        signedIn()
        server.rejectEveryToken = true

        assertIs<SessionRefreshResult.NeedsReLogin>(session.refreshSessionDetailed())

        assertEquals("pass-1", storedPassToken())
    }

    @Test
    fun rejectedSessionSurvivesRecreationWithoutSubmittingTheSameTokenAgain() = runBlocking<Unit> {
        signedIn()
        val before = credentials.loadCredentials()
        server.rejectEveryToken = true
        assertIs<SessionRefreshResult.NeedsReLogin>(session.refreshSessionDetailed())
        assertTrue(credentials.requiresReauthentication())
        assertTrue(session.requiresReauthentication)
        assertIs<SessionRefreshResult.NeedsReLogin>(session.refreshSessionDetailed())
        val reopened = MiSessionManager(credentials, MiAuth())
        assertTrue(reopened.ensureActive())
        assertTrue(reopened.requiresReauthentication)
        assertIs<SessionRefreshResult.NeedsReLogin>(reopened.refreshSessionDetailed())
        assertFailsWith<com.mifitness.miclient.api.MiApiException.AuthExpired> { reopened.api }
        assertEquals(1, server.presentedTokens.size)
        assertEquals(before, credentials.loadCredentials())
        reopened.clear()
    }

    @Test
    fun freshSignInClearsTheRejectionAndEnablesRenewalAgain() = runBlocking<Unit> {
        signedIn()
        server.rejectEveryToken = true
        session.refreshSessionDetailed()
        session.signIn(credentials.loadCredentials()!!)
        assertFalse(credentials.requiresReauthentication())
        assertFalse(session.requiresReauthentication)
        server.rejectEveryToken = false
        assertIs<SessionRefreshResult.Success>(session.refreshSessionDetailed())
        session.clear()
    }

    @Test
    fun temporaryNetworkFailureDoesNotPersistAReauthenticationRequirement() = runBlocking<Unit> {
        signedIn()
        server.stsFailure = IllegalStateException("offline")
        assertIs<SessionRefreshResult.TransientFailure>(session.refreshSessionDetailed())
        assertFalse(credentials.requiresReauthentication())
        assertFalse(session.requiresReauthentication)
        server.stsFailure = null
        assertIs<SessionRefreshResult.Success>(session.refreshSessionDetailed())
        session.clear()
    }

    @Test
    fun openingWithAFreshSessionDoesNotRotateAgain() = runBlocking<Unit> {
        signedIn()
        assertEquals(null, session.refreshSessionIfDue())
        assertTrue(server.presentedTokens.isEmpty())
    }

    @Test
    fun staleSessionRenewsBeforeItExpiresAndTheNextCheckDoesNotRotateAgain() = runBlocking<Unit> {
        signedIn()
        store.setSessionRefreshedAt(LONG_AGO)
        assertIs<SessionRefreshResult.Success>(session.refreshSessionIfDue())
        assertEquals(null, session.refreshSessionIfDue())
        assertEquals(1, server.presentedTokens.size)
        assertEquals("pass-2", storedPassToken())
        session.clear()
    }

    @Test
    fun proactiveNetworkFailureReturnsRetryableOutcomeWithoutRapidRepeatedPassportCalls() = runBlocking<Unit> {
        signedIn()
        store.setSessionRefreshedAt(LONG_AGO)
        server.stsFailure = IllegalStateException("offline")
        assertIs<SessionRefreshResult.TransientFailure>(session.refreshSessionIfDue())
        assertIs<SessionRefreshResult.TransientFailure>(session.refreshSessionIfDue())
        assertEquals(1, server.presentedTokens.size)
        assertEquals(LONG_AGO, credentials.lastSessionRefreshEpochSeconds())
        assertFalse(credentials.requiresReauthentication())
        server.stsFailure = null
        assertIs<SessionRefreshResult.Success>(session.refreshSessionDetailed())
        session.clear()
    }

    @Test
    fun malformedLoginBody_keepsTheTokenFromHeadersAndCanRetry() = runBlocking<Unit> {
        signedIn()
        server.badBodyOnLogin = 1
        server.retireSupersededTokens = true

        assertIs<SessionRefreshResult.TransientFailure>(session.refreshSessionDetailed())
        assertEquals("pass-2", storedPassToken())
        server.badBodyOnLogin = null
        assertIs<SessionRefreshResult.Success>(session.refreshSessionDetailed())
        session.clear()
    }

    @Test
    fun interruptedFirstLoginBody_keepsTheTokenFromHeaders() = runBlocking<Unit> {
        signedIn()
        server.brokenBodyOnLogin = 1

        assertIs<SessionRefreshResult.TransientFailure>(session.refreshSessionDetailed())
        assertEquals("pass-2", storedPassToken())
    }

    @Test
    fun interruptedSsecurityBody_keepsTheSecondRotationAndCanRetry() = runBlocking<Unit> {
        signedIn()
        server.sendSsecurityOnFirstLogin = false
        server.brokenBodyOnLogin = 2
        server.retireSupersededTokens = true

        assertIs<SessionRefreshResult.TransientFailure>(session.refreshSessionDetailed())
        assertEquals("pass-3", storedPassToken())
        server.brokenBodyOnLogin = null
        assertIs<SessionRefreshResult.Success>(session.refreshSessionDetailed())
        session.clear()
    }

    @Test
    fun latePassportRotationAndStsCannotReviveLoggedOutAccount() = runBlocking<Unit> {
        signedIn()
        assertTrue(session.ensureActive())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.beforePassportResponse = {
            entered.complete(Unit)
            release.await()
        }
        val refresh = async { session.refreshSessionDetailed() }
        try {
            entered.await()
            session.signOut()
        } finally {
            release.complete(Unit)
        }
        assertIs<SessionRefreshResult.NeedsReLogin>(refresh.await())
        assertEquals(null, credentials.loadCredentials())
        assertEquals(null, store.data.value[stringPreferencesKey("pass_token")])
        assertFalse(credentials.requiresReauthentication())
        assertFalse(session.isActive)
    }

    @Test
    fun lateStsCannotOverwriteAnotherAccount() = runBlocking<Unit> {
        signedIn()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.beforeStsResponse = {
            entered.complete(Unit)
            release.await()
        }
        val replacement = credentials.loadCredentials()!!.copy(userId = "2", serviceToken = "second", passToken = "second-pass")
        val refresh = async { session.refreshSessionDetailed() }
        try {
            entered.await()
            session.signOut()
            session.signIn(replacement)
        } finally {
            release.complete(Unit)
        }
        assertIs<SessionRefreshResult.NeedsReLogin>(refresh.await())
        assertEquals(replacement, credentials.loadCredentials())
        assertEquals(replacement.userId, session.currentUserId)
        assertFalse(session.requiresReauthentication)
        session.clear()
    }

    private class FakePassport {
        var stsFailure: Exception? = null
        var retireSupersededTokens = false
        var rejectEveryToken = false
        var sendSsecurityOnFirstLogin = true
        var badBodyOnLogin: Int? = null
        var brokenBodyOnLogin: Int? = null
        var beforePassportResponse: suspend () -> Unit = {}
        var beforeStsResponse: suspend () -> Unit = {}
        val presentedTokens = mutableListOf<String>()
        private var current = 1

        suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData) = with(scope) {
            if (request.url.encodedPath.endsWith("/pass/serviceLogin")) {
                beforePassportResponse()
                serviceLogin(request)
            } else {
                beforeStsResponse()
                stsFailure?.let { throw it }
                respond(
                    "ok",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.SetCookie, "serviceToken=service-new; Path=/"),
                )
            }
        }

        private fun MockRequestHandleScope.serviceLogin(request: HttpRequestData): HttpResponseData = run {
            val presented = request.headers[HttpHeaders.Cookie].orEmpty().split(";")
                .map { it.trim() }.first { it.startsWith("passToken=") }.removePrefix("passToken=")
            presentedTokens += presented
            val retired = retireSupersededTokens && presented != "pass-$current"
            if (rejectEveryToken || retired) {
                respond("&&&START&&&{\"code\":70016,\"desc\":\"rejected\"}", HttpStatusCode.OK)
            } else {
                current++
                val headers = headersOf(HttpHeaders.SetCookie, "passToken=pass-$current; Path=/")
                if (presentedTokens.size == brokenBodyOnLogin) {
                    return respond(
                        ByteChannel().apply { cancel(IllegalStateException("body connection reset")) },
                        HttpStatusCode.OK,
                        headers,
                    )
                }
                if (presentedTokens.size == badBodyOnLogin) {
                    return respond("truncated JSON", HttpStatusCode.OK, headers)
                }
                val body = if (sendSsecurityOnFirstLogin || presentedTokens.size > 1) {
                    """{"code":0,"ssecurity":"c2VjdXJpdHk=","nonce":123,"userId":1,
                        "location":"https://sts-hlth.io.mi.com/healthapp/sts?p_ur=SG"}"""
                } else {
                    """{"code":0,"nonce":123,"userId":1,"location":"https://sts-hlth.io.mi.com/healthapp/sts?p_ur=SG"}"""
                }
                respond(
                    "&&&START&&&$body",
                    HttpStatusCode.OK,
                    headers,
                )
            }
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }

        suspend fun setSessionRefreshedAt(epochSeconds: Long) {
            edit { it[stringPreferencesKey("session_refreshed_at")] = epochSeconds.toString() }
        }
    }

    private companion object {
        const val LONG_AGO = 1_000L
    }
}
