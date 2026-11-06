package com.bettermifitness.sync.ui

import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.health.HealthInboundMetric

/** UI selections count actual directions, not optional modifiers such as body composition. */
internal object SyncSelection {
    val taskKeys: Set<String> = SyncPreferences.ALL_METRIC_KEYS + SyncPreferences.INBOUND_METRIC_KEYS

    fun selectedTaskKeys(enabled: Set<String>): Set<String> = enabled.intersect(taskKeys)

    fun inboundMetrics(enabled: Set<String>): Set<HealthInboundMetric> = buildSet {
        if (SyncPreferences.HEART_RATE_IMPORT_KEY in enabled) add(HealthInboundMetric.HEART_RATE)
        if (SyncPreferences.RESTING_HEART_RATE_IMPORT_KEY in enabled) add(HealthInboundMetric.RESTING_HEART_RATE)
        if (SyncPreferences.SLEEP_IMPORT_KEY in enabled) add(HealthInboundMetric.SLEEP)
        if (SyncPreferences.HRV_IMPORT_KEY in enabled) {
            add(HealthInboundMetric.HRV)
            // The experimental HRV summary needs an actual same-source sleep
            // interval even when sleep itself is not selected as an import task.
            add(HealthInboundMetric.SLEEP)
        }
        if (SyncPreferences.SPO2_IMPORT_KEY in enabled) add(HealthInboundMetric.OXYGEN_SATURATION)
    }

    fun sourceMetricKey(key: String): String = when (key) {
        SyncPreferences.HEART_RATE_IMPORT_KEY -> "heart_rate"
        SyncPreferences.RESTING_HEART_RATE_IMPORT_KEY -> "resting_heart_rate"
        SyncPreferences.SLEEP_IMPORT_KEY -> "sleep"
        SyncPreferences.HRV_IMPORT_KEY -> "hrv"
        SyncPreferences.SPO2_IMPORT_KEY -> "spo2"
        else -> key
    }
}
