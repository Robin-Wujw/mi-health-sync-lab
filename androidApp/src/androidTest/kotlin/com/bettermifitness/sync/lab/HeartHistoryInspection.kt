package com.bettermifitness.sync.lab

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthStore
import com.bettermifitness.sync.health.HealthRecordIds
import com.bettermifitness.sync.data.repository.MiPendingSpO2Reconciler
import com.bettermifitness.sync.data.repository.copyableText
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal suspend fun previewHeartRecovery(session: MiSessionManager, store: HealthStore,
    ledger: HealthImportLedger, from: Instant, until: Instant, version: String): String {
    val source = "com.huami.watch.hmwatchmanager"
    val request = HealthInboundReadRequest(from.epochSeconds, until.epochSeconds, setOf(source))
    val plan = MiHealthImportMapper.heartRate(store.readHeartRate(request), source, true, request)
    var eligible = false
    val guarded = object : com.bettermifitness.sync.data.preferences.HealthImportJournal by ledger {
        override suspend fun reserveSingleRecovery(account: String,
            expected: com.bettermifitness.sync.data.preferences.HealthImportPendingSnapshot,
            entry: com.bettermifitness.sync.data.preferences.HealthImportJournalEntry,
        ): com.bettermifitness.sync.data.preferences.HealthImportRecoveryReservation? {
            eligible = true
            return null
        }
        override suspend fun reconcileObservedPending(account: String,
            entries: List<com.bettermifitness.sync.data.preferences.HealthImportPendingReconciliation>): Set<String> = emptySet()
    }
    val realCloud = MiDirectHealthImportCloud(session.api)
    val cloud = object : MiHealthImportCloud by realCloud {
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult =
            error("READ_ONLY_PREVIEW")
    }
    val diagnostic = com.bettermifitness.sync.data.repository.MiPendingImportRecovery(cloud, guarded)
        .recover(checkNotNull(session.currentUserId), plan, true)
    return "heart_recovery_preview.eligible=$eligible\n" +
        "heart_recovery_preview.health_uploads=0\n" +
        "heart_recovery_preview.local_journal_changes=0\n" +
        diagnostic.copyableText(version)
}

internal suspend fun reconcileHeartHistory(session: MiSessionManager, store: HealthStore,
    ledger: HealthImportLedger, from: Instant, until: Instant, version: String): String {
    val source = "com.huami.watch.hmwatchmanager"
    val request = HealthInboundReadRequest(from.epochSeconds, until.epochSeconds, setOf(source))
    val plan = MiHealthImportMapper.heartRate(store.readHeartRate(request), source, true, request)
    return MiPendingSpO2Reconciler(MiDirectHealthImportCloud(session.api), ledger)
        .reconcile(checkNotNull(session.currentUserId), plan, userRequested = true).copyableText(version)
}

