package com.bettermifitness.sync.lab

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.repository.MiHealthImportRunner
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Read-only device evidence. Neither the source nor cloud payload is exported. */
internal suspend fun inspectSpO2Minute(
    session: MiSessionManager, store: HealthStore, ledger: HealthImportLedger,
    from: Instant, until: Instant,
): String {
    val source = "com.huami.watch.hmwatchmanager"
    val request = HealthInboundReadRequest(from.epochSeconds.floorDiv(60) * 60, until.epochSeconds, setOf(source))
    val plan = MiHealthImportMapper.oxygenSaturation(store.readOxygenSaturation(request), source)
    val account = checkNotNull(session.currentUserId)
    val snapshots = ledger.pendingSnapshots(account, plan.rawRows.map { it.identity.stableKey }.toSet())
    val targets = plan.rawRows.filter { it.identity.stableKey in snapshots }
    if (targets.isEmpty()) return "minute_inspection.pending_targets=0\nminute_inspection.health_uploads=0"
    val history = MiDirectHealthImportCloud(session.api).readRawRowsWithoutStart("spo2",
        plan.rawRows.maxOf { it.identity.time } + 1, maxPages = 32)
    val byMinute = history.rows.filter { it.key == "spo2" }.distinct().groupBy { it.time.floorDiv(60) }
    val sourceByMinute = plan.rawRows.groupBy { it.identity.time.floorDiv(60) }
    val counts = linkedMapOf<String, Int>()
    fun count(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
    for (row in targets) {
        if (!snapshots.getValue(row.identity.stableKey).matches(MiHealthImportRunner.journalEntry(row))) {
            count("source_changed_targets"); continue
        }
        if (row.identity.time % 60 == 0L) { count("already_minute_aligned_targets"); continue }
        val minute = row.identity.time.floorDiv(60)
        val observed = byMinute[minute].orEmpty()
        if (sourceByMinute.getValue(minute).map { it.identity }.distinct().size != 1) {
            count("source_minute_collision_targets"); continue
        }
        if (observed.size != 1) { count("nonunique_or_absent_minute_targets"); continue }
        val actual = observed.single()
        if (actual.deleted || actual.sid != row.identity.sid) { count("source_or_deleted_conflict_targets"); continue }
        if (actual.time != minute * 60) { count("outer_not_minute_floor_targets"); continue }
        if (actual.zoneOffsetSeconds == null || actual.zoneOffsetSeconds != row.zoneOffsetSeconds) {
            count("offset_unknown_or_different_targets"); continue
        }
        val expected = row.copy(identity = row.identity.copy(time = minute * 60), value = buildJsonObject {
            row.value.forEach { (key, value) -> put(key, value) }
            put("time", minute * 60)
        })
        if (MiHealthImportVerifier.verify(expected, listOf(actual)).status != MiHealthImportVerificationStatus.VERIFIED) {
            count("normalized_content_mismatch_targets"); continue
        }
        count("strict_minute_floor_match_targets")
    }
    return buildList {
        add("minute_inspection.health_uploads=0")
        add("minute_inspection.local_journal_changes=0")
        add("minute_inspection.source_targets=${plan.rawRows.size}")
        add("minute_inspection.pending_targets=${targets.size}")
        add("minute_inspection.history_pages=${history.pagesRead}")
        add("minute_inspection.history_complete=${history.complete}")
        add("minute_inspection.history_stop=${history.stopReason.name}")
        for (key in listOf("strict_minute_floor_match_targets", "source_changed_targets", "already_minute_aligned_targets",
            "source_minute_collision_targets", "nonunique_or_absent_minute_targets", "source_or_deleted_conflict_targets",
            "outer_not_minute_floor_targets", "offset_unknown_or_different_targets", "normalized_content_mismatch_targets"))
            add("minute_inspection.$key=${counts[key] ?: 0}")
    }.joinToString("\n")
}
