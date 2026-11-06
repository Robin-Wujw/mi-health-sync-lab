package com.bettermifitness.sync.data.api

import com.mifitness.miclient.api.MiApiException

/** Protocol metadata only. Never put a request/response body, record ID or timestamp here. */
enum class MiHealthImportStage { PREPARE, PRECHECK, UPLOAD, READBACK, COMPLETE }

enum class MiRevisionMetadataIssue {
    CLOUD_ZONE_ABSENT, CLOUD_TAG_BLANK, CLOUD_TAG_NONBLANK,
    CLOUD_BPM_NON_INTEGER_ENCODING, CLOUD_DATE_NON_INTEGER_ENCODING,
    SOURCE_SCHEMA_UNEXPECTED, SOURCE_DATE_UNALIGNED,
}

enum class MiHealthImportTransportFailure { NETWORK, AUTH, RATE_LIMIT, HTTP, INVALID_RESPONSE, OTHER }

/** Shape of the upload result only; no response fields or values are copied into diagnostics. */
enum class MiHealthImportUploadResultShape {
    BOOLEAN, NESTED_BOOLEAN, MISSING, NULL, OBJECT_WITHOUT_RESULT, NESTED_NULL, INVALID, NESTED_INVALID,
}

/** Counts only. A source may appear in more than one issue group, so groups are not additive. */
data class MiHealthImportIssueCount(
    val code: String,
    val severity: MiHealthImportIssueSeverity,
    val occurrences: Int,
    val sourceRecordCount: Int,
)

fun summarizeMiHealthImportIssues(issues: List<MiHealthImportIssue>): List<MiHealthImportIssueCount> =
    issues.groupBy { safeMiHealthImportIssueCode(it.code) to it.severity }
        .map { (group, matching) ->
            MiHealthImportIssueCount(group.first, group.second, matching.size,
                matching.flatMap { it.sourceRecordKeys }.distinct().size)
        }.sortedWith(compareBy({ it.severity.name }, { it.code }))

data class MiHealthImportResponseSummary(
    val businessCode: Int? = null,
    /** Only exact, known generic server phrases are retained; arbitrary server text is excluded. */
    val safeMessage: String? = null,
    val messageWithheld: Boolean = false,
    val accepted: Boolean? = null,
    /** Known HTTP status only. A business code is never put in this field. */
    val httpStatus: Int? = null,
    val transportFailure: MiHealthImportTransportFailure? = null,
    val uploadResultShape: MiHealthImportUploadResultShape? = null,
)

/** Fixed recovery states and counters only; never target IDs, raw values or journal contents. */
data class MiPendingRecoveryDiagnostic(
    val status: String,
    val userRequested: Boolean,
    val automatic: Boolean = false,
    val candidateRows: Int = 0,
    val previouslyConsumed: Boolean = false,
    val consumedThisRun: Boolean = false,
    /** Legacy journals omitted the outer timezone; this is not full HTTP-request provenance. */
    val legacyWireMetadataUnknown: Boolean = false,
    val historyPagesRead: Int = 0,
    val historyComplete: Boolean? = null,
    val previousOutcome: String? = null,
    val historyStopReason: MiHistoryStopReason? = null,
    val previousAttempt: MiRecoveryAttemptDiagnostic? = null,
    val precheckStatus: MiHealthImportVerificationStatus? = null,
    val nativeReadbackPages: Int = 0,
    val nativeReadbackComplete: Boolean? = null,
    val nativeReadbackStopReason: MiHistoryStopReason? = null,
    val nativeReadbackStatus: MiHealthImportVerificationStatus? = null,
    val nativeReadbackFailed: Boolean = false,
)

internal fun safeMiPendingRecoveryStatus(status: String): String = status.takeIf { it in setOf(
    "NOT_REQUESTED", "UNSUPPORTED", "NO_PENDING", "NO_PENDING_WITH_EXCLUSIONS", "SOURCE_CHANGED", "SOURCE_COLLISION", "READ_FAILED",
    "HISTORY_INCOMPLETE", "CONFLICT", "TOMBSTONE_PRESENT", "OTHER_SOURCE_PRESENT", "ALREADY_PRESENT",
    "RETRY_ALREADY_CONSUMED", "RESERVATION_CHANGED", "UPLOAD_REJECTED", "UPLOAD_UNVERIFIED", "VERIFIED",
) } ?: "UNKNOWN"

