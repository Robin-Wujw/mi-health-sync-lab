package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiWorkoutImportCloud
import com.bettermifitness.sync.data.api.MiWorkoutImportMapper
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.data.api.MiWorkoutExperimentCloudRow
import com.bettermifitness.sync.data.api.canonicalMiHealthImportJson
import com.bettermifitness.sync.data.preferences.WorkoutImportJournal
import com.bettermifitness.sync.data.preferences.WorkoutRestoreEntry
import com.bettermifitness.sync.data.preferences.WorkoutRestoreJournal
import com.bettermifitness.sync.health.InboundWorkout
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.SportFileTransport
import com.mifitness.miclient.gps.NativeSportFileWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.io.encoding.Base64

enum class WorkoutRestoreStatus { PREPARED, NOT_DELETED, VERIFIED, PENDING, CONFLICT, SOURCE_CHANGED, NO_BACKUP }

/** Retains and reconciles previously prepared backups without resetting uncertain attempts. */
class MiWorkoutRestoreRunner(
    private val cloud: MiWorkoutImportCloud,
    private val ownership: WorkoutImportJournal,
    private val journal: WorkoutRestoreJournal,
    private val files: SportFileTransport,
    private val settleDeletion: suspend () -> Unit = { delay(1_000) },
) {
    suspend fun prepare(account: String, source: InboundWorkout, allSources: List<InboundWorkout>,
        fileSource: InboundWorkout = source): WorkoutRestoreEntry {
        val target = checkNotNull(MiWorkoutImportMapper.map(source))
        check(eligible(account, target, allSources))
        val previous = journal.load(account)
        check(previous == null || previous.verified || !previous.summaryAttempted && previous.fileAttempts.isEmpty())
        val rows = read(target)
        check(rows.isEmpty() || rows.size == 1 && target.matches(rows.single()))
        val full = rows.singleOrNull()?.value
        val proto = if (full != null) (full["proto_type"] as? JsonPrimitive)?.intOrNull
            ?: error("Unknown workout protocol") else protocol(target)
        check(fileSource.session.startTime == target.start && fileSource.session.endTime == target.end)
        val bodies = if (full != null) snapshotFiles(fileSource, target, full, proto) else encodeSource(fileSource, target, proto)
        check(bodies.values.sumOf { it.size.toLong() } <= 8_000_000L)
        val value = full ?: if (bodies.isEmpty()) target.value else JsonObject(target.value + mapOf(
            "version" to JsonPrimitive(1), "did" to JsonPrimitive(target.sid), "proto_type" to JsonPrimitive(proto)))
        // The live summary/source must still agree after downloading the backup.
        check(read(target) == rows && eligible(account, target, allSources))
        val entry = WorkoutRestoreEntry(target, value, bodies.mapKeys { it.key.toString() }
            .mapValues { Base64.encode(it.value) }, proto, full != null)
        check(journal.replace(account, previous, entry))
        return entry
    }

    suspend fun restore(account: String, allSources: List<InboundWorkout>): WorkoutRestoreStatus {
        val entry = journal.load(account) ?: return WorkoutRestoreStatus.NO_BACKUP
        if (!eligible(account, entry.target, allSources)) return WorkoutRestoreStatus.SOURCE_CHANGED
        precheck(entry)?.let { return it }
        val checked = restoreFiles(account, entry) ?: return WorkoutRestoreStatus.PENDING
        return restoreSummary(account, checked, allSources)
    }

    private suspend fun precheck(entry: WorkoutRestoreEntry): WorkoutRestoreStatus? {
        val before = read(entry.target)
        if (entry.verified) return if (matches(entry, before) && filesMatch(entry)) WorkoutRestoreStatus.VERIFIED
            else WorkoutRestoreStatus.CONFLICT
        if (before.isNotEmpty() && !matches(entry, before)) return WorkoutRestoreStatus.CONFLICT
        if (before.isNotEmpty() && !entry.summaryAttempted) return WorkoutRestoreStatus.NOT_DELETED
        // Require two completed absence reads after the user's explicit deletion.
        if (before.isEmpty()) {
            if (entry.summaryAttempted) return WorkoutRestoreStatus.PENDING
            settleDeletion()
            if (read(entry.target).isNotEmpty()) return WorkoutRestoreStatus.NOT_DELETED
        }
        return null
    }

    private suspend fun restoreSummary(account: String, initial: WorkoutRestoreEntry,
        allSources: List<InboundWorkout>): WorkoutRestoreStatus {
        var entry = initial
        val finalRead = read(entry.target)
        if (entry.summaryAttempted) {
            if (!matches(entry, finalRead)) return WorkoutRestoreStatus.PENDING
        } else {
            if (finalRead.isNotEmpty()) return WorkoutRestoreStatus.NOT_DELETED
            if (!eligible(account, entry.target, allSources)) return WorkoutRestoreStatus.SOURCE_CHANGED
            val attempted = entry.copy(summaryAttempted = true)
            if (!journal.replace(account, entry, attempted)) return WorkoutRestoreStatus.CONFLICT
            entry = attempted
            try { cloud.upload(entry.target, entry.value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Readback determines success; the reservation is never reset. */ }
            val after = try { read(entry.target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return WorkoutRestoreStatus.PENDING }
            if (!matches(entry, after)) return WorkoutRestoreStatus.PENDING
        }
        if (!filesMatch(entry)) return WorkoutRestoreStatus.PENDING
        return if (journal.replace(account, entry, entry.copy(verified = true))) WorkoutRestoreStatus.VERIFIED
            else WorkoutRestoreStatus.CONFLICT
    }

    private suspend fun eligible(account: String, target: MiWorkoutImportTarget, source: List<InboundWorkout>): Boolean {
        val owned = ownership.load(account, target.sourceKey)
        val mapped = source.mapNotNull { MiWorkoutImportMapper.map(it) }
        return target.sid == "xiaomiwear_app_manually" && owned?.verified == true && owned.target == target &&
            mapped.count { it == target } == 1 && source.count {
                it.session.startTime < target.end && it.session.endTime > target.start } == 1
    }

    private suspend fun read(target: MiWorkoutImportTarget) = cloud.read(target.start - 86_400, target.end + 86_400)
        .filter { target.overlaps(it) }

    private fun matches(entry: WorkoutRestoreEntry, rows: List<MiWorkoutExperimentCloudRow>): Boolean =
        rows.singleOrNull()?.let { entry.target.sameIdentity(it) &&
            canonicalMiHealthImportJson(it.value) == canonicalMiHealthImportJson(entry.value) } == true

    private suspend fun snapshotFiles(source: InboundWorkout, target: MiWorkoutImportTarget,
        value: JsonObject, proto: Int): Map<Int, ByteArray> {
        if (value["version"] == JsonPrimitive(0)) return emptyMap()
        check(value["version"] == JsonPrimitive(1) && value["did"] == JsonPrimitive(target.sid))
        val result = mutableMapOf<Int, ByteArray>()
        for (type in listOf(0, 2)) {
            val expected = if (type == 0) source.session.heartRateSeries.isNotEmpty() else source.session.route.isNotEmpty()
            try { result[type] = files.downloadSportFile(request(target, proto, type)) }
            catch (error: MiApiException.Server) { if (error.httpOrBusinessCode != 404 || expected) throw error }
        }
        check(result.isNotEmpty())
        return result
    }

    private suspend fun restoreFiles(account: String, initial: WorkoutRestoreEntry): WorkoutRestoreEntry? {
        var entry = initial
        for ((type, encoded) in entry.files) {
            val bytes = Base64.decode(encoded)
            val req = request(entry.target, entry.protocol, type.toInt())
            val existing = try { files.downloadSportFile(req) }
            catch (error: MiApiException.Server) { if (error.httpOrBusinessCode == 404) null else throw error }
            if (existing != null) { check(existing.contentEquals(bytes)); continue }
            if (type in entry.fileAttempts || entry.summaryAttempted) return null
            val prepared = files.prepareSportFile(req)
            val attempted = entry.copy(fileAttempts = entry.fileAttempts + type)
            if (!journal.replace(account, entry, attempted)) return null
            entry = attempted
            try { files.uploadPreparedSportFile(prepared, bytes) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A possibly applied body is read back, never blindly replayed. */ }
            val after = try { files.downloadSportFile(req) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
            check(after.contentEquals(bytes))
        }
        return entry
    }

    private suspend fun filesMatch(entry: WorkoutRestoreEntry): Boolean {
        for ((type, encoded) in entry.files) {
            val actual = try { files.downloadSportFile(request(entry.target, entry.protocol, type.toInt())) }
            catch (error: MiApiException.Server) { if (error.httpOrBusinessCode == 404) return false else throw error }
            if (!actual.contentEquals(Base64.decode(encoded))) return false
        }
        return true
    }

    private fun request(target: MiWorkoutImportTarget, proto: Int, type: Int) =
        FdsClient.SportFileRequest(target.sid, target.start, target.zoneOffsetSeconds / 900, proto, type)

    private fun protocol(target: MiWorkoutImportTarget): Int = when (target.key) {
        "indoor_running" -> 3
        "outdoor_running", "outdoor_walking", "outdoor_hiking" ->
            (target.value.getValue("proto_type") as JsonPrimitive).content.toInt()
        else -> 8
    }

    private fun encodeSource(source: InboundWorkout, target: MiWorkoutImportTarget, proto: Int): Map<Int, ByteArray> = buildMap {
        if (source.session.heartRateSeries.isNotEmpty()) put(0, NativeSportFileWriter.heartRate(target.start,
            target.zoneOffsetSeconds / 900, proto, source.session.heartRateSeries.map {
                NativeSportFileWriter.HeartPoint(it.timeSec, it.value.toInt()) }, sessionEndTime = target.end))
        if (source.session.route.isNotEmpty()) put(2, NativeSportFileWriter.gps(target.start, target.zoneOffsetSeconds / 900,
            proto, source.session.route.map { NativeSportFileWriter.RoutePoint(it.timeSec, it.latitude, it.longitude) }))
    }
}
