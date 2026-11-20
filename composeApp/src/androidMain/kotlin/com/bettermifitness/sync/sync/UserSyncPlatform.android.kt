package com.bettermifitness.sync.sync

import com.bettermifitness.sync.di.androidAppContext
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.i18n.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.koin.mp.KoinPlatform

actual val supportsUserSyncReplacement: Boolean = true

internal actual suspend fun dispatchUserSync(coordinator: SyncCoordinator, request: UserSyncRequest, replace: Boolean): SyncOutcome {
    if (coordinator.isStopping.value) return SyncOutcome.AlreadyRunning
    return if (replace) replaceRequest(coordinator, request, SyncTaskSource.MANUAL) else startPreparedUserSync(coordinator, request)
}

actual suspend fun recoverUserSync(coordinator: SyncCoordinator, request: UserSyncRequest): SyncOutcome =
    replaceRequest(coordinator, request, SyncTaskSource.RECOVERED)

private suspend fun replaceRequest(coordinator: SyncCoordinator, request: UserSyncRequest, source: SyncTaskSource): SyncOutcome = try {
    val journal = journal()
    coordinator.replaceUserSync(
        request,
        markPreviousStopping = { UserSyncService.stopPreviousService() },
        persistReplacement = { journal.stageReplacement(request) },
    ) {
        UserSyncService.awaitPreviousServiceExit()
        startPreparedUserSync(coordinator, request, journal, source)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    SyncOutcome.Failed(L10n.text(L10n.syncFailed), retryable = true)
}

private suspend fun startPreparedUserSync(
    coordinator: SyncCoordinator,
    request: UserSyncRequest,
    queuedIn: UserSyncJournal? = null,
    source: SyncTaskSource = SyncTaskSource.MANUAL,
): SyncOutcome {
    coordinator.prepareUserSync(request)?.let { outcome ->
        if (outcome != SyncOutcome.AlreadyRunning) queuedIn?.finishQueued(request.runId, outcome)
        return outcome
    }
    return UserSyncService.startAndAwait(androidAppContext(), request, fromQueue = queuedIn != null, source = source) {
        !coordinator.isStopping.value
    }
}

actual fun observeRecoverableUserSync(): Flow<UserSyncRequest?> = flow {
    val credentials = KoinPlatform.getKoin().get<CredentialsStore>()
    emitAll(combine(journal().recoverable, credentials.token) { request, token ->
        request?.takeIf { !token.isNullOrEmpty() && it.accountGeneration == credentials.accountGeneration() }
    })
}

actual suspend fun stopUserSync(coordinator: SyncCoordinator): Boolean = coordinator.stopByUser(
    persistStop = { journal().stopEverything() },
) {
    UserSyncService.stopPreviousService()
    UserSyncService.awaitPreviousServiceExit()
}

private fun journal(): UserSyncJournal = KoinPlatform.getKoin().get()

actual fun observeSyncTaskHistory(): Flow<List<SyncTaskSummary>> = flow {
    val credentials = KoinPlatform.getKoin().get<CredentialsStore>()
    val journal = journal()
    journal.reconcileAutomatic(androidSyncProcessId)
    emitAll(combine(journal.summaries, credentials.token) { summaries, token ->
        if (token.isNullOrEmpty()) emptyList()
        else credentials.accountGeneration().let { generation -> summaries.filter { it.generation == generation } }
    })
}
