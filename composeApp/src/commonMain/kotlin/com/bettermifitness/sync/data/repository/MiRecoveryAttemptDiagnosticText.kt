package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiRecoveryAttemptDiagnostic
import com.bettermifitness.sync.data.api.MiRecoveryReadbackStatus
import com.bettermifitness.sync.data.api.MiRecoveryStoredOutcome
import com.bettermifitness.sync.data.preferences.HealthImportPendingSnapshot
import com.bettermifitness.sync.i18n.L10n

/** Project local history into typed diagnostic fields; never retain the original journal object. */
fun HealthImportPendingSnapshot.previousRecoveryDiagnostic(): MiRecoveryAttemptDiagnostic {
    val previous = recoveryAttempt ?: return MiRecoveryAttemptDiagnostic(metadataAvailable = false)
    return MiRecoveryAttemptDiagnostic(
        metadataAvailable = true,
        outcome = MiRecoveryStoredOutcome.entries.firstOrNull { it.name == previous.outcome } ?: MiRecoveryStoredOutcome.UNKNOWN,
        accepted = previous.accepted,
        businessCode = previous.businessCode,
        readbackStatus = previous.readbackStatus?.let { status ->
            MiRecoveryReadbackStatus.entries.firstOrNull { it.name == status } ?: MiRecoveryReadbackStatus.UNKNOWN
        },
        legacyWireMetadataUnknown = previous.legacyWireMetadataUnknown,
    )
}

/** Additional history lines; the caller already formats the existing previous_outcome field. */
fun MiRecoveryAttemptDiagnostic.copyableText(): String = buildList {
    add("pending_recovery.previous_metadata_available=$metadataAvailable")
    add("pending_recovery.previous_accepted=${if (metadataAvailable) accepted?.toString() ?: "unknown" else "unknown"}")
    add("pending_recovery.previous_business_code=${if (metadataAvailable) businessCode?.toString() ?: "unknown" else "unknown"}")
    add("pending_recovery.previous_readback_status=${if (metadataAvailable) readbackStatus?.name ?: "UNKNOWN" else "UNKNOWN"}")
    add("pending_recovery.previous_attempt_legacy_metadata_unknown=${if (metadataAvailable) legacyWireMetadataUnknown?.toString() ?: "unknown" else "unknown"}")
}.joinToString("\n")

/** Explicitly labelled history so it cannot be read as a new upload result. */
fun MiRecoveryAttemptDiagnostic.userFacingDetails(): String {
    if (!metadataAvailable) return L10n.text(L10n.recoveryHistoryUnavailable)
    return buildList {
        add(L10n.text(when (accepted) {
            true -> L10n.recoveryHistoryAccepted
            false -> L10n.recoveryHistoryRejected
            null -> if (outcome == MiRecoveryStoredOutcome.RESERVED) L10n.recoveryHistoryReserved
                else L10n.recoveryHistoryAcceptanceUnknown
        }))
        businessCode?.let { add(L10n.textFmt(L10n.recoveryHistoryBusinessCode, it)) }
        add(L10n.text(when (readbackStatus) {
            MiRecoveryReadbackStatus.VERIFIED -> L10n.recoveryHistoryReadbackVerified
            MiRecoveryReadbackStatus.MISSING -> L10n.recoveryHistoryReadbackMissing
            MiRecoveryReadbackStatus.MISMATCH -> L10n.recoveryHistoryReadbackMismatch
            MiRecoveryReadbackStatus.AMBIGUOUS -> L10n.recoveryHistoryReadbackAmbiguous
            MiRecoveryReadbackStatus.UNQUERIED -> L10n.recoveryHistoryReadbackUnqueried
            MiRecoveryReadbackStatus.UNKNOWN, null -> L10n.recoveryHistoryReadbackUnknown
        }))
    }.joinToString("\n")
}
