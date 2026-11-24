package com.EntropicSoftwareLab.Schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class DayWidgetPagingTest {

    private val today = LocalDate.of(2026, 11, 24)

    @Test
    fun `dayFor honours a fresh offset`() {
        val d = today.toEpochDay()
        assertEquals(today, DayWidgetPaging.dayFor(today, offset = 0, offsetDay = d))
        assertEquals(LocalDate.of(2026, 11, 27), DayWidgetPaging.dayFor(today, offset = 3, offsetDay = d))
        assertEquals(LocalDate.of(2026, 11, 22), DayWidgetPaging.dayFor(today, offset = -2, offsetDay = d))
    }

    @Test
    fun `dayFor ignores a stale offsetDay and returns today`() {
        val yesterday = today.minusDays(1).toEpochDay()
        assertEquals(today, DayWidgetPaging.dayFor(today, offset = 5, offsetDay = yesterday))
        assertEquals(today, DayWidgetPaging.dayFor(today, offset = -3, offsetDay = yesterday))
    }

    @Test
    fun `step moves from zero and accumulates on the same day`() {
        val d = today.toEpochDay()
        assertEquals(1, DayWidgetPaging.step(today, offset = 0, offsetDay = d, delta = 1))
        assertEquals(-1, DayWidgetPaging.step(today, offset = 0, offsetDay = d, delta = -1))
        assertEquals(3, DayWidgetPaging.step(today, offset = 2, offsetDay = d, delta = 1))
        assertEquals(1, DayWidgetPaging.step(today, offset = 2, offsetDay = d, delta = -1))
    }

    @Test
    fun `step restarts from today when offsetDay is stale`() {
        val stale = today.minusDays(2).toEpochDay()
        assertEquals(1, DayWidgetPaging.step(today, offset = 10, offsetDay = stale, delta = 1))
        assertEquals(-1, DayWidgetPaging.step(today, offset = 10, offsetDay = stale, delta = -1))
    }

    @Test
    fun `step clamps at plus and minus MAX_OFFSET`() {
        val d = today.toEpochDay()
        assertEquals(
            DayWidgetPaging.MAX_OFFSET,
            DayWidgetPaging.step(today, offset = DayWidgetPaging.MAX_OFFSET, offsetDay = d, delta = 1),
        )
        assertEquals(
            -DayWidgetPaging.MAX_OFFSET,
            DayWidgetPaging.step(today, offset = -DayWidgetPaging.MAX_OFFSET, offsetDay = d, delta = -1),
        )
    }

    @Test
    fun `agendaTitle names today, tomorrow, yesterday, and formats farther days`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.UK)
            assertEquals("Agenda", DayWidgetPaging.agendaTitle(today, today))
            assertEquals("Tomorrow", DayWidgetPaging.agendaTitle(today.plusDays(1), today))
            assertEquals("Yesterday", DayWidgetPaging.agendaTitle(today.minusDays(1), today))

            val farFuture = LocalDate.of(2026, 11, 27)
            assertEquals("Fri 27 Nov", DayWidgetPaging.agendaTitle(farFuture, today))

            val farPast = LocalDate.of(2026, 11, 20)
            assertEquals(
                farPast.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                DayWidgetPaging.agendaTitle(farPast, today),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }
}
