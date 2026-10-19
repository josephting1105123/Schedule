package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WeekPagingTest {

    /** Saturday 26 September 2026. */
    private val today = LocalDate.of(2026, 9, 26)

    @Test
    fun `a week starts on the Sunday on or before the date`() {
        assertEquals(LocalDate.of(2026, 9, 20), WeekPaging.weekStart(today))
        assertEquals(LocalDate.of(2026, 9, 27), WeekPaging.weekStart(LocalDate.of(2026, 9, 27)))
        assertEquals(LocalDate.of(2026, 9, 27), WeekPaging.weekStart(LocalDate.of(2026, 9, 28)))
    }

    @Test
    fun `a week runs Sunday to Saturday`() {
        val days = WeekPaging.days(LocalDate.of(2026, 9, 27))
        assertEquals(7, days.size)
        assertEquals(DayOfWeek.SUNDAY, days.first().dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, days.last().dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 3), days.last())
    }

    @Test
    fun `weeks are counted Sunday to Sunday, not seven days from the date`() {
        // Saturday to the next day is a new week; Sunday to Saturday is the same one.
        assertEquals(1L, WeekPaging.weeksBetween(today, LocalDate.of(2026, 9, 27)))
        assertEquals(0L, WeekPaging.weeksBetween(LocalDate.of(2026, 9, 20), today))
        assertEquals(-1L, WeekPaging.weeksBetween(today, LocalDate.of(2026, 9, 19)))
    }

    @Test
    fun `the weeks either side of today are named, the rest are dated`() {
        assertEquals("This week", WeekPaging.title(LocalDate.of(2026, 9, 20), today))
        assertEquals("Next week", WeekPaging.title(LocalDate.of(2026, 9, 27), today))
        assertEquals("Last week", WeekPaging.title(LocalDate.of(2026, 9, 13), today))
        assertEquals("4 Oct – 10 Oct 2026", WeekPaging.title(LocalDate.of(2026, 10, 4), today))
    }

    @Test
    fun `a week across New Year carries both years`() {
        assertEquals("27 Dec 2026 – 2 Jan 2027", WeekPaging.range(LocalDate.of(2026, 12, 27)))
    }
}
