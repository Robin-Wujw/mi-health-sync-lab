package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.FitnessResponse
import com.bettermifitness.sync.data.api.FitnessResult
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
    fetch: suspend (String?) -> FitnessResponse<T>,
): List<T> {
    require(maxPages > 0)
    val all = mutableListOf<T>()
    val seen = mutableSetOf<String>()
    var token: String? = null
    repeat(maxPages) {
        val result = fetch(token).checkedResult()
        all += result.dataList
        if (!result.hasMore) return all
        val next = result.nextKey?.takeIf { it.isNotBlank() }
            ?: throw MiApiException.Unexpected("Mi cloud reports more history but returned no next page")
        if (!seen.add(next)) throw MiApiException.Unexpected("Mi cloud repeated a history page; sync stopped before duplicating it")
        token = next
    }
    throw MiApiException.Unexpected("History exceeds the page limit; select a shorter sync range")
}

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
