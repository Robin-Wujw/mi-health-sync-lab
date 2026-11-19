package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.*
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.SportFileTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MiSleepSourceMigrationTest {
    private val account = "synthetic-account"
    private val source = "com.huami.watch.hmwatchmanager"
    private val start = 1_800_000_000L
    private fun origin(key: String) = HealthRecordOrigin(key, source, null, 1, 1)
    private fun legacyPlan(heartValue: Long = 60): MiHealthImportPlan {
        val sleep = HealthInboundSleepRecord(origin("sleep"), start * 1000, (start + 600) * 1000, 0, 0,
            listOf(HealthInboundSleepStage(start * 1000, (start + 600) * 1000, HealthInboundSleepStageKind.LIGHT)))
        val heart = HealthInboundHeartRateRecord(origin("heart"), start * 1000, (start + 600) * 1000, 0, 0,
            listOf(HealthInboundHeartRateSample((start + 60) * 1000, heartValue)))
        return MiSleepVitalsMapper.enrich(MiHealthImportMapper.sleep(listOf(sleep), source), listOf(sleep), source,
            listOf(heart), emptyList())
    }

    @Test fun replacementFileAndSummaryAreVerifiedBeforeRetirementAndRepeatDoesNotWrite() = runTest {
        val f = Fixture(); f.seed()
        val prepared = f.maintain()
        assertEquals(1, prepared.rawRows.size)
        assertTrue(prepared.issues.none { it.severity == MiHealthImportIssueSeverity.REJECTED })
        val active = f.cloud.rows.filterNot { it.deleted }.single()
        assertEquals(MI_SLEEP_FILE_IMPORT_SID, active.sid)
        assertEquals(JsonPrimitive(44), active.value["avg_hrv"])
        assertEquals(JsonPrimitive("retained"), active.value["extension"])
        assertEquals(listOf("file", "summary", "retire"), f.events)
        assertTrue(f.migrations.load(account, f.legacy.identity.stableKey)!!.verified)
        assertEquals(1, f.maintain().rawRows.size)
        assertEquals(listOf("file", "summary", "retire"), f.events)
        assertEquals(1, f.cloud.rows.count { !it.deleted })
        assertTrue(f.migrations.load(account, f.legacy.identity.stableKey)!!.originalValue.contains("extension"))
    }

    @Test fun fileReadbackFailurePreservesLegacyAndDoesNotSubmitOrRetireSummary() = runTest {
        val f = Fixture(); f.seed(); f.files.store = false
        assertTrue(f.maintain().rawRows.isEmpty())
        assertEquals(listOf("file"), f.events)
        assertEquals(MI_HEALTH_CONNECT_IMPORT_SID, f.cloud.rows.single().sid)
        assertFalse(f.migrations.load(account, f.legacy.identity.stableKey)!!.attempted)
    }

    @Test fun foreignOrChangedLegacyCannotBeMigratedEvenWhenTargetTimeMatches() = runTest {
        for (foreign in listOf(false, true)) {
            val f = Fixture(); f.seed(owned = !foreign)
            if (!foreign) f.cloud.rows = f.cloud.rows.map {
                it.copy(value = JsonObject(it.value + ("extension" to JsonPrimitive("changed"))))
            }
            assertTrue(f.maintain().rawRows.isEmpty())
            assertTrue(f.events.isEmpty())
            assertEquals(0, f.cloud.retirements)
        }
    }

    @Test fun legacyChangeDuringReplacementNeverAuthorizesRetirement() = runTest {
        val f = Fixture(); f.seed(); f.cloud.changeLegacyOnUpload = true
        assertTrue(f.maintain().rawRows.isEmpty())
        assertEquals(listOf("file", "summary"), f.events)
        assertEquals(0, f.cloud.retirements)
        assertEquals(2, f.cloud.rows.count { !it.deleted })
    }

    @Test fun lostRetirementResponseIsResolvedByReadbackWithoutRepeatingDeletion() = runTest {
        val f = Fixture(); f.seed(); f.cloud.loseRetirementResponse = true
        assertEquals(1, f.maintain().rawRows.size)
        assertEquals(1, f.maintain().rawRows.size)
        assertEquals(1, f.cloud.retirements)
    }

    @Test fun unknownUnappliedRetirementIsNeverReplayedByAnotherSync() = runTest {
        val f = Fixture(); f.seed(); f.cloud.applyRetirement = false
        assertTrue(f.maintain().rawRows.isEmpty())
        assertTrue(f.maintain().rawRows.isEmpty())
        assertEquals(1, f.cloud.retirements)
        assertEquals(1, f.files.uploads)
        assertEquals(2, f.cloud.rows.count { !it.deleted })
    }

    @Test fun pendingLegacySummaryBlocksChangingItsIdentity() = runTest {
        val f = Fixture(); f.seed()
        f.imports.markPending(account, listOf(MiHealthImportRunner.journalEntry(f.legacy)))
        assertTrue(f.maintain().rawRows.isEmpty())
        assertTrue(f.events.isEmpty())
    }

    @Test fun unknownLegacyFileBodyCannotBeBypassedWithTheNativeSource() = runTest {
        val f = Fixture(); f.seed()
        f.fileJournal.change(account, f.legacy.identity.stableKey, null,
            SleepFileEntry("synthetic-content", "synthetic-body", start + 601, 1, attempted = true))
        assertTrue(f.maintain().rawRows.isEmpty())
        assertTrue(f.events.isEmpty())
    }

    @Test fun mappingKeepsDailyAggregateIdentityAndMapsHrvToTheSameRawSource() {
        val p = legacyPlan()
        val mapped = p.withNativeSleepSource()
        assertTrue(mapped.rawRows.all { it.identity.sid == MI_SLEEP_FILE_IMPORT_SID })
        assertEquals(p.aggregateRows, mapped.aggregateRows)
        assertEquals(mapped.rawRows.single().identity, mapped.sleepFiles.single().identity)
        val hrv = p.copy(rows = p.rawRows.map { it.copy(enrichment = MiHealthImportEnrichment.SLEEP_HRV) }, sleepFiles = emptyList())
        assertEquals(MI_SLEEP_FILE_IMPORT_SID, hrv.withNativeSleepSource().rawRows.single().identity.sid)
    }

    @Test fun completedMigrationDoesNotRetireARecreatedLegacySummary() = runTest {
        val f = Fixture(); f.seed(); assertEquals(1, f.maintain().rawRows.size)
        f.cloud.rows = f.cloud.rows.map { if (it.sid == MI_HEALTH_CONNECT_IMPORT_SID) it.copy(deleted = false) else it }
        assertTrue(f.maintain().rawRows.isEmpty())
        assertEquals(1, f.cloud.retirements)
    }

    @Test fun lateHeartRevisionUsesANewFileAndDoesNotRetireLegacyAgain() = runTest {
        val f = Fixture(); f.seed(); f.sync(f.maintain())
        f.sync(f.maintain(legacyPlan(70).withNativeSleepSource()))
        val active = f.cloud.rows.filterNot { it.deleted }.single()
        assertEquals(JsonPrimitive(70), active.value["avg_hr"])
        assertEquals(JsonPrimitive(44), active.value["avg_hrv"])
        assertEquals(JsonPrimitive("retained"), active.value["extension"])
        assertEquals(2, f.files.uploads)
        assertEquals(1, f.cloud.retirements)
    }

    @Test fun subsequentHrvPassKeepsTheVerifiedVitalFileReference() = runTest {
        val f = Fixture(); f.seed(); f.sync(f.maintain())
        val before = f.cloud.rows.single { !it.deleted }.value
        val hrv = legacyPlan().let { p -> p.copy(rows = p.rawRows.map { row -> row.copy(
            value = JsonObject(row.value.filterKeys { it !in setOf("avg_hr", "min_hr", "max_hr") } +
                ("avg_hrv" to JsonPrimitive(55))), enrichment = MiHealthImportEnrichment.SLEEP_HRV) }, sleepFiles = emptyList()) }
            .withNativeSleepSource()
        f.sync(f.maintain(hrv))
        val active = f.cloud.rows.single { !it.deleted }.value
        assertEquals(JsonPrimitive(55), active["avg_hrv"])
        assertEquals(before["protoTime"], active["protoTime"])
        assertEquals(before["version"], active["version"])
        assertEquals(before["avg_hr"], active["avg_hr"])
        assertEquals(1, f.files.uploads)
        assertEquals(1, f.cloud.retirements)
    }

    private inner class Fixture {
        val events = mutableListOf<String>()
        val store = Store()
        val imports = HealthImportLedger(store)
        val fileJournal = SleepFileLedger(store)
        val migrations = SleepSourceMigrationLedger(store)
        val files = Files(events)
        val cloud = Cloud(events)
        val plan = legacyPlan().withNativeSleepSource()
        val legacy = legacyPlan().rawRows.single().let { row -> row.copy(value = JsonObject(
            row.value.filterKeys { it !in setOf("avg_hr", "min_hr", "max_hr") } + mapOf(
                "avg_hrv" to JsonPrimitive(44), "extension" to JsonPrimitive("retained")))) }
        suspend fun seed(owned: Boolean = true) {
            cloud.rows = listOf(legacy.toCloud())
            imports.markVerified(account, listOf(MiHealthImportRunner.journalEntry(legacy).copy(ownedUpload = owned)))
        }
        suspend fun maintain(selected: MiHealthImportPlan = plan) =
            MiSleepSourceMigration(cloud, imports, fileJournal, migrations, { files }).maintain(account, selected)
        suspend fun sync(prepared: MiHealthImportPlan) = MiHealthImportWorkflow(cloud, imports).run(account, prepared)
    }

    private class Files(private val events: MutableList<String>) : SportFileTransport {
        val bodies = mutableMapOf<FdsClient.SportFileRequest, ByteArray>()
        val requests = mutableMapOf<String, FdsClient.SportFileRequest>()
        var uploads = 0
        var store = true
        override suspend fun prepareSportFile(request: FdsClient.SportFileRequest): FdsClient.UploadMeta {
            assertEquals(MI_SLEEP_FILE_IMPORT_SID, request.sid)
            val key = "synthetic-" + requests.size
            requests[key] = request
            return FdsClient.UploadMeta("https://synthetic.invalid/object", "PUT", key)
        }
        override suspend fun uploadPreparedSportFile(meta: FdsClient.UploadMeta, bytes: ByteArray) {
            events += "file"; uploads++
            if (store) bodies[requests.getValue(meta.objectKey)] = bytes.copyOf()
        }
        override suspend fun downloadSportFile(request: FdsClient.SportFileRequest): ByteArray =
            bodies[request]?.copyOf() ?: throw MiApiException.Server(404, "synthetic missing")
    }

    private class Cloud(private val events: MutableList<String>) : MiHealthImportCloud {
        var rows = emptyList<MiHealthImportCloudRow>()
        var retirements = 0
        var applyRetirement = true
        var loseRetirementResponse = false
        var changeLegacyOnUpload = false
        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long, untilEpochSeconds: Long) =
            rows.filter { it.key == key && it.time >= fromEpochSeconds && it.time < untilEpochSeconds }
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            events += "summary"
            this.rows = this.rows.filterNot { existing -> rows.any {
                it.identity.sid == existing.sid && it.identity.time == existing.time } } +
                rows.map { it.toCloud() }
            if (changeLegacyOnUpload) this.rows = this.rows.map { if (it.sid != MI_HEALTH_CONNECT_IMPORT_SID) it else
                it.copy(value = JsonObject(it.value + ("extension" to JsonPrimitive("changed")))) }
            return MiHealthImportUploadResult(true)
        }
        override suspend fun retireLegacySleep(identity: MiHealthImportIdentity): MiHealthImportUploadResult {
            events += "retire"; retirements++
            assertEquals(MI_HEALTH_CONNECT_IMPORT_SID, identity.sid)
            assertTrue(rows.any { !it.deleted && it.sid == MI_SLEEP_FILE_IMPORT_SID && "version" in it.value })
            if (applyRetirement) rows = rows.map { if (it.sid == identity.sid && it.time == identity.time) it.copy(deleted = true) else it }
            if (loseRetirementResponse) error("synthetic response loss")
            return MiHealthImportUploadResult(true)
        }
    }

    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
    private companion object {
        fun MiHealthImportRow.toCloud() = MiHealthImportCloudRow(identity.sid, identity.key, identity.time, identity.tag,
            value, zoneOffsetSeconds = zoneOffsetSeconds)
    }
}
