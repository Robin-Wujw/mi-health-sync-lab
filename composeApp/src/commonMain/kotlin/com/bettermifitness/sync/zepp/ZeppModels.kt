package com.bettermifitness.sync.zepp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A Zepp session is independent of both Xiaomi login and Health Connect. Never includes a password. */
@Serializable
class ZeppSession(val userId: String, val appToken: String, val loginToken: String, val host: String) {
    init {
        require(userId.matches(Regex("[0-9]{1,32}")))
        require(appToken.isNotBlank())
        require(appToken.length <= 16_384 && loginToken.length <= 16_384)
        require(host == zeppHost(host))
    }
    override fun toString(): String = "ZeppSession(redacted)"
}

internal fun zeppHost(value: String): String {
    val host = value.trim().lowercase().removePrefix("https://").removeSuffix("/")
    require(Regex("api-mifit[a-z0-9-]*\\.(zepp|huami)\\.com").matches(host)) { "Invalid Zepp region" }
    return host
}

enum class ZeppStream(val eventType: String, val subType: String? = null, val userSurface: Boolean = true) {
    PAI("PaiHealthInfo"), STRESS("all_day_stress"), MANUAL_STRESS("single_stress"),
    EXERTION("exertion", "algo_result", false),
}

enum class ZeppSyncMetric(val key: String, val streams: Set<ZeppStream>) {
    PAI("pai", setOf(ZeppStream.PAI)),
    STRESS("stress", setOf(ZeppStream.STRESS, ZeppStream.MANUAL_STRESS)),
    TRAINING_LOAD("training_load", setOf(ZeppStream.EXERTION)),
    WORKOUT_SCORES("workout_scores", emptySet()),
    WORKOUT_HEART_RATE("workout_heart_rate", emptySet());

    companion object {
        val defaultSelection: Set<ZeppSyncMetric> = entries.filterNot { it == WORKOUT_HEART_RATE }.toSet()
    }
}

enum class ZeppFailure { AUTH, CAPTCHA, RATE_LIMIT, UNAVAILABLE, NETWORK, RESPONSE, INCOMPLETE }

/** Only a stable code crosses the network boundary; bodies/URLs/tokens never become UI errors. */
class ZeppException(val failure: ZeppFailure) : Exception("Zepp: ${failure.name}")

/** A value retains vendor semantics. In particular ATL is not weeklyTLSum, and PAI is not vitality. */
data class ZeppObservation(
    val metric: String,
    val value: Double,
    val unit: String,
    val observedSeconds: Long,
    val fetchedSeconds: Long,
    val revisionSeconds: Long?,
    val sourceField: String,
    val sourceScope: String,
    val deviceId: String?,
    val zoneOffsetSeconds: Int?,
    val qualification: String = "vendor_reported",
)

data class ZeppWorkoutScores(
    val startSeconds: Long,
    val endSeconds: Long?,
    val trackId: String,
    val load: Double?,
    val aerobicEffect: Double?,
    val anaerobicEffect: Double?,
    val fetchedSeconds: Long,
    val source: String? = null,
)

data class ZeppDecodeResult(
    val observations: List<ZeppObservation> = emptyList(),
    val workouts: List<ZeppWorkoutScores> = emptyList(),
    val invalidItems: Int = 0,
)

data class ZeppSourceSnapshot(
    val observations: List<ZeppObservation>,
    val workouts: List<ZeppWorkoutScores>,
    val unavailable: Set<String>,
    val incomplete: Set<String>,
    val invalidItems: Int,
)

/** Raw source payloads remain in memory only. */
internal data class ZeppPage(val items: List<JsonObject>, val full: Boolean)
