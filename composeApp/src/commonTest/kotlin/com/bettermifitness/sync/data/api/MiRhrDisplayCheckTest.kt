package com.bettermifitness.sync.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import com.bettermifitness.sync.health.HealthInboundMetric

class MiRhrDisplayCheckTest {
    private val day = 20_000L * 86_400L

    private fun raw(index: Int, bpm: Int = 52) = MiHealthImportRow(
        MiHealthImportIdentity(MiHealthImportChannel.RAW, "default", "resting_heart_rate", day + index * 86_400L),
        buildJsonObject { put("date_time", day + index * 86_400L); put("bpm", bpm) },
        0, setOf("zepp:$index"),
    )

    private fun report(index: Int, bpm: Double? = 52.0) = MiHealthImportCloudRow(
        "default", "heart_rate", day + index * 86_400L, "daily_report",
        buildJsonObject { if (bpm != null) put("avg_rhr", bpm) }, zoneOffsetSeconds = 0,
    )

    @Test
    fun sourceRejectedResultCanStillCheckAllValidVerifiedRhrDays() {
        val plan = MiHealthImportPlan(HealthInboundMetric.RESTING_HEART_RATE, listOf(raw(0)),
            listOf(MiHealthImportIssue("RESTING_HEART_RATE_DAY_COLLISION", setOf("source-2"), "conflict")), 27)
        val diagnostic = MiHealthImportDiagnostic("resting_heart_rate", MiHealthImportStage.PREPARE,
            plannedRawRows = 1, verifiedRawRows = 1, affectedRows = 24)
        assertEquals(listOf(raw(0)), fullyVerifiedRhrRowsForDisplay(plan, diagnostic))
    }

    @Test
    fun incompleteRawVerificationNeverGuessesWhichRhrDaysWerePersisted() {
        val plan = MiHealthImportPlan(HealthInboundMetric.RESTING_HEART_RATE,
            listOf(raw(0), raw(1)), emptyList(), 2)
        val diagnostic = MiHealthImportDiagnostic("resting_heart_rate", MiHealthImportStage.READBACK,
            plannedRawRows = 2, verifiedRawRows = 1)
        assertEquals(emptyList(), fullyVerifiedRhrRowsForDisplay(plan, diagnostic))
        assertEquals(emptyList(), fullyVerifiedRhrRowsForDisplay(plan, null))
        assertEquals(emptyList(), fullyVerifiedRhrRowsForDisplay(plan.copy(metric = HealthInboundMetric.HEART_RATE), diagnostic))
    }

    @Test
    fun testModeChecksOnlyItsVerifiedLatestDayAndNeverSelectsAmongCollidingRevisions() {
        val plan = MiHealthImportPlan(HealthInboundMetric.RESTING_HEART_RATE,
            listOf(raw(0), raw(1)), emptyList(), 2)
        val diagnostic = MiHealthImportDiagnostic("resting_heart_rate", MiHealthImportStage.COMPLETE,
            testMode = true, plannedRawRows = 1, verifiedRawRows = 1)
        assertEquals(listOf(raw(1)), fullyVerifiedRhrRowsForDisplay(plan, diagnostic))
        assertEquals(emptyList(), fullyVerifiedRhrRowsForDisplay(
            plan.copy(rows = plan.rows + raw(1, 53)), diagnostic))
    }

    @Test
    fun onePagedRangeDistinguishesMatchingMissingAndConflictingDaysWithoutWriting() = runBlocking {
        val cloud = FakeCloud(listOf(report(0), report(2, null), report(3, 53.0), report(4), report(4, 54.0)))
        val result = checkMiRhrDisplayReports(cloud, (0..4).map { raw(it) })
        assertEquals(MiRhrDisplayCheckResult(1, 2, 2), result)
        assertEquals(5, result.checkedDays)
        assertFalse(result.verifiesAppDisplay)
        assertEquals(listOf(ReadCall(MiHealthImportChannel.DAILY_AGGREGATE, "heart_rate", day, day + 5 * 86_400L)), cloud.reads)
        assertEquals(0, cloud.writes)
    }

