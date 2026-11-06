package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MI_HEALTH_CONNECT_IMPORT_SID
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Correct an owned heart-rate point or calendar-date resting-heart-rate value. */
internal data class PreparedOwnedHeartRateRevision(
    val row: MiHealthImportRow,
    val previousFingerprint: String,
    val previousZoneOffsetSeconds: Int?,
) {
    fun stillMatches(observed: List<MiHealthImportCloudRow>): Boolean {
        val actual = uncontestedHeartRateTarget(row, observed) ?: return false
        return heartRateFingerprint(actual) == previousFingerprint && actual.zoneOffsetSeconds == previousZoneOffsetSeconds
    }
}

internal fun prepareOwnedHeartRateRevision(
    row: MiHealthImportRow,
    observed: List<MiHealthImportCloudRow>,
    ownedFingerprint: String?,
): PreparedOwnedHeartRateRevision? {
    if (row.identity.channel != MiHealthImportChannel.RAW || row.identity.key !in setOf("heart_rate", "resting_heart_rate") ||
        row.identity.sid != MI_HEALTH_CONNECT_IMPORT_SID || row.identity.tag != null || row.enrichment != null) return null
    val resting = row.identity.key == "resting_heart_rate"
    val timeField = if (resting) "date_time" else "time"
    val validZone = row.zoneOffsetSeconds in -64_800..64_800 && (!resting || row.zoneOffsetSeconds == 0)
    if (row.sourceRecordKeys.isEmpty() || row.sourceRecordKeys.any { it.isBlank() } ||
        !row.dayAggregationEligible || !validZone || row.value.keys != setOf(timeField, "bpm")) return null
    val newBpm = heartRateBpm(row.value, row.identity.time, timeField) ?: return null
    val actual = uncontestedHeartRateTarget(row, observed) ?: return null
    // RestHrItem is a UTC-coded calendar date. The cloud may omit its zero
    // offset; date_time still must equal the same aligned destination date.
    // Instantaneous heart-rate points do not receive this exception.
    val sameZone = actual.zoneOffsetSeconds == row.zoneOffsetSeconds || (resting && actual.zoneOffsetSeconds == null)
    if (!sameZone || ownedFingerprint == null ||
        heartRateFingerprint(actual) != ownedFingerprint) return null
    val previousBpm = heartRateBpm(actual.value, row.identity.time, timeField) ?: return null
    if (previousBpm == newBpm) return null
    // Preserve server extensions. Neither timestamp nor source identity changes.
    return PreparedOwnedHeartRateRevision(row.copy(value = JsonObject(actual.value + row.value)),
        ownedFingerprint, actual.zoneOffsetSeconds)
}

private fun heartRateBpm(value: JsonObject, expectedTime: Long, timeField: String): Long? {
    val time = value[timeField] as? JsonPrimitive ?: return null
    val bpm = value["bpm"] as? JsonPrimitive ?: return null
    val actualTime = time.longOrNull ?: return null
    val number = bpm.longOrNull ?: return null
    if (time.isString || expectedTime < 0 || actualTime != expectedTime || bpm.isString || number !in 1L..300L) return null
    if (timeField == "date_time" && expectedTime % 86_400L != 0L) return null
    return number
}

private fun uncontestedHeartRateTarget(
    row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>,
): MiHealthImportCloudRow? {
    val targets = observed.filter { it.key == row.identity.key && it.time == row.identity.time }
    if (targets.any { it.deleted || it.sid != row.identity.sid || it.tag != null }) return null
    return targets.distinctBy { canonicalMiHealthImportJson(it.value) to it.zoneOffsetSeconds }.singleOrNull()
}

private fun heartRateFingerprint(row: MiHealthImportCloudRow): String =
    HealthRecordIds.version(canonicalMiHealthImportJson(row.value)).toString()
