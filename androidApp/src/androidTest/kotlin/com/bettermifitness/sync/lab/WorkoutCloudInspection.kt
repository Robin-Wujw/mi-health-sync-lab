package com.bettermifitness.sync.lab

import com.bettermifitness.sync.data.MiSessionManager
import com.mifitness.miclient.api.MiApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/** Schema and counts only; source/cloud values and identities stay inside the target process. */
internal suspend fun inspectWorkoutCloud(session: MiSessionManager, from: Instant, until: Instant): String {
    val rows = readWorkoutCloud(session, from.epochSeconds, until.epochSeconds)
    val values = rows.mapNotNull { row -> when (val value = row["value"]) {
        is JsonObject -> value
        is JsonPrimitive -> runCatching { Json.parseToJsonElement(value.content) as? JsonObject }.getOrNull()
        else -> null
    } }
    fun fields(objects: List<JsonObject>) = objects.flatMap { it.keys }.distinct().sorted()
        .filter { it.matches(Regex("[a-zA-Z_]{1,64}")) }.joinToString(",")
    return listOf(
        "workout_cloud.health_uploads=0", "workout_cloud.local_journal_changes=0",
        "workout_cloud.rows=${rows.size}", "workout_cloud.parsed_values=${values.size}",
        "workout_cloud.outer_fields=${fields(rows)}", "workout_cloud.value_fields=${fields(values)}",
        "workout_cloud.default_sid_rows=${rows.count { (it["sid"] as? JsonPrimitive)?.content == "default" }}",
        "workout_cloud.sport_key_rows=${rows.count { (it["key"] as? JsonPrimitive)?.content == "sport" }}",
        "workout_cloud.deleted_rows=${rows.count { (it["deleted"] as? JsonPrimitive)?.booleanOrNull == true }}",
        "workout_cloud.sport_type_3_rows=${values.count { (it["sport_type"] as? JsonPrimitive)?.intOrNull == 3 }}",
        "workout_cloud.has_start_end=${values.count { it["start_time"] != null && it["end_time"] != null }}",
        "workout_cloud.with_distance=${values.count { it["distance"] != null }}",
        "workout_cloud.outer_time_equals_start=${rows.count { row ->
            val value = when (val v = row["value"]) {
                is JsonObject -> v
                is JsonPrimitive -> runCatching { Json.parseToJsonElement(v.content) as? JsonObject }.getOrNull()
                else -> null
            }
            (row["time"] as? JsonPrimitive)?.longOrNull?.let { time ->
                time == (value?.get("start_time") as? JsonPrimitive)?.longOrNull
            } == true
        }}",
    ).joinToString("\n")
}

internal suspend fun readWorkoutCloud(session: MiSessionManager, from: Long, until: Long): List<JsonObject> {
    val rows = mutableListOf<JsonObject>()
    val tokens = mutableSetOf<String>()
    var next: String? = null
    var pages = 0
    do {
        val response = session.api.dataClient().post("/app/v1/data/get_sport_records_by_time", payload = buildMap {
            put("start_time", from)
            put("end_time", until)
            put("limit", 50)
            put("reverse", true)
            next?.let { put("next_key", it) }
        }) as? JsonObject ?: error("Invalid workout response")
        val code = (response["code"] as? JsonPrimitive)?.intOrNull ?: error("Missing workout status")
        if (code != 0) throw MiApiException.Server(code, "Workout read rejected")
        val result = response["result"] as? JsonObject ?: error("Missing workout result")
        val data = result["sport_records"] as? JsonArray ?: error("Missing workout rows")
        rows += data.map { it as? JsonObject ?: error("Invalid workout row") }
        pages++
        val more = (result["has_more"] as? JsonPrimitive)?.booleanOrNull ?: error("Missing workout pagination")
        next = if (more) (result["next_key"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: error("Missing workout cursor") else null
        check(next == null || tokens.add(next) && pages < 40)
    } while (next != null)
    return rows
}
