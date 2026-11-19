package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.bettermifitness.sync.zepp.ZeppSyncMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    }, inboundTestMode) { snapshot, testMode ->
        snapshot.copy(inboundTestMode = testMode)
    }.stateIn(
        scope = prefsScope,
        started = SharingStarted.Eagerly,
        initialValue = UserPrefsSnapshot(),
    )

    override suspend fun updateLastSync(timestamp: String) {
        dataStore.edit { it[LAST_SYNC_KEY] = timestamp }
    }

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
        dataStore.edit { it[AUTO_SYNC_KEY] = enabled.toString() }
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
                LAST_BACKGROUND_SYNC_MESSAGE_KEY)) prefs.remove(key)
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
