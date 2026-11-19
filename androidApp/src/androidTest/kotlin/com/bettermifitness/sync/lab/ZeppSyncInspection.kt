package com.bettermifitness.sync.lab

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.*
import com.bettermifitness.sync.data.preferences.*
import com.bettermifitness.sync.health.HealthRecordIds
import com.bettermifitness.sync.zepp.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.koin.core.Koin

/** Same service as foreground/background sync, restricted to the persisted Zepp selection. */
internal suspend fun syncSelectedZepp(koin: Koin, from: Long, until: Long): String {
    val prefs = koin.get<SyncPreferences>()
    check(SyncPreferences.ZEPP_CLOUD_IMPORT_KEY in prefs.enabledMetrics.first())
    val account = checkNotNull(koin.get<TokenStore>().miUserId.first())
    val result = koin.get<ZeppSyncService>().sync(account, from, until)
    return buildList {
        add("zepp_sync.shared_production_service=true")
        add("zepp_sync.persisted_selection_unchanged=true")
        add("zepp_sync.verified=${result.verified}")
        add("zepp_sync.unresolved=${result.unresolved}")
        add("zepp_sync.source_invalid=${result.source.invalidItems}")
        add("zepp_sync.source_incomplete=${result.source.incomplete.size}")
        add("zepp_sync.mapping_excluded=${result.excluded}")
        for ((name, rows) in listOf("raw" to result.rows, "workout_scores" to result.workouts)) {
            add("zepp_sync.$name.verified=${rows.verified}")
            // Runner.uploaded counts new verified writes, not HTTP mutation attempts.
            add("zepp_sync.$name.newly_verified=${rows.uploaded}")
            add("zepp_sync.$name.pending=${rows.pending}")
            add("zepp_sync.$name.conflicts=${rows.conflicts}")
            add("zepp_sync.$name.skipped=${rows.skipped}")
        }
        add("zepp_sync.complete=${result.unresolved == 0}")
    }.joinToString("\n")
}

