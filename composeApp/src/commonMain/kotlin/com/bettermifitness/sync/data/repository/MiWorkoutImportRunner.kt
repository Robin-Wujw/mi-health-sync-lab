package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.WorkoutImportJournal
import com.bettermifitness.sync.health.InboundWorkout
import com.bettermifitness.sync.health.WorkoutImportReport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Serial per-record writes, durable reservations and exact readback; no whole-run HTTP replay. */
class MiWorkoutImportRunner(private val cloud: MiWorkoutImportCloud, private val journal: WorkoutImportJournal) {
    suspend fun run(account: String, source: List<InboundWorkout>, maxWrites: Int = 200,
        blockedSources: Set<String> = emptySet()): WorkoutImportReport {
        require(account.isNotBlank() && maxWrites in 0..200)
        var report = WorkoutImportReport(sourceRecords = source.size,
            routesAwaitingConsent = source.count { it.routeConsentRequired })
        val targets = source.mapNotNull { MiWorkoutImportMapper.map(it) }
        report = report.copy(unsupported = source.size - targets.size,
            unsupportedTypes = source.filter { MiWorkoutImportMapper.map(it) == null }
                .map { it.session.activityType + ":" + it.omittedMetricFamilies.sorted().joinToString("+") }.distinct())
        var writes = 0
        for (target in targets.sortedBy { it.start }) {
            if (target.sourceKey in blockedSources) { report = report.copy(pending = report.pending + 1); continue }
            if (source.count { it.session.startTime < target.end && it.session.endTime > target.start } != 1) {
                report = report.copy(conflicts = report.conflicts + 1); continue
            }
            val previous = journal.load(account, target.sourceKey)
            if (previous != null && requiresMigration(previous, target)) {
                val allowed = writes < maxWrites
                val migrated = MiWorkoutIdentityMigration(cloud, journal).run(account, target, previous, allowed)
                if (allowed) writes++
                report = if (migrated) report.copy(revised = report.revised + 1) else report.copy(pending = report.pending + 1)
                continue
            }
            if (previous != null && previous.target.identity != target.identity) {
                report = report.copy(conflicts = report.conflicts + 1); continue
            }
            val before = cloud.read(target.start - 86_400, target.end + 86_400).filter { target.overlaps(it) }
            if (before.size == 1 && target.matches(before.single())) {
                if (previous?.target != target && !journal.reserve(account, target, previous)) {
                    report = report.copy(conflicts = report.conflicts + 1); continue
                }
                journal.verified(account, target)
                journal.rememberSummary(account, target, before.single().value)
                report = report.copy(existing = report.existing + 1); continue
            }
            if (previous != null && !previous.verified) {
                report = report.copy(pending = report.pending + 1); continue
            }
            val revision = before.singleOrNull()?.takeIf { row ->
                target.sameIdentity(row) && (previous?.target?.matches(row) == true || legacySummaryMatches(target, row))
            }
            val restore = before.isEmpty() && canRestore(previous, target)
            if (before.isNotEmpty() && revision == null || before.isEmpty() && previous != null && !restore) {
                report = report.copy(conflicts = report.conflicts + 1); continue
            }
            if (writes >= maxWrites) { report = report.copy(pending = report.pending + 1); continue }
            val finalRead = cloud.read(target.start - 86_400, target.end + 86_400).filter { target.overlaps(it) }
            if (finalRead != before || !journal.reserve(account, target, previous)) {
                report = report.copy(conflicts = report.conflicts + 1); continue
            }
            // Preserve native fields not owned by this mapper on a verified same-identity revision.
            val preserved = revision?.value ?: previous?.lastVerifiedValue.takeIf { restore }
            val value = if (preserved != null) revisionValue(target, preserved) else target.value
            writes++
            try {
                cloud.upload(target, value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Always attempt readback; a lost response may still have persisted. */ }
            val after = try {
                cloud.read(target.start - 86_400, target.end + 86_400).filter { target.overlaps(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            if (after.size == 1 && target.matches(after.single())) {
                journal.verified(account, target)
                journal.rememberSummary(account, target, after.single().value)
                report = if (restore) report.copy(restored = report.restored + 1)
                    else if (revision == null) report.copy(uploaded = report.uploaded + 1)
                    else report.copy(revised = report.revised + 1)
            } else report = report.copy(pending = report.pending + 1)
        }
        return report
    }

    private fun canRestore(previous: com.bettermifitness.sync.data.preferences.WorkoutImportEntry?,
        target: MiWorkoutImportTarget): Boolean {
        if (previous?.verified != true || previous.target != target || target.sid != "xiaomiwear_app_manually" ||
            !target.sourceKey.startsWith("com.huami.watch.hmwatchmanager:")) return false
        return previous.lastVerifiedValue?.let { target.matches(parseMiWorkoutExperimentRow(target.row(it))) } ?: true
    }

    private fun revisionValue(target: MiWorkoutImportTarget, preserved: JsonObject) = JsonObject(preserved +
        target.value.filterKeys { it != "version" && !(it == "proto_type" && preserved["did"] in setOf(
            kotlinx.serialization.json.JsonPrimitive("default"),
            kotlinx.serialization.json.JsonPrimitive("xiaomiwear_app_manually"))) })

    private fun requiresMigration(previous: com.bettermifitness.sync.data.preferences.WorkoutImportEntry,
        target: MiWorkoutImportTarget): Boolean = previous.migrationFrom != null && !previous.verified ||
        previous.verified && previous.target.sid == "default" && target.sid == "xiaomiwear_app_manually"

    /** Adopt only the exact basic treadmill summary written by the earlier bounded experiment. */
    private fun legacySummaryMatches(target: MiWorkoutImportTarget, row: MiWorkoutExperimentCloudRow): Boolean =
        target.key == "indoor_running" && row.value.keys == setOf("start_time", "end_time", "time", "duration",
            "timezone", "sport_type", "proto_type", "version", "distance", "calories") &&
            row.value.all { (key, value) -> target.value[key] == value }
}