internal suspend fun inspectHeartCoverage(session: MiSessionManager, store: HealthStore,
    ledger: HealthImportLedger, from: Instant, until: Instant): String {
    val source = "com.huami.watch.hmwatchmanager"
    val request = HealthInboundReadRequest(from.epochSeconds, until.epochSeconds, setOf(source))
    val records = store.readHeartRate(request)
    val plan = MiHealthImportMapper.heartRate(records, source, includeDailyAggregates = true, coverage = request)
    val account = checkNotNull(session.currentUserId)
    val pending = ledger.pendingIdentities(account, plan.rawRows.map { it.identity.stableKey }.toSet())
    val snapshots = ledger.pendingSnapshots(account, pending).values
    val pendingRows = plan.rawRows.filter { it.identity.stableKey in pending }
    val pendingTimes = pendingRows.map { it.identity.time }.sorted()
    val verified = ledger.verifiedFingerprints(account)
    val counts = linkedMapOf<String, Int>()
    fun count(bucket: Int, key: String) { val k = "heart_coverage.age_week.$bucket.$key"; counts[k] = (counts[k] ?: 0) + 1 }
    val cloud = MiDirectHealthImportCloud(session.api)
    var readFailures = 0
    val allObserved = linkedSetOf<MiHealthImportCloudRow>()
    for ((day, targets) in plan.rawRows.groupBy { it.identity.time.floorDiv(86_400) }) {
        val observed = try {
            cloud.readRows(MiHealthImportChannel.RAW, "heart_rate", (day * 86_400 - 86_400).coerceAtLeast(0), (day + 1) * 86_400 + 2)
        }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { readFailures++; null }
        val byTime = observed?.groupBy { it.time }.orEmpty()
        allObserved += observed.orEmpty()
        val byMinute = observed?.groupBy { it.time.floorDiv(60) }.orEmpty()
        for (target in targets) {
            val bucket = ((until.epochSeconds - target.identity.time) / (7 * 86_400)).toInt()
            count(bucket, "source_targets")
            if (target.identity.stableKey in pending) count(bucket, "pending")
            if (HealthImportLedger.identityDigest(target.identity.stableKey) in verified) count(bucket, "journal_verified")
            if (observed == null) { count(bucket, "unqueried"); continue }
            val status = MiHealthImportVerifier.verify(target, byTime[target.identity.time].orEmpty()).status
            count(bucket, "cloud_${status.name.lowercase()}")
            if (status == MiHealthImportVerificationStatus.MISSING) {
                val candidates = byMinute[target.identity.time.floorDiv(60)].orEmpty()
                if (candidates.any { !it.deleted && it.sid == target.identity.sid && it.value["bpm"] == target.value["bpm"] })
                    count(bucket, "missing_same_minute_value")
            }
        }
    }
    // Conflict classification uses source samples only, without exposing values, IDs or times.
    val conflicts = records.flatMap { record -> record.samples.map { it.epochMillis / 1000 to (record to it) } }
        .groupBy({ it.first }, { it.second }).values.filter { same -> same.map { it.second.beatsPerMinute }.distinct().size > 1 }
    val withinRecord = conflicts.count { same -> same.groupBy { it.first.origin.stableRecordKey }.values.any {
        group -> group.map { it.second.beatsPerMinute }.distinct().size > 1 } }
    val exactMillis = conflicts.count { same -> same.groupBy { it.second.epochMillis }.values.any { group ->
        group.map { it.second.beatsPerMinute }.distinct().size > 1 } }
    val cloudByTime = allObserved.groupBy { it.time }
    var cloudConflictingTargets = 0
    var cloudMatchingCandidates = 0
    for (same in conflicts) {
        val time = same.first().second.epochMillis / 1000
        val observed = cloudByTime[time].orEmpty()
        if (observed.isNotEmpty()) cloudConflictingTargets++
        val candidates = same.map { (record, sample) -> MiHealthImportRow(
            MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "heart_rate", time),
            kotlinx.serialization.json.buildJsonObject {
                put("time", kotlinx.serialization.json.JsonPrimitive(time)); put(
                    "bpm", kotlinx.serialization.json.JsonPrimitive(sample.beatsPerMinute))
            },
            record.startZoneOffsetSeconds ?: 0, setOf(record.origin.stableRecordKey)) }
                .distinctBy { it.canonicalContent to it.zoneOffsetSeconds }
        cloudMatchingCandidates += candidates.count {
            MiHealthImportVerifier.verify(it, observed).status == MiHealthImportVerificationStatus.VERIFIED
        }
    }
    return buildList {
        add("heart_coverage.health_uploads=0")
        add("heart_coverage.local_journal_changes=0")
        add("heart_coverage.pending_metadata.rows=${snapshots.size}")
        add("heart_coverage.pending_metadata.invalid=${snapshots.count { !it.valid }}")
        add("heart_coverage.pending_metadata.zone_unknown=${snapshots.count { it.zoneOffsetSeconds == null }}")
        add("heart_coverage.pending_metadata.recovery_attempts=${snapshots.count { it.recoveryAttempt != null }}")
        add("heart_coverage.pending_metadata.reservation_groups=${snapshots.map { it.pendingToken.substringBefore('|') }.distinct().size}")
        val changedSnapshots = snapshots.count { snapshot ->
            pendingRows.find { it.identity.stableKey == snapshot.targetIdentity }
                ?.let { !snapshot.matches(com.bettermifitness.sync.data.repository.MiHealthImportRunner.journalEntry(it)) } == true
        }
        add("heart_coverage.pending_metadata.source_changed=$changedSnapshots")
        val pendingLocalDays = pendingRows.map { (it.identity.time + it.zoneOffsetSeconds).floorDiv(86_400) }.distinct().size
        add("heart_coverage.pending_metadata.local_days=$pendingLocalDays")
        add("heart_coverage.pending_metadata.adjacent_minute_pairs=${pendingTimes.zipWithNext().count { (a, b) -> b - a == 60L }}")
        add("heart_coverage.source_records=${records.size}")
        add("heart_coverage.planned_daily_rows=${plan.aggregateRows.size}")
        val rejectedLocalDays = plan.issues.filter { it.severity == MiHealthImportIssueSeverity.REJECTED }
            .flatMap { it.affectedLocalDays.orEmpty() }.distinct().size
        add("heart_coverage.rejected_local_days=$rejectedLocalDays")
        add("heart_coverage.read_failure_groups=$readFailures")
        add("heart_coverage.conflicting_seconds=${conflicts.size}")
        add("heart_coverage.conflicts_within_one_record=$withinRecord")
        add("heart_coverage.conflicts_between_records=${conflicts.size - withinRecord}")
        add("heart_coverage.conflicts_at_exact_millisecond=$exactMillis")
        add("heart_coverage.conflicts_only_after_second_rounding=${conflicts.size - exactMillis}")
        add("heart_coverage.conflict_targets_with_cloud_rows=$cloudConflictingTargets")
        add("heart_coverage.conflict_candidates_matching_cloud=$cloudMatchingCandidates")
        addAll(counts.map { (key, value) -> "$key=$value" })
        add("heart_coverage.age_week_notes=0_is_0_to_7_days;1_is_7_to_14;2_is_14_to_21;3_is_21_to_28;4_is_28_to_window_end;" +
            "missing_is_query_observation_not_proof_of_absence")
    }.joinToString("\n")
}

