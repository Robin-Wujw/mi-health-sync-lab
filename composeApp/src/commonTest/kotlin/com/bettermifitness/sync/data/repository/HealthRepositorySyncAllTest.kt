package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.LocalizedTest
import com.bettermifitness.sync.data.SessionRefreshResult
import com.bettermifitness.sync.data.api.ActiveCaloriesSample
import com.bettermifitness.sync.data.api.BloodPressureSample
import com.bettermifitness.sync.data.api.DistanceSample
import com.bettermifitness.sync.data.api.HeartRateSample
import com.bettermifitness.sync.data.api.HrvSample
import com.bettermifitness.sync.data.api.MiDirectApi
import com.bettermifitness.sync.data.api.SleepSession
import com.bettermifitness.sync.data.api.SpO2Sample
import com.bettermifitness.sync.data.api.StepsRecord
import com.bettermifitness.sync.data.api.TemperatureSample
import com.bettermifitness.sync.data.api.Vo2MaxSample
import com.bettermifitness.sync.data.api.WeightMeasurement
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.preferences.BodySyncLedger
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.health.HealthStore
import com.mifitness.miclient.api.MiDataClient
import com.mifitness.miclient.auth.MiCredentials
import com.mifitness.miclient.crypto.MiCloudSigner
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Runs the real [HealthRepository.syncAll] against a fake Mi cloud behind Ktor's mock engine:
 * only the HTTP transport and the platform health store are replaced.
 */
class HealthRepositorySyncAllTest : LocalizedTest() {

    @Test
    fun expiredSession_everyMetricRecoversAfterOneRefresh() = runBlocking<Unit> {
        val cloud = FakeMiCloud().withSampleData()
        cloud.sessionValid = false
        val store = RecordingHealthStore()

        val result = cloud.repository(store).syncAll(FROM, TO, setOf("sleep", "steps", "distance", "heart_rate", "workouts"))

        assertEquals(1, cloud.refreshCalls)
        assertEquals(0, result.failed, "unexpected errors: ${result.errorMessages}")
        assertEquals(5, result.succeeded)
        assertEquals(setOf("sleep", "steps", "distance", "heart_rate", "workouts"), store.written.keys)
    }

    @Test
    fun failedRead_failsOnlyTheMetricsThatNeedIt() = runBlocking<Unit> {
        val cloud = FakeMiCloud().withSampleData()
        cloud.failingRoutes["time:steps"] = HttpStatusCode.InternalServerError
        val store = RecordingHealthStore()
        val repository = cloud.repository(store)

        val result = repository.syncAll(FROM, TO, setOf("steps", "distance", "sleep", "spo2"))

        assertTrue(result.isPartialSuccess, "expected a partial result, got $result")
        val progress = repository.syncProgress.value
        assertIs<SyncState.Error>(progress.steps)
        assertIs<SyncState.Error>(progress.distance)
        assertIs<SyncState.Success>(progress.sleep)
        assertIs<SyncState.Success>(progress.spo2)
        assertEquals(setOf("sleep", "spo2"), store.written.keys)
    }

    @Test
    fun heartRateReadFailure_isReportedInsteadOfAnEmptySuccess() = runBlocking<Unit> {
        val cloud = FakeMiCloud().withSampleData()
        cloud.failingRoutes["time:heart_rate"] = HttpStatusCode.InternalServerError
        val store = RecordingHealthStore()
        val repository = cloud.repository(store)

        val result = repository.syncAll(FROM, TO, setOf("heart_rate"))

        assertEquals(1, result.failed)
        assertIs<SyncState.Error>(repository.syncProgress.value.heartRate)
        assertEquals(emptySet(), store.written.keys)
    }

    @Test
    fun heartRateOnlyRun_refreshesAnExpiredSession() = runBlocking<Unit> {
        val cloud = FakeMiCloud().withSampleData()
        cloud.sessionValid = false
        val store = RecordingHealthStore()

        val result = cloud.repository(store).syncAll(FROM, TO, setOf("heart_rate"))

        assertEquals(1, cloud.refreshCalls)
        assertEquals(0, result.failed, "unexpected errors: ${result.errorMessages}")
        assertEquals(1, store.written.getValue("heart_rate"))
    }

    @Test
    fun heartRateReadFailure_staysWithTheHeartRateMetric() = runBlocking<Unit> {
        val cloud = FakeMiCloud().withSampleData()
        cloud.failingRoutes["time:heart_rate"] = HttpStatusCode.InternalServerError
        val store = RecordingHealthStore()
        val repository = cloud.repository(store)

        val result = repository.syncAll(FROM, TO, setOf("heart_rate", "workouts", "steps", "distance"))

        assertEquals(1, result.failed, "unexpected errors: ${result.errorMessages}")
        val progress = repository.syncProgress.value
        assertIs<SyncState.Error>(progress.heartRate)
        assertIs<SyncState.Success>(progress.workouts)
        assertIs<SyncState.Success>(progress.steps)
        assertIs<SyncState.Success>(progress.distance)
        assertEquals(setOf("workouts", "steps", "distance"), store.written.keys)
    }

