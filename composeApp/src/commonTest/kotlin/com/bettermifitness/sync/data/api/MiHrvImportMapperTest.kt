package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthInboundHrvRecord
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStage
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import com.bettermifitness.sync.health.HealthRecordOrigin
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiHrvImportMapperTest {
    private val source = "com.huami.watch.hmwatchmanager"
    private val start = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun disabledExperimentalMappingCannotGenerateAnUpload() {
        assertFailsWith<IllegalStateException> {
            MiHrvImportMapper.sleepSummary(listOf(hrv("a", 1, 61.0)), listOf(sleep()), source)
        }
    }

    @Test
    fun onlyThreeIntegerSummaryFieldsAreAddedToTheUnchangedSleepPayload() {
        val sleep = sleep()
        val samples = listOf(hrv("a", 1, 51.2), hrv("b", 2, 60.8), hrv("c", 3, 71.0))
        val base = MiHealthImportMapper.sleep(listOf(sleep), source).rawRows.single()
        val plan = map(samples, listOf(sleep))
        val row = plan.rawRows.single()

        assertEquals(HealthInboundMetric.HRV, plan.metric)
        assertEquals(base.identity, row.identity)
        assertEquals(base.zoneOffsetSeconds, row.zoneOffsetSeconds)
        assertEquals(base.value.toMap(), row.value.filterKeys { it !in setOf("avg_hrv", "min_hrv", "max_hrv") })
        assertEquals(61, row.value.getValue("avg_hrv").jsonPrimitive.int)
        assertEquals(51, row.value.getValue("min_hrv").jsonPrimitive.int)
        assertEquals(71, row.value.getValue("max_hrv").jsonPrimitive.int)
        assertEquals(MiHealthImportEnrichment.SLEEP_HRV, row.enrichment)
        assertTrue(row.sourceRecordKeys.containsAll(samples.map { it.origin.stableRecordKey } + sleep.origin.stableRecordKey))
        assertTrue(plan.aggregateRows.isEmpty())
        assertFalse(row.dayAggregationEligible)
        assertTrue(plan.issues.any { it.code == "RMSSD_EXPERIMENTAL_MAPPING" && it.severity == MiHealthImportIssueSeverity.WARNING })
        assertTrue(plan.issues.any { it.code == "HRV_INTEGER_ROUNDING" })
        assertTrue(row.value.keys.none { it.startsWith("hrv_base") || it.contains("quantile") ||
            it in setOf("hrv_std", "hrv_median", "hrv_analysis_timestamp", "hrvDetailRecords", "sleep_score") })
        assertEquals(listOf(51.2, 60.8, 71.0), samples.map { it.rmssdMillis })
    }

    @Test
    fun knownAwakeOutOfBedAndAwakeInBedSamplesDoNotBiasTheSleepSummary() {
        val sleep = sleep().copy(stages = listOf(
            phase(0, 1, HealthInboundSleepStageKind.DEEP),
            phase(1, 2, HealthInboundSleepStageKind.AWAKE),
            phase(2, 3, HealthInboundSleepStageKind.OUT_OF_BED),
            phase(3, 4, HealthInboundSleepStageKind.AWAKE_IN_BED),
            phase(4, 8, HealthInboundSleepStageKind.SLEEPING),
        ))
        val samples = listOf(
            hrv("asleep-1", 0, 60.0).copy(epochMillis = start + hour / 2),
            hrv("awake", 1, 180.0).copy(epochMillis = start + hour + 1),
            hrv("out", 2, 150.0).copy(epochMillis = start + hour * 2 + 1),
            hrv("in-bed-awake", 3, 180.0).copy(epochMillis = start + hour * 3 + 1),
            hrv("asleep-2", 5, 80.0),
        )

        val plan = map(samples, listOf(sleep))
        assertEquals(70, plan.rawRows.single().value.getValue("avg_hrv").jsonPrimitive.int)
        assertEquals(3, plan.issues.count { it.code == "HRV_AWAKE_SAMPLE_EXCLUDED" })
        assertFalse(plan.rawRows.single().sourceRecordKeys.contains(samples[1].origin.stableRecordKey))
    }

    @Test
    fun absentSleepPhasesAndWakeTimestampAreExplicitIntervalAssumptions() {
        val sleep = sleep().copy(stages = emptyList())
        val sample = hrv("at-waking", 8, 63.0)
        val plan = map(listOf(sample), listOf(sleep))

        assertEquals(63, plan.rawRows.single().value.getValue("avg_hrv").jsonPrimitive.int)
        assertTrue(plan.issues.any { it.code == "HRV_SLEEP_RANGE_ASSUMPTION" })
        val base = MiHealthImportMapper.sleep(listOf(sleep), source).rawRows.single()
        assertEquals(base.value["items"], plan.rawRows.single().value["items"])
        assertTrue(plan.aggregateRows.isEmpty())
    }

    @Test
    fun foreignMissingAndOverlappingSleepSourcesDoNotGetAnInventedAssociation() {
        val sample = hrv("a", 2, 60.0)
        val foreignSample = sample.copy(origin = sample.origin.copy(sourcePackage = "example.other"))
        val invalidSource = map(listOf(foreignSample), listOf(sleep()))
        assertTrue(invalidSource.rows.isEmpty())
        assertTrue(invalidSource.issues.any { it.code == "INVALID_SOURCE" })

        val foreignSleep = sleep().copy(origin = origin("foreign", "example.other"))
        val missing = map(listOf(sample), listOf(foreignSleep))
        assertTrue(missing.rows.isEmpty())
        assertTrue(missing.issues.any { it.code == "HRV_NO_MATCHING_SLEEP" && it.severity == MiHealthImportIssueSeverity.WARNING })

        val overlapping = map(listOf(sample), listOf(sleep(), sleep(id = "overlap", startHour = 1)))
        assertTrue(overlapping.rows.isEmpty())
        assertTrue(overlapping.issues.any { it.code == "HRV_AMBIGUOUS_SLEEP" && it.severity == MiHealthImportIssueSeverity.WARNING })
    }

    @Test
    fun invalidNewestRevisionDoesNotFallBackToAnOldValueAndNothingIsClamped() {
        val old = hrv("revised", 1, 60.0)
        val badNewest = old.copy(origin = old.origin.copy(clientRecordVersion = 2, lastModifiedEpochMillis = start + 1),
            rmssdMillis = Double.NaN)
        val invalid = listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, 201.0, Int.MAX_VALUE.toDouble() + 1)
            .mapIndexed { index, value -> hrv("invalid-$index", 2, value) }
        val plan = map(listOf(old, badNewest) + invalid, listOf(sleep()))

        assertTrue(plan.rows.isEmpty())
        assertEquals(7, plan.issues.count { it.code == "INVALID_HRV_SAMPLE" })
        val subMillisecond = map(listOf(hrv("tiny", 1, 0.2)), listOf(sleep()))
        assertTrue(subMillisecond.rows.isEmpty())
        assertTrue(subMillisecond.issues.any { it.code == "INVALID_HRV_SAMPLE" })
        val boundaries = map(listOf(hrv("lower", 1, 1.0), hrv("upper", 2, 200.0)), listOf(sleep()))
        assertEquals(1, boundaries.rawRows.single().value.getValue("min_hrv").jsonPrimitive.int)
        assertEquals(200, boundaries.rawRows.single().value.getValue("max_hrv").jsonPrimitive.int)
        assertTrue(boundaries.issues.none { it.severity == MiHealthImportIssueSeverity.REJECTED })
    }

    @Test
    fun identicalDuplicatePointsCountOnceAndDifferentValuesAtOneTimeAreRejected() {
        val first = hrv("a", 1, 40.0)
        val duplicate = first.copy(origin = origin("duplicate"))
        val other = hrv("b", 2, 80.0)
        val plan = map(listOf(first, duplicate, other), listOf(sleep()))
        assertEquals(60, plan.rawRows.single().value.getValue("avg_hrv").jsonPrimitive.int)
        assertTrue(plan.issues.any { it.code == "HRV_DUPLICATE_SAMPLE" })

        val conflicting = duplicate.copy(rmssdMillis = 50.0)
        val rejected = map(listOf(first, conflicting), listOf(sleep()))
        assertTrue(rejected.rows.isEmpty())
        assertTrue(rejected.issues.any { it.code == "HRV_TIME_COLLISION" })
    }

    @Test
    fun latestSourceRevisionWinsButTiedConflictingRevisionsAreNotArbitrarilyChosen() {
        val old = hrv("same", 1, 40.0)
        val updated = old.copy(origin = old.origin.copy(clientRecordVersion = 2, lastModifiedEpochMillis = start + 1),
            rmssdMillis = 80.0)
        val plan = map(listOf(updated, old), listOf(sleep()))
        assertEquals(80, plan.rawRows.single().value.getValue("avg_hrv").jsonPrimitive.int)
        val tie = map(listOf(updated, updated.copy(rmssdMillis = 81.0)), listOf(sleep()))
        assertTrue(tie.rows.isEmpty())
        assertTrue(tie.issues.any { it.code == "CONFLICTING_HRV_REVISIONS" })
    }

    @Test
    fun invalidSleepTimelineIsRejectedByTheExistingEncoderWithoutAnHrvOnlySleepRecord() {
        val invalidSleep = sleep().copy(stages = listOf(phase(0, 9, HealthInboundSleepStageKind.DEEP)))
        val plan = map(listOf(hrv("a", 1, 60.0)), listOf(invalidSleep))

        assertTrue(plan.rows.isEmpty())
        assertTrue(plan.issues.any { it.code == "INVALID_SLEEP_STAGES" })
    }

    @Test
    fun separateSleepSessionsKeepSeparateSampleSummariesAndOutsideSamplesAreNotMoved() {
        val night = sleep()
        val nap = sleep("nap", startHour = 12, durationHours = 1)
        val plan = map(listOf(hrv("night", 2, 50.0), hrv("nap-hrv", 12, 80.0), hrv("outside", 10, 100.0)),
            listOf(night, nap))

        assertEquals(2, plan.rawRows.size)
        assertEquals(50, plan.rawRows.single { night.origin.stableRecordKey in it.sourceRecordKeys }
            .value.getValue("avg_hrv").jsonPrimitive.int)
        assertEquals(80, plan.rawRows.single { nap.origin.stableRecordKey in it.sourceRecordKeys }
            .value.getValue("avg_hrv").jsonPrimitive.int)
        assertTrue(plan.issues.any { it.code == "HRV_NO_MATCHING_SLEEP" && it.severity == MiHealthImportIssueSeverity.WARNING })
        assertTrue(plan.issues.none { it.severity == MiHealthImportIssueSeverity.REJECTED })
        assertTrue(plan.aggregateRows.isEmpty())
    }

    @Test
    fun tenValidNightSummariesRemainUsableWhenOneHundredSeventyDaytimeSamplesAreOutsideTheMapping() {
        val sleeps = (0 until 10).map { sleep("night-$it", startHour = it * 24) }
        val nightSamples = sleeps.flatMapIndexed { night, sleep ->
            (0 until if (night < 8) 272 else 271).map { index ->
                HealthInboundHrvRecord(origin("night-$night-$index"), sleep.startEpochMillis + index * 60_000L,
                    28_800, 60.0)
            }
        }
        val daytimeSamples = sleeps.flatMapIndexed { night, sleep ->
            (0 until 17).map { index ->
                HealthInboundHrvRecord(origin("day-$night-$index"), sleep.startEpochMillis + 10 * hour + index * 60_000L,
                    28_800, 150.0)
            }
        }
        val plan = map(nightSamples + daytimeSamples, sleeps)
        assertEquals(2_888, plan.inputRecordCount)
        assertEquals(10, plan.rawRows.size)
        assertTrue(plan.rawRows.all { it.value.getValue("avg_hrv").jsonPrimitive.int == 60 })
        val excluded = plan.issues.filter { it.code == "HRV_NO_MATCHING_SLEEP" }
        assertEquals(170, excluded.size)
        assertEquals(170, excluded.flatMap { it.sourceRecordKeys }.toSet().size)
        assertTrue(excluded.all { it.severity == MiHealthImportIssueSeverity.WARNING })
        assertTrue(plan.issues.none { it.severity == MiHealthImportIssueSeverity.REJECTED })
        assertFalse(plan.rawRows.flatMap { it.sourceRecordKeys }.any { key -> daytimeSamples.any { it.origin.stableRecordKey == key } })
    }

    @Test
    fun equalLatestSleepRevisionConflictCannotAssignHrvToAnArbitraryTimeline() {
        val original = sleep()
        val changed = original.copy(stages = listOf(phase(0, 8, HealthInboundSleepStageKind.AWAKE)))
        val plan = map(listOf(hrv("rmssd", 1, 60.0)), listOf(original, changed))
        assertTrue(plan.rawRows.isEmpty())
        assertTrue(plan.issues.any { it.code == "CONFLICTING_SOURCE_REVISIONS" && it.severity == MiHealthImportIssueSeverity.REJECTED })
        val identical = map(listOf(hrv("rmssd", 1, 60.0)), listOf(original, original.copy()))
        assertEquals(1, identical.rawRows.size)
        assertTrue(identical.issues.none { it.severity == MiHealthImportIssueSeverity.REJECTED })
    }

    private fun map(hrv: List<HealthInboundHrvRecord>, sleeps: List<HealthInboundSleepRecord>) =
        MiHrvImportMapper.sleepSummary(hrv, sleeps, source, experimentalRmssdMappingEnabled = true)

    @Test
    fun equivalentSleepIdsShareOneSummaryWithoutCreatingAnAmbiguousAssociation() {
        val first = sleep()
        val duplicate = first.copy(origin = origin("same-sleep-another-id"))
        val plan = map(listOf(hrv("a", 1, 40.0), hrv("b", 2, 80.0)), listOf(first, duplicate))
        assertEquals(1, plan.rawRows.size)
        assertEquals(60, plan.rawRows.single().value.getValue("avg_hrv").jsonPrimitive.int)
        assertTrue(plan.rawRows.single().sourceRecordKeys.containsAll(
            listOf(first.origin.stableRecordKey, duplicate.origin.stableRecordKey)))
        assertFalse(plan.issues.any { it.code == "HRV_AMBIGUOUS_SLEEP" })
    }

    @Test
    fun adjoiningSessionsAssignBoundarySampleToTheSessionContainingIt() {
        val first = sleep()
        val second = sleep("next", startHour = 8, durationHours = 1)
        val plan = map(listOf(hrv("at-boundary", 8, 55.0)), listOf(first, second))
        assertEquals(1, plan.rawRows.size)
        assertTrue(second.origin.stableRecordKey in plan.rawRows.single().sourceRecordKeys)
        assertFalse(first.origin.stableRecordKey in plan.rawRows.single().sourceRecordKeys)
        assertFalse(plan.issues.any { it.code == "HRV_AMBIGUOUS_SLEEP" })
    }

    @Test
    fun overlappingSleepSamplesAreExcludedWhileIndependentSummariesRemainUsable() {
        val first = sleep()
        val overlap = sleep("overlap", startHour = 6, durationHours = 1)
        val plan = map(listOf(hrv("valid", 1, 40.0), hrv("ambiguous", 6, 150.0)), listOf(first, overlap))
        assertEquals(1, plan.rawRows.size)
        assertEquals(40, plan.rawRows.single().value.getValue("avg_hrv").jsonPrimitive.int)
        assertTrue(plan.issues.any { it.code == "HRV_AMBIGUOUS_SLEEP" && it.severity == MiHealthImportIssueSeverity.WARNING })
        assertFalse(plan.issues.any { it.severity == MiHealthImportIssueSeverity.REJECTED })
    }

    private fun origin(id: String, packageName: String = source) = HealthRecordOrigin(id, packageName, null, 1, start)

    private fun hrv(id: String, atHour: Int, value: Double) =
        HealthInboundHrvRecord(origin(id), start + atHour * hour, 28_800, value)

    private fun sleep(id: String = "sleep", startHour: Int = 0, durationHours: Int = 8): HealthInboundSleepRecord {
        val begin = start + startHour * hour
        val end = begin + durationHours * hour
        return HealthInboundSleepRecord(origin(id), begin, end, 28_800, 28_800,
            listOf(HealthInboundSleepStage(begin, end, HealthInboundSleepStageKind.LIGHT)))
    }

    private fun phase(fromHour: Int, untilHour: Int, kind: HealthInboundSleepStageKind) =
        HealthInboundSleepStage(start + fromHour * hour, start + untilHour * hour, kind)
}
