package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.MiHealthImportMapper
import com.bettermifitness.sync.data.api.MiHrvImportMapper
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HealthInboundReadPolicyTest {
    private val zepp = "com.huami.watch.hmwatchmanager"
    private val self = "com.bettermifitness.sync.healthlab"
    private val from = 1_800_000_000L
    private val request = HealthInboundReadRequest(from, from + 3_600L, setOf(zepp))

    private fun origin(id: String, source: String = zepp) = HealthRecordOrigin(
        recordId = id,
        sourcePackage = source,
        clientRecordId = "source-$id",
        clientRecordVersion = 1L,
        lastModifiedEpochMillis = from * 1_000L,
    )

    @Test
    fun hrvReadKeepsRmssdPrecisionAndLatestRevisionButExcludesOtherSourcesAndEndBoundary() {
        val old = HealthInboundHrvRecord(origin("hrv"), request.fromEpochMillis + 456, 28_800, 41.25)
        val latest = old.copy(origin = old.origin.copy(clientRecordVersion = 2), rmssdMillis = 42.875)
        val other = latest.copy(origin = origin("other", "example.unselected"))
        val end = latest.copy(origin = origin("end"), epochMillis = request.untilEpochMillis)
        val actual = HealthInboundReadPolicy.hrv(listOf(old, other, end, latest), request, self)
        assertEquals(listOf(latest), actual)
        assertEquals(42.875, actual.single().rmssdMillis)
        assertEquals(request.fromEpochMillis + 456, actual.single().epochMillis)
    }

    @Test
    fun oxygenReadRetainsPercentageAndDistinctMeasurementIdsWithoutInventingManualMethod() {
        val first = HealthInboundOxygenSaturationRecord(origin("spo2-a"), request.fromEpochMillis, null, 97.6)
        val sameTimeDifferentId = first.copy(origin = origin("spo2-b"), percentage = 98.2)
        val before = first.copy(origin = origin("before"), epochMillis = request.fromEpochMillis - 1)
        val loop = first.copy(origin = origin("loop").copy(clientRecordId = "mifit-spo2-1"))
        val actual = HealthInboundReadPolicy.oxygenSaturation(
            listOf(before, loop, sameTimeDifferentId, first), request, self,
        )
        assertEquals(listOf(first, sameTimeDifferentId), actual)
        assertEquals(listOf(97.6, 98.2), actual.map { it.percentage })
        assertEquals(listOf(null, null), actual.map { it.zoneOffsetSeconds })
    }

    @Test
    fun emptySourceAllowlistCannotAccidentallyReadEveryApp() {
        assertFailsWith<IllegalArgumentException> {
            HealthInboundReadRequest(from, from + 60, emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            HealthInboundReadRequest(from, from + 60, setOf(""))
        }
    }

    @Test
    fun rejectsReversedAndOverflowingTimeWindows() {
        assertFailsWith<IllegalArgumentException> {
            HealthInboundReadRequest(from, from, setOf(zepp))
        }
        assertFailsWith<IllegalArgumentException> {
            HealthInboundReadRequest(from, Long.MAX_VALUE, setOf(zepp))
        }
    }

    @Test
    fun excludesOriginalAndExperimentalExportsAndNativeMiSourcesEvenIfAllowlisted() {
        val excluded = setOf(
            "com.bettermifitness.sync",
            "com.bettermifitness.sync.healthlab",
            "com.bettermifitness.sync.bodybridge",
            "com.bettermifitness.sync.debug",
            "example.custom.build",
            "com.mi.health",
            "com.xiaomi.wearable",
        )
        val permissive = request.copy(sourcePackages = excluded + zepp)
        excluded.forEach { source ->
            assertFalse(
                HealthInboundReadPolicy.allowsOrigin(origin("id", source), permissive, "example.custom.build"),
                source,
            )
        }
        assertTrue(HealthInboundReadPolicy.allowsOrigin(origin("zepp"), permissive, self))
        assertFalse(HealthInboundReadPolicy.allowsOrigin(
            origin("reexported").copy(clientRecordId = "mifit-hr-1800000000"), permissive, self,
        ))
    }

    @Test
    fun unknownSourcesAndMissingRecordIdentityNeverBecomeImportable() {
        assertFalse(HealthInboundReadPolicy.allowsOrigin(origin("other", "example.other"), request, self))
        assertFailsWith<IllegalStateException> {
            HealthInboundReadPolicy.allowsOrigin(origin(""), request, self)
        }
    }

    @Test
    fun heartRateUsesHalfOpenSampleWindowButRetainsRecordIdentityAndOriginalOffsets() {
        val start = request.fromEpochMillis
        val record = HealthInboundHeartRateRecord(
            origin = origin("daily-container"),
            startEpochMillis = start - 1_000L,
            endEpochMillis = request.untilEpochMillis + 1_000L,
            startZoneOffsetSeconds = 28_800,
            endZoneOffsetSeconds = 32_400,
            samples = listOf(
                HealthInboundHeartRateSample(request.untilEpochMillis, 72),
                HealthInboundHeartRateSample(start + 123L, 71),
                HealthInboundHeartRateSample(start - 1L, 70),
                HealthInboundHeartRateSample(start, 69),
            ),
        )
        val read = HealthInboundReadPolicy.heartRate(listOf(record), request, self).single()
        assertEquals(listOf(start, start + 123L), read.samples.map { it.epochMillis })
        assertEquals(listOf(69L, 71L), read.samples.map { it.beatsPerMinute })
        assertEquals(record.origin, read.origin)
        assertEquals(record.startEpochMillis, read.startEpochMillis)
        assertEquals(record.endEpochMillis, read.endEpochMillis)
        assertEquals(28_800, read.startZoneOffsetSeconds)
        assertEquals(32_400, read.endZoneOffsetSeconds)
    }

    @Test
    fun sameMeasurementTimeDoesNotCollapseDistinctHcRecordIds() {
        val sample = HealthInboundHeartRateSample(request.fromEpochMillis + 123L, 70)
        val first = HealthInboundHeartRateRecord(
            origin("a"), sample.epochMillis, sample.epochMillis + 1_000L, null, null, listOf(sample),
        )
        val second = first.copy(origin = origin("b"))
        assertEquals(2, HealthInboundReadPolicy.heartRate(listOf(first, second), request, self).size)
    }

    @Test
    fun repeatedPageRecordUsesLatestRevisionRatherThanDuplicatingOrKeepingStaleValue() {
        val first = HealthInboundRestingHeartRateRecord(origin("rhr"), request.fromEpochMillis, 28_800, 60)
        val corrected = first.copy(
            origin = first.origin.copy(clientRecordVersion = 2L, lastModifiedEpochMillis = from * 1_000L + 1),
            beatsPerMinute = 58,
        )
        val read = HealthInboundReadPolicy.restingHeartRate(listOf(corrected, first), request, self)
        assertEquals(listOf(corrected), read)
    }

    @Test
    fun equalLatestConflictingRhrRevisionsReachTheMapperWhileExactPageDuplicatesCollapse() {
        val first = HealthInboundRestingHeartRateRecord(origin("rhr"), request.fromEpochMillis, 28_800, 60)
        val differing = first.copy(beatsPerMinute = 62)
        val read = HealthInboundReadPolicy.restingHeartRate(listOf(first, first.copy(), differing), request, self)
        assertEquals(2, read.size)
        val plan = MiHealthImportMapper.restingHeartRate(read, zepp)
        assertTrue(plan.rawRows.isEmpty())
        assertEquals("CONFLICTING_SOURCE_REVISIONS", plan.issues.single().code)
        assertEquals(listOf(first), HealthInboundReadPolicy.restingHeartRate(listOf(first, first.copy()), request, self))
    }

    @Test
    fun queryBoundaryCannotResolveEqualLatestSourceConflictByDroppingOneRevision() {
        val inside = HealthInboundRestingHeartRateRecord(origin("rhr"), request.fromEpochMillis, 0, 60)
        val outside = inside.copy(epochMillis = request.untilEpochMillis)
        val read = HealthInboundReadPolicy.restingHeartRate(listOf(inside, outside), request, self)
        assertEquals(2, read.size)
        val plan = MiHealthImportMapper.restingHeartRate(read, zepp)
        assertTrue(plan.rawRows.isEmpty())
        assertEquals("CONFLICTING_SOURCE_REVISIONS", plan.issues.single().code)

        val genuinelyNewerOutside = outside.copy(origin = outside.origin.copy(clientRecordVersion = 2))
        assertTrue(HealthInboundReadPolicy.restingHeartRate(listOf(inside, genuinelyNewerOutside), request, self).isEmpty())
    }

    @Test
    fun heartRateSampleClippingDoesNotHideAnEqualLatestRevisionConflict() {
        val inside = HealthInboundHeartRateRecord(origin("hr"), request.fromEpochMillis, request.untilEpochMillis,
            0, 0, listOf(HealthInboundHeartRateSample(request.fromEpochMillis, 60)))
        val outside = inside.copy(samples = listOf(HealthInboundHeartRateSample(request.untilEpochMillis, 61)))
        val read = HealthInboundReadPolicy.heartRate(listOf(inside, outside), request, self)
        assertEquals(2, read.size)
        val plan = MiHealthImportMapper.heartRate(read, zepp)
        assertTrue(plan.rawRows.isEmpty())
        assertEquals("CONFLICTING_SOURCE_REVISIONS", plan.issues.single().code)
    }

    @Test
    fun hrvOxygenAndSleepConflictingRevisionsReachTheirMappers() {
        val firstHrv = HealthInboundHrvRecord(origin("hrv"), request.fromEpochMillis, 0, 60.0)
        val hrv = HealthInboundReadPolicy.hrv(listOf(firstHrv, firstHrv.copy(rmssdMillis = 61.0)), request, self)
        assertEquals(2, hrv.size)
        val hrvPlan = MiHrvImportMapper.sleepSummary(hrv, emptyList(), zepp, true)
        assertTrue(hrvPlan.rawRows.isEmpty())
        assertEquals("CONFLICTING_HRV_REVISIONS", hrvPlan.issues.single().code)

        val firstOxygen = HealthInboundOxygenSaturationRecord(origin("spo2"), request.fromEpochMillis, 0, 98.0)
        val oxygen = HealthInboundReadPolicy.oxygenSaturation(listOf(firstOxygen, firstOxygen.copy(percentage = 99.0)), request, self)
        assertEquals(2, oxygen.size)
        val oxygenPlan = MiHealthImportMapper.oxygenSaturation(oxygen, zepp)
        assertTrue(oxygenPlan.rawRows.isEmpty())
        assertEquals("CONFLICTING_SOURCE_REVISIONS", oxygenPlan.issues.single().code)

        val firstSleep = HealthInboundSleepRecord(origin("sleep"), request.fromEpochMillis, request.untilEpochMillis,
            0, 0, emptyList())
        val sleeps = HealthInboundReadPolicy.sleep(listOf(firstSleep, firstSleep.copy(endEpochMillis = request.untilEpochMillis + 1_000L)), request, self)
        assertEquals(2, sleeps.size)
        val sleepPlan = MiHealthImportMapper.sleep(sleeps, zepp)
        assertTrue(sleepPlan.rawRows.isEmpty())
        assertEquals("CONFLICTING_SOURCE_REVISIONS", sleepPlan.issues.single().code)
    }

    @Test
    fun invalidLatestRhrSourceOffsetIsNotHiddenByReaderDeduplication() {
        val old = HealthInboundRestingHeartRateRecord(origin("rhr"), request.fromEpochMillis, 28_800, 60)
        val newer = old.copy(origin = old.origin.copy(clientRecordVersion = 2), zoneOffsetSeconds = null)
        val read = HealthInboundReadPolicy.restingHeartRate(listOf(newer, old), request, self)
        assertEquals(listOf(newer), read)
        val plan = MiHealthImportMapper.restingHeartRate(read, zepp)
        assertTrue(plan.rawRows.isEmpty())
        assertEquals("RESTING_HEART_RATE_DATE_UNKNOWN", plan.issues.single().code)
    }

    @Test
    fun restingHeartRatePreservesSubsecondTimeAndDoesNotIncludeEndBoundary() {
        val included = HealthInboundRestingHeartRateRecord(
            origin("rhr"), request.fromEpochMillis + 789L, -18_000, 57,
        )
        val boundary = included.copy(origin = origin("end"), epochMillis = request.untilEpochMillis)
        assertEquals(listOf(included), HealthInboundReadPolicy.restingHeartRate(
            listOf(boundary, included), request, self,
        ))
    }

    @Test
    fun crossMidnightSleepIsNotClippedOrFilledAndDifferentOffsetsSurvive() {
        val start = request.fromEpochMillis - 3_600_000L
        val end = request.untilEpochMillis + 3_600_000L
        val session = HealthInboundSleepRecord(
            origin("sleep"), start, end, 28_800, 32_400,
            stages = listOf(
                HealthInboundSleepStage(start, start + 30_000L, HealthInboundSleepStageKind.UNKNOWN, 99),
                // A real gap must stay a gap; SLEEPING does not identify a deep/light/REM phase.
                HealthInboundSleepStage(start + 60_000L, end, HealthInboundSleepStageKind.SLEEPING, 2),
            ),
        )
        assertEquals(listOf(session), HealthInboundReadPolicy.sleep(listOf(session), request, self))
    }

    @Test
    fun separateNapAndNightSessionsStaySeparateAndNonOverlappingBoundaryIsExcluded() {
        val start = request.fromEpochMillis
        val first = HealthInboundSleepRecord(origin("night"), start - 60_000L, start + 1L, null, null, emptyList())
        val nap = first.copy(origin = origin("nap"), startEpochMillis = start + 60_000L, endEpochMillis = start + 90_000L)
        val ended = first.copy(origin = origin("ended"), endEpochMillis = start)
        val read = HealthInboundReadPolicy.sleep(listOf(nap, ended, first), request, self)
        assertEquals(listOf(first, nap), read)
    }

    @Test
    fun readsPastEmptyPagesAndStopsOnEmptyFinalToken() = runBlocking {
        val tokens = mutableListOf<String?>()
        val records = collectHealthReadPages { token ->
            tokens += token
            when (token) {
                null -> HealthReadPage(listOf(1, 2), "page2")
                "page2" -> HealthReadPage(emptyList(), "page3")
                "page3" -> HealthReadPage(listOf(3), "")
                else -> error("Unexpected page")
            }
        }
        assertEquals(listOf(1, 2, 3), records)
        assertEquals(listOf(null, "page2", "page3"), tokens)
    }

    @Test
    fun repeatedPaginationTokenFailsRatherThanLoopingOrReportingPartialSuccess() = runBlocking {
        var pages = 0
        assertFailsWith<IllegalStateException> {
            collectHealthReadPages {
                pages++
                HealthReadPage(listOf(pages), "same-page")
            }
        }
        assertEquals(2, pages)
    }

    @Test
    fun readFailureAfterFirstPagePropagatesInsteadOfBecomingAnEmptyOrPartialSuccess() = runBlocking {
        val denied = HealthInboundReadUnavailableException(HealthInboundMetric.SLEEP, "Read permission revoked")
        val error = assertFailsWith<HealthInboundReadUnavailableException> {
            collectHealthReadPages { token ->
                if (token != null) throw denied
                HealthReadPage(listOf(1), "next")
            }
        }
        assertSame(denied, error)
    }

    @Test
    fun oldPermissionFakesDoNotRequestWritesWhenAskedForNewReads() = runBlocking {
        var writeRequests = 0
        val requester = object : HealthPermissionRequester {
            override suspend fun requestPermissions() { writeRequests++ }
        }
        assertEquals(emptySet(), requester.requestReadPermissions(setOf(HealthInboundMetric.HEART_RATE)))
        assertEquals(0, writeRequests)
    }
}
