package com.bettermifitness.sync.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiHistoryObservingCloudTest {
    private val minute = 1_800_000_000L

    @Test fun heartRateRetainsNativeEvidenceButInvalidatesOnlySubmittedSeconds() = runBlocking {
        val source = row(minute + 23).let { it.copy(identity = it.identity.copy(key = "heart_rate")) }
        val neighbor = source.copy(identity = source.identity.copy(time = minute + 24))
        val delegate = Cloud(listOf(source.cloud(), neighbor.cloud()))
        val cloud = MiHistoryObservingCloud(delegate)
        cloud.readRawRowsWithoutStart("heart_rate", minute + 60, 32)
        delegate.rows = emptyList()
        assertEquals(2, cloud.readRows(MiHealthImportChannel.RAW, "heart_rate", minute, minute + 60).size)
        cloud.uploadRows(listOf(source), true)
        assertEquals(listOf(neighbor.cloud()), cloud.readRows(MiHealthImportChannel.RAW, "heart_rate", minute, minute + 60))
    }

    @Test
    fun preUploadMinuteEvidenceCannotVerifyThePostWhenFreshReadIsEmpty(): Unit = runBlocking {
        val source = row(minute + 23)
        val delegate = Cloud(listOf(row(minute).cloud()))
        val cloud = MiHistoryObservingCloud(delegate)
        cloud.readRawRowsWithoutStart("spo2", minute + 60, 32)
        delegate.rows = emptyList()
        assertEquals(row(minute).cloud(), MiSpO2MinuteMatchPolicy(listOf(source))
            .find(source, cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60)))
        cloud.uploadRows(listOf(source), true)
        val after = cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60)
        assertTrue(after.isEmpty())
        assertNull(MiSpO2MinuteMatchPolicy(listOf(source)).find(source, after))
        assertEquals(1, delegate.uploads)
    }

    @Test
    fun failedUploadAlsoInvalidatesPrePostMinuteEvidence(): Unit = runBlocking {
        val source = row(minute + 23)
        val delegate = Cloud(listOf(row(minute).cloud())).apply { failUpload = true }
        val cloud = MiHistoryObservingCloud(delegate)
        cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60)
        delegate.rows = emptyList()
        assertFailsWith<IllegalStateException> { cloud.uploadRows(listOf(source), true) }
        assertTrue(cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60).isEmpty())
    }

    @Test
    fun uploadClearsEverySourceAndConflictWithinThatMinuteButRetainsOtherMinutes(): Unit = runBlocking {
        val source = row(minute + 23)
        val outside = row(minute + 60).cloud()
        val delegate = Cloud(listOf(row(minute).cloud(), row(minute + 10).cloud().copy(sid = null),
            row(minute + 40).cloud().copy(deleted = true), row(minute + 30).cloud().copy(sid = "other"), outside))
        val cloud = MiHistoryObservingCloud(delegate)
        cloud.readRawRowsWithoutStart("spo2", minute + 120, 32)
        delegate.rows = emptyList()
        cloud.uploadRows(listOf(source), true)
        assertEquals(listOf(outside), cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 120))
    }

    @Test
    fun freshPostReadbackMayVerifyAndLaterReadsRetainItsConflictingEvidence(): Unit = runBlocking {
        val source = row(minute + 23)
        val floor = row(minute).cloud()
        val delegate = Cloud(listOf(floor))
        val cloud = MiHistoryObservingCloud(delegate)
        cloud.readRawRowsWithoutStart("spo2", minute + 60, 32)
        cloud.uploadRows(listOf(source), true)
        val policy = MiSpO2MinuteMatchPolicy(listOf(source))
        assertEquals(floor, policy.find(source, cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60)))
        delegate.rows = listOf(floor.copy(deleted = true))
        assertNull(policy.find(source, cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60)))
        delegate.rows = listOf(floor)
        assertNull(policy.find(source, cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60)))
    }

    @Test
    fun unrelatedRawAndDailyUploadsDoNotInvalidateSpO2MinuteObservations(): Unit = runBlocking {
        val floor = row(minute).cloud()
        for (submitted in listOf(row(minute + 23).copy(identity = row(minute + 23).identity.copy(key = "heart_rate")),
            row(minute + 23).copy(identity = row(minute + 23).identity.copy(channel = MiHealthImportChannel.DAILY_AGGREGATE)))) {
            val delegate = Cloud(listOf(floor))
            val cloud = MiHistoryObservingCloud(delegate)
            cloud.readRawRowsWithoutStart("spo2", minute + 60, 32)
            delegate.rows = emptyList()
            cloud.uploadRows(listOf(submitted), true)
            assertEquals(listOf(floor), cloud.readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60))
        }
    }

    @Test
    fun aNewOperationCannotReuseAnotherOperationsMinuteCache(): Unit = runBlocking {
        val delegate = Cloud(listOf(row(minute).cloud()))
        val first = MiHistoryObservingCloud(delegate)
        first.readRawRowsWithoutStart("spo2", minute + 60, 32)
        delegate.rows = emptyList()
        assertTrue(MiHistoryObservingCloud(delegate).readRows(MiHealthImportChannel.RAW, "spo2", minute, minute + 60).isEmpty())
    }

    private fun row(time: Long) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "spo2", time),
        buildJsonObject { put("time", time); put("spo2", 98) }, 28_800, setOf("synthetic:$time"),
    )
    private fun MiHealthImportRow.cloud() = MiHealthImportCloudRow(identity.sid, identity.key, identity.time,
        identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)

    private class Cloud(var rows: List<MiHealthImportCloudRow>) : MiHealthImportCloud {
        var uploads = 0
        var failUpload = false
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
            untilEpochSeconds: Long): List<MiHealthImportCloudRow> = rows.filter {
                it.key == key && it.time >= fromEpochSeconds && it.time < untilEpochSeconds
            }
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long, maxPages: Int): MiHealthImportHistoryRead =
            MiHealthImportHistoryRead(rows.filter { it.key == key && it.time < untilEpochSeconds }, 1, true)
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            uploads++
            if (failUpload) error("synthetic response failure")
            return MiHealthImportUploadResult(true, 0)
        }
    }
}
