package com.bettermifitness.sync.data.parse

import com.bettermifitness.sync.data.api.SleepSession
import com.bettermifitness.sync.data.api.SleepStage
import com.bettermifitness.sync.data.time.MiTimezone
import com.bettermifitness.sync.health.HealthDataNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Mi daily sleep reports can contain a night and several naps. Only reported
 * timestamps define their intervals: daily anchors and aggregate durations are
 * not enough to reconstruct when a person was asleep or awake.
 */
internal object MiSleepParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(entry: RawFitnessEntry): List<SleepSession> {
        val root = try {
            json.parseToJsonElement(entry.value) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val timezone = root.timezone()
        val segments = root["segment_details"] as? JsonArray
        return if (segments.isNullOrEmpty()) {
            listOfNotNull(parseSegment(root, timezone))
        } else {
            // One malformed segment must not discard the other sleeps that day.
            segments.mapNotNull { (it as? JsonObject)?.let { segment -> parseSegment(segment, timezone) } }
        }
    }

    private fun parseSegment(obj: JsonObject, fallbackTimezone: Int?): SleepSession? {
        val stages = (obj["items"] as? JsonArray).orEmpty().mapNotNull { item ->
            val stage = item as? JsonObject ?: return@mapNotNull null
            val start = stage.epoch("start_time") ?: return@mapNotNull null
            val end = stage.epoch("end_time") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            SleepStage(start, end, stage.int("state") ?: 1)
        }.sortedBy { it.startTime }

        val reportedStart = obj.epoch("bedtime")
        val reportedEnd = obj.epoch("wake_up_time")
        val observedStart = stages.minOfOrNull { it.startTime }
        val observedEnd = stages.maxOfOrNull { it.endTime }

        val start: Long
        val end: Long
        if (reportedStart != null && reportedEnd != null && reportedEnd > reportedStart) {
            start = reportedStart
            end = reportedEnd
        } else {
            // Recover missing/invalid anchors only from actual stage intervals.
            // Do not use entry.time (a day/report anchor), duration, or awake totals.
            if (observedStart == null || observedEnd == null) return null
            start = reportedStart?.takeIf { it < observedEnd } ?: observedStart
            end = reportedEnd?.takeIf { it > start } ?: observedEnd
            if (end <= start) return null
        }

        return SleepSession(
            startTime = start,
            endTime = end,
            inBedStart = obj.epoch("bed_timestamp") ?: start,
            inBedEnd = obj.epoch("out_bed_timestamp") ?: end,
            // Missing ranges remain missing. Health Connect explicitly permits
            // discontinuous stages; aggregate awake minutes have no timestamps.
            stages = stages,
            avgHrvMs = obj.int("avg_hrv") ?: obj.int("avgHrv"),
            minHrvMs = obj.int("min_hrv") ?: obj.int("minHrv"),
            maxHrvMs = obj.int("max_hrv") ?: obj.int("maxHrv"),
            hrvAnalysisTimeSec = obj.epoch("hrv_analysis_timestamp")
                ?: obj.epoch("hrvAnalysisTimestamp"),
            tzIn15Min = obj.timezone() ?: fallbackTimezone,
        )
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.epoch(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
            ?.let(HealthDataNormalizer::toEpochSeconds)
            ?.takeIf(HealthDataNormalizer::isPlausibleEpochSeconds)

    private fun JsonObject.timezone(): Int? =
        MiTimezone.fromPayloadOrNull(int("timezone"))?.unitsOf15Min
}
