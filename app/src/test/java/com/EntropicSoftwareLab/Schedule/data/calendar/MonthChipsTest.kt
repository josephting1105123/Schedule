package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Runs at +08:00 rather than UTC on purpose: an all-day event is stored at UTC midnight, so a
 * conversion that quietly uses the wrong zone still passes every UTC test and puts the band a day
 * out for every real user.
 */
class MonthChipsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")

    /** Monday 10 August 2026 through Sunday the 16th. */
    private val week: List<LocalDate> = (0L..6L).map { LocalDate.of(2026, 8, 10).plusDays(it) }

    private fun timed(
        id: Long,
        start: LocalDateTime,
        end: LocalDateTime,
        title: String = "Event $id",
    ) = EventInstance(
        eventId = id,
        instanceId = id,
        title = title,
        location = null,
        startMillis = start.atZone(zone).toInstant().toEpochMilli(),
        endMillis = end.atZone(zone).toInstant().toEpochMilli(),
        allDay = false,
        calendarId = 1L,
        displayColor = 0,
    )

    /** [endExclusive] is the day *after* the last, which is how the provider stores it. */
    private fun allDay(
        id: Long,
        start: LocalDate,
        endExclusive: LocalDate,
        title: String = "All day $id",
    ) = EventInstance(
        eventId = id,
        instanceId = id,
        title = title,
        location = null,
        startMillis = utcMidnight(start),
        endMillis = utcMidnight(endExclusive),
        allDay = true,
        calendarId = 1L,
        displayColor = 0,
    )

    private fun utcMidnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int): LocalDateTime =
        LocalDate.of(2026, 8, day).atTime(hour, 0)

    private fun layoutOf(vararg events: EventInstance, maxRows: Int = 2): WeekChips =
        MonthChips.layout(week, MonthChips.byDate(events.toList(), zone), zone, maxRows)

    private fun WeekChips.chipFor(title: String): MonthChip =
        chips.single { it.event.title == title }

    // --- daySpan ---------------------------------------------------------------------------------

    @Test
    fun `an all-day event covers up to the day before its exclusive end`() {
        val span = MonthChips.daySpan(
            allDay(1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)),
            zone,
        )
        assertEquals(LocalDate.of(2026, 8, 10), span.first)
        assertEquals(LocalDate.of(2026, 8, 12), span.last)
    }

    @Test
    fun `a single all-day event covers exactly one day`() {
        val span = MonthChips.daySpan(
            allDay(1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11)),
            zone,
        )
        assertEquals(span.first, span.last)
    }

    @Test
    fun `a meeting ending exactly at midnight stays on the day it started`() {
        val span = MonthChips.daySpan(
            timed(1, at(10, 22), LocalDate.of(2026, 8, 11).atTime(LocalTime.MIDNIGHT)),
            zone,
        )
        assertEquals(LocalDate.of(2026, 8, 10), span.last)
    }

    @Test
    fun `a meeting running past midnight covers both days`() {
        val span = MonthChips.daySpan(timed(1, at(10, 22), at(11, 1)), zone)
        assertEquals(LocalDate.of(2026, 8, 10), span.first)
        assertEquals(LocalDate.of(2026, 8, 11), span.last)
    }

    @Test
    fun `a row ending before it starts is given the day it started on`() {
        val span = MonthChips.daySpan(timed(1, at(12, 9), at(10, 9)), zone)
        assertEquals(LocalDate.of(2026, 8, 12), span.first)
        assertEquals(LocalDate.of(2026, 8, 12), span.last)
    }

    // --- byDate ----------------------------------------------------------------------------------

    @Test
    fun `a multi-day event appears on every day it covers`() {
        val trip = allDay(1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13))
        val byDate = MonthChips.byDate(listOf(trip), zone)
        assertEquals(
            listOf(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 12)),
            byDate.keys.sorted(),
        )
        assertTrue(byDate.values.all { it == listOf(trip) })
    }

    // --- layout ----------------------------------------------------------------------------------

    @Test
    fun `events on one day stack down the cell, earliest on top`() {
        val layout = layoutOf(
            timed(2, at(12, 14), at(12, 15), title = "Afternoon"),
            timed(1, at(12, 9), at(12, 10), title = "Morning"),
        )
        assertEquals(0, layout.chipFor("Morning").row)
        assertEquals(1, layout.chipFor("Afternoon").row)
        assertTrue(layout.hidden.isEmpty())
    }

    @Test
    fun `a day carries one chip per column it occupies`() {
        val layout = layoutOf(timed(1, at(12, 9), at(12, 10), title = "Standup"))
        val chip = layout.chipFor("Standup")
        // Wednesday the 12th is the third column of a week starting Monday the 10th.
        assertEquals(2, chip.startColumn)
        assertEquals(1, chip.span)
        assertFalse(chip.continuesBefore)
        assertFalse(chip.continuesAfter)
    }

    @Test
    fun `what will not fit is counted against the day rather than dropped silently`() {
        val layout = layoutOf(
            timed(1, at(12, 9), at(12, 10)),
            timed(2, at(12, 11), at(12, 12)),
            timed(3, at(12, 14), at(12, 15)),
            timed(4, at(12, 16), at(12, 17)),
        )
        assertEquals(2, layout.chips.size)
        assertEquals(2, layout.hidden[2])
        assertNull(layout.hidden[3])
    }

    @Test
    fun `a hidden band counts against every day it would have covered`() {
        val layout = layoutOf(
            allDay(1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), title = "A"),
            allDay(2, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), title = "B"),
            allDay(3, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), title = "C"),
        )
        assertEquals(2, layout.chips.size)
        assertEquals(1, layout.hidden[0])
        assertEquals(1, layout.hidden[1])
        assertNull(layout.hidden[2])
    }

    @Test
    fun `a band pushes a busy day's own events down and out`() {
        val layout = layoutOf(
            timed(1, at(10, 9), at(10, 10)),
            timed(2, at(10, 11), at(10, 12)),
            allDay(3, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12)),
        )
        // The band is the longer run, so it takes row 0 and the two meetings fill rows 1 and 2 —
        // the later meeting drops out, on the one day it touches.
        assertEquals(1, layout.hidden[0])
        assertNull(layout.hidden[1])
    }

    @Test
    fun `a multi-day event is one band across the days it covers`() {
        val layout = layoutOf(
            allDay(1, LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 14), title = "Conference"),
        )
        val chip = layout.chipFor("Conference")
        assertEquals(1, chip.startColumn)
        assertEquals(3, chip.span)
        assertFalse(chip.continuesBefore)
        assertFalse(chip.continuesAfter)
    }

    @Test
    fun `a band settles above the short events it passes over`() {
        val layout = layoutOf(
            timed(1, at(11, 9), at(11, 10), title = "Standup"),
            allDay(2, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14), title = "Trip"),
        )
        assertEquals(0, layout.chipFor("Trip").row)
        assertEquals(1, layout.chipFor("Standup").row)
    }

    @Test
    fun `an event outside a band's run reuses the band's row`() {
        val layout = layoutOf(
            allDay(1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), title = "Trip"),
            timed(2, at(15, 9), at(15, 10), title = "Saturday lunch"),
        )
        assertEquals(0, layout.chipFor("Trip").row)
        val lunch = layout.chipFor("Saturday lunch")
        assertEquals(5, lunch.startColumn)
        assertEquals(0, lunch.row)
    }

    @Test
    fun `a week wholly inside a long event is a full-width band open at both ends`() {
        val layout = layoutOf(
            allDay(1, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 25), title = "Mission trip"),
        )
        val chip = layout.chipFor("Mission trip")
        assertEquals(0, chip.startColumn)
        assertEquals(7, chip.span)
        assertTrue(chip.continuesBefore)
        assertTrue(chip.continuesAfter)
    }

    @Test
    fun `an event running into the next week is square only at the week boundary`() {
        val layout = layoutOf(
            allDay(1, LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 20), title = "Retreat"),
        )
        val chip = layout.chipFor("Retreat")
        assertEquals(4, chip.startColumn)
        assertEquals(3, chip.span)
        assertFalse(chip.continuesBefore)
        assertTrue(chip.continuesAfter)
    }

    @Test
    fun `an all-day event outranks a timed one of the same length`() {
        val layout = layoutOf(
            timed(1, at(12, 9), at(12, 10), title = "Standup"),
            allDay(2, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13), title = "Public holiday"),
        )
        assertEquals(0, layout.chipFor("Public holiday").row)
        assertEquals(1, layout.chipFor("Standup").row)
    }

    @Test
    fun `an empty week lays out to nothing`() {
        val layout = layoutOf()
        assertTrue(layout.chips.isEmpty())
        assertTrue(layout.hidden.isEmpty())
    }

    // --- layoutWithMoreRow: the widget's "•••" ---------------------------------------------------

    private fun moreOf(vararg events: EventInstance, maxRows: Int = 2): WeekChips =
        MonthChips.layoutWithMoreRow(week, MonthChips.byDate(events.toList(), zone), zone, maxRows)

    @Test
    fun `a day that fits keeps every row for chips`() {
        val layout = moreOf(
            timed(1, at(12, 9), at(12, 10)),
            timed(2, at(12, 11), at(12, 12)),
        )
        assertEquals(2, layout.chips.size)
        assertTrue(layout.hidden.isEmpty())
    }

    @Test
    fun `an overflowing day gives its last row to the marker`() {
        val layout = moreOf(
            timed(1, at(10, 9), at(10, 10)),
            timed(2, at(10, 11), at(10, 12)),
            timed(3, at(10, 13), at(10, 14)),
        )
        assertEquals(listOf(0), layout.chips.map { it.row })
        assertEquals(setOf(0), layout.hidden.keys)
    }

    @Test
    fun `a busy day does not take a quiet neighbour's second chip`() {
        val layout = moreOf(
            timed(1, at(10, 9), at(10, 10)),
            timed(2, at(10, 11), at(10, 12)),
            timed(3, at(10, 13), at(10, 14)),
            timed(4, at(12, 9), at(12, 10)),
            timed(5, at(12, 11), at(12, 12)),
        )
        assertEquals(setOf(0), layout.hidden.keys)
        assertEquals(2, layout.chips.count { it.startColumn == 2 })
    }

    @Test
    fun `a last-row band crossing an overflowing day is replaced and marks every day it covered`() {
        val layout = moreOf(
            allDay(1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 15), title = "Trip"),
            allDay(2, LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), title = "Conference"),
            timed(3, at(11, 9), at(11, 10)),
        )
        assertEquals(listOf("Trip"), layout.chips.map { it.event.title })
        assertEquals(setOf(1, 2), layout.hidden.keys)
    }

    @Test
    fun `with a single row nothing is given up to the marker`() {
        val layout = moreOf(
            timed(1, at(10, 9), at(10, 10)),
            timed(2, at(10, 11), at(10, 12)),
            maxRows = 1,
        )
        assertEquals(1, layout.chips.size)
        assertEquals(mapOf(0 to 1), layout.hidden)
    }

    @Test
    fun `a single-day all-day event is a band`() {
        assertTrue(MonthChips.isBand(allDay(1, week[2], week[3]), zone))
    }

    @Test
    fun `a timed event inside one day is a dot`() {
        val start = week[2].atTime(9, 0)
        assertFalse(MonthChips.isBand(timed(1, start, start.plusHours(2)), zone))
    }

    @Test
    fun `a timed event ending at midnight is still one day, so a dot`() {
        val start = week[2].atTime(22, 0)
        assertFalse(MonthChips.isBand(timed(1, start, week[3].atStartOfDay()), zone))
    }

    @Test
    fun `a timed event crossing midnight is a band`() {
        val start = week[2].atTime(22, 0)
        assertTrue(MonthChips.isBand(timed(1, start, week[3].atTime(2, 0)), zone))
    }
    // --- layoutWithDots: the widget's bands-and-dots week ----------------------------------------

    private fun dotsOf(vararg events: EventInstance, maxRows: Int = 2, maxDots: Int = 4): WeekWithDots =
        MonthChips.layoutWithDots(week, MonthChips.byDate(events.toList(), zone), zone, maxRows, maxDots)

    @Test
    fun `a timed event is a dot on the widget, not a named chip`() {
        val layout = dotsOf(timed(1, at(12, 9), at(12, 10)))
        assertTrue(layout.bands.chips.isEmpty())
        assertEquals(DayDots(row = 0, shown = listOf(timed(1, at(12, 9), at(12, 10))), extra = 0), layout.dots[2])
    }

    @Test
    fun `dots sit in the first row the day's bands leave free`() {
        val layout = dotsOf(
            allDay(1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 15), title = "Trip"),
            timed(2, at(11, 9), at(11, 10)),
        )
        assertEquals(listOf("Trip"), layout.bands.chips.map { it.event.title })
        assertEquals(1, layout.dots.getValue(1).row)
        assertTrue(layout.bands.hidden.isEmpty())
    }

    @Test
    fun `a day full of bands counts its timed events into the marker`() {
        val layout = dotsOf(
            allDay(1, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 15), title = "Trip"),
            allDay(2, LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), title = "Conference"),
            timed(3, at(11, 9), at(11, 10)),
        )
        // Conference is in the last row and crosses the 11th, so it gives way to the marker.
        assertEquals(listOf("Trip"), layout.bands.chips.map { it.event.title })
        assertEquals(setOf(1, 2), layout.bands.hidden.keys)
        assertNull(layout.dots[1])
    }

    @Test
    fun `dots never share the marker's row`() {
        // The 12th: one band in row 0 and a second band hidden, so row 1 is the marker's.
        val layout = dotsOf(
            allDay(1, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13), title = "A"),
            allDay(2, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13), title = "B"),
            allDay(3, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 13), title = "C"),
            timed(4, at(12, 9), at(12, 10)),
        )
        assertNull(layout.dots[2])
        assertEquals(setOf(2), layout.bands.hidden.keys)
        // Two bands and the timed event are off screen, all reported by the one marker.
        assertEquals(3, layout.bands.hidden.getValue(2))
    }

    @Test
    fun `a busy day shows one dot fewer than the cap and counts the rest`() {
        val layout = dotsOf(
            timed(1, at(10, 8), at(10, 9)),
            timed(2, at(10, 9), at(10, 10)),
            timed(3, at(10, 10), at(10, 11)),
            timed(4, at(10, 11), at(10, 12)),
            timed(5, at(10, 12), at(10, 13)),
        )
        val day = layout.dots.getValue(0)
        assertEquals(3, day.shown.size)
        assertEquals(2, day.extra)
    }

    @Test
    fun `exactly the cap shows every dot`() {
        val layout = dotsOf(
            timed(1, at(10, 8), at(10, 9)),
            timed(2, at(10, 9), at(10, 10)),
            timed(3, at(10, 10), at(10, 11)),
            timed(4, at(10, 11), at(10, 12)),
        )
        assertEquals(4, layout.dots.getValue(0).shown.size)
        assertEquals(0, layout.dots.getValue(0).extra)
    }
}
