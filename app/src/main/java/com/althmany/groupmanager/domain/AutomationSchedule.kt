package com.althmany.groupmanager.domain

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Pure scheduling helpers used by the UI and covered without Android dependencies. */
object AutomationSchedule {
    fun delayedStart(nowMillis: Long, delaySeconds: Int): Long =
        nowMillis + TimeUnit.SECONDS.toMillis(delaySeconds.coerceIn(1, 60).toLong())

    fun nextClockStart(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis + 1_000L) add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }
    fun exactDateTimeStart(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long = Calendar.getInstance(timeZone).apply {
        clear()
        set(Calendar.YEAR, year.coerceIn(2024, 2100))
        set(Calendar.MONTH, month.coerceIn(0, 11))
        set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, minute.coerceIn(0, 59))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

}
