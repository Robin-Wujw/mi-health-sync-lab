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
    fun existingExportSelectionsDoNotRequestInboundPermissions() {
        assertTrue(SyncSelection.inboundMetrics(SyncPreferences.ALL_METRIC_KEYS).isEmpty())
        assertTrue(SyncPreferences.ALL_METRIC_KEYS.intersect(SyncPreferences.INBOUND_METRIC_KEYS).isEmpty())
    }

    @Test
    fun inboundOnlySelectionIsVisibleAndCanRun() {
        val enabled = setOf(SyncPreferences.SLEEP_IMPORT_KEY)
        val state = SyncUiState(enabledMetrics = enabled, prefsReady = true)

        assertTrue(state.hasSelectedSyncTasks)
        assertTrue(state.visibleMetrics.isEmpty())
        assertEquals(listOf(SyncPreferences.SLEEP_IMPORT_KEY), state.visibleInboundMetrics)
        assertEquals(setOf(HealthInboundMetric.SLEEP), SyncSelection.inboundMetrics(enabled))
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
}