internal fun safeMiPendingRecoveryOutcome(outcome: String): String = outcome.takeIf { it in setOf(
    "RESERVED", "ACCEPTED", "REJECTED", "UNKNOWN", "VERIFIED", "TRANSPORT_UNKNOWN", "READBACK_UNVERIFIED", "ACCEPTED_UNVERIFIED",
    "REJECTED_UNVERIFIED", "UNKNOWN_UNVERIFIED", "READBACK_MISSING", "READBACK_MISMATCH",
    "READBACK_AMBIGUOUS", "READBACK_UNQUERIED",
) } ?: "UNKNOWN"

/** Cloud reads plus local reconciliation only. No health rows are uploaded by this action. */
data class MiPendingReconcileDiagnostic(
    val status: String,
    val userRequested: Boolean,
    val candidateRows: Int = 0,
    val reconciledRows: Int = 0,
    /** Candidates not reconciled in this action; not a fresh global pending count. */
    val unresolvedRows: Int = 0,
    val sourceChangedRows: Int = 0,
    val sourceCollisionRows: Int = 0,
    val cloudConflictRows: Int = 0,
    val deletedRows: Int = 0,
    val otherSourceRows: Int = 0,
    val missingRows: Int = 0,
    val unqueriedRows: Int = 0,
    val journalChangedRows: Int = 0,
    val persistenceFailedRows: Int = 0,
    val broadReadGroups: Int = 0,
    val broadReadFailureGroups: Int = 0,
    val broadReadLimitedGroups: Int = 0,
    val historySelectedRows: Int = 0,
    val historyPagesRead: Int = 0,
    val historyComplete: Boolean? = null,
    val historyReadFailed: Boolean = false,
    val historyQueries: Int = 0,
    val historyFollowUpQueries: Int = 0,
    val historyTargetsWithinObservedSpan: Int = 0,
    val historyStopReasons: Map<MiHistoryStopReason, Int> = emptyMap(),
    val historyFollowUpFailures: Int = 0,
    /** Same oldest candidate as single recovery, inspected without any health upload. */
    val oldestRecoveryAttempt: MiRecoveryAttemptDiagnostic? = null,
    /** Observation-only comparison. Its candidates never count as verified progress. */
    val readComparison: MiSpO2ReadComparison? = null,
    val minuteReconciledRows: Int = 0,
)

internal fun safeMiPendingReconcileStatus(status: String): String = status.takeIf { it in setOf(
    "NOT_REQUESTED", "UNSUPPORTED", "NO_PENDING", "COMPLETE", "PARTIAL", "INCOMPLETE", "LOCAL_SAVE_FAILED",
) } ?: "UNKNOWN"

data class MiHealthImportDiagnostic(
    val metricKey: String,
    val stage: MiHealthImportStage,
    val channel: MiHealthImportChannel? = null,
    val testMode: Boolean = false,
    val inputRecords: Int = 0,
    val sourceRawRows: Int = 0,
    val plannedRawRows: Int = 0,
    val plannedDailyRows: Int = 0,
    val attemptedRawRows: Int = 0,
    val attemptedDailyRows: Int = 0,
    val verifiedRawRows: Int = 0,
    val verifiedDailyRows: Int = 0,
    val affectedRows: Int = 0,
    val batchIndex: Int? = null,
    val batchRows: Int? = null,
    val rawBatchLimit: Int = MI_HEALTH_IMPORT_RAW_BATCH_SIZE,
    /** Last POST outcome, retained even when the later readback also fails. */
    val uploadResponse: MiHealthImportResponseSummary? = null,
    val readResponse: MiHealthImportResponseSummary? = null,
    val rhrDailyMatchedDays: Int? = null,
    val rhrDailyMissingDays: Int? = null,
    val rhrDailyConflictingDays: Int? = null,
    /** A fixed internal failure-reason code, never exception or server text. */
    val failureReason: String? = null,
    val alreadyPresentRawRows: Int = 0,
    val newlyVerifiedRawRows: Int = 0,
    /** Planned minus submitted minus already-present rows; includes blocked rows below. */
    val notAttemptedRawRows: Int = 0,
    val notAttemptedDailyRows: Int = 0,
    /** These blocking categories are disjoint; a pending journal entry takes precedence. */
    val blockedPendingRows: Int = 0,
    val blockedConflictRows: Int = 0,
    val blockedAmbiguousRows: Int = 0,
    /** Previously verified RAW identity omitted by this read; never automatically re-uploaded. */
    val blockedVerifiedHistoryRows: Int = 0,
    val sourceCollisionRows: Int = 0,
    /** Final unresolved classifications, not cumulative counts across repeated GET attempts. */
    val readbackMissingRows: Int = 0,
    val readbackMismatchedRows: Int = 0,
    val readbackAmbiguousRows: Int = 0,
    val readbackUnqueriedRows: Int = 0,
    val preservedDailyRows: Int = 0,
    /** Distinct rejected source IDs, with unkeyed issue fallback; not Xiaomi row count. */
    val sourceRejectedRecords: Int = 0,
    val issueCounts: List<MiHealthImportIssueCount> = emptyList(),
    /** Fixed schema field names only; arbitrary or indexed paths become "other". */
    val precheckMismatchFields: Map<String, Int> = emptyMap(),
    val readbackMismatchFields: Map<String, Int> = emptyMap(),
    /** Extra read-only observations of earlier pending rows, never added to verified upload counts. */
    val pendingProbe: MiPendingImportProbeResult? = null,
    val pendingProbeFailed: Boolean = false,
    val pendingRecovery: MiPendingRecoveryDiagnostic? = null,
    val pendingReconcile: MiPendingReconcileDiagnostic? = null,
    val sleepConflict: MiSleepConflictDiagnostic? = null,
    val attemptedRevisionRows: Int = 0,
    val verifiedRevisionRows: Int = 0,
    val eligibleRevisionRows: Int = 0,
    val revisionMetadataIssues: Map<MiRevisionMetadataIssue, Int> = emptyMap(),
) {
    val hasVerifiedRows: Boolean get() = verifiedRawRows > 0 || verifiedDailyRows > 0
    /** Excluded destination seconds, never included in verified counts. */
    val excludedSourcePoints: Int get() = issueCounts.filter { it.code == "HEART_RATE_SOURCE_POINT_EXCLUDED" }.sumOf { it.occurrences }
    /** Excluded RMSSD samples, not missing uploads or verified sleep summaries. */
    val excludedHrvSamples: Int get() = issueCounts.filter { it.code in setOf(
        "HRV_AMBIGUOUS_SLEEP", "HRV_NO_MATCHING_SLEEP", "HRV_AWAKE_SAMPLE_EXCLUDED",
    ) && it.severity == MiHealthImportIssueSeverity.WARNING }.sumOf { it.occurrences }
}

