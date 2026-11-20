package com.bettermifitness.sync.data.repository
import com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity

import com.bettermifitness.sync.data.api.MI_HEALTH_CONNECT_IMPORT_SID
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportCloudRow
import com.bettermifitness.sync.data.api.MiHealthImportIdentity
import com.bettermifitness.sync.data.api.MiHealthImportIssue
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiHealthImportUploadResult
import com.bettermifitness.sync.data.api.MiHealthImportEnrichment
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.api.MiHealthImportResponseSummary
import com.bettermifitness.sync.data.api.MiHealthImportTransportException
import com.bettermifitness.sync.data.api.MiHealthImportTransportFailure
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.data.api.parseMiHealthImportUploadResponse
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportJournalEntry
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthRecordIds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Remote outcomes and a durable write-ahead journal, with no network or user account. */
class MiHealthImportRunnerTest {
    /** Outside the valid range, so the row cannot be revised and stays a real conflict. */
    private val unrevisableBpm = 999

    @Test fun userOneDayCaseVerifies760PointsAndSkipsOneConflictingSecondWithoutWarning() = runBlocking {
        val source = "com.huami.watch.hmwatchmanager"
        val day = timestamp.floorDiv(86_400) * 86_400
        fun record(id: String, time: Long, bpm: Long = 65, samples: Int = 1) =
            com.bettermifitness.sync.health.HealthInboundHeartRateRecord(
                com.bettermifitness.sync.health.HealthRecordOrigin(id, source, null, 1, day * 1_000),
                time * 1_000, (time + samples) * 1_000, 0, 0,
                (0 until samples).map { com.bettermifitness.sync.health.HealthInboundHeartRateSample((time + it) * 1_000, bpm) })
        val records = (0 until 726).map { i -> record("record-$i", day + 60 + i * 60,
            samples = if (i == 0) 35 else 1) } +
            listOf(record("collision-a", day + 80_000), record("collision-b", day + 80_000, 66))
        val plan = com.bettermifitness.sync.data.api.MiHealthImportMapper.heartRate(records, source, true,
            com.bettermifitness.sync.health.HealthInboundReadRequest(day, day + 86_400, setOf(source)))
        val cloud = FakeCloud().apply { persist(plan.rawRows) }
        val result = runner(cloud, MemoryJournal()).run(account, plan)
        assertEquals(728, plan.inputRecordCount)
        assertEquals(760, result.alreadyPresentRawCount)
        assertEquals(760, result.verifiedRawCount)
        assertEquals(0, result.newlyUploadedRawCount)
        assertEquals(1, result.diagnostic?.excludedSourcePoints)
        assertEquals(0, result.diagnostic?.sourceRejectedRecords)
        assertEquals(0, result.diagnostic?.plannedDailyRows)
        assertEquals(null, result.diagnostic?.failureReason)
        assertTrue(result.warnings.isEmpty())
        assertTrue(cloud.uploads.isEmpty())
    }

    private val account = "test-account"
    private val timestamp = 1_800_000_000L

