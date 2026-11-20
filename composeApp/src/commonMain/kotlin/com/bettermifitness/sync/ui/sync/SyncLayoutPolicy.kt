package com.bettermifitness.sync.ui.sync

internal data class SyncLayoutPolicy(val stackedMetrics: Boolean, val inlineActions: Boolean)

internal fun syncLayoutPolicy(widthDp: Float, heightDp: Float, fontScale: Float): SyncLayoutPolicy = SyncLayoutPolicy(
    stackedMetrics = widthDp <= 360f || fontScale >= 1.3f,
    inlineActions = heightDp < 420f || fontScale >= 1.6f,
)
