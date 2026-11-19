package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.health.HealthInboundHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundHeartRateSample
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundOxygenSaturationRecord
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStage
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import com.bettermifitness.sync.health.HealthMetricReader
import com.bettermifitness.sync.health.HealthRecordOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MiSleepVitalsPlanningTest {
    private val source = "com.huami.watch.hmwatchmanager"
    private val start = 1_800_000_000L
    private val origin = HealthRecordOrigin("sleep", source, null, 1, 1)
    private val sleep = HealthInboundSleepRecord(origin, start * 1_000, (start + 600) * 1_000, 0, 0,
        listOf(HealthInboundSleepStage(start * 1_000, (start + 600) * 1_000, HealthInboundSleepStageKind.LIGHT)))

    @Test fun rollingRangesReadTheWholeSleepForBothHeartAndOxygen() = runTest {
        val reads = mutableListOf<HealthInboundReadRequest>()
        val reader = object : HealthMetricReader {
            override suspend fun readSleep(request: HealthInboundReadRequest) = listOf(sleep)
            override suspend fun readHeartRate(request: HealthInboundReadRequest): List<HealthInboundHeartRateRecord> {
                reads += request
                return listOf(HealthInboundHeartRateRecord(origin.copy(recordId = "heart"), start * 1_000,
                    (start + 600) * 1_000, 0, 0, listOf(HealthInboundHeartRateSample((start + 60) * 1_000, 60))))
            }
            override suspend fun readOxygenSaturation(request: HealthInboundReadRequest): List<HealthInboundOxygenSaturationRecord> {
                reads += request
                return emptyList()
            }
        }
        val row = prepareMiHealthImportPlan(HealthInboundMetric.SLEEP, reader,
            HealthInboundReadRequest(start + 300, start + 900, setOf(source)), source).rawRows.single()
        assertEquals(JsonPrimitive(60), row.value["avg_hr"])
        assertTrue(reads.all { it.fromEpochSeconds == start && it.untilEpochSeconds == start + 900 })
    }

    @Test fun deniedOptionalReadsKeepSleepStagesAndReportTheUnavailableSource() = runTest {
        val reader = object : HealthMetricReader {
            override suspend fun readSleep(request: HealthInboundReadRequest) = listOf(sleep)
        }
        val plan = prepareMiHealthImportPlan(HealthInboundMetric.SLEEP, reader,
            HealthInboundReadRequest(start, start + 900, setOf(source)), source)
        assertEquals(1, plan.rawRows.size)
        assertTrue(plan.issues.any { it.code == "SLEEP_VITALS_READ_UNAVAILABLE" })
    }

    @Test fun cancellationOfAnOptionalSourceStillCancelsTheSync() = runTest {
        val reader = object : HealthMetricReader {
            override suspend fun readSleep(request: HealthInboundReadRequest) = listOf(sleep)
            override suspend fun readHeartRate(request: HealthInboundReadRequest): List<HealthInboundHeartRateRecord> =
                throw CancellationException("synthetic cancellation")
        }
        assertFailsWith<CancellationException> { prepareMiHealthImportPlan(HealthInboundMetric.SLEEP, reader,
            HealthInboundReadRequest(start, start + 900, setOf(source)), source) }
    }
}
