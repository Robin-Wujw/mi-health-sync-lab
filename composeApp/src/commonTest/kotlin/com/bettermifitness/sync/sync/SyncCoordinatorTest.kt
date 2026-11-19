package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.preferences.CredentialsPort
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.SyncPreferencesPort
import com.bettermifitness.sync.data.preferences.SyncSessionPort
import com.bettermifitness.sync.data.repository.HealthSyncRunner
import com.bettermifitness.sync.data.repository.SyncProgress
import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.health.HealthAvailability
import com.bettermifitness.sync.health.HealthPermissionRequester
import com.bettermifitness.sync.health.HealthInboundMetric
import com.mifitness.miclient.auth.MiCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlin.test.assertFailsWith
import kotlin.time.Instant
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
    fun manualRetryAndScheduledWorkerBypassForegroundCooldown() = runBlocking {
        val prefs = FakePrefs()
        prefs.lastSyncActivityTime.value = Instant.parse("2026-11-05T04:00:00Z").toString()
        for (background in listOf(false, true)) {
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

    private fun coordinator(
        prefs: FakePrefs,
        token: String?,
        sessionOk: Boolean = true,
        session: FakeSession = FakeSession(sessionOk),
        health: FakeHealth = FakeHealth(),
        runner: HealthSyncRunner = FakeSyncRunner(),
        now: () -> Instant = { kotlin.time.Clock.System.now() },
    ) = SyncCoordinator(
        session = session,
        credentials = FakeCredentials(token),
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

    private class FakeCredentials(token: String?) : CredentialsPort {
        override val token: Flow<String?> = MutableStateFlow(token)
        override suspend fun loadCredentials(): MiCredentials? = null
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
    ) : HealthSyncRunner {
        private val progress = MutableStateFlow(SyncProgress())
        override val syncProgress: StateFlow<SyncProgress> = progress.asStateFlow()
        override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult {
            hold.complete(Unit)
            release.await()
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
