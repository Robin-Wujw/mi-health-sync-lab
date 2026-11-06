package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.HealthInboundMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class MiPendingHeartRateReconciliationTest {
    private val time = 20_000L * 86_400 + 17
    private val row = MiHealthImportRow(MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "heart_rate", time),
        buildJsonObject { put("time", time); put("bpm", 67) }, 0, setOf("synthetic-source"))
    private val actual = MiHealthImportCloudRow("default", "heart_rate", time, null, row.value, zoneOffsetSeconds = 0)
    private val plan = MiHealthImportPlan(HealthInboundMetric.HEART_RATE, listOf(row), emptyList(), 1)

    @Test fun exactNativeMatchResolvesHeartRatePendingWithoutUploadOrOwnership() = runBlocking {
        val ledger = ledger()
        val cloud = Cloud(listOf(actual), complete = false)
        val result = MiPendingSpO2Reconciler(cloud, ledger).reconcile("account", plan, true)
        assertEquals(1, result.verifiedRawRows)
        assertEquals(0, result.attemptedRawRows)
        assertEquals(null, result.pendingReconcile?.readComparison)
        assertTrue(ledger.pendingIdentities("account", setOf(row.identity.stableKey)).isEmpty())
        assertTrue(ledger.ownedVerifiedFingerprints("account").isEmpty())
    }

    @Test fun missingHeartRateOnCappedHistoryStaysPendingAndUnqueried() = runBlocking {
        val ledger = ledger()
        val result = MiPendingSpO2Reconciler(Cloud(emptyList(), false), ledger).reconcile("account", plan, true)
        assertEquals(0, result.verifiedRawRows)
        assertEquals(1, result.pendingReconcile?.unqueriedRows)
        assertEquals(0, result.pendingReconcile?.missingRows)
        assertEquals(1, ledger.pendingIdentities("account", setOf(row.identity.stableKey)).size)
    }

    @Test fun heartRateNeverBorrowsSpo2MinuteNormalization() = runBlocking {
        val floor = time.floorDiv(60) * 60
        val normalized = actual.copy(time = floor, value = buildJsonObject { put("time", floor); put("bpm", 67) })
        val ledger = ledger()
        val result = MiPendingSpO2Reconciler(Cloud(listOf(normalized), true), ledger).reconcile("account", plan, true)
        assertEquals(0, result.verifiedRawRows)
        assertEquals(1, result.pendingReconcile?.missingRows)
        assertTrue(ledger.verifiedFingerprints("account").isEmpty())
    }

    private suspend fun ledger() = HealthImportLedger(object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }).also { it.markPending("account", listOf(MiHealthImportRunner.journalEntry(row))) }

    private class Cloud(val rows: List<MiHealthImportCloudRow>, val complete: Boolean) : MiHealthImportCloud {
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long, untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            assertEquals("heart_rate", key)
            return emptyList()
        }
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long, maxPages: Int): MiHealthImportHistoryRead {
            assertEquals("heart_rate", key)
            return MiHealthImportHistoryRead(rows, maxPages, complete)
        }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult = error("Read-only reconciliation cannot upload")
    }
}
