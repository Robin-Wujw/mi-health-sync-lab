package com.bettermifitness.sync.zepp

import kotlinx.coroutines.withTimeoutOrNull

/** Deadline cancellation preserves normal write reservations and is distinct from parent cancellation. */
class ZeppSyncTimeoutException : Exception("Zepp synchronization exceeded its time budget")

internal suspend fun <T : Any> runBoundedZeppSync(
    timeoutMillis: Long = 5 * 60_000L,
    action: suspend () -> T,
): T = withTimeoutOrNull(timeoutMillis) { action() } ?: throw ZeppSyncTimeoutException()
