package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportJournalEntry
import com.bettermifitness.sync.health.HealthInboundMetric
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** All reads use fixtures; the probe must have no path to a remote or journal write. */
class MiPendingImportProbeTest {
    private val account = "synthetic-account"
    private val dayStart = 20_000L * DAY

    @Test
    fun pendingMissingTargetsAreSampledAtOldestMiddleNewestWithoutUnlockingAnyRows() = runBlocking {
        val rows = (0 until 219).map { row(dayStart + 1_000 + it) }
        val pendingRows = rows.drop(72)
        val journal = ReadOnlyJournal(account, pendingRows)
        val cloud = ReadOnlyCloud { rows.take(72).map { it.asCloudRow() } }

        val result = MiPendingImportProbe(cloud, journal).inspect(account, plan(rows))

        assertEquals(147, result.pendingRows)
        assertEquals(147, result.pendingMissing)
        assertEquals(0, result.pendingVerified)
        assertEquals(0, result.pendingUnqueried)
        assertEquals(1, result.broadReadGroups)
        assertEquals(3, result.probeSelected)
        assertEquals(3, result.probeMissing)
        assertEquals(0, result.broadVsNarrowDisagreement)
        assertEquals(
            listOf(pendingRows.first(), pendingRows[pendingRows.size / 2], pendingRows.last()).map { it.identity.time + 1 },
            cloud.reads.drop(1).map { it.until },
        )
        assertEquals(pendingRows.map { it.identity.stableKey }.toSet(), journal.pending)
        assertEquals(4, cloud.reads.size)
        assertTrue(cloud.reads.all { it.from == dayStart - DAY })
        assertEquals(dayStart + DAY + 2, cloud.reads.first().until)
        // A legacy implementation without the new read method is unsupported,
        // not an empty native history that could establish absence.
        assertEquals(147, result.nativeSelected)
        assertEquals(147, result.nativeUnqueried)
        assertEquals(0, result.nativeMissing)
    }

    @Test
    fun narrowReadsCanRevealBroadOmissionsButDoNotMarkPendingRowsVerified() = runBlocking {
        val rows = (0 until 5).map { row(dayStart + 500 + it) }
        val journal = ReadOnlyJournal(account, rows)
        val cloud = ReadOnlyCloud { request ->
            if (request.until == dayStart + DAY + 2) rows.takeLast(2).map { it.asCloudRow() }
            else rows.filter { it.identity.time < request.until }.map { it.asCloudRow() }
        }

        val result = MiPendingImportProbe(cloud, journal).inspect(account, plan(rows))

        assertEquals(2, result.pendingVerified)
        assertEquals(3, result.pendingMissing)
        assertEquals(3, result.probeFound)
        assertEquals(3, result.broadVsNarrowDisagreement)
        assertEquals(0, result.probeMissing)
        assertEquals(rows.map { it.identity.stableKey }.toSet(), journal.pending)
    }

    @Test
    fun mismatchUnknownSidAndDifferentExplicitSidRemainDistinct() = runBlocking {
        val rows = (0 until 5).map { row(dayStart + 100 + it) }
        val journal = ReadOnlyJournal(account, rows)
        val observed = listOf(
            rows[0].asCloudRow(),
            row(rows[1].identity.time, spo2 = 95).asCloudRow(),
            rows[2].asCloudRow().copy(sid = null),
            rows[3].asCloudRow().copy(deleted = true),
            rows[4].asCloudRow().copy(sid = "different-source"),
        )
        val cloud = ReadOnlyCloud { request ->
            if (request.until == dayStart + DAY + 2) observed
            else if (request.until == rows[3].identity.time + 1)
                listOf(row(rows[3].identity.time, spo2 = 96).asCloudRow())
            else listOf(rows[4].asCloudRow().copy(sid = null))
        }

        val result = MiPendingImportProbe(cloud, journal).inspect(account, plan(rows))

        assertEquals(1, result.pendingVerified)
        assertEquals(1, result.pendingMismatch)
        assertEquals(1, result.pendingAmbiguous)
        assertEquals(2, result.pendingMissing)
        assertEquals(2, result.probeSelected)
        assertEquals(0, result.probeFound)
        assertEquals(1, result.probeMismatch)
        assertEquals(1, result.probeAmbiguous)
        assertEquals(2, result.broadVsNarrowDisagreement)
        assertEquals(1, result.missingWithOtherSourceRows)
        assertEquals(1, result.missingWithDeletedRows)
    }

