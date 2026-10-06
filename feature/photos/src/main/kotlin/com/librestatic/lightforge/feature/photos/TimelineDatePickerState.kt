package com.librestatic.lightforge.feature.photos

import com.librestatic.lightforge.core.model.TimelineDayBucket
import com.librestatic.lightforge.core.model.TimelineIndex
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** One month of the date picker: only months that show at least one row exist. */
data class DatePickerMonth(val month: YearMonth, val days: Map<Int, TimelineDayBucket>)

/** Months that hold media, in the timeline's display order (newest first unless ascending). */
fun datePickerMonths(index: TimelineIndex): List<DatePickerMonth> {
    val byMonth = LinkedHashMap<YearMonth, MutableMap<Int, TimelineDayBucket>>()
    for (bucket in index.days) {
        val date = LocalDate.ofEpochDay(bucket.epochDay)
        byMonth.getOrPut(YearMonth.from(date)) { HashMap() }[date.dayOfMonth] = bucket
    }
    val months = byMonth.map { (month, days) -> DatePickerMonth(month, days) }
    val newestFirst = months.sortedByDescending { it.month }
    return if (index.ascending) newestFirst.reversed() else newestFirst
}

/** Position of the month that holds [epochDay], or the newest month when there is none. */
fun datePickerMonthIndex(months: List<DatePickerMonth>, epochDay: Long?): Int {
    if (months.isEmpty()) return 0
    val target = epochDay?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }
    val found = target?.let { month -> months.indexOfFirst { it.month == month } } ?: -1
    if (found >= 0) return found
    return months.indices.maxBy { months[it].month }
}

/**
 * Week rows of [month] for a calendar that starts on [firstDayOfWeek]: day-of-month numbers, with
 * null for the blank cells before the 1st and after the last day.
 */
fun datePickerWeeks(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<Int?>> {
    val lead = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { it }
    return cells.chunked(7).map { week -> week + List(7 - week.size) { null } }
}
