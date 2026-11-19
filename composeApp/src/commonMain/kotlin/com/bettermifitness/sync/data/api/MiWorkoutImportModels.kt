package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthRecordIds
import com.bettermifitness.sync.health.InboundWorkout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

@Serializable
data class MiWorkoutImportTarget(
    val sourceKey: String,
    val sourceRevision: String,
    val key: String,
    val category: String,
    val start: Long,
    val end: Long,
    val zoneOffsetSeconds: Int,
    val value: JsonObject,
    val sid: String = "default",
) {
    val identity: String get() = "WORKOUT|$sid|$key|$start"
    val fingerprint: String get() = HealthRecordIds.version(identity, zoneOffsetSeconds,
        canonicalMiHealthImportJson(value)).toString()
    fun row(valueOverride: JsonObject = value): JsonObject = buildJsonObject {
        put("sid", sid); put("key", key); put("category", category); put("time", start)
        put("zone_offset", zoneOffsetSeconds); put("deleted", false); put("value", valueOverride.toString())
    }
    fun matches(row: MiWorkoutExperimentCloudRow): Boolean = !row.deleted && row.sid == sid &&
        row.key == key && row.category == category && row.time == start && row.zoneOffset == zoneOffsetSeconds &&
        value.filterKeys { it != "version" && !(it == "proto_type" && compatibleFileProtocol(row)) }.all { (key, expected) ->
            val actual = row.value[key] as? JsonPrimitive
            val numeric = (expected as? JsonPrimitive)?.doubleOrNull
            if (numeric != null) actual != null && !actual.isString && actual.doubleOrNull == numeric else row.value[key] == expected
        }
    private fun compatibleFileProtocol(row: MiWorkoutExperimentCloudRow): Boolean {
        val manual = row.value["did"] in setOf(JsonPrimitive("default"), JsonPrimitive("xiaomiwear_app_manually"))
        // File protocol selects a binary decoder, independently of the exercise key/sport_type.
        // Outdoor foot protocols 1 and 2 use the identical native record/GPS layout.
        return manual && (row.value["proto_type"] == JsonPrimitive(8) ||
            key == "outdoor_running" && row.value["did"] == JsonPrimitive("xiaomiwear_app_manually") &&
            row.value["proto_type"] == JsonPrimitive(2))
    }
    fun overlaps(row: MiWorkoutExperimentCloudRow): Boolean = row.time == start || run {
        val s = (row.value["start_time"] as? JsonPrimitive)?.content?.toLongOrNull()
        val e = (row.value["end_time"] as? JsonPrimitive)?.content?.toLongOrNull()
        s != null && e != null && s < end && e > start
    }
    fun sameIdentity(row: MiWorkoutExperimentCloudRow) = row.sid == sid && row.key == key && row.category == category &&
        row.time == start && row.zoneOffset == zoneOffsetSeconds && !row.deleted
}

object MiWorkoutImportMapper {
    private data class Type(val number: Int, val category: String)
    private val types = mapOf(
        "outdoor_running" to Type(1, "running"), "outdoor_walking" to Type(2, "walking"),
        "indoor_running" to Type(3, "running"), "outdoor_riding" to Type(6, "outdoor_riding"),
        "outdoor_hiking" to Type(15, "outdoor_hiking"),
        "indoor_riding" to Type(7, "indoor_riding"), "free_training" to Type(8, "free_training"),
        "pool_swimming" to Type(9, "swimming"), "open_swimming" to Type(10, "swimming"),
        "elliptical_trainer" to Type(11, "elliptical_trainer"), "yoga" to Type(12, "yoga"),
        "rowing_machine" to Type(13, "rowing_machine"), "high_interval_training" to Type(16, "high_interval_training"),
        "strength_training" to Type(308, "strength_training"), "weight_lifting" to Type(315, "weight_lifting"),
        "badminton" to Type(608, "badminton"),
    )

    fun map(source: InboundWorkout): MiWorkoutImportTarget? {
        val s = source.session
        val type = types[s.activityType] ?: return null
        val tz = s.tzIn15Min ?: return null
        val invalidTime = s.endTime <= s.startTime || s.endTime - s.startTime > 86_400
        if (tz !in -48..56 || invalidTime ||
            source.activeDurationSeconds !in 1..(s.endTime - s.startTime) ||
            "timezone" in source.omittedMetricFamilies || "subsecond_session" in source.omittedMetricFamilies) return null
        val value = buildJsonObject {
            put("start_time", s.startTime); put("end_time", s.endTime); put("time", s.startTime)
            put("duration", s.endTime - s.startTime); put("valid_duration", source.activeDurationSeconds)
            put("timezone", tz); put("sport_type", type.number)
            put("proto_type", if (type.number > 31) 8 else type.number); put("version", 0)
            s.distanceMeters?.takeIf { it.isFinite() && it in 0.0..200_000.0 }?.let { put("distance", it.roundToInt()) }
            s.caloriesKcal?.takeIf { it.isFinite() && it in 0.0..20_000.0 }?.let { put("calories", it.roundToInt()) }
            val hr = s.heartRateSeries.map { it.value }.filter { it.isFinite() && it in 1.0..255.0 }
            if (hr.isNotEmpty()) {
                put("avg_hrm", hr.average().roundToInt()); put("min_hrm", hr.min().roundToInt()); put("max_hrm", hr.max().roundToInt())
            }
            val cadence = s.cadenceSeries.map { it.value }.filter { it.isFinite() && it in 0.0..300.0 }
            if (cadence.isNotEmpty()) {
                put("avg_cadence", cadence.average().roundToInt()); put("max_cadence", cadence.max().roundToInt())
            }
            val power = s.powerWattsSeries.map { it.value }.filter { it.isFinite() && it in 0.0..3000.0 }
            if (power.isNotEmpty()) {
                put("avg_power", power.average().roundToInt()); put("max_power", power.max().roundToInt())
            }
            s.elevationGainM?.takeIf { it.isFinite() && it >= 0 }?.let { put("rise_height", it) }
        }
        return MiWorkoutImportTarget(source.origin.stableRecordKey, source.origin.lastModifiedEpochMillis.toString(),
            s.activityType, type.category, s.startTime, s.endTime, tz * 900, value, sid = "xiaomiwear_app_manually")
    }

    fun payload(target: MiWorkoutImportTarget, phoneId: String, value: JsonObject = target.value): JsonObject {
        require(phoneId.isNotBlank() && phoneId != "xiaomiwear_app_manually")
        return buildJsonObject { put("phone_id", phoneId); put("sport_records", JsonArray(listOf(target.row(value)))) }
    }
}
