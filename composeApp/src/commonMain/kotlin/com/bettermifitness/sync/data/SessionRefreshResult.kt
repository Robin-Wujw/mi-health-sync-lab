package com.bettermifitness.sync.data

import com.bettermifitness.sync.i18n.L10n

/**
 * Outcome of passToken → serviceToken refresh (APK force-refresh parity).
 */
sealed class SessionRefreshResult {
    data object Success : SessionRefreshResult()

    /** passToken invalid/missing — full sign-in required. */
    data class NeedsReLogin(val reason: String) : SessionRefreshResult()

    /** Xiaomi security challenge during refresh. */
    data class NeedsVerification(
        val reason: String,
        val notificationUrl: String? = null,
    ) : SessionRefreshResult()

    /** Network / STS glitch — may retry later. */
    data class TransientFailure(val reason: String) : SessionRefreshResult()

    val isSuccess: Boolean get() = this is Success

    val userMessage: String
        get() = when (this) {
            Success -> L10n.text(L10n.sessionRenewed)
            is NeedsReLogin -> L10n.text(L10n.sessionExpired)
            is NeedsVerification -> L10n.text(L10n.sessionVerification)
            is TransientFailure -> L10n.text(L10n.sessionRenewalFailed)
        }
}
