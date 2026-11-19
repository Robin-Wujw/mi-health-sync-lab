package com.bettermifitness.sync.zepp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.LocalizedTest
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.preferences.CredentialsPort
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.SyncSessionPort
import com.bettermifitness.sync.data.repository.HealthSyncRunner
import com.bettermifitness.sync.data.repository.SyncProgress
import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.health.HealthAvailability
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthPermissionRequester
import com.bettermifitness.sync.sync.SyncCoordinator
import com.bettermifitness.sync.sync.SyncOutcome
import com.bettermifitness.sync.sync.UserSyncRequest
import com.mifitness.miclient.auth.MiAuth
import com.mifitness.miclient.auth.MiCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Exercises production connection state, not the coordinator's optional legacy fallback. */
class ZeppAutomaticSelectionTest : LocalizedTest() {
    private val dataStore = MemoryStore()
    private val preferences = SyncPreferences(dataStore)
    private val sessions = ZeppSessionStore(dataStore)
    private val http = HttpClient(MockEngine { error("Selection must not make cloud requests") })
    private val service = ZeppSyncService(sessions, ZeppCloudClient(http),
        MiSessionManager(CredentialsStore(dataStore), MiAuth()), HealthImportLedger(dataStore), preferences)
    private val runner = Runner()
    private val health = Health()
    private val coordinator = SyncCoordinator(
        session = object : SyncSessionPort { override suspend fun ensureActive() = true },
        credentials = object : CredentialsPort {
            override val token = MutableStateFlow<String?>("synthetic")
            override suspend fun loadCredentials(): MiCredentials? = null
            override suspend fun saveCredentials(credentials: MiCredentials) = Unit
        }, syncPreferences = preferences, healthAvailability = health, healthPermissions = health,
        repository = runner, zeppSync = service,
    )

    @Test fun connectedSourceAutomaticallyRunsEvenWhenEveryOldSwitchIsOffAndHealthConnectIsUnavailable() = runBlocking {
        try {
            select(emptySet())
            connect()
            assertEquals(null, coordinator.prepareUserSync())
            assertEquals(SyncOutcome.Success, coordinator.run(requestHealthPermissions = false))
            assertEquals(setOf(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY), runner.enabled)
            assertEquals(0, health.availabilityChecks)
            assertEquals(0, health.permissionRequests)
        } finally { http.close() }
    }

    @Test fun disconnectedSourceRemovesAStaleSavedCloudKeyWithoutStartingWork() = runBlocking {
        try {
            select(setOf(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY))
            assertEquals(SyncOutcome.Skipped, coordinator.prepareUserSync())
            assertEquals(SyncOutcome.Skipped, coordinator.run())
            assertEquals(null, runner.enabled)
            assertEquals(0, health.permissionRequests)
        } finally { http.close() }
    }

    @Test fun connectionStateIsRecheckedAfterPreparationBeforeAServiceRun() = runBlocking {
        try {
            select(emptySet())
            connect()
            assertEquals(null, coordinator.prepareUserSync())
            sessions.clear()
            assertEquals(SyncOutcome.Skipped, coordinator.run(requestHealthPermissions = false))
            assertEquals(null, runner.enabled)
        } finally { http.close() }
    }

    @Test fun unavailableHealthConnectDoesNotDiscardTheIndependentCloudDirection() = runBlocking {
        try {
            select(setOf(SyncPreferences.SLEEP_IMPORT_KEY))
            connect()
            assertEquals(null, coordinator.prepareUserSync())
            coordinator.run(requestHealthPermissions = false)
            assertEquals(setOf(SyncPreferences.SLEEP_IMPORT_KEY, SyncPreferences.ZEPP_CLOUD_IMPORT_KEY), runner.enabled)
        } finally { http.close() }
    }

    @Test fun changingZeppRegionInvalidatesAReservedReadBeforeItMakesRequests() = runBlocking<Unit> {
        try {
            connect()
            val expected = checkNotNull(service.automaticSourceIdentity())
            sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitde.zepp.com"))
            assertFailsWith<ZeppSourceMismatch> {
                service.sync("synthetic-destination", 1_795_000_000, 1_795_003_600, expectedSource = expected)
            }
        } finally { http.close() }
    }

