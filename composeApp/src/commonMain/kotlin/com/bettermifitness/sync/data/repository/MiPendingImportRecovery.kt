package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import kotlinx.coroutines.CancellationException

/** Explicit foreground recovery. Each row is durably reserved and verified before advancing. */
class MiPendingImportRecovery(private val cloud: MiHealthImportCloud, private val journal: HealthImportJournal,
    private val readbackPause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) {
    suspend fun recover(account: String, plan: MiHealthImportPlan, userRequested: Boolean,
        maxRows: Int = 300, onProgress: (MiHealthImportDiagnostic) -> Unit = {},
        automatic: Boolean = false): MiHealthImportDiagnostic {
        require(maxRows in 1..300)
        val pending = journal.pendingIdentities(account, plan.rawRows.map { it.identity.stableKey }.toSet())
        val targets = plan.rawRows.filter { it.identity.stableKey in pending }.distinctBy { it.identity }
            .sortedWith(compareBy({ it.identity.time }, { it.identity.stableKey }))
        val end = targets.maxOfOrNull { it.identity.time }?.plus(1)
        var history: MiHealthImportHistoryRead? = null
        var wrote = false
        var historyPages = 0
        // Share only the pre-upload history within this action. Every target still gets fresh
        // bounded reads immediately before its reservation. Never cache a post-upload read.
        val observedCloud = object : MiHealthImportCloud by cloud {
            override suspend fun readRawRowsWithoutStart(key: String, untilEpochSeconds: Long, maxPages: Int): MiHealthImportHistoryRead {
                if (wrote || end == null) return cloud.readRawRowsWithoutStart(key, untilEpochSeconds, maxPages)
                val result = history ?: cloud.readRawRowsWithoutStart(key, end, maxPages).also {
                    history = it; historyPages += it.pagesRead
                }
                return result.copy(rows = result.rows.filter { it.time < untilEpochSeconds })
            }
            override suspend fun uploadRows(rows: List<MiHealthImportRow>, experimentalEnabled: Boolean): MiHealthImportUploadResult {
                wrote = true
                return cloud.uploadRows(rows, experimentalEnabled)
            }
        }
        val results = mutableListOf<MiHealthImportDiagnostic>()
        fun combined() = combine(results, targets.size).let { result -> result.copy(
            pendingRecovery = result.pendingRecovery?.copy(historyPagesRead = historyPages,
                consumedThisRun = results.any { it.pendingRecovery?.consumedThisRun == true },
                legacyWireMetadataUnknown = results.any { it.pendingRecovery?.legacyWireMetadataUnknown == true },
                nativeReadbackPages = results.sumOf { it.pendingRecovery?.nativeReadbackPages ?: 0 })) }
        // An empty plan still returns a meaningful no-pending/exclusion report.
        val selected = targets.take(maxRows).map { it.identity.stableKey }.ifEmpty { listOf(null) }
        for (target in selected) {
            wrote = false
            val result = try {
                MiPendingSpO2Recovery(observedCloud, journal, readbackPause).recoverOne(
                    account, plan, userRequested, automatic = automatic, targetIdentity = target)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (results.isEmpty()) throw e
                return combined().copy(stage = MiHealthImportStage.PRECHECK,
                    failureReason = MiHealthImportFailureReason.UNVERIFIED.name,
                    pendingRecovery = results.last().pendingRecovery?.copy(status = "READ_FAILED", candidateRows = targets.size,
                        historyPagesRead = historyPages))
            }
            results += result
            onProgress(combined())
            // A known pre-existing conflict is local to that identity. Keep it
            // unresolved while independently eligible targets can still proceed.
            val isolated = result.attemptedRawRows == 0 && result.pendingRecovery?.status in setOf(
                "CONFLICT", "TOMBSTONE_PRESENT", "OTHER_SOURCE_PRESENT", "SOURCE_COLLISION", "SOURCE_CHANGED")
            if (!isolated && (result.failureReason != null || result.pendingRecovery?.status !in setOf("VERIFIED", "ALREADY_PRESENT")))
                return combined()
        }
        return combined()
    }

    private fun combine(results: List<MiHealthImportDiagnostic>, candidates: Int): MiHealthImportDiagnostic {
        val last = results.firstOrNull { it.failureReason != null } ?: if (
            results.size > 1 && results.last().pendingRecovery?.status in setOf("NO_PENDING", "NO_PENDING_WITH_EXCLUSIONS"))
            results[results.lastIndex - 1] else results.last()
        val verified = results.sumOf { it.verifiedRawRows }
        val remaining = (candidates - verified).coerceAtLeast(0)
        return last.copy(testMode = false, rawBatchLimit = 1,
            plannedRawRows = candidates, attemptedRawRows = results.sumOf { it.attemptedRawRows },
            verifiedRawRows = verified, alreadyPresentRawRows = results.sumOf { it.alreadyPresentRawRows },
            newlyVerifiedRawRows = results.sumOf { it.newlyVerifiedRawRows },
            notAttemptedRawRows = (candidates - results.sumOf { it.attemptedRawRows + it.alreadyPresentRawRows }).coerceAtLeast(0),
            blockedConflictRows = results.sumOf { it.blockedConflictRows },
            blockedAmbiguousRows = results.sumOf { it.blockedAmbiguousRows },
            readbackMissingRows = results.sumOf { it.readbackMissingRows },
            readbackMismatchedRows = results.sumOf { it.readbackMismatchedRows },
            readbackAmbiguousRows = results.sumOf { it.readbackAmbiguousRows },
            readbackUnqueriedRows = results.sumOf { it.readbackUnqueriedRows },
            affectedRows = remaining, blockedPendingRows = (remaining - results.sumOf {
                it.readbackMissingRows + it.readbackUnqueriedRows + it.readbackMismatchedRows +
                    it.readbackAmbiguousRows + it.blockedConflictRows + it.blockedAmbiguousRows
            }).coerceAtLeast(0),
            failureReason = last.failureReason ?: if (remaining > 0) MiHealthImportFailureReason.PENDING.name else null,
            pendingRecovery = last.pendingRecovery?.copy(candidateRows = candidates))
    }
}
