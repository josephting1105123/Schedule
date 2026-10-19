package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Joining an event's hour slices into one bar on the Today grid. */
class HourRunsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val day: LocalDate = LocalDate.of(2026, 7, 20)

    private fun event(id: Long, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) =
        EventInstance(
            eventId = id,
            instanceId = id,
            title = "Event $id",
            location = null,
            startMillis = day.atTime(startHour, startMinute).atZone(zone).toInstant().toEpochMilli(),
            endMillis = day.atTime(endHour, endMinute).atZone(zone).toInstant().toEpochMilli(),
            allDay = false,
            calendarId = 1L,
            displayColor = 0,
        )

    private fun runs(hours: IntRange, vararg events: EventInstance): List<HourGrid.Run> =
        HourGrid.runs(HourGrid.lanes(events.toList(), day, zone).first(), hours)

    @Test
    fun `a two-hour event is one run across both cells`() {
        val result = runs(0 until 12, event(1, 8, 0, 10, 0))
        assertEquals(1, result.size)
        assertEquals(8f, result.single().start, 1e-4f)
        assertEquals(10f, result.single().end, 1e-4f)
    }

    @Test
    fun `a run keeps its partial ends`() {
        val result = runs(0 until 12, event(1, 8, 30, 10, 15))
        assertEquals(8.5f, result.single().start, 1e-3f)
        assertEquals(10.25f, result.single().end, 1e-3f)
    }

    @Test
    fun `back-to-back different events stay two runs`() {
        val result = runs(0 until 12, event(1, 8, 0, 9, 0), event(2, 9, 0, 10, 0))
        assertEquals(listOf(1L, 2L), result.map { it.event.instanceId })
    }

    @Test
    fun `positions are relative to the row's first hour`() {
        val result = runs(12 until 24, event(1, 14, 0, 17, 0))
        assertEquals(2f, result.single().start, 1e-4f)
        assertEquals(5f, result.single().end, 1e-4f)
    }

    @Test
    fun `an event crossing noon is cut at the row edge`() {
        assertEquals(12f, runs(0 until 12, event(1, 11, 0, 13, 0)).single().end, 1e-4f)
        assertEquals(1f, runs(12 until 24, event(1, 11, 0, 13, 0)).single().end, 1e-4f)
    }
}
