package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.FitnessResponse
import com.bettermifitness.sync.data.api.FitnessResult
import com.bettermifitness.sync.data.api.MiPageCursorGuard
import com.bettermifitness.sync.data.api.MiPaginationIncomplete
import com.bettermifitness.sync.data.api.MiPaginationIssue
import com.bettermifitness.sync.data.api.incompleteMiPagination
import com.mifitness.miclient.api.MiApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** A server failure or truncated history must never look like an empty successful sync. */
internal fun <T> FitnessResponse<T>.checkedResult(): FitnessResult<T> {
    if (code == null) throw MiApiException.Unexpected("Mi cloud returned no business status")
    if (code != 0) throw MiApiException.Server(code, message ?: "Mi cloud rejected this data request ($code)")
    return result ?: throw MiApiException.Unexpected("Mi cloud returned no data result")
}

internal suspend fun <T> collectFitnessPages(
    maxPages: Int = 60,
    resume: FitnessPagePrefix<T>? = null,
    checkpoint: (suspend (FitnessPagePrefix<T>) -> Unit)? = null,
    fetch: suspend (String?) -> FitnessResponse<T>,
): List<T> {
    require(maxPages > 0)
    val all = resume?.rows.orEmpty().toMutableList()
    val tokens = resume?.cursors.orEmpty().toMutableList()
    val cursor = MiPageCursorGuard(maxPages, tokens)
    var token: String? = tokens.lastOrNull()
    do {
        val result = fetch(token).checkedResult()
        all += result.dataList
        token = cursor.nextCursor(result.hasMore, result.nextKey)
        if (token != null) {
            tokens += token
            checkpoint?.invoke(FitnessPagePrefix(all.toList(), tokens.toList()))
        }
    } while (token != null)
    return all
}

internal data class FitnessPagePrefix<T>(val rows: List<T>, val cursors: List<String>) {
    fun usable(maxPages: Int): Boolean = cursors.size in 1 until maxPages && cursors.distinct().size == cursors.size &&
        cursors.all { it.isNotBlank() && it.length <= 4_096 }
}

internal data class FitnessWindowProgress<T>(val prefix: FitnessPagePrefix<T>? = null, val split: Boolean = false)

internal interface FitnessWindowCheckpoints<T> {
    suspend fun load(window: SyncWindow): FitnessWindowProgress<T>?
    suspend fun save(window: SyncWindow, progress: FitnessWindowProgress<T>?)
}

internal suspend fun <T> collectFitnessWindow(
    window: SyncWindow,
    maxPages: Int,
    timestamp: ((T) -> Long)?,
    maxRequests: Int = maxPages * 3,
    maxDepth: Int = 8,
    checkpoints: FitnessWindowCheckpoints<T>? = null,
    fetch: suspend (SyncWindow, String?) -> FitnessResponse<T>,
): List<T> {
    require(maxPages > 0 && maxRequests >= maxPages && maxDepth >= 0)
    var remaining = maxRequests
    var cursorRestartAvailable = true

    suspend fun read(current: SyncWindow, depth: Int): List<T> {
        val saved = checkpoints?.load(current)
        val canSplit = timestamp != null && depth < maxDepth && current.to - current.from > 1
        suspend fun split(): List<T> {
            val middle = current.from + (current.to - current.from) / 2
            return read(SyncWindow(middle, current.to), depth + 1) + read(SyncWindow(current.from, middle), depth + 1)
        }
        if (saved?.split == true && canSplit) return split()
        val prefix = saved?.prefix?.takeIf { it.usable(maxPages) }
        val persistPage: (suspend (FitnessPagePrefix<T>) -> Unit)? = if (checkpoints == null) null else { page ->
            checkpoints.save(current, FitnessWindowProgress(prefix = page))
        }
        suspend fun fetchComplete(resume: FitnessPagePrefix<T>?): List<T> = collectFitnessPages(maxPages, resume, persistPage) { token ->
            if (remaining == 0) throw incompleteMiPagination(MiPaginationIssue.REQUEST_BUDGET,
                "Mi cloud read reached its request budget; export is incomplete")
            remaining--
            fetch(current, token)
        }
        try {
            val complete = try {
                fetchComplete(prefix)
            } catch (error: MiApiException) {
                val rejectedSavedRequest = prefix != null && error is MiApiException.Server && error.httpOrBusinessCode in setOf(400, 422)
                val invalidCursor = error is MiApiException.Unexpected && error.paginationIssue == MiPaginationIssue.INVALID_CURSOR
                if (!cursorRestartAvailable || (!rejectedSavedRequest && !invalidCursor)) throw error
                cursorRestartAvailable = false
                checkpoints?.save(current, null)
                fetchComplete(null)
            }
            return if (timestamp == null) complete else complete.filter { current.contains(timestamp(it)) }
        } catch (error: MiApiException.Unexpected) {
            if (error.paginationIssue != MiPaginationIssue.PAGE_LIMIT || !canSplit) throw error
            checkpoints?.save(current, FitnessWindowProgress(split = true))
            return split()
        }
    }
    return read(window, 0)
}

internal val MiApiException.Unexpected.paginationIssue: MiPaginationIssue?
    get() = (cause as? MiPaginationIncomplete)?.issue

/** Share successful reads, but never cache an auth/network failure across its retry. */
internal class SyncReadCache<T> {
    private val guard = Mutex()
    private val locks = mutableMapOf<String, Mutex>()
    private val values = mutableMapOf<String, T>()

    suspend fun clear() = guard.withLock { locks.clear(); values.clear() }

    suspend fun get(key: String, read: suspend () -> T): T {
        val lock = guard.withLock { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val cached = guard.withLock { values[key] }
            if (cached != null) cached else read().also { value -> guard.withLock { values[key] = value } }
        }
    }
}

internal data class SyncWindow(val from: Long, val to: Long) {
    fun contains(timestamp: Long): Boolean = timestamp >= from && timestamp < to
    fun overlaps(start: Long, end: Long): Boolean = end > from && start < to && end > start
    val expandedSleepStart: String get() = Instant.fromEpochSeconds(from - 36 * 3600).toString()

    companion object {
        fun parse(from: String, to: String): SyncWindow {
            fun seconds(value: String) = value.toLongOrNull() ?: Instant.parse(
                if (value.length == 10) "${value}T00:00:00Z" else value,
            ).epochSeconds
            return SyncWindow(seconds(from), seconds(to)).also { require(it.to > it.from) }
        }
    }
}

class BodyImportVerificationException(message: String) : IllegalStateException(message)