/** Aggregate-only investigation; deliberately exposes no mutation operation. */
internal suspend fun inspectHeartHistory(session: MiSessionManager, store: HealthStore,
    ledger: HealthImportLedger, from: Instant, until: Instant): String {
    val source = "com.huami.watch.hmwatchmanager"
    val request = HealthInboundReadRequest(from.epochSeconds, until.epochSeconds, setOf(source))
    val records = store.readHeartRate(request)
    val plan = MiHealthImportMapper.heartRate(records, source)
    val pending = ledger.pendingIdentities(checkNotNull(session.currentUserId), plan.rawRows.map { it.identity.stableKey }.toSet())
    val targets = plan.rawRows.filter { it.identity.stableKey in pending }
    val report = mutableListOf("heart_history.health_uploads=0", "heart_history.local_journal_changes=0",
        "heart_history.source_records=${records.size}", "heart_history.source_targets=${plan.rawRows.size}",
        "heart_history.pending_targets=${targets.size}")
    if (targets.isEmpty()) return report.joinToString("\n")
    val cloud = MiDirectHealthImportCloud(session.api)
    // Dedicated read-only investigation with a larger finite budget than production recovery.
    for ((index, anchor) in listOf(targets.maxOf { it.identity.time }).withIndex()) {
        val history = readExtendedHeartHistory(session, anchor + 61)
        val observed = history.rows.distinct()
        val exact = targets.groupingBy { MiHealthImportVerifier.verify(it, observed).status }.eachCount()
        val byMinute = observed.groupBy { it.time.floorDiv(60) }
        val missing = targets.filter { MiHealthImportVerifier.verify(it, observed).status == MiHealthImportVerificationStatus.MISSING }
        val sameMinute = missing.filter { byMinute[it.identity.time.floorDiv(60)].orEmpty().isNotEmpty() }
        val sameValue = sameMinute.count { target -> byMinute[target.identity.time.floorDiv(60)].orEmpty().any {
            !it.deleted && it.sid == target.identity.sid && it.value["bpm"] == target.value["bpm"]
        } }
        val prefix = "heart_history.query.$index"
        report += "$prefix.pages=${history.pagesRead}"
        report += "$prefix.complete=${history.complete}"
        report += "$prefix.stop=${history.stopReason}"
        report += "$prefix.returned_rows=${observed.size}"
        for (status in MiHealthImportVerificationStatus.entries) report += "$prefix.exact_${status.name.lowercase()}=${exact[status] ?: 0}"
        report += "$prefix.missing_same_minute_targets=${sameMinute.size}"
        report += "$prefix.missing_same_minute_same_value_targets=$sameValue"
        report += "$prefix.rows_with_bpm=${observed.count { "bpm" in it.value }}"
        report += "$prefix.rows_with_items=${observed.count { "items" in it.value }}"
        report += "$prefix.rows_default_source=${observed.count { it.sid == MI_HEALTH_CONNECT_IMPORT_SID }}"
        report += "$prefix.rows_before_pending=${observed.count { it.time < targets.minOf { row -> row.identity.time } }}"
        report += "$prefix.rows_within_pending=${observed.count {
            it.time in targets.minOf { row -> row.identity.time }..targets.maxOf { row -> row.identity.time }
        }}"
    }
    return report.joinToString("\n")
}

