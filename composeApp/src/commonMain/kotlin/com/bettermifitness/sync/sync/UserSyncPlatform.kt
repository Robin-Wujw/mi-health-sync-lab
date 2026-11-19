package com.bettermifitness.sync.sync

import kotlinx.coroutines.flow.Flow

/** Android transfers a prepared user run to its service; iOS retains its platform lifecycle. */
expect val supportsUserSyncReplacement: Boolean
suspend fun runUserSync(coordinator: SyncCoordinator): SyncOutcome =
    dispatchUserSync(coordinator, coordinator.captureUserSyncRequest(), replace = false)

internal expect suspend fun dispatchUserSync(coordinator: SyncCoordinator, request: UserSyncRequest, replace: Boolean): SyncOutcome
expect suspend fun stopUserSync(coordinator: SyncCoordinator): Boolean
expect fun observeRecoverableUserSync(): Flow<UserSyncRequest?>
expect fun observeSyncTaskHistory(): Flow<List<SyncTaskSummary>>
expect suspend fun recoverUserSync(coordinator: SyncCoordinator, request: UserSyncRequest): SyncOutcome
