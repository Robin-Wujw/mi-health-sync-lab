package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.zepp.ZeppStream
import com.bettermifitness.sync.sync.UserSyncRequest
import kotlinx.coroutines.flow.StateFlow

data class AutomaticZeppRead(val deferEventSplit: Boolean, val requiredEvents: Set<ZeppStream>, val sourceIdentity: Long?)

/**
 * ISP: multi-metric sync orchestration (fetch → write → progress).
 * [HealthRepository] is the production implementation; tests use fakes.
 */
interface HealthSyncRunner {
    val syncProgress: StateFlow<SyncProgress>

    suspend fun syncAll(
        from: String,
        to: String,
        enabled: Set<String>,
    ): SyncRunResult

    suspend fun syncAutomaticSlice(from: String, to: String, enabled: Set<String>, zeppRead: AutomaticZeppRead?) =
        syncAll(from, to, enabled)

    suspend fun syncCheckpointedSlice(from: String, to: String, enabled: Set<String>, zeppRead: AutomaticZeppRead?,
        scope: SyncPageScope): SyncRunResult = syncAutomaticSlice(from, to, enabled, zeppRead)

    suspend fun pageCheckpointRevision(): Long = -1
    suspend fun discardPageCheckpoints(scope: SyncPageScope) = Unit
    suspend fun clearPageCheckpoints() = Unit

    suspend fun syncUserRequest(request: UserSyncRequest): SyncRunResult = with(request.selection) {
        syncAll(from.toString(), to.toString(), enabled)
    }

    fun resetProgress()
}