private suspend fun readExtendedHeartHistory(session: MiSessionManager, until: Long): MiHealthImportHistoryRead {
    val rows = mutableListOf<MiHealthImportCloudRow>()
    val cursors = mutableSetOf<String>()
    var next: String? = null
    for (pageIndex in 1..128) {
        val payload = buildMap<String, Any?> {
            put("key", "heart_rate"); put("end_time", until); put("reverse", true)
            next?.let { put("next_key", it) }
        }
        val response = session.api.dataClient().post("/app/v1/data/get_fitness_data_by_time", payload = payload).jsonObject
        check(response["code"]?.jsonPrimitive?.intOrNull == 0)
        val result = response["result"]!!.jsonObject
        for (element in result["data_list"]!!.jsonArray) {
            val row = element.jsonObject
            val time = row["time"]!!.jsonPrimitive.long
            val deleted = row["deleted"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean ?: false
            val raw = row["value"]
            val value = when (raw) {
                is JsonObject -> raw
                is JsonPrimitive -> Json.parseToJsonElement(raw.content).jsonObject
                else -> { check(deleted); JsonObject(emptyMap()) }
            }
            if (time in 0 until until) rows += MiHealthImportCloudRow(
                row["sid"]?.jsonPrimitive?.contentOrNull, row["key"]?.jsonPrimitive?.contentOrNull ?: "heart_rate",
                time, row["tag"]?.jsonPrimitive?.contentOrNull, value, deleted,
                row["zone_offset"]?.jsonPrimitive?.intOrNull)
        }
        val more = result["has_more"]?.jsonPrimitive?.booleanOrNull
            ?: return MiHealthImportHistoryRead(rows.distinct(), pageIndex, false, MiHistoryStopReason.INVALID_PAGINATION)
        if (!more) return MiHealthImportHistoryRead(rows.distinct(), pageIndex, true)
        next = result["next_key"]?.jsonPrimitive?.contentOrNull
        if (next.isNullOrBlank()) return MiHealthImportHistoryRead(rows.distinct(), pageIndex, false, MiHistoryStopReason.MISSING_CURSOR)
        if (!cursors.add(next!!)) return MiHealthImportHistoryRead(rows.distinct(), pageIndex, false, MiHistoryStopReason.REPEATED_CURSOR)
    }
    return MiHealthImportHistoryRead(rows.distinct(), 128, false, MiHistoryStopReason.PAGE_LIMIT)
}
