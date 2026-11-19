package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.api.MiWorkoutImportTarget
import com.bettermifitness.sync.data.api.WorkoutRoutePoint
import com.bettermifitness.sync.health.InboundWorkout
import com.mifitness.miclient.api.MiApiException
import com.mifitness.miclient.fds.FdsClient
import com.mifitness.miclient.fds.SportFileTransport
import com.mifitness.miclient.gps.SportGpsBinary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Retain the already owned route when background Health Connect cannot return it again. */
internal suspend fun preserveMiWorkoutRoute(source: InboundWorkout, target: MiWorkoutImportTarget,
    value: JsonObject, transport: SportFileTransport): InboundWorkout {
    if (value["version"] != JsonPrimitive(1)) return source
    check(value["did"] == JsonPrimitive(target.sid))
    val proto = (value.getValue("proto_type") as JsonPrimitive).content.toInt()
    val bytes = try { transport.downloadSportFile(FdsClient.SportFileRequest(target.sid, target.start,
        target.zoneOffsetSeconds / 900, proto, 2)) }
    catch (error: MiApiException.Server) { if (error.httpOrBusinessCode == 404) return source else throw error }
    val header = checkNotNull(SportGpsBinary.parseHeader(bytes))
    check(header.version == 1 && header.sportType == proto && header.timeSec == target.start &&
        header.tzIn15Min * 900 == target.zoneOffsetSeconds)
    check(header.dataValid.toList() == listOf(0xe0.toByte()) && (bytes.size - header.bodyOffset) % 12 == 0)
    val points = SportGpsBinary.parsePoints(bytes)
    check(points.isNotEmpty() && points.size == (bytes.size - header.bodyOffset) / 12)
    return source.copy(session = source.session.copy(route = points.map {
        WorkoutRoutePoint(it.timeSec, it.latitude, it.longitude) }))
}