    @Test
    fun failedQueriesAreUnqueriedAndNeverBecomeMissingOrDisagreements() = runBlocking {
        val rows = listOf(row(dayStart + 100), row(dayStart + DAY + 100))
        val cloud = ReadOnlyCloud { request ->
            if (request.from == dayStart || request.until == rows.first().identity.time + 1)
                error("Fixture read failure; this text must not be in the result")
            emptyList()
        }

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(2, result.broadReadGroups)
        assertEquals(1, result.broadReadFailureGroups)
        assertEquals(1, result.pendingMissing)
        assertEquals(1, result.pendingUnqueried)
        assertEquals(1, result.probeSelected)
        assertEquals(1, result.probeUnqueried)
        assertEquals(0, result.probeMissing)
        assertEquals(0, result.broadVsNarrowDisagreement)
        assertTrue(!result.toString().contains("Fixture"))
    }

    @Test
    fun broadReadLimitLeavesOtherGroupsUnqueriedAndCannotTriggerUnboundedReads(): Unit = runBlocking {
        val rows = (0 until 40).map { row(dayStart + it * DAY + 100) }
        val cloud = ReadOnlyCloud { emptyList() }

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows), maxBroadReadGroups = 2)
            .inspect(account, plan(rows.reversed()))

        assertEquals(2, result.broadReadGroups)
        assertEquals(38, result.broadReadLimitedGroups)
        assertEquals(38, result.pendingUnqueried)
        assertEquals(2, result.pendingMissing)
        assertEquals(2, result.probeSelected)
        assertEquals(4, cloud.reads.size)
        assertFailsWith<IllegalArgumentException> {
            MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows), maxBroadReadGroups = 33)
        }
    }

    @Test
    fun duplicateExpectedContentIsCollapsedAndConflictingRevisionsAreNotArbitrarilySelected() = runBlocking {
        val stable = row(dayStart + 100)
        val conflicted = row(dayStart + 200)
        val rows = listOf(stable, stable, conflicted, row(conflicted.identity.time, spo2 = 94))
        val cloud = ReadOnlyCloud { listOf(stable.asCloudRow(), conflicted.asCloudRow()) }

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(2, result.pendingRows)
        assertEquals(1, result.pendingVerified)
        assertEquals(1, result.pendingAmbiguous)
        assertEquals(1, result.sourceCollisionRows)
        assertEquals(0, result.probeSelected)
        assertEquals(1, cloud.reads.size)
    }

    @Test
    fun epochStartAndMidnightProbesRetainValidLowerBoundsAndExclusiveUpperBounds() = runBlocking {
        val rows = listOf(row(0), row(DAY))
        val cloud = ReadOnlyCloud { emptyList() }

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(2, result.probeMissing)
        assertEquals(listOf(0L to 1L, 0L to DAY + 1), cloud.reads.drop(2).map { it.from to it.until })
        assertTrue(cloud.reads.all { it.from >= 0 && it.until > it.from })
    }

    @Test
    fun invalidOrOverflowingBoundsRemainUnqueriedWithoutAnyCloudRequest() = runBlocking {
        val rows = listOf(row(-1), row(Long.MAX_VALUE))
        val cloud = ReadOnlyCloud { error("Invalid bounds must never be queried") }

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(2, result.pendingRows)
        assertEquals(2, result.pendingUnqueried)
        assertEquals(0, result.pendingMissing)
        assertEquals(0, result.probeSelected)
        assertEquals(0, result.broadReadGroups)
        assertTrue(cloud.reads.isEmpty())
    }

    @Test
    fun noPendingTargetsCauseNoCloudReadsAndExposeNoSourceIdentifiers() = runBlocking {
        val rows = listOf(row(dayStart + 100))
        val cloud = ReadOnlyCloud { error("No query should run") }
        val journal = ReadOnlyJournal(account, emptyList())

        val result = MiPendingImportProbe(cloud, journal).inspect(account, plan(rows))

        assertEquals(MiPendingImportProbeResult(), result)
        assertTrue(cloud.reads.isEmpty())
        assertTrue(!result.toString().contains(account))
        assertTrue(rows.none { result.toString().contains(it.identity.stableKey) })
    }

    @Test
    fun cancellationAndExpiredSessionsPropagateForCallerCancellationAndSafeRefresh() = runBlocking {
        val rows = listOf(row(dayStart + 100))
        val journal = ReadOnlyJournal(account, rows)
        val cancelled = CancellationException("Fixture cancellation")
        val expired = MiApiException.AuthExpired()
        assertSame(cancelled, assertFailsWith<CancellationException> {
            MiPendingImportProbe(ReadOnlyCloud { throw cancelled }, journal).inspect(account, plan(rows))
        })
        assertSame(expired, assertFailsWith<MiApiException.AuthExpired> {
            MiPendingImportProbe(ReadOnlyCloud { throw expired }, journal).inspect(account, plan(rows))
        })
        assertEquals(rows.map { it.identity.stableKey }.toSet(), journal.pending)
    }

    @Test
    fun nativeReadCanRevealAll132WindowOmissionsWithoutUnlockingPendingRows() = runBlocking {
        val rows = (0 until 195).map { row(dayStart + 1_000 + it) }
        val pendingRows = rows.drop(63)
        val journal = ReadOnlyJournal(account, pendingRows)
        val cloud = NativeReadCloud(
            onNative = { MiHealthImportHistoryRead(pendingRows.map { it.asCloudRow() }, 2, complete = true) },
            onRead = { rows.take(63).map { it.asCloudRow() } },
        )

        val result = MiPendingImportProbe(cloud, journal).inspect(account, plan(rows))

        assertEquals(132, result.pendingMissing)
        assertEquals(3, result.probeMissing)
        assertEquals(132, result.nativeSelected)
        assertEquals(132, result.nativeFound)
        assertEquals(0, result.nativeMissing)
        assertEquals(0, result.nativeUnqueried)
        assertEquals(2, result.nativePagesRead)
        assertEquals(1, result.nativeCompleteQueries)
        assertEquals(listOf(NativeRead("spo2", pendingRows.last().identity.time + 1, 5)), cloud.nativeReads)
        assertEquals(pendingRows.map { it.identity.stableKey }.toSet(), journal.pending)
    }

    @Test
    fun nativeCapCanReportObservedMatchesButUnreturnedRowsRemainUnqueried() = runBlocking {
        val rows = (0 until 4).map { row(dayStart + 100 + it) }
        val cloud = NativeReadCloud(
            onNative = { MiHealthImportHistoryRead(listOf(rows.first().asCloudRow()), 5, complete = false) },
            onRead = { emptyList() },
        )

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(4, result.nativeSelected)
        assertEquals(1, result.nativeFound)
        assertEquals(3, result.nativeUnqueried)
        assertEquals(0, result.nativeMissing)
        assertEquals(5, result.nativePagesRead)
        assertEquals(0, result.nativeCompleteQueries)
    }

    @Test
    fun onlyAnExplicitlyCompleteNativeReadCanCountUnreturnedTargetsAsMissing() = runBlocking {
        val rows = (0 until 4).map { row(dayStart + 100 + it) }
        val cloud = NativeReadCloud(
            onNative = { MiHealthImportHistoryRead(emptyList(), 1, complete = true) },
            onRead = { emptyList() },
        )

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(4, result.nativeMissing)
        assertEquals(0, result.nativeUnqueried)
        assertEquals(1, result.nativeCompleteQueries)
    }

    @Test
    fun nativeMismatchAndUnknownSidStayDistinctEvenDuringIncompleteTraversal() = runBlocking {
        val rows = (0 until 3).map { row(dayStart + 100 + it) }
        val cloud = NativeReadCloud(
            onNative = { MiHealthImportHistoryRead(listOf(
                row(rows[0].identity.time, spo2 = 95).asCloudRow(),
                rows[1].asCloudRow().copy(sid = null),
            ), 5, complete = false) },
            onRead = { emptyList() },
        )

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(1, result.nativeMismatch)
        assertEquals(1, result.nativeAmbiguous)
        assertEquals(1, result.nativeUnqueried)
        assertEquals(0, result.nativeFound)
        assertEquals(0, result.nativeMissing)
    }

    @Test
    fun otherSourceAndExactTombstoneHintsCanOverlapWithoutExposingIdentifiers() = runBlocking {
        val rows = (0 until 3).map { row(dayStart + 100 + it) }
        val cloud = ReadOnlyCloud { listOf(
            rows[0].asCloudRow().copy(sid = "synthetic-watch-origin"),
            rows[0].asCloudRow().copy(deleted = true),
            rows[1].asCloudRow().copy(deleted = true, sid = "synthetic-watch-origin"),
            // A tombstone from another SID cannot establish our exact deletion.
            rows[2].asCloudRow().copy(sid = null),
        ) }

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(2, result.pendingMissing)
        assertEquals(1, result.pendingAmbiguous)
        assertEquals(1, result.missingWithOtherSourceRows)
        assertEquals(1, result.missingWithDeletedRows)
        assertTrue(!result.toString().contains("synthetic-watch-origin"))
        assertTrue(!result.toString().contains(account))
        assertTrue(rows.none { result.toString().contains(it.identity.stableKey) })
    }

    @Test
    fun mixedMetricPlansMakeAtMostOneNativeQueryAndLeaveOtherKeysUnqueried() = runBlocking {
        val oxygen = row(dayStart + 200)
        val heartRate = row(dayStart + 100).let { it.copy(
            identity = it.identity.copy(key = "heart_rate"),
            value = buildJsonObject { put("time", it.identity.time); put("bpm", 65) },
        ) }
        val rows = listOf(oxygen, heartRate)
        val cloud = NativeReadCloud(
            onNative = { MiHealthImportHistoryRead(emptyList(), 1, complete = true) },
            onRead = { emptyList() },
        )

        val result = MiPendingImportProbe(cloud, ReadOnlyJournal(account, rows)).inspect(account, plan(rows))

        assertEquals(2, result.nativeSelected)
        assertEquals(1, result.nativeMissing)
        assertEquals(1, result.nativeUnqueried)
        assertEquals(listOf(NativeRead("heart_rate", heartRate.identity.time + 1, 5)), cloud.nativeReads)
    }

    @Test
    fun nativeErrorsAreUnqueriedAndAuthenticationOrCancellationStillPropagate(): Unit = runBlocking {
        val rows = listOf(row(dayStart + 100))
        val journal = ReadOnlyJournal(account, rows)
        val failed = NativeReadCloud(onNative = { error("Read failure: private fixture text") }, onRead = { emptyList() })
        val result = MiPendingImportProbe(failed, journal).inspect(account, plan(rows))
        assertEquals(1, result.nativeUnqueried)
        assertEquals(0, result.nativeMissing)
        assertEquals(0, result.nativePagesRead)
        assertTrue(!result.toString().contains("private fixture"))

        val expired = MiApiException.AuthExpired()
        val cancelled = CancellationException("Fixture cancellation")
        assertSame(expired, assertFailsWith<MiApiException.AuthExpired> {
            MiPendingImportProbe(NativeReadCloud(onNative = { throw expired }, onRead = { emptyList() }), journal)
                .inspect(account, plan(rows))
        })
        assertSame(cancelled, assertFailsWith<CancellationException> {
            MiPendingImportProbe(NativeReadCloud(onNative = { throw cancelled }, onRead = { emptyList() }), journal)
                .inspect(account, plan(rows))
        })
    }

    private fun row(time: Long, spo2: Int = 98) = MiHealthImportRow(
        identity = MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "spo2", time),
        value = buildJsonObject { put("time", time); put("spo2", spo2) },
        zoneOffsetSeconds = 28_800,
        sourceRecordKeys = setOf("synthetic-source:$time"),
    )

    private fun MiHealthImportRow.asCloudRow() = MiHealthImportCloudRow(
        sid = identity.sid, key = identity.key, time = identity.time, tag = identity.tag,
        value = value, zoneOffsetSeconds = zoneOffsetSeconds,
    )

    private fun plan(rows: List<MiHealthImportRow>) = MiHealthImportPlan(
        HealthInboundMetric.OXYGEN_SATURATION, rows, emptyList(), rows.size,
    )

    private data class Read(val channel: MiHealthImportChannel, val key: String, val from: Long, val until: Long)

    private data class NativeRead(val key: String, val until: Long, val maxPages: Int)

    private open class ReadOnlyCloud(private val onRead: (Read) -> List<MiHealthImportCloudRow>) : MiHealthImportCloud {
        val reads = mutableListOf<Read>()
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
            untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            val request = Read(channel, key, fromEpochSeconds, untilEpochSeconds)
            reads += request
            return onRead(request)
        }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult =
            error("A pending probe must never upload")
    }

    private class NativeReadCloud(
        private val onNative: (NativeRead) -> MiHealthImportHistoryRead,
        onRead: (Read) -> List<MiHealthImportCloudRow>,
    ) : ReadOnlyCloud(onRead) {
        val nativeReads = mutableListOf<NativeRead>()
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long,
            maxPages: Int): MiHealthImportHistoryRead {
            val request = NativeRead(key, untilEpochSeconds, maxPages)
            nativeReads += request
            return onNative(request)
        }
    }

    private class ReadOnlyJournal(private val expectedAccount: String, rows: List<MiHealthImportRow>) : HealthImportJournal {
        val pending = rows.map { it.identity.stableKey }.toSet()
        override suspend fun pendingIdentities(account: String, identities: Set<String>): Set<String> {
            assertEquals(expectedAccount, account)
            return identities.intersect(pending)
        }
        override suspend fun markPending(account: String, entries: List<HealthImportJournalEntry>): Unit =
            error("A pending probe must not create or change journal entries")
        override suspend fun markVerified(account: String, entries: List<HealthImportJournalEntry>): Unit =
            error("A pending probe must not clear retry locks or mark ownership")
        override suspend fun verifiedFingerprints(account: String): Map<String, String> =
            error("A pending probe only needs the pending identity set")
    }

    private companion object { const val DAY = 86_400L }
}
