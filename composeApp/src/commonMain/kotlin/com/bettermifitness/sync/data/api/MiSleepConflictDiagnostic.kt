package com.bettermifitness.sync.data.api

/** Fixed classifications only: no source IDs, times, values or fingerprints in diagnostic text. */
enum class MiOwnedSleepRevisionStatus {
    ELIGIBLE, UNSUPPORTED_TARGET, SOURCE_NOT_UNIQUE, SOURCE_TIMEZONE_UNCERTAIN,
    MISSING, TOMBSTONE_PRESENT, OTHER_SOURCE_PRESENT, AMBIGUOUS,
    NOT_OWNED, CLOUD_BASELINE_CHANGED, TIMEZONE_CHANGED, INVALID_SLEEP_BASELINE,
    HRV_PRESENT, ALREADY_MATCHED, PENDING, SOURCE_REJECTED, SOURCE_COLLISION, READ_FAILED, READ_LIMIT,
}

enum class MiSleepConflictOwnership {
    OWNED_BASELINE_UNCHANGED, OWNED_BASELINE_CHANGED,
    OBSERVED_BASELINE_UNCHANGED, OBSERVED_BASELINE_CHANGED,
    LEGACY_BASELINE_UNCHANGED, LEGACY_BASELINE_CHANGED,
    NO_HISTORY, CLOUD_TARGET_UNCERTAIN,
}

data class MiSleepConflictDiagnostic(
    val revisionRequested: Boolean = false,
    val inspectedRows: Int = 0,
    val matchedRows: Int = 0,
    val mismatchedRows: Int = 0,
    val ambiguousRows: Int = 0,
    val missingRows: Int = 0,
    val unqueriedRows: Int = 0,
    /** Overlaps with the cloud classification; not an additional target count. */
    val pendingRows: Int = 0,
    val eligibleRevisionRows: Int = 0,
    val attemptedRevisionRows: Int = 0,
    val verifiedRevisionRows: Int = 0,
    val readFailureGroups: Int = 0,
    val readLimitedGroups: Int = 0,
    val ownershipCounts: Map<MiSleepConflictOwnership, Int> = emptyMap(),
    val eligibilityCounts: Map<MiOwnedSleepRevisionStatus, Int> = emptyMap(),
    val mismatchFields: Map<String, Int> = emptyMap(),
)
