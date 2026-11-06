package com.bettermifitness.sync.data.api

/** Persisted recovery state only. It never describes a POST made by the current inspection. */
enum class MiRecoveryStoredOutcome {
    RESERVED, ACCEPTED, REJECTED, TRANSPORT_UNKNOWN, READBACK_UNVERIFIED, VERIFIED, UNKNOWN,
}

enum class MiRecoveryReadbackStatus { VERIFIED, MISSING, MISMATCH, AMBIGUOUS, UNQUERIED, UNKNOWN }

/**
 * An explicitly selected subset of an older recovery reservation's metadata. No journal token,
 * timestamp, source identity, health value or fingerprint can be represented by this model.
 * Null acceptance/code mean unavailable evidence, never rejection or business code zero.
 */
data class MiRecoveryAttemptDiagnostic(
    val metadataAvailable: Boolean,
    val outcome: MiRecoveryStoredOutcome? = null,
    val accepted: Boolean? = null,
    val businessCode: Int? = null,
    val readbackStatus: MiRecoveryReadbackStatus? = null,
    val legacyWireMetadataUnknown: Boolean? = null,
)
