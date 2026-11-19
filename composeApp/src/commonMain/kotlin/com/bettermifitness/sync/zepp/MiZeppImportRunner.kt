package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportJournalEntry
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ZeppImportResult(val verified: Int = 0, val uploaded: Int = 0, val pending: Int = 0,
    val conflicts: Int = 0, val skipped: Int = 0)

/** Durable write-before-send and readback. A timeout never authorizes a blind second POST. */
class MiZeppImportRunner(private val cloud: MiHealthImportCloud, private val journal: HealthImportJournal) {
    suspend fun run(account: String, rows: List<MiHealthImportRow>): ZeppImportResult {
        require(account.isNotBlank())
        require(rows.size <= 20_000 && rows.map { it.identity }.distinct().size == rows.size)
        var result = ZeppImportResult()
        for ((key, group) in rows.groupBy { it.identity.key }) {
            val start = group.minOf { it.identity.time }
            val until = group.maxOf { it.identity.time } + 1
            val before = readRows(group, key, start, until)
            val beforeByTime = before.groupBy { it.time }
            val confirmations = mutableListOf<HealthImportJournalEntry>()
            val verified = journal.verifiedFingerprints(account)
            val toUpload = mutableListOf<MiHealthImportRow>()
            val pending = journal.pendingIdentities(account, group.map { it.identity.stableKey }.toSet())
            val pendingSnapshots = journal.pendingSnapshots(account, pending)
            val owned = journal.ownedVerifiedFingerprints(account)
            for (row in group) {
                currentCoroutineContext().ensureActive()
                // Native control reads can expose tombstones or another observation
                // omitted by ranged reads. Retain all of them when deciding uniqueness.
                val sameTime = beforeByTime[row.identity.time].orEmpty()
                val exact = sameTime.singleOrNull()?.takeIf { !it.deleted && matches(row, it) }
                val entry = entry(row)
                if (exact != null) {
                    val reservation = pendingSnapshots[row.identity.stableKey]
                    if (row.identity.stableKey in pending && (reservation == null || !reservation.matches(entry))) {
                        result = result.copy(pending = result.pending + 1); continue
                    }
                    if (row.identity.stableKey in pending ||
                        verified[HealthImportLedger.identityDigest(row.identity.stableKey)] != entry.contentFingerprint) {
                        confirmations += entry.copy(ownedUpload = reservation != null)
                    }
                    result = result.copy(verified = result.verified + 1)
                } else if (row.identity.stableKey in pending) {
                    result = result.copy(pending = result.pending + 1)
                } else if (sameTime.isNotEmpty() && (sameTime.any { it.deleted } || sameTime.size != 1 ||
                        sameTime.single().sid != row.identity.sid ||
                        owned[HealthImportLedger.identityDigest(row.identity.stableKey)] !=
                        fingerprint(sameTime.single().value, sameTime.single().zoneOffsetSeconds))) {
                    result = result.copy(conflicts = result.conflicts + 1)
                } else toUpload += row
            }
            // Matching receipts are observations, not writes. Persist new confirmations in
            // bounded transactions, and leave already-identical durable receipts untouched.
            for (entries in confirmations.chunked(250)) journal.markVerified(account, entries)
            for (batch in toUpload.sortedBy { it.identity.time }.chunked(MI_HEALTH_IMPORT_RAW_BATCH_SIZE)) {
                // Dense stress histories can span thousands of points. Recheck the
                // current batch, retaining every same-time conflict, rather than
                // traversing the entire source window twice for every 50 points.
                val batchStart = batch.first().identity.time
                val batchUntil = batch.last().identity.time + 1
                val finalRead = readRows(batch, key, batchStart, batchUntil)
                val finalByTime = finalRead.groupBy { it.time }
                if (batch.any { row -> beforeByTime[row.identity.time].orEmpty() !=
                        finalByTime[row.identity.time].orEmpty() }) {
                    result = result.copy(conflicts = result.conflicts + batch.size); continue
                }
                journal.markPending(account, batch.map(::entry))
                try { cloud.uploadRows(batch, experimentalEnabled = true) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Readback is the only evidence that the mutation persisted. */ }
                val after = try { readRows(batch, key, batchStart, batchUntil) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
                val afterByTime = after.groupBy { it.time }
                val uploadedConfirmations = mutableListOf<HealthImportJournalEntry>()
                for (row in batch) {
                    currentCoroutineContext().ensureActive()
                    val actual = afterByTime[row.identity.time].orEmpty().singleOrNull()?.takeUnless { it.deleted }
                    if (actual != null && matches(row, actual)) {
                        uploadedConfirmations += entry(row).copy(ownedUpload = true)
                        result = result.copy(verified = result.verified + 1, uploaded = result.uploaded + 1)
                    } else result = result.copy(pending = result.pending + 1)
                }
                journal.markVerified(account, uploadedConfirmations)
            }
        }
        return result
    }

    /** A real phone run found persisted stress rows omitted by the ranged endpoint.
     * Recover positive observations through the native full-pull shape. A bounded
     * control read never establishes absence and never authorizes a replay. */
    private suspend fun readRows(expected: List<MiHealthImportRow>, key: String, from: Long,
        until: Long): List<MiHealthImportCloudRow> {
        val direct = cloud.readRows(MiHealthImportChannel.RAW, key, from, until)
        val observedTimes = direct.filterNot { it.deleted }.mapTo(mutableSetOf()) { it.time }
        val missing = expected.filter { it.identity.time !in observedTimes }
        if (missing.isEmpty()) return direct
        val control = try { cloud.readRawRowsWithoutStart(key, missing.maxOf { it.identity.time } + 1, 5) }
        catch (_: UnsupportedOperationException) { return direct }
        return (direct + control.rows.filter { it.key == key && it.time >= from && it.time < until }).distinct()
    }

    private fun matches(expected: MiHealthImportRow, actual: MiHealthImportCloudRow): Boolean =
        actual.sid == expected.identity.sid && actual.key == expected.identity.key && actual.tag == null &&
            actual.zoneOffsetSeconds == expected.zoneOffsetSeconds &&
            canonicalMiHealthImportJson(actual.value) == expected.canonicalContent

    private fun entry(row: MiHealthImportRow) = HealthImportJournalEntry(row.identity.stableKey,
        fingerprint(row.value, row.zoneOffsetSeconds), row.sourceRecordKeys, zoneOffsetSeconds = row.zoneOffsetSeconds)

    private fun fingerprint(value: kotlinx.serialization.json.JsonObject, zone: Int?) =
        HealthRecordIds.version("zepp-score-v1", canonicalMiHealthImportJson(value), zone).toString()
}
