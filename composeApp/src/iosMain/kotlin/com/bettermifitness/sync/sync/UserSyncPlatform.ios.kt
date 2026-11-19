package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.data.preferences.SyncPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.koin.mp.KoinPlatform

actual val supportsUserSyncReplacement: Boolean = false

actual suspend fun stopUserSync(coordinator: SyncCoordinator): Boolean = coordinator.stopByUser()
actual fun observeRecoverableUserSync(): Flow<UserSyncRequest?> = flowOf(null)
actual fun observeSyncTaskHistory(): Flow<List<SyncTaskSummary>> = flow {
    val koin = KoinPlatform.getKoin()
    val credentials = koin.get<CredentialsStore>()
    emitAll(combine(koin.get<SyncPreferences>().completedTasks, credentials.token) { summaries, token ->
        if (token.isNullOrEmpty()) emptyList()
        else credentials.accountGeneration().let { generation -> summaries.filter { it.generation == generation } }
    })
}
actual suspend fun recoverUserSync(coordinator: SyncCoordinator, request: UserSyncRequest): SyncOutcome = SyncOutcome.Skipped

internal actual suspend fun dispatchUserSync(coordinator: SyncCoordinator, request: UserSyncRequest, replace: Boolean): SyncOutcome =
    if (replace) SyncOutcome.Skipped else coordinator.run(
        requestHealthPermissions = true,
        resetProgress = true,
        userInitiated = true,
        request = request,
        onTaskFinished = ::recordIosSyncTask,
    )

internal suspend fun recordIosSyncTask(summary: SyncTaskSummary) {
    try {
        KoinPlatform.getKoin().get<SyncPreferences>().recordCompletedTask(summary)
    } catch (_: Exception) {
        println("Sync task history unavailable")
    }
}