    @Test fun acceptedManualRequestCannotRunAgainstAReplacementZeppAccount() = runBlocking {
        try {
            select(emptySet())
            connect()
            val request = coordinator.captureUserSyncRequest()
            assertEquals(service.automaticSourceIdentity(), request.sources?.zeppIdentity)
            assertNull(coordinator.prepareUserSync(request))
            assertIs<SyncOutcome.Failed>(coordinator.prepareUserSync(request.copy(sources = null)))
            sessions.save(ZeppSession("456", "synthetic-app", "synthetic-login", "api-mifitcn.zepp.com"))
            assertEquals(false, assertIs<SyncOutcome.Failed>(coordinator.run(request = request)).retryable)
            assertIs<SyncOutcome.Failed>(coordinator.prepareUserSync(request))
            assertNull(runner.enabled)
            assertEquals(0, health.permissionRequests)
        } finally { http.close() }
    }

    @Test fun sourceChangeWhileNativePermissionRequestIsSuspendedRejectsPreparedDispatch() = runBlocking {
        try {
            select(setOf(SyncPreferences.SLEEP_IMPORT_KEY))
            connect()
            preferences.setSyncRangeDays(30)
            val request = coordinator.captureUserSyncRequest()
            health.duringReadPermissions = { history ->
                assertEquals(true, history)
                preferences.setSyncRangeDays(1)
                select(emptySet())
                sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitde.zepp.com"))
            }
            assertIs<SyncOutcome.Failed>(coordinator.prepareUserSync(request))
            assertEquals(1, health.permissionRequests)
            assertNull(runner.enabled)
            assertEquals(30, request.selection.rangeDays)
        } finally { http.close() }
    }

    @Test fun manualRequestKeepsItsOriginalZoneAndDoesNotAdoptANewlyConnectedSource() = runBlocking {
        try {
            select(setOf("steps"))
            health.available = true
            preferences.setSyncRangeDays(30)
            preferences.setSourceCalendarZoneId("Asia/Singapore")
            val request = coordinator.captureUserSyncRequest()
            connect()
            select(setOf("sleep"))
            preferences.setSyncRangeDays(1)
            preferences.setSourceCalendarZoneId("UTC")
            assertEquals(SyncOutcome.Success, coordinator.run(request = request, requestHealthPermissions = false))
            assertEquals(request, runner.request)
            assertEquals(setOf("steps"), runner.enabled)
            assertEquals(30, runner.request?.selection?.rangeDays)
            assertEquals("Asia/Singapore", runner.request?.sources?.calendarZoneId)
            assertNull(runner.request?.sources?.zeppIdentity)
        } finally { http.close() }
    }

    private suspend fun connect() = sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitcn.zepp.com"))

    private suspend fun select(keys: Set<String>) {
        for (key in SyncPreferences.DEFAULT_METRIC_KEYS + SyncPreferences.ZEPP_CLOUD_METRIC_KEYS) {
            preferences.setMetricEnabled(key, key in keys)
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private class Runner : HealthSyncRunner {
        override val syncProgress = MutableStateFlow(SyncProgress())
        var enabled: Set<String>? = null
        var request: UserSyncRequest? = null
        override suspend fun syncUserRequest(request: UserSyncRequest): SyncRunResult {
            this.request = request
            return super.syncUserRequest(request)
        }
        override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult {
            this.enabled = enabled
            return SyncRunResult(1, 1, 0, 1, false, false, emptyList())
        }
        override fun resetProgress() = Unit
    }

    private class Health : HealthAvailability, HealthPermissionRequester {
        var available = false
        var duringReadPermissions: suspend (Boolean) -> Unit = {}
        var availabilityChecks = 0
        var permissionRequests = 0
        override suspend fun isAvailable(): Boolean { availabilityChecks++; return available }
        override suspend fun availabilityHint(): String? = null
        override fun healthServiceName() = "Test Health"
        override fun openHealthService() = Unit
        override suspend fun hasWritePermissions() = false
        override suspend fun requestPermissions() { permissionRequests++ }
        override suspend fun requestReadPermissions(metrics: Set<HealthInboundMetric>, includeBackground: Boolean,
            includeHistory: Boolean): Set<HealthInboundMetric> {
            permissionRequests++
            duringReadPermissions(includeHistory)
            return emptySet()
        }
    }
}
