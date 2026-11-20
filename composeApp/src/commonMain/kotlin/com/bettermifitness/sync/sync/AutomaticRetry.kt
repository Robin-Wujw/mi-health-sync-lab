package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.preferences.AutoSyncTiming
import kotlinx.serialization.Serializable

data class AutomaticWorkAttempt(val owner: String, val number: Int) {
    init { require(owner.isNotBlank() && number >= 0) }
}

@Serializable
data class AutomaticRetry(
    val owner: String,
    val generation: Long,
    val notBefore: Long,
    val expiresAt: Long,
    val nextAttempt: Int = 1,
) {
    internal fun valid(at: Long, generation: Long): Boolean = this.generation == generation && nextAttempt in 1..3 &&
        expiresAt - at in 1..RETRY_LIFETIME_SECONDS
}

internal fun ownedAutomaticRetry(timing: AutoSyncTiming, owner: String, generation: Long, at: Long): AutomaticRetry? =
    timing.retry?.takeIf { it.owner == owner && it.valid(at, generation) }

internal fun resumedAutomaticAttempt(timing: AutoSyncTiming, owner: String, generation: Long, at: Long): AutomaticWorkAttempt =
    AutomaticWorkAttempt(owner, ownedAutomaticRetry(timing, owner, generation, at)?.nextAttempt ?: 0)

internal fun scheduledAutomaticDelay(timing: AutoSyncTiming, owner: String, generation: Long, at: Long): Long {
    val pause = if (timing.isPaused(at)) checkNotNull(timing.pausedUntil) - at else 0L
    val cooldown = if (timing.isInManualCooldown(at)) checkNotNull(timing.lastManualFinish) + 900L - at else 0L
    val retry = ownedAutomaticRetry(timing, owner, generation, at)
    val normal = timing.lastAutomaticStart?.let { 3_600L - (at - it) }?.takeIf { it in 1..3_600L } ?: 0L
    val due = retry?.let { minOf(it.notBefore, it.expiresAt) - at } ?: normal
    return maxOf(60L, pause, cooldown, due)
}

internal enum class AutomaticDue { NORMAL, RETRY, RETRY_DEFERRED, SKIP }

internal fun automaticDue(timing: AutoSyncTiming, at: Long, generation: Long, attempt: AutomaticWorkAttempt?): AutomaticDue {
    val pending = timing.retry?.takeIf { it.valid(at, generation) }
    if (attempt != null && attempt.number > 0) {
        if (pending?.owner != attempt.owner) return AutomaticDue.SKIP
        return if (at >= pending.notBefore && timing.canRetry(at)) AutomaticDue.RETRY else AutomaticDue.RETRY_DEFERRED
    }
    return if (pending == null && timing.canStart(at)) AutomaticDue.NORMAL else AutomaticDue.SKIP
}

internal fun nextAutomaticRetry(attempt: AutomaticWorkAttempt, generation: Long, at: Long,
    previous: AutomaticRetry?, retryable: Boolean): AutomaticRetry? {
    if (!retryable || attempt.number >= 3) return null
    val delay = 900L shl attempt.number
    val expiresAt = previous?.expiresAt ?: at + RETRY_LIFETIME_SECONDS
    return if (at + delay < expiresAt) {
        AutomaticRetry(attempt.owner, generation, at + delay, expiresAt, attempt.number + 1)
    } else null
}

private const val RETRY_LIFETIME_SECONDS = 3 * 3_600L
