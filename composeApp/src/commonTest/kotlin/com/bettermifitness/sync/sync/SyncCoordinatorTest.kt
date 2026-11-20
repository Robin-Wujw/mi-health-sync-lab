package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.preferences.CredentialsPort
import com.bettermifitness.sync.data.preferences.AutoSyncTiming
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.SyncPreferencesPort
import com.bettermifitness.sync.data.preferences.SyncSessionPort
import com.bettermifitness.sync.data.repository.AutomaticZeppRead
import com.bettermifitness.sync.data.repository.HealthSyncRunner
import com.bettermifitness.sync.data.repository.SyncProgress
import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.data.repository.SyncPageScope
import com.bettermifitness.sync.health.HealthAvailability
import com.bettermifitness.sync.health.HealthPermissionRequester
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.zepp.ZeppStream
import com.bettermifitness.sync.ui.sync.SyncScreenFeedback
import com.bettermifitness.sync.ui.sync.ownsSyncCompletion
import com.mifitness.miclient.auth.MiCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Policy tests for [SyncCoordinator] with pure in-memory fakes (no DataStore / network).
 */
class SyncCoordinatorTest : com.bettermifitness.sync.LocalizedTest() {
    @Test
    fun previouslyRejectedSessionDoesNotRefreshRequestPermissionsOrStartSync() = runBlocking {
        val session = FakeSession(true).apply { requiresReauthentication = true }
        val health = FakeHealth()
        val runner = FakeSyncRunner()
        val coordinator = coordinator(FakePrefs(), "t", session = session, health = health, runner = runner)
        assertEquals(SyncOutcome.NotLoggedIn, coordinator.run())
        assertEquals(SyncOutcome.Skipped, coordinator.run(requireAutoSync = true, userInitiated = false))
        assertEquals(0, session.staleRefreshes)
        assertEquals(0, health.writePermissionRequests)
        assertTrue(health.requestedReadMetrics.isEmpty())
        assertEquals(false, runner.called)
    }

    @Test
    fun hardRejectionDuringProactiveRenewalStopsBeforeAnyMetricStarts() = runBlocking {
        val session = FakeSession(true).apply { rejectOnRefresh = true }
        val health = FakeHealth()
        val runner = FakeSyncRunner()
        val outcome = coordinator(FakePrefs(), "t", session = session, health = health, runner = runner).run()
        assertEquals(SyncOutcome.NotLoggedIn, outcome)
        assertEquals(1, session.staleRefreshes)
        assertEquals(0, health.writePermissionRequests)
        assertTrue(health.requestedReadMetrics.isEmpty())
        assertEquals(false, runner.called)
    }

    @Test
    fun zeppCloudOnlyRunDoesNotDependOnHealthConnectAvailabilityOrPermissions() = runBlocking {
        val prefs = FakePrefs(enabled = setOf(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY))
        val health = FakeHealth(available = false, permissionError = "must not request")
        val runner = FakeSyncRunner()
        coordinator(prefs, "t", runner = runner, health = health).run()
        assertTrue(runner.called)
        assertEquals(0, health.writePermissionRequests)
        assertTrue(health.requestedReadMetrics.isEmpty())
    }

    @Test
    fun zeppCloudTaskCanRunThroughTheScheduledWorkerPolicy() = runBlocking {
        val prefs = FakePrefs(enabled = setOf(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY))
        val runner = FakeSyncRunner(SyncRunResult(1, 1, 0, 5, false, false, emptyList()))
        val result = coordinator(prefs, "t", runner = runner, health = FakeHealth(available = false)).run(
            requireAutoSync = true, recordAsBackground = true, userInitiated = false, requestHealthPermissions = false)
        assertTrue(runner.called)
        assertEquals(SyncOutcome.Success, result)
    }

    @Test
    fun completedSkipRunReplacesOldPartialResultAndSurvivesForegroundReturn() = runBlocking {
        val prefs = FakePrefs()
        prefs.lastStatus = SyncOutcome.STATUS_PARTIAL_SUCCESS
        prefs.lastMessage = "Old partial result"
        val result = SyncRunResult(2, 1, 0, 760, false, false, emptyList(),
            noData = 1, noDataMessages = listOf("No source HRV"), excludedSourcePoints = 1,
            warningMessages = listOf("Verified 760. Skipped 1."))
        val runner = FakeSyncRunner(result)
        val outcome = coordinator(prefs, "t", runner = runner).run(requestHealthPermissions = false)
        assertIs<SyncOutcome.CompletedWithSkips>(outcome)
        assertEquals(SyncOutcome.STATUS_COMPLETED_WITH_SKIPS, prefs.lastStatus)
        assertEquals(outcome.summary, prefs.lastMessage)
        assertEquals("The selected sync directions completed", prefs.lastMessage)

        // A new coordinator represents losing in-memory state on app recreation.
        val afterReturn = FakeSyncRunner(result)
        val resumed = coordinator(prefs, "t", runner = afterReturn).run(
            requireAutoSync = true, userInitiated = false, requestHealthPermissions = false)
        assertEquals(SyncOutcome.Skipped, resumed)
        assertEquals(false, afterReturn.called)
        assertEquals(SyncOutcome.STATUS_COMPLETED_WITH_SKIPS, prefs.lastStatus)
        assertEquals(outcome.summary, prefs.lastMessage)
    }

    @Test
    fun foregroundReturnsWaitFifteenMinutesWithoutExtendingCooldown() = runBlocking {
        var time = Instant.parse("2026-11-05T04:00:00Z")
        val prefs = FakePrefs()
        coordinator(prefs, "t", now = { time }).run(requestHealthPermissions = false)
        val finished = prefs.lastSyncActivityTime.value
        for (minute in listOf(0, 1, 14)) {
            time = Instant.parse("2026-11-05T04:00:00Z") + minute.minutes
            val runner = FakeSyncRunner()
            val health = FakeHealth()
            assertEquals(SyncOutcome.Skipped, coordinator(prefs, "t", runner = runner,
                health = health, now = { time }).run(requireAutoSync = true, userInitiated = false))
            assertEquals(false, runner.called)
            assertEquals(0, health.writePermissionRequests)
            assertEquals(finished, prefs.lastSyncActivityTime.value)
        }
        time += 1.minutes
        val runner = FakeSyncRunner()
        coordinator(prefs, "t", runner = runner, now = { time }).run(
            requireAutoSync = true, userInitiated = false, requestHealthPermissions = false)
        assertTrue(runner.called)
    }

    @Test
    fun manualRetryAndScheduledWorkerIgnoreLegacyActivityMarker() = runBlocking {
        for (background in listOf(false, true)) {
            val prefs = FakePrefs()
            prefs.lastSyncActivityTime.value = Instant.parse("2026-11-05T04:00:00Z").toString()
            val runner = FakeSyncRunner()
            coordinator(prefs, "t", runner = runner, now = { Instant.parse("2026-11-05T04:01:00Z") }).run(
                requireAutoSync = background, recordAsBackground = background,
                userInitiated = !background, requestHealthPermissions = false)
            assertTrue(runner.called)
        }
    }

    @Test
    fun failedManualRunAlsoPreventsImmediateAutomaticRestart() = runBlocking {
        val prefs = FakePrefs()
        val runner = FakeSyncRunner(SyncRunResult(1, 0, 1, 0, true, false, listOf("Network")))
        assertIs<SyncOutcome.Failed>(coordinator(prefs, "t", runner = runner).run(requestHealthPermissions = false))
        val autoRunner = FakeSyncRunner()
        assertEquals(SyncOutcome.Skipped, coordinator(prefs, "t", runner = autoRunner).run(
            requireAutoSync = true, userInitiated = false, requestHealthPermissions = false))
        assertEquals(false, autoRunner.called)
        assertEquals(SyncOutcome.STATUS_FAILED, prefs.lastStatus)
    }

