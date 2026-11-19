package com.bettermifitness.sync.sync

actual suspend fun runUserSync(coordinator: SyncCoordinator): SyncOutcome = coordinator.run(
    requestHealthPermissions = true,
    resetProgress = true,
    userInitiated = true,
)
