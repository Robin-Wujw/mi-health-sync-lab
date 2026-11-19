package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiWorkoutImportCloud
import com.bettermifitness.sync.data.api.MiWorkoutImportMapper
import com.bettermifitness.sync.data.api.WorkoutTimedSample
import com.bettermifitness.sync.data.preferences.WorkoutImportJournal
import com.bettermifitness.sync.data.preferences.WorkoutRestoreJournal
import com.bettermifitness.sync.health.InboundWorkout
import com.bettermifitness.sync.health.WorkoutImportReport
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.SportFileTransport
import kotlinx.coroutines.CancellationException

/** The same authoritative workout reconciliation runs in foreground and background sync. */
class MiWorkoutSyncRunner(
    private val cloud: MiWorkoutImportCloud,
    private val journal: WorkoutImportJournal,
    private val transport: () -> SportFileTransport,
    private val originalHeartRate: suspend (InboundWorkout) -> List<WorkoutTimedSample>?,
    private val legacyRestoration: WorkoutRestoreJournal? = null,
) {
    suspend fun run(account: String, source: List<InboundWorkout>, maxWrites: Int = 200): WorkoutImportReport {
        require(account.isNotBlank() && maxWrites in 0..200)
        if (maxWrites == 0) return MiWorkoutImportRunner(cloud, journal).run(account, source, 0)
        val blocked = reconcileLegacyRestoration(account, source)
        val summaries = MiWorkoutImportRunner(cloud, journal).run(account, source, maxWrites, blocked)
        val files = MiWorkoutFilesRunner(cloud, journal, transport(), originalHeartRate)
            .run(account, source, blockedSources = blocked)
        return summaries.copy(verifiedFiles = files.verified, failedFiles = files.pending,
            fileFailures = files.failures, fileBodyUploads = files.bodyUploads, fileReferenceWrites = files.referenceWrites,
            routesAwaitingConsent = files.routesAwaitingConsent)
    }

    private suspend fun reconcileLegacyRestoration(account: String, source: List<InboundWorkout>): Set<String> {
        val restoration = legacyRestoration ?: return emptySet()
        val entry = restoration.load(account) ?: return emptySet()
        if (source.none { MiWorkoutImportMapper.map(it) == entry.target }) return emptySet()
        if (entry.fullCloudBackup && journal.load(account, entry.target.sourceKey)?.lastVerifiedValue == null)
            journal.rememberSummary(account, entry.target, entry.value)
        if (entry.verified || !entry.summaryAttempted && entry.fileAttempts.isEmpty()) return emptySet()
        val files = transport()
        return try {
            if (MiWorkoutRestoreRunner(cloud, journal, restoration, files).restore(account, source) == WorkoutRestoreStatus.VERIFIED)
                emptySet() else setOf(entry.target.sourceKey)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { setOf(entry.target.sourceKey) }
        finally { (files as? FdsClient)?.close() }
    }
}
