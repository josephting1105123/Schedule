package com.EntropicSoftwareLab.Schedule.data.appearance

import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BrandColorAssignmentTest {

    private val palette = listOf(0xFF1E88C7.toInt(), 0xFFD9A441.toInt(), 0xFF2E9C8E.toInt())
    private val day = LocalDate.of(2026, 8, 8)

    private fun event(
        id: Long,
        startHour: Int,
        colorKey: String? = null,
        displayColor: Int = 0xFF888888.toInt(),
        eventColor: Int? = null,
    ) = EventInstance(
        eventId = id,
        instanceId = id,
        title = "Event $id",
        location = null,
        startMillis = startHour * 3_600_000L,
        endMillis = (startHour + 1) * 3_600_000L,
        allDay = false,
        calendarId = 1L,
        displayColor = displayColor,
        eventColorKey = colorKey,
        eventColor = eventColor,
    )

    @Test
    fun `an uncoloured day gets distinct colours in assignment order`() {
        val events = listOf(event(1, 9), event(2, 11), event(3, 14))
        val assigned = BrandColorAssignment.forDay(events, emptyMap(), palette)

        assertEquals(palette[0], assigned[1L])
        assertEquals(palette[1], assigned[2L])
        assertEquals(palette[2], assigned[3L])
        assertEquals(3, assigned.values.toSet().size)
    }

    @Test
    fun `fewer events than colours means every event differs`() {
        val events = (1..3).map { event(it.toLong(), it + 8) }
        val assigned = BrandColorAssignment.forDay(events, emptyMap(), palette)
        assertEquals(events.size, assigned.values.toSet().size)
    }

    @Test
    fun `an app colour is never overwritten`() {
        val custom = 0xFF7A4FC0.toInt()
        val assigned = BrandColorAssignment.forDay(
            events = listOf(event(1, 9), event(2, 10)),
            overrides = mapOf(1L to custom),
            palette = palette,
        )
        assertEquals(custom, assigned[1L])
        assertNotEquals(custom, assigned[2L])
    }

    @Test
    fun `an event with its own provider colour is left absent so displayColor still shows`() {
        // eventColorKey set means somebody chose that colour, here or in Google Calendar. Assigning
        // over it would silently discard a deliberate choice.
        val assigned = BrandColorAssignment.forDay(
            events = listOf(event(1, 9, colorKey = "5")),
            overrides = emptyMap(),
            palette = palette,
        )
        assertNull(assigned[1L])
    }

    @Test
    fun `an assigned colour avoids one an explicit event already wears that day`() {
        val explicit = event(1, 9, colorKey = "3", displayColor = palette[0])
        val assigned = BrandColorAssignment.forDay(
            events = listOf(explicit, event(2, 10)),
            overrides = emptyMap(),
            palette = palette,
        )
        assertNotEquals(palette[0], assigned[2L])
    }

    @Test
    fun `more events than colours wraps rather than leaving any uncoloured`() {
        val events = (1..5).map { event(it.toLong(), it + 8) }
        val assigned = BrandColorAssignment.forDay(events, emptyMap(), palette)
        assertEquals(5, assigned.size)
        assertTrue(assigned.values.all { it in palette })
    }

    @Test
    fun `the result is stable across calls`() {
        val events = listOf(event(3, 14), event(1, 9), event(2, 11))
        val first = BrandColorAssignment.forDay(events, emptyMap(), palette)
        val second = BrandColorAssignment.forDay(events.reversed(), emptyMap(), palette)
        assertEquals(first, second)
    }

    @Test
    fun `ties on start time are broken by id, not by provider order`() {
        val a = event(7, 9)
        val b = event(2, 9)
        val forwards = BrandColorAssignment.forDay(listOf(a, b), emptyMap(), palette)
        val backwards = BrandColorAssignment.forDay(listOf(b, a), emptyMap(), palette)
        assertEquals(forwards, backwards)
        assertEquals(palette[0], forwards[2L])
    }

    @Test
    fun `a multi-day event keeps one colour across every day it touches`() {
        // Assigning per day independently would repaint a week-long trip daily, and the month grid
        // draws it as a single band — which would then be several colours at once.
        val trip = event(1, 9)
        val assigned = BrandColorAssignment.forDates(
            eventsByDate = mapOf(
                day to listOf(trip, event(2, 10)),
                day.plusDays(1) to listOf(trip, event(3, 10)),
                day.plusDays(2) to listOf(trip),
            ),
            overrides = emptyMap(),
            palette = palette,
        )
        assertEquals(palette[0], assigned[1L])
        assertNotEquals(assigned[1L], assigned[2L])
        assertNotEquals(assigned[1L], assigned[3L])
    }

    @Test
    fun `an empty palette changes nothing`() {
        val overrides = mapOf(1L to 0xFF123456.toInt())
        assertEquals(
            overrides,
            BrandColorAssignment.forDay(listOf(event(1, 9), event(2, 10)), overrides, emptyList()),
        )
    }

    @Test
    fun `no events means the overrides come back untouched`() {
        val overrides = mapOf(9L to 0xFF123456.toInt())
        assertEquals(overrides, BrandColorAssignment.forDay(emptyList(), overrides, palette))
    }

    @Test
    fun `a colour set in Google Calendar with no key this app knows is left alone`() {
        // Cherry Blossom is one of the 13 only Google's own apps can set; it arrives as a colour
        // with no writable key, and must still show rather than be painted over.
        val cherry = 0xFFF48FB1.toInt()
        val assigned = BrandColorAssignment.forDay(
            events = listOf(event(1, 9, displayColor = cherry, eventColor = cherry), event(2, 10)),
            overrides = emptyMap(),
            palette = palette,
        )
        assertNull(assigned[1L])
        assertEquals(palette[0], assigned[2L])
    }
}
