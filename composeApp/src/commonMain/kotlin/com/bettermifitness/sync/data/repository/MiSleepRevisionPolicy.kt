@file:Suppress("MatchingDeclarationName")

package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MI_HEALTH_CONNECT_IMPORT_SID
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportEnrichment
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportVerificationStatus
import com.bettermifitness.sync.data.api.MiHealthImportVerifier
import com.bettermifitness.sync.data.api.MiSleepConflictOwnership
import com.bettermifitness.sync.data.api.MiSleepRevisionStatus
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Replaces one differing sleep target with the selected Zepp data. Zepp is the source of truth for
 * everything this app imports, so who last wrote the `default` row does not matter. The caller must
 * repeat the read before POST, because the row may have changed since it was assessed.
 */
internal data class PreparedSleepRevision(
    val row: MiHealthImportRow,
    val previousFingerprint: String,
    val previousZoneOffsetSeconds: Int,
) {
    fun stillMatches(observedRows: List<MiHealthImportCloudRow>): Boolean {
        val actual = uniqueUncontestedSleepTarget(row, observedRows) ?: return false
        return sleepContentFingerprint(actual) == previousFingerprint &&
            actual.zoneOffsetSeconds == previousZoneOffsetSeconds
    }
}

internal fun prepareSleepRevision(
    row: MiHealthImportRow,
    observedRows: List<MiHealthImportCloudRow>,
    sourceTimezoneReliable: Boolean,
): PreparedSleepRevision? {
    if (assessSleepRevision(row, observedRows, sourceTimezoneReliable) != MiSleepRevisionStatus.ELIGIBLE) return null
    val actual = uniqueUncontestedSleepTarget(row, observedRows) ?: return null
    // Remove obsolete optional mapped fields (especially is_uncomplete), then replace only this
    // mapper's fields. Cloud extensions remain byte-for-byte JSON values, never recreated. A sleep
    // row keeps any HRV summary until the HRV import, which runs next, recomputes it from Zepp.
    val revisedValue = JsonObject(actual.value.filterKeys { it !in replaceableFields(row) } + row.value)
    return PreparedSleepRevision(row.copy(value = revisedValue), sleepContentFingerprint(actual),
        actual.zoneOffsetSeconds ?: return null)
}

/** What still blocks a replacement: anything that is not one clear `default` row at this exact target. */
@Suppress("ComplexCondition", "ReturnCount") // One early return per refusal reason, in the order they are reported.
internal fun assessSleepRevision(
    row: MiHealthImportRow,
    observedRows: List<MiHealthImportCloudRow>,
    sourceTimezoneReliable: Boolean,
): MiSleepRevisionStatus {
    if (row.identity.channel != MiHealthImportChannel.RAW || row.identity.key != "sleep" ||
        row.identity.sid != MI_HEALTH_CONNECT_IMPORT_SID || row.identity.tag != null ||
        row.value.keys.any { it !in replaceableFields(row) }) {
        return MiSleepRevisionStatus.UNSUPPORTED_TARGET
    }
    // A sleep row maps one source session. An HRV row also lists the samples behind its summary.
    val sources = row.sourceRecordKeys
    if (sources.isEmpty() || sources.any { it.isBlank() } || (row.enrichment == null && sources.size != 1)) {
        return MiSleepRevisionStatus.SOURCE_NOT_UNIQUE
    }
    if (!sourceTimezoneReliable || row.zoneOffsetSeconds !in -64_800..64_800) {
        return MiSleepRevisionStatus.SOURCE_TIMEZONE_UNCERTAIN
    }
    val atTarget = sleepTargets(row, observedRows)
    if (atTarget.isEmpty()) return MiSleepRevisionStatus.MISSING
    if (atTarget.any { it.deleted }) return MiSleepRevisionStatus.TOMBSTONE_PRESENT
    if (atTarget.any { !it.sid.isNullOrBlank() && it.sid != row.identity.sid }) {
        return MiSleepRevisionStatus.OTHER_SOURCE_PRESENT
    }
    val actual = uniqueUncontestedSleepTarget(row, observedRows) ?: return MiSleepRevisionStatus.AMBIGUOUS
    if (actual.zoneOffsetSeconds != row.zoneOffsetSeconds) return MiSleepRevisionStatus.TIMEZONE_CHANGED
    val oldStart = validSleepStart(actual.value, row.identity.time)
    val newStart = validSleepStart(row.value, row.identity.time)
    if (oldStart == null || newStart == null || maxOf(oldStart, newStart) >= row.identity.time ||
        !REQUIRED_SLEEP_FIELDS.all { it in row.value }) return MiSleepRevisionStatus.INVALID_SLEEP_BASELINE
    if (MiHealthImportVerifier.verify(row, listOf(actual)).status == MiHealthImportVerificationStatus.VERIFIED) {
        return MiSleepRevisionStatus.ALREADY_MATCHED
    }
    return MiSleepRevisionStatus.ELIGIBLE
}

