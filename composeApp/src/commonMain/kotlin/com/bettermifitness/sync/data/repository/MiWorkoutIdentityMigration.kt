package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiWorkoutExperimentCloudRow
import com.bettermifitness.sync.data.api.MiWorkoutImportCloud
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.data.preferences.WorkoutImportEntry
import com.bettermifitness.sync.data.preferences.WorkoutImportJournal
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Copies the verified source before retiring its exact legacy identity; both attempts are durable. */
internal class MiWorkoutIdentityMigration(private val cloud: MiWorkoutImportCloud, private val journal: WorkoutImportJournal) {
    suspend fun run(account: String, target: MiWorkoutImportTarget, previous: WorkoutImportEntry, allowWrites: Boolean): Boolean {
        val old = previous.migrationFrom ?: previous.target
        if (old.copy(sid = target.sid).identity != target.identity) return false
        var entry = previous
        val rows = read(target)
        if (!safeRows(rows, old, target)) return false
        if (previous.migrationFrom == null) {
            if (!allowWrites) return false
            if (!journal.reserveMigration(account, target, previous)) return false
            entry = checkNotNull(journal.load(account, target.sourceKey))
        } else if (entry.target != target) return false
        if (entry.target != target || !copyIfNeeded(account, old, target, entry, allowWrites)) return false
        return retireIfCopied(account, old, target, allowWrites)
    }

    private suspend fun copyIfNeeded(account: String, old: MiWorkoutImportTarget, target: MiWorkoutImportTarget,
        entry: WorkoutImportEntry, allowWrites: Boolean): Boolean {
        val rows = read(target)
        if (!safeRows(rows, old, target)) return false
        if (rows.any { target.matches(it) }) return true
        if (!allowWrites || entry.migrationCopyAttempted) return false
        val legacy = rows.singleOrNull { old.matches(it) } ?: return false
        val reserved = entry.copy(migrationCopyAttempted = true)
        if (!journal.updateMigration(account, entry, reserved)) return false
        val content = JsonObject(legacy.value + target.value.filterKeys { it != "version" && it != "proto_type" })
        try { cloud.upload(target, content) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Independent readback decides whether the copy exists. */ }
        val after = read(target)
        return after.count { target.matches(it) } == 1 && safeRows(after, old, target)
    }

    private suspend fun retireIfCopied(account: String, old: MiWorkoutImportTarget, target: MiWorkoutImportTarget,
        allowWrites: Boolean): Boolean {
        var rows = read(target)
        if (rows.count { target.matches(it) } != 1 || !safeRows(rows, old, target)) return false
        val entry = journal.load(account, target.sourceKey) ?: return false
        val legacy = rows.singleOrNull { old.matches(it) }
        if (legacy != null) {
            if (!allowWrites || entry.migrationDeleteAttempted && entry.migrationDeleteProtocol >= 1) return false
            val reserved = entry.copy(migrationDeleteAttempted = true, migrationDeleteProtocol = 1)
            if (!journal.updateMigration(account, entry, reserved)) return false
            try { cloud.retire(old, legacy.value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The already verified copy is never removed. */ }
            rows = read(target)
        }
        if (rows.size != 1 || !target.matches(rows.single())) return false
        journal.verified(account, target)
        return true
    }

    private suspend fun read(target: MiWorkoutImportTarget) = cloud.read(target.start - 86_400, target.end + 86_400)
        .filter { !it.deleted && target.overlaps(it) }

    private fun safeRows(rows: List<MiWorkoutExperimentCloudRow>, old: MiWorkoutImportTarget, target: MiWorkoutImportTarget): Boolean =
        rows.size in 1..2 && rows.count { old.matches(it) } <= 1 && rows.count { target.matches(it) } <= 1 &&
            rows.all { old.matches(it) || target.matches(it) }
}
