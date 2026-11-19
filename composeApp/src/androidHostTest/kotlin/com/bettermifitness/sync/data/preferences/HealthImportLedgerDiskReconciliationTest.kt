package com.bettermifitness.sync.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real PreferencesDataStore file reopen; this is not a process-kill or power-loss experiment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthImportLedgerDiskReconciliationTest {
    @Test
    fun pendingBudgetAndAtomicObservedVerificationSurviveClosingAndReopeningTheFile(): Unit = runBlocking {
        val directory = Files.createTempDirectory("healthlab-ledger-reconcile-").toFile()
        val file = directory.resolve("journal.preferences_pb")
        val jobs = mutableListOf<Job>()
        fun open(): Pair<HealthImportLedger, Job> {
            val job = SupervisorJob()
            jobs += job
            val store = PreferenceDataStoreFactory.createWithPath(
                scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { file.absolutePath.toPath() },
            )
            return HealthImportLedger(store) to job
        }
        try {
            val account = "synthetic-disk-account"
            val first = HealthImportJournalEntry("RAW|default|spo2|1700000001|", "source-one",
                setOf("synthetic:one"), zoneOffsetSeconds = 28_800)
            val second = HealthImportJournalEntry("RAW|default|spo2|1700000002|", "source-two",
                setOf("synthetic:two"), zoneOffsetSeconds = 28_800)
            val sources = listOf(first, second)
            val ids = sources.map { it.targetIdentity }.toSet()

            val (writer, writerJob) = open()
            writer.markPending(account, sources)
            val reservation = assertNotNull(writer.reserveSingleRecovery(account,
                writer.pendingSnapshots(account, ids).getValue(first.targetIdentity), first))
            assertTrue(writer.recordRecoveryOutcome(account, reservation, HealthImportRecoveryOutcome.REJECTED,
                accepted = false, businessCode = 100, readbackStatus = "MISSING"))
            assertTrue(file.isFile && file.length() > 0)
            writerJob.cancelAndJoin()

            val (reconciler, reconcilerJob) = open()
            assertEquals(ids, reconciler.pendingIdentities(account, ids))
            val snapshots = reconciler.pendingSnapshots(account, ids)
            assertEquals(reservation.attempt.token, snapshots.getValue(first.targetIdentity).recoveryAttempt?.token)
            assertNull(reconciler.reserveSingleRecovery(account, snapshots.getValue(first.targetIdentity), first))
            assertEquals(ids, reconciler.reconcileObservedPending(account, sources.mapIndexed { index, source ->
                HealthImportPendingReconciliation(snapshots.getValue(source.targetIdentity), source, "observed-$index")
            }))
            reconcilerJob.cancelAndJoin()

            val (reader, _) = open()
            assertTrue(reader.pendingIdentities(account, ids).isEmpty())
            assertEquals(sources.mapIndexed { index, source ->
                HealthImportLedger.identityDigest(source.targetIdentity) to "observed-$index"
            }.toMap(), reader.verifiedFingerprints(account))
            assertTrue(reader.ownedVerifiedFingerprints(account).isEmpty())
            val retained = reader.recoveryAttempts(account, ids).getValue(first.targetIdentity)
            assertEquals(reservation.attempt.token, retained.token)
            assertEquals(HealthImportRecoveryOutcome.VERIFIED.name, retained.outcome)
            assertEquals("VERIFIED", retained.readbackStatus)
            assertEquals(false, retained.accepted)
            assertEquals(100, retained.businessCode)
            reader.resetPendingImports()
            reader.markPending(account, listOf(first))
            assertNull(reader.reserveSingleRecovery(account,
                reader.pendingSnapshots(account, ids).getValue(first.targetIdentity), first))
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            directory.deleteRecursively()
        }
    }
}
