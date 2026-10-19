package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Lane assignment and fractional hour coverage for the Today grid. */
class HourLanesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val day: LocalDate = LocalDate.of(2026, 7, 20)

    private fun at(hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        id: Long,
        startHour: Int,
        endHour: Int,
        startMinute: Int = 0,
        endMinute: Int = 0,
    ) = EventInstance(
        eventId = id,
        instanceId = id,
        title = "Event $id",
        location = null,
        startMillis = at(startHour, startMinute),
        endMillis = at(endHour, endMinute),
        allDay = false,
        calendarId = 1L,
        displayColor = 0,
    )

    private fun lanes(vararg events: EventInstance) = HourGrid.lanes(events.toList(), day, zone)

    @Test
    fun `a day without clashes uses one lane`() {
        val result = lanes(event(1, 9, 10), event(2, 11, 12), event(3, 14, 15))
        assertEquals(1, result.size)
    }

    @Test
    fun `two overlapping events take a lane each`() {
        val result = lanes(event(1, 9, 11), event(2, 10, 12))
        assertEquals(2, result.size)
        assertEquals("Event 1", result[0][9].single().event.title)
        assertEquals("Event 2", result[1][10].single().event.title)
        // Hour 10 is the clash: one event in each lane, neither sharing a cell.
        assertEquals(1, result[0][10].size)
        assertEquals(1, result[1][10].size)
    }

    @Test
    fun `three-way overlap takes three lanes`() {
        assertEquals(3, lanes(event(1, 9, 12), event(2, 10, 12), event(3, 11, 12)).size)
    }

    @Test
    fun `peak overlap sets lane count, not total event count`() {
        // Two clash in the morning, two clash in the evening, but never more than two at once.
        val result = lanes(event(1, 9, 11), event(2, 10, 12), event(3, 18, 20), event(4, 19, 21))
        assertEquals(2, result.size)
    }

    @Test
    fun `back-to-back events share a lane`() {
        // 9-10 and 10-11 don't overlap, so stacking them would imply a clash that isn't there.
        val result = lanes(event(1, 9, 10), event(2, 10, 11))
        assertEquals(1, result.size)
    }

    @Test
    fun `a full hour covers the whole cell`() {
        val slice = lanes(event(1, 9, 10))[0][9].single()
        assertEquals(0f, slice.startFraction, 0.001f)
        assertEquals(1f, slice.endFraction, 0.001f)
    }

    @Test
    fun `an event ending at half past covers half the cell`() {
        // 01:00-01:30 is the user's example: it should occupy half a grid cell, not a whole one.
        val slice = lanes(event(1, 1, 1, endMinute = 30))[0][1].single()
        assertEquals(0f, slice.startFraction, 0.001f)
        assertEquals(0.5f, slice.endFraction, 0.001f)
    }

    @Test
    fun `an event starting mid-hour is offset into the cell`() {
        val slice = lanes(event(1, 9, 10, startMinute = 15))[0][9].single()
        assertEquals(0.25f, slice.startFraction, 0.001f)
        assertEquals(1f, slice.endFraction, 0.001f)
    }

    @Test
    fun `two sub-hour events in one hour share the cell without a second lane`() {
        // The only case where one cell legitimately holds two slices: they abut, not overlap.
        val result = lanes(event(1, 9, 9, endMinute = 30), event(2, 9, 10, startMinute = 30))
        assertEquals(1, result.size)
        val slices = result[0][9]
        assertEquals(2, slices.size)
        assertEquals(0.5f, slices[0].endFraction, 0.001f)
        assertEquals(0.5f, slices[1].startFraction, 0.001f)
    }

    @Test
    fun `a multi-hour event fills every hour it spans`() {
        val lane = lanes(event(1, 9, 12))[0]
        assertTrue(lane[9].isNotEmpty() && lane[10].isNotEmpty() && lane[11].isNotEmpty())
        // Half-open: an event ending at 12:00 does not light hour 12.
        assertTrue(lane[12].isEmpty())
        assertTrue(lane[8].isEmpty())
    }

    @Test
    fun `all-day events are excluded`() {
        val allDay = EventInstance(
            eventId = 9, instanceId = 9, title = "Holiday", location = null,
            startMillis = localDateToUtcMidnight(day),
            endMillis = localDateToUtcMidnight(day.plusDays(1)),
            allDay = true, calendarId = 1L, displayColor = 0,
        )
        assertTrue(HourGrid.lanes(listOf(allDay), day, zone).isEmpty())
    }

    @Test
    fun `an event running past midnight is clipped to the day`() {
        val overnight = EventInstance(
            eventId = 1, instanceId = 1, title = "Overnight", location = null,
            startMillis = at(23, 0),
            endMillis = day.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli(),
            allDay = false, calendarId = 1L, displayColor = 0,
        )
        val lane = HourGrid.lanes(listOf(overnight), day, zone)[0]
        assertEquals(1f, lane[23].single().endFraction, 0.001f)
        assertEquals(24, lane.size)
    }

    @Test
    fun `zero-length and inverted events are dropped`() {
        assertTrue(lanes(event(1, 9, 9)).isEmpty())
        assertTrue(lanes(event(1, 11, 9)).isEmpty())
    }

    @Test
    fun `lane order is stable regardless of input order`() {
        val a = event(1, 9, 11)
        val b = event(2, 10, 12)
        val forward = HourGrid.lanes(listOf(a, b), day, zone)
        val reversed = HourGrid.lanes(listOf(b, a), day, zone)
        assertEquals(
            forward.map { lane -> lane.flatten().map { it.event.instanceId } },
            reversed.map { lane -> lane.flatten().map { it.event.instanceId } },
        )
    }
}
