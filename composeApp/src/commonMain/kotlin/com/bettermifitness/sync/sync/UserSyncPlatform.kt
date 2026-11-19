package com.bettermifitness.sync.sync

/** Android transfers a prepared user run to its service; iOS retains its platform lifecycle. */
expect suspend fun runUserSync(coordinator: SyncCoordinator): SyncOutcome
