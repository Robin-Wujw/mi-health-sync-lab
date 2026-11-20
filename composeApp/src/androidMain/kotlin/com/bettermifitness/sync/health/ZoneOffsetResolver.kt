package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.time.MiTimezone
import com.bettermifitness.sync.data.time.resolveOffsetSeconds
import java.time.Instant
import java.time.ZoneOffset

/**
 * Maps Mi timezone (or device offset) to Health Connect [ZoneOffset].
 *
 * Policy (issue #5 / Mi APK parity):
 * 1. Prefer valid Mi `timezone` (15-minute units) from the payload.
 * 2. Else use the HealthLab default zone at [at].
 */
object ZoneOffsetResolver {

    fun fromMiOrSystem(tzIn15Min: Int?, at: Instant): ZoneOffset {
        val mi = MiTimezone.fromPayloadOrNull(tzIn15Min)
        val fallback = DEFAULT_ZONE.rules.getOffset(at).totalSeconds
        val seconds = resolveOffsetSeconds(mi, fallbackSeconds = fallback)
        return ofTotalSecondsSafe(seconds)
    }

    /**
     * Prefer [tzIn15Min], then [gpsTzIn15Min] (workout FDS keys), then the HealthLab default zone at [at].
     */
    fun fromMiOrSystem(
        tzIn15Min: Int?,
        gpsTzIn15Min: Int?,
        at: Instant,
    ): ZoneOffset = fromMiOrSystem(tzIn15Min ?: gpsTzIn15Min, at)

    private fun ofTotalSecondsSafe(seconds: Int): ZoneOffset =
        try {
            ZoneOffset.ofTotalSeconds(
                seconds.coerceIn(MiTimezone.MIN_OFFSET_SECONDS, MiTimezone.MAX_OFFSET_SECONDS),
            )
        } catch (_: Exception) {
            ZoneOffset.UTC
        }
    private val DEFAULT_ZONE = java.time.ZoneId.of("Asia/Shanghai")
}