    @Test
    fun otherSourcesDeletedRowsAndOtherTagsCannotProveThisTargetsDailyReport() = runBlocking {
        val cloud = FakeCloud(listOf(
            report(0).copy(sid = "another-device"),
            report(0).copy(deleted = true),
            report(0).copy(tag = "weekly_report"),
            report(0).copy(key = "resting_heart_rate"),
        ))
        assertEquals(MiRhrDisplayCheckResult(0, 1, 0), checkMiRhrDisplayReports(cloud, listOf(raw(0))))
        assertEquals(0, cloud.writes)
    }

    @Test
    fun anonymousOrContradictoryReportsAreAmbiguousRatherThanMatched() = runBlocking {
        val cloud = FakeCloud(listOf(report(0).copy(sid = null), report(1), report(1, null)))
        assertEquals(MiRhrDisplayCheckResult(0, 0, 2), checkMiRhrDisplayReports(cloud, listOf(raw(0), raw(1))))
    }

    @Test
    fun equivalentNumericCopiesCountOnceAndDifferingExpectedDailyValuesCannotMatch() = runBlocking {
        val cloud = FakeCloud(listOf(report(0), report(0).copy(value = buildJsonObject { put("avg_rhr", 52) })))
        assertEquals(MiRhrDisplayCheckResult(1, 0, 0), checkMiRhrDisplayReports(cloud, listOf(raw(0), raw(0))))
        assertEquals(MiRhrDisplayCheckResult(0, 0, 1), checkMiRhrDisplayReports(cloud, listOf(raw(0), raw(0, 53))))
    }

    @Test
    fun legacyInstantShapedRhrCanBeDiagnosedAgainstTheSourceCalendarDateWithoutChangingIt() = runBlocking {
        val legacy = raw(0).copy(
            identity = raw(0).identity.copy(time = day - 7 * 3_600L), zoneOffsetSeconds = 28_800,
        )
        val cloud = FakeCloud(listOf(report(0)))
        assertEquals(MiRhrDisplayCheckResult(1, 0, 0), checkMiRhrDisplayReports(cloud, listOf(legacy)))
        assertEquals(day, cloud.reads.single().from)
        assertEquals(day - 7 * 3_600L, legacy.identity.time)
        assertEquals(0, cloud.writes)
    }

    @Test
    fun diagnosticErrorsAndCancellationPropagateWithoutAnyUploadAttempt() = runBlocking {
        val failure = IllegalStateException("Read unavailable")
        val cloud = FakeCloud(emptyList()).apply { readFailure = failure }
        assertSame(failure, assertFailsWith<IllegalStateException> {
            checkMiRhrDisplayReports(cloud, listOf(raw(0)))
        })
        val cancelled = CancellationException("Cancelled")
        cloud.readFailure = cancelled
        assertSame(cancelled, assertFailsWith<CancellationException> {
            checkMiRhrDisplayReports(cloud, listOf(raw(0)))
        })
        assertEquals(0, cloud.writes)
    }

    @Test
    fun noVerifiedRhrNeedsNoNetworkCall() = runBlocking {
        val cloud = FakeCloud(emptyList())
        assertEquals(MiRhrDisplayCheckResult(0, 0, 0), checkMiRhrDisplayReports(cloud, emptyList()))
        assertEquals(0, cloud.reads.size)
        assertEquals(0, cloud.writes)
    }

    private data class ReadCall(val channel: MiHealthImportChannel, val key: String, val from: Long, val until: Long)

    private class FakeCloud(private val rows: List<MiHealthImportCloudRow>) : MiHealthImportCloud {
        val reads = mutableListOf<ReadCall>()
        var writes = 0
        var readFailure: Exception? = null

        override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long, untilEpochSeconds: Long): List<MiHealthImportCloudRow> {
            reads += ReadCall(channel, key, fromEpochSeconds, untilEpochSeconds)
            readFailure?.let { throw it }
            return rows
        }

        override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
            writes++
            error("The RHR display check must never upload data")
        }
    }
}
