package com.bettermifitness.sync.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.bettermifitness.sync.zepp.ZeppSyncMetric

class WorkoutDefaultsMigrationTest {
    @Test fun zeppSelectionSurvivesRecreationAndEmptySelectionDisablesCloudOnly() = runBlocking<Unit> {
        val store = MemoryStore()
        val preferences = SyncPreferences(store)
        assertFalse(ZeppSyncMetric.WORKOUT_HEART_RATE in preferences.zeppMetrics.first())
        preferences.enabledMetrics.first()
        preferences.setMetricEnabled(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY, true)
        for (metric in ZeppSyncMetric.entries) preferences.setZeppMetricEnabled(metric, false)
        val recreated = SyncPreferences(store)
        assertTrue(recreated.zeppMetrics.first().isEmpty())
        assertFalse(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY in recreated.enabledMetrics.first())
        assertTrue(SyncPreferences.WORKOUTS_IMPORT_KEY in recreated.enabledMetrics.first())
        recreated.setZeppMetricEnabled(ZeppSyncMetric.TRAINING_LOAD, true)
        assertEquals(setOf(ZeppSyncMetric.TRAINING_LOAD), SyncPreferences(store).zeppMetrics.first())
        assertFalse(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY in recreated.enabledMetrics.first())
    }
    @Test
    fun addsNewImportsOnceAndRetainsExistingDisabledOptionsAndLaterChoices() = runBlocking<Unit> {
        val store = MemoryStore()
        val saved = setOf("steps", SyncPreferences.SLEEP_IMPORT_KEY)
        store.edit {
            it[stringPreferencesKey("healthlab21_default_metrics_applied")] = "true"
            it[stringSetPreferencesKey("enabled_metrics")] = saved
            it[stringPreferencesKey("auto_sync")] = "true"
        }
        val preferences = SyncPreferences(store)
        assertEquals(saved + setOf(SyncPreferences.WORKOUTS_IMPORT_KEY, SyncPreferences.VO2_MAX_IMPORT_KEY),
            preferences.enabledMetrics.first())
        preferences.setMetricEnabled(SyncPreferences.WORKOUTS_IMPORT_KEY, false)
        preferences.setMetricEnabled(SyncPreferences.VO2_MAX_IMPORT_KEY, false)
        val recreated = SyncPreferences(store)
        assertEquals(saved, recreated.enabledMetrics.first())
        assertTrue(recreated.autoSync.first())
        assertFalse(SyncPreferences.HEART_RATE_IMPORT_KEY in recreated.enabledMetrics.first())
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
}
