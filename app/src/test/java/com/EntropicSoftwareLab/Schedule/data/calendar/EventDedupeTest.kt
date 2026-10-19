package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The case this exists for: a phone signed into four Google accounts subscribes to the same national
 * holiday calendar from each, and one day off arrives as four rows.
 */
class EventDedupeTest {

    private fun event(
        eventId: Long,
        calendarId: Long,
        title: String = "The Prophet Muhammad's Birthday (tentative)",
        start: Long = 1_000L,
        end: Long = 2_000L,
        allDay: Boolean = true,
    ) = EventInstance(
        eventId = eventId,
        instanceId = eventId * 10,
        title = title,
        location = null,
        startMillis = start,
        endMillis = end,
        allDay = allDay,
        calendarId = calendarId,
        displayColor = 0,
    )

    @Test
    fun `one holiday on four calendars is one row`() {
        val rows = listOf(
            event(eventId = 40, calendarId = 4),
            event(eventId = 10, calendarId = 1),
            event(eventId = 30, calendarId = 3),
            event(eventId = 20, calendarId = 2),
        )
        val collapsed = EventDedupe.collapseIdentical(rows)
        assertEquals(1, collapsed.size)
        // The lowest calendar wins, not whichever row the provider happened to return first — the
        // row the user taps to edit must be the same row on every read.
        assertEquals(1L, collapsed.first().calendarId)
    }

    @Test
    fun `anything that actually differs survives`() {
        val rows = listOf(
            event(eventId = 1, calendarId = 1, title = "Standup", start = 0, end = 100, allDay = false),
            event(eventId = 2, calendarId = 2, title = "Standup", start = 1, end = 100, allDay = false),
            event(eventId = 3, calendarId = 2, title = "Stand-up", start = 0, end = 100, allDay = false),
            event(eventId = 4, calendarId = 2, title = "Standup", start = 0, end = 101, allDay = false),
        )
        assertEquals(4, EventDedupe.collapseIdentical(rows).size)
    }

    @Test
    fun `an all-day copy and a timed copy are not the same event`() {
        val rows = listOf(
            event(eventId = 1, calendarId = 1, allDay = true),
            event(eventId = 2, calendarId = 2, allDay = false),
        )
        assertEquals(2, EventDedupe.collapseIdentical(rows).size)
    }

    @Test
    fun `the caller's ordering survives`() {
        val rows = listOf(
            event(eventId = 1, calendarId = 1, title = "Early", start = 0, end = 10, allDay = false),
            event(eventId = 2, calendarId = 1, title = "Late", start = 500, end = 600, allDay = false),
            event(eventId = 3, calendarId = 9, title = "Late", start = 500, end = 600, allDay = false),
        )
        val collapsed = EventDedupe.collapseIdentical(rows)
        assertEquals(listOf("Early", "Late"), collapsed.map { it.title })
    }

    @Test
    fun `a list with nothing to collapse is handed straight back`() {
        val rows = listOf(event(eventId = 1, calendarId = 1))
        assertSame(rows, EventDedupe.collapseIdentical(rows))

        val two = listOf(
            event(eventId = 1, calendarId = 1, title = "A"),
            event(eventId = 2, calendarId = 1, title = "B"),
        )
        assertSame(two, EventDedupe.collapseIdentical(two))
    }
}
