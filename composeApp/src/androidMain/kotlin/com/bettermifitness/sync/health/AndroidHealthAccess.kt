package com.bettermifitness.sync.health

import androidx.health.connect.client.permission.HealthPermission

internal fun androidExternalReadBlock(
    granted: Set<String>,
    readPermission: String,
    foreground: Boolean,
    backgroundFeature: Boolean,
    needsHistory: Boolean = false,
): HealthAccessBlock? = when {
    readPermission !in granted -> HealthAccessBlock.READ_PERMISSION
    !foreground && !backgroundFeature -> HealthAccessBlock.BACKGROUND_UNAVAILABLE
    !foreground && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted -> HealthAccessBlock.BACKGROUND_PERMISSION
    needsHistory && HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY !in granted -> HealthAccessBlock.HISTORY_PERMISSION
    else -> null
}
