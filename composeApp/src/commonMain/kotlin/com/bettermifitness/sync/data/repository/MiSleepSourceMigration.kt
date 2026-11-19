package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.HealthRecordIds
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.SportFileTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** One source for the sleep summary and its FDS files. Daily aggregates keep their existing identity. */
internal fun MiHealthImportPlan.withNativeSleepSource(): MiHealthImportPlan {
    fun native(identity: MiHealthImportIdentity) = if (identity.channel == MiHealthImportChannel.RAW &&
        identity.key == "sleep" && identity.sid == MI_HEALTH_CONNECT_IMPORT_SID)
        identity.copy(sid = MI_SLEEP_FILE_IMPORT_SID) else identity
    return copy(rows = rows.map { it.copy(identity = native(it.identity)) },
        sleepFiles = sleepFiles.map { it.copy(identity = native(it.identity)) })
}

/** Ordinary sync migrates only exact, owned legacy summaries, after verifying the complete replacement. */
internal class MiSleepSourceMigration(
    private val cloud: MiHealthImportCloud,
    private val imports: HealthImportJournal,
    private val files: SleepFileJournal,
    private val migrations: SleepSourceMigrationJournal,
    private val transport: () -> SportFileTransport,
) {
    suspend fun maintain(account: String, plan: MiHealthImportPlan): MiHealthImportPlan {
        var candidates = 0
        var replacements = 0
        var retirements = 0
        var verifiedMigrations = 0
        val result = mutableListOf<MiHealthImportRow>()
        val issues = plan.issues.toMutableList()
        for (row in plan.rows) {
            if (row.identity.channel != MiHealthImportChannel.RAW || row.identity.key != "sleep" ||
                row.identity.sid != MI_SLEEP_FILE_IMPORT_SID) { result += row; continue }
            try {
                val observed = read(row)
                check(observed.none { !it.deleted && it.sid !in setOf(MI_HEALTH_CONNECT_IMPORT_SID, MI_SLEEP_FILE_IMPORT_SID) })
                val legacy = observed.filter { !it.deleted && it.sid == MI_HEALTH_CONNECT_IMPORT_SID }
                    .distinctBy { canonicalMiHealthImportJson(it.value) to it.zoneOffsetSeconds }
                check(legacy.size <= 1)
                val old = legacy.singleOrNull()
                val legacyIdentity = row.identity.copy(sid = MI_HEALTH_CONNECT_IMPORT_SID)
                var archive = migrations.load(account, legacyIdentity.stableKey)
                check(archive?.verified != true || old == null)
                if (old != null) {
                    val fingerprint = HealthRecordIds.version(canonicalMiHealthImportJson(old.value)).toString()
                    val legacyDigest = HealthImportLedger.identityDigest(legacyIdentity.stableKey)
                    check(imports.ownedVerifiedFingerprints(account)[legacyDigest] == fingerprint)
                    check(imports.pendingIdentities(account, setOf(legacyIdentity.stableKey)).isEmpty())
                    check(old.zoneOffsetSeconds == row.zoneOffsetSeconds)
                    val oldFile = files.load(account, legacyIdentity.stableKey)
                    check(oldFile == null || !oldFile.attempted || oldFile.verified)
                    val original = SleepSourceMigrationEntry(canonicalMiHealthImportJson(old.value), row.zoneOffsetSeconds)
                    if (archive == null) {
                        check(migrations.change(account, legacyIdentity.stableKey, null, original))
                        archive = original
                    }
                    check(archive.originalValue == original.originalValue && archive.zoneOffsetSeconds == original.zoneOffsetSeconds)
                }
                if (archive != null && !archive.verified) candidates++
                // Preserve extensions such as HRV. A file revision replaces only observations supplied this time.
                val enriched = if (old == null) row else row.copy(value = JsonObject(
                    old.value.filterKeys { it != "is_uncomplete" || it in row.value } + row.value))
                val scopedCloud = if (old == null) cloud else object : MiHealthImportCloud by cloud {
                    override suspend fun readRows(channel: MiHealthImportChannel, key: String, fromEpochSeconds: Long,
                        untilEpochSeconds: Long) = cloud.readRows(channel, key, fromEpochSeconds, untilEpochSeconds)
                        .filterNot { channel == MiHealthImportChannel.RAW && it.key == "sleep" && it.time == row.identity.time &&
                            it.sid == MI_HEALTH_CONNECT_IMPORT_SID && !it.deleted &&
                            canonicalMiHealthImportJson(it.value) == archive?.originalValue &&
                            it.zoneOffsetSeconds == archive?.zoneOffsetSeconds }
                }
                val one = plan.copy(rows = listOf(enriched), issues = emptyList(),
                    sleepFiles = plan.sleepFiles.filter { it.identity == row.identity })
                val prepared = if (one.sleepFiles.isEmpty()) one else
                    MiSleepFilesRunner(scopedCloud, files, imports, transport()).prepare(account, one)
                issues += prepared.issues
                val ready = prepared.rawRows.singleOrNull() ?: continue
                if (archive != null && !archive.verified) {
                    // This is the same normal runner, with its durable summary journal and independent readback.
                    try {
                        replacements += MiHealthImportWorkflow(scopedCloud, imports).run(account, prepared)
                            .diagnostic?.attemptedRawRows ?: 0
                    } catch (e: MiHealthImportRunException) {
                        replacements += e.diagnostic?.attemptedRawRows ?: 0
                        throw e
                    }
                    check(verified(ready, read(ready)))
                    val freshOld = read(ready).filter { !it.deleted && it.sid == MI_HEALTH_CONNECT_IMPORT_SID }
                    if (freshOld.isNotEmpty()) {
                        check(freshOld.distinctBy { canonicalMiHealthImportJson(it.value) to it.zoneOffsetSeconds }.size == 1)
                        check(freshOld.all { canonicalMiHealthImportJson(it.value) == archive.originalValue &&
                            it.zoneOffsetSeconds == archive.zoneOffsetSeconds })
                        // An unknown retirement is reconciled by reads; changing the source never authorizes a replay.
                        check(!archive.attempted)
                        val attempted = archive.copy(attempted = true)
                        check(migrations.change(account, legacyIdentity.stableKey, archive, attempted))
                        archive = attempted
                        retirements++
                        try { cloud.retireLegacySleep(legacyIdentity) }
                        catch (e: CancellationException) { throw e }
                        catch (e: MiApiException.AuthExpired) { throw e }
                        catch (_: Exception) { }
                    }
                    val after = read(ready)
                    check(after.none { !it.deleted && it.sid == MI_HEALTH_CONNECT_IMPORT_SID } && verified(ready, after))
                    check(migrations.change(account, legacyIdentity.stableKey, archive, archive.copy(verified = true)))
                    verifiedMigrations++
                } else check(old == null) // A completed migration never silently retires a recreated legacy row.
                result += ready
            } catch (e: CancellationException) { throw e }
            catch (e: MiApiException.AuthExpired) { throw e }
            catch (_: Exception) {
                issues += MiHealthImportIssue("SLEEP_SOURCE_MIGRATION_UNVERIFIED", row.sourceRecordKeys,
                    "The native-source sleep replacement or legacy retirement has not been independently verified.")
            }
        }
        return plan.copy(rows = result, issues = issues.distinct(), sleepFiles = emptyList(),
            sleepSourceMigration = if (candidates == 0) null else
                MiSleepSourceMigrationDiagnostic(candidates, replacements, retirements, verifiedMigrations))
    }

    private suspend fun read(row: MiHealthImportRow) = cloud.readRows(MiHealthImportChannel.RAW, "sleep",
        row.identity.time, row.identity.time + 1)
    private fun verified(row: MiHealthImportRow, rows: List<MiHealthImportCloudRow>) =
        MiHealthImportVerifier.verify(row, rows).status == MiHealthImportVerificationStatus.VERIFIED
}
