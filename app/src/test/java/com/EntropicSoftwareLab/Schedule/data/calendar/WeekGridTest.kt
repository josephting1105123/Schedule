package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Block placement on the Week page's timetable. Runs at +08:00, not UTC, on purpose. */
class WeekGridTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val day: LocalDate = LocalDate.of(2026, 9, 28)

    private fun timed(id: Long, start: LocalDateTime, end: LocalDateTime) = EventInstance(
        eventId = id,
        instanceId = id,
        title = "Event $id",
        location = null,
        startMillis = start.atZone(zone).toInstant().toEpochMilli(),
        endMillis = end.atZone(zone).toInstant().toEpochMilli(),
        allDay = false,
        calendarId = 1L,
        displayColor = 0,
    )

    private fun at(h: Int, m: Int = 0) = day.atTime(h, m)

    private fun blocks(vararg events: EventInstance) = WeekGrid.blocks(events.toList(), day, zone)

    @Test
    fun `a lone event takes the whole column at its clock minutes`() {
        val block = blocks(timed(1, at(8), at(10, 30))).single()
        assertEquals(8 * 60, block.startMinute)
        assertEquals(10 * 60 + 30, block.endMinute)
        assertEquals(0, block.lane)
        assertEquals(1, block.lanes)
    }

    @Test
    fun `overlapping events split the column`() {
        val result = blocks(timed(1, at(9), at(11)), timed(2, at(10), at(12)))
        assertEquals(listOf(0, 1), result.map { it.lane })
        assertTrue(result.all { it.lanes == 2 })
    }

    @Test
    fun `a clash later in the day doesn't narrow a morning event`() {
        val result = blocks(timed(1, at(8), at(9)), timed(2, at(15), at(17)), timed(3, at(16), at(18)))
        assertEquals(1, result.first { it.event.instanceId == 1L }.lanes)
        assertEquals(2, result.first { it.event.instanceId == 3L }.lanes)
    }

    @Test
    fun `back-to-back events share one lane`() {
        val result = blocks(timed(1, at(8), at(9)), timed(2, at(9), at(10)))
        assertTrue(result.all { it.lane == 0 && it.lanes == 1 })
    }

    @Test
    fun `an event ending at midnight runs to the bottom of the column`() {
        val block = blocks(timed(1, at(22), day.plusDays(1).atStartOfDay())).single()
        assertEquals(WeekGrid.MINUTES_PER_DAY, block.endMinute)
    }

    @Test
    fun `an event crossing midnight is left to the all-day strip`() {
        assertTrue(blocks(timed(1, at(22), day.plusDays(1).atTime(2, 0))).isEmpty())
    }

    @Test
    fun `an event on another day is not placed here`() {
        assertTrue(blocks(timed(1, day.plusDays(1).atTime(9, 0), day.plusDays(1).atTime(10, 0))).isEmpty())
    }

}
