package com.bettermifitness.sync.ui.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncLayoutPolicyTest {
    @Test fun narrowAndLargeTextUseStackedMetricRows() {
        assertTrue(syncLayoutPolicy(320f, 640f, 1f).stackedMetrics)
        assertTrue(syncLayoutPolicy(360f, 640f, 1f).stackedMetrics)
        assertTrue(syncLayoutPolicy(411f, 640f, 1.3f).stackedMetrics)
        assertFalse(syncLayoutPolicy(411f, 640f, 1.25f).stackedMetrics)
    }

    @Test fun shortHeightOrVeryLargeTextMovesActionsIntoTheScrollContent() {
        assertTrue(syncLayoutPolicy(411f, 419f, 1f).inlineActions)
        assertTrue(syncLayoutPolicy(411f, 640f, 1.6f).inlineActions)
        assertFalse(syncLayoutPolicy(411f, 640f, 1.5f).inlineActions)
    }
}
