package com.bettermifitness.sync.zepp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.health.HealthRecordOrigin
import com.bettermifitness.sync.health.InboundWorkout
import com.bettermifitness.sync.sync.UserSyncSources
import com.mifitness.miclient.auth.MiAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZeppWorkoutMaintenanceSourceTest {
    private val store = MemoryStore()
    private val preferences = SyncPreferences(store)
    private val sessions = ZeppSessionStore(store)
    private val start = 1_800_000_000L
    private val end = start + 3
    private val source = InboundWorkout(HealthRecordOrigin("run", "com.huami.watch.hmwatchmanager", null, 1, 1000),
        WorkoutSession(start, end, "outdoor_running", tzIn15Min = 32), 3)
    private val second = source.copy(origin = source.origin.copy(recordId = "second"),
        session = source.session.copy(startTime = start + 100, endTime = end + 100))
    private var requests = 0
    private val client = ZeppCloudClient(HttpClient(MockEngine { request ->
        requests++
        when (request.url.encodedPath) {
            "/v1/sport/run/history.json" -> respond("""{"data":{"summary":[
                {"trackid":"$start","start_time":$start,"end_time":$end,"source":"synthetic"},
                {"trackid":"${start+100}","start_time":${start+100},"end_time":${end+100},"source":"synthetic"}],"next":0}}""")
            "/v1/sport/run/detail.json" -> respond(
                """{"data":{"trackid":"${request.url.parameters["trackid"]}","source":"synthetic","heart_rate":"0,100;2,1"}}""")
            else -> error("Workout maintenance must not request score streams")
        }
    }))
    private val service = ZeppSyncService(sessions, client, MiSessionManager(CredentialsStore(store), MiAuth()),
        HealthImportLedger(store), preferences)
    private suspend fun connected() = sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitcn.zepp.com"))

    @Test fun originalHeartRateIsAutomaticEvenWithScoresAndTheOldHeartRateSwitchDisabled() = runTest {
        connected()
        for (metric in ZeppSyncMetric.entries) preferences.setZeppMetricEnabled(metric, false)
        preferences.setMetricEnabled(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY, false)
        preferences.setMetricEnabled(SyncPreferences.WORKOUTS_IMPORT_KEY, true)
        assertEquals(listOf(100.0, 101.0, 101.0), service.workoutHeartRate(source)!!.map { it.value })
        assertEquals(2, requests)
    }

    @Test fun disablingWorkoutSyncStopsTheExtraSourceRequests() = runTest {
        connected()
        preferences.setMetricEnabled(SyncPreferences.WORKOUTS_IMPORT_KEY, false)
        assertNull(service.workoutHeartRate(source))
        assertEquals(0, requests)
    }

    @Test fun disconnectedOrForeignSourcesDoNotReadZeppWorkouts() = runTest {
        preferences.setMetricEnabled(SyncPreferences.WORKOUTS_IMPORT_KEY, true)
        assertNull(service.workoutHeartRate(source))
        connected()
        assertNull(service.workoutHeartRate(source.copy(origin = source.origin.copy(sourcePackage = "another.app"))))
        assertEquals(0, requests)
    }

    @Test fun twoWorkoutsShareOnlyTheRunIndexAndKeepTheirOwnDetails() = runTest {
        connected()
        preferences.setMetricEnabled(SyncPreferences.WORKOUTS_IMPORT_KEY, true)
        val reader = service.workoutHeartRateReader(listOf(source, second))
        val first = reader(source)!!
        val next = reader(second)!!
        assertEquals(listOf(100.0, 101.0, 101.0), first.map { it.value })
        assertEquals(listOf(100.0, 101.0, 101.0), next.map { it.value })
        assertEquals(first.first().timeSec + 100, next.first().timeSec)
        assertEquals(3, requests)
    }

    @Test fun nextRunAlwaysReadsAFreshSourceIndex() = runTest {
        connected()
        preferences.setMetricEnabled(SyncPreferences.WORKOUTS_IMPORT_KEY, true)
        service.workoutHeartRateReader(listOf(source))(source)
        service.workoutHeartRateReader(listOf(source))(source)
        assertEquals(4, requests)
    }

    @Test fun disablingImportsAfterBatchCreationStillPreventsSourceReads() = runTest {
        connected()
        preferences.setMetricEnabled(SyncPreferences.WORKOUTS_IMPORT_KEY, true)
        val reader = service.workoutHeartRateReader(listOf(source, second))
        preferences.setMetricEnabled(SyncPreferences.WORKOUTS_IMPORT_KEY, false)
        assertNull(reader(source))
        assertEquals(0, requests)
    }

    @Test fun replacingBoundZeppRegionPreventsWorkoutEnrichmentEvenWithinAnAcceptedRun() = runTest {
        connected()
        val sources = UserSyncSources(checkNotNull(service.automaticSourceIdentity()), null)
        val reader = service.workoutHeartRateReader(listOf(source, second), sources)
        checkNotNull(reader(source))
        val before = requests
        sessions.save(ZeppSession("123", "synthetic-app", "synthetic-login", "api-mifitde.zepp.com"))
        assertNull(reader(second))
        assertEquals(before, requests)
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
}
