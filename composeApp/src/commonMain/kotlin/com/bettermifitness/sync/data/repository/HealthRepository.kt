package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.SessionRefreshResult
import com.bettermifitness.sync.data.api.HeartRateEntry
import com.bettermifitness.sync.data.api.FitnessResponse
import com.bettermifitness.sync.data.api.SleepEntry
import com.bettermifitness.sync.data.api.WeightMeasurement
import com.bettermifitness.sync.data.api.WorkoutSession
import com.bettermifitness.sync.data.parse.MiFitnessParsers
import com.bettermifitness.sync.data.parse.toRaw
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.BodySyncLedger
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.api.MiDirectApi
import com.bettermifitness.sync.data.api.MiDirectHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportIdentity
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiPendingImportProbe
import com.bettermifitness.sync.sync.LiveTestSessionGate
import com.bettermifitness.sync.data.api.checkMiRhrDisplayReports
import com.bettermifitness.sync.data.api.fullyVerifiedRhrRowsForDisplay
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.data.api.buildMiWeightUploadValue
import com.bettermifitness.sync.data.api.missingMiWeightUploadFields
import com.bettermifitness.sync.data.parse.RawFitnessEntry
import com.bettermifitness.sync.health.BodySyncConflictException
import com.bettermifitness.sync.health.HealthWeightReadUnavailableException
import com.bettermifitness.sync.health.HealthWriteIncompleteException
import com.bettermifitness.sync.health.UnsupportedHrvAlgorithmException
import com.bettermifitness.sync.health.HealthRecordIds
import com.bettermifitness.sync.health.bodyExportFingerprint
import com.bettermifitness.sync.health.planBodySync
import com.bettermifitness.sync.i18n.L10n
import kotlinx.coroutines.delay
import kotlin.time.Instant
import com.bettermifitness.sync.health.HealthDataNormalizer
import com.bettermifitness.sync.health.HealthStore
import com.bettermifitness.sync.health.HealthInboundMetric
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthInboundReadUnavailableException
import com.bettermifitness.sync.health.HealthTimePolicy
import com.mifitness.miclient.api.MiApiException
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json

data class SyncProgress(
    val heartRate: SyncState = SyncState.Idle,
    val restingHeartRate: SyncState = SyncState.Idle,
    val sleep: SyncState = SyncState.Idle,
    val hrv: SyncState = SyncState.Idle,
    val steps: SyncState = SyncState.Idle,
    val distance: SyncState = SyncState.Idle,
    val activeCalories: SyncState = SyncState.Idle,
    val spo2: SyncState = SyncState.Idle,
    val weight: SyncState = SyncState.Idle,
    val workouts: SyncState = SyncState.Idle,
    val bloodPressure: SyncState = SyncState.Idle,
    val temperature: SyncState = SyncState.Idle,
    val vo2Max: SyncState = SyncState.Idle,
    val heartRateImport: SyncState = SyncState.Idle,
    val restingHeartRateImport: SyncState = SyncState.Idle,
    val sleepImport: SyncState = SyncState.Idle,
    val hrvImport: SyncState = SyncState.Idle,
    val spo2Import: SyncState = SyncState.Idle,
)

sealed class SyncState {
    data object Idle : SyncState()
    data object InProgress : SyncState()
    data class Success(val count: Int, val detail: String? = null, val diagnostic: MiHealthImportDiagnostic? = null) : SyncState()
    data class NoData(val message: String, val detail: String? = null, val diagnostic: MiHealthImportDiagnostic? = null) : SyncState()
    data class Error(val message: String, val diagnostic: MiHealthImportDiagnostic? = null) : SyncState()
}

private fun SyncProgress.withMetric(metric: String, state: SyncState): SyncProgress = when (metric) {
    "heartRate" -> copy(heartRate = state)
    "restingHeartRate" -> copy(restingHeartRate = state)
    "sleep" -> copy(sleep = state)
    "hrv" -> copy(hrv = state)
    "steps" -> copy(steps = state)
    "distance" -> copy(distance = state)
    "activeCalories" -> copy(activeCalories = state)
    "spo2" -> copy(spo2 = state)
    "weight" -> copy(weight = state)
    "workouts" -> copy(workouts = state)
    "bloodPressure" -> copy(bloodPressure = state)
    "temperature" -> copy(temperature = state)
    "vo2Max" -> copy(vo2Max = state)
    "heartRateImport" -> copy(heartRateImport = state)
    "restingHeartRateImport" -> copy(restingHeartRateImport = state)
    "sleepImport" -> copy(sleepImport = state)
    "hrvImport" -> copy(hrvImport = state)
    "spo2Import" -> copy(spo2Import = state)
    else -> this
}

/**
 * Orchestrates Mi fetch → parse → platform health write.
 * Each metric fails independently; one bad metric does not abort the rest.
 * On [MiApiException.AuthExpired], attempts a single passToken refresh for the whole run.
 *
 * Weight is bidirectional latest-only (sparse weigh-ins) — newest timestamp wins
 * with optional verified body-composition import. Other metrics use the selected
 * time window and report unavailable data or failed conversions explicitly.
 */
