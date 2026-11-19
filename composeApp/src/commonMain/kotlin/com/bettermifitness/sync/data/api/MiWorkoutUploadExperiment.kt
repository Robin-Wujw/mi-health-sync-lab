package com.bettermifitness.sync.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One real treadmill summary. The experiment never infers outdoor sport or writes scores/files. */
@Serializable
data class MiWorkoutExperimentTarget(
    val sourceId: String,
    val sourceRevision: String,
    val start: Long,
    val end: Long,
    val zoneOffsetSeconds: Int,
    val distanceMeters: Int? = null,
    val activeCaloriesKcal: Int? = null,
) {
    init {
        require(sourceId.isNotBlank() && !sourceId.startsWith("mifit-"))
        require(sourceRevision.isNotBlank())
        require(start > 0 && end > start && end - start <= 86_400)
        require(zoneOffsetSeconds in -43_200..50_400 && zoneOffsetSeconds % 900 == 0)
        require(distanceMeters == null || distanceMeters in 0..200_000)
        require(activeCaloriesKcal == null || activeCaloriesKcal in 0..20_000)
    }

    fun value(): JsonObject = buildJsonObject {
        put("start_time", start)
        put("end_time", end)
        put("time", start)
        put("duration", end - start)
        put("timezone", zoneOffsetSeconds / 900)
        put("sport_type", 3)
        put("proto_type", 3)
        put("version", 0) // No FDS record/GPS/recovery files exist for this summary.
        distanceMeters?.let { put("distance", it) }
        activeCaloriesKcal?.let { put("calories", it) }
    }

    fun row(): JsonObject = buildJsonObject {
        put("sid", "default")
        put("key", "indoor_running")
        put("category", "running")
        put("time", start)
        put("zone_offset", zoneOffsetSeconds)
        put("deleted", false)
        put("value", value().toString())
    }

    fun payload(phoneId: String): JsonObject {
        require(phoneId.isNotBlank() && phoneId != "xiaomiwear_app_manually")
        return buildJsonObject {
            put("phone_id", phoneId)
            put("sport_records", JsonArray(listOf(row())))
        }
    }
}

data class MiWorkoutExperimentCloudRow(
    val sid: String,
    val key: String,
    val category: String,
    val time: Long,
    val zoneOffset: Int?,
    val deleted: Boolean,
    val value: JsonObject,
) {
    fun matches(target: MiWorkoutExperimentTarget): Boolean =
        !deleted && sid == "default" && key == "indoor_running" && category == "running" && time == target.start &&
            zoneOffset == target.zoneOffsetSeconds && target.value().all { (key, expected) ->
                val actual = value[key] as? JsonPrimitive
                val number = (expected as? JsonPrimitive)?.doubleOrNull
                number != null && actual != null && !actual.isString && actual.doubleOrNull == number
            }

    fun overlaps(target: MiWorkoutExperimentTarget): Boolean {
        val start = (value["start_time"] as? JsonPrimitive)?.longOrNull
        val end = (value["end_time"] as? JsonPrimitive)?.longOrNull
        return time == target.start || (start != null && end != null && start < target.end && end > target.start)
    }
}

