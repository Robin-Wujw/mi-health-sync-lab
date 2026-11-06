package com.bettermifitness.sync.health

/**
 * A batch can write its permitted records while still reporting the records it
 * could not write. Callers must not treat this exception as a successful sync or
 * advance their success checkpoint. Retrying uses the same logical record IDs.
 */
class HealthWriteIncompleteException(
    val writtenRecordCount: Int,
    val skippedReasons: List<String>,
    cause: Throwable? = null,
) : IllegalStateException(
    "Health Connect accepted $writtenRecordCount record(s), but synchronization is incomplete. " +
        skippedReasons.joinToString(" "),
    cause,
)

/** Monotonic even after clock rollback, same-millisecond retries and app restart. */
internal fun nextHealthRecordVersion(lastVersion: Long, nowMillis: Long): Long {
    check(lastVersion >= 0L) { "The stored Health Connect revision is invalid." }
    check(lastVersion < Long.MAX_VALUE) { "The Health Connect revision is exhausted." }
    return maxOf(1L, nowMillis, lastVersion + 1L)
}

internal data class HealthWriteCandidate<T>(
    val record: T,
    val label: String,
    val requiredPermissions: Set<String>,
)

internal data class HealthWritePlan<T>(
    val writableRecords: List<T>,
    val skippedReasons: List<String>,
)

/** A missing permission affects only the records that actually need it. */
internal fun <T> planHealthWrites(
    candidates: List<HealthWriteCandidate<T>>,
    grantedPermissions: Set<String>,
    missingAccessMessage: (label: String, count: Int) -> String = { label, count ->
        "$label: write access is required in Health Connect ($count record(s) skipped)."
    },
): HealthWritePlan<T> {
    val writable = mutableListOf<T>()
    val skipped = linkedMapOf<String, Int>()
    for (candidate in candidates) {
        val missing = candidate.requiredPermissions - grantedPermissions
        if (missing.isEmpty()) {
            writable += candidate.record
        } else {
            skipped[candidate.label] = (skipped[candidate.label] ?: 0) + 1
        }
    }
    return HealthWritePlan(
        writableRecords = writable,
        skippedReasons = skipped.map { (label, count) -> missingAccessMessage(label, count) },
    )
}
