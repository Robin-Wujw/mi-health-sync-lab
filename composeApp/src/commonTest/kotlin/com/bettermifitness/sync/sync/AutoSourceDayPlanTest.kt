package com.bettermifitness.sync.sync

import com.bettermifitness.sync.LocalizedTest
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.repository.SyncProgress
import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.data.repository.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class AutoSourceDayPlanTest : LocalizedTest() {
    private val metric = SyncPreferences.SPO2_IMPORT_KEY

    @Test fun unfinishedAndPreBaselineDaysAreIgnoredWhileALaterFullyCoveredDayCanRun() {
        val zone = TimeZone.UTC
        val day = Instant.parse("2026-11-05T00:00:00Z").epochSeconds
        val covered = AutoSyncWindow(day + 2 * AUTO_WINDOW_SECONDS, day + 3 * AUTO_WINDOW_SECONDS)
        val baseline = day + 60
        val raw = mapOf(metric to AutoSyncCheckpoint(day + AUTO_WINDOW_SECONDS, listOf(covered), coverageStart = baseline))
        val daily = mapOf(metric to AutoSyncCheckpoint(day + AUTO_WINDOW_SECONDS))
        assertTrue(planSourceDaySlices(zone, covered.until - 1, raw, daily).isEmpty())
        assertEquals(listOf(AutoSyncSlice(covered, setOf(metric), historical = true, sourceDay = true)),
            planSourceDaySlices(zone, covered.until, raw, daily))
        val done = daily.mapValues { it.value.confirm(covered) }
        assertTrue(planSourceDaySlices(zone, covered.until, raw, done).isEmpty())
    }

    @Test fun rawVerificationAndPreservationAloneDoNotVerifyADailyReport() {
        val diagnostic = MiHealthImportDiagnostic("spo2", MiHealthImportStage.COMPLETE, plannedDailyRows = 1)
        fun result(state: SyncState) = SyncRunResult.from(SyncProgress(spo2Import = state), setOf(metric), emptyList(), false)
        val preserved = result(SyncState.Success(1, diagnostic = diagnostic.copy(preservedDailyRows = 1)))
        assertEquals(setOf(metric), preserved.confirmedMetricKeys)
        assertTrue(preserved.confirmedSourceDayKeys.isEmpty())
        assertEquals(setOf(metric), result(SyncState.Success(1, diagnostic = diagnostic.copy(verifiedDailyRows = 1)))
            .confirmedSourceDayKeys)
        assertTrue(result(SyncState.NoData("no usable records", diagnostic = diagnostic)).confirmedSourceDayKeys.isEmpty())
        assertEquals(setOf(metric), result(SyncState.NoData("successfully read an empty source day")).confirmedSourceDayKeys)
    }
}
