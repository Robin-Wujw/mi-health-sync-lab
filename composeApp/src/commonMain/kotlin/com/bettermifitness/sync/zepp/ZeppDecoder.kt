package com.bettermifitness.sync.zepp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Instant

/** Independently implemented from the protocol references in Vitalis; no captured account data. */
object ZeppDecoder {
    fun decode(stream: ZeppStream, items: List<JsonObject>, fetchedSeconds: Long): ZeppDecodeResult {
        val out = mutableListOf<ZeppObservation>()
        var invalid = 0
        val selected = if (stream !in setOf(ZeppStream.PAI, ZeppStream.EXERTION)) items else items.groupBy { item ->
            listOf(item["time"] ?: item["timestamp"], item["deviceId"] ?: item["device_id"] ?: item["deviceSource"])
        }.values.flatMap { group ->
            fun revision(item: JsonObject): Long = epoch(item["uploadTimestamp"] ?: (item["value"] as? JsonObject)
                ?.get("lastUpdateTime")) ?: 0
            val latest = group.maxOf(::revision)
            group.filter { revision(it) == latest }
        }
        for (item in selected) {
            val before = out.size
            val value = item["value"] as? JsonObject
            val observed = epoch(item["time"] ?: item["timestamp"])
            val revision = epoch(item["uploadTimestamp"] ?: value?.get("lastUpdateTime"))
            val zone = number(item["timeZone"], -48.0, 56.0)?.takeIf { it % 1 == 0.0 }?.toInt()?.times(900)
            val device = listOf("deviceId", "device_id", "deviceSource").firstNotNullOfOrNull {
                (item[it] as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() && s.length <= 256 }
            }
            fun add(metric: String, raw: JsonElement?, unit: String, field: String, time: Long?,
                min: Double = 0.0, max: Double = 10_000.0, scope: String = "vendor_stream") {
                val reading = number(raw, min, max) ?: return
                if (time == null) return
                out += ZeppObservation(metric, reading, unit, time, fetchedSeconds, revision, field,
                    scope, if (scope == "user_summary") null else device, zone,
                    if (stream == ZeppStream.EXERTION) "vendor_estimate_unvalidated" else "vendor_reported")
            }
            when (stream) {
                ZeppStream.PAI -> {
                    add("pai_daily", item["dailyPai"], "PAI", "dailyPai", observed)
                    add("pai_total", item["totalPai"], "PAI", "totalPai", observed)
                    for (band in listOf("low", "medium", "high")) {
                        add("pai_${band}_zone", item["${band}ZonePai"], "PAI", "${band}ZonePai", observed)
                    }
                }
                ZeppStream.MANUAL_STRESS -> add("single_stress", item["value"], "score", "single_stress.value",
                    observed, 1.0, 100.0)
                ZeppStream.STRESS -> {
                    val body = value ?: item
                    val average = listOf("avgStress", "averageStress", "stress").firstOrNull { body[it] != null }
                    if (average != null) add("stress_average", body[average], "score", average, observed,
                        0.0, 100.0, "user_summary")
                    val raw = body["data"]
                    val timeline = if (raw is JsonPrimitive && raw.isString) {
                        runCatching { Json.parseToJsonElement(raw.content) as? JsonArray }.getOrNull()
                    } else raw as? JsonArray
                    for (point in timeline.orEmpty().take(20_000)) {
                        val p = point as? JsonObject ?: continue
                        val field = listOf("value", "stress", "stressScore").firstOrNull { p[it] != null } ?: continue
                        add("stress", p[field], "score", "all_day_stress.data.$field", epoch(p["time"] ?: p["timestamp"]),
                            1.0, 100.0, "user_summary")
                    }
                    if (timeline != null && timeline.size > 20_000) invalid++
                }
                ZeppStream.EXERTION -> if (value != null) {
                    for (field in listOf("atl", "ctl", "tsb", "atlTotal", "ctlTotal", "tsbTotal")) {
                        add("vendor_$field", value[field], "Zepp load", field, observed,
                            if (field.startsWith("tsb")) -1e6 else 0.0, 1e6, "user_summary")
                    }
                }
            }
            if (out.size == before) invalid++
        }
        return ZeppDecodeResult(observations = out.distinct(), invalidItems = invalid)
    }

    fun workouts(items: List<JsonObject>, fetchedSeconds: Long): ZeppDecodeResult {
        var invalid = 0
        val scores = items.mapNotNull { row ->
            val id = (row["trackid"] ?: row["trackId"]) as? JsonPrimitive
            val track = id?.contentOrNull?.takeIf { it.matches(Regex("[0-9]{1,20}")) }
            val start = epoch(row["start_time"] ?: row["startTime"] ?: row["beginTime"] ?: id)
            val end = epoch(row["end_time"] ?: row["endTime"] ?: row["finishTime"])
            if (track == null || start == null || end != null && end <= start) {
                invalid++; return@mapNotNull null
            }
            val load = listOf("training_load", "trainLoad", "exercise_load").firstNotNullOfOrNull {
                number(row[it], 0.0, 1e6)
            }
            // Zepp encodes TE in tenths; the destination uses 0–5, not 0–50.
            val aerobic = number(row["te"], 0.0, 50.0)?.div(10)
            val anaerobic = number(row["anaerobic_te"], 0.0, 50.0)?.div(10)
            val source = (row["source"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) }
            ZeppWorkoutScores(start, end, track, load, aerobic, anaerobic, fetchedSeconds, source)
        }
        return ZeppDecodeResult(workouts = scores.distinct(), invalidItems = invalid)
    }

    internal fun number(raw: JsonElement?, min: Double, max: Double): Double? =
        (raw as? JsonPrimitive)?.takeUnless { it.booleanOrNull != null || it == JsonNull }
            ?.doubleOrNull?.takeIf { it.isFinite() && it in min..max }

    /** Numeric seconds/milliseconds and ISO instants only; never a local host timezone. */
    internal fun epoch(raw: JsonElement?): Long? {
        val primitive = raw as? JsonPrimitive ?: return null
        val numeric = number(primitive, 1.0, 32_503_680_000_000.0)
        if (numeric != null) {
            val seconds = if (numeric >= 100_000_000_000.0) numeric / 1000 else numeric
            return seconds.takeIf { it % 1 == 0.0 && it in 946_684_800.0..32_503_680_000.0 }?.toLong()
        }
        if (!primitive.isString) return null
        return runCatching { Instant.parse(primitive.content).takeIf { it.nanosecondsOfSecond == 0 }?.epochSeconds }.getOrNull()
            ?.takeIf { it in 946_684_800L..32_503_680_000L }
    }
}