fun parseMiWorkoutExperimentRow(row: JsonObject): MiWorkoutExperimentCloudRow {
    fun string(key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("Missing workout identity field")
    val value = when (val raw = row["value"]) {
        is JsonObject -> raw
        is JsonPrimitive -> {
            check(raw.isString)
            Json.parseToJsonElement(raw.content) as? JsonObject ?: error("Invalid workout value")
        }
        else -> error("Missing workout value")
    }
    val deleted = when (val raw = row["deleted"]) {
        null -> false // Native SportReportModel omits its nullable deleted field on ordinary rows.
        is JsonPrimitive -> raw.takeUnless { it.isString }?.booleanOrNull ?: error("Invalid deletion status")
        else -> error("Invalid deletion status")
    }
    val start = (value["start_time"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?: error("Missing workout start")
    val end = (value["end_time"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?: error("Missing workout end")
    check(start > 0 && end > start) // An unreadable interval cannot establish absence of overlap.
    return MiWorkoutExperimentCloudRow(string("sid"), string("key"), string("category"),
        (row["time"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("Invalid workout time"),
        (row["zone_offset"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull, deleted, value)
}

@Serializable
data class MiWorkoutExperimentReservation(val target: MiWorkoutExperimentTarget, val verified: Boolean = false)

/** Account-scoped, durable, single-slot reservation. reserve must atomically refuse any existing slot. */
interface MiWorkoutExperimentJournal {
    suspend fun load(): MiWorkoutExperimentReservation?
    suspend fun reserve(target: MiWorkoutExperimentTarget): Boolean
    suspend fun verified(target: MiWorkoutExperimentTarget)
}

interface MiWorkoutExperimentCloud {
    suspend fun read(from: Long, until: Long): List<MiWorkoutExperimentCloudRow>
    /** Exactly one network attempt, with HTTP body replay disabled. */
    suspend fun upload(target: MiWorkoutExperimentTarget): JsonElement
}

data class MiWorkoutExperimentResult(
    val outcome: String,
    val attemptedUploads: Int = 0,
    val matchedRows: Int = 0,
    val businessCode: Int? = null,
    val accepted: Boolean? = null,
) {
    fun report(): String = listOf(
        "workout_upload.outcome=$outcome", "workout_upload.attempted_uploads=$attemptedUploads",
        "workout_upload.matched_rows=$matchedRows", "workout_upload.business_code=${businessCode ?: "UNKNOWN"}",
        "workout_upload.accepted=${accepted ?: "UNKNOWN"}", "workout_upload.max_uploads=1",
        "workout_upload.gps_writes=0", "workout_upload.score_writes=0", "workout_upload.daily_summary_writes=0",
    ).joinToString("\n")
}

/** Explicit, single-slot experiment; repeated invocations cannot advance to another source workout. */
class MiWorkoutUploadExperiment(
    private val cloud: MiWorkoutExperimentCloud,
    private val journal: MiWorkoutExperimentJournal,
) {
    @Suppress("ReturnCount") // Each preflight guard has a distinct, non-retrying diagnostic outcome.
    suspend fun run(source: List<MiWorkoutExperimentTarget>, allowWrite: Boolean): MiWorkoutExperimentResult {
        val reservation = journal.load()
        val target = if (reservation != null) {
            source.singleOrNull { it.sourceId == reservation.target.sourceId }
                ?: return MiWorkoutExperimentResult("PINNED_SOURCE_MISSING_OR_AMBIGUOUS")
        } else source.maxByOrNull { it.start } ?: return MiWorkoutExperimentResult("NO_ELIGIBLE_TREADMILL")
        if (reservation != null && reservation.target != target) return MiWorkoutExperimentResult("PINNED_SOURCE_CHANGED")
        if (source.count { it.start < target.end && it.end > target.start } != 1)
            return MiWorkoutExperimentResult("SOURCE_OVERLAP_OR_COLLISION")
        val before = cloud.read(target.start - 86_400, target.end + 86_400)
        val overlaps = before.filter { it.overlaps(target) }
        if (overlaps.size == 1 && overlaps.single().matches(target)) {
            if (allowWrite) {
                if (reservation == null && !journal.reserve(target)) return MiWorkoutExperimentResult("RESERVATION_CHANGED")
                journal.verified(target)
            }
            return MiWorkoutExperimentResult("ALREADY_PRESENT_VERIFIED", matchedRows = 1)
        }
        if (overlaps.isNotEmpty()) return MiWorkoutExperimentResult("CLOUD_CONFLICT")
        if (reservation != null) return MiWorkoutExperimentResult(
            if (reservation.verified) "VERIFIED_HISTORY_MISSING" else "PENDING_NOT_RETRIED")
        if (!allowWrite) return MiWorkoutExperimentResult("PREVIEW_READY")
        // Check immediately before the durable reservation. Never revise an existing cloud workout.
        val lastRead = cloud.read(target.start - 86_400, target.end + 86_400)
        if (lastRead.any { it.overlaps(target) }) return MiWorkoutExperimentResult("CLOUD_CHANGED_BEFORE_WRITE")
        if (!journal.reserve(target)) return MiWorkoutExperimentResult("RESERVATION_CHANGED")
        val response = try {
            cloud.upload(target)
        } catch (cancelled: CancellationException) {
            throw cancelled // The committed reservation survives cancellation and process death.
        } catch (_: Exception) {
            null
        }
        val obj = response as? JsonObject
        val code = (obj?.get("code") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val result = obj?.get("result")
        val acceptedValue = (result as? JsonObject)?.get("result") ?: result
        val accepted = (acceptedValue as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val after = try {
            cloud.read(target.start - 86_400, target.end + 86_400)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MiWorkoutExperimentResult("READBACK_FAILED_PENDING", 1, businessCode = code, accepted = accepted)
        }
        val found = after.filter { it.overlaps(target) }
        if (found.size == 1 && found.single().matches(target)) {
            journal.verified(target)
            return MiWorkoutExperimentResult("UPLOADED_READBACK_VERIFIED", 1, 1, code, accepted)
        }
        return MiWorkoutExperimentResult("UNVERIFIED_PENDING", 1, businessCode = code, accepted = accepted)
    }
}
