package com.bettermifitness.sync.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bettermifitness.sync.data.api.MI_HEALTH_CONNECT_IMPORT_SID
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportHistoryRead
import com.bettermifitness.sync.data.api.MiHealthImportIdentity
import com.bettermifitness.sync.data.api.MiHealthImportIssue
import com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportUploadResult
import com.bettermifitness.sync.data.api.MiHrvImportMapper
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthInboundHrvRecord
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStage
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import com.bettermifitness.sync.health.HealthRecordOrigin
import com.bettermifitness.sync.sync.SyncOutcome
import com.bettermifitness.sync.sync.SyncOutcomeLabels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiHealthImportWorkflowTest : com.bettermifitness.sync.LocalizedTest() {
    private val account = "synthetic-account"
    private val source = "com.huami.watch.hmwatchmanager"
    private val day = 20_000L * 86_400L

    @Test
    fun userOneDayCountsRepair194UnchangedOwnedPointsAndKeep1228ExistingMatches() = runBlocking {
        val rows = (0 until 1422).map { row(it) }
        val old = rows.drop(1228).map { oldValue(it) }
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, old.map { MiHealthImportRunner.journalEntry(it).copy(ownedUpload = true) })
        val cloud = Cloud().apply { persist(rows.take(1228) + old) }
        cloud.beforeUpload = { batch ->
            assertEquals(batch.size, ledger.pendingSnapshots(account, batch.map { it.identity.stableKey }.toSet()).size)
        }
        val plan = plan(rows, listOf(excludedSecond())).copy(inputRecordCount = 1390)
        val result = workflow(cloud, ledger).run(account, plan)
        val diagnostic = assertNotNull(result.diagnostic)
        assertEquals(1422, result.verifiedRawCount)
        assertEquals(1228, result.alreadyPresentRawCount)
        assertEquals(194, result.newlyUploadedRawCount)
        assertEquals(194, diagnostic.attemptedOwnedRevisionRows)
        assertEquals(194, diagnostic.verifiedOwnedRevisionRows)
        assertEquals(1, diagnostic.excludedSourcePoints)
        assertEquals(194, cloud.uploads.flatten().size)
        assertTrue(cloud.uploads.all { it.size <= 50 })
        assertTrue(cloud.uploads.flatten().all { it.identity in old.map { previous -> previous.identity } })
        assertNull(diagnostic.failureReason)
        val again = workflow(cloud, ledger).run(account, plan)
        assertEquals(1422, again.alreadyPresentRawCount)
        assertEquals(0, again.newlyUploadedRawCount)
        assertEquals(194, cloud.uploads.flatten().size)
    }

    @Test
    fun latestFeedbackRepairs163OwnedConflictsAlongside88NewPoints() = runBlocking {
        val rows = (0 until 1426).map { row(it) }
        val previous = rows.subList(1175, 1175 + 163).map { oldValue(it) }
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, previous.map { MiHealthImportRunner.journalEntry(it).copy(ownedUpload = true) })
        val cloud = Cloud().apply { persist(rows.take(1175) + previous) }
        val result = workflow(cloud, ledger).run(account, plan(rows, listOf(excludedSecond())))
        assertEquals(1426, result.verifiedRawCount)
        assertEquals(1175, result.alreadyPresentRawCount)
        assertEquals(251, result.newlyUploadedRawCount)
        assertEquals(163, result.diagnostic?.verifiedOwnedRevisionRows)
        assertEquals(0, result.diagnostic?.blockedConflictRows)
        assertEquals(251, cloud.uploads.flatten().size)
    }

    @Test
    fun sameFeedbackWithoutOwnershipKeeps163ConflictsAndStillUploads88FreshPoints() = runBlocking {
        val rows = (0 until 1426).map { row(it) }
        val previous = rows.subList(1175, 1175 + 163).map { oldValue(it) }
        val cloud = Cloud().apply { persist(rows.take(1175) + previous) }
        val error = assertFailsWith<MiHealthImportRunException> {
            workflow(cloud, HealthImportLedger(MemoryStore())).run(account, plan(rows, listOf(excludedSecond())))
        }
        val diagnostic = assertNotNull(error.diagnostic)
        assertEquals(1263, error.verifiedRawCount)
        assertEquals(1175, diagnostic.alreadyPresentRawRows)
        assertEquals(88, diagnostic.newlyVerifiedRawRows)
        assertEquals(163, diagnostic.blockedConflictRows)
        assertEquals(163, diagnostic.unownedConflictRows)
        assertEquals(88, cloud.uploads.flatten().size)
        assertEquals(163, diagnostic.precheckMismatchFields["bpm"])
    }

    @Test
    fun readOnlyPreflightSeparatesOwnedAndUnownedConflictsWithoutChangingTheJournal() = runBlocking {
        val rows = (0 until 4).map { row(it) }
        val previous = rows.take(3).map { oldValue(it) }
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous[0]).copy(ownedUpload = true)))
        ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous[1]).copy(ownedUpload = true)))
        val cloud = Cloud().apply { persist(listOf(previous[0], oldValue(rows[1], 99), previous[2], rows[3])) }
        val before = ledger.verifiedFingerprints(account)
        val diagnostic = inspectMiHealthImportPreflight(account, plan(rows), cloud, ledger)
        assertEquals(1, diagnostic.alreadyPresentRawRows)
        assertEquals(3, diagnostic.blockedConflictRows)
        assertEquals(1, diagnostic.eligibleOwnedRevisionRows)
        assertEquals(1, diagnostic.changedOwnedBaselineRows)
        assertEquals(1, diagnostic.unownedConflictRows)
        assertEquals(0, diagnostic.attemptedRawRows)
        assertTrue(cloud.uploads.isEmpty())
        assertEquals(before, ledger.verifiedFingerprints(account))
        assertTrue(ledger.pendingIdentities(account, rows.map { it.identity.stableKey }.toSet()).isEmpty())
    }

    @Test
    fun normalHrvSyncUpdatesOnlyItsOwnedSummaryAndPreservesTheSleepAndServerFields() = runBlocking {
        val current = hrvRow(60, 40, 80)
        val previous = hrvRow(50, 30, 70).let { it.copy(value = kotlinx.serialization.json.JsonObject(
            it.value + ("server_extension" to kotlinx.serialization.json.JsonPrimitive("retained")))) }
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = true)))
        val cloud = Cloud().apply { persist(listOf(previous)) }
        val plan = plan(listOf(current)).copy(metric = HealthInboundMetric.HRV)
        val dryRun = inspectMiHealthImportPreflight(account, plan, cloud, ledger)
        assertEquals(1, dryRun.eligibleOwnedRevisionRows)
        val result = workflow(cloud, ledger).run(account, plan)
        assertEquals(1, result.verifiedRawCount)
        assertEquals(1, result.diagnostic?.verifiedOwnedRevisionRows)
        assertEquals(1, cloud.uploads.size)
        assertEquals(previous.value.filterKeys { it !in setOf("avg_hrv", "min_hrv", "max_hrv") },
            cloud.stored.single().value.filterKeys { it !in setOf("avg_hrv", "min_hrv", "max_hrv") })
        val again = workflow(cloud, ledger).run(account, plan)
        assertEquals(0, again.newlyUploadedRawCount)
        assertEquals(1, cloud.uploads.size)
    }

    @Test
    fun hrvRevisionCannotReplaceUnownedPartialOrChangedSleepSummaries() = runBlocking {
        for (kind in listOf("unowned", "partial-summary", "sleep-changed", "other-origin", "deleted")) {
            val current = hrvRow(60, 40, 80)
            val previous = hrvRow(50, 30, 70).let { row -> when (kind) {
                "partial-summary" -> row.copy(value = kotlinx.serialization.json.JsonObject(row.value - "min_hrv"))
                "sleep-changed" -> row.copy(value = kotlinx.serialization.json.JsonObject(row.value +
                    ("duration" to kotlinx.serialization.json.JsonPrimitive(100))))
                else -> row
            } }
            val ledger = HealthImportLedger(MemoryStore())
            ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = kind != "unowned")))
            val cloud = Cloud().apply { persist(listOf(previous)) }
            if (kind == "other-origin") cloud.stored += previous.cloud().copy(sid = "other")
            if (kind == "deleted") cloud.stored += previous.cloud().copy(deleted = true)
            assertFailsWith<MiHealthImportRunException> {
                workflow(cloud, ledger).run(account, plan(listOf(current)).copy(metric = HealthInboundMetric.HRV))
            }
            assertTrue(cloud.uploads.isEmpty(), kind)
        }
    }

    @Test
    fun hrvRevisionIsCancelledWhenCloudChangesBeforeWrite() = runBlocking {
        val current = hrvRow(60, 40, 80)
        val previous = hrvRow(50, 30, 70)
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = true)))
        val cloud = Cloud().apply { persist(listOf(previous)) }
        cloud.onRead = { if (cloud.reads == 2) cloud.persist(listOf(hrvRow(55, 30, 70))) }
        assertFailsWith<MiHealthImportRunException> {
            workflow(cloud, ledger).run(account, plan(listOf(current)).copy(metric = HealthInboundMetric.HRV))
        }
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test
    fun oxygenReadOnlyPreflightRecognizesStrictMinuteAliasesWithoutSavingThem() = runBlocking {
        val target = row(0, HealthInboundMetric.OXYGEN_SATURATION).let { it.copy(
            identity = it.identity.copy(time = it.identity.time + 5), value = buildJsonObject {
                put("time", it.identity.time + 5); put("spo2", 97)
            }) }
        val minute = row(0, HealthInboundMetric.OXYGEN_SATURATION)
        val ledger = HealthImportLedger(MemoryStore())
        val cloud = Cloud().apply { persist(listOf(minute)) }
        val diagnostic = inspectMiHealthImportPreflight(account,
            plan(listOf(target)).copy(metric = HealthInboundMetric.OXYGEN_SATURATION), cloud, ledger)
        assertEquals(1, diagnostic.verifiedRawRows)
        assertEquals(0, diagnostic.notAttemptedRawRows)
        assertTrue(ledger.verifiedFingerprints(account).isEmpty())
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test
    fun oneDayUnownedConflictDoesNotPreventIndependentNewPointsFromSyncing() = runBlocking {
        val matched = row(0)
        val conflict = row(1)
        val fresh = row(2)
        val previous = oldValue(conflict)
        val cloud = Cloud().apply { persist(listOf(matched, previous)) }
        val error = assertFailsWith<MiHealthImportRunException> {
            workflow(cloud, HealthImportLedger(MemoryStore())).run(account,
                plan(listOf(matched, conflict, fresh), listOf(excludedSecond())))
        }
        assertEquals(2, error.verifiedRawCount)
        assertEquals(1, error.diagnostic?.blockedConflictRows)
        assertEquals(listOf(fresh), cloud.uploads.flatten())
        assertEquals(previous.value, cloud.stored.single { it.time == conflict.identity.time }.value)
    }

    @Test
    fun ownedBaselineChangingBetweenPrecheckAndWriteIsNeverOverwritten() = runBlocking {
        val target = row(0)
        val previous = oldValue(target)
        val cloud = Cloud().apply { persist(listOf(previous)) }
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = true)))
        cloud.onRead = { if (cloud.reads == 2) cloud.persist(listOf(oldValue(target, 99))) }
        val error = assertFailsWith<MiHealthImportRunException> {
            workflow(cloud, ledger).run(account, plan(listOf(target)))
        }
        assertEquals(MiHealthImportFailureReason.CONFLICT, error.reason)
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(ledger.pendingIdentities(account, setOf(target.identity.stableKey)).isEmpty())
    }

    @Test
    fun lostOwnershipTombstoneOtherSourceAndChangedTimezoneBlockAutomaticRevision() = runBlocking {
        for (kind in listOf("observed", "deleted", "other-source", "timezone", "missing-zone")) {
            val target = row(0)
            val previous = oldValue(target)
            val cloud = Cloud().apply { persist(listOf(previous)) }
            val ledger = HealthImportLedger(MemoryStore())
            ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = kind != "observed")))
            if (kind == "deleted") cloud.stored += previous.cloud().copy(deleted = true)
            if (kind == "other-source") cloud.stored += previous.cloud().copy(sid = "another-origin")
            if (kind == "timezone") cloud.stored[0] = previous.cloud().copy(zoneOffsetSeconds = 0)
            if (kind == "missing-zone") cloud.stored[0] = previous.cloud().copy(zoneOffsetSeconds = null)
            assertFailsWith<MiHealthImportRunException> { workflow(cloud, ledger).run(account, plan(listOf(target))) }
            assertTrue(cloud.uploads.isEmpty(), kind)
        }
    }

    @Test
    fun existingPendingHeartRateAndOxygenRecoverDuringNormalSyncWithOneDurableAllowance() = runBlocking {
        for (metric in listOf(HealthInboundMetric.HEART_RATE, HealthInboundMetric.OXYGEN_SATURATION)) {
            val target = row(0, metric)
            val ledger = HealthImportLedger(MemoryStore())
            ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(target)))
            val cloud = Cloud()
            cloud.beforeUpload = {
                assertTrue(ledger.pendingSnapshots(account, setOf(target.identity.stableKey)).values.single().recoveryConsumed)
            }
            val result = workflow(cloud, ledger).run(account, plan(listOf(target)).copy(metric = metric))
            assertEquals(1, result.verifiedRawCount)
            assertEquals(1, result.newlyUploadedRawCount)
            assertEquals(0, result.alreadyPresentRawCount)
            assertEquals(1, result.diagnostic?.attemptedRawRows)
            assertEquals(true, result.diagnostic?.pendingRecovery?.automatic)
            assertEquals(false, result.diagnostic?.pendingRecovery?.userRequested)
            assertEquals(1, cloud.uploads.flatten().size)
            assertTrue(ledger.pendingIdentities(account, setOf(target.identity.stableKey)).isEmpty())
        }
    }

    @Test
    fun knownConflictInOldestPendingDoesNotBlockAnIndependentRecoverablePoint() = runBlocking {
        val conflicting = row(0)
        val missing = row(1)
        val previous = oldValue(conflicting)
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markPending(account, listOf(conflicting, missing).map(MiHealthImportRunner::journalEntry))
        val cloud = Cloud().apply { persist(listOf(previous)) }
        val error = assertFailsWith<MiHealthImportRunException> {
            workflow(cloud, ledger).run(account, plan(listOf(conflicting, missing)))
        }
        assertEquals(1, error.verifiedRawCount)
        assertEquals(1, error.diagnostic?.newlyVerifiedRawRows)
        assertEquals(listOf(missing), cloud.uploads.flatten())
        assertEquals(previous.value, cloud.stored.single { it.time == conflicting.identity.time }.value)
        assertEquals(setOf(conflicting.identity.stableKey), ledger.pendingIdentities(account,
            setOf(conflicting.identity.stableKey, missing.identity.stableKey)))
    }

    @Test
    fun newUnverifiedSubmissionIsNotImmediatelyRetriedAndLaterAllowanceIsNeverReset() = runBlocking {
        val target = row(0)
        val ledger = HealthImportLedger(MemoryStore())
        val cloud = Cloud().apply { persistUploads = false }
        val plan = plan(listOf(target))
        val first = assertFailsWith<MiHealthImportRunException> { workflow(cloud, ledger).run(account, plan) }
        assertNull(first.diagnostic?.pendingRecovery)
        assertEquals(1, cloud.uploads.size)
        assertFailsWith<MiHealthImportRunException> { workflow(cloud, ledger).run(account, plan) }
        assertEquals(2, cloud.uploads.size)
        val third = assertFailsWith<MiHealthImportRunException> { workflow(cloud, ledger).run(account, plan) }
        assertEquals(2, cloud.uploads.size)
        assertEquals("RETRY_ALREADY_CONSUMED", third.diagnostic?.pendingRecovery?.status)
        assertEquals(setOf(target.identity.stableKey), ledger.pendingIdentities(account, setOf(target.identity.stableKey)))
    }

    @Test
    fun incompleteHistoryNeverAuthorizesAutomaticRecoveryButFreshIndependentPointsStillSync() = runBlocking {
        val pending = row(0)
        val fresh = row(1)
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(pending)))
        val cloud = Cloud().apply { historyComplete = false }
        val error = assertFailsWith<MiHealthImportRunException> {
            workflow(cloud, ledger).run(account, plan(listOf(pending, fresh)))
        }
        assertEquals(listOf(fresh), cloud.uploads.flatten())
        assertEquals("HISTORY_INCOMPLETE", error.diagnostic?.pendingRecovery?.status)
        assertFalse(ledger.pendingSnapshots(account, setOf(pending.identity.stableKey)).values.single().recoveryConsumed)
    }

    @Test
    fun oneDaySelectionDoesNotConsumePendingOutsideItsSourcePlan() = runBlocking {
        val outside = row(-2000)
        val selected = row(0)
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markPending(account, listOf(MiHealthImportRunner.journalEntry(outside)))
        val cloud = Cloud()
        val result = workflow(cloud, ledger).run(account, plan(listOf(selected), listOf(excludedSecond())))
        assertEquals(1, result.verifiedRawCount)
        assertEquals(listOf(selected), cloud.uploads.flatten())
        assertNull(result.diagnostic?.pendingRecovery)
        assertFalse(ledger.pendingSnapshots(account, setOf(outside.identity.stableKey)).values.single().recoveryConsumed)
    }

    @Test
    fun hrvFeedback12343SamplesKeeps50VerifiedSummariesAndSkips21AmbiguousAssociations() = runBlocking {
        fun origin(id: String) = HealthRecordOrigin(id, source, null, 1, day * 1000)
        fun sleep(id: String, begin: Long, end: Long) = HealthInboundSleepRecord(origin(id), begin * 1000,
            end * 1000, 0, 0, listOf(HealthInboundSleepStage(begin * 1000, end * 1000, HealthInboundSleepStageKind.LIGHT)))
        val sleeps = (0 until 50).map { sleep("night-$it", day + it * 86_400, day + it * 86_400 + 8 * 3600) }
        val samples = (0 until 12322).map { index ->
            val night = index % 50
            HealthInboundHrvRecord(origin("hrv-$index"), sleeps[night].startEpochMillis +
                3_600_000 + (index / 50) * 60_000, 0, 60.0)
        } + (0 until 21).map { index ->
            HealthInboundHrvRecord(origin("ambiguous-$index"), (day + 6 * 3600 + index * 60) * 1000, 0, 150.0)
        }
        val overlap = sleep("overlap", day + 6 * 3600, day + 7 * 3600)
        val plan = MiHrvImportMapper.sleepSummary(samples, sleeps + overlap, source, true)
        val cloud = Cloud().apply { persist(plan.rawRows) }
        val result = workflow(cloud, HealthImportLedger(MemoryStore())).run(account, plan)
        val diagnostic = assertNotNull(result.diagnostic)
        assertEquals(12343, plan.inputRecordCount)
        assertEquals(50, result.verifiedRawCount)
        assertEquals(50, result.alreadyPresentRawCount)
        assertEquals(21, diagnostic.excludedHrvSamples)
        assertEquals(0, diagnostic.sourceRejectedRecords)
        assertNull(diagnostic.failureReason)
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(diagnostic.copyableText("test").contains("outcome=COMPLETE_WITH_EXCLUSIONS"))
        val outcome = SyncRunResult.from(SyncProgress(hrvImport = SyncState.Success(50, diagnostic = diagnostic)),
            listOf(com.bettermifitness.sync.data.preferences.SyncPreferences.HRV_IMPORT_KEY), emptyList(), false)
        assertTrue(outcome.isCompletedWithSkips)
        assertFalse(outcome.isPartialSuccess)
        assertFalse(SyncOutcomeLabels.isWarning(SyncOutcome.STATUS_COMPLETED_WITH_SKIPS))
    }

    private fun workflow(cloud: Cloud, ledger: HealthImportLedger) = MiHealthImportWorkflow(cloud, ledger, readbackPause = {})

    @Test
    fun twoRestingMeasurementsUpdateThePreviouslyOwnedDailyValueAutomatically() = runBlocking {
        val observations = listOf(65L, 61L).mapIndexed { index, bpm ->
            com.bettermifitness.sync.health.HealthInboundRestingHeartRateRecord(
                HealthRecordOrigin("rhr-$index", source, null, 1, day * 1000),
                (day + 3600 + index * 3600) * 1000, 28_800, bpm)
        }
        val plan = com.bettermifitness.sync.data.api.MiHealthImportMapper.restingHeartRate(observations, source)
        val target = plan.rawRows.single()
        val previous = oldValue(target, 65)
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = true)))
        val cloud = Cloud().apply { persist(listOf(previous)) }
        assertEquals(1, inspectMiHealthImportPreflight(account, plan, cloud, ledger).eligibleOwnedRevisionRows)
        val result = workflow(cloud, ledger).run(account, plan)
        assertEquals(1, result.diagnostic?.verifiedOwnedRevisionRows)
        assertEquals(1, result.newlyUploadedRawCount)
        assertEquals(1, cloud.uploads.size)
        assertEquals(target.identity, cloud.uploads.single().single().identity)
        assertEquals(target.value, cloud.stored.single().value)
        assertEquals(0, cloud.uploads.single().single().zoneOffsetSeconds)
        val again = workflow(cloud, ledger).run(account, plan)
        assertEquals(0, again.newlyUploadedRawCount)
        assertEquals(1, cloud.uploads.size)
    }

    @Test
    fun ownedDateCodedRestingValueCanBeRevisedWhenTheCloudOmitsZeroOffset() = runBlocking {
        val target = row(0).copy(identity = row(0).identity.copy(key = "resting_heart_rate", time = day),
            value = buildJsonObject { put("date_time", day); put("bpm", 61) }, zoneOffsetSeconds = 0)
        val previous = oldValue(target, 65)
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = true)))
        val cloud = Cloud().apply { omitZeroZone = true; persist(listOf(previous)) }
        val result = workflow(cloud, ledger).run(account,
            plan(listOf(target)).copy(metric = HealthInboundMetric.RESTING_HEART_RATE))
        assertEquals(1, result.diagnostic?.verifiedOwnedRevisionRows)
        assertEquals(1, result.newlyUploadedRawCount)
        assertEquals(0, cloud.uploads.single().single().zoneOffsetSeconds)
        assertNull(cloud.stored.single().zoneOffsetSeconds)
        assertEquals(target.value, cloud.stored.single().value)
    }

    @Test
    fun restingRevisionDoesNotOverwriteUnownedChangedOrContestedCloudValues() = runBlocking {
        val target = row(0).copy(identity = row(0).identity.copy(key = "resting_heart_rate", time = day),
            value = buildJsonObject { put("date_time", day); put("bpm", 61) }, zoneOffsetSeconds = 0)
        for (kind in listOf("unowned", "changed", "deleted", "other-source", "timezone", "date-field")) {
            val previous = oldValue(target, 65)
            val cloud = Cloud().apply { persist(listOf(previous)) }
            val ledger = HealthImportLedger(MemoryStore())
            ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = kind != "unowned")))
            when (kind) {
                "changed" -> cloud.persist(listOf(oldValue(target, 70)))
                "deleted" -> cloud.stored += previous.cloud().copy(deleted = true)
                "other-source" -> cloud.stored += previous.cloud().copy(sid = "another-source")
                "timezone" -> cloud.stored[0] = previous.cloud().copy(zoneOffsetSeconds = 28_800)
                "date-field" -> cloud.persist(listOf(previous.copy(value = buildJsonObject {
                    put("date_time", day + 60); put("bpm", 65)
                })))
            }
            assertFailsWith<MiHealthImportRunException> {
                workflow(cloud, ledger).run(account, plan(listOf(target)).copy(metric = HealthInboundMetric.RESTING_HEART_RATE))
            }
            assertTrue(cloud.uploads.isEmpty(), kind)
        }
    }

    @Test
    fun restingRevisionStopsIfTheBaselineChangesImmediatelyBeforeWriting() = runBlocking {
        val target = row(0).copy(identity = row(0).identity.copy(key = "resting_heart_rate", time = day),
            value = buildJsonObject { put("date_time", day); put("bpm", 61) }, zoneOffsetSeconds = 0)
        val previous = oldValue(target, 65)
        val cloud = Cloud().apply { persist(listOf(previous)) }
        val ledger = HealthImportLedger(MemoryStore())
        ledger.markVerified(account, listOf(MiHealthImportRunner.journalEntry(previous).copy(ownedUpload = true)))
        cloud.onRead = { if (cloud.reads == 2) cloud.persist(listOf(oldValue(target, 70))) }
        assertFailsWith<MiHealthImportRunException> {
            workflow(cloud, ledger).run(account, plan(listOf(target)).copy(metric = HealthInboundMetric.RESTING_HEART_RATE))
        }
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(ledger.pendingIdentities(account, setOf(target.identity.stableKey)).isEmpty())
    }
    private fun hrvRow(avg: Int, minimum: Int, maximum: Int): MiHealthImportRow {
        val origin = HealthRecordOrigin("night", source, null, 1, day * 1000)
        val sleep = HealthInboundSleepRecord(origin, day * 1000, (day + 8 * 3600) * 1000, 0, 0,
            listOf(HealthInboundSleepStage(day * 1000, (day + 8 * 3600) * 1000, HealthInboundSleepStageKind.LIGHT)))
        val base = com.bettermifitness.sync.data.api.MiHealthImportMapper.sleep(listOf(sleep), source).rawRows.single()
        return base.copy(value = buildJsonObject {
            base.value.forEach { (key, value) -> put(key, value) }
            put("avg_hrv", avg); put("min_hrv", minimum); put("max_hrv", maximum)
        }, enrichment = com.bettermifitness.sync.data.api.MiHealthImportEnrichment.SLEEP_HRV)
    }
    private fun plan(rows: List<MiHealthImportRow>, issues: List<MiHealthImportIssue> = emptyList()) =
        MiHealthImportPlan(HealthInboundMetric.HEART_RATE, rows, issues, rows.size)
    private fun excludedSecond() = MiHealthImportIssue("HEART_RATE_SOURCE_POINT_EXCLUDED", setOf("a", "b"),
        "synthetic exclusion", MiHealthImportIssueSeverity.WARNING)
    private fun row(index: Int, metric: HealthInboundMetric = HealthInboundMetric.HEART_RATE): MiHealthImportRow {
        val time = day + 120 + index * 60
        val heart = metric == HealthInboundMetric.HEART_RATE
        return MiHealthImportRow(MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID,
            if (heart) "heart_rate" else "spo2", time), buildJsonObject {
                put("time", time); put(if (heart) "bpm" else "spo2", if (heart) 65 else 97)
            }, 28_800, setOf("$source:record-$index"))
    }
    private fun oldValue(row: MiHealthImportRow, bpm: Int = 70) = row.copy(value = buildJsonObject {
        row.value.forEach { (key, value) -> put(key, value) }; put("bpm", bpm)
    })
    private fun MiHealthImportRow.cloud() = MiHealthImportCloudRow(identity.sid, identity.key,
        identity.time, identity.tag, value, zoneOffsetSeconds = zoneOffsetSeconds)

    private inner class Cloud : MiHealthImportCloud {
        val stored = mutableListOf<MiHealthImportCloudRow>()
        val uploads = mutableListOf<List<MiHealthImportRow>>()
        var reads = 0
        var historyComplete = true
        var persistUploads = true
        var omitZeroZone = false
        var onRead: () -> Unit = {}
        var beforeUpload: suspend (List<MiHealthImportRow>) -> Unit = {}
        fun persist(rows: List<MiHealthImportRow>) {
            for (row in rows) {
                stored.removeAll { it.sid == row.identity.sid && it.key == row.identity.key && it.time == row.identity.time }
                stored += row.cloud().let { if (omitZeroZone && it.zoneOffsetSeconds == 0)
                    it.copy(zoneOffsetSeconds = null) else it }
            }
        }
        override suspend fun readRows(channel: MiHealthImportChannel, key: String,
            fromEpochSeconds: Long, untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            reads++
            onRead()
            return stored.filter { it.key == key && it.time >= fromEpochSeconds && it.time < untilEpochSeconds }
        }
        override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long, maxPages: Int) =
            MiHealthImportHistoryRead(stored.filter { it.key == key && it.time < untilEpochSeconds }, 1, historyComplete)
        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            assertTrue(experimentalEnabled)
            beforeUpload(rows)
            uploads += rows
            if (persistUploads) persist(rows)
            return MiHealthImportUploadResult(true, 0)
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
}
