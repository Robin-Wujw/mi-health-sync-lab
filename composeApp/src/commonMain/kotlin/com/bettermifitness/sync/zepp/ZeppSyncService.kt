package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.MiDirectHealthImportCloud
import com.bettermifitness.sync.data.api.MiDirectWorkoutImportCloud
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.datetime.TimeZone

data class ZeppSyncReport(val source: ZeppSourceSnapshot, val rows: ZeppImportResult,
    val workouts: ZeppImportResult, val excluded: Int) {
    val verified: Int get() = rows.verified + workouts.verified
    val unresolved: Int get() = rows.pending + rows.conflicts + workouts.pending + workouts.conflicts + excluded +
        source.incomplete.size + source.invalidItems
}

/** Shared by Settings, foreground sync and the real background worker. */
class ZeppSyncService(
    private val store: ZeppSessionStore,
    private val client: ZeppCloudClient,
    private val session: MiSessionManager,
    private val journal: HealthImportLedger,
    private val preferences: SyncPreferences,
) {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow<ZeppSourceSnapshot?>(null)
    val snapshot: StateFlow<ZeppSourceSnapshot?> = mutableSnapshot
    val connected = store.connected

    /** True when a Zepp session is available for automatic/manual sync. */
    suspend fun isConnectedNow(): Boolean = connected.first()

    suspend fun connect(account: String, password: String, country: String) = mutex.withLock {
        val result = client.login(account.trim(), password, country.trim().uppercase())
        store.save(result)
        mutableSnapshot.value = null
    }

    suspend fun disconnect() = mutex.withLock {
        store.clear()
        preferences.setMetricEnabled(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY, false)
        mutableSnapshot.value = null
    }

    suspend fun inspect(days: Int): ZeppSourceSnapshot = mutex.withLock {
        val until = Clock.System.now().epochSeconds
        read(until - days.coerceIn(1, 31) * 86_400L, until)
    }

    suspend fun workoutHeartRate(workout: com.bettermifitness.sync.health.InboundWorkout):
        List<com.bettermifitness.sync.data.api.WorkoutTimedSample>? = workoutHeartRateReader(listOf(workout))(workout)

    /** A new reader per import run: index reuse never survives a run or an account rotation. */
    fun workoutHeartRateReader(workouts: List<com.bettermifitness.sync.health.InboundWorkout>):
        suspend (com.bettermifitness.sync.health.InboundWorkout) -> List<com.bettermifitness.sync.data.api.WorkoutTimedSample>? {
        val eligible = workouts.filter { it.origin.sourcePackage == "com.huami.watch.hmwatchmanager" }
        val from = eligible.minOfOrNull { it.session.startTime }?.minus(1)
        val until = eligible.maxOfOrNull { it.session.endTime }?.plus(1)
        var index: ZeppSourceSnapshot? = null
        var indexAccount: String? = null
        var indexUnavailable = false
        return { workout -> mutex.withLock {
            val enabled = preferences.enabledMetrics.first()
            if (SyncPreferences.WORKOUTS_IMPORT_KEY !in enabled || workout !in eligible) null else {
                val credentials = store.load()
                if (credentials == null) null else {
                    if (indexAccount != credentials.userId) {
                        index = null; indexUnavailable = false; indexAccount = credentials.userId
                    }
                    suspend fun read(current: ZeppSession): List<com.bettermifitness.sync.data.api.WorkoutTimedSample> {
                        if (index == null && !indexUnavailable && from != null && until != null && until - from <= 31 * 86_400) {
                            val loaded = client.fetch(current, from, until, setOf(ZeppSyncMetric.WORKOUT_SCORES))
                            if (loaded.incomplete.isEmpty() && loaded.unavailable.isEmpty() && loaded.invalidItems == 0) index = loaded
                            else indexUnavailable = true
                        }
                        return client.workoutHeartRate(current, workout.session.startTime, workout.session.endTime, index)
                    }
                    try { read(credentials) } catch (e: ZeppException) {
                        if (e.failure != ZeppFailure.AUTH) throw e
                        val rotated = client.refresh(credentials)
                        store.save(rotated)
                        index = null; indexUnavailable = false
                        read(rotated)
                    }
                }
            }
        } }
    }

    var sleepBreathStatus: String = "NOT_READ"
        private set
    val sleepBreathDiagnostic: Map<String, Int> get() =
        if (sleepBreathStatus == "COMPLETED") client.sleepBreathDiagnostic else emptyMap()

    suspend fun sleepBreath(sleeps: List<com.bettermifitness.sync.health.HealthInboundSleepRecord>):
        List<com.bettermifitness.sync.data.api.SleepVitalPoint> = mutex.withLock {
        sleepBreathStatus = "DISABLED"
        if (SyncPreferences.SLEEP_IMPORT_KEY !in preferences.enabledMetrics.first()) return@withLock emptyList()
        sleepBreathStatus = "NO_SESSION"
        val credentials = store.load() ?: return@withLock emptyList()
        sleepBreathStatus = "REQUESTED"
        val source = sleeps.filter { it.origin.sourcePackage == "com.huami.watch.hmwatchmanager" }
        try { client.sleepBreath(credentials, source).also { sleepBreathStatus = "COMPLETED" } }
        catch (error: ZeppException) {
            if (error.failure != ZeppFailure.AUTH) throw error
            val rotated = client.refresh(credentials)
            store.save(rotated)
            client.sleepBreath(rotated, source).also { sleepBreathStatus = "COMPLETED" }
        }
    }

    suspend fun sync(account: String, from: Long, until: Long): ZeppSyncReport = mutex.withLock {
        // Zepp metrics are part of the normal sync pipeline once the account is
        // connected. Keep the old preference only for migration/compatibility;
        // it is no longer a second sync gate exposed to users.
        val selected = ZeppSyncMetric.defaultSelection
        // Independently bounded source windows; a 90-day HC selection never causes an unbounded Zepp scan.
        val source = read(maxOf(from, until - 31 * 86_400L), until, selected)
        val plan = MiZeppScoreMapper.map(source.observations, stressDisplayTimeZone = TimeZone.currentSystemDefault())
        val namespace = "$account:zepp:${HealthRecordIds.version(store.load()?.userId)}"
        val raw = MiZeppImportRunner(MiDirectHealthImportCloud(apiProvider = { session.api }), journal).run(namespace, plan.rows)
        val workouts = MiZeppWorkoutScoresRunner(MiDirectWorkoutImportCloud { session.api }, journal)
            .run(namespace, source.workouts, preferences.workoutImportLedger().verifiedTargets(account))
        ZeppSyncReport(source, raw, workouts, plan.excluded)
    }

    private suspend fun read(from: Long, until: Long,
        selected: Set<ZeppSyncMetric> = ZeppSyncMetric.entries.toSet()): ZeppSourceSnapshot {
        val credentials = store.load() ?: throw ZeppException(ZeppFailure.AUTH)
        val source = try { client.fetch(credentials, from, until, selected) }
        catch (e: ZeppException) {
            if (e.failure != ZeppFailure.AUTH) throw e
            val rotated = client.refresh(credentials)
            store.save(rotated)
            client.fetch(rotated, from, until, selected)
        }
        return source.also { mutableSnapshot.value = it }
    }
}
