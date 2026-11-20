package com.bettermifitness.sync.sync

import com.bettermifitness.sync.i18n.L10n
import kotlinx.serialization.Serializable

@Serializable
enum class AutomaticSkipReason {
    NOT_DUE, MANUAL_BUSY, SYNC_BUSY, MAINTENANCE_BUSY, DISABLED, PAUSED, MANUAL_COOLDOWN, RETRY_DEFERRED,
    STALE_RETRY, ACCOUNT_CHANGED, SIGN_IN_REQUIRED, NO_SELECTION, HEALTH_UNAVAILABLE, MISSING_PERMISSION;

    internal fun label() = L10n.text(when (this) {
        NOT_DUE -> L10n.autoSkipNotDue
        MANUAL_BUSY -> L10n.autoSkipManualBusy
        SYNC_BUSY -> L10n.autoSkipSyncBusy
        MAINTENANCE_BUSY -> L10n.autoSkipMaintenanceBusy
        DISABLED -> L10n.autoSkipDisabled
        PAUSED -> L10n.autoSkipPaused
        MANUAL_COOLDOWN -> L10n.autoSkipManualCooldown
        RETRY_DEFERRED -> L10n.autoSkipRetryDeferred
        STALE_RETRY -> L10n.autoSkipStaleRetry
        ACCOUNT_CHANGED -> L10n.autoSkipAccountChanged
        SIGN_IN_REQUIRED -> L10n.outcomePleaseSignIn
        NO_SELECTION -> L10n.syncNothingToSync
        HEALTH_UNAVAILABLE -> L10n.outcomeHealthDetail
        MISSING_PERMISSION -> L10n.healthPermissionsIncomplete
    })
}

@Serializable
data class AutomaticSkip(val reason: AutomaticSkipReason, val at: String, val generation: Long) {
    internal fun label(): String = L10n.textFmt(L10n.autoLastSkip, displayTaskTime(at), reason.label())
}
