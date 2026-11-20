package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MI_HEALTH_CONNECT_IMPORT_SID
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportIdentity
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportVerificationStatus
import com.bettermifitness.sync.data.api.MiHealthImportVerifier
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.data.api.possibleDailyReports
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthRecordIds

internal fun couldBeDailyRevision(
    expected: MiHealthImportRow,
    sameDay: List<MiHealthImportCloudRow>,
    ownedFingerprint: String?,
    allRawVerified: Boolean,
    hasPending: Boolean,
): Boolean {
    val identity = expected.identity
    if (!expected.completeSourceDay || expected.sourceRecordKeys.isEmpty() || !allRawVerified || hasPending ||
        ownedFingerprint == null) return false
    if (identity.channel != MiHealthImportChannel.DAILY_AGGREGATE || identity.sid != MI_HEALTH_CONNECT_IMPORT_SID ||
        identity.tag != "daily_report" || identity.time % 86_400L != 0L || expected.zoneOffsetSeconds != 0) return false
    val observed = sameDay.singleOrNull() ?: return false
    val actualIdentity = MiHealthImportIdentity(identity.channel, observed.sid.orEmpty(), observed.key, observed.time, observed.tag)
    if (observed.deleted || observed.tagInferred || actualIdentity != identity ||
        observed.zoneOffsetSeconds != expected.zoneOffsetSeconds) return false
    val current = canonicalMiHealthImportJson(observed.value)
    return current != expected.canonicalContent && HealthRecordIds.version(current).toString() == ownedFingerprint
}

internal fun dailySourceVerified(expected: MiHealthImportRow, rows: List<MiHealthImportRow>,
    verifiedRaw: Set<MiHealthImportIdentity>): Boolean {
    val contributing = rows.filter { row -> row.identity.channel == MiHealthImportChannel.RAW &&
        (row.identity.time + row.zoneOffsetSeconds).floorDiv(86_400L) * 86_400L == expected.identity.time }
    return contributing.isNotEmpty() && contributing.all { it.identity in verifiedRaw } &&
        contributing.flatMap { it.sourceRecordKeys }.toSet() == expected.sourceRecordKeys
}

internal suspend fun inspectDailyReports(account: String, plan: MiHealthImportPlan, cloud: MiHealthImportCloud,
    journal: HealthImportJournal, verifiedRaw: Set<MiHealthImportIdentity>): DailyInspectionCounts {
    val days = plan.rows.filter { it.identity.channel == MiHealthImportChannel.DAILY_AGGREGATE }.groupBy { it.identity }
    if (days.isEmpty()) return DailyInspectionCounts()
    val pending = journal.pendingIdentities(account, days.keys.mapTo(mutableSetOf()) { it.stableKey })
    val owned = journal.ownedVerifiedFingerprints(account)
    var counts = DailyInspectionCounts()
    for ((identity, variants) in days) {
        val expected = variants.distinct().singleOrNull() ?: continue
        if (!expected.completeSourceDay) continue
        val reports = possibleDailyReports(expected, cloud.readRows(identity.channel, identity.key,
            (identity.time - 86_400L).coerceAtLeast(0), identity.time + 86_402L))
        val rawConfirmed = dailySourceVerified(expected, plan.rawRows, verifiedRaw)
        val hasPending = identity.stableKey in pending
        val matches = MiHealthImportVerifier.verify(expected, reports).status == MiHealthImportVerificationStatus.VERIFIED
        if (rawConfirmed && !hasPending && matches) {
            counts = counts.copy(verified = counts.verified + 1)
        } else if (reports.any { !it.deleted }) {
            val fingerprint = owned[HealthImportLedger.identityDigest(identity.stableKey)]
            val candidate = couldBeDailyRevision(expected, reports, fingerprint, rawConfirmed, hasPending)
            counts = counts.copy(preserved = counts.preserved + 1, candidates = counts.candidates + if (candidate) 1 else 0)
        }
    }
    return counts
}
