package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiPendingImportProbeResult
import com.bettermifitness.sync.health.HealthInboundHrvRecord
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthInboundRestingHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthMetricReader
import com.bettermifitness.sync.health.HealthRecordOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiHealthLiveInspectionTest {
    private val source = "com.huami.watch.hmwatchmanager"
    private val day = 1_730_592_000L
    private val request = HealthInboundReadRequest(day, day + 86_400L, setOf(source))
    private fun origin(id: String) = HealthRecordOrigin(id, source, "private-client-$id", 1, day * 1_000L)

    @Test
    fun differentTimeRhrUsesSharedDailyPolicyButInspectionDoesNotClaimUploadOrVerification() = runTest {
        val reader = object : HealthMetricReader {
            override suspend fun readRestingHeartRate(request: HealthInboundReadRequest): List<HealthInboundRestingHeartRateRecord> {
                assertEquals(this@MiHealthLiveInspectionTest.request, request)
                return listOf(71L, 69L, 70L).mapIndexed { index, value ->
                    HealthInboundRestingHeartRateRecord(origin("private-source-$index"),
                        (day + 3_600L + index) * 1_000L, 0, value)
                }
            }
        }
        val plan = prepareMiHealthImportPlan(HealthInboundMetric.RESTING_HEART_RATE, reader, request, source)
        assertEquals(3, plan.inputRecordCount)
        assertEquals(1, plan.rawRows.size)
        val diagnostic = plan.inspectionDiagnostic()
        assertEquals(0, diagnostic.sourceRejectedRecords)
        assertEquals(0, diagnostic.attemptedRawRows)
        assertEquals(0, diagnostic.verifiedRawRows)
        assertEquals(1, diagnostic.notAttemptedRawRows)
        val report = MiHealthLiveInspection(diagnostic, MiPendingImportProbeResult()).copyableText("test")
        assertTrue("inspect_only=true" in report)
        assertTrue("health_data_writes_attempted=0" in report)
        assertTrue("RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED" in report)
        assertFalse("private-source" in report)
        assertFalse("private-client" in report)
        assertFalse(day.toString() in report)
        assertFalse("bpm=" in report)
    }

    @Test
    fun contradictorySameInstantRhrIsReportedWithoutAnyMappedOrVerifiedRow() = runTest {
        val reader = object : HealthMetricReader {
            override suspend fun readRestingHeartRate(request: HealthInboundReadRequest) = listOf(71L, 69L, 70L).mapIndexed { index, value ->
                HealthInboundRestingHeartRateRecord(origin("conflicting-$index"), (day + 3_600L) * 1_000L, 0, value)
            }
        }
        val diagnostic = prepareMiHealthImportPlan(HealthInboundMetric.RESTING_HEART_RATE, reader, request, source)
            .inspectionDiagnostic()
        assertEquals(3, diagnostic.sourceRejectedRecords)
        assertEquals(0, diagnostic.plannedRawRows)
        assertEquals(0, diagnostic.attemptedRawRows)
        assertEquals(0, diagnostic.verifiedRawRows)
        assertEquals("SOURCE_REJECTED", diagnostic.failureReason)
    }

    @Test
    fun hrvInspectionUsesSameBoundedSourceRequestForHrvAndSleep() = runTest {
        val calls = mutableListOf<Pair<String, HealthInboundReadRequest>>()
        val reader = object : HealthMetricReader {
            override suspend fun readHrv(request: HealthInboundReadRequest): List<HealthInboundHrvRecord> {
                calls += "hrv" to request
                return listOf(HealthInboundHrvRecord(origin("private-hrv"), (day + 3_600L) * 1_000L, 0, 36.0))
            }
            override suspend fun readSleep(request: HealthInboundReadRequest): List<HealthInboundSleepRecord> {
                calls += "sleep" to request
                return emptyList()
            }
        }
        val diagnostic = prepareMiHealthImportPlan(HealthInboundMetric.HRV, reader, request, source).inspectionDiagnostic()
        assertEquals(listOf("sleep" to request, "hrv" to request), calls)
        assertEquals(1, diagnostic.inputRecords)
        assertEquals(0, diagnostic.plannedRawRows)
        assertEquals(0, diagnostic.sourceRejectedRecords)
        assertTrue(diagnostic.issueCounts.any { it.code == "HRV_NO_MATCHING_SLEEP" })
    }

    @Test
    fun oneSevenAndThirtyDayRangesUseTheSameCompleteOverlappingSleepHrvSummary() = runTest {
        val sleep = HealthInboundSleepRecord(origin("night"), day * 1_000,
            (day + 8 * 3_600) * 1_000, 0, 0, listOf(com.bettermifitness.sync.health.HealthInboundSleepStage(
                day * 1_000, (day + 8 * 3_600) * 1_000,
                com.bettermifitness.sync.health.HealthInboundSleepStageKind.LIGHT)))
        val samples = listOf(1 to 40.0, 5 to 80.0).map { (hour, value) ->
            HealthInboundHrvRecord(origin("hrv-$hour"), (day + hour * 3_600) * 1_000, 0, value)
        }
        val calls = mutableListOf<HealthInboundReadRequest>()
        val reader = object : HealthMetricReader {
            override suspend fun readSleep(request: HealthInboundReadRequest) = listOf(sleep)
            override suspend fun readHrv(request: HealthInboundReadRequest): List<HealthInboundHrvRecord> {
                calls += request
                return samples.filter { it.epochMillis >= request.fromEpochMillis && it.epochMillis < request.untilEpochMillis }
            }
        }
        val until = day + 28 * 3_600
        val rows = listOf(1, 7, 30).map { days ->
            prepareMiHealthImportPlan(HealthInboundMetric.HRV, reader,
                HealthInboundReadRequest(until - days * 86_400, until, setOf(source)), source).rawRows.single()
        }
        assertEquals(1, rows.map { it.canonicalContent }.distinct().size)
        assertEquals(1, rows.map { it.identity }.distinct().size)
        assertEquals(day, calls.first().fromEpochSeconds)
        assertEquals("60", rows.first().value.getValue("avg_hrv").toString())
    }

    @Test
    fun restingDailyMinimumIsStableWhenTheRollingRangeExcludesAnEarlierLowerMeasurement() = runTest {
        val midnight = day.floorDiv(86_400) * 86_400
        val records = listOf(1 to 60L, 7 to 65L).map { (hour, bpm) ->
            HealthInboundRestingHeartRateRecord(origin("rhr-$hour"), (midnight + hour * 3600) * 1000, 0, bpm)
        }
        val reader = object : HealthMetricReader {
            override suspend fun readRestingHeartRate(request: HealthInboundReadRequest) = records.filter {
                it.epochMillis >= request.fromEpochMillis && it.epochMillis < request.untilEpochMillis
            }
        }
        val until = midnight + 28 * 3600
        val plans = listOf(1, 7, 30).map { days -> prepareMiHealthImportPlan(HealthInboundMetric.RESTING_HEART_RATE,
            reader, HealthInboundReadRequest(until - days * 86_400, until, setOf(source)), source) }
        assertEquals(1, plans.map { it.rawRows.single().canonicalContent }.distinct().size)
        assertEquals("60", plans.first().rawRows.single().value.getValue("bpm").toString())
        assertEquals(2, plans.first().inputRecordCount)
    }

    @Test
    fun restingContextUsesTheSourceTimezoneAndDoesNotAddAnUnselectedCalendarDate() = runTest {
        val utcDay = day.floorDiv(86_400) * 86_400
        val offset = 28_800
        val midnight = utcDay - offset
        val records = listOf(-1 to 45L, 1 to 59L, 7 to 65L).map { (hour, bpm) ->
            HealthInboundRestingHeartRateRecord(origin("rhr-$hour"), (midnight + hour * 3600) * 1000, offset, bpm)
        }
        val reader = object : HealthMetricReader {
            override suspend fun readRestingHeartRate(request: HealthInboundReadRequest) = records.filter {
                it.epochMillis >= request.fromEpochMillis && it.epochMillis < request.untilEpochMillis
            }
        }
        val plan = prepareMiHealthImportPlan(HealthInboundMetric.RESTING_HEART_RATE, reader,
            HealthInboundReadRequest(midnight + 4 * 3600, midnight + 12 * 3600, setOf(source)), source)
        assertEquals(1, plan.rawRows.size)
        assertEquals(utcDay, plan.rawRows.single().identity.time)
        assertEquals("59", plan.rawRows.single().value.getValue("bpm").toString())
        assertEquals(0, plan.rawRows.single().zoneOffsetSeconds)
    }
}
