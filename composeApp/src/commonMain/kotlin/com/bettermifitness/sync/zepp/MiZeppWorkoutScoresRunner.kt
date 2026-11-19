package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportJournalEntry
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Enrich only a unique, already owned workout. Never create another copy of an HC-imported session. */
class MiZeppWorkoutScoresRunner(private val cloud: MiWorkoutImportCloud, private val journal: HealthImportJournal) {
    suspend fun run(account: String, source: List<ZeppWorkoutScores>, owned: List<MiWorkoutImportTarget>): ZeppImportResult {
        var result = ZeppImportResult()
        val ownership = journal.ownedVerifiedFingerprints(account)
        for ((start, sources) in source.filter {
            it.load != null || it.aerobicEffect != null || it.anaerobicEffect != null
        }.groupBy { it.startSeconds }) {
            val score = sources.distinctBy {
                listOf(it.endSeconds, it.trackId, it.load, it.aerobicEffect, it.anaerobicEffect)
            }.singleOrNull()
            if (score == null) { result = result.copy(conflicts = result.conflicts + 1); continue }
            val candidates = owned.filter { it.start == start }
            if (candidates.isEmpty()) { result = result.copy(skipped = result.skipped + 1); continue }
            val target = candidates.singleOrNull()?.takeIf { score.endSeconds == null || it.end == score.endSeconds }
            if (target == null) { result = result.copy(conflicts = result.conflicts + 1); continue }
            val fields = buildJsonObject {
                score.load?.takeIf { it % 1 == 0.0 && it in 0.0..1e6 }?.let { put("train_load", it.toInt()) }
                score.aerobicEffect?.takeIf { it in 0.0..5.0 }?.let { put("train_effect", it) }
                score.anaerobicEffect?.takeIf { it in 0.0..5.0 }?.let { put("anaerobic_train_effect", it) }
            }
            if (fields.isEmpty()) continue
            val identity = "ZEPP-SCORES|${target.identity}"
            val entry = HealthImportJournalEntry(identity, fingerprint(fields), setOf(HealthRecordIds.version(
                "zepp-workout", score.trackId).toString()))
            val rows = cloud.read(target.start, target.end).filter { target.overlaps(it) }
            val row = rows.singleOrNull()?.takeIf { target.sameIdentity(it) && target.matches(it) }
            if (row == null) { result = result.copy(conflicts = result.conflicts + 1); continue }
            fun matches(value: JsonObject): Boolean = fields.all { (k, v) ->
                val actual = value[k] as? JsonPrimitive
                actual != null && !actual.isString && actual.doubleOrNull == (v as JsonPrimitive).doubleOrNull
            }
            if (matches(row.value)) {
                val pending = journal.pendingIdentities(account, setOf(identity))
                val reservation = journal.pendingSnapshots(account, pending)[identity]
                if (identity in pending && (reservation == null || !reservation.matches(entry))) {
                    result = result.copy(pending = result.pending + 1); continue
                }
                journal.markVerified(account, listOf(entry.copy(ownedUpload = reservation != null)))
                result = result.copy(verified = result.verified + 1); continue
            }
            if (identity in journal.pendingIdentities(account, setOf(identity))) {
                result = result.copy(pending = result.pending + 1); continue
            }
            val currentScores = JsonObject(row.value.filterKeys { it in SCORE_FIELDS })
            if (currentScores.any { (k, v) -> fields[k] != v } &&
                ownership[HealthImportLedger.identityDigest(identity)] != fingerprint(currentScores)) {
                result = result.copy(conflicts = result.conflicts + 1); continue
            }
            // A second read immediately before reserving prevents using a stale detail snapshot.
            if (cloud.read(target.start, target.end).filter { target.overlaps(it) } != rows) {
                result = result.copy(conflicts = result.conflicts + 1); continue
            }
            val enriched = JsonObject(row.value + fields)
            journal.markPending(account, listOf(entry))
            try { cloud.upload(target, enriched) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Uncertain mutation: always inspect before any future write. */ }
            val after = try { cloud.read(target.start, target.end).filter { target.overlaps(it) }.singleOrNull() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            if (after != null && target.sameIdentity(after) &&
                canonicalMiHealthImportJson(after.value) == canonicalMiHealthImportJson(enriched)) {
                journal.markVerified(account, listOf(entry.copy(ownedUpload = true)))
                result = result.copy(verified = result.verified + 1, uploaded = result.uploaded + 1)
            } else result = result.copy(pending = result.pending + 1)
        }
        return result
    }

    private fun fingerprint(value: JsonObject) = HealthRecordIds.version("zepp-workout-scores-v1",
        canonicalMiHealthImportJson(value)).toString()

    companion object { private val SCORE_FIELDS = setOf("train_load", "train_effect", "anaerobic_train_effect") }
}
