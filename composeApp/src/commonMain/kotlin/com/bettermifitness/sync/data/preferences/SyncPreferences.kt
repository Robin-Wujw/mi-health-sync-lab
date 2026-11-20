package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.bettermifitness.sync.zepp.ZeppSyncMetric
import com.bettermifitness.sync.sync.AutoSyncCheckpoint
import com.bettermifitness.sync.sync.AutoSyncContinuation
import com.bettermifitness.sync.sync.AutomaticRetry
import com.bettermifitness.sync.sync.AutomaticSkip
import com.bettermifitness.sync.sync.SyncTaskSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.datetime.TimeZone

/**
 * Process-wide scope for hot preference flows. Lives as long as the app;
 * cancelled only on process death (never in practice).
 */
private val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * User sync settings and last-sync timestamps (SRP: not credentials).
 *
 * Owns the single hot [snapshot] flow: one DataStore subscription for the whole
 * process (started via [start]), so screens never replay the cold-load pop-in.
 * Per DataStore docs, expose the flow and share it; collectors only observe.
 */
class SyncPreferences(
    private val dataStore: DataStore<Preferences>,
) : SyncPreferencesPort {
    val sourceCalendarZoneId: Flow<String?> = dataStore.data.map { it[SOURCE_CALENDAR_ZONE_KEY] }
    override suspend fun readSourceCalendarZoneId(): String? = sourceCalendarZoneId.first()

    suspend fun setSourceCalendarZoneId(id: String?) {
        val zone = id?.let { TimeZone.of(it) }
        dataStore.edit { prefs ->
            if (zone == null) prefs.remove(SOURCE_CALENDAR_ZONE_KEY) else prefs[SOURCE_CALENDAR_ZONE_KEY] = zone.id
            prefs.remove(AUTOMATIC_PLAN_KEY)
        }
    }
    val lastAutomaticSkip: Flow<AutomaticSkip?> = dataStore.data.map { prefs ->
        prefs[AUTOMATIC_SKIP_KEY]?.let { Json.decodeFromString<AutomaticSkip>(it) }
            ?.takeIf { it.generation == CredentialsStore.signedInGeneration(prefs) }
    }

    override suspend fun storeAutomaticSkip(skip: AutomaticSkip) {
        dataStore.edit { prefs ->
            if (skip.generation == CredentialsStore.signedInGeneration(prefs)) {
                prefs[AUTOMATIC_SKIP_KEY] = Json.encodeToString(skip)
            }
        }
    }
    val completedTasks: Flow<List<SyncTaskSummary>> = dataStore.data.map { preferences ->
        preferences[COMPLETED_TASKS_KEY]?.let { Json.decodeFromString<List<SyncTaskSummary>>(it) }.orEmpty()
    }

    suspend fun recordCompletedTask(summary: SyncTaskSummary) {
        dataStore.edit { preferences ->
            val prior = preferences[COMPLETED_TASKS_KEY]?.let { Json.decodeFromString<List<SyncTaskSummary>>(it) }.orEmpty()
            preferences[COMPLETED_TASKS_KEY] = Json.encodeToString((listOf(summary) + prior.filterNot { it.id == summary.id }).take(20))
        }
    }
    override val lastSyncActivityTime: Flow<String?> = dataStore.data.map { it[LAST_SYNC_ACTIVITY_KEY] }

    val lastSyncTime: Flow<String?> = dataStore.data.map { it[LAST_SYNC_KEY] }

    val lastBackgroundSyncTime: Flow<String?> = dataStore.data.map { it[LAST_BACKGROUND_SYNC_KEY] }

    /** Last run status code from [com.bettermifitness.sync.sync.SyncOutcome.toStatusCode]. */
    val lastSyncStatus: Flow<String?> = dataStore.data.map { it[LAST_SYNC_STATUS_KEY] }

    val lastSyncMessage: Flow<String?> = dataStore.data.map { it[LAST_SYNC_MESSAGE_KEY] }

    val lastBackgroundSyncStatus: Flow<String?> =
        dataStore.data.map { it[LAST_BACKGROUND_SYNC_STATUS_KEY] }

    val lastBackgroundSyncMessage: Flow<String?> =
        dataStore.data.map { it[LAST_BACKGROUND_SYNC_MESSAGE_KEY] }

    override val autoSync: Flow<Boolean> = dataStore.data.map {
        it[AUTO_SYNC_KEY]?.toBooleanStrictOrNull() ?: false
    }

    override val enabledMetrics: Flow<Set<String>> = flow {
        // Apply the requested defaults once, including on upgrades. Later user choices
        // survive process recreation and sign-in, including explicitly disabled imports.
        dataStore.edit { applyDefaultMetrics(it) }
        emitAll(dataStore.data.map { it[ENABLED_METRICS_KEY] ?: DEFAULT_METRIC_KEYS })
    }

    override val syncRangeDays: Flow<Int> = dataStore.data.map {
        it[SYNC_RANGE_DAYS_KEY] ?: 7
    }

    override suspend fun captureSyncConfig(): SyncConfig = dataStore.edit { applyDefaultMetrics(it) }.let {
        SyncConfig(it[SYNC_RANGE_DAYS_KEY] ?: 7, it[ENABLED_METRICS_KEY] ?: DEFAULT_METRIC_KEYS, it[SOURCE_CALENDAR_ZONE_KEY])
    }

    /** Opt-in: one real source row per selected import; no daily summaries. */
    val inboundTestMode: Flow<Boolean> = dataStore.data.map {
        it[INBOUND_TEST_MODE_KEY]?.toBooleanStrictOrNull() ?: false
    }

    /** The legacy cloud switch remains the opt-in gate; individual choices persist independently. */
    val zeppMetrics: Flow<Set<ZeppSyncMetric>> = dataStore.data.map { prefs ->
        val selected = prefs[ZEPP_SELECTED_METRICS_KEY]
        ZeppSyncMetric.entries.filterTo(mutableSetOf()) {
            it != ZeppSyncMetric.WORKOUT_HEART_RATE &&
                (if (selected == null) it in ZeppSyncMetric.defaultSelection else it.key in selected)
        }
    }

    suspend fun setZeppMetricEnabled(metric: ZeppSyncMetric, enabled: Boolean) {
        if (metric == ZeppSyncMetric.WORKOUT_HEART_RATE) return // Automatically maintained by workout sync.
        dataStore.edit { prefs ->
            val current = (prefs[ZEPP_SELECTED_METRICS_KEY] ?: ZeppSyncMetric.defaultSelection.map { it.key }.toSet()) -
                ZeppSyncMetric.WORKOUT_HEART_RATE.key
            val selected = if (enabled) current + metric.key else current - metric.key
            prefs[ZEPP_SELECTED_METRICS_KEY] = selected
            if (selected.isEmpty()) prefs[ENABLED_METRICS_KEY] =
                (prefs[ENABLED_METRICS_KEY] ?: DEFAULT_METRIC_KEYS) - ZEPP_CLOUD_IMPORT_KEY
        }
    }

    /**
     * Hot snapshot shared by all screens: single DataStore subscription for the
     * whole process. Started eagerly so the first emission lands before screens
     * draw; collectors created later observe the latest value immediately.
     * Sync/config writes still go through the suspend setters below.
     */
    val snapshot: StateFlow<UserPrefsSnapshot> = combine(combine(
        enabledMetrics,
        syncRangeDays,
        autoSync,
        lastSyncTime,
        lastBackgroundSyncTime,
    ) { enabled, rangeDays, auto, lastSync, lastBg ->
        UserPrefsSnapshot(
            ready = true,
            enabledMetrics = enabled,
            syncRangeDays = rangeDays,
            autoSync = auto,
            lastSyncTime = lastSync,
            lastBackgroundSyncTime = lastBg,
        )
    }, inboundTestMode, sourceCalendarZoneId) { snapshot, testMode, zone ->
        snapshot.copy(inboundTestMode = testMode, sourceCalendarZoneId = zone)
    }.stateIn(
        scope = prefsScope,
        started = SharingStarted.Eagerly,
        initialValue = UserPrefsSnapshot(),
    )

    override suspend fun updateLastSync(timestamp: String) {
        dataStore.edit { it[LAST_SYNC_KEY] = timestamp }
    }

    override suspend fun readAutoSyncTiming(): AutoSyncTiming = dataStore.data.first().let {
        AutoSyncTiming(it[LAST_AUTOMATIC_START_KEY], it[LAST_MANUAL_FINISH_KEY], it[AUTOMATIC_PAUSED_UNTIL_KEY],
            it[AUTOMATIC_RETRY_KEY]?.let { value -> Json.decodeFromString<AutomaticRetry>(value) })
    }

    override suspend fun acceptAutomaticStart(at: Long): Boolean {
        var accepted = false
        dataStore.edit { prefs ->
            if (prefs[AUTO_SYNC_KEY] == "true") {
                prefs[LAST_AUTOMATIC_START_KEY] = at
                prefs.remove(AUTOMATIC_RETRY_KEY)
                accepted = true
            }
        }
        return accepted
    }

    override suspend fun recordManualFinish(at: Long) {
        dataStore.edit { it[LAST_MANUAL_FINISH_KEY] = at }
    }

    override suspend fun pauseAutomaticUntil(at: Long) {
        dataStore.edit { it[AUTOMATIC_PAUSED_UNTIL_KEY] = at }
    }

    override suspend fun storeAutomaticRetry(retry: AutomaticRetry?) {
        dataStore.edit { prefs ->
            if (retry == null) prefs.remove(AUTOMATIC_RETRY_KEY)
            else if (prefs[AUTO_SYNC_KEY] == "true") prefs[AUTOMATIC_RETRY_KEY] = Json.encodeToString(retry)
        }
    }

    override suspend fun readAutomaticCheckpoints(keys: Set<String>): Map<String, AutoSyncCheckpoint> {
        val snapshot = dataStore.data.first()
        return keys.mapNotNull { key -> snapshot[automaticCheckpointKey(key)]?.let {
            key to Json.decodeFromString<AutoSyncCheckpoint>(it)
        } }.toMap()
    }

    override suspend fun writeAutomaticCheckpoints(checkpoints: Map<String, AutoSyncCheckpoint>) {
        if (checkpoints.isEmpty()) return
        dataStore.edit { prefs -> prefs.putAutomaticCheckpoints(checkpoints) }
    }

    override suspend fun readAutomaticContinuation(): AutoSyncContinuation? = dataStore.data.first()[AUTOMATIC_PLAN_KEY]
        ?.let { Json.decodeFromString<AutoSyncContinuation>(it) }

    override suspend fun saveAutomaticProgress(
        generation: Long,
        checkpoints: Map<String, AutoSyncCheckpoint>,
        continuation: AutoSyncContinuation?,
    ) {
        dataStore.edit { prefs ->
            if (generation != CredentialsStore.signedInGeneration(prefs)) return@edit
            prefs.putAutomaticCheckpoints(checkpoints)
            if (continuation == null) prefs.remove(AUTOMATIC_PLAN_KEY)
            else if (prefs[AUTO_SYNC_KEY] == "true") prefs[AUTOMATIC_PLAN_KEY] = Json.encodeToString(continuation)
        }
    }

    private fun MutablePreferences.putAutomaticCheckpoints(checkpoints: Map<String, AutoSyncCheckpoint>) {
        checkpoints.forEach { (key, checkpoint) -> this[automaticCheckpointKey(key)] = Json.encodeToString(checkpoint) }
    }

    private fun automaticCheckpointKey(key: String) = stringPreferencesKey("automatic_checkpoint_v1_$key")

    override suspend fun updateLastSyncActivity(timestamp: String) {
        dataStore.edit { it[LAST_SYNC_ACTIVITY_KEY] = timestamp }
    }

    override suspend fun updateLastBackgroundSync(timestamp: String) {
        dataStore.edit { it[LAST_BACKGROUND_SYNC_KEY] = timestamp }
    }

    override suspend fun updateLastSyncOutcome(status: String, message: String?) {
        dataStore.edit { prefs ->
            prefs[LAST_SYNC_STATUS_KEY] = status
            if (message.isNullOrBlank()) {
                prefs.remove(LAST_SYNC_MESSAGE_KEY)
            } else {
                prefs[LAST_SYNC_MESSAGE_KEY] = message.take(400)
            }
        }
    }

    override suspend fun updateLastBackgroundSyncOutcome(status: String, message: String?) {
        dataStore.edit { prefs ->
            prefs[LAST_BACKGROUND_SYNC_STATUS_KEY] = status
            if (message.isNullOrBlank()) {
                prefs.remove(LAST_BACKGROUND_SYNC_MESSAGE_KEY)
            } else {
                prefs[LAST_BACKGROUND_SYNC_MESSAGE_KEY] = message.take(400)
            }
        }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        dataStore.edit {
            it[AUTO_SYNC_KEY] = enabled.toString()
            if (!enabled) {
                it.remove(AUTOMATIC_RETRY_KEY)
                it.remove(AUTOMATIC_PLAN_KEY)
            }
        }
    }

    suspend fun setMetricEnabled(key: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            applyDefaultMetrics(preferences)
            val current = preferences[ENABLED_METRICS_KEY] ?: DEFAULT_METRIC_KEYS
            preferences[ENABLED_METRICS_KEY] =
                if (enabled) current + key else current - key
        }
    }

    suspend fun setSyncRangeDays(days: Int) {
        dataStore.edit { it[SYNC_RANGE_DAYS_KEY] = days }
    }

    suspend fun setInboundTestMode(enabled: Boolean) {
        dataStore.edit { it[INBOUND_TEST_MODE_KEY] = enabled.toString() }
    }

    /** Session activity belongs to the previous sign-in; user configuration does not. */
    suspend fun clearSyncHistory() {
        dataStore.edit { prefs ->
            for (key in listOf(LAST_SYNC_KEY, LAST_SYNC_ACTIVITY_KEY, LAST_BACKGROUND_SYNC_KEY,
                LAST_SYNC_STATUS_KEY, LAST_SYNC_MESSAGE_KEY, LAST_BACKGROUND_SYNC_STATUS_KEY,
                LAST_BACKGROUND_SYNC_MESSAGE_KEY, LAST_AUTOMATIC_START_KEY, LAST_MANUAL_FINISH_KEY,
                AUTOMATIC_PAUSED_UNTIL_KEY, AUTOMATIC_RETRY_KEY, AUTOMATIC_SKIP_KEY, AUTOMATIC_PLAN_KEY)) prefs.remove(key)
        }
    }

    private fun applyDefaultMetrics(preferences: MutablePreferences) {
        if (preferences[DEFAULT_METRICS_APPLIED_KEY] != "true") {
            preferences[ENABLED_METRICS_KEY] =
                (preferences[ENABLED_METRICS_KEY] ?: ALL_METRIC_KEYS) + DEFAULT_ADDITIONAL_METRIC_KEYS
            preferences[DEFAULT_METRICS_APPLIED_KEY] = "true"
        }
        if (preferences[WORKOUT_DEFAULTS_APPLIED_KEY] != "true") {
            preferences[ENABLED_METRICS_KEY] = (preferences[ENABLED_METRICS_KEY] ?: DEFAULT_METRIC_KEYS) +
                setOf(WORKOUTS_IMPORT_KEY, VO2_MAX_IMPORT_KEY)
            preferences[WORKOUT_DEFAULTS_APPLIED_KEY] = "true"
        }
    }

    fun workoutImportLedger() = WorkoutImportLedger(dataStore)
    fun sleepFileLedger() = SleepFileLedger(dataStore)
    fun sleepSourceMigrationLedger() = SleepSourceMigrationLedger(dataStore)
    fun workoutRestoreLedger() = WorkoutRestoreLedger(dataStore)

    companion object {
        /** Independent switch for attaching body composition to weight imports. */
        const val BODY_COMPOSITION_IMPORT_KEY = "body_composition_import_experimental"

        const val HEART_RATE_IMPORT_KEY = "heart_rate_import_experimental"
        const val RESTING_HEART_RATE_IMPORT_KEY = "resting_heart_rate_import_experimental"
        const val SLEEP_IMPORT_KEY = "sleep_import_experimental"
        const val HRV_IMPORT_KEY = "hrv_import_experimental"
        const val SPO2_IMPORT_KEY = "spo2_import_experimental"
        const val WORKOUTS_IMPORT_KEY = "workouts_import_experimental"
        const val VO2_MAX_IMPORT_KEY = "vo2_max_import_experimental"
        const val ZEPP_CLOUD_IMPORT_KEY = "zepp_cloud_scores_import_experimental"
        val ZEPP_CLOUD_METRIC_KEYS = setOf(ZEPP_CLOUD_IMPORT_KEY)

        /** Separate keys for the Zepp → Xiaomi direction. */
        val INBOUND_METRIC_KEYS = setOf(
            HEART_RATE_IMPORT_KEY,
            RESTING_HEART_RATE_IMPORT_KEY,
            SLEEP_IMPORT_KEY,
            HRV_IMPORT_KEY,
            SPO2_IMPORT_KEY,
            WORKOUTS_IMPORT_KEY,
            VO2_MAX_IMPORT_KEY,
        )

        /** Xiaomi → Health Connect metric keys (also used to select write permissions). */
        val ALL_METRIC_KEYS = setOf(
            "heart_rate",
            "resting_heart_rate",
            "sleep",
            "hrv",
            "steps",
            "distance",
            "active_calories",
            "spo2",
            "weight",
            "workouts",
            "blood_pressure",
            "temperature",
            "vo2_max",
        )

        private val DEFAULT_ADDITIONAL_METRIC_KEYS =
            INBOUND_METRIC_KEYS + BODY_COMPOSITION_IMPORT_KEY + "weight"
        val DEFAULT_METRIC_KEYS = ALL_METRIC_KEYS + DEFAULT_ADDITIONAL_METRIC_KEYS

        private val WORKOUT_DEFAULTS_APPLIED_KEY = stringPreferencesKey("healthlab22_workout_defaults_applied")
        private val DEFAULT_METRICS_APPLIED_KEY = stringPreferencesKey("healthlab21_default_metrics_applied")

        private val LAST_SYNC_KEY = stringPreferencesKey("last_sync_time")
        private val LAST_SYNC_ACTIVITY_KEY = stringPreferencesKey("last_sync_activity_time")
        private val LAST_AUTOMATIC_START_KEY = longPreferencesKey("last_automatic_sync_start")
        private val AUTOMATIC_PAUSED_UNTIL_KEY = longPreferencesKey("automatic_sync_paused_until")
        private val AUTOMATIC_RETRY_KEY = stringPreferencesKey("automatic_retry_v1")
        private val AUTOMATIC_PLAN_KEY = stringPreferencesKey("automatic_plan_v1")
        private val SOURCE_CALENDAR_ZONE_KEY = stringPreferencesKey("health_connect_source_calendar_zone_v1")
        private val AUTOMATIC_SKIP_KEY = stringPreferencesKey("automatic_skip_v1")
        private val COMPLETED_TASKS_KEY = stringPreferencesKey("completed_sync_tasks_v1")
        private val LAST_MANUAL_FINISH_KEY = longPreferencesKey("last_manual_sync_finish")
        private val LAST_BACKGROUND_SYNC_KEY = stringPreferencesKey("last_background_sync_time")
        private val LAST_SYNC_STATUS_KEY = stringPreferencesKey("last_sync_status")
        private val LAST_SYNC_MESSAGE_KEY = stringPreferencesKey("last_sync_message")
        private val LAST_BACKGROUND_SYNC_STATUS_KEY =
            stringPreferencesKey("last_background_sync_status")
        private val LAST_BACKGROUND_SYNC_MESSAGE_KEY =
            stringPreferencesKey("last_background_sync_message")
        private val AUTO_SYNC_KEY = stringPreferencesKey("auto_sync")
        private val ENABLED_METRICS_KEY = stringSetPreferencesKey("enabled_metrics")
        private val SYNC_RANGE_DAYS_KEY = intPreferencesKey("sync_range_days")
        private val INBOUND_TEST_MODE_KEY = stringPreferencesKey("inbound_single_record_test_mode")
        private val ZEPP_SELECTED_METRICS_KEY = stringSetPreferencesKey("zepp_selected_score_metrics")
    }
}
