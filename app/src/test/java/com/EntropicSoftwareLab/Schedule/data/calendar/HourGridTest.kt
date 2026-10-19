package com.EntropicSoftwareLab.Schedule.data.calendar

import com.EntropicSoftwareLab.Schedule.data.calendar.HourGrid.EventColorKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HourGridTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val day: LocalDate = LocalDate.of(2026, 7, 20)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        startHour: Int,
        endHour: Int,
        startMinute: Int = 0,
        endMinute: Int = 0,
        id: Long = startHour.toLong(),
        startDate: LocalDate = day,
        endDate: LocalDate = day,
        calendarId: Long = 1L,
        displayColor: Int = 0,
    ) = EventInstance(
        eventId = id,
        instanceId = id,
        title = "Event $id",
        location = null,
        startMillis = at(startDate, startHour, startMinute),
        endMillis = at(endDate, endHour, endMinute),
        allDay = false,
        calendarId = calendarId,
        displayColor = displayColor,
    )

    private fun allDayEvent(id: Long = 99) = EventInstance(
        eventId = id, instanceId = id, title = "All day", location = null,
        startMillis = localDateToUtcMidnight(day),
        endMillis = localDateToUtcMidnight(day.plusDays(1)),
        allDay = true, calendarId = 1L, displayColor = 0,
    )

    private fun occupiedHours(cells: List<List<EventInstance>>): List<Int> =
        cells.indices.filter { cells[it].isNotEmpty() }

    // --- bucketByHour -----------------------------------------------------

    @Test
    fun `grid always has 24 cells`() {
        assertEquals(24, HourGrid.bucketByHour(emptyList(), day, zone).size)
    }

    @Test
    fun `event ending on the hour does not claim the hour it touches`() {
        val cells = HourGrid.bucketByHour(listOf(event(9, 10)), day, zone)
        assertEquals(listOf(9), occupiedHours(cells))
    }

    @Test
    fun `partial hours light every hour they touch`() {
        val cells = HourGrid.bucketByHour(listOf(event(9, 11, startMinute = 30, endMinute = 15)), day, zone)
        assertEquals(listOf(9, 10, 11), occupiedHours(cells))
    }

    @Test
    fun `multi-hour event lights the whole span`() {
        val cells = HourGrid.bucketByHour(listOf(event(8, 17)), day, zone)
        assertEquals((8..16).toList(), occupiedHours(cells))
    }

    @Test
    fun `event running to midnight lights through hour 23`() {
        val cells = HourGrid.bucketByHour(listOf(event(22, 0, endDate = day.plusDays(1))), day, zone)
        assertEquals(listOf(22, 23), occupiedHours(cells))
    }

    @Test
    fun `event starting yesterday is clipped to the start of the day`() {
        val cells = HourGrid.bucketByHour(
            listOf(event(23, 1, startDate = day.minusDays(1))),
            day,
            zone,
        )
        assertEquals(listOf(0), occupiedHours(cells))
    }

    @Test
    fun `event spilling into tomorrow is clipped to the end of the day`() {
        val cells = HourGrid.bucketByHour(
            listOf(event(23, 1, endDate = day.plusDays(1))),
            day,
            zone,
        )
        assertEquals(listOf(23), occupiedHours(cells))
    }

    @Test
    fun `event entirely on another day lights nothing`() {
        val cells = HourGrid.bucketByHour(
            listOf(event(9, 10, startDate = day.plusDays(2), endDate = day.plusDays(2))),
            day,
            zone,
        )
        assertTrue(occupiedHours(cells).isEmpty())
    }

    @Test
    fun `overlapping events share a cell in start order`() {
        val early = event(9, 11, id = 1)
        val late = event(9, 10, startMinute = 30, id = 2)
        val cells = HourGrid.bucketByHour(listOf(late, early), day, zone)
        assertEquals(listOf(9, 10), occupiedHours(cells))
        assertEquals(2, cells[9].size)
        assertEquals(listOf(2L, 1L), cells[9].map { it.instanceId })
    }

    @Test
    fun `all-day events never reach the hour axis`() {
        val cells = HourGrid.bucketByHour(listOf(allDayEvent()), day, zone)
        assertTrue(occupiedHours(cells).isEmpty())
    }

    @Test
    fun `zero-length and inverted rows are ignored`() {
        val zeroLength = event(9, 9, id = 1)
        val inverted = event(15, 14, id = 2)
        val cells = HourGrid.bucketByHour(listOf(zeroLength, inverted), day, zone)
        assertTrue(occupiedHours(cells).isEmpty())
    }

    /**
     * On a spring-forward day the local clock skips 02:00–03:00, so cell 2 is zero-width. What
     * matters is that every other cell still lines up with the hour it is labelled: the evening
     * event must not drift a column, which is exactly what a fixed 3,600,000 ms step would do.
     * The skipped cell fills too, keeping an event that spans the transition unbroken.
     */
    @Test
    fun `dst spring forward keeps cells aligned to local clock hours`() {
        val dstZone = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2026, 3, 8)
        val morning = EventInstance(
            eventId = 1, instanceId = 1, title = "Early", location = null,
            startMillis = dstDay.atTime(1, 0).atZone(dstZone).toInstant().toEpochMilli(),
            endMillis = dstDay.atTime(4, 0).atZone(dstZone).toInstant().toEpochMilli(),
            allDay = false, calendarId = 1L, displayColor = 0,
        )
        val evening = EventInstance(
            eventId = 2, instanceId = 2, title = "Evening", location = null,
            startMillis = dstDay.atTime(20, 0).atZone(dstZone).toInstant().toEpochMilli(),
            endMillis = dstDay.atTime(21, 0).atZone(dstZone).toInstant().toEpochMilli(),
            allDay = false, calendarId = 1L, displayColor = 0,
        )
        val cells = HourGrid.bucketByHour(listOf(morning, evening), dstDay, dstZone)
        // 1 and 3 are the real hours the morning event covers; 2 is the skipped hour it spans.
        // 20 is the one that matters most — it must not drift to 19 or 21.
        assertEquals(listOf(1, 2, 3, 20), occupiedHours(cells))
    }

    /** The autumn counterpart: 01:00 happens twice, so cell 1 covers both passes through it. */
    @Test
    fun `dst fall back folds the repeated hour into one cell`() {
        val dstZone = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2026, 11, 1)
        // 01:30 EDT and 01:30 EST are an hour apart in real time but share a clock label.
        val firstPass = EventInstance(
            eventId = 1, instanceId = 1, title = "First", location = null,
            startMillis = dstDay.atTime(1, 15).atZone(dstZone).toInstant().toEpochMilli(),
            endMillis = dstDay.atTime(1, 45).atZone(dstZone).toInstant().toEpochMilli(),
            allDay = false, calendarId = 1L, displayColor = 0,
        )
        val evening = EventInstance(
            eventId = 2, instanceId = 2, title = "Evening", location = null,
            startMillis = dstDay.atTime(20, 0).atZone(dstZone).toInstant().toEpochMilli(),
            endMillis = dstDay.atTime(21, 0).atZone(dstZone).toInstant().toEpochMilli(),
            allDay = false, calendarId = 1L, displayColor = 0,
        )
        val cells = HourGrid.bucketByHour(listOf(firstPass, evening), dstDay, dstZone)
        assertEquals(listOf(1, 20), occupiedHours(cells))
    }

    // --- assignColors -----------------------------------------------------

    private val calendarColors = mapOf(1L to 0xFF3F51B5.toInt(), 2L to 0xFF009688.toInt())

    @Test
    fun `palette indices are dense and follow start order`() {
        val keys = HourGrid.assignColors(
            listOf(event(14, 15, id = 3), event(9, 10, id = 1), event(11, 12, id = 2)),
            calendarColors,
        )
        assertEquals(EventColorKey.Palette(0), keys[1L])
        assertEquals(EventColorKey.Palette(1), keys[2L])
        assertEquals(EventColorKey.Palette(2), keys[3L])
    }

    @Test
    fun `assignment is independent of query order`() {
        val events = listOf(event(9, 10, id = 1), event(11, 12, id = 2), event(14, 15, id = 3))
        assertEquals(
            HourGrid.assignColors(events, calendarColors),
            HourGrid.assignColors(events.reversed(), calendarColors),
        )
    }

    @Test
    fun `an event recoloured by the user keeps its colour and skips the palette`() {
        val custom = event(9, 10, id = 1, displayColor = 0xFFE91E63.toInt())
        val plain = event(11, 12, id = 2, displayColor = 0xFF3F51B5.toInt())
        val keys = HourGrid.assignColors(listOf(custom, plain), calendarColors)
        assertEquals(EventColorKey.Custom(0xFFE91E63.toInt()), keys[1L])
        // The custom event consumed no slot, so the next event still starts the palette.
        assertEquals(EventColorKey.Palette(0), keys[2L])
    }

    @Test
    fun `an event matching its calendar colour is treated as uncoloured`() {
        val keys = HourGrid.assignColors(
            listOf(event(9, 10, id = 1, calendarId = 2L, displayColor = 0xFF009688.toInt())),
            calendarColors,
        )
        assertEquals(EventColorKey.Palette(0), keys[1L])
    }

    @Test
    fun `alpha differences alone do not count as a custom colour`() {
        // The provider hands back colours with no alpha; asCalendarColor forces them opaque, and
        // the comparison has to do the same or every event would look custom.
        val keys = HourGrid.assignColors(
            listOf(event(9, 10, id = 1, displayColor = 0x003F51B5)),
            mapOf(1L to 0xFF3F51B5.toInt()),
        )
        assertEquals(EventColorKey.Palette(0), keys[1L])
    }

    @Test
    fun `unknown or unset calendar colour falls back to the palette`() {
        val keys = HourGrid.assignColors(
            listOf(
                event(9, 10, id = 1, calendarId = 77L, displayColor = 0xFFE91E63.toInt()),
                event(11, 12, id = 2, calendarId = 1L, displayColor = 0),
            ),
            calendarColors,
        )
        assertEquals(EventColorKey.Palette(0), keys[1L])
        assertEquals(EventColorKey.Palette(1), keys[2L])
    }

    @Test
    fun `two occurrences of one series get separate colours`() {
        val first = event(9, 10, id = 1).copy(eventId = 500, instanceId = 1000)
        val second = event(14, 15, id = 2).copy(eventId = 500, instanceId = 1001)
        val keys = HourGrid.assignColors(listOf(first, second), calendarColors)
        assertEquals(2, keys.size)
        assertEquals(EventColorKey.Palette(0), keys[1000L])
        assertEquals(EventColorKey.Palette(1), keys[1001L])
    }

    @Test
    fun `all-day events are not assigned grid colours`() {
        assertTrue(HourGrid.assignColors(listOf(allDayEvent()), calendarColors).isEmpty())
    }
}
