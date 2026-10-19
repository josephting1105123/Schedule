package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today's top card: what is on now, what is next, and "the rest of the day is yours" only when neither. */
class DayStatusTest {

    private fun event(id: Long, start: Long, end: Long) = EventInstance(
        eventId = id,
        instanceId = id,
        title = "E$id",
        location = null,
        startMillis = start,
        endMillis = end,
        allDay = false,
        calendarId = 1,
        displayColor = 0,
    )

    private val lunch = event(1, 1_000, 2_000)
    private val evening = event(2, 3_000, 6_000)
    private val dinner = event(3, 4_000, 5_000)

    @Test
    fun `an event in progress is current, not skipped`() {
        // The reported case: 19:59 inside a 19:30–22:00 event with nothing after it.
        val status = DayStatus.of(listOf(lunch, evening), nowMillis = 3_500)
        assertEquals(listOf(evening), status.current)
        assertNull(status.next)
        assertTrue(!status.isClear)
    }

    @Test
    fun `overlapping events are all current, earliest first, and the next is the next to start`() {
        val later = event(4, 7_000, 8_000)
        val status = DayStatus.of(listOf(later, dinner, evening), nowMillis = 4_500)
        assertEquals(listOf(evening, dinner), status.current)
        assertEquals(later, status.next)
    }

    @Test
    fun `an event starting exactly now is current, one ending exactly now is not`() {
        val status = DayStatus.of(listOf(lunch, evening), nowMillis = 3_000)
        assertEquals(listOf(evening), status.current)
        val ended = DayStatus.of(listOf(lunch), nowMillis = 2_000)
        assertTrue(ended.current.isEmpty())
    }

    @Test
    fun `between events there is nothing current and the next one is up next`() {
        val status = DayStatus.of(listOf(lunch, evening), nowMillis = 2_500)
        assertTrue(status.current.isEmpty())
        assertEquals(evening, status.next)
    }

    @Test
    fun `only when nothing is on and nothing is left is the day clear`() {
        assertTrue(DayStatus.of(listOf(lunch, evening), nowMillis = 7_000).isClear)
        assertTrue(DayStatus.of(emptyList(), nowMillis = 0).isClear)
    }
}
