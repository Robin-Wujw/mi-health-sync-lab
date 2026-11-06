package com.bettermifitness.sync.data.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The only alternative identity eligible for SpO2 minute readback or write-history guards. */
fun observedMinuteIdentity(row: MiHealthImportRow): MiHealthImportIdentity? = row.identity.takeIf {
    it.channel == MiHealthImportChannel.RAW && it.key == "spo2" && it.sid == MI_HEALTH_CONNECT_IMPORT_SID &&
        it.tag == null && it.time >= 0 && it.time % 60L != 0L
}?.let { it.copy(time = it.time.floorDiv(60L) * 60L) }

/** Multiple source targets must never compete for the same destination minute. */
fun spo2MinuteCollisions(sourceRows: List<MiHealthImportRow>): Set<MiHealthImportIdentity> = sourceRows
    .filter { it.identity.channel == MiHealthImportChannel.RAW && it.identity.key == "spo2" }
    .groupBy { it.identity.time.floorDiv(60) }.values
    .filter { rows -> rows.map { it.identity }.distinct().size > 1 ||
        rows.map { it.canonicalContent to it.zoneOffsetSeconds }.distinct().size > 1 }
    .flatMap { it.map { row -> row.identity } }.toSet()

/**
 * Verified on-device: native SpO2 rows can have both times floored to a minute.
 * Keep the original source identity and request time. Only accept a unique,
 * otherwise exact observation; this is not a nearest-time/value search.
 */
class MiSpO2MinuteMatchPolicy(sourceRows: List<MiHealthImportRow>) {
    val collisions = spo2MinuteCollisions(sourceRows)
    private val sources = sourceRows.filter { it.identity.channel == MiHealthImportChannel.RAW && it.identity.key == "spo2" }
        .groupBy { it.identity }

    fun hasMinuteObservation(row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>): Boolean =
        row.identity.channel == MiHealthImportChannel.RAW && row.identity.key == "spo2" && observed.any {
            it.key == "spo2" && it.time.floorDiv(60) == row.identity.time.floorDiv(60)
        }

    fun find(row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>): MiHealthImportCloudRow? {
        val id = row.identity
        if (id.channel != MiHealthImportChannel.RAW || id.key != "spo2" || id.sid != MI_HEALTH_CONNECT_IMPORT_SID ||
            id.tag != null || id.time < 0 || id.time % 60 == 0L || id in collisions ||
            !row.dayAggregationEligible || row.zoneOffsetSeconds !in -64_800..64_800 || row.enrichment != null ||
            row.sourceRecordKeys.isEmpty() || row.value.keys != setOf("time", "spo2")) return null
        val originalTime = row.value["time"] as? JsonPrimitive ?: return null
        if (originalTime.isString || originalTime.longOrNull != id.time) return null
        val spo2 = (row.value["spo2"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
        if (!spo2.isFinite() || spo2 !in 1.0..100.0 || spo2 != spo2.toInt().toDouble()) return null
        val currentSources = sources[id] ?: return null
        if (currentSources.any { it.canonicalContent != row.canonicalContent || it.zoneOffsetSeconds != row.zoneOffsetSeconds ||
                !it.dayAggregationEligible || it.sourceRecordKeys.isEmpty() || it.enrichment != null }) return null
        // Never replace an exact identity conflict with a normalized match.
        if (observed.any { it.key == id.key && it.time == id.time }) return null
        val floor = observedMinuteIdentity(row)?.time ?: return null
        val minuteRows = observed.filter { it.key == id.key && it.time >= 0 && it.time.floorDiv(60) == id.time.floorDiv(60) }.distinct()
        val actual = minuteRows.singleOrNull() ?: return null
        if (actual.deleted || actual.sid != id.sid || actual.time != floor || actual.tag != null ||
            actual.zoneOffsetSeconds == null || actual.zoneOffsetSeconds != row.zoneOffsetSeconds) return null
        val normalized = row.copy(identity = id.copy(time = floor), value = buildJsonObject {
            row.value.forEach { (key, value) -> put(key, value) }
            put("time", floor)
        })
        return actual.takeIf { MiHealthImportVerifier.verify(normalized, listOf(it)).status == MiHealthImportVerificationStatus.VERIFIED }
    }
}

fun MiHealthImportCloudRow.importIdentity(): MiHealthImportIdentity = MiHealthImportIdentity(
    MiHealthImportChannel.RAW, requireNotNull(sid), key, time, tag,
)
