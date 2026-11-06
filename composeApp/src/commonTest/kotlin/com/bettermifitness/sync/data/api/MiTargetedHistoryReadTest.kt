package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class MiTargetedHistoryReadTest {
    private fun row(time: Long, value: Int = 98) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "spo2", time),
        buildJsonObject { put("time", time); put("spo2", value) }, 28_800, setOf("fixture-$time"))
    private fun MiHealthImportRow.observed() = MiHealthImportCloudRow(identity.sid, identity.key, identity.time,
        identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)
    private class Cloud(val native: suspend (Long, Int) -> MiHealthImportHistoryRead) : MiHealthImportCloud {
        val ends = mutableListOf<Long>()
        var fresh = emptyList<MiHealthImportCloudRow>()
        var uploads = 0
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
            untilEpochSeconds: Long) = fresh
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long, maxPages: Int): MiHealthImportHistoryRead {
            ends += untilEpochSeconds
            return native(untilEpochSeconds, maxPages)
        }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            uploads++
            return MiHealthImportUploadResult(accepted = true, code = 0)
        }
    }

    @Test fun cappedHistoryFindsOlderPendingWithoutRepeatingNewestEndpoint() = runTest {
        val targets = listOf(row(100), row(200), row(300))
        val cloud = Cloud { end, pages ->
            if (end == 301L) MiHealthImportHistoryRead(listOf(row(299).observed()), pages, false, MiHistoryStopReason.PAGE_LIMIT)
            else MiHealthImportHistoryRead(listOf(row(end - 1).observed()), 1, false, MiHistoryStopReason.PAGE_LIMIT)
        }
        val result = readMiTargetedHistory(cloud, targets, 5, 3, 2)
        assertEquals(listOf(301L, 101L, 201L), cloud.ends)
        assertEquals(7, result.pagesRead)
        assertEquals(2, result.followUpQueries)
        assertEquals(0, result.completeQueries)
        assertTrue(result.completedTargetIdentities.isEmpty())
        assertEquals(MiHealthImportVerificationStatus.VERIFIED, MiHealthImportVerifier.verify(targets.first(), result.rows).status)
        assertEquals(0, cloud.uploads)
    }

    @Test fun completedOlderQueryDoesNotDeclareNewerTargetsMissing() = runTest {
        val targets = listOf(row(100), row(200), row(300))
        val cloud = Cloud { end, pages ->
            MiHealthImportHistoryRead(emptyList(), if (end == 301L) pages else 1, end == 101L,
                if (end == 101L) MiHistoryStopReason.END_OF_HISTORY else MiHistoryStopReason.PAGE_LIMIT)
        }
        val result = readMiTargetedHistory(cloud, targets, 5, 1, 2)
        assertEquals(setOf(targets.first().identity), result.completedTargetIdentities)
        assertEquals(1, result.completeQueries)
        assertEquals(2, result.queries)
    }

    @Test fun malformedPaginationAndUnknownStopsNeverTriggerMoreQueries() = runTest {
        for (reason in listOf(MiHistoryStopReason.INVALID_PAGINATION, MiHistoryStopReason.MISSING_CURSOR,
            MiHistoryStopReason.REPEATED_CURSOR, MiHistoryStopReason.UNKNOWN)) {
            val cloud = Cloud { _, _ -> MiHealthImportHistoryRead(emptyList(), 1, false, reason) }
            val result = readMiTargetedHistory(cloud, listOf(row(100), row(200)), 5, 3, 2)
            assertEquals(1, result.queries)
            assertEquals(mapOf(reason to 1), result.stopReasons)
        }
    }

    @Test fun earlierMatchesSurviveFollowUpReadFailure() = runTest {
        val cloud = Cloud { end, pages ->
            if (end == 301L) MiHealthImportHistoryRead(listOf(row(300).observed()), pages, false, MiHistoryStopReason.PAGE_LIMIT)
            else error("private server text")
        }
        val result = readMiTargetedHistory(cloud, listOf(row(100), row(300)), 5, 3, 2)
        assertEquals(1, result.followUpFailures)
        assertEquals(listOf(row(300).observed()), result.rows)
        assertFalse(result.toString().contains("private server text"))
        assertTrue(result.completedTargetIdentities.isEmpty())
    }

    @Test fun followUpCancellationAndAuthExpiryPropagate() = runTest {
        for (error in listOf(CancellationException("cancel"), MiApiException.AuthExpired())) {
            val cloud = Cloud { end, pages ->
                if (end == 301L) MiHealthImportHistoryRead(emptyList(), pages, false, MiHistoryStopReason.PAGE_LIMIT)
                else throw error
            }
            assertSame(error, assertFails { readMiTargetedHistory(cloud, listOf(row(100), row(300)), 5, 3, 2) })
        }
    }

    @Test fun conflictingObservationsAreRetainedAndOldTimestampsDoNotProveCompleteness() = runTest {
        val cloud = Cloud { end, pages ->
            if (end == 301L) MiHealthImportHistoryRead(listOf(row(1).observed(), row(200, 97).observed()), pages, false, MiHistoryStopReason.PAGE_LIMIT)
            else MiHealthImportHistoryRead(listOf(row(100).observed(), row(200).observed()), 1, false, MiHistoryStopReason.PAGE_LIMIT)
        }
        val result = readMiTargetedHistory(cloud, listOf(row(100), row(200), row(300)), 5, 3, 2)
        assertTrue(result.completedTargetIdentities.isEmpty())
        // Out-of-bound rows from the older query cannot manufacture a new observation.
        assertEquals(MiHealthImportVerificationStatus.MISMATCH, MiHealthImportVerifier.verify(row(200), result.rows).status)
    }

    @Test fun operationScopedNativeMatchSurvivesWindowOmissionAndIsInvalidatedBeforeUpload() = runTest {
        val target = row(100)
        val delegate = Cloud { _, _ -> MiHealthImportHistoryRead(listOf(target.observed()), 1, true) }
        val cloud = MiHistoryObservingCloud(delegate)
        cloud.readRawRowsWithoutStart("spo2", 101, 5)
        cloud.beginImportPrecheck(listOf(target))
        assertEquals(listOf(target.observed()), cloud.readRows(MiHealthImportChannel.RAW, "spo2", 0, 101))
        cloud.uploadRows(listOf(target), true)
        assertTrue(cloud.readRows(MiHealthImportChannel.RAW, "spo2", 0, 101).isEmpty())
        assertEquals(1, delegate.uploads)
    }

    @Test fun freshChangedContentRemainsAmbiguousAndFreshTombstoneOrOtherSidCannotBeMasked() = runTest {
        val target = row(100)
        val delegate = Cloud { _, _ -> MiHealthImportHistoryRead(listOf(target.observed()), 1, true) }
        val cloud = MiHistoryObservingCloud(delegate)
        cloud.readRawRowsWithoutStart("spo2", 101, 5)
        cloud.beginImportPrecheck(listOf(target))
        delegate.fresh = listOf(row(100, 96).observed())
        assertEquals(MiHealthImportVerificationStatus.AMBIGUOUS,
            MiHealthImportVerifier.verify(target, cloud.readRows(MiHealthImportChannel.RAW, "spo2", 0, 101)).status)
        for (conflict in listOf(target.observed().copy(deleted = true), target.observed().copy(sid = "watch"))) {
            delegate.fresh = listOf(conflict)
            assertFails { cloud.readRows(MiHealthImportChannel.RAW, "spo2", 0, 101) }
        }
    }

    @Test fun unrelatedTombstonesAndOtherKeysDoNotBlockSelectedTarget() = runTest {
        val target = row(100)
        val unrelated = listOf(row(90).observed().copy(deleted = true), row(91).observed().copy(sid = null),
            target.observed().copy(key = "heart_rate", deleted = true))
        val delegate = Cloud { _, _ -> MiHealthImportHistoryRead(listOf(target.observed()) + unrelated, 1, true) }
        val cloud = MiHistoryObservingCloud(delegate)
        cloud.readRawRowsWithoutStart("spo2", 101, 5)
        delegate.fresh = unrelated
        cloud.beginImportPrecheck(listOf(target))
        assertEquals(MiHealthImportVerificationStatus.VERIFIED,
            MiHealthImportVerifier.verify(target, cloud.readRows(MiHealthImportChannel.RAW, "spo2", 0, 101)).status)
    }

    @Test fun nativeOnlyTombstoneAndUnexpectedSourceRemainBlockedAfterFreshReadOmitsThem() = runTest {
        val target = row(100)
        for (conflict in listOf(target.observed().copy(deleted = true), target.observed().copy(sid = "watch"),
            target.observed().copy(sid = null))) {
            val delegate = Cloud { _, _ -> MiHealthImportHistoryRead(listOf(conflict), 1, true) }
            val cloud = MiHistoryObservingCloud(delegate)
            cloud.readRawRowsWithoutStart("spo2", 101, 5)
            cloud.beginImportPrecheck(listOf(target))
            assertFails { cloud.readRows(MiHealthImportChannel.RAW, "spo2", 0, 101) }
        }
    }
}
