package com.EntropicSoftwareLab.Schedule.ai

import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Handles are what stop the tool-calling agent from being able to name an event it never found.
 * Everything about that guarantee is here.
 */
class EventHandlesTest {

    private fun event(id: Long, title: String = "Dentist") = EventInstance(
        eventId = id,
        instanceId = id * 10,
        title = title,
        location = null,
        startMillis = id * 1000,
        endMillis = id * 1000 + 500,
        allDay = false,
        calendarId = 1,
        displayColor = 0,
    )

    @Test
    fun `an invented handle resolves to nothing`() {
        val handles = EventHandles()
        handles.put(event(1))

        // The whole point. A model that writes propose_cancel(event="e9") gets a miss, not
        // somebody's meeting — and a provider id it guessed would have been just as invalid.
        assertNull(handles["e9"])
        assertNull(handles["12345"])
        assertNull(handles["e1x"])
        assertNull(handles[null])
        assertNull(handles[""])
    }

    @Test
    fun `a handle resolves to the event it was minted for`() {
        val handles = EventHandles()
        val dentist = event(1, "Dentist")
        val standup = event(2, "Standup")
        val h1 = handles.put(dentist)
        val h2 = handles.put(standup)

        assertNotEquals(h1, h2)
        assertEquals(dentist.eventId, handles[h1]?.eventId)
        assertEquals(standup.eventId, handles[h2]?.eventId)
    }

    @Test
    fun `the same occurrence twice keeps one handle`() {
        val handles = EventHandles()
        val first = handles.put(event(1))
        val again = handles.put(event(1))
        assertEquals(first, again)
        assertEquals(1, handles.size)
    }

    @Test
    fun `case and whitespace from the model don't cost a lookup`() {
        val handles = EventHandles()
        handles.put(event(1))
        // Minted lower case, but a model that echoes it back shouted or padded still gets a hit.
        assertEquals(1L, handles["E1"]?.eventId)
        assertEquals(1L, handles[" e1 "]?.eventId)
        assertEquals(1L, handles[" E1 "]?.eventId)
        assertNull(handles["nope"])
    }

    @Test
    fun `putAll pairs every event with its handle`() {
        val handles = EventHandles()
        val pairs = handles.putAll(listOf(event(1), event(2), event(3)))
        assertEquals(3, pairs.size)
        assertEquals(listOf("e1", "e2", "e3"), pairs.map { it.first })
        pairs.forEach { (handle, event) -> assertEquals(event.eventId, handles[handle]?.eventId) }
    }

    @Test
    fun `clearing drops every handle, so last turn's cannot be reused`() {
        val handles = EventHandles()
        val handle = handles.put(event(1))
        handles.clear()
        assertNull(handles[handle])
        // Numbering restarts, so e1 next turn is a different event — which is exactly why a handle
        // is scoped to one message and a stale one has to fail rather than resolve.
        assertEquals("e1", handles.put(event(7)))
        assertEquals(7L, handles["e1"]?.eventId)
    }
}
