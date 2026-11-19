package com.bettermifitness.sync.sync

import com.bettermifitness.sync.data.repository.SyncRunResult
import com.bettermifitness.sync.i18n.L10n
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class SyncTaskSource { MANUAL, AUTOMATIC, RECOVERED }

@Serializable
data class UserSyncSources(val zeppIdentity: Long?, val calendarZoneId: String?)

internal fun SyncTaskSource.label() = L10n.text(when (this) {
    SyncTaskSource.MANUAL -> L10n.syncSourceManual
    SyncTaskSource.AUTOMATIC -> L10n.syncSourceAutomatic
    SyncTaskSource.RECOVERED -> L10n.syncSourceRecovered
})

internal fun displayTaskTime(value: String): String = try {
    Instant.parse(value).toLocalDateTime(TimeZone.currentSystemDefault()).toString().replace('T', ' ').take(16)
} catch (_: IllegalArgumentException) {
    "—"
}

data class ActiveSyncTask(
    val id: String,
    val source: SyncTaskSource,
    val accountGeneration: Long,
    val selection: ActiveSyncSelection,
    val startedAt: Instant,
) {
    internal fun summarize(status: String, finishedAt: Instant, result: SyncRunResult?) = SyncTaskSummary(
        id = id,
        status = status,
        days = selection.rangeDays,
        finishedAt = finishedAt.toString(),
        generation = accountGeneration,
        source = source,
        from = selection.from.toString(),
        until = selection.to.toString(),
        startedAt = startedAt.toString(),
        succeeded = result?.succeeded,
        failed = result?.failed,
        noData = result?.noData,
    )
}

@Serializable
data class SyncTaskSummary(
    val id: String,
    val status: String,
    val days: Int,
    val finishedAt: String,
    val generation: Long? = null,
    val source: SyncTaskSource = SyncTaskSource.MANUAL,
    val from: String? = null,
    val until: String? = null,
    val startedAt: String? = null,
    val succeeded: Int? = null,
    val failed: Int? = null,
    val noData: Int? = null,
) {
    val wasSkipped: Boolean get() = status == SyncOutcome.STATUS_SKIPPED
}
