package com.bettermifitness.sync.zepp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlinx.datetime.TimeZone

class ZeppDecoderTest {
    private val time = 1_800_000_000L
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun decode(stream: ZeppStream, raw: String) = ZeppDecoder.decode(stream, listOf(obj(raw)), time + 10)
    private fun pai(extra: String = "") = """{"timestamp":1800000000000,"timeZone":32,
        "dailyPai":12.5,"totalPai":88,"lowZonePai":1.5,"mediumZonePai":3,"highZonePai":8 $extra}"""

    @Test fun paiPreservesDailyAndTotalWithRealSourceZone() {
        val out = decode(ZeppStream.PAI, pai()).observations
        assertEquals(5, out.size)
        assertEquals(12.5, out.single { it.metric == "pai_daily" }.value)
        assertEquals(88.0, out.single { it.metric == "pai_total" }.value)
        assertTrue(out.all { it.observedSeconds == time && it.zoneOffsetSeconds == 28_800 && it.fetchedSeconds == time + 10 })
        val rows = MiZeppScoreMapper.map(out).rows
        assertEquals("pai", rows.single().identity.key)
        assertEquals(JsonPrimitive(12.5), rows.single().value["daily_pai"])
        assertFalse(rows.single().value.toString().contains("vitality"))
    }
    @Test fun zeroIsRecordedForPaiButMissingIsNeverFilled() {
        val points = decode(ZeppStream.PAI, """{"time":1800000000,"totalPai":0,"dailyPai":0}""").observations
        assertEquals(listOf(0.0, 0.0), points.map { it.value })
        assertTrue(MiZeppScoreMapper.map(points).rows.isEmpty())
    }
    @Test fun invalidNumbersAndBooleanScoresAreRejected() {
        val points = decode(ZeppStream.PAI, """{"time":1800000000,"dailyPai":true,"totalPai":"NaN","highZonePai":-1}""")
        assertTrue(points.observations.isEmpty())
        assertEquals(1, points.invalidItems)
    }
    @Test fun unknownSourceTimezoneDoesNotBorrowPhoneTimezone() {
        val points = decode(ZeppStream.PAI, pai().replace("\"timeZone\":32,", "")).observations
        assertEquals(5, points.size)
        assertTrue(MiZeppScoreMapper.map(points).rows.isEmpty())
    }
    @Test fun stressStringTimelinePreservesSamplesAndRejectsSentinels() {
        val raw = buildJsonObject {
            put("timeZone", 32)
            put("data", """[{"time":1800000000000,"value":40},{"time":1800000001000,"value":255},
                {"time":1800000002000,"value":0},{"time":1800000003000,"value":true}]""")
        }
        val points = ZeppDecoder.decode(ZeppStream.STRESS, listOf(raw), time + 10).observations
        assertEquals(1, points.size)
        assertEquals("user_summary", points.single().sourceScope)
        assertNull(points.single().deviceId)
        assertEquals("stress", MiZeppScoreMapper.map(points).rows.single().identity.key)
    }
    @Test fun manualStressIsSeparateFromAllDayStress() {
        val points = decode(ZeppStream.MANUAL_STRESS, """{"timestamp":1800000000,"value":40,"timeZone":32}""").observations
        assertEquals("single_stress", MiZeppScoreMapper.map(points).rows.single().identity.key)
    }
    @Test fun fractionalStressIsNotRoundedIntoAReading() {
        val points = decode(ZeppStream.MANUAL_STRESS, """{"timestamp":1800000000,"value":40.5,"timeZone":32}""").observations
        assertTrue(MiZeppScoreMapper.map(points).rows.isEmpty())
    }
    @Test fun omittedStressZoneUsesOnlyAnExplicitDestinationDisplayZone() {
        val point = decode(ZeppStream.MANUAL_STRESS, """{"timestamp":1800000000,"value":40}""").observations
        assertTrue(MiZeppScoreMapper.map(point).rows.isEmpty())
        val row = MiZeppScoreMapper.map(point, TimeZone.of("Asia/Singapore")).rows.single()
        assertEquals(28_800, row.zoneOffsetSeconds)
        assertEquals(time, row.identity.time)
        assertFalse(row.dayAggregationEligible)
        assertNull(point.single().zoneOffsetSeconds)
    }
    @Test fun importedAppTokenWithoutRefreshTokenIsStillAValidReadSession() {
        val session = ZeppSession("123", "synthetic-app-token", "", "api-mifitcn.zepp.com")
        assertTrue(session.loginToken.isEmpty())
    }
    @Test fun exertionRemainsVendorEstimateAndNegativeTsbIsValid() {
        val out = decode(ZeppStream.EXERTION, """{"timestamp":1800000000000,"value":{
            "atl":-1,"ctl":23,"tsb":-8,"atlTotal":45,"lastUpdateTime":1800000005000}}""").observations
        assertEquals(setOf("vendor_ctl", "vendor_tsb", "vendor_atlTotal"), out.map { it.metric }.toSet())
        assertEquals(-8.0, out.single { it.metric == "vendor_tsb" }.value)
        assertTrue(out.all { it.qualification == "vendor_estimate_unvalidated" && it.revisionSeconds == time + 5 })
        assertTrue(MiZeppScoreMapper.map(out).rows.isEmpty())
    }
    @Test fun trainingLoadRequiresExactVendorFieldsAndDoesNotReplaceWithAtl() {
        val out = ZeppTrainingLoadDecoder.decode(listOf(obj("""{"dayId":20833,"weeklyTLSum":250,
            "currentDayTL":50,"weeklyTLSumOptimalMin":100,"atl":999}""")), time)
        val row = MiZeppScoreMapper.map(out.observations).rows.single()
        assertEquals(20833 * 86_400L, row.identity.time)
        assertEquals(0, row.zoneOffsetSeconds)
        assertEquals(JsonPrimitive(250), row.value["wtl_sum"])
        assertEquals(JsonPrimitive(50), row.value["current_day_train_load"])
        assertNull(row.value["wtl_sum_optimal_max"])
    }
    @Test fun regionalSportLoadUsesIsoDayAndDistinctWireFieldNames() {
        val raw = obj("""{"dayId":"2027-01-15","wtlSum":240,"currnetDayTrainLoad":50,
            "wtlSumOptimalMin":100,"wtlSumOptimalMax":280,"wtlSumOverreaching":350,"updateTime":1800000000}""")
        val points = ZeppTrainingLoadDecoder.decode(listOf(raw), time).observations
        assertEquals(5, points.size)
        assertEquals("wtlSum", points.single { it.metric == "weekly_load" }.sourceField)
        val row = MiZeppScoreMapper.map(points).rows.single()
        assertEquals(JsonPrimitive(240), row.value["wtl_sum"])
        assertEquals(JsonPrimitive(50), row.value["current_day_train_load"])
    }
    @Test fun revisionUsesWholeLatestEventWithoutOldFieldFill() {
        val old = decode(ZeppStream.PAI, pai(",\"uploadTimestamp\":1800000001000")).observations
        val new = decode(ZeppStream.PAI, """{"timestamp":1800000000000,"timeZone":32,
            "dailyPai":13,"uploadTimestamp":1800000002000}""").observations
        assertTrue(MiZeppScoreMapper.map(old + new).rows.isEmpty())
    }
    @Test fun newerWithdrawnPaiEventCannotReviveTheOldScore() {
        val old = obj(pai(",\"uploadTimestamp\":1800000001000"))
        val withdrawn = obj("""{"timestamp":1800000000000,"timeZone":32,"uploadTimestamp":1800000002000}""")
        val out = ZeppDecoder.decode(ZeppStream.PAI, listOf(old, withdrawn), time + 10)
        assertTrue(out.observations.isEmpty())
        assertEquals(1, out.invalidItems)
    }
    @Test fun undatedStressHeadersDoNotDiscardEarlierTimelineChunks() {
        val a = obj("""{"timeZone":32,"data":[{"time":1800000000000,"value":35}]}""")
        val b = obj("""{"timeZone":32,"data":[{"time":1800000001000,"value":40}]}""")
        assertEquals(2, ZeppDecoder.decode(ZeppStream.STRESS, listOf(a, b), time + 10).observations.size)
    }
    @Test fun conflictingDevicesAtSameDestinationAreExcluded() {
        val a = decode(ZeppStream.PAI, pai(",\"deviceId\":\"synthetic-a\"")).observations
        val b = decode(ZeppStream.PAI, pai(",\"deviceId\":\"synthetic-b\"").replace("12.5", "14")).observations
        assertTrue(MiZeppScoreMapper.map(a + b).rows.isEmpty())
    }
    @Test fun duplicateEventsDoNotCreateDuplicateTargets() {
        val points = decode(ZeppStream.PAI, pai()).observations
        assertEquals(1, MiZeppScoreMapper.map(points + points).rows.size)
    }
    @Test fun workoutEffectUsesTenthsAndKeepsAbsentScoresUnknown() {
        val out = ZeppDecoder.workouts(listOf(obj("""{"trackid":"1800000000","te":32,
            "anaerobic_te":15,"exercise_load":83}""")), time).workouts.single()
        assertEquals(3.2, out.aerobicEffect)
        assertEquals(1.5, out.anaerobicEffect)
        assertEquals(83.0, out.load)
        assertNull(out.endSeconds)
    }
    @Test fun invalidWorkoutScoresAreNotClamped() {
        val raw = obj("""{"trackid":1800000000,"te":51,"anaerobic_te":true,"exercise_load":-1}""")
        val out = ZeppDecoder.workouts(listOf(raw), time).workouts.single()
        assertNull(out.aerobicEffect); assertNull(out.anaerobicEffect); assertNull(out.load)
    }
    @Test fun subsecondTimesAreNotSilentlyCollapsed() {
        assertNull(ZeppDecoder.epoch(JsonPrimitive(1_800_000_000_500L)))
        assertEquals(time, ZeppDecoder.epoch(JsonPrimitive("2027-01-15T08:00:00Z")))
    }
    @Test fun onlyZeppRegionalOriginsAreAllowed() {
        assertEquals("api-mifitcn.zepp.com", zeppHost("https://api-mifitcn.zepp.com/"))
        for (host in listOf("http://api-mifitcn.zepp.com", "api-mifitcn.zepp.com.evil.test",
            "api-mifitcn.zepp.com:443", "api-mifitcn.zepp.com/path", "x@api-mifitcn.zepp.com", "api-mifitcn.zepp.com?q=a")) {
            assertFailsWith<IllegalArgumentException> { zeppHost(host) }
        }
    }
    @Test fun sessionDebugTextNeverIncludesSecrets() {
        val session = ZeppSession("123", "synthetic-app-token", "synthetic-login-token", "api-mifitcn.zepp.com")
        assertEquals("ZeppSession(redacted)", session.toString())
    }
}
