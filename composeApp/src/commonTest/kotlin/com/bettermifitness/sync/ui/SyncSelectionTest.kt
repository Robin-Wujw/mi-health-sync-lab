package com.bettermifitness.sync.ui

import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.ui.sync.SyncUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSelectionTest {
    @Test
    fun zeppCloudScoresAreAnIndependentTaskWithoutHealthConnectPermissions() {
        val enabled = setOf(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY)
        val state = SyncUiState(enabledMetrics = enabled, prefsReady = true)
        assertTrue(state.hasSelectedSyncTasks)
        assertEquals(enabled.toList(), state.visibleInboundMetrics)
        assertTrue(SyncSelection.inboundMetrics(enabled).isEmpty())
        assertFalse(SyncSelection.requiresHealthConnect(enabled))
        assertTrue(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY !in SyncPreferences.DEFAULT_METRIC_KEYS)
    }
    @Test
    fun existingExportSelectionsDoNotRequestInboundPermissions() {
        assertTrue(SyncSelection.inboundMetrics(SyncPreferences.ALL_METRIC_KEYS).isEmpty())
        assertTrue(SyncSelection.requiresHealthConnect(SyncPreferences.ALL_METRIC_KEYS))
        assertTrue(SyncPreferences.ALL_METRIC_KEYS.intersect(SyncPreferences.INBOUND_METRIC_KEYS).isEmpty())
    }

    @Test
    fun inboundOnlySelectionIsVisibleAndCanRun() {
        val enabled = setOf(SyncPreferences.SLEEP_IMPORT_KEY)
        val state = SyncUiState(enabledMetrics = enabled, prefsReady = true)

        assertTrue(state.hasSelectedSyncTasks)
        assertTrue(state.visibleMetrics.isEmpty())
        assertEquals(listOf(SyncPreferences.SLEEP_IMPORT_KEY), state.visibleInboundMetrics)
        assertEquals(setOf(HealthInboundMetric.SLEEP, HealthInboundMetric.HEART_RATE,
            HealthInboundMetric.OXYGEN_SATURATION), SyncSelection.inboundMetrics(enabled))
    }

    @Test
    fun optionalModifierAndUnknownSavedKeysAreNotRunnableTasks() {
        val enabled = setOf(SyncPreferences.BODY_COMPOSITION_IMPORT_KEY, "unknown_saved_option")
        val state = SyncUiState(enabledMetrics = enabled, prefsReady = true)

        assertFalse(state.hasSelectedSyncTasks)
        assertTrue(SyncSelection.selectedTaskKeys(enabled).isEmpty())
        assertTrue(state.visibleInboundMetrics.isEmpty())
    }

    @Test
    fun twoDirectionsForOneMetricRemainDistinctTasks() {
        val enabled = setOf("heart_rate", SyncPreferences.HEART_RATE_IMPORT_KEY, SyncPreferences.BODY_COMPOSITION_IMPORT_KEY)

        assertEquals(setOf("heart_rate", SyncPreferences.HEART_RATE_IMPORT_KEY), SyncSelection.selectedTaskKeys(enabled))
        assertEquals(setOf(HealthInboundMetric.HEART_RATE), SyncSelection.inboundMetrics(enabled))
    }

    @Test
    fun hrvAndBloodOxygenSelectionsNeedSleepReadButDoNotAddASleepSyncTask() {
        val enabled = setOf(SyncPreferences.HRV_IMPORT_KEY, SyncPreferences.SPO2_IMPORT_KEY)
        assertEquals(setOf(HealthInboundMetric.HRV, HealthInboundMetric.SLEEP,
            HealthInboundMetric.OXYGEN_SATURATION), SyncSelection.inboundMetrics(enabled))
        assertEquals(enabled, SyncSelection.selectedTaskKeys(enabled))
        assertTrue(enabled.none { it in SyncPreferences.ALL_METRIC_KEYS })
        assertEquals("hrv", SyncSelection.sourceMetricKey(SyncPreferences.HRV_IMPORT_KEY))
        assertEquals("spo2", SyncSelection.sourceMetricKey(SyncPreferences.SPO2_IMPORT_KEY))
    }
    @Test
    fun workoutAndVo2MaxImportSwitchesRequestTheirOwnReadTypesAndRemainSeparateFromExports() {
        val enabled = setOf("workouts", "vo2_max", SyncPreferences.WORKOUTS_IMPORT_KEY, SyncPreferences.VO2_MAX_IMPORT_KEY)
        assertEquals(enabled, SyncSelection.selectedTaskKeys(enabled))
        assertEquals(setOf(HealthInboundMetric.WORKOUTS, HealthInboundMetric.VO2_MAX), SyncSelection.inboundMetrics(enabled))
        assertEquals("workouts", SyncSelection.sourceMetricKey(SyncPreferences.WORKOUTS_IMPORT_KEY))
        assertEquals("vo2_max", SyncSelection.sourceMetricKey(SyncPreferences.VO2_MAX_IMPORT_KEY))
    }

}