class HealthRepository internal constructor(
    private val apiProvider: () -> MiDirectApi,
    private val currentUserId: () -> String?,
    private val ensureSession: suspend () -> Boolean,
    refreshSession: suspend () -> SessionRefreshResult,
    private val healthWriter: HealthStore,
    private val bodyLedger: BodySyncLedger,
    private val importLedger: HealthImportLedger,
    private val syncPreferences: SyncPreferences,
) : HealthSyncRunner {
    constructor(
        session: MiSessionManager,
        healthWriter: HealthStore,
        bodyLedger: BodySyncLedger,
        importLedger: HealthImportLedger,
        syncPreferences: SyncPreferences,
    ) : this(
        apiProvider = { session.api },
        currentUserId = { session.currentUserId },
        ensureSession = { session.ensureActive() },
        refreshSession = { session.refreshSessionDetailed() },
        healthWriter = healthWriter,
        bodyLedger = bodyLedger,
        importLedger = importLedger,
        syncPreferences = syncPreferences,
    )

    override val syncProgress: StateFlow<SyncProgress>
        field = MutableStateFlow(SyncProgress())
    private val api get() = apiProvider()

    private var sawAuthFailure = false
    private var lastRefreshUserMessage: String? = null
    private var lastRefreshRetryable: Boolean = false
    private val retryableFailures = mutableListOf<Boolean>()
    private val stateMutex = Mutex()
    private val sharedRefresh = SharedAuthRefresh(refreshSession)
    // Bound concurrent Mi requests so parallel metrics cannot trigger rate limits.
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)
    private val rowCache = SyncReadCache<List<HeartRateEntry>>()
    private val workoutCache = SyncReadCache<List<WorkoutSession>>()
    private var correctWorkoutOverlap = false
    private var workoutCompletion: kotlinx.coroutines.Deferred<Unit>? = null
    private var verifiedImportFingerprints: Map<String, String> = emptyMap()
    private var inboundTestMode: Boolean = false
    private var allowLegacySleepHrvEnrichment: Boolean = false
    private var allowOwnedSleepRevision: Boolean = false
    private val liveTestMutex = Mutex()

    /** Explicit foreground action: verify existing data and recover eligible missing pending rows. */
    suspend fun processPendingImport(metric: HealthInboundMetric, from: String, to: String,
        onProgress: (MiHealthImportDiagnostic) -> Unit = {}): MiHealthImportDiagnostic {
        check(!LiveTestSessionGate.isActive)
        require(metric in setOf(HealthInboundMetric.HEART_RATE, HealthInboundMetric.OXYGEN_SATURATION))
        return withContext(Dispatchers.Default) {
            liveTestMutex.withLock {
                check(ensureSession())
                val window = SyncWindow.parse(from, to)
                require(window.to - window.from <= 30 * 86_400L)
                val plan = prepareInboundPlan(metric, from, to)
                // No whole-action auth replay after any recovery submission.
                MiPendingImportRecovery(MiDirectHealthImportCloud(apiProvider = { api }), importLedger)
                    .recover(accountKey(), plan, userRequested = true, onProgress = onProgress)
            }
        }
    }

    suspend fun reconcilePendingSpO2(
        from: String, to: String, userRequested: Boolean = false,
    ): MiHealthImportDiagnostic {
        check(!LiveTestSessionGate.isActive) { "Use the instrumentation reconciliation entry point during a live test." }
        return reconcilePendingSpO2Internal(from, to, userRequested)
    }

    suspend fun reconcilePendingSpO2ForLiveTest(from: String, to: String): MiHealthImportDiagnostic {
        check(LiveTestSessionGate.isActive) { "Live testing requires the dedicated instrumentation runner." }
        return reconcilePendingSpO2Internal(from, to, userRequested = true)
    }

    private suspend fun reconcilePendingSpO2Internal(
        from: String, to: String, userRequested: Boolean,
    ): MiHealthImportDiagnostic = liveTestMutex.withLock {
        check(ensureSession()) { "Sign in on the phone before reconciling imports." }
        sharedRefresh.reset()
        executeWithAuthRetry {
            val plan = prepareInboundPlan(HealthInboundMetric.OXYGEN_SATURATION, from, to, liveTestWindow = true)
            MiPendingSpO2Reconciler(MiDirectHealthImportCloud(apiProvider = { api }), importLedger)
                .reconcile(accountKey(), plan, userRequested)
        }
    }

    /** Invoked only by the explicit foreground recovery action under SyncCoordinator's lock. */
    suspend fun recoverOnePendingSpO2(
        from: String, to: String, userRequested: Boolean = false,
    ): MiHealthImportDiagnostic {
        check(!LiveTestSessionGate.isActive) { "Use the instrumentation recovery entry point during a live test." }
        return recoverPendingSpO2(from, to, userRequested)
    }

    suspend fun recoverPendingSpO2ForLiveTest(from: String, to: String): MiHealthImportDiagnostic {
        check(LiveTestSessionGate.isActive) { "Live testing requires the dedicated instrumentation runner." }
        return recoverPendingSpO2(from, to, userRequested = true)
    }

    private suspend fun recoverPendingSpO2(
        from: String, to: String, userRequested: Boolean,
    ): MiHealthImportDiagnostic = liveTestMutex.withLock {
        check(ensureSession()) { "Sign in on the phone before recovering imports." }
        sharedRefresh.reset()
        executeWithAuthRetry {
            val plan = prepareInboundPlan(HealthInboundMetric.OXYGEN_SATURATION, from, to, liveTestWindow = true)
            MiPendingSpO2Recovery(MiDirectHealthImportCloud(apiProvider = { api }), importLedger)
                .recoverOne(accountKey(), plan, userRequested)
        }
    }

    /** Instrumentation-only reads; does not execute the import Runner or alter its journal. */
    suspend fun inspectInboundForLiveTest(
        metric: HealthInboundMetric, from: String, to: String,
    ): MiHealthLiveInspection {
        check(LiveTestSessionGate.isActive) { "Live testing requires the dedicated instrumentation runner." }
        return liveTestMutex.withLock {
            check(ensureSession()) { "Sign in on the phone before running live tests." }
            sharedRefresh.reset()
            executeWithAuthRetry {
                val plan = prepareInboundPlan(metric, from, to, liveTestWindow = true)
                val cloud = MiDirectHealthImportCloud(apiProvider = { api })
                val probe = MiPendingImportProbe(cloud, importLedger)
                    .inspect(accountKey(), plan)
                val sleep = if (metric == HealthInboundMetric.SLEEP) MiSleepConflictInspector(cloud, importLedger)
                    .inspect(accountKey(), plan, syncPreferences.allowOwnedSleepRevision.first()) else null
                MiHealthLiveInspection(inspectMiHealthImportPreflight(accountKey(), plan, cloud, importLedger)
                    .copy(sleepConflict = sleep), probe)
            }
        }
    }

    /** Bounded actual-data write probe. Saved selections/preferences are not modified. */
    suspend fun runInboundForLiveTest(
        metric: HealthInboundMetric, from: String, to: String, singleRecord: Boolean = true,
    ): MiHealthImportDiagnostic {
        check(LiveTestSessionGate.isActive) { "Live testing requires the dedicated instrumentation runner." }
        require(singleRecord) { "Live write tests support one selected raw target and no daily uploads." }
        return liveTestMutex.withLock {
            check(ensureSession()) { "Sign in on the phone before running live tests." }
            sharedRefresh.reset()
            executeWithAuthRetry {
                val plan = prepareInboundPlan(metric, from, to, liveTestWindow = true)
                val cloud = MiDirectHealthImportCloud(apiProvider = { api })
                val result = try {
                    MiHealthImportRunner(cloud, importLedger).run(accountKey(), plan, testMode = true,
                        allowLegacySleepHrvEnrichment = syncPreferences.allowLegacySleepHrvEnrichment.first(),
                        allowOwnedSleepRevision = syncPreferences.allowOwnedSleepRevision.first()).diagnostic!!
                } catch (e: MiHealthImportRunException) {
                    e.diagnostic ?: throw e
                }
                attachPendingProbe(result, plan, cloud)
            }
        }
    }

    /** Explicit test-only one-day execution of the same automatic workflow used by normal sync. */
    suspend fun runOneDayInboundForLiveTest(metric: HealthInboundMetric, from: String, to: String): MiHealthImportDiagnostic {
        check(LiveTestSessionGate.isActive)
        val window = SyncWindow.parse(from, to)
        require(window.to - window.from in 1..86_400L)
        return liveTestMutex.withLock {
            check(ensureSession())
            sharedRefresh.reset()
            inboundTestMode = false
            allowLegacySleepHrvEnrichment = syncPreferences.allowLegacySleepHrvEnrichment.first()
            allowOwnedSleepRevision = syncPreferences.allowOwnedSleepRevision.first()
            syncInbound(metric, from, to)
            when (val state = syncProgress.value.stateForKey("${metric.metricKey}_import_experimental")) {
                is SyncState.Success -> checkNotNull(state.diagnostic)
                is SyncState.Error -> checkNotNull(state.diagnostic)
                is SyncState.NoData -> state.diagnostic ?: MiHealthImportDiagnostic(metric.metricKey,
                    com.bettermifitness.sync.data.api.MiHealthImportStage.COMPLETE)
                else -> error("The bounded sync did not finish")
            }
        }
    }

    /** At most one still-owned correction, using the normal runner's fresh precheck and readback. */
    suspend fun repairOneOwnedImportForLiveTest(
        metric: HealthInboundMetric, from: String, to: String,
    ): MiHealthImportDiagnostic {
        check(LiveTestSessionGate.isActive)
        require(metric in setOf(HealthInboundMetric.HEART_RATE, HealthInboundMetric.RESTING_HEART_RATE, HealthInboundMetric.HRV))
        return liveTestMutex.withLock {
            check(ensureSession())
            val plan = prepareInboundPlan(metric, from, to, liveTestWindow = true)
            check(plan.issues.none { it.severity == com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity.REJECTED })
            val cloud = MiDirectHealthImportCloud(apiProvider = { api })
            val owned = importLedger.ownedVerifiedFingerprints(accountKey())
            var candidate: com.bettermifitness.sync.data.api.MiHealthImportRow? = null
            candidateSearch@ for ((group, rows) in plan.rawRows.groupBy {
                it.identity.key to it.identity.time.floorDiv(86_400)
            }) {
                val observed = cloud.readRows(MiHealthImportChannel.RAW, group.first,
                    (group.second * 86_400 - 86_400).coerceAtLeast(0), (group.second + 1) * 86_400 + 2)
                val index = com.bettermifitness.sync.data.api.MiHealthImportObservationIndex(observed)
                for (row in rows) {
                    val fingerprint = owned[HealthImportLedger.identityDigest(row.identity.stableKey)]
                    if (prepareOwnedHeartRateRevision(row, index.atTarget(row), fingerprint) != null ||
                        prepareOwnedHrvSummaryRevision(row, index.atTarget(row), fingerprint) != null) {
                        candidate = row
                        break@candidateSearch
                    }
                }
            }
            val selected = candidate ?: return@withLock plan.inspectionDiagnostic().copy(
                stage = com.bettermifitness.sync.data.api.MiHealthImportStage.COMPLETE,
                plannedRawRows = 0, plannedDailyRows = 0, notAttemptedRawRows = 0, notAttemptedDailyRows = 0)
            try {
                MiHealthImportRunner(cloud, importLedger).run(accountKey(), plan.copy(rows = listOf(selected)),
                    testMode = true).diagnostic!!
            } catch (e: MiHealthImportRunException) { e.diagnostic ?: throw e }
        }
    }

    private suspend fun prepareInboundPlan(
        metric: HealthInboundMetric, from: String, to: String, liveTestWindow: Boolean = false,
    ): MiHealthImportPlan {
        val window = SyncWindow.parse(from, to)
        if (liveTestWindow) require(window.to - window.from <= 27 * 86_400L) {
            "Select at most 27 days for a live-test inbound read."
        }
        val request = HealthInboundReadRequest(window.from, window.to, setOf(ZEPP_SOURCE_PACKAGE))
        return withContext(Dispatchers.Default) {
            prepareMiHealthImportPlan(metric, healthWriter, request, ZEPP_SOURCE_PACKAGE)
        }
    }

    private suspend fun attachPendingProbe(
        diagnostic: MiHealthImportDiagnostic, plan: MiHealthImportPlan, cloud: MiHealthImportCloud,
    ): MiHealthImportDiagnostic {
        if (diagnostic.blockedPendingRows == 0) return diagnostic
        return try {
            diagnostic.copy(pendingProbe = MiPendingImportProbe(cloud, importLedger).inspect(accountKey(), plan))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Inspection failure does not erase the original incomplete result.
            diagnostic.copy(pendingProbeFailed = true)
        }
    }

    override suspend fun syncAll(from: String, to: String, enabled: Set<String>): SyncRunResult = withContext(Dispatchers.Default) {
        sharedRefresh.reset()
        rowCache.clear()
        workoutCache.clear()
        sawAuthFailure = false
        lastRefreshUserMessage = null
        lastRefreshRetryable = false
        retryableFailures.clear()
        verifiedImportFingerprints = importLedger.verifiedFingerprints(accountKey())
        inboundTestMode = syncPreferences.inboundTestMode.first()
        allowLegacySleepHrvEnrichment = syncPreferences.allowLegacySleepHrvEnrichment.first()
        allowOwnedSleepRevision = syncPreferences.allowOwnedSleepRevision.first()
        correctWorkoutOverlap = "workouts" in enabled
        syncProgress.value = SyncProgress()
        // Reads are cached only after success, so an auth retry can really refetch.
        // Each metric owns its failure; a failed shared Deferred cannot abort the run.
        supervisorScope {
            workoutCompletion = if ("workouts" in enabled) async { syncWorkouts(from, to) } else null
            val actions = linkedMapOf<String, suspend () -> Unit>(
                "heart_rate" to { syncHeartRate(from, to) },
                "resting_heart_rate" to { syncRestingHeartRate(from, to) },
                "sleep" to { syncSleep(from, to) },
                "hrv" to { syncHrv(from, to) },
                "steps" to { syncSteps(from, to) },
                "distance" to { syncDistance(from, to) },
                "active_calories" to { syncActiveCalories(from, to) },
                "spo2" to { syncSpO2(from, to) },
                "weight" to { syncWeightBidirectional(SyncPreferences.BODY_COMPOSITION_IMPORT_KEY in enabled) },
                "blood_pressure" to { syncBloodPressure(from, to) },
                "temperature" to { syncTemperature(from, to) },
                "vo2_max" to { syncVo2Max(from, to) },
            )
            actions.filterKeys { it in enabled }.values.map { action -> async { action() } }.forEach { it.await() }
            workoutCompletion?.await()
        }
        // Import after exports: fresh cloud reads verify mutations, and a single
        // source direction cannot race its own current-run export cache.
        if (SyncPreferences.HEART_RATE_IMPORT_KEY in enabled) syncInbound(HealthInboundMetric.HEART_RATE, from, to)
        if (SyncPreferences.RESTING_HEART_RATE_IMPORT_KEY in enabled) syncInbound(HealthInboundMetric.RESTING_HEART_RATE, from, to)
        if (SyncPreferences.SLEEP_IMPORT_KEY in enabled) syncInbound(HealthInboundMetric.SLEEP, from, to)
        if (SyncPreferences.SPO2_IMPORT_KEY in enabled) syncInbound(HealthInboundMetric.OXYGEN_SATURATION, from, to)
        // Sleep runs first so an optional HRV pass may enrich the just-verified
        // sleep record, subject to the journal and missing-field checks.
        if (SyncPreferences.HRV_IMPORT_KEY in enabled) syncInbound(HealthInboundMetric.HRV, from, to)
        SyncRunResult.from(syncProgress.value, enabled, retryableFailures.toList(), sawAuthFailure)
    }

    private suspend fun rawRows(key: String, from: String, to: String): List<RawFitnessEntry> =
        fetchAllByTime(key, from, to).filterNot { isVerifiedImport(it, key) }.map { it.toRaw() }

    private fun isVerifiedImport(row: HeartRateEntry, requestedKey: String): Boolean {
        val sid = row.sid ?: return false
        val identity = MiHealthImportIdentity(MiHealthImportChannel.RAW, sid, row.key ?: requestedKey, row.time)
        val expected = verifiedImportFingerprints[HealthImportLedger.identityDigest(identity.stableKey)] ?: return false
        val actual = try { canonicalMiHealthImportJson(Json.parseToJsonElement(row.value)) }
            catch (_: Exception) { return false }
        return expected == HealthRecordIds.version(actual).toString()
    }

    private suspend fun syncInbound(metric: HealthInboundMetric, from: String, to: String) {
        val progressKey = when (metric) {
            HealthInboundMetric.HEART_RATE -> "heartRateImport"
            HealthInboundMetric.RESTING_HEART_RATE -> "restingHeartRateImport"
            HealthInboundMetric.SLEEP -> "sleepImport"
            HealthInboundMetric.HRV -> "hrvImport"
            HealthInboundMetric.OXYGEN_SATURATION -> "spo2Import"
        }
        var detail: String? = null
        var diagnostic: MiHealthImportDiagnostic? = null
        runMetric(progressKey, successDetail = { detail }, successDiagnostic = { diagnostic }) {
            val plan = prepareInboundPlan(metric, from, to)
            if (plan.inputRecordCount == 0) throw NoMetricDataException()
            val cloud = MiDirectHealthImportCloud(apiProvider = { api })
            val result = try {
                MiHealthImportWorkflow(cloud, importLedger).run(
                    accountKey(), plan, testMode = inboundTestMode,
                    allowLegacySleepHrvEnrichment = allowLegacySleepHrvEnrichment,
                    allowOwnedSleepRevision = allowOwnedSleepRevision)
            } catch (original: MiHealthImportRunException) {
                val probeDiagnostic = original.diagnostic?.let { attachPendingProbe(it, plan, cloud) }
                val e = if (probeDiagnostic == original.diagnostic) original else MiHealthImportRunException(
                    original.reason, original.verifiedRawCount, original.affectedCount, original.details,
                    original, probeDiagnostic)
                // Source rejections occur after valid raw rows were processed.
                // Keep the incomplete result, but do not lose its read-only
                // daily-report diagnostic when all mapped RHR rows are verified.
                val verifiedRows = fullyVerifiedRhrRowsForDisplay(plan, e.diagnostic)
                if (verifiedRows.isEmpty()) throw e
                val enrichedDiagnostic = try {
                    val display = checkMiRhrDisplayReports(cloud, verifiedRows)
                    e.diagnostic?.copy(
                        rhrDailyMatchedDays = display.matchedDays,
                        rhrDailyMissingDays = display.missingDays,
                        rhrDailyConflictingDays = display.conflictingDays,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw MiHealthImportRunException(e.reason, e.verifiedRawCount, e.affectedCount,
                        listOf(e.details, "RHR_DAILY_CHECK_FAILED").filter { it.isNotBlank() }.joinToString(","),
                        e, e.diagnostic)
                }
                throw MiHealthImportRunException(e.reason, e.verifiedRawCount, e.affectedCount,
                    e.details, e, enrichedDiagnostic)
            }
            diagnostic = result.diagnostic
            detail = L10n.textFmt(L10n.importRunDetail, result.verifiedRawCount,
                result.newlyUploadedRawCount, result.alreadyPresentRawCount, result.verifiedAggregateCount) +
                result.warnings.map { L10n.importWarning(it) }.distinct()
                    .joinToString(separator = "\n", prefix = if (result.warnings.isEmpty()) "" else "\n")
            result.diagnostic?.excludedSourcePoints?.takeIf { it > 0 }?.let {
                detail += "\n" + L10n.textFmt(L10n.importExcludedPoints, result.verifiedRawCount, it)
            }
            result.diagnostic?.excludedHrvSamples?.takeIf { it > 0 }?.let {
                detail += "\n" + L10n.textFmt(L10n.importHrvExcludedSamples, result.verifiedRawCount, it)
            }
            result.diagnostic?.verifiedOwnedRevisionRows?.takeIf { it > 0 }?.let {
                detail += "\n" + L10n.textFmt(when (metric) {
                    HealthInboundMetric.HRV -> L10n.importOwnedHrvRevision
                    HealthInboundMetric.RESTING_HEART_RATE -> L10n.importOwnedRhrRevision
                    else -> L10n.importOwnedHeartRateRevision
                }, it)
            }
            if (metric == HealthInboundMetric.RESTING_HEART_RATE) {
                val verifiedRows = fullyVerifiedRhrRowsForDisplay(plan, diagnostic)
                try {
                    val display = checkMiRhrDisplayReports(cloud, verifiedRows)
                    diagnostic = diagnostic?.copy(
                        rhrDailyMatchedDays = display.matchedDays,
                        rhrDailyMissingDays = display.missingDays,
                        rhrDailyConflictingDays = display.conflictingDays,
                    )
                    detail += "\n" + L10n.textFmt(L10n.rhrDisplayCheckDetail,
                        display.matchedDays, display.missingDays, display.conflictingDays)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // This extra GET is a display-path diagnostic; a failure
                    // must not turn already verified raw writes into failures.
                    detail += "\n" + L10n.importWarning("RHR_DAILY_CHECK_FAILED")
                }
            }
            result.verifiedRawCount
        }
    }

    private suspend fun sleepRows(from: String, to: String): List<RawFitnessEntry> {
        val expandedFrom = SyncWindow.parse(from, to).expandedSleepStart
        return listOf("sleep", "watch_night_sleep", "watch_daytime_sleep").flatMap { key ->
            rawRows(key, expandedFrom, to)
        }
    }

    suspend fun syncRestingHeartRate(from: String, to: String) {
        runMetric("restingHeartRate") {
            val window = SyncWindow.parse(from, to)
            val samples = HealthDataNormalizer.normalizeHeartRate(
                MiFitnessParsers.parseRestingHeartRateSamples(rawRows("resting_heart_rate", from, to).asReversed()),
            ).filter { window.contains(it.timestamp) }
            if (samples.isNotEmpty()) healthWriter.writeRestingHeartRate(samples)
            samples.size
        }
    }

    suspend fun syncHeartRate(from: String, to: String) {
        runMetric("heartRate") {
            val samples = heartRateSamples(from, to)
            if (samples.isNotEmpty()) healthWriter.writeHeartRate(samples)
            samples.size
        }
    }

    private suspend fun heartRateSamples(from: String, to: String): List<com.bettermifitness.sync.data.api.HeartRateSample> {
        val window = SyncWindow.parse(from, to)
        // The HC client ID covers a five-minute bucket. Re-read its complete start
        // rather than replacing an older full bucket with a truncated rolling window.
        val alignedStart = HealthRecordIds.heartRateWindowStart(window.from)
        // Xiaomi's by-time heart-rate endpoint can drop the entire source bucket
        // containing start_time. A one-day overlap is backed by the public
        // account comparison in Jenwein/mifit notes/08 (2026-10-14); trim after parsing.
        val queryFrom = Instant.fromEpochSeconds(alignedStart - 86_400).toString()
        return HealthDataNormalizer.normalizeHeartRate(MiFitnessParsers.parseHeartRateSamples(
            rawRows("heart_rate", queryFrom, to).asReversed() + rawRows("single_heart_rate", queryFrom, to).asReversed(),
        )).filter { it.timestamp >= alignedStart && it.timestamp < window.to }
    }

    suspend fun syncSleep(from: String, to: String) {
        runMetric("sleep") {
            val window = SyncWindow.parse(from, to)
            val sessions = HealthDataNormalizer.normalizeSleep(
                MiFitnessParsers.parseSleepSessions(sleepRows(from, to)),
            ).filter { window.overlaps(it.startTime, it.endTime) }
            if (sessions.isNotEmpty()) healthWriter.writeSleep(sessions)
            sessions.size
        }
    }

    suspend fun syncHrv(from: String, to: String) {
        runMetric("hrv") {
            val window = SyncWindow.parse(from, to)
            val samples = HealthDataNormalizer.normalizeHrv(
                MiFitnessParsers.parseHrvSamples(sleepRows(from, to)),
            ).filter { window.contains(it.timestamp) }
            // The platform writer must require the matching algorithm; no SDNN/RMSSD guessing.
            if (samples.isNotEmpty()) healthWriter.writeHrv(samples)
            samples.size
        }
    }

    private suspend fun overlapWorkouts(from: String, to: String): List<WorkoutSession> {
        if (!correctWorkoutOverlap) return emptyList()
        workoutCompletion?.await()
        if (syncProgress.value.workouts is SyncState.Error) {
            throw IllegalStateException(L10n.text(L10n.syncCounterDependencyFailed))
        }
        return parsedWorkouts(from, to)
    }

    private fun hourQueryFrom(from: String, to: String): String =
        Instant.fromEpochSeconds((SyncWindow.parse(from, to).from / 3600) * 3600).toString()

    private suspend fun parsedWorkouts(from: String, to: String): List<WorkoutSession> =
        workoutCache.get("$from|$to") {
            val window = SyncWindow.parse(from, to)
            HealthDataNormalizer.normalizeWorkouts(MiFitnessParsers.parseWorkouts(
                fetchSemaphore.withPermit { api.getSportRecordsByTime(from, to) },
            )).filter { window.overlaps(it.startTime, it.endTime) }
        }

    suspend fun syncSteps(from: String, to: String) {
        runMetric("steps") {
            val records = HealthDataNormalizer.normalizeSteps(
                MiFitnessParsers.parseHourlySteps(rawRows("steps", hourQueryFrom(from, to), to)),
            )
            val adjusted = HealthDataNormalizer.subtractWorkoutSteps(records, overlapWorkouts(from, to))
            if (adjusted.isNotEmpty()) healthWriter.writeSteps(adjusted)
            adjusted.size
        }
    }

    suspend fun syncDistance(from: String, to: String) {
        runMetric("distance") {
            val records = HealthDataNormalizer.normalizeDistance(
                MiFitnessParsers.parseHourlyDistanceFromSteps(rawRows("steps", hourQueryFrom(from, to), to)),
            )
            val adjusted = HealthDataNormalizer.subtractWorkoutDistance(records, overlapWorkouts(from, to))
            if (adjusted.isNotEmpty()) healthWriter.writeDistance(adjusted)
            adjusted.size
        }
    }

    private suspend fun syncActiveCalories(from: String, to: String) {
        runMetric("activeCalories") {
            val records = HealthDataNormalizer.normalizeActiveCalories(
                MiFitnessParsers.parseHourlyActiveCalories(rawRows("calories", hourQueryFrom(from, to), to)),
            )
            val adjusted = HealthDataNormalizer.subtractWorkoutCalories(records, overlapWorkouts(from, to))
            if (adjusted.isNotEmpty()) healthWriter.writeActiveCalories(adjusted)
            adjusted.size
        }
    }

    suspend fun syncSpO2(from: String, to: String) {
        runMetric("spo2") {
            val window = SyncWindow.parse(from, to)
            val samples = HealthDataNormalizer.normalizeSpO2(MiFitnessParsers.parseSpO2Samples(
                rawRows("spo2", from, to) + rawRows("single_spo2", from, to),
            )).filter { window.contains(it.timestamp) }
            if (samples.isNotEmpty()) healthWriter.writeSpO2(samples)
            samples.size
        }
    }

    private suspend fun syncWeightBidirectional(allowBodyImport: Boolean = false) {
        runMetric("weight", zeroMeansNoData = false) {
            val mi = getMiLatestWeight()
            val hc = try {
                healthWriter.readLatestWeight()
            } catch (e: HealthWeightReadUnavailableException) {
                if (mi != null) writeBodyToHealth(mi)
                throw e // A blocked reverse read is not evidence that Health Connect is empty.
            }
            val plan = planBodySync(mi, hc, allowBodyImport)
            var count = 0
            plan.toHealth?.let { count += writeBodyToHealth(it) }
            plan.toMi?.let { count += uploadBodyAndVerify(it, allowBodyImport) }
            if (mi == null && hc == null) throw NoMetricDataException()
            count
        }
    }

    suspend fun syncWeight(from: String, to: String) { syncWeightBidirectional() }

    private fun accountKey(): String = currentUserId()
        ?: throw IllegalStateException("No active Xiaomi account")

    private suspend fun writeBodyToHealth(measurement: WeightMeasurement): Int {
        val account = accountKey()
        val fingerprint = bodyExportFingerprint(measurement)
        if (bodyLedger.lastExport(account) == fingerprint) return 0
        healthWriter.writeWeight(listOf(measurement))
        bodyLedger.noteExport(account, fingerprint)
        return 1
    }

    private suspend fun uploadBodyAndVerify(measurement: WeightMeasurement, includeBody: Boolean): Int {
        val account = accountKey()
        val fingerprint = HealthRecordIds.version(buildMiWeightUploadValue(measurement, includeBody).toString()).toString()
        val previous = bodyLedger.attempt(account, fingerprint)
        if (previous != null) {
            val missing = missingMiWeightUploadFields(measurement, readBackMiWeight(measurement), includeBody)
            if (missing.isEmpty()) {
                bodyLedger.noteAttempt(account, fingerprint, "verified")
                return 0
            }
            throw BodyImportVerificationException(L10n.textFmt(L10n.bodyImportNotVerified, missing.joinToString()))
        }
        // Journal before POST: after a timeout, verify rather than blindly insert again.
        bodyLedger.noteAttempt(account, fingerprint, "pending")
        val accepted = try {
            api.uploadWeight(measurement, includeBodyComposition = includeBody)
        } catch (e: MiApiException.AuthExpired) {
            bodyLedger.forgetAttempt(account, fingerprint) // Definite auth refusal can be retried after refresh.
            throw e
        }
        if (!accepted) {
            bodyLedger.noteAttempt(account, fingerprint, "rejected")
            throw BodyImportVerificationException(L10n.text(L10n.bodyImportRejected))
        }
        var missing: Set<String> = emptySet()
        repeat(3) { attempt ->
            if (attempt > 0) delay(300L * attempt)
            missing = missingMiWeightUploadFields(measurement, readBackMiWeight(measurement), includeBody)
            if (missing.isEmpty()) {
                bodyLedger.noteAttempt(account, fingerprint, "verified")
                return 1
            }
        }
        bodyLedger.noteAttempt(account, fingerprint, "unverified")
        throw BodyImportVerificationException(L10n.textFmt(L10n.bodyImportNotVerified, missing.joinToString()))
    }

    private suspend fun readBackMiWeight(expected: WeightMeasurement): WeightMeasurement? {
        val rows = collectFitnessPages(maxPages = 5) { next ->
            fetchSemaphore.withPermit {
                api.getDataByTime("weight", Instant.fromEpochSeconds(expected.timestamp - 86_400).toString(),
                    Instant.fromEpochSeconds(expected.timestamp + 2).toString(), next)
            }
        }
        val manual = rows.filter { it.sid == "xiaomiwear_app_manually" }
        // Never verify against another device's row when the server exposes source identity.
        val eligible = if (manual.isNotEmpty()) manual else rows.filter { it.sid == null }
        return HealthDataNormalizer.normalizeWeight(MiFitnessParsers.parseWeightMeasurements(eligible.asReversed().map { it.toRaw() }))
            .firstOrNull { it.timestamp == expected.timestamp && kotlin.math.abs(it.weightKg - expected.weightKg) <= 0.050001 }
    }

    private suspend fun getMiLatestWeight(): WeightMeasurement? {
        val response = fetchSemaphore.withPermit { api.getLatest("weight", limit = 20) }.checkedResult()
        val primary = HealthDataNormalizer.normalizeWeight(
            MiFitnessParsers.parseWeightMeasurements(response.dataList.asReversed().map { it.toRaw() }),
        ).maxByOrNull { it.timestamp }
        if (primary != null) return primary
        // Only a valid empty response may trigger the compatibility fallback.
        return HealthDataNormalizer.normalizeWeight(MiFitnessParsers.parseWeightMeasurements(
            rawRows("weight", "1970-01-01T00:00:00Z", Clock.System.now().toString()).asReversed(),
        )).maxByOrNull { it.timestamp }
    }

    suspend fun syncWorkouts(from: String, to: String) {
        runMetric("workouts") {
            val sessions = enrichWorkouts(parsedWorkouts(from, to), enrichmentHeartRateSamples(from, to))
            if (sessions.isNotEmpty()) healthWriter.writeWorkouts(sessions)
            sessions.size
        }
    }

    /**
     * Cloud heart rate only enriches workouts, so a failed read stays with the heart-rate metric
     * instead of also failing workouts and the step, distance and calorie metrics that wait for
     * them. Health Connect keeps a fuller stored series. An expired session still surfaces, so
     * the auth retry can heal this metric too.
     */
    private suspend fun enrichmentHeartRateSamples(
        from: String,
        to: String,
    ): List<com.bettermifitness.sync.data.api.HeartRateSample> = try {
        heartRateSamples(from, to)
    } catch (e: CancellationException) {
        throw e
    } catch (e: MiApiException.AuthExpired) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * FDS GPS/record/recover + cloud HR overlap. Failures leave partial detail so summary still syncs.
     * One shared FDS client per sync (engine/TLS setup once, not per workout).
     */
    private suspend fun enrichWorkouts(
        sessions: List<WorkoutSession>,
        hrSamples: List<com.bettermifitness.sync.data.api.HeartRateSample>,
    ): List<WorkoutSession> {
        if (sessions.isEmpty()) return sessions
        val fds = com.mifitness.miclient.fds.FdsClient(api.dataClient())
        try {
            return sessions.map { session ->
                enrichOneWorkout(session, hrSamples, fds)
            }
        } finally {
            fds.close()
        }
    }

    private suspend fun enrichOneWorkout(
        session: WorkoutSession,
        hrSamples: List<com.bettermifitness.sync.data.api.HeartRateSample>,
        fds: com.mifitness.miclient.fds.FdsClient,
    ): WorkoutSession {
        var out = session
        // Cloud HR overlapping the workout window
        val fromCloud = MiFitnessParsers.heartRateInWindow(
            hrSamples,
            session.startTime,
            session.endTime,
        )
        if (fromCloud.size > out.heartRateSeries.size) {
            out = out.copy(heartRateSeries = fromCloud)
        }
        // FDS GPS + record + recover
        if (!session.gpsDeviceSid.isNullOrBlank()) {
            out = try {
                api.enrichWorkoutDetails(out, fds, closeFds = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                out
            }
        }
        // Prefer denser HR after FDS
        if (fromCloud.size > out.heartRateSeries.size) {
            out = out.copy(heartRateSeries = fromCloud)
        }
        return out
    }

    suspend fun syncBloodPressure(from: String, to: String) {
        runMetric("bloodPressure") {
            val window = SyncWindow.parse(from, to)
            val fitness = rawRows("blood_pressure", from, to)
            val medical = try { fetchAllMedicalByTime("mc_blood_pressure", from, to).map { it.toRaw() } }
                catch (e: MiApiException.Server) {
                    if (e.httpOrBusinessCode == 404) emptyList() else throw e
                }
            val samples = HealthDataNormalizer.normalizeBloodPressure(
                MiFitnessParsers.parseBloodPressureSamples(fitness + medical),
            ).filter { window.contains(it.timestamp) }
            if (samples.isNotEmpty()) healthWriter.writeBloodPressure(samples)
            samples.size
        }
    }

    suspend fun syncTemperature(from: String, to: String) {
        runMetric("temperature") {
            val window = SyncWindow.parse(from, to)
            val samples = HealthDataNormalizer.normalizeTemperature(MiFitnessParsers.parseTemperatureSamples(
                rawRows("temperature_characteristic", from, to) + rawRows("single_temperature", from, to),
            )).filter { window.contains(it.timestamp) }
            if (samples.isNotEmpty()) healthWriter.writeTemperature(samples)
            samples.size
        }
    }

    suspend fun syncVo2Max(from: String, to: String) {
        runMetric("vo2Max") {
            val window = SyncWindow.parse(from, to)
            val samples = HealthDataNormalizer.normalizeVo2Max(
                MiFitnessParsers.parseVo2MaxSamples(rawRows("vo2_max", from, to)),
            ).filter { window.contains(it.timestamp) }
            if (samples.isNotEmpty()) healthWriter.writeVo2Max(samples)
            samples.size
        }
    }

    override fun resetProgress() {
        syncProgress.value = SyncProgress()
    }

    private suspend fun fetchAllByTime(key: String, from: String, to: String, maxPages: Int = 60): List<HeartRateEntry> =
        rowCache.get("fitness|$key|$from|$to") {
            collectFitnessPages(maxPages) { next -> fetchSemaphore.withPermit { api.getDataByTime(key, from, to, next) } }
                .distinctBy { listOf(it.key, it.sid, it.time, it.value) }
        }

    private suspend fun fetchAllMedicalByTime(key: String, from: String, to: String, maxPages: Int = 60): List<HeartRateEntry> =
        rowCache.get("medical|$key|$from|$to") {
            collectFitnessPages(maxPages) { next -> fetchSemaphore.withPermit { api.getMedicalDataByTime(key, from, to, next) } }
                .distinctBy { listOf(it.key, it.sid, it.time, it.value) }
        }

    private class NoMetricDataException : IllegalStateException()

    private suspend fun runMetric(
        metric: String,
        zeroMeansNoData: Boolean = true,
        successDetail: () -> String? = { null },
        successDiagnostic: () -> MiHealthImportDiagnostic? = { null },
        block: suspend () -> Int,
    ) {
        setState(metric, SyncState.InProgress)
        try {
            val count = executeWithAuthRetry(block)
            val excluded = (successDiagnostic()?.excludedSourcePoints ?: 0) + (successDiagnostic()?.excludedHrvSamples ?: 0)
            setState(metric, if (count == 0 && zeroMeansNoData && excluded == 0)
                SyncState.NoData(L10n.text(L10n.syncNoUsableData), successDetail(), successDiagnostic())
            else SyncState.Success(count, successDetail(), successDiagnostic()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoMetricDataException) {
            setState(metric, SyncState.NoData(L10n.text(L10n.syncNoUsableData)))
        } catch (e: Exception) {
            recordFailure(e)
            setState(metric, SyncState.Error(friendlyMessage(e), (e as? MiHealthImportRunException)?.diagnostic))
        }
    }

    /**
     * Runs [block], healing an expired session once per sync run.
     *
     * Every metric that observes [MiApiException.AuthExpired] joins the same shared
     * refresh: on success each of them retries once against the renewed session instead
     * of only the first failure recovering while the rest report auth errors.
     */
    private suspend fun <T> executeWithAuthRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: MiApiException.AuthExpired) {
            when (val refresh = sharedRefresh.refreshOnce()) {
                SessionRefreshResult.Success -> {
                    stateMutex.withLock {
                        lastRefreshUserMessage = null
                        lastRefreshRetryable = false
                    }
                    return block()
                }
                else -> {
                    noteRefreshOutcome(refresh)
                    throw e
                }
            }
        }
    }

    private suspend fun noteRefreshOutcome(refresh: SessionRefreshResult) {
        val message = if (refresh is SessionRefreshResult.NeedsVerification) {
            // Include verification URL so UI can open Xiaomi WebView directly,
            // matching Mi Fitness behavior where 70016 shows “Verify” link.
            val url = refresh.notificationUrl?.takeIf { it.isNotBlank() }
            if (url != null) "${refresh.reason} — Verify at: $url" else refresh.userMessage
        } else {
            refresh.userMessage
        }
        val retryable = refresh is SessionRefreshResult.TransientFailure
        stateMutex.withLock {
            lastRefreshUserMessage = message
            lastRefreshRetryable = retryable
        }
    }

    private suspend fun recordFailure(e: Exception) {
        stateMutex.withLock {
            when (e) {
                is MiApiException.AuthExpired -> {
                    sawAuthFailure = true
                    retryableFailures += lastRefreshRetryable
                }
                is MiApiException -> retryableFailures += e.isRetryable
                is BodyImportVerificationException, is BodySyncConflictException,
                is HealthWeightReadUnavailableException, is HealthWriteIncompleteException,
                is HealthInboundReadUnavailableException, is MiHealthImportRunException,
                is UnsupportedHrvAlgorithmException, is SecurityException -> retryableFailures += false
                else -> retryableFailures += true
            }
        }
    }

    private fun friendlyMessage(e: Exception): String {
        return when (e) {
            is MiHealthImportRunException -> e.userMessage()
            is UnsupportedHrvAlgorithmException -> L10n.textFmt(L10n.hrvAlgorithmUnconfirmed,
                e.unsupportedCount, e.targetAlgorithm.name, e.unknownCount)
            is HealthWriteIncompleteException -> L10n.textFmt(L10n.healthPartialWrite,
                e.writtenRecordCount, e.skippedReasons.joinToString("; "))
            is MiApiException.AuthExpired ->
                lastRefreshUserMessage
                    ?: e.message?.takeIf { it.isNotBlank() }
                    ?: "Session expired — sign in again"
            is MiApiException.Network ->
                e.message?.takeIf { it.isNotBlank() } ?: "Network error"
            is MiApiException.RateLimited ->
                "Mi cloud rate limited — try again later"
            is MiApiException.Server ->
                e.message?.takeIf { it.isNotBlank() } ?: "Mi cloud error (${e.httpOrBusinessCode})"
            is MiApiException.Unexpected ->
                e.message?.takeIf { it.isNotBlank() } ?: "Unexpected Mi API error"
            else -> e.message ?: e::class.simpleName ?: "Unknown error"
        }
    }

    private suspend fun setState(metric: String, state: SyncState) {
        stateMutex.withLock {
            syncProgress.value = syncProgress.value.withMetric(metric, state)
        }
    }

    companion object {
        /** Max concurrent Mi cloud requests during parallel sync (rate-limit safety). */
        const val MAX_CONCURRENT_FETCHES = 3
        private const val ZEPP_SOURCE_PACKAGE = "com.huami.watch.hmwatchmanager"
    }
}
