package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class MonthGridTest {

    @Test
    fun `always 42 dates, whatever the month`() {
        val months = listOf(
            YearMonth.of(2026, 2),  // 28 days, non-leap
            YearMonth.of(2024, 2),  // 29 days, leap
            YearMonth.of(2026, 8),  // 31 days
            YearMonth.of(2026, 11), // 30 days
        )
        for (month in months) {
            for (start in DayOfWeek.entries) {
                assertEquals(
                    "$month starting $start",
                    42,
                    MonthGrid.days(month, start).size,
                )
            }
        }
    }

    @Test
    fun `dates are consecutive with no gaps`() {
        val days = MonthGrid.days(YearMonth.of(2026, 8), DayOfWeek.MONDAY)
        days.zipWithNext { a, b -> assertEquals(a.plusDays(1), b) }
    }

    @Test
    fun `the grid starts on the requested first day of week`() {
        for (start in DayOfWeek.entries) {
            val first = MonthGrid.days(YearMonth.of(2026, 8), start).first()
            assertEquals(start, first.dayOfWeek)
        }
    }

    @Test
    fun `a month starting on the week's first day still gets six rows`() {
        // The case a naive implementation gets wrong: no leading padding is needed, so it emits five
        // rows and the widget changes height when you page onto it.
        // 1 June 2026 is a Monday.
        val month = YearMonth.of(2026, 6)
        assertEquals(DayOfWeek.MONDAY, month.atDay(1).dayOfWeek)

        val days = MonthGrid.days(month, DayOfWeek.MONDAY)
        assertEquals(month.atDay(1), days.first())
        assertEquals(42, days.size)
        assertEquals(6, MonthGrid.weeks(month, DayOfWeek.MONDAY).size)
    }

    @Test
    fun `February 2026 on a Sunday start pads from January`() {
        // 1 Feb 2026 is a Sunday, so a Sunday-start grid needs no padding at the front either.
        val days = MonthGrid.days(YearMonth.of(2026, 2), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 2, 1), days.first())
        // 28 days + 14 spilling into March.
        assertEquals(LocalDate.of(2026, 3, 14), days.last())
    }

    @Test
    fun `leading padding comes from the previous month`() {
        // 1 Aug 2026 is a Saturday; a Monday-start week needs the preceding Mon-Fri from July.
        val days = MonthGrid.days(YearMonth.of(2026, 8), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 7, 27), days.first())
        assertEquals(LocalDate.of(2026, 8, 1), days[5])
    }

    @Test
    fun `weeks are six rows of seven`() {
        val weeks = MonthGrid.weeks(YearMonth.of(2026, 8), DayOfWeek.MONDAY)
        assertEquals(6, weeks.size)
        weeks.forEach { assertEquals(7, it.size) }
    }

    @Test
    fun `weekday order rotates with the first day`() {
        assertEquals(
            listOf(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
            ),
            MonthGrid.weekdayOrder(DayOfWeek.SUNDAY),
        )
        assertEquals(DayOfWeek.MONDAY, MonthGrid.weekdayOrder(DayOfWeek.MONDAY).first())
    }

    @Test
    fun `columnEdges returns one more edge than columns, running from zero to the grid width`() {
        val edges = MonthGrid.columnEdges(226f)
        assertEquals(8, edges.size)
        assertEquals(0f, edges.first(), 0.0001f)
        assertEquals(226f, edges.last(), 0.0001f)
    }

    @Test
    fun `an evenly divisible width gives seven equal columns`() {
        val widths = MonthGrid.columnEdges(350f).zipWithNext { a, b -> b - a }
        assertEquals(List(7) { 50f }, widths)
    }

    @Test
    fun `a width that does not divide evenly still sums exactly and spreads the remainder`() {
        // 320 is an arbitrary widget width nobody chose to divide nicely by 7; 226 is the realistic
        // one — a 250dp widget less 24dp of card padding.
        for (gridWidth in listOf(320f, 226f)) {
            val edges = MonthGrid.columnEdges(gridWidth)
            assertEquals("$gridWidth: edges must start at zero", 0f, edges.first(), 0.0001f)
            assertEquals(
                "$gridWidth: edges must end exactly at the grid width",
                gridWidth,
                edges.last(),
                0.0001f,
            )

            val average = gridWidth / MonthGrid.DAYS_PER_WEEK
            val widths = edges.zipWithNext { a, b -> b - a }
            widths.forEach { width ->
                assertTrue("$gridWidth: column width $width should be positive", width > 0f)
                assertTrue(
                    "$gridWidth: column width $width should be within ~1 unit of the average $average",
                    width <= average + 1f,
                )
            }
        }
    }

    @Test
    fun `an explicit column count other than seven still sums exactly to the grid width`() {
        val edges = MonthGrid.columnEdges(100f, columns = 3)
        assertEquals(4, edges.size)
        assertEquals(0f, edges.first(), 0.0001f)
        assertEquals(100f, edges.last(), 0.0001f)
        edges.zipWithNext { a, b -> b - a }.forEach { assertTrue(it > 0f) }
    }

    @Test
    fun `a zero grid width returns columns plus one zeros rather than throwing`() {
        // The widget floors its own grid width well above zero before calling this, but the function
        // still needs a sane answer for the one instant a collapsing widget would pass this in —
        // unlike a negative width or a non-positive column count, which are caller bugs and throw.
        assertEquals(List(8) { 0f }, MonthGrid.columnEdges(0f))
    }

    @Test
    fun `a non-positive column count is a caller bug and throws`() {
        assertThrows(IllegalArgumentException::class.java) { MonthGrid.columnEdges(100f, columns = 0) }
        assertThrows(IllegalArgumentException::class.java) { MonthGrid.columnEdges(100f, columns = -1) }
    }

    @Test
    fun `a negative grid width is a caller bug and throws`() {
        assertThrows(IllegalArgumentException::class.java) { MonthGrid.columnEdges(-1f) }
    }
}