    @Test
    fun expiredSessionDuringWorkoutEnrichment_stillTriggersTheRefresh() = runBlocking<Unit> {
        val cloud = FakeMiCloud().withSampleData()
        cloud.expiredRoutesUntilRefresh += "time:heart_rate"
        val store = RecordingHealthStore()
        val repository = cloud.repository(store)

        val result = repository.syncAll(FROM, TO, setOf("workouts"))

        assertEquals(1, cloud.refreshCalls)
        assertEquals(0, result.failed, "unexpected errors: ${result.errorMessages}")
        assertEquals(1, store.written.getValue("workouts"))
    }

    @Test
    fun failedWorkouts_blockTheCountersThatSubtractThem() = runBlocking<Unit> {
        val cloud = FakeMiCloud().withSampleData()
        cloud.failingRoutes["sport"] = HttpStatusCode.InternalServerError
        val store = RecordingHealthStore()
        val repository = cloud.repository(store)

        repository.syncAll(FROM, TO, setOf("workouts", "steps", "spo2"))

        val progress = repository.syncProgress.value
        assertIs<SyncState.Error>(progress.workouts)
        assertIs<SyncState.Error>(progress.steps)
        assertIs<SyncState.Success>(progress.spo2)
        assertEquals(setOf("spo2"), store.written.keys)
    }

    @Test
    fun successfulReads_areFetchedOnceAcrossMetrics() = runBlocking<Unit> {
        val cloud = FakeMiCloud().withSampleData()
        val store = RecordingHealthStore()

        val result = cloud.repository(store).syncAll(FROM, TO, setOf("heart_rate", "workouts", "steps", "distance"))

        assertEquals(0, result.failed, "unexpected errors: ${result.errorMessages}")
        assertEquals(1, cloud.requestCount("time:heart_rate"))
        assertEquals(1, cloud.requestCount("time:steps"))
        assertEquals(1, cloud.requestCount("sport"))
    }

    private companion object {
        const val FROM = "2023-11-14T00:00:00Z"
        const val TO = "2023-11-15T00:00:00Z"
        const val SAMPLE_TIME = 1_700_000_000L
    }

    /** Speaks Mi's encrypted protocol so the production [MiDataClient] runs unmodified. */
    private class FakeMiCloud {
        private val ssecurity = Base64.encode("fake-ssecurity-16".encodeToByteArray())
        private val credentials = MiCredentials(
            userId = "1",
            ssecurity = ssecurity,
            serviceToken = "service-token",
            passToken = "pass-token",
            deviceId = "device",
            region = "sg",
        )
        private val lock = Mutex()
        private val requests = mutableListOf<String>()
        private val rows = mutableMapOf<String, JsonArray>()
        private val dataStore = MemoryStore()

        var sessionValid = true
        var refreshCalls = 0
            private set
        val failingRoutes = mutableMapOf<String, HttpStatusCode>()
        val expiredRoutesUntilRefresh = mutableSetOf<String>()

        private val api = MiDirectApi(
            MiDataClient(
                credentials = credentials,
                httpClient = HttpClient(
                    MockEngine { request ->
                        val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                        val signedNonce = MiCloudSigner.computeSignedNonce(ssecurity, form["_nonce"].orEmpty())
                        val payload = Json.parseToJsonElement(
                            MiCloudSigner.decryptResponse(signedNonce, form["data"].orEmpty()).decodeToString(),
                        ).jsonObject
                        val route = routeOf(request.url.encodedPath, payload)
                        lock.withLock { requests += route }
                        val failure = failingRoutes[route]
                        when {
                            !sessionValid || route in expiredRoutesUntilRefresh -> respond("", HttpStatusCode.Unauthorized)
                            failure != null -> respond("", failure)
                            else -> respond(encrypt(signedNonce, responseFor(route)), HttpStatusCode.OK)
                        }
                    },
                ),
            ),
        )

        fun repository(store: HealthStore) = HealthRepository(
            apiProvider = { api },
            currentUserId = { credentials.userId },
            ensureSession = { true },
            refreshSession = {
                refreshCalls++
                sessionValid = true
                expiredRoutesUntilRefresh.clear()
                SessionRefreshResult.Success
            },
            healthWriter = store,
            bodyLedger = BodySyncLedger(dataStore),
            importLedger = HealthImportLedger(dataStore),
            syncPreferences = SyncPreferences(dataStore),
        )