    @Test
    fun cancellationAfterLongUserActionPersistsEndTimeBeforeUnlocking() = runBlocking {
        val prefs = FakePrefs()
        var time = Instant.parse("2026-11-05T04:00:00Z")
        val coord = coordinator(prefs, "t", now = { time })
        val entered = CompletableDeferred<Unit>()
        val cancelled = async {
            coord.runExclusiveUserAction {
                assertEquals(time.toString(), prefs.lastSyncActivityTime.value)
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        entered.await()
        time += 30.minutes
        cancelled.cancel()
        cancelled.join()
        assertEquals(false, coord.isRunning.value)
        assertEquals(time.toString(), prefs.lastSyncActivityTime.value)
        val runner = FakeSyncRunner()
        assertEquals(SyncOutcome.Skipped, coordinator(prefs, "t", runner = runner, now = { time }).run(
            requireAutoSync = true, userInitiated = false, requestHealthPermissions = false))
        assertEquals(false, runner.called)
    }

    @Test
    fun invalidOrFutureActivityTimestampDoesNotDisableAutomaticSyncForever() = runBlocking {
        for (timestamp in listOf("invalid", "2027-01-01T00:00:00Z")) {
            val prefs = FakePrefs()
            prefs.lastSyncActivityTime.value = timestamp
            val runner = FakeSyncRunner()
            coordinator(prefs, "t", runner = runner, now = { Instant.parse("2026-11-05T04:00:00Z") }).run(
                requireAutoSync = true, userInitiated = false, requestHealthPermissions = false)
            assertTrue(runner.called)
            assertEquals("2026-11-05T04:00:00Z", prefs.lastSyncActivityTime.value)
        }
    }

    @Test
    fun explicitNoOpReplacesStalePartialButAutomaticNoOpPreservesIt() = runBlocking {
        for (manual in listOf(true, false)) {
            val prefs = FakePrefs(enabled = emptySet())
            prefs.lastStatus = SyncOutcome.STATUS_PARTIAL_SUCCESS
            val outcome = coordinator(prefs, "t").run(userInitiated = manual, requestHealthPermissions = false)
            assertEquals(SyncOutcome.Skipped, outcome)
            assertEquals(if (manual) SyncOutcome.STATUS_SKIPPED else SyncOutcome.STATUS_PARTIAL_SUCCESS, prefs.lastStatus)
        }
    }

    @Test fun historyPermissionFollowsEffectiveRangeAndIsNeverRequestedInBackground() = runBlocking {
        for ((saved, override, requested) in listOf(Triple(30, 0, true), Triple(30, 7, false), Triple(7, 30, true), Triple(14, 0, false))) {
            val health = FakeHealth()
            val prefs = FakePrefs(enabled = setOf(SyncPreferences.HEART_RATE_IMPORT_KEY), rangeDays = saved)
            coordinator(prefs = prefs, token = "t", health = health).run(requestHealthPermissions = true,
                rangeDaysOverride = override, userInitiated = true)
            assertEquals(requested, health.requestedHistory)
            assertEquals(0, health.writePermissionRequests)
        }
        val health = FakeHealth()
        coordinator(prefs = FakePrefs(enabled = setOf(SyncPreferences.HEART_RATE_IMPORT_KEY), rangeDays = 30),
            token = "t", health = health).run(requestHealthPermissions = false)
        assertTrue(health.requestedReadMetrics.isEmpty())
        assertEquals(false, health.requestedHistory)
    }

    @Test
    fun changingSavedRangeAndMetricsDoesNotChangeAnActiveSelection() = runBlocking {
        val metrics = setOf(SyncPreferences.HEART_RATE_IMPORT_KEY)
        val prefs = FakePrefs(enabled = metrics, rangeDays = 30)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = BlockingSyncRunner(started, release)
        val coordinator = coordinator(prefs = prefs, token = "t", runner = runner)
        val run = async { coordinator.run(requestHealthPermissions = false) }
        try {
            started.await()
            prefs.syncRangeDays.value = 1
            prefs.enabledMetrics.value = emptySet()
            val active = requireNotNull(coordinator.activeSelection.value)
            assertEquals(30, active.rangeDays)
            assertEquals(30.days, active.to - active.from)
            assertEquals(metrics, active.enabled)
            assertEquals(SyncOutcome.AlreadyRunning, coordinator.run())
        } finally {
            release.complete(Unit)
        }
        run.await()
        assertEquals(null, coordinator.activeSelection.value)
    }

    @Test
    fun permissionPreparationAndExecutionUseTheSubmittedRequestDespiteSettingsChanges() = runBlocking<Unit> {
        val metrics = setOf(SyncPreferences.HEART_RATE_IMPORT_KEY)
        val prefs = FakePrefs(enabled = metrics, rangeDays = 30)
        val health = FakeHealth()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator(prefs, "t", health = health, runner = BlockingSyncRunner(started, release))
        val request = coordinator.captureUserSyncRequest()
        prefs.syncRangeDays.value = 1
        prefs.enabledMetrics.value = emptySet()
        assertEquals(null, coordinator.prepareUserSync(request))
        assertTrue(health.requestedHistory)
        val run = async { coordinator.run(requestHealthPermissions = false, request = request) }
        try {
            started.await()
            assertEquals(request.selection, coordinator.activeSelection.value)
        } finally {
            release.complete(Unit)
        }
        run.await()
    }

    @Test
    fun requestFromPreviousAccountGenerationIsNeverRun() = runBlocking {
        val runner = FakeSyncRunner()
        val coordinator = coordinator(FakePrefs(), "t", runner = runner)
        val staleRequest = coordinator.captureUserSyncRequest().copy(accountGeneration = 99)
        assertEquals(SyncOutcome.Skipped, coordinator.prepareUserSync(staleRequest))
        assertEquals(SyncOutcome.Skipped, coordinator.run(request = staleRequest, requestHealthPermissions = false))
        assertEquals(false, runner.called)
        assertEquals(null, coordinator.lastOutcome.value)
    }

    @Test
    fun cancellingActiveRunWaitsForCleanupBeforeTheWriterCanBeAcquiredAgain() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val neverFinish = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val runner = BlockingSyncRunner(started, neverFinish) {
            withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                cleanupRelease.await()
            }
        }
        val coordinator = coordinator(FakePrefs(), "t", runner = runner)
        val running = async { coordinator.run(requestHealthPermissions = false) }
        started.await()
        val stopping = async { coordinator.cancelActiveRunAndAwait() }
        try {
            cleanupStarted.await()
            assertEquals(false, stopping.isCompleted)
            assertEquals(SyncOutcome.AlreadyRunning, coordinator.run())
        } finally {
            cleanupRelease.complete(Unit)
        }
        stopping.await()
        assertFailsWith<CancellationException> { running.await() }
        assertEquals(false, coordinator.isRunning.value)
        assertEquals(ExclusiveSyncActionResult.Completed(Unit), coordinator.runExclusiveUserAction {})
    }

