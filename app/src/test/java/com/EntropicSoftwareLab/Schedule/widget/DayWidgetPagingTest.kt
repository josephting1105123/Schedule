package com.EntropicSoftwareLab.Schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
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
    fun `agendaDayHeader labels today and tomorrow, then plain dates`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.UK)
            val t = LocalDate.of(2026, 11, 26)
            assertEquals("Today · 26 Nov", DayWidgetPaging.agendaDayHeader(t, t))
            assertEquals("Tomorrow · 27 Nov", DayWidgetPaging.agendaDayHeader(t.plusDays(1), t))
            assertEquals("Sat 28 Nov", DayWidgetPaging.agendaDayHeader(t.plusDays(2), t))
            assertEquals("Wed 25 Nov", DayWidgetPaging.agendaDayHeader(t.minusDays(1), t))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `fillerHeight pads short days and never goes negative`() {
        assertEquals(88f, DayWidgetPaging.fillerHeight(200f, 28f, 42f, 2), 0.001f)
        assertEquals(172f, DayWidgetPaging.fillerHeight(200f, 28f, 42f, 0), 0.001f)
        assertEquals(0f, DayWidgetPaging.fillerHeight(200f, 28f, 42f, 10), 0.001f)
    }
}
