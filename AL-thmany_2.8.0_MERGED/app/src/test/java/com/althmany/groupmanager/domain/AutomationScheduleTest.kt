package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class AutomationScheduleTest {
    @Test
    fun delayedStartClampsSeconds() {
        assertEquals(TimeUnit.SECONDS.toMillis(1), AutomationSchedule.delayedStart(0L, 0))
        assertEquals(TimeUnit.SECONDS.toMillis(60), AutomationSchedule.delayedStart(0L, 99))
    }

    @Test
    fun nextClockStartMovesToTomorrowWhenTimePassed() {
        val zone = TimeZone.getTimeZone("UTC")
        val now = Calendar.getInstance(zone).apply {
            set(2026, Calendar.AUGUST, 6, 21, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val next = AutomationSchedule.nextClockStart(now, 20, 0, zone)
        assertEquals(TimeUnit.DAYS.toMillis(1) - TimeUnit.HOURS.toMillis(1), next - now)
        assertTrue(next > now)
    }
    @Test
    fun exactDateTimeUsesSelectedLocalComponents() {
        val zone = TimeZone.getTimeZone("UTC")
        val value = AutomationSchedule.exactDateTimeStart(
            year = 2026,
            month = Calendar.AUGUST,
            day = 10,
            hour = 14,
            minute = 35,
            timeZone = zone
        )
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = value }
        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, calendar.get(Calendar.MONTH))
        assertEquals(10, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(35, calendar.get(Calendar.MINUTE))
    }

}
