package com.bettermifitness.sync.lab

import android.content.Context
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.data.api.MiWorkoutImportMapper
import com.bettermifitness.sync.data.api.MiDirectWorkoutImportCloud
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import com.mifitness.miclient.fds.FdsClient
import com.bettermifitness.sync.health.HealthInboundReadRequest
import com.bettermifitness.sync.health.HealthStore
import com.mifitness.miclient.gps.NativeSportFileWriter
import dalvik.system.DexClassLoader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Executes only the installed app's public-file parser; no native account/data directories are accessed. */
internal suspend fun inspectNativeWorkoutCodec(
    context: Context, store: HealthStore, sessionManager: MiSessionManager, from: Long, until: Long, cloudFile: Boolean = false,
): String {
    val sources = store.readWorkouts(HealthInboundReadRequest(from, until, setOf("com.huami.watch.hmwatchmanager")))
    val workout = sources.filter { it.session.activityType == "outdoor_running" }.maxBy { it.session.startTime }
    val session = workout.session
    val sourcePoints = session.heartRateSeries.map { NativeSportFileWriter.HeartPoint(it.timeSec, it.value.toInt()) }
    val bytes = if (cloudFile) {
        val cloud = MiDirectWorkoutImportCloud { sessionManager.api }
        val row = cloud.read(session.startTime - 86_400, session.endTime + 86_400)
            .single { it.key == "outdoor_running" && it.time == session.startTime && it.sid == "xiaomiwear_app_manually" }
        val transport = FdsClient(sessionManager.api.dataClient())
        try { transport.downloadSportFile(FdsClient.SportFileRequest(row.sid, row.time,
            checkNotNull(row.zoneOffset) / 900, row.value.getValue("proto_type").jsonPrimitive.int, 0)) }
        finally { transport.close() }
    } else NativeSportFileWriter.heartRate(session.startTime, checkNotNull(session.tzIn15Min), 1, sourcePoints,
        session.route.firstOrNull()?.altitudeMeters, sessionEndTime = session.endTime)
    val expected = if (cloudFile) NativeSportFileWriter.decodeHeartRate(bytes) else sourcePoints
    val lines = mutableListOf("native_codec.health_uploads=0", "native_codec.input_points=${expected.size}")
    try {
        val info = context.packageManager.getApplicationInfo("com.mi.health", 0)
        val loader = DexClassLoader(info.sourceDir, context.codeCacheDir.absolutePath, null, context.classLoader)
        val environmentType = loader.loadClass("com.xiaomi.fit.fitness.parser.schema.SchemaEnvironment")
        val sdkType = loader.loadClass("com.xiaomi.fit.fitness.parser.FitnessParserSDK")
        val sdk = sdkType.getField("INSTANCE").get(null)
        val packageContext = context.createPackageContext("com.mi.health", 0)
        val environment = Proxy.newProxyInstance(loader, arrayOf(environmentType)) { _, method, _ ->
            when (method.name) {
                "getAssetManager" -> packageContext.assets
                "getSchemaFilePath" -> context.codeCacheDir
                "getOutputSchemaDir" -> context.codeCacheDir.absolutePath
                "getSchemaMd5" -> ""
                "isUseNativeParser" -> true
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        }
        sdkType.methods.single { it.name == "init" }.invoke(sdk, context, null, environment)
        val type = loader.loadClass("com.xiaomi.fit.fitness.parser.FitnessDataParser")
        val parser = type.getField("INSTANCE").get(null)
        val headerType = loader.loadClass("com.xiaomi.fit.data.common.data.mi.FitnessDataHeader")
        val companion = headerType.getField("Companion").get(null)
        val dataId = companion.javaClass.getMethod("getSportDataId", ByteArray::class.java, Int::class.javaPrimitiveType)
            .invoke(companion, bytes, 0)
        val header = companion.javaClass.methods.single { it.name == "parseDataHeader" }.invoke(companion, dataId, bytes)
        lines += "native_codec.header_length=" + header.javaClass.getMethod("getHeaderLen").invoke(header)
        lines += "native_codec.validity_length=" + header.javaClass.getMethod("getDataValidLen").invoke(header)
        val result = type.getMethod("parseSportData", ByteArray::class.java, Int::class.javaPrimitiveType).invoke(parser, bytes, 0)
        val sports = result.javaClass.getMethod("getSportRecord").invoke(result) as Map<*, *>
        val segments = sports.values.flatMap { it as List<*> }.filterNotNull()
        val slots = segments.flatMap { segment -> segment.javaClass.getMethod("getRecordList").invoke(segment) as List<*> }
            .filterNotNull()
        val points = slots.filter { (it.javaClass.getMethod("getHr").invoke(it) as? Int ?: 0) > 0 }
        lines += "native_codec.elapsed_slots=${slots.size}"
        check(slots.size.toLong() == session.endTime - session.startTime)
        val chartKey = loader.loadClass("com.xiaomi.fit.data.common.data.sport.SportRecordKey")
        val hrKey = chartKey.enumConstants.single { (it as Enum<*>).name == "Hr" }
        val chartUtil = loader.loadClass("com.xiaomi.fitness.chart.util.SportRecordChatUtil")
        val invalidMethod = chartUtil.getMethod("checkZeroNotValidByKey", chartKey, Float::class.javaPrimitiveType)
        val invalid = invalidMethod.invoke(chartUtil.getField("INSTANCE").get(null), hrKey, 0f) == true
        lines += "native_codec.chart_zero_is_missing=$invalid"
        check(invalid)
        val actualValues = points.map { it.javaClass.getMethod("getHr").invoke(it) as? Int }
        val actualTimes = points.map { (it.javaClass.getMethod("getStartTime").invoke(it) as Number).toLong() }
        lines += "native_codec.decoded_points=${points.size}"
        lines += "native_codec.values_match=${actualValues == expected.map { it.bpm }}"
        lines += "native_codec.times_match=${actualTimes == expected.map { it.time }}"
        lines += "native_codec.segments=${segments.size}"
        lines += "native_codec.unit_count_segments=" + segments.count {
            (it.javaClass.getMethod("getRecordCount").invoke(it) as Number).toLong() == 1L
        }
        if (cloudFile) {
            lines += "native_codec.actual_cloud_file=true"
            lines += "native_codec.outcome=PARSED"
            return lines.joinToString("\n")
        }
        for (proto in listOf(1, 2, 3, 8, 15)) {
            val candidate = NativeSportFileWriter.heartRate(session.startTime, checkNotNull(session.tzIn15Min), proto,
                expected, session.route.firstOrNull()?.altitudeMeters, sessionEndTime = session.endTime)
            val parsed = type.getMethod("parseSportData", ByteArray::class.java, Int::class.javaPrimitiveType)
                .invoke(parser, candidate, 0)
            val records = parsed.javaClass.getMethod("getSportRecord").invoke(parsed) as Map<*, *>
            val decoded = records.values.flatMap { it as List<*> }.filterNotNull().flatMap {
                it.javaClass.getMethod("getRecordList").invoke(it) as List<*>
            }.filterNotNull()
            check(decoded.size.toLong() == session.endTime - session.startTime)
            val observed = decoded.filter { (it.javaClass.getMethod("getHr").invoke(it) as? Int ?: 0) > 0 }
            val matches = observed.map { it.javaClass.getMethod("getHr").invoke(it) as? Int } == expected.map { it.bpm } &&
                observed.map { (it.javaClass.getMethod("getStartTime").invoke(it) as Number).toLong() } == expected.map { it.time }
            lines += "native_codec.proto_${proto}_exact_match=$matches"
            check(matches) { "Installed parser mismatch" }
        }
        val transport = FdsClient(sessionManager.api.dataClient())
        var cloudSources = 0
        var cloudPoints = 0
        var pendingLayouts = 0
        try {
            for (source in sources) {
                val target = checkNotNull(MiWorkoutImportMapper.map(source))
                val s = source.session
                if (s.heartRateSeries.isEmpty()) continue
                val proto = when (s.activityType) {
                    "indoor_running" -> 3
                    "outdoor_running" -> if (s.route.firstOrNull()?.altitudeMeters != null) 1 else 8
                    "outdoor_walking" -> if (s.route.firstOrNull()?.altitudeMeters != null) 2 else 8
                    "outdoor_hiking" -> if (s.route.firstOrNull()?.altitudeMeters != null) 15 else 8
                    else -> 8
                }
                val downloaded = transport.downloadSportFile(FdsClient.SportFileRequest(
                    "xiaomiwear_app_manually", target.start, target.zoneOffsetSeconds / 900, proto, 0))
                val expectedPoints = s.heartRateSeries.map { NativeSportFileWriter.HeartPoint(it.timeSec, it.value.toInt()) }
                val expectedBytes = NativeSportFileWriter.heartRate(s.startTime, checkNotNull(s.tzIn15Min),
                    proto, expectedPoints, s.route.firstOrNull()?.altitudeMeters, sessionEndTime = s.endTime)
                if (!downloaded.contentEquals(expectedBytes)) { pendingLayouts++; continue }
                val parsed = type.getMethod("parseSportData", ByteArray::class.java, Int::class.javaPrimitiveType)
                    .invoke(parser, downloaded, 0)
                val records = parsed.javaClass.getMethod("getSportRecord").invoke(parsed) as Map<*, *>
                val decoded = records.values.flatMap { it as List<*> }.filterNotNull().flatMap {
                    it.javaClass.getMethod("getRecordList").invoke(it) as List<*>
                }.filterNotNull()
                check(decoded.size.toLong() == s.endTime - s.startTime)
                val observed = decoded.filter { (it.javaClass.getMethod("getHr").invoke(it) as? Int ?: 0) > 0 }
                check(observed.map { it.javaClass.getMethod("getHr").invoke(it) as? Int } == expectedPoints.map { it.bpm } &&
                    observed.map { (it.javaClass.getMethod("getStartTime").invoke(it) as Number).toLong() } ==
                    expectedPoints.map { it.time }) { "Cloud native parser mismatch" }
                cloudSources++
                cloudPoints += observed.size
            }
        } finally { transport.close() }
        lines += "native_codec.cloud_sources_verified=$cloudSources"
        lines += "native_codec.cloud_points_verified=$cloudPoints"
        lines += "native_codec.cloud_layouts_pending=$pendingLayouts"
        lines += "native_codec.cloud_values_and_times_match=${pendingLayouts == 0}"
        lines += "native_codec.outcome=" + if (pendingLayouts == 0) "PARSED" else "LOCAL_PARSED_CLOUD_PENDING"
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        lines += "native_codec.outcome=FAILED"
        lines += "native_codec.error_type=${cause::class.simpleName}"
        lines += "native_codec.error_frame=" + cause.stackTrace.firstOrNull()?.let { it.className + "." + it.methodName }
    }
    return lines.joinToString("\n")
}
