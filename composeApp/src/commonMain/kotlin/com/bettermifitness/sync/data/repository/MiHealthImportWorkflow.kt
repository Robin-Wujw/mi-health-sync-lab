package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportDiagnostic
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportStage
import com.bettermifitness.sync.data.api.MiHistoryObservingCloud
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.health.HealthInboundMetric
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Normal sync owns reconciliation and bounded recovery; no separate button is required. */
internal class MiHealthImportWorkflow(
    cloud: MiHealthImportCloud,
    private val journal: HealthImportJournal,
    private val readbackPause: suspend (Long) -> Unit = { delay(it) },
) {
    private val cloud = MiHistoryObservingCloud(cloud)

    suspend fun run(account: String, plan: MiHealthImportPlan, testMode: Boolean = false,
        allowLegacySleepHrvEnrichment: Boolean = false,
        allowOwnedSleepRevision: Boolean = false): MiHealthImportRunResult {
        var recovery: MiHealthImportDiagnostic? = null
        val reconciliation = if (!testMode && plan.metric in setOf(
                HealthInboundMetric.HEART_RATE, HealthInboundMetric.OXYGEN_SATURATION)) {
            try {
                MiPendingSpO2Reconciler(cloud, journal).reconcile(account, plan,
                    userRequested = false, automatic = true).pendingReconcile?.takeIf { it.candidateRows > 0 }
            } catch (e: CancellationException) { throw e }
            catch (e: MiApiException.AuthExpired) { throw e }
            catch (_: Exception) { null }
        } else null
        // Only writes pending BEFORE this sync may receive the one durable recovery
        // attempt. An unconfirmed new POST is never immediately submitted again.
        if (!testMode && plan.metric in setOf(HealthInboundMetric.HEART_RATE, HealthInboundMetric.OXYGEN_SATURATION) &&
            journal.pendingIdentities(account, plan.rawRows.map { it.identity.stableKey }.toSet()).isNotEmpty()) {
            recovery = MiPendingImportRecovery(cloud, journal, readbackPause).recover(
                account, plan, userRequested = false, automatic = true)
        }
        fun attach(diagnostic: MiHealthImportDiagnostic): MiHealthImportDiagnostic {
            val recovered = recovery?.newlyVerifiedRawRows ?: 0
            val attempts = diagnostic.attemptedRawRows + (recovery?.attemptedRawRows ?: 0)
            val existing = (diagnostic.alreadyPresentRawRows - recovered).coerceAtLeast(0)
            return diagnostic.copy(pendingReconcile = reconciliation, pendingRecovery = recovery?.pendingRecovery,
                attemptedRawRows = attempts, alreadyPresentRawRows = existing,
                newlyVerifiedRawRows = diagnostic.newlyVerifiedRawRows + recovered,
                notAttemptedRawRows = (diagnostic.plannedRawRows - attempts - existing).coerceAtLeast(0))
        }
        val guarded = if (testMode) {
            val selected = plan.rawRows.maxByOrNull { it.identity.time }?.identity
            plan.rawRows.filter { it.identity == selected }
        } else plan.rawRows
        cloud.beginImportPrecheck(guarded)
        val result = try {
            MiHealthImportRunner(cloud, journal, readbackPause).run(account, plan, testMode,
                allowLegacySleepHrvEnrichment, allowOwnedSleepRevision)
        } catch (e: MiHealthImportRunException) {
            throw MiHealthImportRunException(e.reason, e.verifiedRawCount, e.affectedCount, e.details,
                e, e.diagnostic?.let(::attach))
        } catch (e: MiApiException.AuthExpired) {
            if ((recovery?.attemptedRawRows ?: 0) == 0) throw e
            throw MiHealthImportRunException(MiHealthImportFailureReason.UNVERIFIED,
                recovery?.verifiedRawRows ?: 0, plan.rawRows.size, cause = e,
                diagnostic = attach(plan.inspectionDiagnostic().copy(stage = MiHealthImportStage.PRECHECK,
                    verifiedRawRows = recovery?.verifiedRawRows ?: 0,
                    failureReason = MiHealthImportFailureReason.UNVERIFIED.name)))
        }
        val recovered = recovery?.newlyVerifiedRawRows ?: 0
        return result.copy(newlyUploadedRawCount = result.newlyUploadedRawCount + recovered,
            alreadyPresentRawCount = (result.alreadyPresentRawCount - recovered).coerceAtLeast(0),
            diagnostic = result.diagnostic?.let(::attach))
    }
}
