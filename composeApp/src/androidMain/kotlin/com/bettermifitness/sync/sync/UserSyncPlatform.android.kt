package com.bettermifitness.sync.sync

import com.bettermifitness.sync.di.androidAppContext

actual suspend fun runUserSync(coordinator: SyncCoordinator): SyncOutcome {
    coordinator.prepareUserSync()?.let { return it }
    return UserSyncService.startAndAwait(androidAppContext())
}
