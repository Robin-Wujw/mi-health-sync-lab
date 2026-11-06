package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MI_HEALTH_CONNECT_IMPORT_SID
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportVerificationStatus
import com.bettermifitness.sync.data.api.MiHealthImportVerifier
import com.bettermifitness.sync.data.api.MiOwnedSleepRevisionStatus
import com.bettermifitness.sync.data.api.MiSleepConflictOwnership
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Explicit replacement of one still-owned sleep target, not proof that HC revised the same record.
 * Older ledgers did not retain verified source provenance. They must never silently authorize this
 * replacement. The caller must require the user's separate opt-in and repeat the read before POST.
 */
internal data class PreparedOwnedSleepRevision(
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

internal fun prepareOwnedSleepRevision(
    row: MiHealthImportRow,
    observedRows: List<MiHealthImportCloudRow>,
    ownedFingerprint: String?,
    userRequested: Boolean,
    sourceTimezoneReliable: Boolean,
): PreparedOwnedSleepRevision? {
    if (!userRequested || assessOwnedSleepRevision(row, observedRows, ownedFingerprint,
            sourceTimezoneReliable) != MiOwnedSleepRevisionStatus.ELIGIBLE) return null
    val actual = uniqueUncontestedSleepTarget(row, observedRows) ?: return null
    // Remove obsolete optional mapped fields (especially is_uncomplete), then replace only this
    // mapper's sleep fields. Cloud extensions remain byte-for-byte JSON values, never recreated.
    val revisedValue = JsonObject(actual.value.filterKeys { it !in MAPPED_SLEEP_FIELDS } + row.value)
    return PreparedOwnedSleepRevision(row.copy(value = revisedValue), sleepContentFingerprint(actual),
        actual.zoneOffsetSeconds ?: return null)
}

/** A source-zone warning cannot be inferred from dayAggregationEligible, which also covers gaps. */
internal fun assessOwnedSleepRevision(
    row: MiHealthImportRow,
    observedRows: List<MiHealthImportCloudRow>,
    ownedFingerprint: String?,
    sourceTimezoneReliable: Boolean,
): MiOwnedSleepRevisionStatus {
    if (row.identity.channel != MiHealthImportChannel.RAW || row.identity.key != "sleep" ||
        row.identity.sid != MI_HEALTH_CONNECT_IMPORT_SID || row.identity.tag != null ||
        row.enrichment != null || row.value.keys.any { it !in MAPPED_SLEEP_FIELDS }) {
        return MiOwnedSleepRevisionStatus.UNSUPPORTED_TARGET
    }
    if (row.sourceRecordKeys.size != 1 || row.sourceRecordKeys.single().isBlank()) {
        return MiOwnedSleepRevisionStatus.SOURCE_NOT_UNIQUE
    }
    if (!sourceTimezoneReliable || row.zoneOffsetSeconds !in -64_800..64_800) {
        return MiOwnedSleepRevisionStatus.SOURCE_TIMEZONE_UNCERTAIN
    }
    val atTarget = sleepTargets(row, observedRows)
    if (atTarget.isEmpty()) return MiOwnedSleepRevisionStatus.MISSING
    if (atTarget.any { it.deleted }) return MiOwnedSleepRevisionStatus.TOMBSTONE_PRESENT
    if (atTarget.any { !it.sid.isNullOrBlank() && it.sid != row.identity.sid }) {
        return MiOwnedSleepRevisionStatus.OTHER_SOURCE_PRESENT
    }
    val actual = uniqueUncontestedSleepTarget(row, observedRows) ?: return MiOwnedSleepRevisionStatus.AMBIGUOUS
    if (ownedFingerprint == null) return MiOwnedSleepRevisionStatus.NOT_OWNED
    if (ownedFingerprint != sleepContentFingerprint(actual)) return MiOwnedSleepRevisionStatus.CLOUD_BASELINE_CHANGED
    if (actual.zoneOffsetSeconds != row.zoneOffsetSeconds) return MiOwnedSleepRevisionStatus.TIMEZONE_CHANGED
    val oldStart = validSleepStart(actual.value, row.identity.time)
    val newStart = validSleepStart(row.value, row.identity.time)
    if (oldStart == null || newStart == null || maxOf(oldStart, newStart) >= row.identity.time ||
        !REQUIRED_SLEEP_FIELDS.all { it in row.value }) return MiOwnedSleepRevisionStatus.INVALID_SLEEP_BASELINE
    if (MiHealthImportVerifier.verify(row, listOf(actual)).status == MiHealthImportVerificationStatus.VERIFIED) {
        return MiOwnedSleepRevisionStatus.ALREADY_MATCHED
    }
    // An existing summary belongs to the old sleep timeline. Keeping it on changed stages/bounds
    // would imply a new unsupported association; deleting it would lose user data.
    if (HRV_SUMMARY_FIELDS.any { it in actual.value }) return MiOwnedSleepRevisionStatus.HRV_PRESENT
    return MiOwnedSleepRevisionStatus.ELIGIBLE
}

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
