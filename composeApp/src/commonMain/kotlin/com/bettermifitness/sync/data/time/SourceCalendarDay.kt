package com.bettermifitness.sync.data.time

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

internal const val SOURCE_DAY_SECONDS = 86_400L

internal data class SourceCalendarDay(val from: Long, val until: Long) {
    fun coveredBy(start: Long, end: Long): Boolean = start <= from && end >= until
}

internal fun TimeZone.sourceCalendarDay(epochDay: Long): SourceCalendarDay {
    val date = Instant.fromEpochSeconds(epochDay * SOURCE_DAY_SECONDS).toLocalDateTime(TimeZone.UTC).date
    return SourceCalendarDay(date.atStartOfDayIn(this).epochSeconds,
        date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(this).epochSeconds)
}

internal fun TimeZone.dayContaining(epochSeconds: Long): SourceCalendarDay {
    val date = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(this).date
    return SourceCalendarDay(date.atStartOfDayIn(this).epochSeconds,
        date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(this).epochSeconds)
}