/** Test APK only. Reports counts, never account IDs, timestamps, fingerprints or samples. */
internal suspend fun inspectZeppSync(koin: Koin, instrumentation: Instrumentation, from: Long, until: Long,
    restoreDesktopOwnership: Boolean = false): String {
    val account = checkNotNull(koin.get<TokenStore>().miUserId.first())
    val session = checkNotNull(koin.get<ZeppSessionStore>().load())
    val prefs = koin.get<SyncPreferences>()
    val source = koin.get<ZeppCloudClient>().fetch(session, from, until, prefs.zeppMetrics.first())
    val plan = MiZeppScoreMapper.map(source.observations, TimeZone.currentSystemDefault())
    val namespace = "$account:zepp:${HealthRecordIds.version(session.userId)}"
    val ledger = koin.get<HealthImportLedger>()
    val cloud = MiDirectHealthImportCloud(koin.get<MiSessionManager>().api)
    val receipts = if (restoreDesktopOwnership) {
        val encoded = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cat /data/local/tmp/healthlab-score-owned.json")).bufferedReader().use { it.readText() }
        check(encoded.length <= 300_000)
        Json.parseToJsonElement(encoded).jsonObject.also {
            check(it["version"]?.jsonPrimitive?.intOrNull == 1)
            check(it["account_digest"]?.jsonPrimitive?.content == HealthRecordIds.version("score-receipt-mi", account).toString())
            check(it["source_digest"]?.jsonPrimitive?.content == HealthRecordIds.version("score-receipt-zepp", session.userId).toString())
        }.getValue("rows").jsonObject
    } else null
    val lines = mutableListOf("zepp_check.health_uploads=0", "zepp_check.source_invalid=${source.invalidItems}",
        "zepp_check.source_incomplete=${source.incomplete.size}", "zepp_check.mapping_excluded=${plan.excluded}")
    lines += "zepp_check.original_heart_rate_selected=" + (ZeppSyncMetric.WORKOUT_HEART_RATE in prefs.zeppMetrics.first())
    for ((key, rows) in plan.rows.groupBy { it.identity.key }) {
        val observed = cloud.readRows(MiHealthImportChannel.RAW, key,
            rows.minOf { it.identity.time } - 60, rows.maxOf { it.identity.time } + 61)
        val pending = ledger.pendingIdentities(namespace, rows.map { it.identity.stableKey }.toSet())
        val snapshots = ledger.pendingSnapshots(namespace, pending)
        val owned = ledger.ownedVerifiedFingerprints(namespace)
        var exact=0; var missing=0; var pendingExact=0; var pendingOld=0; var pendingChanged=0; var foreign=0; var restored=0; var zones=0
        var missingOffMinute=0; var minuteAliases=0; var oldPending=0
        val missingRows=mutableListOf<MiHealthImportRow>()
        for (row in rows) {
            val same = observed.filter { !it.deleted && it.time == row.identity.time }
            val actual = same.singleOrNull()
            val entry = HealthImportJournalEntry(row.identity.stableKey,
                HealthRecordIds.version("zepp-score-v1",row.canonicalContent,row.zoneOffsetSeconds).toString(),row.sourceRecordKeys,
                zoneOffsetSeconds=row.zoneOffsetSeconds)
            val actualFingerprint = actual?.let {
                HealthRecordIds.version("zepp-score-v1",canonicalMiHealthImportJson(it.value),it.zoneOffsetSeconds).toString()
            }
            val matches = actual?.sid==row.identity.sid && actual.tag==null && actualFingerprint==entry.contentFingerprint
            if(matches) exact++
            if(actual==null) {
                missing++;missingRows+=row
                if(row.identity.time%60!=0L) missingOffMinute++
                if(observed.any { it.sid==row.identity.sid && kotlin.math.abs(it.time-row.identity.time)<60 &&
                    canonicalMiHealthImportJson(it.value)==row.canonicalContent }) minuteAliases++
            }
            if(actual!=null && actual.zoneOffsetSeconds!=row.zoneOffsetSeconds) zones++
            val snapshot=snapshots[row.identity.stableKey]
            if(snapshot!=null) {
                if(matches) pendingExact++
                if(actualFingerprint==snapshot.contentFingerprint) pendingOld++
                if(!snapshot.matches(entry)) pendingChanged++
                val reservedAt=snapshot.pendingToken.substringBefore('|').toLongOrNull()
                if(reservedAt!=null && kotlin.time.Clock.System.now().epochSeconds-reservedAt>600) oldPending++
            }
            val identity=HealthImportLedger.identityDigest(row.identity.stableKey)
            if(actual!=null && !matches && owned[identity]!=actualFingerprint) foreign++
            val eligible = actual!=null && actual.sid==row.identity.sid && actual.tag==null &&
                actual.zoneOffsetSeconds==row.zoneOffsetSeconds
            val receiptMatches = actualFingerprint!=null && receipts?.get(identity)?.jsonPrimitive?.content==actualFingerprint
            if(receipts!=null && row.identity.stableKey !in pending && eligible && receiptMatches) {
                ledger.markVerified(namespace,listOf(entry.copy(contentFingerprint=checkNotNull(actualFingerprint),
                    zoneOffsetSeconds=actual?.zoneOffsetSeconds,ownedUpload=true)));restored++
            }
        }
        lines += listOf("zepp_check.$key.planned=${rows.size}","zepp_check.$key.exact=$exact", "zepp_check.$key.missing=$missing",
            "zepp_check.$key.pending=${pending.size}","zepp_check.$key.pending_exact=$pendingExact",
            "zepp_check.$key.pending_prior_content=$pendingOld", "zepp_check.$key.pending_source_changed=$pendingChanged",
            "zepp_check.$key.unowned_different=$foreign","zepp_check.$key.zone_mismatch=$zones",
            "zepp_check.$key.ownership_restored=$restored")
        lines += "zepp_check.$key.missing_off_minute=$missingOffMinute"
        lines += "zepp_check.$key.exact_content_minute_aliases=$minuteAliases"
        lines += "zepp_check.$key.pending_older_than_10min=$oldPending"
        if(key=="stress" && missingRows.isNotEmpty()) {
            val control=cloud.readRawRowsWithoutStart(key,until+1,8)
            lines += "zepp_check.$key.native_control_matches=" + missingRows.count { row ->
                control.rows.any { it.sid==row.identity.sid && it.time==row.identity.time &&
                    canonicalMiHealthImportJson(it.value)==row.canonicalContent } }
        }
    }
    val ownedTargets=prefs.workoutImportLedger().verifiedTargets(account)
    lines += "zepp_check.workout_scores.sources=${source.workouts.size}"
    lines += "zepp_check.workout_scores.owned_targets=${ownedTargets.size}"
    lines += "zepp_check.workout_scores.unique_matches=" + source.workouts.count { score ->
        ownedTargets.count { it.start==score.startSeconds && (score.endSeconds==null || it.end==score.endSeconds) }==1 }
    return lines.joinToString("\n")
}