class MonthWidgetStateTest {

    private val today = LocalDate.of(2026, 8, 7)

    @Test
    fun `an offset set today is honoured`() {
        val month = MonthWidgetState.monthFor(today, offset = 3, offsetDay = today.toEpochDay())
        assertEquals(YearMonth.of(2026, 11), month)
    }

    @Test
    fun `an offset from another day is ignored`() {
        // The whole point of storing the day: browsing to December and glancing tomorrow should
        // show the current month, not a stale one.
        val yesterday = today.minusDays(1).toEpochDay()
        assertEquals(
            YearMonth.of(2026, 8),
            MonthWidgetState.monthFor(today, offset = 4, offsetDay = yesterday),
        )
    }

    @Test
    fun `a negative offset goes back`() {
        assertEquals(
            YearMonth.of(2026, 5),
            MonthWidgetState.monthFor(today, offset = -3, offsetDay = today.toEpochDay()),
        )
    }

    @Test
    fun `stepping from a stale offset restarts from this month`() {
        val stale = today.minusDays(2).toEpochDay()
        assertEquals(1, MonthWidgetState.step(today, offset = 7, offsetDay = stale, delta = 1))
        assertEquals(-1, MonthWidgetState.step(today, offset = 7, offsetDay = stale, delta = -1))
    }

    @Test
    fun `stepping accumulates within the same day`() {
        val d = today.toEpochDay()
        assertEquals(3, MonthWidgetState.step(today, offset = 2, offsetDay = d, delta = 1))
        assertEquals(1, MonthWidgetState.step(today, offset = 2, offsetDay = d, delta = -1))
    }

    @Test
    fun `the offset is clamped both ways`() {
        val d = today.toEpochDay()
        assertEquals(
            MonthWidgetState.MAX_OFFSET,
            MonthWidgetState.step(today, MonthWidgetState.MAX_OFFSET, d, delta = 1),
        )
        assertEquals(
            -MonthWidgetState.MAX_OFFSET,
            MonthWidgetState.step(today, -MonthWidgetState.MAX_OFFSET, d, delta = -1),
        )
    }
}
