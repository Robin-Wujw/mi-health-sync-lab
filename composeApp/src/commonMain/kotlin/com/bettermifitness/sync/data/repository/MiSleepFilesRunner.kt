package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiHealthImportCloud
import com.bettermifitness.sync.data.api.MiHealthImportChannel
import com.bettermifitness.sync.data.api.MiHealthImportIssue
import com.bettermifitness.sync.data.api.MiHealthImportPlan
import com.bettermifitness.sync.data.api.MiHealthImportRow
import com.bettermifitness.sync.data.api.MiSleepVitalsFile
import com.bettermifitness.sync.data.preferences.HealthImportJournal
import com.bettermifitness.sync.data.preferences.HealthImportLedger
import com.bettermifitness.sync.data.preferences.SleepFileEntry
import com.bettermifitness.sync.data.preferences.SleepFileJournal
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.FdsKeys
import com.mifitness.miclient.fds.SportFileTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class MiSleepFilesRunner(private val cloud: MiHealthImportCloud, private val journal: SleepFileJournal,
    private val imports: HealthImportJournal, private val transport: SportFileTransport) {

    suspend fun prepare(account: String, plan: MiHealthImportPlan): MiHealthImportPlan {
        require(account.isNotBlank())
        val files = plan.sleepFiles.associateBy { it.identity }
        val issues = plan.issues.toMutableList()
        val rows = mutableListOf<MiHealthImportRow>()
        try {
            for (row in plan.rows) {
                val file = files[row.identity]
                if (file == null) { rows += row; continue }
                var phase = "TARGET_READ"
                try {
                    val reference = prepareFile(account, row, file) { phase = it }
                    rows += row.copy(value = JsonObject(row.value + mapOf("version" to JsonPrimitive(2),
                        "protoTime" to JsonPrimitive(reference))))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (expired: MiApiException.AuthExpired) { throw expired }
                catch (failure: Exception) {
                    val reason = when (failure) {
                        is MiApiException.Server -> "SERVER_${failure.httpOrBusinessCode}"
                        is MiApiException.Unexpected -> when (failure.message) {
                            "gen_download_url: missing requested entry" -> "MISSING_ENTRY"
                            "gen_download_url: missing obj_key" -> "MISSING_KEY"
                            "gen_download_url: missing url" -> "MISSING_URL"
                            else -> "PROTOCOL"
                        }
                        is IllegalArgumentException -> "INVALID"
                        is IllegalStateException -> "CHECK"
                        else -> "UNAVAILABLE"
                    }
                    issues += MiHealthImportIssue("SLEEP_FILE_${phase}_$reason", row.sourceRecordKeys,
                        "Sleep file preparation stopped at a bounded diagnostic phase.",
                        com.bettermifitness.sync.data.api.MiHealthImportIssueSeverity.WARNING)
                    issues += MiHealthImportIssue("SLEEP_FILE_UNVERIFIED", row.sourceRecordKeys,
                        "Sleep file persistence is not confirmed; its summary was not submitted.")
                }
            }
        } finally { (transport as? FdsClient)?.close() }
        return plan.copy(rows = rows, issues = issues.distinct(), sleepFiles = emptyList())
    }

    private suspend fun prepareFile(account: String, row: MiHealthImportRow, file: MiSleepVitalsFile, phase: (String) -> Unit): Long {
        val identity = row.identity.stableKey
        val before = cloud.readRows(MiHealthImportChannel.RAW, "sleep", row.identity.time, row.identity.time + 1)
            .filterNot { row.identity.sid == com.bettermifitness.sync.data.api.MI_SLEEP_FILE_IMPORT_SID &&
                it.sid == com.bettermifitness.sync.data.api.MI_HEALTH_CONNECT_IMPORT_SID && it.deleted }
        phase("TARGET_OWNERSHIP")
        check(before.none { it.deleted || it.sid != row.identity.sid || it.zoneOffsetSeconds != row.zoneOffsetSeconds })
        check(before.distinctBy { it.value }.size <= 1)
        val pending = identity in imports.pendingIdentities(account, setOf(identity))
        val history = HealthImportLedger.identityDigest(identity) in imports.verifiedFingerprints(account)
        check(before.isNotEmpty() || !history && !pending)
        phase("ENCODE")
        val content = FdsKeys.contentFingerprint(file.encode(file.end))
        phase("JOURNAL_READ")
        var entry = journal.load(account, identity)
        if (entry != null && entry.content != content && !entry.verified && entry.attempted) {
            phase("PREVIOUS_READ")
            val old = checkNotNull(read(request(row, entry.referenceTime)))
            check(FdsKeys.contentFingerprint(old) == entry.body)
            val checked = entry.copy(verified = true)
            check(journal.change(account, identity, entry, checked))
            entry = checked
        }
        var reusable: SleepFileEntry? = null
        if (entry?.content == content) {
            phase("EXISTING_READ")
            val observed = read(request(row, entry.referenceTime))
            if (observed != null) {
                check(FdsKeys.contentFingerprint(observed) == entry.body)
                if (!entry.verified) check(journal.change(account, identity, entry, entry.copy(verified = true)))
                return entry.referenceTime
            }
            if (!entry.verified && !entry.attempted) reusable = entry else {
                check(entry.verified && !pending)
                check(read(request(row, entry.referenceTime)) == null)
            }
        }
        phase("RESERVE")
        check(!pending)
        val generation = reusable?.generation ?: ((entry?.generation ?: 0) + 1)
        require(generation in 1..10_000)
        val referenceTime = reusable?.referenceTime ?: (file.end + generation)
        val bytes = file.encode(referenceTime)
        val reserved = reusable ?: SleepFileEntry(content, FdsKeys.contentFingerprint(bytes), referenceTime, generation)
            .also { check(journal.change(account, identity, entry, it)) }
        val request = request(row, referenceTime)
        phase("INITIAL_READ")
        val existing = read(request)
        if (existing != null) {
            check(existing.contentEquals(bytes))
            check(journal.change(account, identity, reserved, reserved.copy(verified = true)))
            return referenceTime
        }
        phase("UPLOAD_ADDRESS")
        val prepared = transport.prepareSportFile(request)
        val attempted = reserved.copy(attempted = true)
        check(journal.change(account, identity, reserved, attempted))
        try { transport.uploadPreparedSportFile(prepared, bytes) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (expired: MiApiException.AuthExpired) { throw expired }
        catch (_: Exception) { }
        phase("UPLOAD_READBACK")
        check(checkNotNull(read(request)).contentEquals(bytes))
        check(journal.change(account, identity, attempted, attempted.copy(verified = true)))
        return referenceTime
    }

    private fun request(row: MiHealthImportRow, time: Long) = FdsClient.SportFileRequest(
        row.identity.sid, time, row.zoneOffsetSeconds / 900, 8, FdsKeys.FILE_TYPE_RECORD, daily = true)

    private suspend fun read(request: FdsClient.SportFileRequest): ByteArray? = try { transport.downloadSportFile(request) }
    catch (error: MiApiException.Server) { if (error.httpOrBusinessCode == 404) null else throw error }
}
