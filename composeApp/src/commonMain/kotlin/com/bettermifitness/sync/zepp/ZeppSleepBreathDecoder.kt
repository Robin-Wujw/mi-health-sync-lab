package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.data.api.SleepVitalPoint
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

object ZeppSleepBreathDecoder {
    fun decode(bands: List<JsonObject>, events: List<JsonObject>, sleeps: List<HealthInboundSleepRecord>,
        diagnostic: ((Map<String, Int>) -> Unit)? = null): List<SleepVitalPoint> {
        var intervals = 0
        val sourceIntervals = bands.mapNotNull { item ->
            val encoded = (item["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 524_288 }
                ?: return@mapNotNull null
            val summary = runCatching { Json.parseToJsonElement(Base64.Default.decode(encoded).decodeToString()) as? JsonObject }
                .getOrNull() ?: return@mapNotNull null
            val sleep = summary["slp"] as? JsonObject ?: return@mapNotNull null
            val start = ZeppDecoder.epoch(sleep["st"]) ?: return@mapNotNull null
            val end = ZeppDecoder.epoch(sleep["ed"]) ?: return@mapNotNull null
            if (end <= start || end - start > 86_400) return@mapNotNull null
            intervals++
            (start to end).takeIf { interval -> sleeps.any {
                it.startEpochMillis == interval.first * 1_000 && it.endEpochMillis == interval.second * 1_000
            } }
        }.toSet()
        val latest = events.groupBy { listOf(it["timestamp"] ?: it["time"], device(it)) }
            .values.flatMap { revisions ->
                val latestRevision = revisions.maxOf(::revision)
                revisions.filter { revision(it) == latestRevision }.distinct()
            }
        val points = latest.flatMap { item ->
            val anchor = ZeppDecoder.epoch(item["timestamp"] ?: item["time"]) ?: return@flatMap emptyList()
            val value = item["value"] as? JsonObject ?: return@flatMap emptyList()
            val encoded = (value["measurements"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 4_096 }
                ?: return@flatMap emptyList()
            val bytes = runCatching { Base64.Default.decode(encoded) }.getOrNull() ?: return@flatMap emptyList()
            if (bytes.size !in 1..1_440) return@flatMap emptyList()
            bytes.toList().mapIndexedNotNull { minute, raw ->
                val reading = raw.toInt() and 255
                val time = anchor + minute * 60
                if (reading !in 4..60 || sourceIntervals.count { time >= it.first && time < it.second } != 1) null
                else SleepVitalPoint(time, reading, "zepp-respiratory:$anchor:$minute",
                    (device(item) as? JsonPrimitive)?.contentOrNull)
            }
        }.distinct()
        diagnostic?.invoke(mapOf("band_rows" to bands.size, "event_rows" to events.size,
            "band_intervals" to intervals, "matched_intervals" to sourceIntervals.size, "decoded_points" to points.size))
        return points
    }

    private fun device(item: JsonObject): JsonElement? {
        val value = item["value"] as? JsonObject
        return item["deviceId"] ?: item["deviceSource"] ?: value?.get("deviceId") ?: value?.get("deviceSource")
    }

    private fun revision(item: JsonObject): Long = ZeppDecoder.epoch(item["uploadTimestamp"]
        ?: (item["value"] as? JsonObject)?.get("lastUpdateTime")) ?: 0L
}
