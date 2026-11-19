package com.bettermifitness.sync.lab

import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.MiDirectWorkoutImportCloud
import com.bettermifitness.sync.data.api.MiWorkoutImportMapper
import com.bettermifitness.sync.data.preferences.SyncPreferences
import com.bettermifitness.sync.data.preferences.WorkoutImportEntry
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthStore
import com.bettermifitness.sync.zepp.ZeppSyncService
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.gps.NativeSportFileWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.Koin

/** Source/cloud/file comparison only. No runner, upload, journal mutation or raw identifier output. */
internal suspend fun inspectWorkoutFilesReadOnly(koin: Koin, from: Long, until: Long): String {
    val session = koin.get<MiSessionManager>()
    val account = checkNotNull(session.currentUserId)
    val prefs = koin.get<SyncPreferences>()
    val source = koin.get<HealthStore>().readWorkouts(HealthInboundReadRequest(from, until,
        setOf("com.huami.watch.hmwatchmanager")))
    val reader = koin.get<ZeppSyncService>().workoutHeartRateReader(source)
    val cloud = MiDirectWorkoutImportCloud { session.api }
    val transport = FdsClient(session.api.dataClient())
    val dataStore = koin.get<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>()
    val allEntries = dataStore.data.first().asMap().filterKeys { it.name.startsWith("workout_import_v1_") }.values
        .map { Json.decodeFromString<WorkoutImportEntry>(it.toString()) }
    val lines = mutableListOf("workout_files.health_uploads=0", "workout_files.local_journal_changes=0",
        "workout_files.source_records=${source.size}")
    try {
        for ((index, item) in source.withIndex()) {
            val target = MiWorkoutImportMapper.map(item) ?: continue
            val prefix = "workout_files.item_$index"
            val owned = prefs.workoutImportLedger().load(account, target.sourceKey)
            val candidates = cloud.read(target.start - 86_400, target.end + 86_400).filter { target.overlaps(it) }
            val row = candidates.singleOrNull()?.takeIf { target.matches(it) }
            val entries = allEntries.filter { it.target.sourceKey.startsWith("FILES:") &&
                it.target.identity == target.identity }
            lines += "$prefix.summary_verified=${owned?.verified == true}"
            lines += "$prefix.summary_matches=${row != null}"
            lines += "$prefix.file_ledger_entries=${entries.size}"
            lines += "$prefix.unconfirmed_entries=" + entries.count { !it.verified }
            lines += "$prefix.unconfirmed_body_attempts=" + entries.filter { !it.verified }.sumOf {
                it.fileStates.values.count { state -> state == com.bettermifitness.sync.data.preferences.WorkoutFileStage.ATTEMPTED } }
            for ((entryIndex, entry) in entries.withIndex()) {
                lines += "$prefix.ledger_$entryIndex.verified=${entry.verified}"
                lines += "$prefix.ledger_$entryIndex.protocol_version=${entry.fileProtocolVersion}"
                lines += "$prefix.ledger_$entryIndex.states=" + entry.fileStates.entries.sortedBy { it.key }
                    .joinToString(",") { "${it.key}:${it.value}" }
                lines += "$prefix.ledger_$entryIndex.promotion_attempted=${entry.promotionAttempted}"
            }
            if (row == null) continue
            val proto = (row.value["proto_type"] as? JsonPrimitive)?.content?.toIntOrNull()
            val version = (row.value["version"] as? JsonPrimitive)?.content?.toIntOrNull()
            lines += "$prefix.cloud_proto=${proto ?: -1}"
            lines += "$prefix.cloud_version=${version ?: -1}"
            var sourceStatus = "OK"
            val original = try { reader(item) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { sourceStatus = error::class.simpleName.orEmpty(); null }
            lines += "$prefix.original_heart_rate_status=$sourceStatus"
            lines += "$prefix.health_connect_heart_points=${item.session.heartRateSeries.size}"
            lines += "$prefix.original_heart_points=${original?.size ?: 0}"
            val points = original?.takeIf { it.isNotEmpty() } ?: item.session.heartRateSeries
            val expectedProto = if (target.key == "outdoor_running" && (original?.isNotEmpty() == true ||
                row.value["proto_type"] == JsonPrimitive(2))) 2 else when (target.key) {
                "outdoor_running" -> 1
                "indoor_running" -> 3
                "outdoor_walking", "outdoor_hiking" -> (target.value.getValue("proto_type") as JsonPrimitive).content.toInt()
                else -> 8
            }
            lines += "$prefix.expected_proto=$expectedProto"
            for (type in listOf(0, 2)) {
                if (proto == null || proto !in 1..31) continue
                try {
                    val bytes = transport.downloadSportFile(FdsClient.SportFileRequest(target.sid, target.start,
                        target.zoneOffsetSeconds / 900, proto, type))
                    lines += "$prefix.file_$type.download=OK"
                    if (type == 0) {
                        val decoded = NativeSportFileWriter.decodeHeartRate(bytes)
                        lines += "$prefix.file_$type.decoded_points=${decoded.size}"
                        lines += "$prefix.file_$type.source_points_match=" + (decoded == points.map {
                            NativeSportFileWriter.HeartPoint(it.timeSec, it.value.toInt()) })
                        val expected = NativeSportFileWriter.heartRate(target.start, target.zoneOffsetSeconds / 900,
                            expectedProto, points.map { NativeSportFileWriter.HeartPoint(it.timeSec, it.value.toInt()) },
                            sessionEndTime = target.end)
                        lines += "$prefix.file_$type.expected_bytes_match=${bytes.contentEquals(expected)}"
                    } else {
                        val header = com.mifitness.miclient.gps.SportGpsBinary.parseHeader(bytes)
                        val route = com.mifitness.miclient.gps.SportGpsBinary.parsePoints(bytes)
                        lines += "$prefix.file_$type.route_points=${route.size}"
                        lines += "$prefix.file_$type.header_valid=" + (header != null && header.version == 1 &&
                            header.timeSec == target.start && header.sportType == proto)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val code = if (error is MiApiException.Server) "SERVER_${error.httpOrBusinessCode}" else error::class.simpleName
                    lines += "$prefix.file_$type.download=$code"
                }
            }
        }
    } finally { transport.close() }
    return lines.joinToString("\n")
}
