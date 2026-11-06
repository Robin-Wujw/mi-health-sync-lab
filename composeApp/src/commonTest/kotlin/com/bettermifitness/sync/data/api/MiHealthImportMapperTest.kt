package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthInboundHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundHeartRateSample
import com.bettermifitness.sync.health.HealthInboundOxygenSaturationRecord
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthInboundRestingHeartRateRecord
import com.bettermifitness.sync.health.HealthInboundSleepRecord
import com.bettermifitness.sync.health.HealthInboundSleepStage
import com.bettermifitness.sync.health.HealthInboundSleepStageKind
import com.bettermifitness.sync.health.HealthRecordOrigin
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiHealthImportMapperTest {
    private val source = "com.xiaomi.hm.health"
    private val day = 20_000L * 86_400L

    private fun origin(id: String) = HealthRecordOrigin(id, source, null, 1, day * 1_000L)

    @Test fun contradictoryExactInstantIsExcludedWithoutLosingValidSamplesOrOtherDays() {
        val base = hr("broken").copy(endEpochMillis = (day + 180) * 1000)
        val broken = base.copy(samples = base.samples + base.samples.single().copy(beatsPerMinute = 66) +
            HealthInboundHeartRateSample((day + 120) * 1000, 70))
        val input = listOf(broken, hr("same-time-other-record"), hr("next", day + 86_460))
        val plan = MiHealthImportMapper.heartRate(input, source, true, HealthInboundReadRequest(day, day + 172_800, setOf(source)))
        assertEquals(listOf(day + 120, day + 86_460), plan.rawRows.map { it.identity.time })
        assertEquals(listOf(day + 86_400), plan.aggregateRows.map { it.identity.time })
        val excluded = plan.issues.single { it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" }
        assertEquals(MiHealthImportIssueSeverity.WARNING, excluded.severity)
        assertEquals(setOf(day / 86_400), excluded.affectedLocalDays)
        assertFalse(plan.issues.any { it.severity == MiHealthImportIssueSeverity.REJECTED })
    }

    @Test fun fixedSourceRevisionReturnsToThePlanWithoutAnyPersistentSkip() {
        val original = hr("broken")
        val broken = original.copy(samples = original.samples + original.samples.single().copy(beatsPerMinute = 66))
        assertTrue(MiHealthImportMapper.heartRate(listOf(broken), source).rawRows.isEmpty())
        val fixed = original.copy(origin = original.origin.copy(clientRecordVersion = 2))
        val plan = MiHealthImportMapper.heartRate(listOf(broken, fixed), source)
        assertEquals(1, plan.rawRows.size)
        assertFalse(plan.issues.any { it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" })
    }

    @Test fun identicalExactInstantDuplicatesAreNotMarkedExcluded() {
        val original = hr("same")
        val plan = MiHealthImportMapper.heartRate(listOf(original.copy(samples = original.samples + original.samples)), source)
        assertEquals(1, plan.rawRows.size)
        assertTrue(plan.issues.isEmpty())
    }

    @Test fun multipleExcludedSecondsInOneRecordAreCountedSeparatelyWithoutExposingTimes() {
        val record = hr("broken").copy(endEpochMillis = (day + 180) * 1000,
            samples = listOf(day + 60, day + 120).flatMap { time -> listOf(
                HealthInboundHeartRateSample(time * 1000, 65), HealthInboundHeartRateSample(time * 1000, 66)) })
        val plan = MiHealthImportMapper.heartRate(listOf(record), source)
        assertTrue(plan.rawRows.isEmpty())
        val counts = summarizeMiHealthImportIssues(plan.issues)
        assertEquals(2, counts.single().occurrences)
        assertEquals(1, counts.single().sourceRecordCount)
    }

    @Test fun heartRateCollisionOnlyRemovesItsOwnLocalDaySummary() {
        val records = listOf(hr("a"), hr("b", bpm = 66), hr("same-day", day + 120), hr("next-day", day + 86_460))
        val plan = MiHealthImportMapper.heartRate(records, source, true, HealthInboundReadRequest(day, day + 172_800, setOf(source)))
        assertEquals(2, plan.rawRows.size)
        assertEquals(listOf(day + 86_400), plan.aggregateRows.map { it.identity.time })
        assertEquals(setOf(day / 86_400), plan.issues.single { it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" }.affectedLocalDays)
        assertFalse(plan.issues.any { it.severity == MiHealthImportIssueSeverity.REJECTED })
    }

    @Test fun unknownRejectedSourceStillPreventsAllHeartRateDailyReports() {
        val invalid = hr("bad").copy(startEpochMillis = -1)
        val plan = MiHealthImportMapper.heartRate(listOf(invalid, hr("next", day + 86_460)), source, true,
            HealthInboundReadRequest(day, day + 172_800, setOf(source)))
        assertTrue(plan.aggregateRows.isEmpty())
    }

    @Test fun collisionAtUtcBoundaryUsesSourceLocalDate() {
        val values = listOf(hr("a", day - 60), hr("b", day - 60, 66), hr("c", day + 60), hr("d", day + 86_460))
            .map { it.copy(startZoneOffsetSeconds = 28_800, endZoneOffsetSeconds = 28_800) }
        val plan = MiHealthImportMapper.heartRate(values, source, true,
            HealthInboundReadRequest(day - 86_400, day + 172_800, setOf(source)))
        assertEquals(listOf(day + 86_400), plan.aggregateRows.map { it.identity.time })
    }
    private fun hr(id: String, time: Long = day + 60, bpm: Long = 65) = HealthInboundHeartRateRecord(
        origin(id), time * 1_000L, (time + 1) * 1_000L, 0, 0,
        listOf(HealthInboundHeartRateSample(time * 1_000L, bpm)),
    )
    private fun stage(start: Long, end: Long, kind: HealthInboundSleepStageKind) =
        HealthInboundSleepStage((day + start) * 1_000L, (day + end) * 1_000L, kind)

    @Test
    fun equalSamplesCoalesceButDifferentValuesAtTheSameMiSecondAreNotChosenArbitrarily() {
        val duplicate = MiHealthImportMapper.heartRate(listOf(hr("a"), hr("b")), source)
        assertEquals(1, duplicate.rows.size)
        assertEquals(setOf("$source:a", "$source:b"), duplicate.rows.single().sourceRecordKeys)
        assertEquals("default", duplicate.rows.single().identity.sid)
        val collision = MiHealthImportMapper.heartRate(listOf(hr("a"), hr("b", bpm = 66)), source)
        assertTrue(collision.rows.isEmpty())
        assertTrue(collision.issues.any { it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" })
    }

    @Test
    fun subsecondCollisionIsReportedInsteadOfAveragingOrMovingTheSecondPoint() {
        val record = hr("a").copy(samples = listOf(
            HealthInboundHeartRateSample((day + 60) * 1_000L + 1, 65),
            HealthInboundHeartRateSample((day + 60) * 1_000L + 500, 66),
        ))
        val plan = MiHealthImportMapper.heartRate(listOf(record), source)
        assertTrue(plan.rows.isEmpty())
        assertTrue(plan.issues.any { it.code == "SUBSECOND_PRECISION" })
        assertTrue(plan.issues.any { it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" })
    }

    @Test
    fun sourceRevisionUsesTheLatestRecordAndDoesNotDuplicateItsSamples() {
        val old = hr("a", bpm = 60)
        val revised = hr("a", bpm = 65).copy(origin = origin("a").copy(lastModifiedEpochMillis = day * 1_000L + 1))
        val plan = MiHealthImportMapper.heartRate(listOf(revised, old), source)
        assertEquals(65, plan.rows.single().value.getValue("bpm").jsonPrimitive.int)
        assertTrue(plan.issues.any { it.code == "SOURCE_REVISION_DEDUPLICATED" })
    }

    @Test
    fun conflictingEqualLatestRevisionsAreRejectedWithoutChoosingPageOrderOrOlderData() {
        val old = hr("same", bpm = 59)
        val newest = hr("same", bpm = 60).copy(origin = origin("same").copy(clientRecordVersion = 2))
        val conflicting = newest.copy(samples = listOf(newest.samples.single().copy(beatsPerMinute = 61)))
        for (inputs in listOf(listOf(old, newest, conflicting), listOf(conflicting, newest, old))) {
            val plan = MiHealthImportMapper.heartRate(inputs + hr("unrelated", day + 600, 70), source)
            assertEquals(1, plan.rawRows.size)
            assertEquals(setOf("$source:unrelated"), plan.rawRows.single().sourceRecordKeys)
            assertEquals(setOf("$source:same"), plan.issues.single { it.code == "CONFLICTING_SOURCE_REVISIONS" }.sourceRecordKeys)
        }
        val identical = MiHealthImportMapper.heartRate(listOf(newest, newest.copy(), old), source)
        assertEquals(60, identical.rawRows.single().value.getValue("bpm").jsonPrimitive.int)
        assertFalse(identical.issues.any { it.severity == MiHealthImportIssueSeverity.REJECTED })
    }

    @Test
    fun explicitSourceIsRequiredAndOtherAppsCannotSlipIntoTheImport() {
        val plan = MiHealthImportMapper.heartRate(listOf(hr("a").copy(origin = origin("a").copy(sourcePackage = "other.app"))), source)
        assertTrue(plan.rows.isEmpty())
        assertEquals("INVALID_SOURCE", plan.issues.single().code)
    }

    @Test
    fun dailyCandidateRequiresCompleteLocalDayAndNeverInventsAbnormalHr() {
        val inputs = listOf(hr("a", day + 60, 60), hr("b", day + 600, 80))
        val complete = MiHealthImportMapper.heartRate(inputs, source, true,
            HealthInboundReadRequest(day, day + 86_400, setOf(source)))
        assertEquals(2, complete.rawRows.size)
        val aggregate = complete.aggregateRows.single()
        assertEquals(day, aggregate.identity.time)
        assertEquals(70, aggregate.value.getValue("avg_hr").jsonPrimitive.int)
        assertFalse("abnormal_hr_count" in aggregate.value)
        val partial = MiHealthImportMapper.heartRate(inputs, source, true,
            HealthInboundReadRequest(day + 1, day + 86_400, setOf(source)))
        assertEquals(2, partial.rawRows.size)
        assertTrue(partial.aggregateRows.isEmpty())
        assertTrue(partial.issues.any { it.code == "INCOMPLETE_LOCAL_DAY" })
    }

    @Test
    fun absentOrChangedOffsetsPreserveRawInstantsWithoutPretendingToKnowLocalDay() {
        for (record in listOf(
            hr("a").copy(startZoneOffsetSeconds = null, endZoneOffsetSeconds = null),
            hr("a").copy(startZoneOffsetSeconds = 3_600, endZoneOffsetSeconds = 7_200),
        )) {
            val plan = MiHealthImportMapper.heartRate(listOf(record), source, true,
                HealthInboundReadRequest(day - 86_400, day + 86_400, setOf(source)))
            assertEquals(day + 60, plan.rawRows.single().identity.time)
            assertTrue(plan.aggregateRows.isEmpty())
            assertFalse(plan.rawRows.single().dayAggregationEligible)
        }
    }

    @Test
    fun nonUtcDayUsesRealLocalCoverageButMiUtcCalendarDateAsAggregateIdentity() {
        // UTC 17:00 on the preceding date is 01:00 on this +08:00 local date.
        val sampleTime = day - 7 * 3_600L
        val record = hr("a", sampleTime).copy(startZoneOffsetSeconds = 28_800, endZoneOffsetSeconds = 28_800)
        val localMidnight = day - 28_800L
        val full = MiHealthImportMapper.heartRate(listOf(record), source, true,
            HealthInboundReadRequest(localMidnight, localMidnight + 86_400L, setOf(source)))
        assertEquals(sampleTime, full.rawRows.single().identity.time)
        assertEquals(28_800, full.rawRows.single().zoneOffsetSeconds)
        assertEquals(day, full.aggregateRows.single().identity.time)
        assertEquals(0, full.aggregateRows.single().zoneOffsetSeconds)

        // Covering the UTC date alone does not cover the source's local date.
        val partial = MiHealthImportMapper.heartRate(listOf(record), source, true,
            HealthInboundReadRequest(day, day + 86_400L, setOf(source)))
        assertEquals(1, partial.rawRows.size)
        assertTrue(partial.aggregateRows.isEmpty())
    }

    @Test
    fun restingHrHasItsOwnSourceFieldAndIsNeverDerivedFromHeartRate() {
        val record = HealthInboundRestingHeartRateRecord(origin("r"), (day + 100) * 1_000L, 28_800, 52)
        val plan = MiHealthImportMapper.restingHeartRate(listOf(record), source, true)
        assertEquals("resting_heart_rate", plan.rows.single().identity.key)
        assertEquals(setOf("date_time", "bpm"), plan.rows.single().value.keys)
        assertEquals(day, plan.rows.single().value.getValue("date_time").jsonPrimitive.long)
        assertEquals(day, plan.rows.single().identity.time)
        assertEquals(0, plan.rows.single().zoneOffsetSeconds)
        assertTrue(plan.aggregateRows.isEmpty())
    }

    @Test
    fun restingHrUsesOfficialCalendarDateEncodingAcrossUtcDateBoundary() {
        // Contract: Mi DailyRecordDaoUtils.reprocessModelRecordIfNeed ->
        // changeToDestZeroTimeOneDay(t, sourceOffset, 0), then date_time=t and offset=0.
        val record = HealthInboundRestingHeartRateRecord(origin("r"), (day - 7 * 3_600L) * 1_000L, 28_800, 52)
        val row = MiHealthImportMapper.restingHeartRate(listOf(record), source).rawRows.single()
        assertEquals(day, row.identity.time)
        assertEquals(day, row.value.getValue("date_time").jsonPrimitive.long)
        assertEquals(0, row.zoneOffsetSeconds)
        val negativeOffset = record.copy(epochMillis = (day + 2 * 3_600L) * 1_000L, zoneOffsetSeconds = -18_000)
        assertEquals(day - 86_400L,
            MiHealthImportMapper.restingHeartRate(listOf(negativeOffset), source).rawRows.single().identity.time)
    }

    @Test
    fun restingHrSameDateEqualObservationsMergeWithoutAnAggregationWarning() {
        val a = HealthInboundRestingHeartRateRecord(origin("a"), (day + 60) * 1_000L, 0, 52)
        val b = a.copy(origin = origin("b"), epochMillis = (day + 3_600) * 1_000L)
        val same = MiHealthImportMapper.restingHeartRate(listOf(a, b), source)
        assertEquals(setOf("$source:a", "$source:b"), same.rawRows.single().sourceRecordKeys)
        assertEquals(52, same.rawRows.single().value.getValue("bpm").jsonPrimitive.int)
        assertTrue(same.issues.isEmpty())
    }

    @Test
    fun threeDifferentTimeRhrObservationsUseAnActualMinimumWithVisiblePolicyAndProvenance() {
        // HC RHR is instantaneous; native Mi Fitness 3.59.2's daily RestHrItem
        // report takes minOfOrDefault(getRestHr), not latest or an average.
        val a = HealthInboundRestingHeartRateRecord(origin("a"), (day + 60) * 1_000L, 0, 58)
        val b = a.copy(origin = origin("b"), epochMillis = (day + 3_600) * 1_000L, beatsPerMinute = 52)
        val c = a.copy(origin = origin("c"), epochMillis = (day + 43_200) * 1_000L, beatsPerMinute = 54)
        for (inputs in listOf(listOf(a, b, c), listOf(c, b, a))) {
            val plan = MiHealthImportMapper.restingHeartRate(inputs, source)
            assertEquals(3, plan.inputRecordCount)
            val row = plan.rawRows.single()
            assertEquals(52, row.value.getValue("bpm").jsonPrimitive.int)
            assertEquals(day, row.identity.time)
            assertEquals(day, row.value.getValue("date_time").jsonPrimitive.long)
            assertEquals(0, row.zoneOffsetSeconds)
            assertEquals(setOf("$source:a", "$source:b", "$source:c"), row.sourceRecordKeys)
            assertTrue(plan.aggregateRows.isEmpty())
            val warning = plan.issues.single()
            assertEquals("RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED", warning.code)
            assertEquals(MiHealthImportIssueSeverity.WARNING, warning.severity)
            assertEquals(row.sourceRecordKeys, warning.sourceRecordKeys)
        }
    }

    @Test
    fun contradictoryRhrAtTheSameSourceInstantIsStillRejectedWithoutAveragingOrMovingIt() {
        val a = HealthInboundRestingHeartRateRecord(origin("a"), (day + 60) * 1_000L, 0, 52)
        val b = a.copy(origin = origin("b"), beatsPerMinute = 54)
        val c = a.copy(origin = origin("c"), epochMillis = (day + 3_600) * 1_000L, beatsPerMinute = 50)
        val unrelated = a.copy(origin = origin("next-day"), epochMillis = (day + 86_460) * 1_000L)
        for (inputs in listOf(listOf(a, b, c, unrelated), listOf(unrelated, c, b, a))) {
            val plan = MiHealthImportMapper.restingHeartRate(inputs, source)
            assertEquals(setOf("$source:next-day"), plan.rawRows.single().sourceRecordKeys)
            val issue = plan.issues.single()
            assertEquals("RESTING_HEART_RATE_DAY_COLLISION", issue.code)
            assertEquals(MiHealthImportIssueSeverity.REJECTED, issue.severity)
            assertEquals(setOf("$source:a", "$source:b", "$source:c"), issue.sourceRecordKeys)
        }
    }

    @Test
    fun twentySevenRhrInputsOnThirteenDatesProduceThirteenRealDailyValuesInsteadOfRejectingNormalObservations() {
        val multipleObservationDays = (0 until 12).flatMap { index ->
            val timestamp = (day + index * 86_400L + 60L) * 1_000L
            listOf(
                HealthInboundRestingHeartRateRecord(origin("a-$index"), timestamp, 0, 52),
                HealthInboundRestingHeartRateRecord(origin("b-$index"), timestamp + 1_000L, 0, 54),
            )
        }
        val validDay = (0 until 3).map { index ->
            HealthInboundRestingHeartRateRecord(origin("valid-$index"), (day + 14 * 86_400L + index) * 1_000L, 0, 55)
        }
        val plan = MiHealthImportMapper.restingHeartRate(multipleObservationDays + validDay, source)
        assertEquals(27, plan.inputRecordCount)
        assertEquals(13, plan.rawRows.size)
        assertEquals(27, plan.rawRows.flatMap { it.sourceRecordKeys }.toSet().size)
        assertTrue(plan.rawRows.take(12).all { it.value.getValue("bpm").jsonPrimitive.int == 52 })
        assertEquals(55, plan.rawRows.last().value.getValue("bpm").jsonPrimitive.int)
        assertFalse(plan.issues.any { it.severity == MiHealthImportIssueSeverity.REJECTED })
        assertEquals(12, plan.issues.size)
        assertEquals(24, plan.issues.flatMap { it.sourceRecordKeys }.toSet().size)
        assertEquals(setOf("RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED"), plan.issues.map { it.code }.toSet())
    }

    @Test
    fun invalidLatestRhrRevisionIsNotReplacedByItsOlderValidDateOrValue() {
        val old = HealthInboundRestingHeartRateRecord(origin("r"), (day + 100) * 1_000L, 0, 52)
        val newest = old.copy(origin = old.origin.copy(clientRecordVersion = 2), zoneOffsetSeconds = null)
        val plan = MiHealthImportMapper.restingHeartRate(listOf(newest, old), source)
        assertTrue(plan.rawRows.isEmpty())
        assertTrue(plan.issues.any { it.code == "RESTING_HEART_RATE_DATE_UNKNOWN" })
        val tied = MiHealthImportMapper.restingHeartRate(
            listOf(newest.copy(zoneOffsetSeconds = 0), newest.copy(zoneOffsetSeconds = 0, beatsPerMinute = 54)), source)
        assertTrue(tied.rawRows.isEmpty())
        assertEquals("CONFLICTING_SOURCE_REVISIONS", tied.issues.single().code)
    }

    @Test
    fun restingHrWithoutSourceDateDoesNotInventAUtcCalendarDate() {
        val record = HealthInboundRestingHeartRateRecord(origin("r"), (day + 100) * 1_000L, null, 52)
        val plan = MiHealthImportMapper.restingHeartRate(listOf(record), source)
        assertTrue(plan.rows.isEmpty())
        assertEquals("RESTING_HEART_RATE_DATE_UNKNOWN", plan.issues.single().code)
    }

    @Test
    fun oxygenSaturationUsesOfficialPointSchemaWithoutClaimingManualMeasurement() {
        // Contract: Mi Spo2Item @SerializedName("time") Long and ("spo2") Int;
        // recordDailySpo2 uses spo2 while recordManualSpo2 uses single_spo2.
        val record = HealthInboundOxygenSaturationRecord(origin("o"), (day + 60) * 1_000L, 28_800, 98.4)
        val plan = MiHealthImportMapper.oxygenSaturation(listOf(record), source)
        val row = plan.rawRows.single()
        assertEquals("spo2", row.identity.key)
        assertEquals(setOf("time", "spo2"), row.value.keys)
        assertEquals(98, row.value.getValue("spo2").jsonPrimitive.int)
        assertEquals(day + 60, row.value.getValue("time").jsonPrimitive.long)
        assertEquals(28_800, row.zoneOffsetSeconds)
        assertTrue(plan.issues.any { it.code == "OXYGEN_SATURATION_PRECISION" })
    }

    @Test
    fun invalidOxygenSaturationDoesNotBecomeZeroOrCreateADailyReport() {
        val invalidValues = listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 101.0)
        val inputs = invalidValues.mapIndexed { index, value ->
            HealthInboundOxygenSaturationRecord(origin("bad$index"), (day + index) * 1_000L, 0, value)
        } + HealthInboundOxygenSaturationRecord(origin("valid"), (day + 60) * 1_000L, 0, 98.0)
        val plan = MiHealthImportMapper.oxygenSaturation(inputs, source, true,
            HealthInboundReadRequest(day, day + 86_400L, setOf(source)))
        assertEquals(1, plan.rawRows.size)
        assertTrue(plan.aggregateRows.isEmpty())
        assertEquals(5, plan.issues.count { it.code == "INVALID_OXYGEN_SATURATION" })
    }

    @Test
    fun oxygenDailyReportUsesKnownValuesAndNeverInventsLowOxygenEvents() {
        val inputs = listOf(
            HealthInboundOxygenSaturationRecord(origin("a"), (day + 60) * 1_000L, 0, 96.0),
            HealthInboundOxygenSaturationRecord(origin("b"), (day + 120) * 1_000L, 0, 98.0),
        )
        val plan = MiHealthImportMapper.oxygenSaturation(inputs, source, true,
            HealthInboundReadRequest(day, day + 86_400L, setOf(source)))
        val aggregate = plan.aggregateRows.single()
        assertEquals("spo2", aggregate.identity.key)
        assertEquals(96, aggregate.value.getValue("min_spo2").jsonPrimitive.int)
        assertEquals(98, aggregate.value.getValue("max_spo2").jsonPrimitive.int)
        assertEquals(97, aggregate.value.getValue("avg_spo2").jsonPrimitive.int)
        assertEquals(day + 120, aggregate.value.getValue("latest_spo2").jsonObject.getValue("time").jsonPrimitive.long)
        assertFalse("lack_spo2_count" in aggregate.value)
        val partial = MiHealthImportMapper.oxygenSaturation(inputs, source, true,
            HealthInboundReadRequest(day + 1, day + 86_400L, setOf(source)))
        assertEquals(2, partial.rawRows.size)
        assertTrue(partial.aggregateRows.isEmpty())
    }

    @Test
    fun sleepUsesOfficialDeepAndLightCodesAndRetainsUnknownGapsWithoutInventingStages() {
        val record = HealthInboundSleepRecord(origin("s"), day * 1_000L, (day + 2_400) * 1_000L,
            28_800, 28_800, listOf(
                stage(0, 600, HealthInboundSleepStageKind.DEEP),
                stage(600, 1_200, HealthInboundSleepStageKind.UNKNOWN),
                stage(1_200, 1_800, HealthInboundSleepStageKind.LIGHT),
                stage(1_800, 2_400, HealthInboundSleepStageKind.AWAKE),
            ))
        val plan = MiHealthImportMapper.sleep(listOf(record), source, true,
            HealthInboundReadRequest(day - 86_400, day + 86_400, setOf(source)))
        val row = plan.rawRows.single()
        val value = row.value
        assertEquals(listOf(2, 3, 5), value.getValue("items").jsonArray.map { it.jsonObject.getValue("state").jsonPrimitive.int })
        assertEquals(day, value.getValue("bedtime").jsonPrimitive.long)
        assertEquals(day + 2_400, value.getValue("wake_up_time").jsonPrimitive.long)
        assertEquals(20, value.getValue("duration").jsonPrimitive.int)
        assertEquals(32, value.getValue("timezone").jsonPrimitive.int)
        assertEquals(28_800, row.zoneOffsetSeconds)
        assertTrue(value.getValue("is_uncomplete").jsonPrimitive.boolean)
        assertFalse("sleep_score" in value)
        assertFalse("avg_hrv" in value)
        assertTrue(plan.aggregateRows.isEmpty())
        assertTrue(plan.issues.any { it.code == "PARTIAL_SLEEP_STAGES" })
    }

    @Test
    fun genericSleepCountsAsConfirmedDurationButNotAsLightSleep() {
        val record = HealthInboundSleepRecord(origin("s"), day * 1_000L, (day + 600) * 1_000L,
            0, 0, listOf(stage(0, 600, HealthInboundSleepStageKind.SLEEPING)))
        val value = MiHealthImportMapper.sleep(listOf(record), source).rows.single().value
        assertEquals(10, value.getValue("duration").jsonPrimitive.int)
        assertEquals(0, value.getValue("sleep_light_duration").jsonPrimitive.int)
        assertTrue(value.getValue("items").jsonArray.isEmpty())
    }

    @Test
    fun sleepTotalsUseDurationsRatherThanRoundingEachShortStageUpToAMinute() {
        val record = HealthInboundSleepRecord(origin("s"), day * 1_000L, (day + 80) * 1_000L,
            0, 0, listOf(stage(0, 40, HealthInboundSleepStageKind.DEEP), stage(40, 80, HealthInboundSleepStageKind.LIGHT)))
        val value = MiHealthImportMapper.sleep(listOf(record), source).rows.single().value
        assertEquals(1, value.getValue("duration").jsonPrimitive.int)
        assertEquals(0, value.getValue("sleep_deep_duration").jsonPrimitive.int)
        assertEquals(0, value.getValue("sleep_light_duration").jsonPrimitive.int)
        assertEquals(2, value.getValue("items").jsonArray.size)
    }

    @Test
    fun overlappingStagesAreNotSilentlyTrimmed() {
        val record = HealthInboundSleepRecord(origin("s"), day * 1_000L, (day + 1_200) * 1_000L,
            0, 0, listOf(stage(0, 900, HealthInboundSleepStageKind.DEEP), stage(600, 1_200, HealthInboundSleepStageKind.LIGHT)))
        val plan = MiHealthImportMapper.sleep(listOf(record), source)
        assertTrue(plan.rows.isEmpty())
        assertTrue(plan.issues.any { it.code == "INVALID_SLEEP_STAGES" })
    }

    @Test
    fun validFullyCoveredSleepProducesRawPlusACompleteWakeDayCandidate() {
        val record = HealthInboundSleepRecord(origin("s"), day * 1_000L, (day + 1_800) * 1_000L,
            0, 0, listOf(stage(0, 600, HealthInboundSleepStageKind.DEEP),
                stage(600, 1_200, HealthInboundSleepStageKind.REM), stage(1_200, 1_800, HealthInboundSleepStageKind.LIGHT)))
        val plan = MiHealthImportMapper.sleep(listOf(record), source, true,
            HealthInboundReadRequest(day, day + 86_400, setOf(source)))
        assertEquals(1, plan.rawRows.size)
        assertEquals(1, plan.aggregateRows.size)
        assertEquals(30, plan.aggregateRows.single().value.getValue("total_duration").jsonPrimitive.int)
        assertFalse("sleep_score" in plan.aggregateRows.single().value)
    }
}
