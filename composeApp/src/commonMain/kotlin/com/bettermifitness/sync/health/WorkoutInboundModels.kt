package com.bettermifitness.sync.health

import com.bettermifitness.sync.data.api.WorkoutSession
import kotlinx.serialization.Serializable

data class InboundWorkout(
    val origin: HealthRecordOrigin,
    val session: WorkoutSession,
    val activeDurationSeconds: Long,
    val routeConsentRequired: Boolean = false,
    val omittedMetricFamilies: Set<String> = emptySet(),
)

data class InboundVo2Max(
    val origin: HealthRecordOrigin,
    val epochSeconds: Long,
    val zoneOffsetSeconds: Int?,
    val mlPerKgMinute: Double,
    val measurementMethod: Int,
)

@Serializable
data class WorkoutImportReport(
    val sourceRecords: Int = 0,
    val uploaded: Int = 0,
    val existing: Int = 0,
    val revised: Int = 0,
    val pending: Int = 0,
    val conflicts: Int = 0,
    val unsupported: Int = 0,
    val routesAwaitingConsent: Int = 0,
    val verifiedFiles: Int = 0,
    val failedFiles: Int = 0,
    val fileBodyUploads: Int = 0,
    val fileReferenceWrites: Int = 0,
    val unsupportedTypes: List<String> = emptyList(),
    val fileFailures: List<String> = emptyList(),
    val restored: Int = 0,
) {
    val verified: Int get() = uploaded + existing + revised + restored
    val complete: Boolean get() = pending + conflicts + unsupported + failedFiles + routesAwaitingConsent == 0
    fun diagnostic(): String = listOf(
        "workout_sync.source_records=$sourceRecords", "workout_sync.uploaded=$uploaded",
        "workout_sync.existing=$existing", "workout_sync.revised=$revised", "workout_sync.pending=$pending",
        "workout_sync.conflicts=$conflicts", "workout_sync.unsupported=$unsupported",
        "workout_sync.routes_awaiting_consent=$routesAwaitingConsent", "workout_sync.verified_files=$verifiedFiles",
        "workout_sync.failed_files=$failedFiles", "workout_sync.complete=$complete",
        "workout_sync.file_body_uploads=$fileBodyUploads", "workout_sync.file_reference_writes=$fileReferenceWrites",
        "workout_sync.unsupported_types=${unsupportedTypes.joinToString(",")}",
        "workout_sync.file_failures=${fileFailures.joinToString(",")}",
        "workout_sync.restored=$restored",
    ).joinToString("\n")
}