/** Do not serialize arbitrary keys even if they resemble an internal diagnostic code. */
internal fun safeMiHealthImportIssueCode(code: String): String =
    code.takeIf { it in SAFE_IMPORT_ISSUE_CODES } ?: "UNKNOWN"

internal fun safeMiHealthImportFailureReason(reason: String): String =
    reason.takeIf { it in setOf("CONFLICT", "PENDING", "REJECTED", "UNVERIFIED", "SOURCE_REJECTED") } ?: "UNKNOWN"

fun safeMiHealthImportMismatchField(field: String): String =
    field.takeIf { it in SAFE_IMPORT_MISMATCH_FIELDS } ?: safeMiSleepMismatchField(field) ?: "other"

/** A row contributes at most one count to a normalized field, including the "other" bucket. */
fun summarizeMiHealthImportMismatchFields(verifications: Iterable<MiHealthImportVerification>): Map<String, Int> =
    verifications.flatMap { it.mismatchedFields.map(::safeMiHealthImportMismatchField).distinct() }
        .groupingBy { it }.eachCount().entries.sortedBy { it.key }.associate { it.key to it.value }

private val SAFE_IMPORT_MISMATCH_FIELDS = setOf(
    "bpm", "spo2", "time", "date_time", "zone_offset", "avg_hrv", "min_hrv", "max_hrv", "sid",
    "duplicate_target", "deleted", "bedtime", "wake_up_time", "bed_timestamp", "out_bed_timestamp", "duration", "timezone",
    "sleep_deep_duration", "sleep_light_duration", "sleep_rem_duration", "sleep_awake_duration", "awake_count",
    "items", "is_uncomplete", "min_hr", "max_hr", "avg_hr", "latest_hr", "latest_hr.time", "latest_hr.bpm",
    "min_spo2", "max_spo2", "avg_spo2", "latest_spo2", "latest_spo2.time", "latest_spo2.spo2",
    "total_duration", "segment_details", "other",
)

