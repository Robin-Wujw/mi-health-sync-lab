package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MI_HEALTH_CONNECT_IMPORT_SID
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportEnrichment
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportVerificationStatus
import com.bettermifitness.sync.data.api.MiHealthImportVerifier
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Narrow permission to add missing HRV summary fields; this is never a generic merge. */
internal data class PreparedSleepHrvEnrichment(
    val row: MiHealthImportRow,
    val previousFingerprint: String,
    val previousZoneOffsetSeconds: Int?,
    val usedLegacyPermission: Boolean,
    val replacesOwnedSummary: Boolean = false,
) {
    fun stillMatches(observedRows: List<MiHealthImportCloudRow>): Boolean {
        val actual = if (replacesOwnedSummary) uncontestedHrvSleepTarget(row, observedRows)
            else matchingSleepTarget(row, observedRows).singleOrNull()
        actual ?: return false
        return fingerprint(actual) == previousFingerprint && actual.zoneOffsetSeconds == previousZoneOffsetSeconds
    }
}

/** Recompute only this app's unchanged summary on an identical, fully encoded sleep timeline. */
internal fun prepareOwnedHrvSummaryRevision(
    row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>, ownedFingerprint: String?,
): PreparedSleepHrvEnrichment? {
    if (row.enrichment != MiHealthImportEnrichment.SLEEP_HRV || row.identity.channel != MiHealthImportChannel.RAW ||
        row.identity.key != "sleep" || row.identity.sid != MI_HEALTH_CONNECT_IMPORT_SID || row.identity.tag != null) return null
    if (row.sourceRecordKeys.isEmpty()) return null
    val actual = uncontestedHrvSleepTarget(row, observed) ?: return null
    if (ownedFingerprint == null || fingerprint(actual) != ownedFingerprint ||
        actual.zoneOffsetSeconds != row.zoneOffsetSeconds) return null
    fun validSummary(value: JsonObject): Boolean {
        val numbers = HRV_FIELDS.associateWith { field ->
            val primitive = value[field] as? JsonPrimitive ?: return false
            val number = primitive.doubleOrNull ?: return false
            if (primitive.isString || !number.isFinite() || number !in 1.0..200.0 || number != number.toInt().toDouble()) return false
            number
        }
        return numbers.getValue("avg_hrv") in numbers.getValue("min_hrv")..numbers.getValue("max_hrv")
    }
    if (!validSummary(row.value) || !validSummary(actual.value)) return null
    val base = row.copy(value = JsonObject(row.value.filterKeys { it !in HRV_FIELDS }), enrichment = null)
    if (base.value.isEmpty() ||
        MiHealthImportVerifier.verify(base, listOf(actual)).status != MiHealthImportVerificationStatus.VERIFIED) return null
    if (MiHealthImportVerifier.verify(row, listOf(actual)).status == MiHealthImportVerificationStatus.VERIFIED) return null
    return PreparedSleepHrvEnrichment(row.copy(value = JsonObject(actual.value + row.value)),
        ownedFingerprint, actual.zoneOffsetSeconds, usedLegacyPermission = false, replacesOwnedSummary = true)
}

private fun uncontestedHrvSleepTarget(row: MiHealthImportRow, observed: List<MiHealthImportCloudRow>): MiHealthImportCloudRow? {
    val targets = observed.filter { it.key == row.identity.key && it.time == row.identity.time }
    if (targets.any { it.deleted || it.sid != row.identity.sid || it.tag != null }) return null
    return targets.distinctBy { canonicalMiHealthImportJson(it.value) to it.zoneOffsetSeconds }.singleOrNull()
}

internal fun prepareSleepHrvEnrichment(
    row: MiHealthImportRow,
    observedRows: List<MiHealthImportCloudRow>,
    ownedFingerprint: String?,
    legacyFingerprint: String?,
    allowLegacy: Boolean,
): PreparedSleepHrvEnrichment? {
    if (row.enrichment != MiHealthImportEnrichment.SLEEP_HRV || row.identity.channel != MiHealthImportChannel.RAW ||
        row.identity.key != "sleep" || row.identity.sid != MI_HEALTH_CONNECT_IMPORT_SID) return null
    val actual = matchingSleepTarget(row, observedRows).singleOrNull() ?: return null
    val priorFingerprint = fingerprint(actual)
    val owned = ownedFingerprint == priorFingerprint
    val legacy = allowLegacy && legacyFingerprint == priorFingerprint
    if (!owned && !legacy) return null
    // Even a partially populated original summary is not replaced or repaired.
    // An entirely equal summary is handled by the ordinary readback/skip path.
    if (HRV_FIELDS.any { it in actual.value }) return null
    val hrv = HRV_FIELDS.associateWith { name ->
        val value = row.value[name] as? JsonPrimitive ?: return null
        val number = value.doubleOrNull ?: return null
        if (value.isString || !number.isFinite() || number <= 0 || number > Int.MAX_VALUE || number != number.toInt().toDouble()) return null
        value
    }
    val minimum = hrv.getValue("min_hrv").doubleOrNull!!
    val average = hrv.getValue("avg_hrv").doubleOrNull!!
    val maximum = hrv.getValue("max_hrv").doubleOrNull!!
    if (average !in minimum..maximum) return null
    val base = JsonObject(row.value.filterKeys { it !in HRV_FIELDS })
    if (base.isEmpty()) return null
    val baseMatch = MiHealthImportVerifier.verify(row.copy(value = base), listOf(actual))
    if (baseMatch.status != MiHealthImportVerificationStatus.VERIFIED) return null
    return PreparedSleepHrvEnrichment(
        row = row.copy(value = JsonObject(actual.value + hrv)),
        previousFingerprint = priorFingerprint,
        previousZoneOffsetSeconds = actual.zoneOffsetSeconds,
        usedLegacyPermission = !owned && legacy,
    )
}

private val HRV_FIELDS = setOf("avg_hrv", "min_hrv", "max_hrv")

private fun matchingSleepTarget(row: MiHealthImportRow, observedRows: List<MiHealthImportCloudRow>) = observedRows.filter {
    !it.deleted && it.sid == row.identity.sid && it.key == row.identity.key && it.time == row.identity.time
}

private fun fingerprint(row: MiHealthImportCloudRow): String =
    HealthRecordIds.version(canonicalMiHealthImportJson(row.value)).toString()
