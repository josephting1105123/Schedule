package com.EntropicSoftwareLab.Schedule.ui.screens.month

import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AgendaBuilderTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 20)
    private val july: YearMonth = YearMonth.of(2026, 7)

    private fun event(id: Long) = EventInstance(
        eventId = id,
        instanceId = id,
        title = "Event $id",
        location = null,
        startMillis = id,
        endMillis = id + 1,
        allDay = false,
        calendarId = 1L,
        displayColor = 0,
    )

    private fun birthday(id: Long, name: String = "Person $id") = Birthday(
        id = id,
        name = name,
        month = 1,
        day = 1,
        year = null,
    )

    private fun build(
        eventsByDate: Map<LocalDate, List<EventInstance>>,
        birthdaysByDate: Map<LocalDate, List<Birthday>> = emptyMap(),
        month: YearMonth = july,
        selected: LocalDate = today,
        mode: CalendarMode = CalendarMode.Events,
    ) = MonthViewModel.buildAgenda(eventsByDate, birthdaysByDate, month, selected, mode, today)

    @Test
    fun `days come back in ascending order regardless of map order`() {
        val agenda = build(
            mapOf(
                today.plusDays(5) to listOf(event(3)),
                today.minusDays(2) to listOf(event(1)),
                today.plusDays(1) to listOf(event(2)),
            )
        )
        assertEquals(
            listOf(today.minusDays(2), today, today.plusDays(1), today.plusDays(5)),
            agenda.map { it.date },
        )
    }

    @Test
    fun `days with no events are left out`() {
        val agenda = build(mapOf(today.plusDays(3) to listOf(event(1))))
        // today is forced in; the untouched days between it and the 23rd are not.
        assertEquals(listOf(today, today.plusDays(3)), agenda.map { it.date })
    }

    @Test
    fun `today is always present even when empty`() {
        val agenda = build(mapOf(today.plusDays(1) to listOf(event(1))))
        val todayEntry = agenda.single { it.date == today }
        assertTrue(todayEntry.events.isEmpty())
    }

    @Test
    fun `selected day is always present even when empty`() {
        val selected = today.plusDays(10)
        val agenda = build(mapOf(today to listOf(event(1))), selected = selected)
        assertEquals(listOf(today, selected), agenda.map { it.date })
        assertTrue(agenda.last().events.isEmpty())
    }

    @Test
    fun `a day that is both today and selected appears once`() {
        val agenda = build(mapOf(today to listOf(event(1))), selected = today)
        assertEquals(1, agenda.count { it.date == today })
        assertEquals(1, agenda.single().events.size)
    }

    @Test
    fun `events are carried through untouched`() {
        val day = today.plusDays(2)
        val events = listOf(event(1), event(2))
        val agenda = build(mapOf(day to events))
        assertEquals(events, agenda.single { it.date == day }.events)
    }

    // The agenda is the month's own detail view: it stops at the month boundary so that scrolling it
    // can never carry the user into a month the grid above isn't showing.

    @Test
    fun `days outside the month are dropped however busy they are`() {
        val agenda = build(
            mapOf(
                LocalDate.of(2026, 6, 30) to listOf(event(1)),
                LocalDate.of(2026, 7, 4) to listOf(event(2)),
                LocalDate.of(2026, 8, 1) to listOf(event(3)),
            )
        )
        assertEquals(listOf(LocalDate.of(2026, 7, 4), today), agenda.map { it.date })
    }

    @Test
    fun `today is not forced in when the month on show is not its own`() {
        val august = YearMonth.of(2026, 8)
        val agenda = build(
            eventsByDate = mapOf(LocalDate.of(2026, 8, 9) to listOf(event(1))),
            month = august,
            selected = LocalDate.of(2026, 8, 9),
        )
        assertEquals(listOf(LocalDate.of(2026, 8, 9)), agenda.map { it.date })
    }

    @Test
    fun `a selected day outside the month is not forced in either`() {
        val agenda = build(
            eventsByDate = mapOf(today to listOf(event(1))),
            selected = LocalDate.of(2026, 9, 3),
        )
        assertEquals(listOf(today), agenda.map { it.date })
    }

    @Test
    fun `an empty month still yields one day so the agenda is never blank`() {
        val august = YearMonth.of(2026, 8)
        val agenda = build(emptyMap(), month = august, selected = LocalDate.of(2026, 8, 14))
        assertEquals(listOf(LocalDate.of(2026, 8, 14)), agenda.map { it.date })
    }

    @Test
    fun `an empty month with nothing selected in it falls back to the first`() {
        val august = YearMonth.of(2026, 8)
        val agenda = build(emptyMap(), month = august, selected = today)
        assertEquals(listOf(LocalDate.of(2026, 8, 1)), agenda.map { it.date })
        assertTrue(agenda.single().events.isEmpty())
    }

    // --- birthday mode -------------------------------------------------------------------------

    @Test
    fun `birthday mode carries birthdays and no events`() {
        val day = today.plusDays(2)
        val agenda = build(
            eventsByDate = mapOf(day to listOf(event(1))),
            birthdaysByDate = mapOf(day to listOf(birthday(1))),
            mode = CalendarMode.Birthdays,
        )
        val entry = agenda.single { it.date == day }
        assertEquals(listOf(birthday(1)), entry.birthdays)
        assertTrue(entry.events.isEmpty())
    }

    @Test
    fun `events mode carries events and no birthdays`() {
        val day = today.plusDays(2)
        val agenda = build(
            eventsByDate = mapOf(day to listOf(event(1))),
            birthdaysByDate = mapOf(day to listOf(birthday(1))),
            mode = CalendarMode.Events,
        )
        val entry = agenda.single { it.date == day }
        assertEquals(listOf(event(1)), entry.events)
        assertTrue(entry.birthdays.isEmpty())
    }

    @Test
    fun `birthday mode drops days that only have events`() {
        val agenda = build(
            eventsByDate = mapOf(today.plusDays(3) to listOf(event(1))),
            birthdaysByDate = emptyMap(),
            mode = CalendarMode.Birthdays,
        )
        // today is still forced in; the event-only day is not, since this is birthday mode.
        assertEquals(listOf(today), agenda.map { it.date })
    }
}
