package com.bettermifitness.sync.zepp

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MiZeppImportRunnerTest {
    private val cloud = Cloud()
    private val journal = Journal()
    private val runner = MiZeppImportRunner(cloud, journal)
    private fun source(value: Int = 40) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "stress", 1_800_000_000),
        buildJsonObject { put("time", 1_800_000_000); put("stress", value) }, 28_800, setOf("synthetic-source"))

    @Test fun initialUploadAndNewProcessRepeatWriteOnlyOnce() = runTest {
        assertEquals(1, runner.run("a", listOf(source())).uploaded)
        assertEquals(1, MiZeppImportRunner(cloud, journal).run("a", listOf(source())).verified)
        assertEquals(1, cloud.uploads)
    }
    @Test fun timeoutAfterPersistenceStillVerifies() = runTest {
        cloud.error = IllegalStateException("synthetic")
        assertEquals(1, runner.run("a", listOf(source())).verified)
        assertTrue(journal.pending.isEmpty())
    }
    @Test fun absentReadbackIsDurablyPendingAndNeverBlindlyReplayed() = runTest {
        cloud.persist = false
        assertEquals(1, runner.run("a", listOf(source())).pending)
        assertEquals(1, MiZeppImportRunner(cloud, journal).run("a", listOf(source(45))).pending)
        assertEquals(1, cloud.uploads)
    }
    @Test fun nativeControlFindsPersistedPendingRowsOmittedByRangedReadsWithoutReposting() = runTest {
        cloud.omitRange = true
        assertEquals(1, runner.run("a", listOf(source())).pending)
        assertEquals(1, cloud.uploads)
        cloud.controlAvailable = true
        val result = MiZeppImportRunner(cloud,journal).run("a",listOf(source()))
        assertEquals(1,result.verified)
        assertEquals(0,result.pending)
        assertEquals(0,result.uploaded)
        assertEquals(1,cloud.uploads)
        assertTrue(journal.pending.isEmpty())
    }
    @Test fun nativeControlRetainsForeignSourceConflictsInsteadOfAuthorizingAnUpload() = runTest {
        cloud.omitRange=true
        cloud.controlAvailable=true
        cloud.rows+=row(source()).copy(sid="watch")
        assertEquals(1,runner.run("a",listOf(source())).conflicts)
        assertEquals(0,cloud.uploads)
    }
    @Test fun incompleteNativeControlDoesNotClearOrReplayAnUnreturnedPendingRow() = runTest {
        cloud.persist = false
        runner.run("a", listOf(source()))
        cloud.omitRange = true
        cloud.controlAvailable = true
        val result = MiZeppImportRunner(cloud, journal).run("a", listOf(source()))
        assertEquals(1, result.pending)
        assertEquals(0, result.verified)
        assertEquals(1, cloud.uploads)
        assertEquals(1, journal.pending.size)
    }
    @Test fun nativeControlCannotAdoptAnExactRowBesideADeletionMarker() = runTest {
        cloud.persist = false
        runner.run("a", listOf(source()))
        cloud.omitRange = true
        cloud.controlAvailable = true
        cloud.rows += listOf(row(source()), row(source()).copy(deleted = true))
        val result = MiZeppImportRunner(cloud, journal).run("a", listOf(source()))
        assertEquals(1, result.pending)
        assertEquals(0, result.verified)
        assertEquals(1, cloud.uploads)
        assertEquals(1, journal.pending.size)
    }
    @Test fun deletedOwnedRowCannotBeResurrectedByNormalSync() = runTest {
        runner.run("a", listOf(source()))
        cloud.rows[0] = row(source()).copy(deleted = true)
        val result = MiZeppImportRunner(cloud, journal).run("a", listOf(source()))
        assertEquals(1, result.conflicts)
        assertEquals(0, result.verified)
        assertEquals(1, cloud.uploads)
    }
    @Test fun deletionObservedDuringReadbackKeepsTheWritePending() = runTest {
        cloud.onRead = { read -> if (read == 3) cloud.rows += row(source()).copy(deleted = true) }
        val result = runner.run("a", listOf(source()))
        assertEquals(1, result.pending)
        assertEquals(0, result.verified)
        assertEquals(1, cloud.uploads)
        assertEquals(1, journal.pending.size)
    }
    @Test fun ownedRevisionUpdatesTheSameRow() = runTest {
        runner.run("a", listOf(source()))
        assertEquals(1, runner.run("a", listOf(source(45))).uploaded)
        assertEquals(2, cloud.uploads)
        assertEquals(1, cloud.rows.size)
    }
    @Test fun conflictingWatchOrUnownedDefaultRowIsPreserved() = runTest {
        cloud.rows += row(source(35)).copy(sid = "watch")
        assertEquals(1, runner.run("a", listOf(source())).conflicts)
        assertEquals(0, cloud.uploads)
        cloud.rows[0] = cloud.rows[0].copy(sid = "default")
        assertEquals(1, runner.run("a", listOf(source())).conflicts)
    }
    @Test fun externallyEditedOwnedRowIsNotOverwritten() = runTest {
        runner.run("a", listOf(source()))
        cloud.rows[0] = row(source(33))
        assertEquals(1, runner.run("a", listOf(source(45))).conflicts)
        assertEquals(1, cloud.uploads)
    }
    @Test fun otherAccountDoesNotInheritOwnership() = runTest {
        runner.run("a", listOf(source()))
        assertEquals(1, runner.run("b", listOf(source(45))).conflicts)
        assertEquals(1, cloud.uploads)
    }
    @Test fun cancellationAfterWritePreservesTheReservation() = runTest {
        cloud.error = CancellationException()
        assertFailsWith<CancellationException> { runner.run("a", listOf(source())) }
        assertEquals(1, journal.pending.size)
        cloud.error = null
        assertEquals(1, runner.run("a", listOf(source())).verified)
        assertEquals(1, cloud.uploads)
    }
    @Test fun raceBetweenPreflightReadsStopsUpload() = runTest {
        cloud.onRead = { index -> if (index == 2) cloud.rows += row(source(35)) }
        assertEquals(1, runner.run("a", listOf(source())).conflicts)
        assertEquals(0, cloud.uploads)
    }
    @Test fun duplicateSourceDestinationsFailBeforeAnyNetworkRequest() = runTest {
        assertFailsWith<IllegalArgumentException> { runner.run("a", listOf(source(), source(45))) }
        assertEquals(0, cloud.reads)
    }

    @Test fun denseHistoryRechecksEachBatchAndStillFindsEveryUploadedPoint() = runTest {
        val rows = (0..150).reversed().map { offset -> source().let { original ->
            val time = original.identity.time + offset
            original.copy(identity = original.identity.copy(time = time),
                value = buildJsonObject { put("time", time); put("stress", 40) })
        } }
        assertEquals(151, runner.run("a", rows).verified)
        assertEquals(4, cloud.uploads)
        assertEquals(9, cloud.readRanges.size)
        assertTrue(cloud.readRanges.drop(1).all { (from, until) -> until - from <= 50 })
        assertEquals(151, MiZeppImportRunner(cloud, journal).run("a", rows).verified)
        assertEquals(4, cloud.uploads)
    }

    private fun denseRows(count: Int): List<MiHealthImportRow> = (0 until count).map { offset ->
        val original = source()
        val time = original.identity.time + offset
        original.copy(identity = original.identity.copy(time = time),
            value = buildJsonObject { put("time", time); put("stress", 40) })
    }

    @Test fun denseExistingHistoryUsesBoundedReceiptTransactionsAndRepeatWritesNone() = runTest {
        val rows = denseRows(2000)
        cloud.rows += rows.map(::row)
        assertEquals(2000, runner.run("a", rows).verified)
        assertEquals(List(8) { 250 }, journal.verifiedBatchSizes)
        assertEquals(0, cloud.uploads)
        assertEquals(2000, runner.run("a", rows).verified)
        assertEquals(8, journal.verifiedTransactions)
        assertEquals(0, cloud.uploads)
    }

    @Test fun newUploadConfirmsEachNativeBatchInOneReceiptTransaction() = runTest {
        val rows = denseRows(151)
        assertEquals(151, runner.run("a", rows).uploaded)
        assertEquals(listOf(50, 50, 50, 1), journal.verifiedBatchSizes)
        assertTrue(journal.pending.isEmpty())
    }

    @Test fun cancellationDuringReceiptBatchKeepsEarlierConfirmationsAndCanResumeReadOnly() = runTest {
        val rows = denseRows(501)
        cloud.rows += rows.map(::row)
        journal.onVerifiedTransaction = { if (it == 2) throw CancellationException("synthetic interruption") }
        assertFailsWith<CancellationException> { runner.run("a", rows) }
        assertEquals(250, journal.verified.size)
        assertEquals(0, cloud.uploads)
        journal.onVerifiedTransaction = {}
        assertEquals(501, runner.run("a", rows).verified)
        assertEquals(501, journal.verified.size)
        assertEquals(0, cloud.uploads)
    }

    @Test fun existingReceiptDoesNotPreventMatchingPendingReservationFromBeingReconciled() = runTest {
        cloud.persist = false
        runner.run("a", listOf(source()))
        val reservation = journal.pending.values.single()
        journal.verified["a" to reservation.targetIdentity] = reservation
        cloud.rows += row(source())
        assertEquals(1, runner.run("a", listOf(source())).verified)
        assertTrue(journal.pending.isEmpty())
        assertTrue(journal.verified.values.single().ownedUpload)
        assertEquals(1, cloud.uploads)
    }

    @Test fun stageDeadlineAfterPossiblePersistenceRetainsPendingAndNextRunDoesNotRepost() = runTest {
        cloud.afterPersist = { kotlinx.coroutines.awaitCancellation() }
        assertFailsWith<ZeppSyncTimeoutException> {
            runBoundedZeppSync(timeoutMillis = 20) { runner.run("a", listOf(source())) }
        }
        assertEquals(1, journal.pending.size)
        cloud.afterPersist = {}
        assertEquals(1, runner.run("a", listOf(source())).verified)
        assertTrue(journal.pending.isEmpty())
        assertEquals(1, cloud.uploads)
    }

    @Test fun stageDeadlineBeforeWriteDoesNotCreatePendingOrUploadAnything() = runTest {
        cloud.onRead = { kotlinx.coroutines.awaitCancellation() }
        assertFailsWith<ZeppSyncTimeoutException> {
            runBoundedZeppSync(timeoutMillis = 20) { runner.run("a", listOf(source())) }
        }
        assertEquals(0, cloud.uploads)
        assertTrue(journal.pending.isEmpty())
    }

    internal class Journal : HealthImportJournal {
        var verifiedTransactions = 0
        val verifiedBatchSizes = mutableListOf<Int>()
        var onVerifiedTransaction: (Int) -> Unit = {}
        val pending = mutableMapOf<Pair<String, String>, HealthImportJournalEntry>()
        val verified = mutableMapOf<Pair<String, String>, HealthImportJournalEntry>()
        override suspend fun pendingIdentities(account: String, identities: Set<String>) = identities.filterTo(mutableSetOf()) {
            account to it in pending
        }
        override suspend fun markPending(account: String, entries: List<HealthImportJournalEntry>) {
            entries.forEach { pending[account to it.targetIdentity] = it }
        }
        override suspend fun pendingSnapshots(account: String, identities: Set<String>) = identities.mapNotNull { identity ->
            pending[account to identity]?.let { entry -> identity to HealthImportPendingSnapshot(identity, "synthetic-token",
                entry.contentFingerprint, HealthImportLedger.sourceDigest(entry.sourceRecordKeys), entry.zoneOffsetSeconds,
                true, false, null) }
        }.toMap()
        override suspend fun markVerified(account: String, entries: List<HealthImportJournalEntry>) {
            if (entries.isEmpty()) return
            verifiedBatchSizes += entries.size
            onVerifiedTransaction(++verifiedTransactions)
            entries.forEach { e ->
                val k = account to e.targetIdentity
                val old = verified[k]
                val previousOwnership = old?.takeIf { it.contentFingerprint == e.contentFingerprint }?.ownedUpload == true
                verified[k] = e.copy(ownedUpload = e.ownedUpload || previousOwnership)
                pending.remove(k)
            }
        }
        override suspend fun verifiedFingerprints(account: String) = verified.filterKeys { it.first == account }
            .mapKeys { HealthImportLedger.identityDigest(it.key.second) }.mapValues { it.value.contentFingerprint }
        override suspend fun ownedVerifiedFingerprints(account: String) = verified.filter {
            it.key.first == account && it.value.ownedUpload
        }
            .mapKeys { HealthImportLedger.identityDigest(it.key.second) }.mapValues { it.value.contentFingerprint }
    }
    private inner class Cloud : MiHealthImportCloud {
        val rows = mutableListOf<MiHealthImportCloudRow>()
        var uploads = 0
        var reads = 0
        var persist = true
        var error: Exception? = null
        var onRead: suspend (Int) -> Unit = {}
        var afterPersist: suspend () -> Unit = {}
        var omitRange = false
        var controlAvailable = false
        val readRanges = mutableListOf<Pair<Long, Long>>()
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
            untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            onRead(++reads)
            readRanges += fromEpochSeconds to untilEpochSeconds
            return if (omitRange) emptyList() else rows.filter { it.key == key && it.time in fromEpochSeconds until untilEpochSeconds }
        }
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long, maxPages: Int): MiHealthImportHistoryRead {
            if(!controlAvailable) throw UnsupportedOperationException()
            return MiHealthImportHistoryRead(rows.filter {it.key==key && it.time<untilEpochSeconds},1,false)
        }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            uploads++
            assertTrue(experimentalEnabled)
            if (persist) for (item in rows) {
                this.rows.removeAll { it.sid == item.identity.sid && it.key == item.identity.key && it.time == item.identity.time }
                this.rows += row(item)
            }
            afterPersist()
            error?.let { throw it }
            return MiHealthImportUploadResult(true)
        }
    }
    private fun row(source: MiHealthImportRow) = MiHealthImportCloudRow(source.identity.sid, source.identity.key,
        source.identity.time, null, source.value, zoneOffsetSeconds = source.zoneOffsetSeconds)
}