private val SAFE_IMPORT_ISSUE_CODES = setOf(
    "INVALID_INTERVAL", "EMPTY_SAMPLES", "INVALID_HEART_RATE_SAMPLE", "INVALID_RESTING_HEART_RATE",
    "RESTING_HEART_RATE_DATE_UNKNOWN", "INVALID_RESTING_HEART_RATE_DATE", "RESTING_HEART_RATE_DAY_COLLISION",
    "RESTING_HEART_RATE_RAW_ONLY", "RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED", "INVALID_OXYGEN_SATURATION",
        "UNREPRESENTABLE_OXYGEN_SATURATION",
    "OXYGEN_SATURATION_PRECISION", "INVALID_SLEEP_INTERVAL", "INVALID_SLEEP_STAGES", "SUBSECOND_SLEEP_INTERVAL",
    "SUBSECOND_SLEEP_STAGE", "PARTIAL_SLEEP_STAGES", "SOURCE_TIMEZONE_UNAVAILABLE", "SOURCE_TIMEZONE_CHANGED",
    "SOURCE_TIMEZONE_NOT_QUARTER_HOUR", "SUBSECOND_PRECISION", "INVALID_SOURCE", "SOURCE_REVISION_DEDUPLICATED",
    "CONFLICTING_SOURCE_REVISIONS", "TARGET_TIME_COLLISION", "REJECTED_SOURCE_PREVENTS_DAILY_REPORT", "HEART_RATE_SOURCE_POINT_EXCLUDED",
    "INCOMPLETE_LOCAL_DAY", "OVERLAPPING_SLEEP_SESSIONS", "HRV_NO_MATCHING_SLEEP", "HRV_AMBIGUOUS_SLEEP",
    "HRV_AWAKE_SAMPLE_EXCLUDED", "HRV_SLEEP_RANGE_ASSUMPTION", "UNREPRESENTABLE_HRV_SUMMARY",
    "RMSSD_EXPERIMENTAL_MAPPING", "HRV_INTEGER_ROUNDING", "CONFLICTING_HRV_REVISIONS", "INVALID_HRV_SAMPLE",
    "HRV_TIME_COLLISION", "HRV_DUPLICATE_SAMPLE", "EXISTING_DAILY_REPORT_PRESERVED", "TEST_MODE_SINGLE_RECORD",
    "RHR_DAILY_CHECK_FAILED", "SLEEP_UPDATED_FROM_ZEPP", "SLEEP_REVISION_CHANGED",
    "source_target_collision", "remaining_batches_not_submitted", "UNKNOWN",
)

/** Does not expose the original exception message, which can contain request data. */
class MiHealthImportTransportException(
    val responseSummary: MiHealthImportResponseSummary,
    cause: Throwable? = null,
) : IllegalStateException("Mi import request did not complete successfully", cause)

internal fun safeMiHealthImportMessage(message: String?): Pair<String?, Boolean> {
    val trimmed = message?.trim().orEmpty()
    if (trimmed.isEmpty()) return null to false
    return if (trimmed.lowercase() in SAFE_SERVER_MESSAGES) trimmed to false else null to true
}

internal fun miHealthImportFailureSummary(error: Throwable): MiHealthImportResponseSummary = when (error) {
    is MiHealthImportTransportException -> error.responseSummary
    is MiApiException.Network -> MiHealthImportResponseSummary(transportFailure = MiHealthImportTransportFailure.NETWORK)
    is MiApiException.AuthExpired -> MiHealthImportResponseSummary(transportFailure = MiHealthImportTransportFailure.AUTH)
    is MiApiException.RateLimited -> MiHealthImportResponseSummary(
        httpStatus = 429, transportFailure = MiHealthImportTransportFailure.RATE_LIMIT)
    // Callers must know whether a Server code came from HTTP or a business response.
    // Do not guess from its numeric range in this general fallback.
    is MiApiException.Server -> MiHealthImportResponseSummary(transportFailure = MiHealthImportTransportFailure.OTHER)
    is MiApiException.Unexpected -> MiHealthImportResponseSummary(transportFailure = MiHealthImportTransportFailure.INVALID_RESPONSE)
    else -> MiHealthImportResponseSummary(transportFailure = MiHealthImportTransportFailure.OTHER)
}

private val SAFE_SERVER_MESSAGES = setOf(
    "ok", "success", "succeed", "successful", "failed", "failure", "error",
    "invalid parameter", "invalid parameters", "invalid param", "invalid params",
    "invalid argument", "invalid arguments", "parameter error", "param error", "bad request",
    "invalid data", "data error", "data invalid", "request error", "request failed", "upload failed",
    "operation failed", "not supported", "unsupported", "permission denied", "access denied",
    "forbidden", "unauthorized", "rate limited", "too many requests", "system error", "server error",
    "internal error", "internal server error", "unknown error",
    "参数错误", "参数不合法", "参数非法", "参数校验失败", "请求参数错误", "请求失败", "上传失败",
    "数据错误", "数据格式错误", "无效数据", "未授权", "没有权限", "权限不足", "服务异常",
    "系统错误", "系统异常", "服务器错误", "操作成功", "成功", "失败",
)