    @Test fun excludedSourcePointDoesNotFailValidHeartRatePrecheckOrCountAsVerified() = runBlocking {
        val issue = MiHealthImportIssue("HEART_RATE_SOURCE_POINT_EXCLUDED", setOf("broken-source"), "excluded",
            MiHealthImportIssueSeverity.WARNING, setOf(timestamp.floorDiv(86_400)))
        val row = raw()
        val cloud = FakeCloud().apply { persist(listOf(row)) }
        val result = runner(cloud, MemoryJournal()).run(account, plan(listOf(row), listOf(issue)))
        assertEquals(1, result.verifiedRawCount)
        assertEquals(0, result.newlyUploadedRawCount)
        assertEquals(1, result.diagnostic?.excludedSourcePoints)
        assertEquals(null, result.diagnostic?.failureReason)
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.diagnostic!!.copyableText("test").contains("outcome=COMPLETE_WITH_EXCLUSIONS"))
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test fun exclusionsNeverHideAnIndependentCloudConflict() = runBlocking {
        val issue = MiHealthImportIssue("HEART_RATE_SOURCE_POINT_EXCLUDED", setOf("broken"), "excluded",
            MiHealthImportIssueSeverity.WARNING)
        val cloud = FakeCloud().apply { persist(listOf(raw(bpm = unrevisableBpm))) }
        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(raw()), listOf(issue)))
        }
        assertEquals(MiHealthImportFailureReason.CONFLICT, failure.reason)
        assertEquals(1, failure.diagnostic?.excludedSourcePoints)
        assertEquals(0, failure.verifiedRawCount)
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test fun heartRateRejectedDayDoesNotPreventIndependentVerifiedDaySummary() = runBlocking {
        val first = raw().copy(zoneOffsetSeconds = 0)
        val second = raw(86_400).copy(zoneOffsetSeconds = 0)
        val summaries = listOf(daily(), daily().let { it.copy(identity = it.identity.copy(time = it.identity.time + 86_400)) })
        val issue = MiHealthImportIssue("TARGET_TIME_COLLISION", setOf("rejected-source"), "synthetic collision",
            affectedLocalDays = setOf(first.identity.time.floorDiv(86_400)))
        val cloud = FakeCloud()
        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(first, second) + summaries, listOf(issue)))
        }
        assertEquals(MiHealthImportFailureReason.SOURCE_REJECTED, failure.reason)
        assertEquals(2, failure.verifiedRawCount)
        assertEquals(1, failure.diagnostic?.verifiedDailyRows)
        assertEquals(listOf(summaries.last()), cloud.uploads.flatten().filter {
            it.identity.channel == MiHealthImportChannel.DAILY_AGGREGATE
        })
    }

    @Test fun unknownRejectionNeverAuthorizesAHeartRateSummaryEvenWithAConstructedPlan() = runBlocking {
        val first = raw().copy(zoneOffsetSeconds = 0)
        val issue = MiHealthImportIssue("INVALID_SOURCE", setOf("bad"), "unknown affected day")
        val cloud = FakeCloud()
        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(first, daily()), listOf(issue)))
        }
        assertEquals(0, failure.diagnostic?.attemptedDailyRows)
        assertTrue(cloud.uploads.flatten().all { it.identity.channel == MiHealthImportChannel.RAW })
    }

    @Test
    fun acceptedPostWithoutMatchingReadbackIsUnverifiedAndDoesNotWriteDailyReport() = runBlocking {
        val raw = raw()
        val summary = daily()
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            onUpload = { MiHealthImportUploadResult(accepted = true) }
        }

        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(listOf(raw, summary)))
        }

        assertEquals(MiHealthImportFailureReason.UNVERIFIED, failure.reason)
        assertEquals(0, failure.verifiedRawCount)
        assertEquals(1, failure.affectedCount)
        assertEquals(listOf(listOf(raw)), cloud.uploads)
        assertEquals(setOf(raw.identity.stableKey), journal.pendingFor(account))
        assertTrue(journal.verifiedFor(account).isEmpty())
    }

    @Test
    fun timeoutAfterPersistenceIsConfirmedByReadbackAndNextRunSkipsTheExistingRecord() = runBlocking {
        val row = raw()
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            beforeUpload = { rows ->
                assertTrue(rows.all { it.identity.stableKey in journal.pendingFor(account) })
            }
            onUpload = { rows ->
                persist(rows)
                val saved = stored.single()
                stored[0] = saved.first to saved.second.copy(value = buildJsonObject {
                    saved.second.value.forEach { (key, value) -> put(key, value) }
                    put("server_added_field", 1)
                })
                throw IllegalStateException("Response lost after the server saved the batch")
            }
        }
        val runner = runner(cloud, journal)

        val first = runner.run(account, plan(listOf(row)))
        assertEquals(1, first.verifiedRawCount)
        assertEquals(1, first.newlyUploadedRawCount)
        assertTrue(journal.pendingFor(account).isEmpty())
        assertEquals(setOf(row.identity.stableKey), journal.verifiedFor(account))
        assertEquals(
            HealthRecordIds.version(canonicalMiHealthImportJson(cloud.stored.single().second.value)).toString(),
            journal.verifiedFingerprints(account)[HealthImportLedger.identityDigest(row.identity.stableKey)],
        )

        val second = runner.run(account, plan(listOf(row)))
        assertEquals(1, second.verifiedRawCount)
        assertEquals(0, second.newlyUploadedRawCount)
        assertEquals(1, second.alreadyPresentRawCount)
        assertEquals(1, cloud.uploads.size)
    }

    @Test
    fun previouslyVerifiedRowMissingFromLaterReadIsNotUploadedAgain() = runBlocking {
        val row = raw()
        val journal = MemoryJournal()
        val cloud = FakeCloud()
        val first = runner(cloud, journal).run(account, plan(listOf(row)))
        assertEquals(1, first.newlyUploadedRawCount)
        cloud.stored.clear() // Simulate a later read omitting a successfully observed row.

        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(listOf(row)))
        }
        assertEquals(1, cloud.uploads.size)
        assertEquals(MiHealthImportFailureReason.UNVERIFIED, failure.reason)
        val diagnostic = assertNotNull(failure.diagnostic)
        assertEquals(MiHealthImportStage.PRECHECK, diagnostic.stage)
        assertEquals(1, diagnostic.blockedVerifiedHistoryRows)
        assertEquals(0, diagnostic.blockedPendingRows)
        assertEquals(0, diagnostic.attemptedRawRows)
        assertEquals(0, diagnostic.verifiedRawRows)
        assertEquals(1, diagnostic.notAttemptedRawRows)
        assertTrue(journal.pendingFor(account).isEmpty())
        assertEquals(setOf(row.identity.stableKey), journal.verifiedFor(account))
    }

    @Test
    fun nativeObservedIdentityCannotBeResubmittedAfterSourceRevisionOrCloudDeletion() = runBlocking {
        val row = raw()
        val journal = MemoryJournal()
        // A recovery/native read may have verified this identity independently
        // of the normal window query, without owning the original upload.
        journal.markVerified(account, listOf(MiHealthImportRunner.journalEntry(row)))
        val cloud = FakeCloud().apply {
            stored += MiHealthImportChannel.RAW to row.asCloudRow().copy(deleted = true)
        }
        val revised = raw(bpm = 73).copy(sourceRecordKeys = setOf("changed-source-revision"))
        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(listOf(revised)))
        }
        assertTrue(cloud.uploads.isEmpty())
        assertEquals(1, failure.diagnostic?.blockedVerifiedHistoryRows)
        assertEquals(0, failure.diagnostic?.blockedConflictRows)
        assertEquals(0, failure.diagnostic?.newlyVerifiedRawRows)
        assertEquals(setOf(row.identity.stableKey), journal.verifiedFor(account))
    }

    @Test
    fun uncertainRowsAreNotResubmittedWhenWindowBatchMembershipOrSourceRevisionChanges() = runBlocking {
        val first = raw(0)
        val uncertain = raw(1)
        val fresh = raw(2)
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            onUpload = { throw IllegalStateException("Timeout with no readback evidence") }
        }
        val runner = runner(cloud, journal)
        assertFailsWith<MiHealthImportRunException> {
            runner.run(account, plan(listOf(first, uncertain)))
        }

        // A later read window contains only one former row, a changed source
        // revision for it, and a new row. A batch hash would miss this retry.
        val revised = raw(1, bpm = 73).copy(sourceRecordKeys = setOf("zepp:new-container"))
        cloud.onUpload = { rows -> persist(rows); MiHealthImportUploadResult(true) }
        val failure = assertFailsWith<MiHealthImportRunException> {
            runner.run(account, plan(listOf(fresh, revised)))
        }

        assertEquals(MiHealthImportFailureReason.PENDING, failure.reason)
        assertEquals(1, failure.verifiedRawCount)
        assertEquals(1, failure.affectedCount)
        val writtenTargets = cloud.uploads.flatten().map { it.identity }
        assertEquals(1, writtenTargets.count { it == uncertain.identity })
        assertEquals(1, writtenTargets.count { it == fresh.identity })
        assertEquals(setOf(first.identity.stableKey, uncertain.identity.stableKey), journal.pendingFor(account))
        assertEquals(setOf(fresh.identity.stableKey), journal.verifiedFor(account))
    }

    @Test
    fun differentValuesAreReplacedFromTheSourceAndEqualValuesAreSkipped() = runBlocking {
        val alreadyPresent = raw(0)
        val different = raw(1)
        val fresh = raw(2)
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply { persist(listOf(alreadyPresent, raw(1, bpm = 99))) }

        val result = runner(cloud, journal).run(account, plan(listOf(alreadyPresent, different, fresh)))

        assertEquals(3, result.verifiedRawCount)
        assertEquals(1, result.diagnostic?.verifiedRevisionRows)
        assertEquals(listOf(listOf(different, fresh)), cloud.uploads)
        assertEquals(different.asCloudRow(), cloud.stored.single { it.second.time == different.identity.time }.second)
    }

    @Test
    fun aConflictThatCannotBeRevisedStaysUnchangedWhileCountsStayPartial() = runBlocking {
        val alreadyPresent = raw(0)
        val conflict = raw(1)
        val originalCloudValue = raw(1, bpm = unrevisableBpm)
        val fresh = raw(2)
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply { persist(listOf(alreadyPresent, originalCloudValue)) }

        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(listOf(alreadyPresent, conflict, fresh, daily())))
        }

        assertEquals(MiHealthImportFailureReason.CONFLICT, failure.reason)
        assertEquals(2, failure.verifiedRawCount)
        assertEquals(1, failure.affectedCount)
        assertEquals(listOf(listOf(fresh)), cloud.uploads)
        assertEquals(originalCloudValue.asCloudRow(), cloud.stored.single { it.second.time == conflict.identity.time }.second)
        assertEquals(setOf(alreadyPresent.identity.stableKey, fresh.identity.stableKey), journal.verifiedFor(account))
        assertTrue(journal.pendingFor(account).isEmpty())
    }

    @Test
    fun missingCloudSourceIdentityAndRejectedSourcePlansCannotBeTreatedAsSuccess() = runBlocking {
        val row = raw()
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            stored += MiHealthImportChannel.RAW to row.asCloudRow().copy(sid = null)
        }
        val runner = runner(cloud, journal)
        val ambiguous = assertFailsWith<MiHealthImportRunException> {
            runner.run(account, plan(listOf(row)))
        }
        assertEquals(MiHealthImportFailureReason.CONFLICT, ambiguous.reason)
        assertEquals(0, ambiguous.verifiedRawCount)
        assertTrue(ambiguous.details.contains("sid"))

        val rejected = assertFailsWith<MiHealthImportRunException> {
            runner.run(account, plan(emptyList(), listOf(MiHealthImportIssue(
                code = "INVALID_SOURCE",
                sourceRecordKeys = setOf("unknown:record"),
                message = "The source of this record cannot be established.",
            ))))
        }
        assertEquals(MiHealthImportFailureReason.SOURCE_REJECTED, rejected.reason)
        assertEquals(0, rejected.verifiedRawCount)
        assertEquals(1, rejected.affectedCount)
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(journal.pendingFor(account).isEmpty())
        assertTrue(journal.verifiedFor(account).isEmpty())
    }

    @Test
    fun existingDailyReportFromAnotherSourceIsPreservedAfterRawRecordsAreVerified() = runBlocking {
        val raw = raw()
        val summary = daily()
        val deviceSummary = summary.asCloudRow().copy(
            sid = "existing-watch-source",
            value = buildJsonObject { put("avg_hr", 82); put("source_only_field", 7) },
        )
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply { stored += MiHealthImportChannel.DAILY_AGGREGATE to deviceSummary }

        val result = runner(cloud, journal).run(account, plan(listOf(raw, summary)))

        assertEquals(1, result.verifiedRawCount)
        assertEquals(0, result.verifiedAggregateCount)
        assertEquals(0, result.diagnostic?.gatedDailyRevisionRows)
        assertTrue("EXISTING_DAILY_REPORT_PRESERVED" in result.warnings)
        assertEquals(listOf(listOf(raw)), cloud.uploads)
        assertEquals(deviceSummary, cloud.stored.single { it.first == MiHealthImportChannel.DAILY_AGGREGATE }.second)
        assertTrue(summary.identity.stableKey !in journal.verifiedFor(account))
    }

    @Test
    fun completeLateSourceDayRecognizesOwnedReportButNeverPostsARevision() = runBlocking {
        val early = raw(bpm = 60).copy(zoneOffsetSeconds = 0)
        val late = raw(index = 1, bpm = 160).copy(zoneOffsetSeconds = 0)
        val original = daily().copy(zoneOffsetSeconds = 0, completeSourceDay = true,
            value = buildJsonObject { put("avg_hr", 60) })
        val proposed = original.copy(sourceRecordKeys = early.sourceRecordKeys + late.sourceRecordKeys,
            value = buildJsonObject { put("avg_hr", 110) })
        val cloud = FakeCloud()
        val journal = MemoryJournal()
        val runner = runner(cloud, journal)
        assertEquals(1, runner.run(account, plan(listOf(early, original))).verifiedAggregateCount)
        assertTrue(HealthImportLedger.identityDigest(original.identity.stableKey) in journal.ownedVerifiedFingerprints(account))
        cloud.uploads.clear()

        val result = runner.run(account, plan(listOf(early, late, proposed)))
        assertEquals(1, result.diagnostic?.gatedDailyRevisionRows)
        assertEquals(listOf(listOf(late)), cloud.uploads)
        assertEquals(original.asCloudRow(), cloud.stored.single { it.first == MiHealthImportChannel.DAILY_AGGREGATE }.second)

        val incomplete = runner.run(account, plan(listOf(early, late, proposed.copy(completeSourceDay = false))))
        assertEquals(0, incomplete.diagnostic?.gatedDailyRevisionRows)
        cloud.stored += MiHealthImportChannel.DAILY_AGGREGATE to original.asCloudRow()
        assertEquals(0, runner.run(account, plan(listOf(early, late, proposed))).diagnostic?.gatedDailyRevisionRows)
        cloud.stored.removeAt(cloud.stored.lastIndex)
        val oldIndex = cloud.stored.indexOfFirst { it.first == MiHealthImportChannel.DAILY_AGGREGATE }
        cloud.stored[oldIndex] = MiHealthImportChannel.DAILY_AGGREGATE to original.asCloudRow().copy(tagInferred = true)
        assertEquals(0, runner.run(account, plan(listOf(early, late, proposed))).diagnostic?.gatedDailyRevisionRows)
        cloud.stored[oldIndex] = MiHealthImportChannel.DAILY_AGGREGATE to original.asCloudRow()
        cloud.stored += MiHealthImportChannel.DAILY_AGGREGATE to original.asCloudRow().copy(sid = "watch")
        val competing = runner.run(account, plan(listOf(early, late, proposed)))
        assertEquals(0, competing.diagnostic?.gatedDailyRevisionRows)
        cloud.stored.removeAll { it.first == MiHealthImportChannel.DAILY_AGGREGATE && it.second.sid == "watch" }
        journal.markPending(account, listOf(MiHealthImportRunner.journalEntry(proposed)))
        val pending = runner.run(account, plan(listOf(early, late, proposed)))
        assertEquals(0, pending.diagnostic?.gatedDailyRevisionRows)
        assertTrue(proposed.identity.stableKey in journal.pendingFor(account))
        assertEquals(listOf(listOf(late)), cloud.uploads)
    }

    @Test fun aCompetingDailyReportAppearingAfterPrecheckBlocksCreationBeforeReservation() = runBlocking {
        val point = raw()
        val summary = daily()
        val external = summary.asCloudRow().copy(sid = "watch", value = buildJsonObject { put("avg_hr", 91) })
        var dailyReads = 0
        val cloud = FakeCloud().apply {
            onChannelRead = { channel ->
                if (channel == MiHealthImportChannel.DAILY_AGGREGATE && ++dailyReads == 2) stored += channel to external
            }
        }
        val journal = MemoryJournal()
        val failure = assertFailsWith<MiHealthImportRunException> { runner(cloud, journal).run(account, plan(listOf(point, summary))) }
        assertEquals(MiHealthImportFailureReason.CONFLICT, failure.reason)
        assertEquals("DAILY_TARGET_CHANGED", failure.details)
        assertEquals(1, failure.verifiedRawCount)
        assertEquals(0, failure.diagnostic?.attemptedDailyRows)
        assertEquals(listOf(listOf(point)), cloud.uploads)
        assertEquals(emptySet(), journal.pendingFor(account))
        assertEquals(external, cloud.stored.single { it.first == MiHealthImportChannel.DAILY_AGGREGATE }.second)
    }

    @Test fun missingFullSourceDayProofDoesNotPreventRawImportButCannotCreateDailyReport() = runBlocking {
        val point = raw()
        val summary = daily().copy(completeSourceDay = false)
        val cloud = FakeCloud()
        val journal = MemoryJournal()
        val failure = assertFailsWith<MiHealthImportRunException> { runner(cloud, journal).run(account, plan(listOf(point, summary))) }
        assertEquals(MiHealthImportFailureReason.UNVERIFIED, failure.reason)
        assertEquals("incomplete_daily_source", failure.details)
        assertEquals(1, failure.verifiedRawCount)
        assertEquals(0, failure.diagnostic?.attemptedDailyRows)
        assertEquals(listOf(listOf(point)), cloud.uploads)
        assertEquals(emptySet(), journal.pendingFor(account))
        cloud.persist(listOf(summary))
        val preserved = runner(cloud, journal).run(account, plan(listOf(point, summary)))
        assertEquals(0, preserved.verifiedAggregateCount)
        assertEquals(1, preserved.diagnostic?.preservedDailyRows)
        assertEquals(listOf(listOf(point)), cloud.uploads)
    }

    @Test fun serverRecomputedDailyMatchesWithoutPostingOrClaimingOwnershipOfChangedContent() = runBlocking {
        val early = raw(bpm = 60).copy(zoneOffsetSeconds = 0)
        val late = raw(index = 1, bpm = 160).copy(zoneOffsetSeconds = 0)
        val original = daily().copy(zoneOffsetSeconds = 0, value = buildJsonObject { put("avg_hr", 60) })
        val proposed = original.copy(sourceRecordKeys = early.sourceRecordKeys + late.sourceRecordKeys,
            value = buildJsonObject { put("avg_hr", 110) })
        val cloud = FakeCloud()
        val journal = MemoryJournal()
        val sync = runner(cloud, journal)
        sync.run(account, plan(listOf(early, original)))
        val digest = HealthImportLedger.identityDigest(original.identity.stableKey)
        assertTrue(digest in journal.ownedVerifiedFingerprints(account))
        cloud.uploads.clear()
        cloud.onUpload = { rows ->
            persist(rows)
            if (rows == listOf(late)) persist(listOf(proposed))
            MiHealthImportUploadResult(true)
        }
        val result = sync.run(account, plan(listOf(early, late, proposed)))
        assertEquals(1, result.verifiedAggregateCount)
        assertEquals(0, result.diagnostic?.gatedDailyRevisionRows)
        assertEquals(listOf(listOf(late)), cloud.uploads)
        assertTrue(digest !in journal.ownedVerifiedFingerprints(account))
        assertEquals(MiHealthImportRunner.contentFingerprint(proposed), journal.verifiedFingerprints(account)[digest])
    }

    @Test fun readOnlyInspectionDistinguishesMissingLateRawAndServerRecomputeWithoutChangingOwnership() = runBlocking {
        val early = raw(bpm = 60).copy(zoneOffsetSeconds = 0)
        val late = raw(index = 1, bpm = 160).copy(zoneOffsetSeconds = 0)
        val original = daily().copy(zoneOffsetSeconds = 0, value = buildJsonObject { put("avg_hr", 60) })
        val revised = original.copy(value = buildJsonObject { put("avg_hr", 110) },
            sourceRecordKeys = early.sourceRecordKeys + late.sourceRecordKeys)
        val cloud = FakeCloud()
        val journal = MemoryJournal()
        runner(cloud, journal).run(account, plan(listOf(early, original)))
        cloud.uploads.clear()
        val ownedBefore = journal.ownedVerifiedFingerprints(account)
        val verifiedBefore = journal.verifiedFingerprints(account)

        suspend fun inspect() = inspectMiHealthImportPreflight(account, plan(listOf(early, late, revised)), cloud, journal)
        assertEquals(0, inspect().gatedDailyRevisionRows)
        cloud.persist(listOf(late))
        val stale = inspect()
        assertEquals(1, stale.gatedDailyRevisionRows)
        assertEquals(1, stale.preservedDailyRows)
        assertEquals(0, stale.verifiedDailyRows)
        cloud.persist(listOf(revised))
        val recomputed = inspect()
        assertEquals(1, recomputed.verifiedDailyRows)
        assertEquals(0, recomputed.gatedDailyRevisionRows)
        assertEquals(0, recomputed.attemptedDailyRows)
        assertTrue(cloud.uploads.isEmpty())
        assertEquals(emptySet(), journal.pendingFor(account))
        assertEquals(ownedBefore, journal.ownedVerifiedFingerprints(account))
        assertEquals(verifiedBefore, journal.verifiedFingerprints(account))
    }

    @Test fun competingPostWriteDailyReadDoesNotConfirmOrReleaseThePendingReport() = runBlocking {
        val point = raw()
        val summary = daily()
        val cloud = FakeCloud().apply {
            onUpload = { rows ->
                persist(rows)
                if (rows.single().identity.channel == MiHealthImportChannel.DAILY_AGGREGATE) {
                    stored += MiHealthImportChannel.DAILY_AGGREGATE to summary.asCloudRow().copy(sid = "watch")
                }
                MiHealthImportUploadResult(true)
            }
        }
        val journal = MemoryJournal()
        val sync = runner(cloud, journal)
        val failure = assertFailsWith<MiHealthImportRunException> { sync.run(account, plan(listOf(point, summary))) }
        assertEquals(MiHealthImportFailureReason.UNVERIFIED, failure.reason)
        assertEquals(0, failure.diagnostic?.verifiedDailyRows)
        assertEquals(setOf(summary.identity.stableKey), journal.pendingFor(account))
        cloud.uploads.clear()
        assertEquals(0, sync.run(account, plan(listOf(point, summary))).verifiedAggregateCount)
        assertTrue(cloud.uploads.isEmpty())
        assertEquals(setOf(summary.identity.stableKey), journal.pendingFor(account))
    }

    @Test
    fun omittedPreviouslyVerifiedDailyIsNotSubmittedAgain() = runBlocking {
        val point = raw().copy(zoneOffsetSeconds = 0)
        val summary = daily().copy(zoneOffsetSeconds = 0, completeSourceDay = true)
        val cloud = FakeCloud()
        val runner = runner(cloud, MemoryJournal())
        runner.run(account, plan(listOf(point, summary)))
        cloud.stored.removeAll { it.first == MiHealthImportChannel.DAILY_AGGREGATE }
        cloud.uploads.clear()

        val error = assertFailsWith<MiHealthImportRunException> { runner.run(account, plan(listOf(point, summary))) }
        assertEquals(MiHealthImportFailureReason.UNVERIFIED, error.reason)
        assertEquals(1, error.diagnostic?.blockedVerifiedHistoryRows)
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test
    fun partiallyAppliedBatchReportsOnlyConfirmedRawCountAndKeepsOtherRowsPending() = runBlocking {
        val rows = listOf(raw(0), raw(1), raw(2))
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            onUpload = { batch -> persist(batch.take(1)); MiHealthImportUploadResult(true) }
        }

        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(rows + daily()))
        }

        assertEquals(MiHealthImportFailureReason.UNVERIFIED, failure.reason)
        assertEquals(1, failure.verifiedRawCount)
        assertEquals(2, failure.affectedCount)
        assertEquals(setOf(rows.first().identity.stableKey), journal.verifiedFor(account))
        assertEquals(rows.drop(1).map { it.identity.stableKey }.toSet(), journal.pendingFor(account))
        assertTrue(cloud.uploads.flatten().all { it.identity.channel == MiHealthImportChannel.RAW })
    }

    @Test
    fun explicitCloudBusinessRejectionIsReportedAsRejectedInsteadOfSuccessOrTransportTimeout() = runBlocking {
        val row = raw()
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            // Transport translates a valid nonzero business status to false;
            // transport exceptions remain uncertain outcomes instead.
            onUpload = { MiHealthImportUploadResult(accepted = false) }
        }

        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(listOf(row)))
        }

        assertEquals(MiHealthImportFailureReason.REJECTED, failure.reason)
        assertEquals(0, failure.verifiedRawCount)
        assertEquals(1, failure.affectedCount)
        assertEquals(1, cloud.uploads.size)
        assertTrue(journal.verifiedFor(account).isEmpty())
    }

    @Test
    fun codeZeroWithoutExplicitAcceptanceStaysPendingAndStopsRemainingBatchesAndDays() = runBlocking {
        val rows = (0..50).map { raw(it) } + raw(86_400)
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            onUpload = { parseMiHealthImportUploadResponse(buildJsonObject {
                put("code", 0); put("message", "ok"); put("result", buildJsonObject {})
            }) }
        }

        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(rows + daily()))
        }

        val diagnostic = assertNotNull(failure.diagnostic)
        assertEquals(MiHealthImportFailureReason.UNVERIFIED, failure.reason)
        assertEquals(listOf(rows.take(50)), cloud.uploads)
        assertTrue(cloud.readCount > 1)
        assertEquals(50, diagnostic.attemptedRawRows)
        assertEquals(50, diagnostic.readbackMissingRows)
        assertEquals(2, diagnostic.notAttemptedRawRows)
        assertEquals(0, diagnostic.attemptedDailyRows)
        assertEquals(0, diagnostic.uploadResponse?.businessCode)
        assertNull(diagnostic.uploadResponse?.accepted)
        assertEquals(MiHealthImportTransportFailure.INVALID_RESPONSE, diagnostic.uploadResponse?.transportFailure)
        assertEquals(rows.take(50).map { it.identity.stableKey }.toSet(), journal.pendingFor(account))
        assertTrue(journal.verifiedFor(account).isEmpty())
        assertTrue(journal.ownedVerifiedFingerprints(account).isEmpty())
    }

    @Test
    fun malformedAcceptanceCanBeVerifiedByReadbackWithoutSubmittingRemainingBatch() = runBlocking {
        val rows = (0..50).map { raw(it) }
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            onUpload = { batch ->
                persist(batch)
                parseMiHealthImportUploadResponse(buildJsonObject {
                    put("code", 0); put("message", "ok"); put("result", buildJsonObject {})
                })
            }
        }

        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(rows + daily()))
        }

        val diagnostic = assertNotNull(failure.diagnostic)
        assertEquals(MiHealthImportFailureReason.UNVERIFIED, failure.reason)
        assertEquals("remaining_batches_not_submitted", failure.details)
        assertEquals(listOf(rows.take(50)), cloud.uploads)
        assertTrue(cloud.readCount > 1)
        assertEquals(50, diagnostic.newlyVerifiedRawRows)
        assertEquals(1, diagnostic.notAttemptedRawRows)
        assertEquals(0, diagnostic.readbackMissingRows)
        assertEquals(0, diagnostic.attemptedDailyRows)
        assertEquals(0, diagnostic.uploadResponse?.businessCode)
        assertNull(diagnostic.uploadResponse?.accepted)
        assertEquals(MiHealthImportTransportFailure.INVALID_RESPONSE, diagnostic.uploadResponse?.transportFailure)
        assertEquals(rows.take(50).map { it.identity.stableKey }.toSet(), journal.verifiedFor(account))
        assertTrue(journal.pendingFor(account).isEmpty())
    }

    @Test
    fun fiftyOneRawRowsRespectCurrentNativeBatchLimitAndJournalEveryBatchBeforePosting() = runBlocking {
        val rows = (0..50).map { raw(it) }
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            beforeUpload = { batch ->
                assertTrue(batch.all { it.identity.stableKey in journal.pendingFor(account) })
            }
        }

        val result = runner(cloud, journal).run(account, plan(rows))

        assertEquals(listOf(50, 1), cloud.uploads.map { it.size })
        assertEquals(51, result.verifiedRawCount)
        assertEquals(51, result.newlyUploadedRawCount)
        assertEquals(51, journal.verifiedFor(account).size)
        assertTrue(journal.pendingFor(account).isEmpty())
    }

    @Test
    fun journalPersistenceFailureStopsTheRemoteWriteEntirely() = runBlocking {
        val diskError = IllegalStateException("Persistent journal unavailable")
        val journal = MemoryJournal().apply { pendingFailure = diskError }
        val cloud = FakeCloud()

        val error = assertFailsWith<IllegalStateException> {
            runner(cloud, journal).run(account, plan(listOf(raw())))
        }

        assertSame(diskError, error)
        assertTrue(cloud.uploads.isEmpty())
        assertTrue(journal.pendingFor(account).isEmpty())
    }

    @Test
    fun rejectionDiagnosticPreservesBusinessCodeAndRawBatchSizeSeparately() = runBlocking {
        val cloud = FakeCloud().apply { onUpload = { MiHealthImportUploadResult(false, 10007, "invalid parameter") } }
        val error = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(raw(0), raw(1), raw(2))))
        }
        val diagnostic = assertNotNull(error.diagnostic)
        assertEquals(3, error.affectedCount)
        assertEquals(3, diagnostic.batchRows)
        assertEquals(MiHealthImportStage.UPLOAD, diagnostic.stage)
        assertEquals(MiHealthImportChannel.RAW, diagnostic.channel)
        assertEquals(10007, diagnostic.uploadResponse?.businessCode)
        assertEquals("invalid parameter", diagnostic.uploadResponse?.safeMessage)
    }

    @Test
    fun aRejectedBatchThatAppearsInReadbackIsObservedWithoutTakingOwnershipFromAcceptedBatches() = runBlocking {
        val rows = (0..50).map { raw(it) }
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            onUpload = { batch ->
                // Simulate concurrent matching data for the rejected final batch.
                persist(batch)
                MiHealthImportUploadResult(accepted = uploads.size == 1, code = if (uploads.size == 1) 0 else 7)
            }
        }
        val result = runner(cloud, journal).run(account, plan(rows))
        assertEquals(51, result.verifiedRawCount)
        assertEquals(51, journal.verifiedFingerprints(account).size)
        assertEquals(50, journal.ownedVerifiedFingerprints(account).size)
        assertFalse(HealthImportLedger.identityDigest(rows.last().identity.stableKey) in journal.ownedVerifiedFingerprints(account))
        assertTrue(journal.pendingFor(account).isEmpty())
    }

    @Test
    fun dailyRejectionRetainsConfirmedRawCountAndNamesTheDailyChannel() = runBlocking {
        val cloud = FakeCloud().apply {
            onUpload = { batch ->
                if (batch.first().identity.channel == MiHealthImportChannel.RAW) {
                    persist(batch); MiHealthImportUploadResult(true, 0)
                } else MiHealthImportUploadResult(false, 7)
            }
        }
        val error = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(raw(), daily())))
        }
        val diagnostic = assertNotNull(error.diagnostic)
        assertEquals(1, error.verifiedRawCount)
        assertEquals(MiHealthImportChannel.DAILY_AGGREGATE, diagnostic.channel)
        assertEquals(1, diagnostic.attemptedDailyRows)
        assertEquals(7, diagnostic.uploadResponse?.businessCode)
    }

    @Test
    fun precheckFailureHasNoAttemptedWriteAndRetainsItsBusinessCode() = runBlocking {
        val cloud = FakeCloud().apply {
            onRead = { throw MiHealthImportTransportException(MiHealthImportResponseSummary(businessCode = 17)) }
        }
        val error = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(raw())))
        }
        val diagnostic = assertNotNull(error.diagnostic)
        assertEquals(MiHealthImportStage.PRECHECK, diagnostic.stage)
        assertEquals(0, diagnostic.attemptedRawRows)
        assertNull(diagnostic.uploadResponse)
        assertEquals(17, diagnostic.readResponse?.businessCode)
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test
    fun uploadAndReadbackFailuresKeepBothIndependentCodes() = runBlocking {
        val cloud = FakeCloud().apply {
            onUpload = { throw MiHealthImportTransportException(MiHealthImportResponseSummary(httpStatus = 503)) }
            onRead = { if (readCount > 1) throw MiHealthImportTransportException(MiHealthImportResponseSummary(businessCode = 17)) }
        }
        val error = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(raw())))
        }
        assertEquals(503, error.diagnostic?.uploadResponse?.httpStatus)
        assertEquals(17, error.diagnostic?.readResponse?.businessCode)
    }

    @Test
    fun singleRecordModeUsesTheLatestRealRowAndNeverCreatesDailyReports() = runBlocking {
        val rows = listOf(raw(0), raw(1), raw(2))
        val cloud = FakeCloud()
        val result = runner(cloud, MemoryJournal()).run(account, plan(rows + daily()), testMode = true)
        assertEquals(listOf(listOf(rows.last())), cloud.uploads)
        assertEquals(1, result.verifiedRawCount)
        assertTrue(result.diagnostic?.testMode == true)
        assertEquals(3, result.diagnostic?.sourceRawRows)
        assertEquals(1, result.diagnostic?.plannedRawRows)
        assertEquals(0, result.diagnostic?.plannedDailyRows)
        assertTrue("TEST_MODE_SINGLE_RECORD" in result.warnings)
    }

    @Test
    fun testModeDoesNotClearPendingOrChooseArbitrarilyBetweenConflictingLatestRows() = runBlocking {
        val row = raw(2)
        val cloud = FakeCloud()
        val journal = MemoryJournal()
        journal.markPending(account, listOf(MiHealthImportRunner.journalEntry(row)))
        val pending = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(listOf(raw(0), row)), testMode = true)
        }
        assertEquals(MiHealthImportFailureReason.PENDING, pending.reason)
        assertTrue(cloud.uploads.isEmpty())
        assertEquals(setOf(row.identity.stableKey), journal.pendingFor(account))
        val conflict = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(row, raw(2, bpm = 99))), testMode = true)
        }
        assertEquals(MiHealthImportFailureReason.CONFLICT, conflict.reason)
        assertTrue(cloud.uploads.isEmpty())
    }

    @Test
    fun newSleepWithHrvCanBeCreatedAndIsOwnedOnlyAfterReadback() = runBlocking {
        val journal = MemoryJournal()
        val cloud = FakeCloud()
        val row = sleepWithHrv()
        val result = runner(cloud, journal).run(account, plan(listOf(row)))
        assertEquals(1, result.verifiedRawCount)
        assertEquals(row.value, cloud.stored.single().second.value)
        assertTrue(journal.ownedVerifiedFingerprints(account).isNotEmpty())
    }

    @Test
    fun sleepCanReceiveMissingHrvWhilePreservingServerFields() = runBlocking<Unit> {
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            onUpload = { batch ->
                persist(batch)
                val saved = stored.single()
                stored[0] = saved.first to saved.second.copy(value = buildJsonObject {
                    saved.second.value.forEach { (key, value) -> put(key, value) }
                    put("server_extra", "kept")
                })
                MiHealthImportUploadResult(true, 0)
            }
        }
        val runner = runner(cloud, journal)
        runner.run(account, plan(listOf(sleepBase())))
        val result = runner.run(account, hrvPlan(sleepWithHrv()))
        assertEquals(1, result.verifiedRawCount)
        assertEquals(2, cloud.uploads.size)
        assertEquals(cloud.stored.single().second.value["server_extra"], cloud.uploads.last().single().value["server_extra"])
        assertNotNull(cloud.uploads.last().single().value["avg_hrv"])
    }

    @Test
    fun observedSleepBecomesOwnedOnlyAfterAnUpload() = runBlocking {
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply { persist(listOf(sleepBase())) }
        val runner = runner(cloud, journal)
        runner.run(account, plan(listOf(sleepBase())))
        assertTrue(journal.ownedVerifiedFingerprints(account).isEmpty())
        assertTrue(cloud.uploads.isEmpty())
        runner.run(account, hrvPlan(sleepWithHrv()))
        assertEquals(1, cloud.uploads.size)
        assertTrue(journal.ownedVerifiedFingerprints(account).isNotEmpty())
    }

    @Test
    fun legacySleepIsUpgradedOnlyAfterItsSummaryIsVerified() = runBlocking {
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply { persist(listOf(sleepBase())) }
        journal.seedLegacy(account, MiHealthImportRunner.journalEntry(sleepBase()))
        val result = runner(cloud, journal).run(account, hrvPlan(sleepWithHrv()))
        assertEquals(1, result.verifiedRawCount)
        assertTrue(journal.legacyVerifiedFingerprints(account).isEmpty())
        assertTrue(journal.ownedVerifiedFingerprints(account).isNotEmpty())
    }

    @Test
    fun changedHrvSummaryIsUpdatedFromTheSource() = runBlocking {
        val journal = MemoryJournal()
        val cloud = FakeCloud()
        val runner = runner(cloud, journal)
        runner.run(account, plan(listOf(sleepWithHrv())))
        val changed = sleepWithHrv().copy(value = buildJsonObject {
            sleepWithHrv().value.forEach { (key, value) -> put(key, value) }
            put("avg_hrv", 60)
        })
        runner.run(account, hrvPlan(changed))
        assertEquals(2, cloud.uploads.size)
        assertEquals(changed.value, cloud.stored.single().second.value)
    }

    @Test
    fun pendingHrvIsNeverResent() = runBlocking {
        val pendingJournal = MemoryJournal()
        val pendingCloud = FakeCloud()
        val pendingRunner = runner(pendingCloud, pendingJournal)
        pendingRunner.run(account, plan(listOf(sleepBase())))
        pendingJournal.markPending(account, listOf(MiHealthImportRunner.journalEntry(sleepWithHrv())))
        val error = assertFailsWith<MiHealthImportRunException> {
            pendingRunner.run(account, hrvPlan(sleepWithHrv()))
        }
        assertEquals(MiHealthImportFailureReason.PENDING, error.reason)
        assertEquals(1, pendingCloud.uploads.size)
    }

    @Test
    fun aCloudChangeBetweenPrecheckAndEnrichmentStopsThePost() = runBlocking {
        val journal = MemoryJournal()
        val cloud = FakeCloud()
        val runner = runner(cloud, journal)
        runner.run(account, plan(listOf(sleepBase())))
        val initialReads = cloud.readCount
        cloud.onRead = {
            if (readCount == initialReads + 2) {
                val saved = stored.single()
                stored[0] = saved.first to saved.second.copy(value = buildJsonObject {
                    saved.second.value.forEach { (key, value) -> put(key, value) }
                    put("server_new_field", "changed-after-precheck")
                })
            }
        }
        val error = assertFailsWith<MiHealthImportRunException> {
            runner.run(account, hrvPlan(sleepWithHrv()))
        }
        assertEquals(MiHealthImportFailureReason.CONFLICT, error.reason)
        assertEquals("SLEEP_REVISION_CHANGED", error.details)
        assertEquals(1, cloud.uploads.size)
        assertTrue(journal.pendingFor(account).isEmpty())
    }

    @Test
    fun acceptedUnverifiedDayDoesNotBlockLaterIndependentDaysOrRepeatPendingWrites() = runBlocking {
        // Matches the reported .3 shape: 349 pre-existing + 233 attempted,
        // 62 of those confirmed, leaving 171 unresolved before later days.
        val firstDay = (0 until 582).map { raw(it) }
        val secondDay = (0 until 960).map { raw(86_400 + it) }
        val rows = firstDay + secondDay
        val summaries = (0 until 6).map { day ->
            daily().let { it.copy(identity = it.identity.copy(time = it.identity.time + day * 86_400L)) }
        }
        val journal = MemoryJournal()
        val pauses = mutableListOf<Long>()
        val cloud = FakeCloud().apply {
            stored += firstDay.take(349).map { MiHealthImportChannel.RAW to it.asCloudRow() }
            onUpload = { batch ->
                if (batch.first().identity.channel == MiHealthImportChannel.RAW && batch.first().identity.time < timestamp + 86_400L) {
                    persist(batch.filter { it.identity.time < timestamp + 411L })
                } else persist(batch)
                MiHealthImportUploadResult(true, 0, "ok")
            }
        }
        val runner = MiHealthImportRunner(cloud, journal, readbackPause = { pauses += it })
        val first = assertFailsWith<MiHealthImportRunException> {
            runner.run(account, plan(rows + summaries))
        }
        val diagnostic = assertNotNull(first.diagnostic)
        assertEquals(1542, diagnostic.plannedRawRows)
        assertEquals(6, diagnostic.plannedDailyRows)
        assertEquals(1193, diagnostic.attemptedRawRows)
        assertEquals(1371, diagnostic.verifiedRawRows)
        assertEquals(349, diagnostic.alreadyPresentRawRows)
        assertEquals(1022, diagnostic.newlyVerifiedRawRows)
        assertEquals(171, diagnostic.readbackMissingRows)
        assertEquals(0, diagnostic.readbackMismatchedRows)
        assertEquals(0, diagnostic.readbackAmbiguousRows)
        assertEquals(0, diagnostic.readbackUnqueriedRows)
        assertEquals(0, diagnostic.notAttemptedRawRows)
        assertEquals(6, diagnostic.notAttemptedDailyRows)
        assertEquals(0, diagnostic.attemptedDailyRows)
        assertEquals(171, diagnostic.affectedRows)
        assertEquals(5, diagnostic.batchIndex) // The unresolved group's POST context.
        assertEquals(33, diagnostic.batchRows)
        assertEquals(0, diagnostic.uploadResponse?.businessCode)
        assertEquals(true, diagnostic.uploadResponse?.accepted)
        assertEquals(listOf(1_000L, 3_000L), pauses)
        assertEquals(171, journal.pendingFor(account).size)
        val postCount = cloud.uploads.size

        val second = assertFailsWith<MiHealthImportRunException> {
            runner.run(account, plan(rows + summaries))
        }
        assertEquals(MiHealthImportFailureReason.PENDING, second.reason)
        assertEquals(1371, second.verifiedRawCount)
        assertEquals(171, second.diagnostic?.blockedPendingRows)
        assertEquals(0, second.diagnostic?.attemptedRawRows)
        assertEquals(postCount + 1, cloud.uploads.size) // Only the independent fully verified day's summary.
        assertTrue(cloud.uploads.last().all { it.identity.channel == MiHealthImportChannel.DAILY_AGGREGATE })
        assertEquals(1, second.diagnostic?.verifiedDailyRows)
        assertEquals(171, journal.pendingFor(account).size)
    }

    @Test
    fun reportedHeartRatePartitionCanBeDistinguishedWithoutPostingOrLosingVerifiedRows() = runBlocking {
        val rows = (0 until 9540).map { raw(it) }
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            stored += rows.take(9322).map { MiHealthImportChannel.RAW to it.asCloudRow() }
            stored += rows.subList(9522, 9532).map { row ->
                MiHealthImportChannel.RAW to row.copy(value = buildJsonObject {
                    put("time", row.identity.time); put("bpm", unrevisableBpm)
                }).asCloudRow()
            }
            stored += rows.takeLast(8).map { MiHealthImportChannel.RAW to it.asCloudRow().copy(sid = null) }
        }
        journal.markPending(account, rows.subList(9322, 9522).map(MiHealthImportRunner::journalEntry))
        val failure = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(rows).copy(inputRecordCount = 9412))
        }
        val diagnostic = assertNotNull(failure.diagnostic)
        assertEquals(MiHealthImportStage.PRECHECK, diagnostic.stage)
        assertEquals("PENDING", diagnostic.failureReason)
        assertEquals(9412, diagnostic.inputRecords)
        assertEquals(9540, diagnostic.plannedRawRows)
        assertEquals(9322, diagnostic.verifiedRawRows)
        assertEquals(9322, diagnostic.alreadyPresentRawRows)
        assertEquals(0, diagnostic.attemptedRawRows)
        assertEquals(218, diagnostic.affectedRows)
        assertEquals(218, diagnostic.notAttemptedRawRows)
        assertEquals(200, diagnostic.blockedPendingRows)
        assertEquals(10, diagnostic.blockedConflictRows)
        assertEquals(8, diagnostic.blockedAmbiguousRows)
        assertEquals(mapOf("bpm" to 10, "sid" to 8), diagnostic.precheckMismatchFields)
        assertTrue(cloud.uploads.isEmpty())
        assertEquals(200, journal.pendingFor(account).size)
        assertNull(diagnostic.uploadResponse)
        assertNull(diagnostic.readResponse)
    }

    @Test
    fun readbackSeparatesMissingChangedAndAmbiguousTargetsWithoutCountingRetriesAgain() = runBlocking {
        val rows = listOf(raw(0), raw(1), raw(2), raw(3))
        val cloud = FakeCloud().apply {
            onUpload = { batch ->
                persist(listOf(batch[0], raw(2, bpm = 99)))
                stored += MiHealthImportChannel.RAW to batch[3].asCloudRow().copy(sid = null)
                MiHealthImportUploadResult(true, 0)
            }
        }
        val error = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(rows))
        }
        val diagnostic = assertNotNull(error.diagnostic)
        assertEquals(1, diagnostic.verifiedRawRows)
        assertEquals(1, diagnostic.readbackMissingRows)
        assertEquals(1, diagnostic.readbackMismatchedRows)
        assertEquals(1, diagnostic.readbackAmbiguousRows)
        assertEquals(0, diagnostic.readbackUnqueriedRows)
        assertEquals(mapOf("bpm" to 1, "sid" to 1), diagnostic.readbackMismatchFields)
        assertEquals(3, diagnostic.affectedRows)
        assertEquals(1, cloud.uploads.size)
    }

    @Test
    fun failedReadbackRemainsUnqueriedAndStopsAdditionalDays() = runBlocking {
        val rows = listOf(raw(0), raw(86_400))
        val journal = MemoryJournal()
        val cloud = FakeCloud().apply {
            onRead = { if (readCount > 1) throw IllegalStateException("read unavailable") }
        }
        val error = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, journal).run(account, plan(rows))
        }
        val diagnostic = assertNotNull(error.diagnostic)
        assertEquals(1, diagnostic.attemptedRawRows)
        assertEquals(1, diagnostic.notAttemptedRawRows)
        assertEquals(0, diagnostic.readbackMissingRows)
        assertEquals(1, diagnostic.readbackUnqueriedRows)
        assertEquals(1, cloud.uploads.size)
        assertEquals(setOf(rows.first().identity.stableKey), journal.pendingFor(account))
    }

    @Test
    fun delayedReadbackOnlyRetriesReadsAndConfirmsBeforeReleasingPending() = runBlocking {
        val row = raw()
        val journal = MemoryJournal()
        val pauses = mutableListOf<Long>()
        val cloud = FakeCloud().apply {
            onUpload = { MiHealthImportUploadResult(true, 0) }
            onRead = {
                // First preflight, then two stale reads, then visible data.
                if (readCount == 4) persist(listOf(row))
            }
        }
        val result = MiHealthImportRunner(cloud, journal, readbackPause = { pauses += it })
            .run(account, plan(listOf(row)))
        assertEquals(1, result.verifiedRawCount)
        assertEquals(1, cloud.uploads.size)
        assertEquals(listOf(1_000L, 3_000L), pauses)
        assertEquals(0, result.diagnostic?.readbackMissingRows)
        assertEquals(0, result.diagnostic?.readbackUnqueriedRows)
        assertTrue(journal.pendingFor(account).isEmpty())
    }

    @Test
    fun conflictingSourceTargetsAreIsolatedAndIndependentTargetsStillProgress() = runBlocking {
        val first = raw(0)
        val collision = raw(1)
        val later = raw(2)
        val cloud = FakeCloud()
        val error = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(first, collision, raw(1, bpm = 99), later)))
        }
        val diagnostic = assertNotNull(error.diagnostic)
        assertEquals(MiHealthImportFailureReason.CONFLICT, error.reason)
        assertEquals(MiHealthImportStage.PREPARE, diagnostic.stage)
        assertEquals(1, diagnostic.sourceCollisionRows)
        assertEquals(3, diagnostic.plannedRawRows)
        assertEquals(2, diagnostic.verifiedRawRows)
        assertEquals(2, diagnostic.attemptedRawRows)
        assertEquals(1, diagnostic.notAttemptedRawRows)
        assertEquals(listOf(listOf(first, later)), cloud.uploads)
        assertTrue(cloud.stored.none { it.second.time == collision.identity.time })
    }

    @Test
    fun sourceRejectsCountUniqueRecordsAndDoNotAttachPriorSuccessfulPostAsRejection() = runBlocking {
        val issue = MiHealthImportIssue("INVALID_SOURCE", setOf("source-one"), "Unusable source")
        val cloud = FakeCloud()
        val error = assertFailsWith<MiHealthImportRunException> {
            runner(cloud, MemoryJournal()).run(account, plan(listOf(raw()), listOf(issue, issue.copy(code = "INVALID_VALUE"))))
        }
        val diagnostic = assertNotNull(error.diagnostic)
        assertEquals(MiHealthImportFailureReason.SOURCE_REJECTED, error.reason)
        assertEquals(1, diagnostic.verifiedRawRows)
        assertEquals(1, diagnostic.sourceRejectedRecords)
        assertEquals(1, diagnostic.affectedRows)
        assertEquals(2, diagnostic.issueCounts.sumOf { it.occurrences })
        assertEquals(MiHealthImportStage.PREPARE, diagnostic.stage)
        assertNull(diagnostic.uploadResponse)
        assertNull(diagnostic.readResponse)
        assertNull(diagnostic.batchIndex)
        assertNull(diagnostic.batchRows)
    }

    private fun sleepBase() = raw().copy(
        identity = MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "sleep", timestamp),
        value = buildJsonObject {
            put("bedtime", timestamp - 3600); put("wake_up_time", timestamp)
            put("bed_timestamp", timestamp - 3600); put("out_bed_timestamp", timestamp)
            put("duration", 60); put("sleep_deep_duration", 0); put("sleep_light_duration", 60)
            put("sleep_rem_duration", 0); put("sleep_awake_duration", 0); put("awake_count", 0)
            put("items", buildJsonArray {
                add(buildJsonObject { put("start_time", timestamp - 3600); put("end_time", timestamp); put("state", 3) })
            })
        },
    )

    private fun sleepWithHrv() = sleepBase().copy(
        value = buildJsonObject {
            sleepBase().value.forEach { (key, value) -> put(key, value) }
            put("avg_hrv", 45); put("min_hrv", 30); put("max_hrv", 60)
        }, enrichment = MiHealthImportEnrichment.SLEEP_HRV,
    )

    private fun hrvPlan(row: MiHealthImportRow) = plan(listOf(row)).copy(metric = HealthInboundMetric.HRV)

    private fun runner(cloud: FakeCloud, journal: MemoryJournal) =
        MiHealthImportRunner(cloud, journal, readbackPause = {})

    private fun raw(index: Int = 0, bpm: Int = 61): MiHealthImportRow {
        val time = timestamp + index
        return MiHealthImportRow(
            identity = MiHealthImportIdentity(MiHealthImportChannel.RAW, MI_HEALTH_CONNECT_IMPORT_SID, "heart_rate", time),
            value = buildJsonObject { put("time", time); put("bpm", bpm) },
            zoneOffsetSeconds = 28_800,
            sourceRecordKeys = setOf("com.huami.watch.hmwatchmanager:record-$index"),
        )
    }

    private fun daily() = MiHealthImportRow(
        identity = MiHealthImportIdentity(
            MiHealthImportChannel.DAILY_AGGREGATE, MI_HEALTH_CONNECT_IMPORT_SID,
            "heart_rate", timestamp.floorDiv(86_400L) * 86_400L, "daily_report",
        ),
        value = buildJsonObject { put("avg_hr", 61) },
        zoneOffsetSeconds = 28_800,
        sourceRecordKeys = setOf("com.huami.watch.hmwatchmanager:record-0"),
        completeSourceDay = true,
    )

    private fun plan(rows: List<MiHealthImportRow>, issues: List<MiHealthImportIssue> = emptyList()) =
        MiHealthImportPlan(HealthInboundMetric.HEART_RATE, rows, issues,
            rows.flatMap { it.sourceRecordKeys }.distinct().size)

    private fun MiHealthImportRow.asCloudRow() = MiHealthImportCloudRow(
        sid = identity.sid,
        key = identity.key,
        time = identity.time,
        tag = identity.tag,
        value = value,
        zoneOffsetSeconds = zoneOffsetSeconds,
    )

    private inner class FakeCloud : MiHealthImportCloud {
        val stored = mutableListOf<Pair<MiHealthImportChannel, MiHealthImportCloudRow>>()
        val uploads = mutableListOf<List<MiHealthImportRow>>()
        var beforeUpload: (List<MiHealthImportRow>) -> Unit = {}
        var readCount = 0
        var onRead: suspend FakeCloud.() -> Unit = {}
        var onChannelRead: suspend FakeCloud.(MiHealthImportChannel) -> Unit = {}
        var onUpload: suspend FakeCloud.(List<MiHealthImportRow>) -> MiHealthImportUploadResult = { rows ->
            persist(rows)
            MiHealthImportUploadResult(true)
        }

        fun persist(rows: List<MiHealthImportRow>) {
            rows.forEach { row ->
                stored.removeAll { (channel, previous) ->
                    channel == row.identity.channel && previous.sid == row.identity.sid &&
                        previous.key == row.identity.key && previous.time == row.identity.time &&
                        previous.tag == row.identity.tag
                }
                stored += row.identity.channel to row.asCloudRow()
            }
        }

        override suspend fun readRows(
            channel: MiHealthImportChannel,
            key: String,
            fromEpochSeconds: Long,
            untilEpochSeconds: Long,
        ): List<MiHealthImportCloudRow> {
            readCount++
            onRead()
            onChannelRead(channel)
            return stored.filter { (kind, row) ->
                kind == channel && row.key == key && row.time >= fromEpochSeconds && row.time < untilEpochSeconds
            }.map { it.second }
        }

        override suspend fun uploadRows(
            rows: List<MiHealthImportRow>,
            experimentalEnabled: Boolean,
        ): MiHealthImportUploadResult {
            check(experimentalEnabled)
            check(rows.isNotEmpty())
            val channel = rows.first().identity.channel
            // These are the actual cloud adapter's limits, not runner constants.
            require(rows.size <= if (channel == MiHealthImportChannel.RAW) 50 else 30)
            check(rows.all { it.identity.channel == channel })
            beforeUpload(rows)
            uploads += rows.toList()
            return onUpload(rows)
        }
    }

    private class MemoryJournal : HealthImportJournal {
        private val pending = linkedMapOf<Pair<String, String>, HealthImportJournalEntry>()
        private val verified = linkedMapOf<Pair<String, String>, HealthImportJournalEntry>()
        private val legacy = mutableSetOf<Pair<String, String>>()
        var pendingFailure: Throwable? = null

        fun pendingFor(account: String): Set<String> = pending.keys.filter { it.first == account }.map { it.second }.toSet()
        fun verifiedFor(account: String): Set<String> = verified.keys.filter { it.first == account }.map { it.second }.toSet()
        fun seedLegacy(account: String, entry: HealthImportJournalEntry) {
            verified[account to entry.targetIdentity] = entry
            legacy += account to entry.targetIdentity
        }

        override suspend fun pendingIdentities(account: String, identities: Set<String>): Set<String> =
            pendingFor(account).intersect(identities)

        override suspend fun markPending(account: String, entries: List<HealthImportJournalEntry>) {
            pendingFailure?.let { throw it }
            entries.forEach { pending[account to it.targetIdentity] = it }
        }

        override suspend fun markVerified(account: String, entries: List<HealthImportJournalEntry>) {
            entries.forEach {
                val key = account to it.targetIdentity
                val previous = verified[key]
                val retained = if (!it.ownedUpload && previous?.ownedUpload == true &&
                    previous.contentFingerprint == it.contentFingerprint) {
                    it.copy(ownedUpload = true)
                } else it
                if (retained.ownedUpload || previous?.contentFingerprint != retained.contentFingerprint) legacy.remove(key)
                verified[key] = retained
                pending.remove(key)
            }
        }

        override suspend fun verifiedFingerprints(account: String): Map<String, String> =
            verified.filterKeys { it.first == account }.values.associate {
                HealthImportLedger.identityDigest(it.targetIdentity) to it.contentFingerprint
            }

        override suspend fun ownedVerifiedFingerprints(account: String): Map<String, String> =
            verified.filter { it.key.first == account && it.value.ownedUpload }.values.associate {
                HealthImportLedger.identityDigest(it.targetIdentity) to it.contentFingerprint
            }

        override suspend fun legacyVerifiedFingerprints(account: String): Map<String, String> =
            verified.filterKeys { it.first == account && it in legacy }.values.associate {
                HealthImportLedger.identityDigest(it.targetIdentity) to it.contentFingerprint
            }
    }
}
