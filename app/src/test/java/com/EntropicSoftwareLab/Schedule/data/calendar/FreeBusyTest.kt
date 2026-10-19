package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FreeBusyTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val day: LocalDate = LocalDate.of(2026, 7, 20)

    private fun at(hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        startHour: Int,
        endHour: Int,
        title: String = "Event",
        allDay: Boolean = false,
        startMinute: Int = 0,
        endMinute: Int = 0,
    ) = EventInstance(
        eventId = startHour.toLong(),
        instanceId = startHour.toLong(),
        title = title,
        location = null,
        startMillis = at(startHour, startMinute),
        endMillis = at(endHour, endMinute),
        allDay = allDay,
        calendarId = 1L,
        displayColor = 0,
    )

    /** All-day rows carry UTC-midnight bounds, the way the provider actually stores them. */
    private fun allDayEvent(date: LocalDate = day, title: String = "All day") = EventInstance(
        eventId = 99, instanceId = 99, title = title, location = null,
        startMillis = localDateToUtcMidnight(date),
        endMillis = localDateToUtcMidnight(date.plusDays(1)),
        allDay = true, calendarId = 1L, displayColor = 0,
    )

    private val dayStart get() = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val dayEnd get() = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    // --- busyBlocks -------------------------------------------------------

    @Test
    fun `separate events stay separate`() {
        val blocks = FreeBusy.busyBlocks(listOf(event(9, 10), event(14, 15)), dayStart, dayEnd)
        assertEquals(2, blocks.size)
        assertEquals(at(9), blocks[0].startMillis)
        assertEquals(at(15), blocks[1].endMillis)
    }

    @Test
    fun `overlapping events merge into one block`() {
        val blocks = FreeBusy.busyBlocks(listOf(event(9, 11), event(10, 12)), dayStart, dayEnd)
        assertEquals(1, blocks.size)
        assertEquals(at(9), blocks[0].startMillis)
        assertEquals(at(12), blocks[0].endMillis)
    }

    @Test
    fun `back-to-back events merge into one block`() {
        val blocks = FreeBusy.busyBlocks(listOf(event(9, 10), event(10, 11)), dayStart, dayEnd)
        assertEquals(1, blocks.size)
        assertEquals(at(11), blocks[0].endMillis)
    }

    @Test
    fun `an event fully inside another does not extend it`() {
        val blocks = FreeBusy.busyBlocks(listOf(event(9, 17), event(11, 12)), dayStart, dayEnd)
        assertEquals(1, blocks.size)
        assertEquals(at(9), blocks[0].startMillis)
        assertEquals(at(17), blocks[0].endMillis)
    }

    @Test
    fun `unsorted input still merges correctly`() {
        val blocks = FreeBusy.busyBlocks(listOf(event(14, 15), event(9, 10), event(9, 11)), dayStart, dayEnd)
        assertEquals(2, blocks.size)
        assertEquals(at(9), blocks[0].startMillis)
        assertEquals(at(11), blocks[0].endMillis)
    }

    @Test
    fun `all-day events are not treated as busy time`() {
        val blocks = FreeBusy.busyBlocks(listOf(allDayEvent()), dayStart, dayEnd)
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `zero-length and inverted rows are dropped`() {
        val blocks = FreeBusy.busyBlocks(listOf(event(9, 9), event(15, 14)), dayStart, dayEnd)
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `events are clipped to the window`() {
        // An event running from the previous evening into this morning.
        val overnight = EventInstance(
            eventId = 1, instanceId = 1, title = "Overnight", location = null,
            startMillis = day.minusDays(1).atTime(22, 0).atZone(zone).toInstant().toEpochMilli(),
            endMillis = at(2),
            allDay = false, calendarId = 1, displayColor = 0,
        )
        val blocks = FreeBusy.busyBlocks(listOf(overnight), dayStart, dayEnd)
        assertEquals(1, blocks.size)
        assertEquals(dayStart, blocks[0].startMillis)
        assertEquals(at(2), blocks[0].endMillis)
    }

    // --- conflicts --------------------------------------------------------

    @Test
    fun `overlapping draft reports the clashing event`() {
        val standup = event(15, 16, title = "Standup")
        val draft = NewEvent(
            calendarId = 1, title = "Coffee",
            start = LocalDateTime.of(day, java.time.LocalTime.of(15, 30)),
            end = LocalDateTime.of(day, java.time.LocalTime.of(16, 30)),
        )
        val conflicts = FreeBusy.conflictsWith(draft, listOf(standup), zone)
        assertEquals(1, conflicts.size)
        assertEquals("Standup", conflicts[0].title)
    }

    @Test
    fun `adjacent draft does not conflict`() {
        // Ends exactly when the next starts — the common back-to-back case, and it must read free.
        val standup = event(15, 16, title = "Standup")
        val draft = NewEvent(
            calendarId = 1, title = "Coffee",
            start = LocalDateTime.of(day, java.time.LocalTime.of(16, 0)),
            end = LocalDateTime.of(day, java.time.LocalTime.of(17, 0)),
        )
        assertTrue(FreeBusy.conflictsWith(draft, listOf(standup), zone).isEmpty())
    }

    @Test
    fun `all-day draft never conflicts`() {
        val draft = NewEvent(
            calendarId = 1, title = "Leave",
            start = day.atStartOfDay(), end = day.plusDays(1).atStartOfDay(), allDay = true,
        )
        assertTrue(FreeBusy.conflictsWith(draft, listOf(event(9, 17)), zone).isEmpty())
    }

    @Test
    fun `an existing all-day event does not block a timed draft`() {
        val holiday = allDayEvent(title = "Public holiday")
        val draft = NewEvent(
            calendarId = 1, title = "Errand",
            start = LocalDateTime.of(day, java.time.LocalTime.of(11, 0)),
            end = LocalDateTime.of(day, java.time.LocalTime.of(12, 0)),
        )
        assertTrue(FreeBusy.conflictsWith(draft, listOf(holiday), zone).isEmpty())
    }

    @Test
    fun `inverted draft is rejected rather than matching everything`() {
        val draft = NewEvent(
            calendarId = 1, title = "Broken",
            start = LocalDateTime.of(day, java.time.LocalTime.of(16, 0)),
            end = LocalDateTime.of(day, java.time.LocalTime.of(15, 0)),
        )
        assertTrue(FreeBusy.conflictsWith(draft, listOf(event(9, 23)), zone).isEmpty())
    }

    // --- free gaps --------------------------------------------------------

    @Test
    fun `free gaps are found around meetings`() {
        val events = listOf(event(10, 11), event(14, 15))
        val gaps = FreeBusy.freeGaps(
            events,
            from = LocalDateTime.of(day, java.time.LocalTime.of(9, 0)),
            to = LocalDateTime.of(day, java.time.LocalTime.of(17, 0)),
            zone = zone,
        )
        assertEquals(3, gaps.size)
        assertEquals(at(9), gaps[0].startMillis)
        assertEquals(at(10), gaps[0].endMillis)
        assertEquals(at(11), gaps[1].startMillis)
        assertEquals(at(14), gaps[1].endMillis)
        assertEquals(at(15), gaps[2].startMillis)
        assertEquals(at(17), gaps[2].endMillis)
    }

    @Test
    fun `gaps shorter than the minimum are skipped`() {
        // 09:00–10:00 then 10:05–11:00, leaving only a 5-minute window in between.
        val events = listOf(
            event(9, 10),
            event(10, 11).copy(startMillis = at(10, 5)),
        )
        val gaps = FreeBusy.freeGaps(
            events,
            from = LocalDateTime.of(day, java.time.LocalTime.of(9, 0)),
            to = LocalDateTime.of(day, java.time.LocalTime.of(11, 0)),
            zone = zone,
            minimumMinutes = 15,
        )
        // The 5-minute window between 10:00 and 10:05 is not usable time.
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `a fully free window is one gap`() {
        val gaps = FreeBusy.freeGaps(
            emptyList(),
            from = LocalDateTime.of(day, java.time.LocalTime.of(9, 0)),
            to = LocalDateTime.of(day, java.time.LocalTime.of(17, 0)),
            zone = zone,
        )
        assertEquals(1, gaps.size)
        assertEquals(at(9), gaps[0].startMillis)
        assertEquals(at(17), gaps[0].endMillis)
    }

    // --- occupancy --------------------------------------------------------

    @Test
    fun `occupancy is the merged busy fraction`() {
        // 9-11 and 10-12 merge to 3 hours out of a 24-hour day.
        val fraction = FreeBusy.occupancyFraction(listOf(event(9, 11), event(10, 12)), dayStart, dayEnd)
        assertEquals(3.0 / 24.0, fraction.toDouble(), 0.001)
    }

    @Test
    fun `empty day is zero occupancy`() {
        assertEquals(0f, FreeBusy.occupancyFraction(emptyList(), dayStart, dayEnd), 0.0001f)
    }

    @Test
    fun `an inverted window reports no occupancy rather than dividing by zero`() {
        assertFalse(FreeBusy.occupancyFraction(listOf(event(9, 10)), dayEnd, dayStart) > 0f)
    }

    // --- all-day date handling -------------------------------------------

    @Test
    fun `all-day events resolve to the intended date regardless of local zone`() {
        // Stored as UTC midnight; a device in UTC+8 must still show it on the 20th, not the 19th.
        val utcMidnight = localDateToUtcMidnight(day)
        val allDay = EventInstance(
            eventId = 1, instanceId = 1, title = "Holiday", location = null,
            startMillis = utcMidnight,
            endMillis = localDateToUtcMidnight(day.plusDays(1)),
            allDay = true, calendarId = 1, displayColor = 0,
        )
        assertEquals(day, allDay.localStart(zone).toLocalDate())
        assertEquals(day, allDay.localStart(ZoneId.of("America/Los_Angeles")).toLocalDate())
    }
}