/** For diagnostics only: this tells where a cloud row came from, and no longer decides anything. */
internal fun describeSleepConflictOwnership(
    row: MiHealthImportRow,
    observedRows: List<MiHealthImportCloudRow>,
    verifiedFingerprint: String?,
    ownedFingerprint: String?,
    legacyFingerprint: String?,
): MiSleepConflictOwnership {
    val actual = uniqueUncontestedSleepTarget(row, observedRows)
        ?: return MiSleepConflictOwnership.CLOUD_TARGET_UNCERTAIN
    val current = sleepContentFingerprint(actual)
    return when {
        ownedFingerprint != null -> if (ownedFingerprint == current) MiSleepConflictOwnership.OWNED_BASELINE_UNCHANGED
            else MiSleepConflictOwnership.OWNED_BASELINE_CHANGED
        legacyFingerprint != null -> if (legacyFingerprint == current) MiSleepConflictOwnership.LEGACY_BASELINE_UNCHANGED
            else MiSleepConflictOwnership.LEGACY_BASELINE_CHANGED
        verifiedFingerprint != null -> if (verifiedFingerprint == current) MiSleepConflictOwnership.OBSERVED_BASELINE_UNCHANGED
            else MiSleepConflictOwnership.OBSERVED_BASELINE_CHANGED
        else -> MiSleepConflictOwnership.NO_HISTORY
    }
}

private fun replaceableFields(row: MiHealthImportRow): Set<String> =
    if (row.enrichment == MiHealthImportEnrichment.SLEEP_HRV) MAPPED_SLEEP_FIELDS + HRV_SUMMARY_FIELDS else MAPPED_SLEEP_FIELDS

private fun sleepTargets(row: MiHealthImportRow, observedRows: List<MiHealthImportCloudRow>) = observedRows.filter {
    it.key == row.identity.key && it.time == row.identity.time
}

private fun uniqueUncontestedSleepTarget(row: MiHealthImportRow, observedRows: List<MiHealthImportCloudRow>): MiHealthImportCloudRow? {
    val targets = sleepTargets(row, observedRows)
    if (targets.any { it.deleted || it.sid != row.identity.sid }) return null
    return targets.distinctBy { canonicalMiHealthImportJson(it.value) to it.zoneOffsetSeconds }.singleOrNull()
}

private fun validSleepStart(value: JsonObject, expectedEnd: Long): Long? {
    fun integer(field: String): Long? = (value[field] as? JsonPrimitive)?.let {
        if (it.isString) null else canonicalMiHealthImportJson(it).toLongOrNull()
    }
    val start = integer("bedtime") ?: return null
    if (start < 0 || expectedEnd <= start || integer("bed_timestamp") != start ||
        integer("wake_up_time") != expectedEnd || integer("out_bed_timestamp") != expectedEnd) return null
    return start
}

private fun sleepContentFingerprint(row: MiHealthImportCloudRow): String =
    HealthRecordIds.version(canonicalMiHealthImportJson(row.value)).toString()

private val HRV_SUMMARY_FIELDS = setOf("avg_hrv", "min_hrv", "max_hrv")
private val REQUIRED_SLEEP_FIELDS = setOf("bedtime", "wake_up_time", "bed_timestamp", "out_bed_timestamp",
    "duration", "sleep_deep_duration", "sleep_light_duration", "sleep_rem_duration", "sleep_awake_duration",
    "awake_count", "items")
private val MAPPED_SLEEP_FIELDS = REQUIRED_SLEEP_FIELDS + setOf("timezone", "is_uncomplete")
