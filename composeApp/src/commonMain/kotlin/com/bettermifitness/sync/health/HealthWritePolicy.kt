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
internal fun nextHealthRecordRevision(lastRevision: Long, nowMillis: Long): Long {
    check(lastRevision >= 0L) { "The stored Health Connect revision is invalid." }
    check(lastRevision < Long.MAX_VALUE) { "The Health Connect revision is exhausted." }
    return maxOf(1L, nowMillis, lastRevision + 1L)
}

/*
 * Layout of every clientRecordVersion this app writes to Health Connect:
 *
 *     [ revision from the persisted clock ][ 20-bit content fingerprint ]
 *
 * Health Connect only applies an upsert whose version is higher than the stored one. The
 * revision guarantees that, so a corrected lower value still wins. The fingerprint lets a
 * later sync see from the stored version alone that a record is already up to date, without
 * comparing floating-point fields that may not read back exactly as they were written.
 */
private const val CONTENT_FINGERPRINT_BITS = 20
private const val CONTENT_FINGERPRINT_MASK = (1L shl CONTENT_FINGERPRINT_BITS) - 1L
private const val MAX_STAMPED_REVISION = Long.MAX_VALUE ushr CONTENT_FINGERPRINT_BITS

/**
 * healthlab.18 and earlier wrote the bare millisecond clock (about 1.8e12). Shifted right,
 * those values stay far below this floor, so they are never mistaken for a stamped version.
 */
private const val MIN_STAMPED_REVISION = 1L shl 30

internal fun stampedHealthRecordVersion(revision: Long, contentFingerprint: Long): Long {
    check(revision in 1L..MAX_STAMPED_REVISION) { "The Health Connect revision is exhausted." }
    return (revision shl CONTENT_FINGERPRINT_BITS) or (contentFingerprint and CONTENT_FINGERPRINT_MASK)
}

/** Null when [version] carries no fingerprint, so the stored content is unknown. */
internal fun stampedContentFingerprint(version: Long): Long? =
    if (version ushr CONTENT_FINGERPRINT_BITS >= MIN_STAMPED_REVISION) version and CONTENT_FINGERPRINT_MASK else null

/** [detailCount] ranks copies of one record by how much of it they carry, such as samples or route points. */
internal data class HealthRecordState(val version: Long, val detailCount: Int)

/**
 * Whether [candidate] should replace what Health Connect holds under the same client record ID.
 *
 * A fuller copy always wins and a less complete one never does, because a failed detail download
 * or a truncated read must not erase what an earlier sync stored. Copies with equal detail are
 * written only when their content differs. A version without a fingerprint is written once, which
 * stamps it for later syncs.
 */
internal fun shouldWriteHealthRecord(candidate: HealthRecordState, stored: HealthRecordState?): Boolean {
    if (stored == null) return true
    if (candidate.detailCount != stored.detailCount) return candidate.detailCount > stored.detailCount
    val storedFingerprint = stampedContentFingerprint(stored.version) ?: return true
    return storedFingerprint != stampedContentFingerprint(candidate.version)
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
