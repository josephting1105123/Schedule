package com.EntropicSoftwareLab.Schedule.data.task

import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceEnd
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TaskRepeatTest {

    private val end = LocalDate.of(2026, 12, 31)

    // ---- wire format ----

    @Test
    fun `wire round trips all four frequencies with and without interval and until`() {
        for (freq in RecurrenceFreq.entries) {
            val cases = listOf(
                TaskRepeat(freq),
                TaskRepeat(freq, interval = 3),
                TaskRepeat(freq, interval = 1, until = end),
                TaskRepeat(freq, interval = 12, until = end),
            )
            for (repeat in cases) {
                assertEquals(repeat, TaskRepeat.fromWire(repeat.wire()))
            }
        }
    }

    @Test
    fun `wire writes the shortest form`() {
        assertEquals("day", TaskRepeat(RecurrenceFreq.Daily).wire())
        assertEquals("week", TaskRepeat(RecurrenceFreq.Weekly).wire())
        assertEquals("month", TaskRepeat(RecurrenceFreq.Monthly).wire())
        assertEquals("year", TaskRepeat(RecurrenceFreq.Yearly).wire())
        assertEquals("week/2", TaskRepeat(RecurrenceFreq.Weekly, interval = 2).wire())
        assertEquals("week/1/2026-12-31", TaskRepeat(RecurrenceFreq.Weekly, interval = 1, until = end).wire())
        assertEquals("week/2/2026-12-31", TaskRepeat(RecurrenceFreq.Weekly, interval = 2, until = end).wire())
    }

    @Test
    fun `fromWire accepts explicit interval 1 without until`() {
        assertEquals(TaskRepeat(RecurrenceFreq.Weekly, interval = 1), TaskRepeat.fromWire("week/1"))
    }

    @Test
    fun `fromWire returns null on null, blank or malformed values`() {
        val bad = listOf(
            null,
            "",
            "   ",
            "fortnight",
            "WEEK",
            "week/0",
            "week/-1",
            "week/+1",
            "week/1000",
            "week/x",
            "week/",
            "week/1/",
            "week/1/notadate",
            "week/1/2026-02-30",
            "week/1/2026-12-31/extra",
        )
        for (value in bad) {
            assertNull("expected null for <$value>", TaskRepeat.fromWire(value))
        }
    }

    @Test
    fun `fromWire rejects year 0`() {
        assertNull(TaskRepeat.fromWire("week/1/0000-01-01"))
    }

    // ---- nextDue ----

    @Test
    fun `daily task due today moves to tomorrow`() {
        val today = LocalDate.of(2026, 11, 22)
        assertEquals(
            LocalDate.of(2026, 11, 23),
            TaskRepeat(RecurrenceFreq.Daily).nextDue(due = today, today = today),
        )
    }

    @Test
    fun `weekly task three weeks overdue moves to the first weekly date on or after today`() {
        val due = LocalDate.of(2026, 11, 1)
        assertEquals(
            LocalDate.of(2026, 11, 22),
            TaskRepeat(RecurrenceFreq.Weekly).nextDue(due = due, today = LocalDate.of(2026, 11, 22)),
        )
        assertEquals(
            LocalDate.of(2026, 11, 29),
            TaskRepeat(RecurrenceFreq.Weekly).nextDue(due = due, today = LocalDate.of(2026, 11, 23)),
        )
    }

    @Test
    fun `weekly task due in the future moves to due plus 7 days`() {
        val due = LocalDate.of(2026, 12, 10)
        val today = LocalDate.of(2026, 11, 22)
        assertEquals(
            LocalDate.of(2026, 12, 17),
            TaskRepeat(RecurrenceFreq.Weekly).nextDue(due = due, today = today),
        )
    }

    @Test
    fun `monthly from 31 Jan 2027 clamps to 28 Feb 2027 and anchors from due not previous step`() {
        val jan31 = LocalDate.of(2027, 1, 31)
        val monthly = TaskRepeat(RecurrenceFreq.Monthly)
        assertEquals(
            LocalDate.of(2027, 2, 28),
            monthly.nextDue(due = jan31, today = jan31),
        )
        assertEquals(
            LocalDate.of(2027, 3, 31),
            monthly.nextDue(due = jan31, today = LocalDate.of(2027, 3, 15)),
        )
    }

    @Test
    fun `nextDue respects until inclusively and returns null past it`() {
        val due = LocalDate.of(2026, 11, 22)
        assertNull(
            TaskRepeat(RecurrenceFreq.Daily, until = LocalDate.of(2026, 11, 22))
                .nextDue(due = due, today = due),
        )
        assertEquals(
            LocalDate.of(2026, 11, 23),
            TaskRepeat(RecurrenceFreq.Daily, until = LocalDate.of(2026, 11, 23))
                .nextDue(due = due, today = due),
        )
        assertNull(
            TaskRepeat(RecurrenceFreq.Weekly, until = LocalDate.of(2026, 11, 28))
                .nextDue(due = due, today = due),
        )
    }

    @Test
    fun `nextDue stops after 100000 steps and returns null`() {
        assertNull(
            TaskRepeat(RecurrenceFreq.Daily).nextDue(
                due = LocalDate.of(1700, 1, 1),
                today = LocalDate.of(2026, 11, 22),
            ),
        )
    }

    // ---- RecurrenceRule conversion ----

    @Test
    fun `toRule maps until to OnDate and null to Never`() {
        assertEquals(
            RecurrenceRule(RecurrenceFreq.Weekly, interval = 2, end = RecurrenceEnd.Never),
            TaskRepeat(RecurrenceFreq.Weekly, interval = 2, until = null).toRule(),
        )
        assertEquals(
            RecurrenceRule(RecurrenceFreq.Monthly, interval = 1, end = RecurrenceEnd.OnDate(end)),
            TaskRepeat(RecurrenceFreq.Monthly, interval = 1, until = end).toRule(),
        )
    }

    @Test
    fun `toTaskRepeat converts AfterCount, OnDate, Never and clamps interval`() {
        val due = LocalDate.of(2026, 11, 22)
        assertEquals(
            TaskRepeat(RecurrenceFreq.Weekly, interval = 1, until = LocalDate.of(2026, 12, 6)),
            RecurrenceRule(RecurrenceFreq.Weekly, interval = 1, end = RecurrenceEnd.AfterCount(3))
                .toTaskRepeat(due),
        )
        assertEquals(
            TaskRepeat(RecurrenceFreq.Weekly, interval = 1, until = due),
            RecurrenceRule(RecurrenceFreq.Weekly, interval = 1, end = RecurrenceEnd.AfterCount(1))
                .toTaskRepeat(due),
        )
        assertEquals(
            TaskRepeat(RecurrenceFreq.Daily, interval = 1, until = null),
            RecurrenceRule(RecurrenceFreq.Daily, interval = 0, end = RecurrenceEnd.Never)
                .toTaskRepeat(due),
        )
        assertEquals(
            TaskRepeat(RecurrenceFreq.Yearly, interval = 999, until = end),
            RecurrenceRule(RecurrenceFreq.Yearly, interval = 5000, end = RecurrenceEnd.OnDate(end))
                .toTaskRepeat(due),
        )
    }

    @Test
    fun `toTaskRepeat clamps AfterCount until to 9999-12-31 so wire round-trips`() {
        val due = LocalDate.of(2026, 11, 22)
        val repeat = RecurrenceRule(
            freq = RecurrenceFreq.Yearly,
            interval = 1,
            end = RecurrenceEnd.AfterCount(9999),
        ).toTaskRepeat(due)
        assertEquals(LocalDate.of(9999, 12, 31), repeat.until)
        assertEquals(repeat, TaskRepeat.fromWire(repeat.wire()))
    }
}
