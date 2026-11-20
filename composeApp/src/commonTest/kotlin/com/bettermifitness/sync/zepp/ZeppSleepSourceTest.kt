package com.bettermifitness.sync.zepp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthRecordOrigin
import com.bettermifitness.sync.sync.UserSyncSources
import com.mifitness.miclient.auth.MiAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZeppSleepSourceTest {
    private val start = 1_800_000_000L
    private val sleep = HealthInboundSleepRecord(HealthRecordOrigin("sleep", "com.huami.watch.hmwatchmanager", null, 1, 1),
        start * 1_000, (start + 600) * 1_000, 0, 0, emptyList())
    private val store = MemoryStore()
    private val sessions = ZeppSessionStore(store)
    private val preferences = SyncPreferences(store)
    private var requests = 0
    private val client = ZeppCloudClient(HttpClient(MockEngine { request ->
        requests++
        assertEquals("synthetic-app", request.headers["apptoken"])
        when (request.url.encodedPath) {
            "/v1/data/band_data.json" -> {
                assertEquals("detail", request.url.parameters["query_type"])
                val summary = Base64.Default.encode("""{"slp":{"st":$start,"ed":${start + 600}}}""".encodeToByteArray())
                respond("""{"data":{"items":[{"summary":"$summary"}]}}""")
            }
            "/v2/users/me/events" -> {
                assertEquals("RespiratoryRate", request.url.parameters["eventType"])
                assertEquals("real_data", request.url.parameters["subType"])
                val bytes = Base64.Default.encode(byteArrayOf(16, 0, 18))
                respond("""{"data":{"items":[{"timestamp":${start * 1_000},"value":{"measurements":"$bytes"}}]}}""")
            }
            else -> error("Sleep maintenance must not read scoring or workout streams")
        }
    }), now = { start + 1_000 })
    private val service = ZeppSyncService(sessions, client, MiSessionManager(CredentialsStore(store), MiAuth()),
        HealthImportLedger(store), preferences)

    @Test fun sleepBreathingIsReadEvenWhenScoringIsDisabled() = runTest {
        sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitcn.zepp.com"))
        preferences.setMetricEnabled(SyncPreferences.SLEEP_IMPORT_KEY, true)
        preferences.setMetricEnabled(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY, false)
        val points = service.sleepBreath(listOf(sleep))
        assertEquals(listOf(start, start + 120), points.map { it.time })
        assertEquals(2, requests)
    }

    @Test fun sleepSwitchAndConnectionRemainRequiredForCloudReads() = runTest {
        preferences.setMetricEnabled(SyncPreferences.SLEEP_IMPORT_KEY, true)
        assertTrue(service.sleepBreath(listOf(sleep)).isEmpty())
        sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitcn.zepp.com"))
        preferences.setMetricEnabled(SyncPreferences.SLEEP_IMPORT_KEY, false)
        assertTrue(service.sleepBreath(listOf(sleep)).isEmpty())
        assertEquals(0, requests)
    }

    @Test fun acceptedSleepImportUsesOnlyItsFrozenAccountEvenAfterSettingsChange() = runTest {
        sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitcn.zepp.com"))
        val sources = UserSyncSources(checkNotNull(service.automaticSourceIdentity()), "UTC")
        preferences.setMetricEnabled(SyncPreferences.SLEEP_IMPORT_KEY, false)
        assertEquals(listOf(start, start + 120), service.sleepBreath(listOf(sleep), sources).map { it.time })
        sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitde.zepp.com"))
        assertTrue(service.sleepBreath(listOf(sleep), sources).isEmpty())
        assertTrue(service.sleepBreath(listOf(sleep), UserSyncSources(null, "UTC")).isEmpty())
        assertEquals(2, requests)
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
}