    @Test
    fun replacingReservesTheWriterDuringCleanupAndTheGapBeforeStart() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val betweenRuns = CompletableDeferred<Unit>()
        val startReplacement = CompletableDeferred<Unit>()
        val prefs = FakePrefs(rangeDays = 30)
        val runner = BlockingSyncRunner(started, finishWrite) {
            withContext(NonCancellable) { cleanupStarted.complete(Unit); finishCleanup.await() }
        }
        val coordinator = coordinator(prefs, "t", runner = runner)
        val first = async { coordinator.run(requestHealthPermissions = false) }
        started.await()
        prefs.syncRangeDays.value = 1
        val next = coordinator.captureUserSyncRequest()
        val replacing = async {
            coordinator.replaceUserSync(next) {
                betweenRuns.complete(Unit)
                startReplacement.await()
                coordinator.run(requestHealthPermissions = false, request = next)
            }
        }
        try {
            cleanupStarted.await()
            assertEquals(30, coordinator.activeSelection.value?.rangeDays)
            assertEquals(next, coordinator.replacementRequest.value)
            assertEquals(false, betweenRuns.isCompleted)
            finishCleanup.complete(Unit)
            betweenRuns.await()
            prefs.syncRangeDays.value = 7
            assertEquals(1, coordinator.replacementRequest.value?.selection?.rangeDays)
            assertEquals(SyncOutcome.AlreadyRunning, coordinator.run(requireAutoSync = true))
            assertEquals(ExclusiveSyncActionResult.Busy, coordinator.runExclusiveUserAction {})
        } finally {
            finishCleanup.complete(Unit)
            finishWrite.complete(Unit)
            startReplacement.complete(Unit)
        }
        assertFailsWith<UserSyncStopped> { first.await() }
        assertEquals(SyncOutcome.Success, replacing.await())
        assertEquals(null, coordinator.replacementRequest.value)
        assertEquals(null, prefs.autoTiming.pausedUntil)
    }

    @Test
    fun explicitStopCancelsQueuedReplacementAndWaitsUntilWriterCleanupEnds() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val prefs = FakePrefs()
        val clock = Instant.parse("2026-11-19T12:00:00Z")
        val runner = BlockingSyncRunner(entered, CompletableDeferred()) {
            withContext(NonCancellable) {
                cleanup.complete(Unit)
                cleaned.await()
            }
        }
        val coordinator = coordinator(prefs, "t", runner = runner, now = { clock })
        val running = async { coordinator.run(requestHealthPermissions = false) }
        entered.await()
        val next = coordinator.captureUserSyncRequest()
        var startedReplacement = false
        val queued = async {
            coordinator.replaceUserSync(next) {
                startedReplacement = true
                SyncOutcome.Success
            }
        }
        cleanup.await()
        val stopRequested = CompletableDeferred<Unit>()
        val stopping = async { coordinator.stopByUser { stopRequested.complete(Unit) } }
        try {
            stopRequested.await()
            assertEquals(true, coordinator.isStopping.value)
            assertEquals(false, stopping.isCompleted)
            assertEquals(null, coordinator.replacementRequest.value)
            assertEquals(clock.epochSeconds + 3_600, prefs.autoTiming.pausedUntil)
            assertEquals(SyncOutcome.AlreadyRunning, coordinator.run())
            assertEquals(SyncOutcome.AlreadyRunning, coordinator.replaceUserSync(next) { SyncOutcome.Success })
        } finally {
            cleaned.complete(Unit)
        }
        assertTrue(stopping.await())
        assertFailsWith<UserSyncStopped> { running.await() }
        assertFailsWith<UserSyncStopped> { queued.await() }
        assertEquals(false, startedReplacement)
        assertEquals(SyncOutcome.Cancelled, coordinator.lastOutcome.value)
        assertEquals(false, coordinator.isStopping.value)
    }

    @Test
    fun onlyNewestExplicitReplacementStartsAfterSupersededHandoffsFinish() = runBlocking {
        val prefs = FakePrefs(rangeDays = 30)
        val coordinator = coordinator(prefs, "t")
        val older = coordinator.captureUserSyncRequest()
        prefs.syncRangeDays.value = 7
        val intermediate = coordinator.captureUserSyncRequest()
        prefs.syncRangeDays.value = 1
        val newest = coordinator.captureUserSyncRequest()
        val markingOld = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()
        val first = async {
            coordinator.replaceUserSync(older, markPreviousStopping = {
                withContext(NonCancellable) {
                    markingOld.complete(Unit)
                    releaseOld.await()
                }
            }) { starts += older.runId; SyncOutcome.Success }
        }
        markingOld.await()
        assertEquals(SyncOutcome.AlreadyRunning, coordinator.replaceUserSync(older) { error("Duplicate ran") })
        val second = async {
            coordinator.replaceUserSync(intermediate) { starts += intermediate.runId; SyncOutcome.Success }
        }
        coordinator.replacementRequest.first { it == intermediate }
        val last = async {
            coordinator.replaceUserSync(newest) { starts += newest.runId; SyncOutcome.Success }
        }
        try {
            coordinator.replacementRequest.first { it == newest }
            assertEquals(emptyList(), starts)
            assertEquals(SyncOutcome.AlreadyRunning, coordinator.run(requireAutoSync = true))
        } finally {
            releaseOld.complete(Unit)
        }
        assertFailsWith<UserSyncStopped> { first.await() }
        assertFailsWith<UserSyncStopped> { second.await() }
        assertEquals(SyncOutcome.Success, last.await())
        assertEquals(listOf(newest.runId), starts)
        assertEquals(null, prefs.autoTiming.pausedUntil)
    }

    @Test
    fun explicitStopPausesAutomaticEntryPointsAcrossRecreationButAllowsManualSync() = runBlocking {
        var clock = Instant.parse("2026-11-19T12:00:00Z")
        val prefs = FakePrefs()
        val runner = FakeSyncRunner(SyncRunResult(1, 1, 0, 1, false, false, emptyList()))
        fun controller() = coordinator(prefs, "t", runner = runner, now = { clock })
        suspend fun automatic(scheduled: Boolean) = controller().run(requireAutoSync = true,
            requestHealthPermissions = false, recordAsBackground = scheduled, userInitiated = false)

        assertTrue(controller().stopByUser())
        assertEquals(SyncOutcome.Skipped, automatic(true))
        assertEquals(SyncOutcome.Skipped, automatic(false))
        assertEquals(false, runner.called)
        assertEquals(SyncOutcome.Success, controller().run(requestHealthPermissions = false))
        runner.called = false
        clock += 59.minutes
        assertEquals(SyncOutcome.Skipped, automatic(true))
        assertEquals(false, runner.called)
        clock += 1.minutes
        assertEquals(SyncOutcome.Success, automatic(true))
        assertTrue(runner.called)
    }

    @Test
    fun scheduledTriggersShareTheOneHourStartLimitAndManualCooldown() = runBlocking<Unit> {
        var clock = Instant.parse("2026-11-19T12:00:00Z")
        val prefs = FakePrefs()
        val runner = FakeSyncRunner()
        fun controller() = coordinator(prefs, "t", runner = runner, now = { clock })
        suspend fun scheduled() = controller().run(requireAutoSync = true, requestHealthPermissions = false,
            recordAsBackground = true, userInitiated = false)
        controller().run(requestHealthPermissions = false)
        runner.called = false
        clock += 14.minutes
        assertEquals(SyncOutcome.Skipped, scheduled())
        assertEquals(false, runner.called)
        clock += 1.minutes
        scheduled()
        assertEquals(true, runner.called)
        runner.called = false
        clock += 59.minutes
        assertEquals(SyncOutcome.Skipped, scheduled())
        assertEquals(false, runner.called)
        clock += 1.minutes
        scheduled()
        assertEquals(true, runner.called)
    }

    @Test
    fun explicitRecoveryExcludesNormalSyncAndSessionRefreshWithoutChangingPreferences() = runBlocking {
        val prefs = FakePrefs(autoSync = true)
        prefs.lastStatus = SyncOutcome.STATUS_SUCCESS
        val session = FakeSession(true)
        val coord = coordinator(prefs = prefs, token = "t", session = session)
        var maintenanceCalls = 0
        val result = coord.runExclusiveUserAction {
            assertTrue(coord.isRunning.value)
            assertEquals(SyncOutcome.AlreadyRunning, coord.run())
            assertEquals(ExclusiveSyncActionResult.Busy, coord.runExclusiveUserAction { error("must not enter") })
            assertEquals(false, coord.runSessionMaintenance { maintenanceCalls++ })
            42
        }
        assertEquals(42, assertIs<ExclusiveSyncActionResult.Completed<Int>>(result).value)
        assertEquals(0, maintenanceCalls)
        assertEquals(0, session.ensureCalls)
        assertEquals(0, session.staleRefreshes)
        assertEquals(false, coord.isRunning.value)
        assertEquals(null, coord.lastOutcome.value)
        assertEquals(SyncOutcome.STATUS_SUCCESS, prefs.lastStatus)
    }

    @Test
    fun inFlightProfileRefreshCannotBeOvertakenByRecovery(): Unit = runBlocking {
        val coord = coordinator(prefs = FakePrefs(), token = "t", session = FakeSession(true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val maintenance = async {
            coord.runSessionMaintenance {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            assertEquals(ExclusiveSyncActionResult.Busy, coord.runExclusiveUserAction { error("must not enter") })
            assertEquals(SyncOutcome.AlreadyRunning, coord.run())
        } finally {
            release.complete(Unit)
        }
        assertTrue(maintenance.await())
        assertIs<ExclusiveSyncActionResult.Completed<Unit>>(coord.runExclusiveUserAction { Unit })
    }

    @Test
    fun recoveryCancellationReleasesLockButLiveGateRejectsBothRecoveryAndRefresh() = runBlocking {
        val coord = coordinator(prefs = FakePrefs(), token = "t", session = FakeSession(true))
        assertFailsWith<CancellationException> {
            coord.runExclusiveUserAction { throw CancellationException("synthetic") }
        }
        assertEquals(false, coord.isRunning.value)
        assertIs<ExclusiveSyncActionResult.Completed<Unit>>(coord.runExclusiveUserAction { Unit })
        LiveTestSessionGate.activateForInstrumentation()
        try {
            assertEquals(ExclusiveSyncActionResult.Unavailable, coord.runExclusiveUserAction { error("must not enter") })
            assertEquals(false, coord.runSessionMaintenance { error("must not enter") })
        } finally {
            LiveTestSessionGate.resetForUnitTests()
        }
    }

    @Test
    fun liveInspectionGate_blocksForegroundAndBackgroundWithoutSideEffects() = runBlocking {
        val prefs = FakePrefs(autoSync = true)
        prefs.lastStatus = SyncOutcome.STATUS_SUCCESS
        val session = FakeSession(true)
        val health = FakeHealth()
        val runner = FakeSyncRunner()
        val coord = coordinator(prefs = prefs, token = "t", session = session,
            health = health, runner = runner)
        LiveTestSessionGate.activateForInstrumentation()
        try {
            for (background in listOf(false, true)) {
                val outcome = coord.run(
                    requireAutoSync = background,
                    requestHealthPermissions = true,
                    recordAsBackground = background,
                    resetProgress = true,
                    userInitiated = !background,
                )
                assertEquals(SyncOutcome.Skipped, outcome)
            }
            assertEquals(0, session.ensureCalls)
            assertEquals(0, session.staleRefreshes)
            assertEquals(0, health.writePermissionRequests)
            assertTrue(health.requestedReadMetrics.isEmpty())
            assertEquals(false, runner.called)
            assertEquals(false, runner.resetCalled)
            assertEquals(SyncOutcome.STATUS_SUCCESS, prefs.lastStatus)
            assertEquals(null, prefs.lastBgStatus)
            assertEquals(null, coord.lastOutcome.value)
            assertEquals(false, coord.isRunning.value)
        } finally {
            LiveTestSessionGate.resetForUnitTests()
        }
    }

    @Test
    fun hrvImportRequiresItsRealSleepContextButNoUnrelatedWrites() = runBlocking {
        val health = FakeHealth(permissionError = "writing must not be requested")
        val runner = FakeSyncRunner()
        coordinator(prefs = FakePrefs(enabled = setOf(SyncPreferences.HRV_IMPORT_KEY)), token = "t",
            health = health, runner = runner).run()
        assertTrue(runner.called)
        assertEquals(0, health.writePermissionRequests)
        assertEquals(setOf(HealthInboundMetric.HRV, HealthInboundMetric.SLEEP), health.requestedReadMetrics)
    }

    @Test
    fun oxygenImportRequestsOnlyOxygenReadAndCanRunOnItsOwn() = runBlocking {
        val health = FakeHealth(permissionError = "writing must not be requested")
        val runner = FakeSyncRunner()
        coordinator(prefs = FakePrefs(enabled = setOf(SyncPreferences.SPO2_IMPORT_KEY)), token = "t",
            health = health, runner = runner).run()
        assertTrue(runner.called)
        assertEquals(0, health.writePermissionRequests)
        assertEquals(setOf(HealthInboundMetric.OXYGEN_SATURATION), health.requestedReadMetrics)
    }

    @Test
    fun workoutAndVo2MaxImportsRequestTheirOwnReadPermissionsWithoutWriteAccess() = runBlocking {
        for (selected in listOf(setOf(SyncPreferences.WORKOUTS_IMPORT_KEY), setOf(SyncPreferences.VO2_MAX_IMPORT_KEY),
            setOf(SyncPreferences.WORKOUTS_IMPORT_KEY, SyncPreferences.VO2_MAX_IMPORT_KEY))) {
            val health = FakeHealth(permissionError = "writing must not be requested")
            val runner = FakeSyncRunner()
            coordinator(prefs = FakePrefs(enabled = selected), token = "t", health = health, runner = runner).run()
            val expected = buildSet {
                if (SyncPreferences.WORKOUTS_IMPORT_KEY in selected) add(HealthInboundMetric.WORKOUTS)
                if (SyncPreferences.VO2_MAX_IMPORT_KEY in selected) add(HealthInboundMetric.VO2_MAX)
            }
            assertTrue(runner.called)
            assertEquals(0, health.writePermissionRequests)
            assertEquals(expected, health.requestedReadMetrics)
        }
    }

    @Test
    fun sleepImportRequestsItsSleepVitalsDependenciesWithoutUnrelatedWrites() = runBlocking {
        val prefs = FakePrefs(enabled = setOf(SyncPreferences.SLEEP_IMPORT_KEY))
        val health = FakeHealth(permissionError = "writing is deliberately unavailable")
        val runner = FakeSyncRunner(result = SyncRunResult(1, 1, 0, 1, false, false, emptyList()))
        val outcome = coordinator(prefs = prefs, token = "t", health = health, runner = runner).run()
        assertEquals(SyncOutcome.Success, outcome)
        assertEquals(0, health.writePermissionRequests)
        assertEquals(setOf(HealthInboundMetric.SLEEP, HealthInboundMetric.HEART_RATE,
            HealthInboundMetric.OXYGEN_SATURATION), health.requestedReadMetrics)
        assertTrue(runner.called)
    }

    @Test
    fun sleepPreparationRequestsVitalsForManualServiceRunsWithAutoSyncOff() = runBlocking {
        val health = FakeHealth(permissionError = "writing must not be requested")
        val runner = FakeSyncRunner()
        val coord = coordinator(FakePrefs(autoSync = false, enabled = setOf(SyncPreferences.SLEEP_IMPORT_KEY)),
            "t", health = health, runner = runner)
        assertEquals(null, coord.prepareUserSync())
        assertEquals(setOf(HealthInboundMetric.SLEEP, HealthInboundMetric.HEART_RATE,
            HealthInboundMetric.OXYGEN_SATURATION), health.requestedReadMetrics)
        assertTrue(health.requestedBackground)
        assertEquals(0, health.writePermissionRequests)
        assertEquals(false, runner.called)
    }

    @Test
    fun existingOutboundSelection_doesNotRequestNewInboundReads() = runBlocking {
        val health = FakeHealth()
        coordinator(prefs = FakePrefs(enabled = setOf("heart_rate")), token = "t", health = health).run()
        assertEquals(1, health.writePermissionRequests)
        assertTrue(health.requestedReadMetrics.isEmpty())
    }

    @Test
    fun inboundBackgroundRun_neverLaunchesPermissionUi() = runBlocking {
        val health = FakeHealth(permissionError = "must not launch in background")
        val runner = FakeSyncRunner()
        coordinator(prefs = FakePrefs(enabled = setOf(SyncPreferences.HEART_RATE_IMPORT_KEY)), token = "t",
            health = health, runner = runner).run(requestHealthPermissions = false, recordAsBackground = true)
        assertTrue(runner.called)
        assertEquals(0, health.writePermissionRequests)
        assertTrue(health.requestedReadMetrics.isEmpty())
    }

    @Test
    fun requireAutoSync_whenDisabled_skipsDataSyncButStillRollsSession() = runBlocking {
        val prefs = FakePrefs(autoSync = false)
        prefs.lastStatus = SyncOutcome.STATUS_SUCCESS
        val session = FakeSession(true)
        val outcome = coordinator(prefs = prefs, token = "t", session = session).run(
            requireAutoSync = true,
            requestHealthPermissions = false,
            userInitiated = false,
        )
        assertEquals(SyncOutcome.Skipped, outcome)
        // The iOS foreground auto-sync path hits this when the toggle is off:
        // a no-op run must leave the manual Success result alone.
        assertEquals(SyncOutcome.STATUS_SUCCESS, prefs.lastStatus)
        assertEquals(null, prefs.lastBgStatus)
        // …but the passport session is still rolled forward so the login outlives
        // weeks without a data sync.
        assertEquals(1, session.staleRefreshes)
    }

    @Test
    fun missingToken_userInitiated_notLoggedIn() = runBlocking {
        val prefs = FakePrefs()
        val outcome = coordinator(prefs = prefs, token = null).run(
            requestHealthPermissions = false,
            userInitiated = true,
        )
        assertEquals(SyncOutcome.NotLoggedIn, outcome)
        assertEquals(SyncOutcome.STATUS_NOT_LOGGED_IN, prefs.lastStatus)
    }

    @Test
    fun healthUnavailable() = runBlocking {
        val prefs = FakePrefs()
        val health = FakeHealth(available = false)
        val outcome = coordinator(prefs = prefs, token = "t", health = health).run(
            requestHealthPermissions = false,
            userInitiated = true,
        )
        assertEquals(SyncOutcome.HealthUnavailable, outcome)
    }

    @Test
    fun permissionDenied_failedNonRetryable() = runBlocking {
        val prefs = FakePrefs()
        val health = FakeHealth(available = true, permissionError = "denied")
        val outcome = coordinator(prefs = prefs, token = "t", health = health).run(
            requestHealthPermissions = true,
            userInitiated = true,
        )
        assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(false, outcome.retryable)
        assertTrue(outcome.message!!.contains("denied"))
    }

    @Test
    fun fullSuccess_persistsOutcome() = runBlocking {
        val prefs = FakePrefs()
        val runner = FakeSyncRunner(
            result = SyncRunResult(
                attempted = 2,
                succeeded = 2,
                failed = 0,
                totalRecords = 10,
                hadRetryableFailure = false,
                hadAuthFailure = false,
                errorMessages = emptyList(),
            ),
        )
        val outcome = coordinator(prefs = prefs, token = "t", runner = runner).run(
            requestHealthPermissions = false,
            userInitiated = true,
            resetProgress = true,
        )
        assertEquals(SyncOutcome.Success, outcome)
        assertTrue(runner.resetCalled)
        assertEquals("success", prefs.lastStatus)
    }

    @Test
    fun partialSuccess_mapsCorrectly() = runBlocking {
        val prefs = FakePrefs()
        val runner = FakeSyncRunner(
            result = SyncRunResult(
                attempted = 3,
                succeeded = 2,
                failed = 1,
                totalRecords = 5,
                hadRetryableFailure = true,
                hadAuthFailure = false,
                errorMessages = listOf("timeout"),
            ),
        )
        val outcome = coordinator(prefs = prefs, token = "t", runner = runner).run(
            requestHealthPermissions = false,
            userInitiated = true,
        )
        assertIs<SyncOutcome.PartialSuccess>(outcome)
        assertEquals(2, outcome.succeeded)
        assertEquals(1, outcome.failed)
        assertEquals(false, outcome.shouldRetryBackground())
    }

    @Test
    fun totalRetryableFailure_shouldRetryBackground() = runBlocking {
        val prefs = FakePrefs()
        val runner = FakeSyncRunner(
            result = SyncRunResult(
                attempted = 1,
                succeeded = 0,
                failed = 1,
                totalRecords = 0,
                hadRetryableFailure = true,
                hadAuthFailure = false,
                errorMessages = listOf("network"),
            ),
        )
        val outcome = coordinator(prefs = prefs, token = "t", runner = runner).run(
            requestHealthPermissions = false,
            userInitiated = true,
        )
        assertIs<SyncOutcome.Failed>(outcome)
        assertTrue(outcome.shouldRetryBackground())
    }

    @Test
    fun emptyEnabledMetrics_skips() = runBlocking {
        val prefs = FakePrefs(enabled = emptySet())
        val outcome = coordinator(prefs = prefs, token = "t").run(
            requestHealthPermissions = false,
            userInitiated = true,
        )
        assertEquals(SyncOutcome.Skipped, outcome)
    }

    @Test
    fun skippedRun_leavesLastSyncPrefsUntouched() = runBlocking {
        val prefs = FakePrefs()
        prefs.lastStatus = SyncOutcome.STATUS_SUCCESS
        val outcome = coordinator(prefs = prefs, token = "t", runner = FakeSyncRunner()).run(
            requestHealthPermissions = false,
            userInitiated = false,
            requireAutoSync = false,
        )
        // Empty repository result maps to Skipped, which must not clobber the prior Success.
        assertEquals(SyncOutcome.Skipped, outcome)
        assertEquals(SyncOutcome.STATUS_SUCCESS, prefs.lastStatus)
    }

    @Test
    fun backgroundRun_recordsBackgroundKeysOnly() = runBlocking {
        val prefs = FakePrefs()
        val runner = FakeSyncRunner(
            result = SyncRunResult(
                attempted = 2,
                succeeded = 2,
                failed = 0,
                totalRecords = 10,
                hadRetryableFailure = false,
                hadAuthFailure = false,
                errorMessages = emptyList(),
            ),
        )
        val outcome = coordinator(prefs = prefs, token = "t", runner = runner).run(
            requestHealthPermissions = false,
            recordAsBackground = true,
            userInitiated = false,
        )
        assertEquals(SyncOutcome.Success, outcome)
        assertEquals(SyncOutcome.STATUS_SUCCESS, prefs.lastBgStatus)
        assertEquals(null, prefs.lastStatus)
    }

    @Test
    fun concurrentRun_secondReturnsAlreadyRunning() = runBlocking {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val prefs = FakePrefs()
        val runner = BlockingSyncRunner(hold, release)
        val coord = coordinator(prefs = prefs, token = "t", runner = runner)
        val first = async {
            coord.run(requestHealthPermissions = false, userInitiated = true)
        }
        hold.await()
        assertTrue(coord.isRunning.value)
        val second = coord.run(requestHealthPermissions = false, userInitiated = true)
        assertEquals(SyncOutcome.AlreadyRunning, second)
        // AlreadyRunning must not overwrite last sync prefs
        assertEquals(null, prefs.lastStatus)
        release.complete(Unit)
        assertIs<SyncOutcome.Success>(first.await())
        assertEquals(false, coord.isRunning.value)
        assertEquals(SyncOutcome.STATUS_SUCCESS, prefs.lastStatus)
    }

    @Test
    fun activeSession_rollsPassportForwardBeforeSyncing() = runBlocking {
        val session = FakeSession(ok = true)
        val runner = FakeSyncRunner()
        coordinator(prefs = FakePrefs(), token = "t", session = session, runner = runner).run(
            requestHealthPermissions = false,
            userInitiated = true,
        )
        assertEquals(1, session.staleRefreshes)
    }

    @Test
    fun inactiveSession_doesNotAttemptPassportRefresh() = runBlocking {
        val session = FakeSession(ok = false)
        val outcome = coordinator(prefs = FakePrefs(), token = "t", session = session).run(
            requestHealthPermissions = false,
            userInitiated = true,
        )
        assertEquals(SyncOutcome.NotLoggedIn, outcome)
        assertEquals(0, session.staleRefreshes)
    }

    @Test fun userPreparationRequestsBackgroundForManualImportsWithAutoSyncOff() = runBlocking {
        val health = FakeHealth()
        val runner = FakeSyncRunner()
        val prefs = FakePrefs(autoSync = false, enabled = setOf(SyncPreferences.HEART_RATE_IMPORT_KEY))
        val coord = coordinator(prefs, "t", health = health, runner = runner)
        assertEquals(null, coord.prepareUserSync())
        assertTrue(health.requestedBackground)
        assertEquals(setOf(HealthInboundMetric.HEART_RATE), health.requestedReadMetrics)
        assertEquals(0, health.writePermissionRequests)
        assertEquals(false, runner.called)
        assertEquals(false, coord.isRunning.value)
        assertEquals(null, prefs.lastStatus)
    }

    @Test fun preparedServiceRunDoesNotRequestPermissionUiAgain() = runBlocking {
        val health = FakeHealth()
        val runner = FakeSyncRunner()
        val coord = coordinator(FakePrefs(enabled = setOf("heart_rate")), "t", health = health, runner = runner)
        assertEquals(null, coord.prepareUserSync())
        coord.run(requestHealthPermissions = false)
        assertEquals(1, health.writePermissionRequests)
        assertTrue(runner.called)
    }

    @Test fun loggedOutPreparationNeverRequestsPermissionOrStartsRepository() = runBlocking {
        val health = FakeHealth()
        val runner = FakeSyncRunner()
        assertEquals(SyncOutcome.NotLoggedIn, coordinator(FakePrefs(), null, health = health, runner = runner).prepareUserSync())
        assertEquals(0, health.writePermissionRequests)
        assertEquals(false, runner.called)
    }

    @Test fun cloudOnlyUserPreparationDoesNotRequireHealthConnect() = runBlocking {
        val health = FakeHealth(available = false, permissionError = "must not request")
        val coord = coordinator(FakePrefs(enabled = setOf(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY)), "t", health = health)
        assertEquals(null, coord.prepareUserSync())
        assertEquals(0, health.writePermissionRequests)
        assertTrue(health.requestedReadMetrics.isEmpty())
    }

    @Test fun failedUserPreparationIsRecordedForAServiceStartedFromHome() = runBlocking {
        val prefs = FakePrefs(enabled = setOf("steps"))
        val coord = coordinator(prefs, "t", health = FakeHealth(available = false))
        assertEquals(SyncOutcome.HealthUnavailable, coord.prepareUserSync())
        assertEquals(SyncOutcome.STATUS_HEALTH_UNAVAILABLE, prefs.lastStatus)
        assertEquals(SyncOutcome.HealthUnavailable, coord.lastOutcome.value)
    }

    @Test
    fun scheduledCatchUpUsesPerMetricCheckpointsAndNeverInheritsTheManualRange() = runBlocking {
        val baseline = Instant.parse("2026-11-19T12:00:00Z")
        var clock = baseline
        val prefs = FakePrefs(enabled = setOf("steps", "sleep", "weight"), rangeDays = 30)
        val account = MiCredentials("id", "security", "service", "pass", "d", "sg")
        val calls = mutableListOf<Pair<AutoSyncWindow, Set<String>>>()
        val runner = object : HealthSyncRunner {
            override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress())
            override fun resetProgress() = Unit
            override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult {
                calls += AutoSyncWindow(Instant.parse(from).epochSeconds, Instant.parse(to).epochSeconds) to enabled
                val completed = enabled - "sleep"
                return SyncRunResult(enabled.size, completed.size, enabled.size - completed.size, completed.size,
                    false, false, emptyList(), confirmedMetricKeys = completed)
            }
        }
        fun controller() = coordinator(prefs, "t", runner = runner, now = { clock }, account = account)
        suspend fun automatic() = controller().run(requireAutoSync = true, requestHealthPermissions = false,
            recordAsBackground = true, userInitiated = false)
        fun window(fromDays: Long, untilDays: Long) = AutoSyncWindow(
            (baseline + fromDays.days).epochSeconds, (baseline + untilDays.days).epochSeconds)

        automatic()
        assertEquals(listOf(window(-1, 0) to setOf("steps", "sleep", "weight")), calls)
        assertEquals(setOf(baseline.epochSeconds, (baseline - 1.days).epochSeconds),
            prefs.automaticCheckpoints.values.map { it.contiguousUntil }.toSet())

        val beforeManual = prefs.automaticCheckpoints.toMap()
        controller().run(requestHealthPermissions = false)
        assertEquals(window(-30, 0), calls.last().first)
        assertEquals(beforeManual, prefs.automaticCheckpoints)

        calls.clear()
        clock += 3.days
        automatic()
        assertEquals(listOf(window(2, 3) to setOf("steps", "sleep", "weight"),
            window(-1, 0) to setOf("sleep"), window(0, 1) to setOf("steps"),
            window(0, 1) to setOf("sleep"), window(1, 2) to setOf("steps")), calls)
        assertEquals(setOf(AutoSyncCheckpoint((baseline - 1.days).epochSeconds,
            historicalScanFrom = (baseline + 1.days).epochSeconds), AutoSyncCheckpoint((baseline + 3.days).epochSeconds)),
            prefs.automaticCheckpoints.values.toSet())

        calls.clear()
        clock += 61.minutes
        automatic()
        assertEquals(window(1, 2) to setOf("sleep"), calls.last())
        assertEquals(1, calls.count { "steps" in it.second })
        assertEquals(setOf((baseline - 1.days).epochSeconds, clock.epochSeconds),
            prefs.automaticCheckpoints.values.map { it.contiguousUntil }.toSet())
    }

    @Test
    fun recentTimeoutDoesNotConsumeTheReservedHistoricalBudget() = runTest {
        val until = Instant.parse("2026-11-19T12:00:00Z")
        val base = (until - 3.days).epochSeconds
        val prefs = FakePrefs(enabled = setOf("steps"))
        prefs.automaticCheckpoints["steps-key"] = AutoSyncCheckpoint(base)
        val calls = mutableListOf<AutoSyncWindow>()
        val runner = object : HealthSyncRunner {
            override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress())
            override fun resetProgress() = Unit
            override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult {
                calls += AutoSyncWindow(Instant.parse(from).epochSeconds, Instant.parse(to).epochSeconds)
                if (calls.size == 1) awaitCancellation()
                return SyncRunResult(1, 1, 0, 1, false, false, emptyList(), confirmedMetricKeys = enabled)
            }
        }
        val selection = ActiveSyncSelection(1, setOf("steps"), until - 1.days, until)
        val report = AutomaticSyncRunner(prefs, runner).run(selection, mapOf("steps" to "steps-key")) { true }
        assertTrue(report.isPartialSuccess)
        assertEquals(listOf(AutoSyncWindow((until - 1.days).epochSeconds, until.epochSeconds),
            AutoSyncWindow(base, base + AUTO_WINDOW_SECONDS),
            AutoSyncWindow(base + AUTO_WINDOW_SECONDS, base + 2 * AUTO_WINDOW_SECONDS)), calls)
        assertEquals(AutoSyncCheckpoint(base + 2 * AUTO_WINDOW_SECONDS), prefs.automaticCheckpoints["steps-key"])
    }

    @Test
    fun automaticTimeoutsWithNewlySavedPagesKeepTheSameSliceAndLogicalIdentity() = runTest {
        val until = Instant.parse("2026-11-19T12:00:00Z")
        val metrics = setOf("steps")
        val prefs = FakePrefs(enabled = metrics)
        val reads = mutableListOf<Triple<SyncPageScope, String, String>>()
        var revision = 0L
        var discarded: SyncPageScope? = null
        val runner = object : HealthSyncRunner {
            override val syncProgress = MutableStateFlow(SyncProgress())
            override fun resetProgress() = Unit
            override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult =
                error("Expected logical read scope")
            override suspend fun pageCheckpointRevision() = revision
            override suspend fun discardPageCheckpoints(scope: SyncPageScope) { discarded = scope }
            override suspend fun syncCheckpointedSlice(from: String, to: String, enabled: Set<String>, zeppRead: AutomaticZeppRead?,
                scope: SyncPageScope): SyncRunResult {
                reads += Triple(scope, from, to)
                if (reads.size <= 2) {
                    revision++
                    awaitCancellation()
                }
                return SyncRunResult(1, 1, 0, 1, false, false, emptyList(), confirmedMetricKeys = enabled)
            }
        }
        suspend fun run(end: Instant, id: String) = AutomaticSyncRunner(prefs, runner).run(
            ActiveSyncSelection(1, metrics, end - 1.days, end), mapOf("steps" to "steps-key"), id, generation = 7,
        ) { true }
        assertTrue(run(until, "original").isTotalFailure)
        assertEquals(0, prefs.automaticContinuation?.remaining?.single()?.timeouts)
        assertTrue(run(until + 1.hours, "next").isTotalFailure)
        assertEquals(0, prefs.automaticContinuation?.remaining?.single()?.timeouts)
        assertTrue(run(until + 2.hours, "third").isFullSuccess)
        assertEquals(listOf(Triple(SyncPageScope("original", 7), (until - 1.days).toString(), until.toString())), reads.distinct())
        assertEquals(SyncPageScope("original", 7), discarded)
        assertEquals(null, prefs.automaticContinuation)
    }

    @Test
    fun laterInvocationResumesOnlyUnfinishedFixedSlicesOfTheOriginalPlan() = runTest {
        val until = Instant.parse("2026-11-19T12:00:00Z")
        val recent = AutoSyncWindow((until - 1.days).epochSeconds, until.epochSeconds)
        val old = AutoSyncWindow((until - 3.days).epochSeconds, (until - 2.days).epochSeconds)
        val intermediate = AutoSyncWindow(old.until, recent.from)
        val prefs = FakePrefs(enabled = setOf("steps"))
        prefs.automaticCheckpoints["steps-key"] = AutoSyncCheckpoint(old.from)
        val keys = mapOf("steps" to "steps-key")
        val calls = mutableListOf<AutoSyncWindow>()
        val runner = object : HealthSyncRunner {
            override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress())
            override fun resetProgress() = Unit
            override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult {
                calls += AutoSyncWindow(Instant.parse(from).epochSeconds, Instant.parse(to).epochSeconds)
                if (calls.size == 2) awaitCancellation()
                return SyncRunResult(1, 1, 0, 1, false, false, emptyList(), confirmedMetricKeys = enabled)
            }
        }
        val initial = ActiveSyncSelection(1, setOf("steps"), until - 1.days, until)
        val first = AutomaticSyncRunner(prefs, runner).run(initial, keys, "logical-original", 7) { true }
        assertTrue(first.isPartialSuccess)
        assertEquals(listOf(recent, old, intermediate), calls)
        val remaining = checkNotNull(prefs.automaticContinuation)
        assertEquals("logical-original", remaining.id)
        assertEquals(listOf(AutoSyncSlice(old, setOf("steps"), historical = true, timeouts = 1)), remaining.remaining)
        assertEquals(AutoSyncCheckpoint(old.from, listOf(AutoSyncWindow(old.until, recent.until)), recent.from),
            prefs.automaticCheckpoints["steps-key"])

        val later = initial.copy(from = initial.from + 61.minutes, to = initial.to + 61.minutes)
        AutomaticSyncRunner(prefs, runner).run(later, keys, "next-worker", 7) { true }
        assertEquals(listOf(recent, old, intermediate, old), calls)
        assertEquals(null, prefs.automaticContinuation)
        assertEquals(AutoSyncCheckpoint(recent.until), prefs.automaticCheckpoints["steps-key"])
    }

    @Test
    fun zeppSplitResumesFixedChildrenWithoutRequeryingConfirmedSiblingsOrOtherMetrics() = runTest {
        val until = Instant.parse("2026-11-19T12:00:00Z")
        val from = until - 1.days
        val midpoint = from + 12.hours
        val zepp = SyncPreferences.ZEPP_CLOUD_IMPORT_KEY
        val metrics = setOf(zepp, "steps")
        val keys = mapOf(zepp to "zepp-source", "steps" to "steps-source")
        val requiredEvents = setOf(ZeppStream.PAI, ZeppStream.STRESS)
        val prefs = FakePrefs(enabled = metrics)
        val calls = mutableListOf<AutoSyncWindow>()
        val runner = object : HealthSyncRunner {
            override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress())
            override fun resetProgress() = Unit
            override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult =
                error("Expected automatic read context")

            override suspend fun syncAutomaticSlice(from: String, to: String, enabled: Set<String>,
                zeppRead: AutomaticZeppRead?): SyncRunResult {
                calls += AutoSyncWindow(Instant.parse(from).epochSeconds, Instant.parse(to).epochSeconds)
                assertEquals(17L, zeppRead?.sourceIdentity)
                assertEquals(true, zeppRead?.deferEventSplit)
                if (calls.size == 1) {
                    assertEquals(metrics, enabled)
                    return SyncRunResult(2, 1, 1, 1, false, false, listOf("incomplete"),
                        confirmedMetricKeys = setOf("steps"), zeppEventSplit = requiredEvents)
                }
                assertEquals(setOf(zepp), enabled)
                assertEquals(requiredEvents, zeppRead?.requiredEvents)
                if (calls.size == 3) return SyncRunResult(1, 0, 1, 0, true, false, listOf("temporary"))
                if (calls.size == 4) throw CancellationException("process interrupted during the child read")
                return SyncRunResult(1, 1, 0, 1, false, false, emptyList(), confirmedMetricKeys = enabled)
            }
        }
        val initial = ActiveSyncSelection(1, metrics, from, until)
        suspend fun run(at: Instant) = AutomaticSyncRunner(prefs, runner).run(
            initial.copy(from = at - 1.days, to = at), keys, "plan-$at", 7, zeppSourceIdentity = 17L,
        ) { true }
        assertTrue(run(until).isPartialSuccess)
        val splitPlan = checkNotNull(prefs.automaticContinuation)
        prefs.automaticContinuation = Json.decodeFromString(Json.encodeToString(splitPlan))
        assertFalse(splitPlan.matches(8, metrics, keys))
        assertFalse(splitPlan.matches(7, metrics, keys + (zepp to "other-region")))
        assertEquals(from.epochSeconds, prefs.automaticCheckpoints["zepp-source"]?.contiguousUntil)
        assertEquals(until.epochSeconds, prefs.automaticCheckpoints["steps-source"]?.contiguousUntil)

        assertTrue(run(until + 1.hours).isPartialSuccess)
        assertEquals(splitPlan.id, prefs.automaticContinuation?.id)
        assertEquals(midpoint.epochSeconds, prefs.automaticCheckpoints["zepp-source"]?.contiguousUntil)
        assertEquals(1, prefs.automaticContinuation?.remaining?.single()?.failedLeafAttempts)
        val pending = prefs.automaticContinuation
        assertFailsWith<CancellationException> { run(until + 2.hours) }
        assertEquals(pending, prefs.automaticContinuation)
        assertEquals(midpoint.epochSeconds, prefs.automaticCheckpoints["zepp-source"]?.contiguousUntil)
        assertTrue(run(until + 3.hours).isFullSuccess)
        assertEquals(null, prefs.automaticContinuation)
        assertEquals(until.epochSeconds, prefs.automaticCheckpoints["zepp-source"]?.contiguousUntil)
        assertEquals(listOf(AutoSyncWindow(from.epochSeconds, until.epochSeconds),
            AutoSyncWindow(from.epochSeconds, midpoint.epochSeconds),
            AutoSyncWindow(midpoint.epochSeconds, until.epochSeconds),
            AutoSyncWindow(midpoint.epochSeconds, until.epochSeconds),
            AutoSyncWindow(midpoint.epochSeconds, until.epochSeconds)), calls)
    }

    @Test
    fun automaticCalendarLaneKeepsTwentyFiveHourReportVerificationSeparateFromRawCoverage() = runTest {
        val start = Instant.parse("2026-11-01T04:00:00Z")
        val end = Instant.parse("2026-11-02T05:00:00Z")
        val now = end + 60.minutes
        val metric = SyncPreferences.SPO2_IMPORT_KEY
        val prefs = FakePrefs(enabled = setOf(metric)).apply {
            calendarZoneId = "America/New_York"
            automaticCheckpoints["raw"] = AutoSyncCheckpoint(end.epochSeconds, coverageStart = start.epochSeconds)
        }
        val calls = mutableListOf<AutoSyncWindow>()
        var verified = false
        val runner = object : HealthSyncRunner {
            override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress())
            override fun resetProgress() = Unit
            override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult {
                calls += AutoSyncWindow(Instant.parse(from).epochSeconds, Instant.parse(to).epochSeconds)
                return SyncRunResult(1, 1, 0, 1, false, false, emptyList(), confirmedMetricKeys = enabled,
                    confirmedSourceDayKeys = if (verified && from == start.toString() && to == end.toString()) enabled else emptySet())
            }
        }
        val selection = ActiveSyncSelection(1, setOf(metric), now - 1.days, now)
        val daily = AutoSyncWindow(start.epochSeconds, end.epochSeconds)
        val first = AutomaticSyncRunner(prefs, runner).run(selection, mapOf(metric to "raw")) { true }
        assertTrue(first.isPartialSuccess)
        assertEquals(daily, calls.last())
        assertEquals(now.epochSeconds, prefs.automaticCheckpoints.getValue("raw").contiguousUntil)
        assertEquals(start.epochSeconds, prefs.automaticCheckpoints.getValue("raw-source-day").contiguousUntil)

        verified = true
        val retry = AutomaticSyncRunner(prefs, runner).run(selection, mapOf(metric to "raw")) { true }
        assertTrue(retry.isFullSuccess)
        assertEquals(end.epochSeconds, prefs.automaticCheckpoints.getValue("raw-source-day").contiguousUntil)
        AutomaticSyncRunner(prefs, runner).run(selection, mapOf(metric to "raw")) { true }
        assertEquals(2, calls.count { it == daily })
    }

    @Test
    fun summaryUsesTheRunningRequestIdentityAndDoesNotArchivePolicySkipsAsCompletedWork() = runBlocking {
        val clock = Instant.parse("2026-11-19T12:00:00Z")
        val prefs = FakePrefs(rangeDays = 30)
        val report = SyncRunResult(2, 1, 1, 3, false, false, listOf("private details"))
        val coordinator = coordinator(prefs, "t", runner = FakeSyncRunner(report), now = { clock })
        val manual = coordinator.captureUserSyncRequest()
        val finished = mutableListOf<SyncTaskSummary>()
        coordinator.run(request = manual, requestHealthPermissions = false, source = SyncTaskSource.RECOVERED,
            onTaskFinished = { summary ->
                assertEquals(summary.id, coordinator.activeTask.value?.id)
                finished += summary
            })
        coordinator.run(requireAutoSync = true, recordAsBackground = true, userInitiated = false,
            requestHealthPermissions = false, onTaskFinished = { finished += it })
        val summary = finished.single()
        assertEquals(manual.runId, summary.id)
        assertEquals(SyncTaskSource.RECOVERED, summary.source)
        assertEquals(SyncOutcome.STATUS_PARTIAL_SUCCESS, summary.status)
        assertEquals(manual.selection.from.toString(), summary.from)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.failed)
        assertTrue("private details" !in summary.toString())
        assertEquals(null, coordinator.activeTask.value)
        assertEquals(manual.runId, coordinator.latestStartedTask.value?.id)
    }

    @Test
    fun permissionFeedbackAndLateCompletionCannotBelongToANewerSuccessfulTask() = runBlocking {
        val prefs = FakePrefs(enabled = setOf("sleep"))
        val success = SyncRunResult(1, 1, 0, 1, false, false, emptyList())
        val coordinator = coordinator(prefs, "t", runner = FakeSyncRunner(success))
        val oldRequest = coordinator.captureUserSyncRequest()
        val before = coordinator.latestStartedTask.value
        assertEquals(SyncOutcome.Success, coordinator.run(request = oldRequest, requestHealthPermissions = false))
        val oldTask = checkNotNull(coordinator.latestStartedTask.value)
        val permissionError = SyncScreenFeedback(oldTask, error = "older prompt denied access")
        assertEquals(permissionError, permissionError.forTask(oldTask))
        assertTrue(ownsSyncCompletion(before, oldTask, oldRequest))

        val nextRequest = coordinator.captureUserSyncRequest()
        assertEquals(SyncOutcome.Success, coordinator.run(request = nextRequest, requestHealthPermissions = false))
        val latest = checkNotNull(coordinator.latestStartedTask.value)
        assertEquals(nextRequest.runId, latest.id)
        assertEquals(null, coordinator.activeTask.value)
        assertEquals(null, permissionError.forTask(latest))
        assertFalse(ownsSyncCompletion(before, latest, oldRequest))
        assertTrue(ownsSyncCompletion(oldTask, latest, nextRequest))
        assertFalse(ownsSyncCompletion(oldTask, latest.copy(accountGeneration = nextRequest.accountGeneration + 1), nextRequest))
        assertEquals(SyncOutcome.Success, coordinator.lastOutcome.value)
    }

    @Test
    fun workManagerRetryKeepsItsOwnerAndBypassesOnlyTheNormalInterval() = runBlocking {
        val start = Instant.parse("2026-11-19T12:00:00Z")
        var clock = start
        val prefs = FakePrefs(enabled = setOf("sleep"))
        val failure = FakeSyncRunner(SyncRunResult(1, 0, 1, 0, true, false, listOf("network")))
        val success = FakeSyncRunner(SyncRunResult(1, 1, 0, 1, false, false, emptyList()))
        fun controller(runner: HealthSyncRunner) = coordinator(prefs, "t", runner = runner, now = { clock })
        suspend fun scheduled(owner: String, attempt: Int, runner: HealthSyncRunner) = controller(runner).run(
            requireAutoSync = true, requestHealthPermissions = false, recordAsBackground = true, userInitiated = false,
            automaticAttempt = AutomaticWorkAttempt(owner, attempt))

        assertIs<SyncOutcome.Failed>(scheduled("original", 0, failure))
        assertTrue(controller(success).hasPendingAutomaticRetry("original"))
        assertEquals(start.epochSeconds + 900, prefs.autoTiming.retry?.notBefore)
        clock += 14.minutes
        assertEquals(SyncOutcome.Skipped, scheduled("original", 1, success))
        clock += 1.minutes
        assertEquals(SyncOutcome.Skipped, scheduled("competing", 0, success))
        prefs.recordManualFinish(clock.epochSeconds)
        assertEquals(SyncOutcome.Skipped, scheduled("original", 1, success))
        assertEquals(false, success.called)
        clock += 15.minutes
        assertEquals(SyncOutcome.Success, scheduled("original", 2, success))
        assertEquals(start.epochSeconds, prefs.autoTiming.lastAutomaticStart)
        assertEquals(null, prefs.autoTiming.retry)
        assertEquals(SyncOutcome.Skipped, scheduled("original", 3, success))
    }

    @Test
    fun persistedSchedulerRebuildsItsRetryAttemptAndDoesNotRestartTheNormalCadence() = runBlocking {
        val start = Instant.parse("2026-11-19T12:00:00Z")
        var clock = start
        val owner = "processing"
        val prefs = FakePrefs(enabled = setOf("sleep"))
        val failure = FakeSyncRunner(SyncRunResult(1, 0, 1, 0, true, false, listOf("network")))
        val success = FakeSyncRunner(SyncRunResult(1, 1, 0, 1, false, false, emptyList()))
        fun controller(runner: HealthSyncRunner) = coordinator(prefs, "t", runner = runner, now = { clock })
        suspend fun scheduled(runner: HealthSyncRunner) = controller(runner).run(
            requireAutoSync = true, requestHealthPermissions = false, recordAsBackground = true, userInitiated = false,
            persistedAutomaticOwner = owner,
        )

        assertIs<SyncOutcome.Failed>(scheduled(failure))
        assertEquals(900L, controller(success).automaticScheduleDelay(owner))
        clock += 14.minutes
        assertEquals(SyncOutcome.Skipped, scheduled(success))
        assertEquals(false, success.called)
        clock += 1.minutes
        assertEquals(SyncOutcome.Success, scheduled(success))
        assertEquals(start.epochSeconds, prefs.autoTiming.lastAutomaticStart)
        assertEquals(null, prefs.autoTiming.retry)
        assertEquals(2_700L, controller(success).automaticScheduleDelay(owner))
    }

    @Test
    fun disablingAutomaticWorkNeverCancelsAManualWriter() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val prefs = FakePrefs(enabled = setOf("sleep"))
        val coordinator = coordinator(prefs, "t", runner = BlockingSyncRunner(entered, release))
        val manual = async { coordinator.run(requestHealthPermissions = false) }
        try {
            entered.await()
            prefs.autoSync.value = false
            coordinator.cancelAutomaticRunAndAwait()
            assertEquals(false, manual.isCompleted)
        } finally {
            release.complete(Unit)
        }
        assertEquals(SyncOutcome.Success, manual.await())
    }

    @Test
    fun disablingAutomaticWorkCancelsTheOwnedRunAndWaitsForCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val prefs = FakePrefs(enabled = setOf("sleep"))
        val runner = BlockingSyncRunner(entered, CompletableDeferred()) {
            withContext(NonCancellable) { cleanup.complete(Unit); release.await() }
        }
        val coordinator = coordinator(prefs, "t", runner = runner)
        val automatic = async { coordinator.run(requireAutoSync = true, recordAsBackground = true,
            userInitiated = false, requestHealthPermissions = false) }
        entered.await()
        prefs.autoSync.value = false
        val stopping = async { coordinator.cancelAutomaticRunAndAwait() }
        try {
            cleanup.await()
            assertEquals(false, stopping.isCompleted)
            assertEquals(SyncOutcome.AlreadyRunning, coordinator.run(requestHealthPermissions = false))
        } finally {
            release.complete(Unit)
        }
        stopping.await()
        assertFailsWith<UserSyncStopped> { automatic.await() }
        assertEquals(null, prefs.autoTiming.pausedUntil)
    }

    @Test
    fun skippedAutomaticChecksKeepTheLastOutcomeAndDoNotMoveTheStartTime() = runBlocking {
        val start = Instant.parse("2026-11-19T12:00:00Z")
        var clock = start
        val prefs = FakePrefs(enabled = setOf("sleep"))
        val runner = FakeSyncRunner(SyncRunResult(1, 1, 0, 1, false, false, emptyList()))
        val coordinator = coordinator(prefs, "t", runner = runner, now = { clock })
        suspend fun runAutomatic() = coordinator.run(requireAutoSync = true, recordAsBackground = true,
            userInitiated = false, requestHealthPermissions = false)

        assertEquals(SyncOutcome.Success, runAutomatic())
        clock += 1.minutes
        assertEquals(SyncOutcome.Skipped, runAutomatic())
        assertEquals(AutomaticSkip(AutomaticSkipReason.NOT_DUE, clock.toString(), 0), prefs.automaticSkip)
        assertEquals(SyncOutcome.Success, coordinator.lastOutcome.value)
        assertEquals(start.epochSeconds, prefs.autoTiming.lastAutomaticStart)
        prefs.autoSync.value = false
        assertEquals(SyncOutcome.Skipped, runAutomatic())
        assertEquals(AutomaticSkipReason.DISABLED, prefs.automaticSkip?.reason)
        assertEquals(SyncOutcome.STATUS_SUCCESS, prefs.lastBgStatus)
        assertEquals(start.epochSeconds, prefs.autoTiming.lastAutomaticStart)
    }

    private fun coordinator(
        prefs: FakePrefs,
        token: String?,
        sessionOk: Boolean = true,
        session: FakeSession = FakeSession(sessionOk),
        health: FakeHealth = FakeHealth(),
        runner: HealthSyncRunner = FakeSyncRunner(),
        now: () -> Instant = { kotlin.time.Clock.System.now() },
        account: MiCredentials? = null,
    ) = SyncCoordinator(
        session = session,
        credentials = FakeCredentials(token, account),
        syncPreferences = prefs,
        healthAvailability = health,
        healthPermissions = health,
        repository = runner,
        now = now,
    )

    private class FakeSession(private val ok: Boolean) : SyncSessionPort {
        override var requiresReauthentication = false
        var rejectOnRefresh = false
        var ensureCalls = 0
        var staleRefreshes = 0
        override suspend fun ensureActive(): Boolean {
            ensureCalls++
            return ok
        }
        override suspend fun refreshSessionIfStale() {
            staleRefreshes++
            if (rejectOnRefresh) requiresReauthentication = true
        }
    }

    private class FakeCredentials(token: String?, private val account: MiCredentials? = null) : CredentialsPort {
        override val token: Flow<String?> = MutableStateFlow(token)
        override suspend fun loadCredentials(): MiCredentials? = account
        override suspend fun saveCredentials(credentials: MiCredentials) = Unit
    }

    private class FakePrefs(
        autoSync: Boolean = true,
        enabled: Set<String> = SyncPreferences.ALL_METRIC_KEYS,
        rangeDays: Int = 7,
    ) : SyncPreferencesPort {
        override val autoSync = MutableStateFlow(autoSync)
        override val enabledMetrics = MutableStateFlow(enabled)
        override val syncRangeDays = MutableStateFlow(rangeDays)
        override val lastSyncActivityTime = MutableStateFlow<String?>(null)
        var lastStatus: String? = null
        var lastMessage: String? = null
        var lastBgStatus: String? = null
        var lastBgMessage: String? = null
        var autoTiming = AutoSyncTiming()
        var automaticSkip: AutomaticSkip? = null
        val automaticCheckpoints = mutableMapOf<String, AutoSyncCheckpoint>()
        var automaticContinuation: AutoSyncContinuation? = null
        var calendarZoneId: String? = null

        override suspend fun readAutoSyncTiming(): AutoSyncTiming = autoTiming
        override suspend fun acceptAutomaticStart(at: Long): Boolean {
            if (!autoSync.value) return false
            autoTiming = autoTiming.copy(lastAutomaticStart = at, retry = null)
            return true
        }
        override suspend fun storeAutomaticRetry(retry: AutomaticRetry?) { autoTiming = autoTiming.copy(retry = retry) }
        override suspend fun storeAutomaticSkip(skip: AutomaticSkip) { automaticSkip = skip }
        override suspend fun recordManualFinish(at: Long) { autoTiming = autoTiming.copy(lastManualFinish = at) }
        override suspend fun pauseAutomaticUntil(at: Long) { autoTiming = autoTiming.copy(pausedUntil = at) }
        override suspend fun readAutomaticCheckpoints(keys: Set<String>) = automaticCheckpoints.filterKeys { it in keys }
        override suspend fun readAutomaticContinuation() = automaticContinuation
        override suspend fun readSourceCalendarZoneId() = calendarZoneId
        override suspend fun saveAutomaticProgress(generation: Long, checkpoints: Map<String, AutoSyncCheckpoint>,
            continuation: AutoSyncContinuation?) {
            writeAutomaticCheckpoints(checkpoints)
            automaticContinuation = continuation
        }
        override suspend fun writeAutomaticCheckpoints(checkpoints: Map<String, AutoSyncCheckpoint>) {
            automaticCheckpoints.putAll(checkpoints)
        }

        override suspend fun updateLastSync(timestamp: String) = Unit
        override suspend fun updateLastSyncActivity(timestamp: String) { lastSyncActivityTime.value = timestamp }
        override suspend fun updateLastBackgroundSync(timestamp: String) = Unit
        override suspend fun updateLastSyncOutcome(status: String, message: String?) {
            lastStatus = status
            lastMessage = message
        }
        override suspend fun updateLastBackgroundSyncOutcome(status: String, message: String?) {
            lastBgStatus = status
            lastBgMessage = message
        }
    }

    private class FakeHealth(
        var available: Boolean = true,
        var permissionError: String? = null,
    ) : HealthAvailability, HealthPermissionRequester {
        var writePermissionRequests = 0
        var requestedReadMetrics = emptySet<HealthInboundMetric>()
        var requestedHistory = false
        var requestedBackground = false
        override suspend fun isAvailable(): Boolean = available
        override suspend fun availabilityHint(): String? = if (available) null else "missing"
        override fun healthServiceName(): String = "Test Health"
        override fun openHealthService() = Unit
        override suspend fun hasWritePermissions(): Boolean = available && permissionError == null
        override suspend fun requestPermissions() {
            writePermissionRequests++
            permissionError?.let { throw Exception(it) }
        }
        override suspend fun requestReadPermissions(
            metrics: Set<HealthInboundMetric>, includeBackground: Boolean, includeHistory: Boolean): Set<HealthInboundMetric> {
            requestedReadMetrics = metrics
            requestedBackground = includeBackground
            requestedHistory = includeHistory
            return metrics
        }
    }

    private class FakeSyncRunner(
        private val result: SyncRunResult = SyncRunResult(
            attempted = 0,
            succeeded = 0,
            failed = 0,
            totalRecords = 0,
            hadRetryableFailure = false,
            hadAuthFailure = false,
            errorMessages = emptyList(),
        ),
    ) : HealthSyncRunner {
        var resetCalled = false
        var called = false
        private val progress = MutableStateFlow(SyncProgress())
        override val syncProgress: StateFlow<SyncProgress> = progress.asStateFlow()
        override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult {
            called = true
            return result
        }
        override fun resetProgress() {
            resetCalled = true
        }
    }

    private class BlockingSyncRunner(
        private val hold: kotlinx.coroutines.CompletableDeferred<Unit>,
        private val release: kotlinx.coroutines.CompletableDeferred<Unit>,
        private val cleanup: suspend () -> Unit = {},
    ) : HealthSyncRunner {
        private val progress = MutableStateFlow(SyncProgress())
        override val syncProgress: StateFlow<SyncProgress> = progress.asStateFlow()
        override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult {
            hold.complete(Unit)
            try {
                release.await()
            } finally {
                cleanup()
            }
            return SyncRunResult(
                attempted = 1,
                succeeded = 1,
                failed = 0,
                totalRecords = 1,
                hadRetryableFailure = false,
                hadAuthFailure = false,
                errorMessages = emptyList(),
            )
        }
        override fun resetProgress() = Unit
    }
}
