@file:Suppress("MatchingDeclarationName")

package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthInboundMetric
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Counts refer only to the cloud's HR daily_report.avg_rhr, never App display. */
data class MiRhrDisplayCheckResult(
    val matchedDays: Int,
    val missingDays: Int,
    val conflictingDays: Int,
) {
    val checkedDays: Int get() = matchedDays + missingDays + conflictingDays
    val verifiesAppDisplay: Boolean get() = false
}

/**
 * A PREPARE/source-rejection result can still have every mappable RHR row
 * verified. Only diagnose those rows when the counters prove that the whole
 * selected target set was verified; a positive partial count alone cannot
 * identify which individual days were persisted.
 */
internal fun fullyVerifiedRhrRowsForDisplay(
    plan: MiHealthImportPlan,
    diagnostic: MiHealthImportDiagnostic?,
): List<MiHealthImportRow> {
    if (plan.metric != HealthInboundMetric.RESTING_HEART_RATE ||
        diagnostic?.metricKey != "resting_heart_rate" || diagnostic.verifiedRawRows <= 0) return emptyList()
    val selected = if (diagnostic.testMode) {
        val target = plan.rawRows.maxByOrNull { it.identity.time }?.identity
        plan.rawRows.filter { it.identity == target }
    } else plan.rawRows
    val groups = selected.groupBy { it.identity }
    if (groups.values.any { same ->
            same.map { it.canonicalContent to it.zoneOffsetSeconds }.distinct().size != 1
        }) return emptyList()
    val rows = groups.values.map { it.first() }
    return rows.takeIf { it.size == diagnostic.plannedRawRows && it.size == diagnostic.verifiedRawRows }.orEmpty()
}

/**
 * Read-only diagnostic after the caller has verified [rows] as persisted RHR raw
 * data. A cloud daily report is one input to Xiaomi's chart; it does not establish
 * that the phone has downloaded, refreshed or displayed that report.
 *
 * One contiguous date range is passed to [cloud], whose transport owns paging.
 * A failure (including cancellation) propagates to the caller, which must report
 * a diagnostic warning without undoing a successful raw import. No write occurs.
 */
suspend fun checkMiRhrDisplayReports(
    cloud: MiHealthImportCloud,
    rows: List<MiHealthImportRow>,
): MiRhrDisplayCheckResult {
    if (rows.isEmpty()) return MiRhrDisplayCheckResult(0, 0, 0)
    val expectedByDay = rows.groupBy { row ->
        require(row.identity.channel == MiHealthImportChannel.RAW && row.identity.key == "resting_heart_rate") {
            "RHR display diagnostics require verified resting-heart-rate raw rows."
        }
        require(row.identity.sid.isNotBlank() && row.identity.time in 0..(Long.MAX_VALUE - 64_800L) &&
            row.zoneOffsetSeconds in -64_800..64_800)
        val bpm = (row.value["bpm"] as? JsonPrimitive)?.intOrNull
        require(bpm != null && bpm in 1..300) {
            "RHR display diagnostics require the expected source BPM."
        }
        // New rows already encode a calendar date with offset zero. This also
        // permits diagnosis of legacy instant-shaped rows without rewriting them.
        (row.identity.time + row.zoneOffsetSeconds).floorDiv(86_400L) * 86_400L
    }
    val firstDay = expectedByDay.keys.min()
    val lastDay = expectedByDay.keys.max()
    require(firstDay >= 0 && lastDay <= Long.MAX_VALUE - 86_400L)
    val reports = cloud.readRows(
        MiHealthImportChannel.DAILY_AGGREGATE, "heart_rate", firstDay, lastDay + 86_400L,
    ).filter { !it.deleted && it.key == "heart_rate" && it.tag == "daily_report" }
        .groupBy { it.time }
    var matched = 0
    var missing = 0
    var conflicting = 0
    for ((day, expectedRows) in expectedByDay) {
        val sources = expectedRows.map { it.identity.sid }.distinct()
        val expectedValues = expectedRows.map { (it.value.getValue("bpm") as JsonPrimitive).intOrNull!! }.distinct()
        if (sources.size != 1 || expectedValues.size != 1) {
            conflicting++
            continue
        }
        val atDay = reports[day].orEmpty()
        val sameSource = atDay.filter { it.sid == sources.single() }
        if (sameSource.isEmpty()) {
            // An anonymous source cannot prove this is the imported record's
            // report, even if its numeric value happens to match.
            if (atDay.any { it.sid.isNullOrBlank() }) conflicting++ else missing++
            continue
        }
        val values = sameSource.map { it.value["avg_rhr"] }.distinct()
        val parsed = values.map { (it as? JsonPrimitive)?.doubleOrNull }
        when {
            sameSource.any { it.zoneOffsetSeconds != null && it.zoneOffsetSeconds != 0 } -> conflicting++
            values.all { it == null || it == JsonNull } -> missing++
            // Duplicate reports must agree; one correct copy cannot conceal a
            // different or incomplete report returned for the same target day.
            parsed.all { it != null && it.isFinite() && it == expectedValues.single().toDouble() } -> matched++
            else -> conflicting++
        }
    }
    return MiRhrDisplayCheckResult(matched, missing, conflicting)
}
