package com.bettermifitness.sync.sync

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.bettermifitness.sync.data.preferences.SyncPreferences
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path

internal class UserSyncJournal private constructor(private val store: DataStore<State>) {
    val summaries = store.data.map { it.history }.distinctUntilChanged()
    val recoverable = store.data.map { state ->
        (state.queued ?: state.active?.takeIf { it.fromReplacement && it.phase == Phase.ACCEPTED })?.requestOrNull()
    }.distinctUntilChanged()

    suspend fun stageReplacement(request: UserSyncRequest): Boolean {
        val entry = entryOf(request)
        var accepted = false
        update { state ->
            val promoted = state.active
            if (promoted?.fromReplacement == true && promoted.phase == Phase.ACCEPTED &&
                promoted.copy(fromReplacement = false, source = SyncTaskSource.MANUAL) == entry && state.queued == null) {
                accepted = true
                return@update state.copy(active = null, queued = entry)
            }
            if (state.active?.id == entry.id || state.history.any { it.id == entry.id }) return@update state
            if (state.queued?.id == entry.id) {
                accepted = state.queued == entry
                return@update state
            }
            accepted = true
            val replaced = state.queued?.let { state.ended(it, REPLACED) } ?: state
            replaced.copy(
                active = replaced.active?.let { it.copy(phase = Phase.STOPPING, stopStatus = it.stopStatus ?: REPLACED) },
                queued = entry,
            )
        }
        return accepted
    }

    suspend fun accept(
        request: UserSyncRequest,
        fromQueue: Boolean = false,
        source: SyncTaskSource = SyncTaskSource.MANUAL,
    ): Boolean {
        val entry = entryOf(request)
        var accepted = false
        update { state ->
            if (state.active?.id == entry.id || state.history.any { it.id == entry.id }) return@update state
            val queued = state.queued
            if (fromQueue && queued != entry || !fromQueue && queued?.generation == entry.generation) return@update state
            accepted = true
            val cleared = if (!fromQueue && queued != null) state.ended(queued, SyncOutcome.STATUS_SKIPPED) else state
            val ended = cleared.active?.let { cleared.ended(it, it.stopStatus ?: INTERRUPTED) } ?: cleared
            ended.copy(active = entry.copy(fromReplacement = fromQueue, source = source), queued = null)
        }
        return accepted
    }

    private fun entryOf(request: UserSyncRequest): Entry {
        require(validRunId(request.runId))
        val entry = Entry(
            request.runId, request.accountGeneration, request.selection.rangeDays,
            request.selection.enabled, request.selection.from.toString(), request.selection.to.toString(),
            sources = request.sources,
        )
        require(entry.requestOrNull() != null)
        return entry
    }

    internal data class Claimed(val request: UserSyncRequest, val source: SyncTaskSource)

    suspend fun claim(id: String, processId: String): UserSyncRequest? = claimTask(id, processId)?.request

    suspend fun claimTask(id: String, processId: String): Claimed? {
        var claimed: Claimed? = null
        update { state ->
            val entry = state.active?.takeIf { it.id == id } ?: return@update state
            if (entry.phase == Phase.STOPPING) return@update state.ended(entry, entry.stopStatus ?: CANCELLED)
            if (entry.processId == processId) return@update state
            val request = entry.requestOrNull() ?: return@update state.ended(entry, INTERRUPTED)
            val source = if (entry.phase == Phase.RUNNING) SyncTaskSource.RECOVERED else entry.source
            claimed = Claimed(request, source)
            state.copy(active = entry.copy(phase = Phase.RUNNING, processId = processId, source = source,
                startedAt = entry.startedAt ?: Clock.System.now().toString()))
        }
        return claimed
    }

    suspend fun stopping(id: String) = update { state ->
        val entry = state.active?.takeIf { it.id == id } ?: return@update state
        if (entry.phase == Phase.STOPPING) state
        else state.copy(active = entry.copy(phase = Phase.STOPPING, stopStatus = CANCELLED))
    }

    suspend fun stopEverything() = update { state ->
        val ended = state.queued?.let { state.ended(it, CANCELLED) } ?: state
        val active = ended.active ?: return@update ended
        if (active.phase == Phase.ACCEPTED) ended.ended(active, CANCELLED)
        else ended.copy(active = active.copy(phase = Phase.STOPPING, stopStatus = CANCELLED))
    }

    suspend fun finishQueued(id: String, outcome: SyncOutcome) = update { state ->
        val queued = state.queued?.takeIf { it.id == id } ?: return@update state
        state.ended(queued, outcome.toStatusCode())
    }

    suspend fun finish(
        id: String,
        outcome: SyncOutcome?,
        processId: String? = null,
        execution: SyncTaskSummary? = null,
    ) = update { state ->
        val entry = state.active?.takeIf { it.id == id } ?: return@update state
        if (processId != null && entry.processId != processId && entry.phase != Phase.STOPPING) return@update state
        val status = if (entry.phase == Phase.STOPPING) entry.stopStatus ?: CANCELLED else outcome?.toStatusCode() ?: INTERRUPTED
        state.ended(entry, status, execution?.takeIf { it.id == id && it.generation == entry.generation })
    }

