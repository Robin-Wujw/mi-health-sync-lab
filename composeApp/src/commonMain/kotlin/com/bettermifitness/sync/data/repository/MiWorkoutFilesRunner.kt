package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiDirectApi
import com.bettermifitness.sync.data.api.MiWorkoutImportCloud
import com.bettermifitness.sync.data.api.MiWorkoutImportMapper
import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.data.preferences.WorkoutFileStage
import com.bettermifitness.sync.data.preferences.WorkoutImportEntry
import com.bettermifitness.sync.data.preferences.WorkoutImportJournal
import com.bettermifitness.sync.health.HealthRecordIds
import com.bettermifitness.sync.health.InboundWorkout
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.FdsKeys
import com.mifitness.miclient.fds.SportFileTransport
import com.mifitness.miclient.gps.NativeSportFileWriter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Each file body and summary promotion has its own durable attempt and independent readback. */
class MiWorkoutFilesRunner(
    private val cloud: MiWorkoutImportCloud,
    private val journal: WorkoutImportJournal,
    private val transport: SportFileTransport,
    private val originalHeartRate: (suspend (InboundWorkout) -> List<com.bettermifitness.sync.data.api.WorkoutTimedSample>?)? = null,
) {
    constructor(api: () -> MiDirectApi, cloud: MiWorkoutImportCloud, journal: WorkoutImportJournal,
        originalHeartRate: (suspend (InboundWorkout) -> List<com.bettermifitness.sync.data.api.WorkoutTimedSample>?)? = null) :
        this(cloud, journal, FdsClient(api().dataClient()), originalHeartRate)

    data class Result(val verified: Int, val pending: Int, val failures: List<String>, val bodyUploads: Int, val referenceWrites: Int,
        val routesAwaitingConsent: Int = 0)
    private var phase = "PRECHECK"
    private var bodyResult = "UNKNOWN"
    private var allowRecovery = false
    private var bodyUploads = 0
    private var referenceWrites = 0
    private var fileProto = 8
    private var enrichedHeartRate = false

    suspend fun run(account: String, source: List<InboundWorkout>, repairMissingFiles: Boolean = false,
        refreshOutdoorCurve: Boolean = false, blockedSources: Set<String> = emptySet()): Result {
        require(account.isNotBlank())
        allowRecovery = repairMissingFiles
        bodyUploads = 0
        referenceWrites = 0
        var verified = 0
        var pending = 0
        var routesAwaitingConsent = source.count { it.routeConsentRequired }
        val failures = mutableListOf<String>()
        try {
            for (workout in source) {
                val target = MiWorkoutImportMapper.map(workout) ?: continue
                if (target.sourceKey in blockedSources) continue
                if (source.count { it.session.startTime < target.end && it.session.endTime > target.start } != 1) {
                    pending++; continue
                }
                val summary = journal.load(account, target.sourceKey)
                if (summary?.verified != true || summary.target != target) continue
                try {
                    phase = "SUMMARY_PRECHECK"
                    bodyResult = "UNKNOWN"
                    val current = matchingSummary(target)
                    if (current == null) { pending++; continue }
                    journal.rememberSummary(account, target, current)
                    phase = "SOURCE_HEART_RATE"
                    val original = originalHeartRate?.invoke(workout)?.takeIf { it.isNotEmpty() }
                    enrichedHeartRate = original != null
                    var fileSource = if (original != null) workout.copy(
                        session = workout.session.copy(heartRateSeries = original)) else workout
                    phase = "ROUTE_PRECHECK"
                    fileSource = preserveMiWorkoutRoute(fileSource, target, current, transport)
                    if (workout.routeConsentRequired && fileSource.session.route.isNotEmpty()) routesAwaitingConsent--
                    if (refreshOutdoorCurve) require(target.key == "outdoor_running" && original != null)
                    fileProto = when (target.key) {
                        "outdoor_running" -> if (original != null || current["proto_type"] == JsonPrimitive(2)) 2 else 1
                        "indoor_running" -> 3
                        "outdoor_walking", "outdoor_hiking" ->
                            (target.value["proto_type"] as JsonPrimitive).content.toInt()
                        else -> 8
                    }
                    phase = "ENCODE"
                    bodyResult = "UNKNOWN"
                    val files = encodeFiles(fileSource, target)
                    if (files.isEmpty()) continue
                    if (syncFiles(account, target, files, current)) verified += files.size else pending++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    pending++
                    val failure = when (error) {
                        is MiApiException.Server -> "SERVER_${error.httpOrBusinessCode}"
                        is IllegalStateException -> when (error.message) {
                            "Missing FDS upload target" -> "UPLOAD_TARGET_MISSING"
                            "Missing FDS upload URL" -> "UPLOAD_URL_MISSING"
                            "Missing FDS upload key" -> "UPLOAD_KEY_MISSING"
                            "Missing FDS upload method" -> "UPLOAD_METHOD_MISSING"
                            "File readback mismatch" -> "FILE_READBACK_MISMATCH"
                            else -> "STATE_UNCONFIRMED"
                        }
                        else -> error::class.simpleName ?: "UNKNOWN"
                    }
                    failures += "$phase:$failure:BODY_$bodyResult"
                }
            }
        } finally {
            (transport as? FdsClient)?.close()
        }
        return Result(verified, pending, failures.distinct(), bodyUploads, referenceWrites, routesAwaitingConsent)
    }

    private fun encodeFiles(workout: InboundWorkout, target: MiWorkoutImportTarget): Map<Int, ByteArray> {
        // Generic native schema does not require invented altitude/IT fields. The sport identity is retained.
        val files = linkedMapOf<Int, ByteArray>()
        val session = workout.session
        if (session.heartRateSeries.isNotEmpty()) {
            files[FdsKeys.FILE_TYPE_RECORD] = NativeSportFileWriter.heartRate(target.start,
                target.zoneOffsetSeconds / 900, fileProto, session.heartRateSeries.map {
                    NativeSportFileWriter.HeartPoint(it.timeSec, it.value.toInt()) },
                // This file advertises HR only. Keep its structural altitude unknown,
                // so late route permissions/altitude cannot change the HR file identity.
                sessionEndTime = target.end)
        }
        if (session.route.isNotEmpty()) {
            files[FdsKeys.FILE_TYPE_GPS] = NativeSportFileWriter.gps(target.start, target.zoneOffsetSeconds / 900,
                fileProto, session.route.map { NativeSportFileWriter.RoutePoint(it.timeSec, it.latitude, it.longitude) })
        }
        return files
    }

    private suspend fun syncFiles(account: String, target: MiWorkoutImportTarget, files: Map<Int, ByteArray>,
        current: JsonObject): Boolean {
        val digest = HealthRecordIds.version(*files.flatMap { (type, bytes) ->
            listOf(type.toString(), bytes.joinToString(",")) }.toTypedArray()).toString()
        // Each encoder revision has a durable reservation. Timeline v5 retains missing
        // seconds as native invalid-HR slots, replacing owned objects once without journal resets.
        val codecRevision = when {
            fileProto == 2 && target.key == "outdoor_running" -> "timeline-v8-$fileProto-${digest.take(16)}"
            enrichedHeartRate -> "timeline-v7-$fileProto-${digest.take(16)}"
            fileProto in setOf(1, 2, 15) -> "timeline-v6-$fileProto"
            else -> "timeline-v5-$fileProto"
        }
        val fileTarget = target.copy(sourceKey = "FILES:manual-$codecRevision:${target.sourceKey}",
            value = JsonObject(target.value + ("file_digest" to JsonPrimitive(digest))))
        if (journal.hasUnconfirmedFileWrite(account, target, fileTarget.sourceKey)) return false
        phase = "SUMMARY_PRECHECK"
        val previous = journal.load(account, fileTarget.sourceKey)
        if (previous?.target == fileTarget && previous.verified) {
            val states = verifiedBodyStates(target, files)
            if (states.values.all { it == WorkoutFileStage.VERIFIED } && referencesFiles(current)) {
                journal.rememberSummary(account, target, current)
                return true
            }
            if (!journal.reserveFiles(account, fileTarget, previous, states)) return false
            val reserved = checkNotNull(journal.load(account, fileTarget.sourceKey))
            val checked = syncFileBodies(account, target, files, reserved) ?: return false
            return promoteSummary(account, target, fileTarget, checked)
        }
        phase = "RESERVATION"
        val entry = prepareEntry(account, target, fileTarget, files, previous) ?: return false
        val checked = syncFileBodies(account, target, files, entry) ?: return false
        return promoteSummary(account, target, fileTarget, checked)
    }

    private suspend fun verifiedBodyStates(target: MiWorkoutImportTarget, files: Map<Int, ByteArray>): Map<String, WorkoutFileStage> {
        val states = mutableMapOf<String, WorkoutFileStage>()
        for ((type, bytes) in files) {
            suspend fun read(): ByteArray? = try { transport.downloadSportFile(request(target, type)) }
            catch (error: MiApiException.Server) { if (error.httpOrBusinessCode == 404) null else throw error }
            // Only a completed, previously verified write may start a new repair after
            // independently confirming that its object is now missing. Pending attempts never reset.
            val existing = read() ?: read()
            if (existing != null) check(existing.contentEquals(bytes)) { "File readback mismatch" }
            states[type.toString()] = if (existing == null) WorkoutFileStage.NEW else WorkoutFileStage.VERIFIED
        }
        return states
    }

    private suspend fun prepareEntry(account: String, target: MiWorkoutImportTarget, fileTarget: MiWorkoutImportTarget,
        files: Map<Int, ByteArray>, previous: WorkoutImportEntry?): WorkoutImportEntry? {
        if (previous != null && !previous.verified && previous.target != fileTarget) return null
        val states = when {
            previous?.verified == true && previous.target.copy(sid = fileTarget.sid).fingerprint == fileTarget.fingerprint ->
                files.keys.associate { it.toString() to WorkoutFileStage.VERIFIED }
            previous == null || previous.verified -> files.keys.associate { it.toString() to WorkoutFileStage.NEW }
            previous.fileProtocolVersion == 0 -> {
                // Old entries cannot distinguish address failure from a body attempt. Reconcile only.
                for ((type, bytes) in files) check(transport.downloadSportFile(request(target, type)).contentEquals(bytes))
                files.keys.associate { it.toString() to WorkoutFileStage.VERIFIED }
            }
            else -> return previous
        }
        return if (journal.reserveFiles(account, fileTarget, previous, states)) journal.load(account, fileTarget.sourceKey) else null
    }

    private suspend fun syncFileBodies(account: String, target: MiWorkoutImportTarget, files: Map<Int, ByteArray>,
        initial: WorkoutImportEntry): WorkoutImportEntry? {
        var entry = initial
        check(entry.fileProtocolVersion == 2 && entry.fileStates.keys == files.keys.map { it.toString() }.toSet())
        for ((type, bytes) in files) {
            val key = type.toString()
            bodyResult = entry.fileWriteResults[key] ?: "UNKNOWN"
            if (entry.fileStates.getValue(key) == WorkoutFileStage.VERIFIED) continue
            if (entry.fileStates.getValue(key) == WorkoutFileStage.NEW) {
                if (immutableCurve(target)) {
                    // The native app caches this identity even after summary deletion.
                    // A new immutable file identity may be adopted or created, never overwritten.
                    val existing = try { transport.downloadSportFile(request(target, type)) }
                    catch (error: MiApiException.Server) { if (error.httpOrBusinessCode == 404) null else throw error }
                    if (existing != null) {
                        check(existing.contentEquals(bytes)) { "File readback mismatch" }
                        val observed = entry.copy(fileStates = entry.fileStates + (key to WorkoutFileStage.VERIFIED))
                        if (!journal.updateFiles(account, entry, observed)) return null
                        entry = observed
                        continue
                    }
                }
                phase = "FILE_PREPARE"
                val prepared = transport.prepareSportFile(request(target, type))
                val attempted = entry.copy(fileStates = entry.fileStates + (key to WorkoutFileStage.ATTEMPTED))
                if (!journal.updateFiles(account, entry, attempted)) return null
                entry = attempted
                entry = writeFile(account, entry, key, prepared, bytes) ?: return null
            }
            phase = "FILE_READBACK"
            val downloaded = try { transport.downloadSportFile(request(target, type)) }
            catch (error: MiApiException.Server) {
                val eligible = allowRecovery && error.httpOrBusinessCode == 404 && bodyResult != "ACKNOWLEDGED" &&
                    key !in entry.fileRecoveryAttempted
                if (!eligible) throw error
                // Explicit protocol-repair action only: unchanged owned source, independently missing
                // object, and at most one durable repair for this exact file fingerprint.
                val prepared = transport.prepareSportFile(request(target, type))
                val reserved = entry.copy(fileRecoveryAttempted = entry.fileRecoveryAttempted + key)
                if (!journal.updateFiles(account, entry, reserved)) return null
                entry = writeFile(account, reserved, key, prepared, bytes) ?: return null
                phase = "FILE_READBACK"
                transport.downloadSportFile(request(target, type))
            }
            check(downloaded.contentEquals(bytes)) { "File readback mismatch" }
            val checked = entry.copy(fileStates = entry.fileStates + (key to WorkoutFileStage.VERIFIED))
            if (!journal.updateFiles(account, entry, checked)) return null
            entry = checked
        }
        return entry
    }

    private suspend fun writeFile(account: String, entry: WorkoutImportEntry, key: String,
        prepared: FdsClient.UploadMeta, bytes: ByteArray): WorkoutImportEntry? {
        phase = "FILE_WRITE"
        bodyUploads++
        bodyResult = try {
            transport.uploadPreparedSportFile(prepared, bytes)
            "ACKNOWLEDGED"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (error is MiApiException.Server) "HTTP_${error.httpOrBusinessCode}" else "RESPONSE_LOST"
        }
        val outcome = entry.copy(fileWriteResults = entry.fileWriteResults + (key to bodyResult))
        return outcome.takeIf { journal.updateFiles(account, entry, it) }
    }

    private suspend fun promoteSummary(account: String, target: MiWorkoutImportTarget, fileTarget: MiWorkoutImportTarget,
        initial: WorkoutImportEntry): Boolean {
        var entry = initial
        phase = "SUMMARY_REFERENCE"
        // Re-read after file I/O so a concurrent foreign summary edit is never overwritten.
        val latest = matchingSummary(target) ?: return false
        if (!referencesFiles(latest)) {
            if (entry.promotionAttempted) return false
            val attempted = entry.copy(promotionAttempted = true)
            if (!journal.updateFiles(account, entry, attempted)) return false
            entry = attempted
            val promoted = JsonObject(latest + mapOf("version" to JsonPrimitive(1),
                "did" to JsonPrimitive(FILE_SID), "proto_type" to JsonPrimitive(fileProto)))
            referenceWrites++
            try { cloud.upload(target, promoted) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Reconcile the independently read summary below. */ }
        } else if (!entry.promotionAttempted) {
            val observed = entry.copy(promotionAttempted = true)
            if (!journal.updateFiles(account, entry, observed)) return false
        }
        val confirmed = matchingSummary(target)?.takeIf(::referencesFiles) ?: return false
        phase = "JOURNAL_VERIFY"
        journal.verified(account, fileTarget)
        journal.rememberSummary(account, target, confirmed)
        return true
    }

    private suspend fun matchingSummary(target: MiWorkoutImportTarget): JsonObject? {
        val rows = cloud.read(target.start - 86_400, target.end + 86_400).filter { target.overlaps(it) }
        return rows.singleOrNull()?.takeIf { target.matches(it) }?.value
    }

    private fun referencesFiles(value: JsonObject) = value["version"] == JsonPrimitive(1) &&
        value["did"] == JsonPrimitive(FILE_SID) && value["proto_type"] == JsonPrimitive(fileProto)

    private fun immutableCurve(target: MiWorkoutImportTarget) = target.key == "outdoor_running" && fileProto == 2

    private fun request(target: MiWorkoutImportTarget, type: Int) = FdsClient.SportFileRequest(
        FILE_SID, target.start, target.zoneOffsetSeconds / 900, fileProto, type)

    private companion object {
        const val FILE_SID = "xiaomiwear_app_manually"
    }
}
