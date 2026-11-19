package com.bettermifitness.sync.lab

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** Counts and relative days only. No cloud APIs, health writes or journal access. */
@Suppress("NestedBlockDepth") // A one-off device report; keeping it flat matters less than keeping it in one place.
internal suspend fun inspectHealthConnectChain(context: Context): String {
    val client = HealthConnectClient.getOrCreate(context)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val end = Instant.now()
    val start = today.minusDays(7).atStartOfDay(zone).toInstant()
    val output = mutableListOf("hc_chain.health_uploads=0", "hc_chain.local_journal_changes=0",
        "hc_chain.day_basis=phone_local_calendar", "hc_chain.day_0=today", "hc_chain.values_withheld=true")
    fun age(instant: Instant) = java.time.temporal.ChronoUnit.DAYS.between(instant.atZone(zone).toLocalDate(), today).toInt()
    suspend fun <T : Record> read(type: KClass<T>, origin: String): Pair<List<T>, Int> {
        val rows = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        var token: String? = null
        var pages = 0
        do {
            val response = client.readRecords(ReadRecordsRequest(type,
                timeRangeFilter = TimeRangeFilter.between(start, end), dataOriginFilter = setOf(DataOrigin(origin)), pageToken = token))
            rows += response.records
            pages++
            token = response.pageToken
            check(token == null || seen.add(token!!))
            check(token == null || pages < 100)
        } while (token != null)
        return rows to pages
    }
    for ((label, origin) in listOf("zepp" to "com.huami.watch.hmwatchmanager", "fitbit" to "com.fitbit.FitbitMobile")) {
        for (metric in listOf("hrv", "resting_heart_rate", "heart_rate", "sleep")) {
            val prefix = "hc_chain.$label.$metric"
            try {
                val (rows, pages) = when (metric) {
                    "hrv" -> read(HeartRateVariabilityRmssdRecord::class, origin)
                    "resting_heart_rate" -> read(RestingHeartRateRecord::class, origin)
                    "heart_rate" -> read(HeartRateRecord::class, origin)
                    else -> read(SleepSessionRecord::class, origin)
                }
                val counts = mutableMapOf<Int, Int>()
                var earlierMeasurementsModifiedToday = 0
                var sourceZoneDiffers = 0
                var missingSourceZone = 0
                var sleepStartedYesterdayEndedToday = 0
                for (record in rows) {
                    val times = when (record) {
                        is HeartRateVariabilityRmssdRecord -> listOf(record.time)
                        is RestingHeartRateRecord -> listOf(record.time)
                        is HeartRateRecord -> record.samples.map { it.time }
                        is SleepSessionRecord -> listOf(record.endTime)
                        else -> emptyList()
                    }
                    val offset = when (record) {
                        is HeartRateVariabilityRmssdRecord -> record.zoneOffset
                        is RestingHeartRateRecord -> record.zoneOffset
                        is HeartRateRecord -> record.startZoneOffset
                        is SleepSessionRecord -> record.endZoneOffset
                        else -> null
                    }
                    if (offset == null) missingSourceZone++
                    for (time in times.filter { it >= start && it < end }) {
                        counts[age(time)] = (counts[age(time)] ?: 0) + 1
                        if (offset != null && time.atOffset(offset).toLocalDate() != time.atZone(zone).toLocalDate()) sourceZoneDiffers++
                    }
                    if (age(record.metadata.lastModifiedTime) == 0 && times.isNotEmpty() && times.all { age(it) > 0 })
                        earlierMeasurementsModifiedToday++
                    if (record is SleepSessionRecord && age(record.startTime) == 1 && age(record.endTime) == 0)
                        sleepStartedYesterdayEndedToday++
                }
                output += "$prefix.read_complete=true"
                output += "$prefix.records=${rows.size}"
                output += "$prefix.pages=$pages"
                if (label == "zepp" && metric == "hrv") {
                    for (day in 0..1) {
                        val daily = rows.filterIsInstance<HeartRateVariabilityRmssdRecord>().filter { age(it.time) == day }
                        output += "$prefix.day_$day.nonpositive=${daily.count { it.heartRateVariabilityMillis <= 0 }}"
                        output += "$prefix.day_$day.recording_methods=" + daily.map { it.metadata.recordingMethod }.distinct().sorted()
                            .joinToString(",")
                        val modifiedOverOneHourAgo = daily.count { it.metadata.lastModifiedTime < end.minusSeconds(3600) }
                        output += "$prefix.day_$day.modified_over_one_hour_ago=$modifiedOverOneHourAgo"
                    }
                }
                for (day in 0..7) output += "$prefix.day_$day=${counts[day] ?: 0}"
                output += "$prefix.earlier_measurements_modified_today=$earlierMeasurementsModifiedToday"
                output += "$prefix.missing_source_zone=$missingSourceZone"
                output += "$prefix.source_zone_different_date=$sourceZoneDiffers"
                if (metric == "sleep") output += "$prefix.started_yesterday_ended_today=$sleepStartedYesterdayEndedToday"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                output += "$prefix.read_complete=false"
                output += "$prefix.failure=" + if (error is SecurityException) "ACCESS_DENIED" else "READ_FAILED"
            }
        }
    }
    return output.joinToString("\n")
}
