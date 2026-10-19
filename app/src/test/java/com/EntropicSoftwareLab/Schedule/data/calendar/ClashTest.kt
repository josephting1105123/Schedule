package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Covers [FreeBusy.clashesOnDay] — which stretches of a day are booked twice, and by what. */
class ClashTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val day: LocalDate = LocalDate.of(2026, 7, 20)

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        id: Long,
        startHour: Int,
        endHour: Int,
        startMinute: Int = 0,
        endMinute: Int = 0,
        startDate: LocalDate = day,
        endDate: LocalDate = day,
    ) = EventInstance(
        eventId = id,
        instanceId = id,
        title = "Event $id",
        location = null,
        startMillis = at(startHour, startMinute, startDate),
        endMillis = at(endHour, endMinute, endDate),
        allDay = false,
        calendarId = 1L,
        displayColor = 0,
    )

    private fun allDayEvent(id: Long = 99) = EventInstance(
        eventId = id, instanceId = id, title = "All day", location = null,
        startMillis = localDateToUtcMidnight(day),
        endMillis = localDateToUtcMidnight(day.plusDays(1)),
        allDay = true, calendarId = 1L, displayColor = 0,
    )

    private fun clashes(vararg events: EventInstance) =
        FreeBusy.clashesOnDay(events.toList(), day, zone)

    @Test
    fun `a clear day has nothing clashing`() {
        assertTrue(clashes(event(1, 9, 10), event(2, 11, 12)).isEmpty())
    }

    @Test
    fun `a single event cannot clash with itself`() {
        assertTrue(clashes(event(1, 9, 17)).isEmpty())
    }

    @Test
    fun `back-to-back events do not clash`() {
        assertTrue(clashes(event(1, 9, 10), event(2, 10, 11)).isEmpty())
    }

    @Test
    fun `the reported window is the overlap, not the events' full span`() {
        val result = clashes(event(1, 14, 15), event(2, 14, 16, startMinute = 30))
        assertEquals(1, result.size)
        // 14:00-15:00 and 14:30-16:00 are double-booked only from 14:30 to 15:00.
        assertEquals(at(14, 30), result[0].startMillis)
        assertEquals(at(15), result[0].endMillis)
        assertEquals(listOf(1L, 2L), result[0].events.map { it.instanceId })
    }

    @Test
    fun `an event fully inside another clashes for its own length`() {
        val result = clashes(event(1, 9, 17), event(2, 11, 12))
        assertEquals(1, result.size)
        assertEquals(at(11), result[0].startMillis)
        assertEquals(at(12), result[0].endMillis)
        assertEquals(2, result[0].events.size)
    }

    @Test
    fun `separate collisions are reported separately`() {
        val result = clashes(
            event(1, 9, 11), event(2, 10, 12),
            event(3, 15, 17), event(4, 16, 18),
        )
        assertEquals(2, result.size)
        assertEquals(at(10), result[0].startMillis)
        assertEquals(at(11), result[0].endMillis)
        assertEquals(at(16), result[1].startMillis)
        assertEquals(at(17), result[1].endMillis)
    }

    /**
     * A overlaps B and B overlaps C, but A and C never meet. The contested time is continuous, so it
     * is one problem window naming all three rather than two fragments the user has to stitch.
     */
    @Test
    fun `a chain of overlaps merges into one window naming everyone`() {
        val result = clashes(
            event(1, 9, 11),
            event(2, 10, 12),
            event(3, 11, 13),
        )
        assertEquals(1, result.size)
        assertEquals(at(10), result[0].startMillis)
        assertEquals(at(12), result[0].endMillis)
        assertEquals(listOf(1L, 2L, 3L), result[0].events.map { it.instanceId })
    }

    @Test
    fun `three events overlapping at once are one clash`() {
        val result = clashes(event(1, 9, 12), event(2, 9, 12), event(3, 9, 12))
        assertEquals(1, result.size)
        assertEquals(3, result[0].events.size)
        assertEquals(at(9), result[0].startMillis)
        assertEquals(at(12), result[0].endMillis)
    }

    @Test
    fun `a gap between collisions is not swallowed`() {
        // 1 spans the day, 2 and 3 poke into it at different times — two windows, not one.
        val result = clashes(event(1, 9, 17), event(2, 10, 11), event(3, 14, 15))
        assertEquals(2, result.size)
        assertEquals(at(10), result[0].startMillis)
        assertEquals(at(11), result[0].endMillis)
        assertEquals(at(14), result[1].startMillis)
        assertEquals(at(15), result[1].endMillis)
    }

    @Test
    fun `all-day events never clash with anything`() {
        assertTrue(clashes(allDayEvent(), event(1, 9, 10)).isEmpty())
        assertTrue(clashes(allDayEvent(1), allDayEvent(2)).isEmpty())
    }

    @Test
    fun `zero-length and inverted rows are ignored`() {
        assertTrue(clashes(event(1, 9, 17), event(2, 11, 11)).isEmpty())
        assertTrue(clashes(event(1, 9, 17), event(2, 12, 11)).isEmpty())
    }

    @Test
    fun `query order does not change the result`() {
        val events = listOf(event(3, 15, 17), event(1, 9, 11), event(4, 16, 18), event(2, 10, 12))
        assertEquals(
            FreeBusy.clashesOnDay(events, day, zone),
            FreeBusy.clashesOnDay(events.reversed(), day, zone),
        )
    }

    @Test
    fun `an overlap that happens on another day is not today's problem`() {
        // Both run 22:00 yesterday to 01:00 today: they overlap for three hours, but only the last
        // one falls on this day, so that is the only part reported.
        val result = clashes(
            event(1, 22, 1, startDate = day.minusDays(1)),
            event(2, 22, 1, startDate = day.minusDays(1)),
        )
        assertEquals(1, result.size)
        assertEquals(at(0), result[0].startMillis)
        assertEquals(at(1), result[0].endMillis)
    }

    @Test
    fun `an overlap entirely before this day is dropped`() {
        val result = clashes(
            event(1, 20, 23, startDate = day.minusDays(1), endDate = day.minusDays(1)),
            event(2, 21, 23, startDate = day.minusDays(1), endDate = day.minusDays(1)),
        )
        assertTrue(result.isEmpty())
    }
}
