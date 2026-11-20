package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.HeartRateEntry
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Path

data class SyncPageScope(val id: String, val generation: Long)

class FitnessPageCache(
    private val directory: Path,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var epoch = 0L
    private var writes = 0L

    internal suspend fun lease(): Long = access(-1L) { epoch }
    internal suspend fun revision(): Long = access(-1L) { writes }

    internal fun bind(lease: Long, scope: SyncPageScope, account: String, host: String, stream: String, root: SyncWindow):
        FitnessWindowCheckpoints<HeartRateEntry> = bind(lease, scope, account, host, stream, root, HeartRateEntry.serializer())

    internal fun <T> bind(lease: Long, scope: SyncPageScope, account: String, host: String, stream: String, root: SyncWindow,
        rowSerializer: KSerializer<T>): FitnessWindowCheckpoints<T> {
        val prefix = scopePrefix(scope)
        val format = StoredPrefix.serializer(rowSerializer)
        fun path(window: SyncWindow) = directory / (prefix + digest(
            "$account\n$host\n$stream\n${root.from}\n${root.to}\n${window.from}\n${window.to}",
        ) + ".json")
        return object : FitnessWindowCheckpoints<T> {
            override suspend fun load(window: SyncWindow): FitnessWindowProgress<T>? = access(null) {
                if (lease != epoch) return@access null
                read(path(window), format)?.let { saved ->
                    FitnessWindowProgress(saved.cursors.takeIf { it.isNotEmpty() }?.let { FitnessPagePrefix(saved.rows, it) },
                        saved.split)
                }
            }

            override suspend fun save(window: SyncWindow, progress: FitnessWindowProgress<T>?) = access(Unit) {
                if (lease != epoch) return@access
                val target = path(window)
                if (progress == null) {
                    fs.delete(target)
                } else {
                    val saved = StoredPrefix(progress.prefix?.rows.orEmpty(), progress.prefix?.cursors.orEmpty(), progress.split)
                    write(target, saved, format)
                }
            }
        }
    }

    internal suspend fun prepare() = access(Unit) { prune() }

    internal suspend fun discard(scope: SyncPageScope) = access(Unit) {
        val prefix = scopePrefix(scope)
        entries().filter { it.name.startsWith(prefix) }.forEach { fs.delete(it) }
    }

    internal suspend fun clear() = access(Unit) {
        epoch++
        entries().forEach { fs.delete(it) }
    }

    private suspend fun <T> access(fallback: T, action: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { action() } catch (_: IOException) { fallback }
        }
    }

    private fun <T> read(path: Path, format: KSerializer<StoredPrefix<T>>): StoredPrefix<T>? {
        val meta = fs.metadataOrNull(path) ?: return null
        if (!fresh(meta.lastModifiedAtMillis) || (meta.size ?: Long.MAX_VALUE) > MAX_ENTRY_BYTES) {
            fs.delete(path)
            return null
        }
        return try { fs.read(path) { Json.decodeFromString(format, readUtf8()) } }
        catch (_: IllegalArgumentException) { fs.delete(path); null }
    }

    private fun <T> write(path: Path, saved: StoredPrefix<T>, format: KSerializer<StoredPrefix<T>>) {
        val bytes = try { Json.encodeToString(format, saved).encodeToByteArray() } catch (_: IllegalArgumentException) { return }
        if (bytes.size > MAX_ENTRY_BYTES || saved.cursors.any { it.length > 4_096 }) {
            return
        }
        val previous = read(path, format)
        if (previous == saved) return
        fs.createDirectories(directory)
        prune(replacing = path, extra = bytes.size.toLong())
        val temporary = directory / (path.name + ".tmp")
        try {
            fs.write(temporary) { write(bytes) }
            fs.atomicMove(temporary, path)
            if (saved.split || saved.cursors.size > (previous?.cursors?.size ?: 0)) writes++
        } finally {
            fs.delete(temporary)
        }
    }

    private fun entries() = fs.listOrNull(directory).orEmpty().filter { it.name.startsWith("p1-") }

    private fun prune(replacing: Path? = null, extra: Long = 0) {
        var size = extra
        val entries = entries().filter { it != replacing }.mapNotNull { path ->
            fs.metadataOrNull(path)?.let { path to it }
        }.sortedByDescending { (_, meta) -> meta.lastModifiedAtMillis ?: Long.MIN_VALUE }
        for ((path, meta) in entries) {
            val bytes = meta.size ?: MAX_TOTAL_BYTES
            if (!path.name.endsWith(".json") || !fresh(meta.lastModifiedAtMillis) || bytes > MAX_ENTRY_BYTES ||
                size + bytes > MAX_TOTAL_BYTES) {
                fs.delete(path)
            } else size += bytes
        }
    }

    private fun fresh(modified: Long?): Boolean = modified != null && nowMillis() - modified in 0..TTL_MILLIS
    private fun scopePrefix(scope: SyncPageScope) = "p1-${digest("${scope.generation}\n${scope.id}")}-"
    private fun digest(value: String) = value.encodeUtf8().sha256().hex()

    @Serializable
    private data class StoredPrefix<T>(val rows: List<T>, val cursors: List<String>, val split: Boolean)

    private companion object {
        const val TTL_MILLIS = 3 * 60 * 60_000L
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024L
        const val MAX_TOTAL_BYTES = 24 * 1024 * 1024L
    }
}
