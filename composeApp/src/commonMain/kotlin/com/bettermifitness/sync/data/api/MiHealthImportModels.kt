package com.bettermifitness.sync.data.api

import com.bettermifitness.sync.health.HealthInboundMetric
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The software/default source used by the independently tested raw importer.
 * It is NOT exclusive to BMFS: loop prevention must use the verified target
 * identity AND content from the import ledger, never exclude all `default` rows.
 * A differing `default` row at a target is still replaced: the user syncs from Zepp
 * only and treats it as the source of truth.
 */
const val MI_HEALTH_CONNECT_IMPORT_SID = "default"
/** Native FDS requires an existing application source; `default` has no device namespace. */
const val MI_SLEEP_FILE_IMPORT_SID = "xiaomiwear_app_manually"
// Mi Fitness 3.59.2 IncrementalSyncController bounds pending raw uploads to 50 rows.
// Follow that native batch shape; business acceptance still requires readback.
const val MI_HEALTH_IMPORT_RAW_BATCH_SIZE = 50
const val MI_HEALTH_IMPORT_AGGREGATE_BATCH_SIZE = 30

@Serializable
enum class MiHealthImportChannel { RAW, DAILY_AGGREGATE }

@Serializable
enum class MiHealthImportEnrichment { SLEEP_HRV, SLEEP_VITALS }

@Serializable
data class MiHealthImportIdentity(
    val channel: MiHealthImportChannel,
    val sid: String,
    val key: String,
    val time: Long,
    val tag: String? = null,
) {
    val stableKey: String get() = "${channel.name}|$sid|$key|$time|${tag.orEmpty()}"
}

/** [sourceRecordKeys] belongs in the local journal, not in Xiaomi's payload. */
@Serializable
data class MiHealthImportRow(
    val identity: MiHealthImportIdentity,
    val value: JsonObject,
    val zoneOffsetSeconds: Int,
    val sourceRecordKeys: Set<String>,
    /** Missing source zone or a DST transition prevents a trustworthy day summary. */
    val dayAggregationEligible: Boolean = true,
    val enrichment: MiHealthImportEnrichment? = null,
) {
    val canonicalContent: String get() = canonicalMiHealthImportJson(value)
}

enum class MiHealthImportIssueSeverity { WARNING, REJECTED }

data class MiHealthImportIssue(
    val code: String,
    val sourceRecordKeys: Set<String>,
    val message: String,
    val severity: MiHealthImportIssueSeverity = MiHealthImportIssueSeverity.REJECTED,
    /** Internal attribution only; null means affected days cannot safely be localized. */
    val affectedLocalDays: Set<Long>? = null,
    /** Internal attribution distinguishes skipped points; never emitted by diagnostic formatters. */
    val affectedTarget: MiHealthImportIdentity? = null,
)

data class MiHealthImportPlan(
    val metric: HealthInboundMetric,
    val rows: List<MiHealthImportRow>,
    val issues: List<MiHealthImportIssue>,
    val inputRecordCount: Int,
    val sleepFiles: List<MiSleepVitalsFile> = emptyList(),
    val sleepSourceMigration: MiSleepSourceMigrationDiagnostic? = null,
) {
    val rawRows: List<MiHealthImportRow> get() = rows.filter { it.identity.channel == MiHealthImportChannel.RAW }
    val aggregateRows: List<MiHealthImportRow> get() = rows.filter { it.identity.channel == MiHealthImportChannel.DAILY_AGGREGATE }
}

data class MiHealthImportCloudRow(
    val sid: String?,
    val key: String,
    val time: Long,
    val tag: String?,
    val value: JsonObject,
    val deleted: Boolean = false,
    val zoneOffsetSeconds: Int? = null,
)

/** An accepted POST still requires a separate content readback. */
data class MiHealthImportUploadResult(
    val accepted: Boolean,
    val code: Int? = null,
    val safeMessage: String? = null,
    val messageWithheld: Boolean = false,
    val resultShape: MiHealthImportUploadResultShape? = null,
) {
    fun responseSummary() = MiHealthImportResponseSummary(
        businessCode = code, safeMessage = safeMessage,
        messageWithheld = messageWithheld, accepted = accepted, uploadResultShape = resultShape,
    )
}

/** Fakeable, single-attempt transport. It must never retry a possibly applied write. */
interface MiHealthImportCloud {
    /** Retires only a previously verified imported legacy sleep after its replacement is verified. */
    suspend fun retireLegacySleep(identity: MiHealthImportIdentity): MiHealthImportUploadResult =
        throw UnsupportedOperationException("Sleep source migration retirement is unavailable")

    suspend fun readRows(
        channel: MiHealthImportChannel,
        key: String,
        fromEpochSeconds: Long,
        untilEpochSeconds: Long,
    ): List<MiHealthImportCloudRow>

    /**
     * Independent RAW read control using the native full-pull request shape.
     * No start_time or non-native limit is sent. A bounded/incomplete read must
     * never be treated as evidence that an unreturned target does not exist.
     */
    suspend fun readRawRowsWithoutStart(
        key: String,
        untilEpochSeconds: Long,
        maxPages: Int = 5,
    ): MiHealthImportHistoryRead = throw UnsupportedOperationException("Native-shape history reads are unavailable")

    suspend fun uploadRows(
        rows: List<MiHealthImportRow>,
        experimentalEnabled: Boolean = false,
    ): MiHealthImportUploadResult
}

/** complete means the endpoint explicitly ended this cursor traversal, not proof of persistence/absence. */
data class MiHealthImportHistoryRead(
    val rows: List<MiHealthImportCloudRow>,
    val pagesRead: Int,
    val complete: Boolean,
    val stopReason: MiHistoryStopReason = if (complete) MiHistoryStopReason.END_OF_HISTORY else MiHistoryStopReason.UNKNOWN,
)

/** Fixed labels only: cursors and health timestamps never enter diagnostics. */
enum class MiHistoryStopReason { END_OF_HISTORY, PAGE_LIMIT, INVALID_PAGINATION, MISSING_CURSOR, REPEATED_CURSOR, UNKNOWN }

enum class MiHealthImportVerificationStatus { VERIFIED, MISSING, MISMATCH, AMBIGUOUS }

data class MiHealthImportVerification(
    val status: MiHealthImportVerificationStatus,
    val mismatchedFields: Set<String> = emptySet(),
)

/** Counts for the preparatory native-source migration, separate from the subsequent ordinary runner. */
data class MiSleepSourceMigrationDiagnostic(val candidateRows: Int, val submittedReplacementRows: Int,
    val attemptedRetirementRows: Int, val verifiedMigrationRows: Int)
