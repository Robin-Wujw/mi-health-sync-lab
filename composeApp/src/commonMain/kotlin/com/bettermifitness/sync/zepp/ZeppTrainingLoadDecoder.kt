package com.bettermifitness.sync.zepp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.datetime.LocalDate

object ZeppTrainingLoadDecoder {
    /** Exact Huami SPORT_LOAD contract used by Mi Fitness 3.59.2 HmTrainingLoadParse. */
    fun decode(items: List<JsonObject>, fetchedSeconds: Long): ZeppDecodeResult {
        val out = mutableListOf<ZeppObservation>()
        var invalid = 0
        for (item in items) {
            val body = item["value"] as? JsonObject ?: item
            val rawDay = (body["dayId"] as? JsonPrimitive)?.contentOrNull
            val day = rawDay?.let { runCatching { LocalDate.parse(it).toEpochDays() }.getOrNull() }?.toLong()
                ?: ZeppDecoder.number(body["dayId"], 10_957.0, 100_000.0)?.takeIf { it % 1 == 0.0 }?.toLong()
            if (day == null) { invalid++; continue }
            val revision = ZeppDecoder.epoch(body["updateTime"] ?: item["timestamp"])
            for ((metric, field) in listOf("weekly_load" to "weeklyTLSum", "daily_load" to "currentDayTL",
                "weekly_load_min" to "weeklyTLSumOptimalMin", "weekly_load_max" to "weeklyTLSumOptimalMax",
                "weekly_load_overreaching" to "weeklyTLSumOverreaching")) {
                val sourceField = if (body[field] != null) field else when (field) {
                    "weeklyTLSum" -> "wtlSum"
                    "currentDayTL" -> "currnetDayTrainLoad"
                    "weeklyTLSumOptimalMin" -> "wtlSumOptimalMin"
                    "weeklyTLSumOptimalMax" -> "wtlSumOptimalMax"
                    "weeklyTLSumOverreaching" -> "wtlSumOverreaching"
                    else -> field
                }
                val reading = ZeppDecoder.number(body[sourceField], 0.0, 1e6)?.takeIf { it % 1 == 0.0 } ?: continue
                out += ZeppObservation(metric, reading, "Huami load", day * 86_400, fetchedSeconds,
                    revision, sourceField, "user_summary", null, 0)
            }
        }
        return ZeppDecodeResult(observations = out, invalidItems = invalid)
    }
}