        fun requestCount(route: String): Int = requests.count { it == route }

        fun withSampleData(): FakeMiCloud = apply {
            rows["time:heart_rate"] = rowsOf("heart_rate", """{"time":$SAMPLE_TIME,"bpm":72}""")
            rows["time:steps"] = rowsOf("steps", """{"time":$SAMPLE_TIME,"steps":10,"distance":7.5}""")
            rows["time:spo2"] = rowsOf("spo2", """{"time":$SAMPLE_TIME,"spo2":98}""")
            rows["time:sleep"] = rowsOf(
                "sleep",
                """
                    {"bedtime":$SAMPLE_TIME,"wake_up_time":${SAMPLE_TIME + 1300},"items":[
                      {"start_time":$SAMPLE_TIME,"end_time":${SAMPLE_TIME + 1100},"state":2},
                      {"start_time":${SAMPLE_TIME + 1200},"end_time":${SAMPLE_TIME + 1300},"state":3}
                    ]}
                """.trimIndent(),
            )
            rows["sport"] = rowsOf(
                "outdoor_running",
                """{"start_time":$SAMPLE_TIME,"end_time":${SAMPLE_TIME + 3600},"duration":3600,"distance":5000}""",
            )
        }

        private fun routeOf(path: String, payload: JsonObject): String {
            val key = payload["key"]?.jsonPrimitive?.content
                ?: payload["params"]?.jsonArray?.firstOrNull()?.jsonObject?.get("key")?.jsonPrimitive?.content
            return when {
                path.endsWith("/get_fitness_data_by_time") -> "time:$key"
                path.endsWith("/get_latest_fitness_data") -> "latest:$key"
                path.endsWith("/get_sport_records_by_time") -> "sport"
                else -> path
            }
        }

        private fun responseFor(route: String): String {
            val listName = if (route == "sport") "sport_records" else "data_list"
            return buildJsonObject {
                put("code", 0)
                put(
                    "result",
                    buildJsonObject {
                        put(listName, rows[route] ?: JsonArray(emptyList()))
                        put("has_more", false)
                    },
                )
            }.toString()
        }

        private fun rowsOf(key: String, value: String): JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("key", key)
                    put("time", SAMPLE_TIME)
                    put("value", JsonPrimitive(value))
                },
            )
        }

        /** RC4 is symmetric, so the client's own decrypt step also produces the ciphertext. */
        private fun encrypt(signedNonce: String, plaintext: String): String =
            Base64.encode(MiCloudSigner.decryptResponse(signedNonce, Base64.encode(plaintext.encodeToByteArray())))
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private class RecordingHealthStore : HealthStore {
        private val lock = Mutex()
        val written = mutableMapOf<String, Int>()

        private suspend fun record(metric: String, count: Int) = lock.withLock { written[metric] = count }

        override suspend fun writeHeartRate(samples: List<HeartRateSample>) = record("heart_rate", samples.size)
        override suspend fun writeRestingHeartRate(samples: List<HeartRateSample>) = record("resting_heart_rate", samples.size)
        override suspend fun writeSleep(sessions: List<SleepSession>) = record("sleep", sessions.size)
        override suspend fun writeSteps(records: List<StepsRecord>) = record("steps", records.size)
        override suspend fun writeDistance(samples: List<DistanceSample>) = record("distance", samples.size)
        override suspend fun writeActiveCalories(samples: List<ActiveCaloriesSample>) = record("active_calories", samples.size)
        override suspend fun writeSpO2(samples: List<SpO2Sample>) = record("spo2", samples.size)
        override suspend fun writeWeight(measurements: List<WeightMeasurement>) = record("weight", measurements.size)
        override suspend fun writeWorkouts(sessions: List<WorkoutSession>) = record("workouts", sessions.size)
        override suspend fun writeBloodPressure(samples: List<BloodPressureSample>) = record("blood_pressure", samples.size)
        override suspend fun writeTemperature(samples: List<TemperatureSample>) = record("temperature", samples.size)
        override suspend fun writeVo2Max(samples: List<Vo2MaxSample>) = record("vo2_max", samples.size)
        override suspend fun writeHrv(samples: List<HrvSample>) = record("hrv", samples.size)
        override suspend fun readLatestWeight(): WeightMeasurement? = null
        override suspend fun isAvailable() = true
        override suspend fun availabilityHint(): String? = null
        override fun healthServiceName() = "Fake"
        override fun openHealthService() = Unit
        override suspend fun hasWritePermissions() = true
        override suspend fun requestPermissions() = Unit
    }
}
