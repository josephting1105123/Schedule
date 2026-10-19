package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An all-day event belongs to its own dates, not to whichever local days its UTC-midnight span
 * overlaps. Run both east and west of Greenwich, where the old read went wrong in opposite ways.
 */
class FallsWithinTest {

    private val kualaLumpur: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    /** [lastDay] inclusive, stored the provider's way: UTC midnight to the UTC midnight after. */
    private fun allDay(firstDay: LocalDate, lastDay: LocalDate) = EventInstance(
        eventId = 1,
        instanceId = 1,
        title = "Semester I Final Examination",
        location = null,
        startMillis = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endMillis = lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        allDay = true,
        calendarId = 1,
        displayColor = 0,
    )

    private val jan25 = LocalDate.of(2027, 1, 25)
    private val feb4 = LocalDate.of(2027, 2, 4)
    private val exam = allDay(jan25, feb4)

    private fun on(date: LocalDate, zone: ZoneId) = exam.fallsWithin(date, date.plusDays(1), zone)

    @Test
    fun `east of Greenwich it is not listed the day after it ends`() {
        assertTrue(on(feb4, kualaLumpur))
        assertFalse(on(feb4.plusDays(1), kualaLumpur))
    }

    @Test
    fun `west of Greenwich it is not listed the day before it starts`() {
        assertTrue(on(jan25, newYork))
        assertFalse(on(jan25.minusDays(1), newYork))
    }

    @Test
    fun `a range counts it when any of its days fall inside`() {
        assertTrue(exam.fallsWithin(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 7), kualaLumpur))
        assertFalse(exam.fallsWithin(LocalDate.of(2027, 2, 5), LocalDate.of(2027, 2, 12), kualaLumpur))
    }

    @Test
    fun `a malformed row still shows on its first day`() {
        val broken = exam.copy(endMillis = exam.startMillis)
        assertTrue(broken.fallsWithin(jan25, jan25.plusDays(1), kualaLumpur))
    }

    @Test
    fun `timed events are left to the query`() {
        val timed = exam.copy(allDay = false)
        assertTrue(timed.fallsWithin(feb4.plusDays(5), feb4.plusDays(6), kualaLumpur))
    }
}