    suspend fun recordAutomatic(summary: SyncTaskSummary) = update { state ->
        require(summary.source == SyncTaskSource.AUTOMATIC && summary.generation != null && validRunId(summary.id))
        val cleared = if (state.automatic?.summary?.id == summary.id) state.copy(automatic = null) else state
        if (cleared.history.any { it.id == summary.id }) cleared else cleared.prepend(summary)
    }

    suspend fun beginAutomatic(task: ActiveSyncTask, processId: String) = update { state ->
        require(task.source == SyncTaskSource.AUTOMATIC && validRunId(task.id))
        val reconciled = state.reconcileAutomatic(processId)
        check(reconciled.automatic == null) { "Another automatic sync is still active" }
        val summary = task.summarize(INTERRUPTED, task.startedAt, null)
        reconciled.copy(automatic = PendingAutomatic(summary, processId))
    }

    suspend fun reconcileAutomatic(processId: String) = update { state -> state.reconcileAutomatic(processId) }

    @Serializable
    private data class PendingAutomatic(val summary: SyncTaskSummary, val processId: String)

    private fun State.reconcileAutomatic(processId: String): State {
        val pending = automatic?.takeUnless { it.processId == processId } ?: return this
        val cleared = copy(automatic = null)
        if (cleared.history.any { it.id == pending.summary.id }) return cleared
        return cleared.prepend(pending.summary.copy(finishedAt = Clock.System.now().toString()))
    }

    suspend fun history(): List<SyncTaskSummary> = summaries.first()

    private suspend fun update(transform: (State) -> State) {
        store.updateData(transform)
    }

    @Serializable
    private data class State(
        val active: Entry? = null,
        val history: List<SyncTaskSummary> = emptyList(),
        val queued: Entry? = null,
        val automatic: PendingAutomatic? = null,
    ) {
        fun prepend(summary: SyncTaskSummary): State = copy(history = (listOf(summary) + history).take(HISTORY_LIMIT))

        fun ended(entry: Entry, status: String, execution: SyncTaskSummary? = null): State = prepend(SyncTaskSummary(
            entry.id, status, entry.days, Clock.System.now().toString(), entry.generation, entry.source,
            entry.from, entry.until, entry.startedAt, execution?.succeeded, execution?.failed, execution?.noData,
        )).copy(
            active = active?.takeUnless { it.id == entry.id },
            queued = queued?.takeUnless { it.id == entry.id },
        )
    }

    @Serializable
    private enum class Phase { ACCEPTED, RUNNING, STOPPING }

    @Serializable
    private data class Entry(
        val id: String,
        val generation: Long,
        val days: Int,
        val metrics: Set<String>,
        val from: String,
        val until: String,
        val phase: Phase = Phase.ACCEPTED,
        val processId: String? = null,
        val stopStatus: String? = null,
        val fromReplacement: Boolean = false,
        val source: SyncTaskSource = SyncTaskSource.MANUAL,
        val startedAt: String? = null,
        val sources: UserSyncSources? = null,
    ) {
        fun requestOrNull(): UserSyncRequest? {
            if (!validRunId(id) || generation < 0L || days !in 1..30 ||
                metrics.isEmpty() || !ALLOWED_METRICS.containsAll(metrics)) return null
            val start = parseInstant(from) ?: return null
            val end = parseInstant(until) ?: return null
            if (end - start != days.days) return null
            return UserSyncRequest(id, generation, ActiveSyncSelection(days, metrics, start, end), sources)
        }
    }

    private object StateSerializer : OkioSerializer<State> {
        override val defaultValue = State()

        override suspend fun readFrom(source: BufferedSource): State = try {
            Json.decodeFromString(source.readUtf8())
        } catch (error: SerializationException) {
            throw CorruptionException("Invalid user sync journal", error)
        }

        override suspend fun writeTo(t: State, sink: BufferedSink) {
            sink.writeUtf8(Json.encodeToString(t))
        }
    }

    companion object {
        fun create(
            path: Path,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): UserSyncJournal = UserSyncJournal(DataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, StateSerializer, producePath = { path }),
            scope = scope,
        ))

        private const val HISTORY_LIMIT = 20
        private const val INTERRUPTED = "interrupted"
        private const val REPLACED = "replaced"
        private const val CANCELLED = SyncOutcome.STATUS_CANCELLED
        private val ALLOWED_METRICS = SyncPreferences.ALL_METRIC_KEYS + SyncPreferences.INBOUND_METRIC_KEYS +
            SyncPreferences.ZEPP_CLOUD_METRIC_KEYS

        private fun parseInstant(value: String): Instant? = try {
            Instant.parse(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private val RUN_ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")

internal val androidSyncProcessId: String = java.util.UUID.randomUUID().toString()

internal fun validRunId(value: String): Boolean = RUN_ID_PATTERN.matches(value)
