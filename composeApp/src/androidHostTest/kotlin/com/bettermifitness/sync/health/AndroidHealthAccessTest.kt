package com.bettermifitness.sync.health

import androidx.health.connect.client.permission.HealthPermission
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidHealthAccessTest {
    @Test fun activityStopImmediatelyClosesTheForegroundReadPath() {
        assertFalse(HealthConnectPermissionBridge.hasVisibleActivity)
        HealthConnectPermissionBridge.activityStarted()
        try {
            assertTrue(HealthConnectPermissionBridge.hasVisibleActivity)
        } finally {
            HealthConnectPermissionBridge.activityStopped()
        }
        assertFalse(HealthConnectPermissionBridge.hasVisibleActivity)
    }
    @Test fun backgroundCapabilityNeedsBothFeatureSupportAndGrant() {
        val permission = "record-read"
        val grant = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        assertEquals(HealthAccessBlock.BACKGROUND_UNAVAILABLE,
            androidExternalReadBlock(setOf(permission), permission, foreground = false, backgroundFeature = false))
        assertEquals(HealthAccessBlock.BACKGROUND_UNAVAILABLE,
            androidExternalReadBlock(setOf(permission, grant), permission, foreground = false, backgroundFeature = false))
        assertEquals(HealthAccessBlock.BACKGROUND_PERMISSION,
            androidExternalReadBlock(setOf(permission), permission, foreground = false, backgroundFeature = true))
        assertNull(androidExternalReadBlock(setOf(permission, grant), permission, foreground = false, backgroundFeature = true))
    }

    @Test fun foregroundAndHistoryAreIndependentlyChecked() {
        val permission = "record-read"
        assertNull(androidExternalReadBlock(setOf(permission), permission, foreground = true, backgroundFeature = false))
        assertEquals(HealthAccessBlock.READ_PERMISSION,
            androidExternalReadBlock(emptySet(), permission, foreground = true, backgroundFeature = true))
        assertEquals(HealthAccessBlock.HISTORY_PERMISSION,
            androidExternalReadBlock(setOf(permission), permission, foreground = true, backgroundFeature = true, needsHistory = true))
    }
}
