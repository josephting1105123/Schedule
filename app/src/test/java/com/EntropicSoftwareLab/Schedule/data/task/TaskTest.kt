package com.EntropicSoftwareLab.Schedule.data.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TaskTest {

    private val day = LocalDate.of(2026, 8, 7)

    private fun task(
        id: Long,
        title: String = "t$id",
        due: LocalDate = day,
        time: LocalTime? = null,
        done: Boolean = false,
    ) = Task(id = id, title = title, due = due, dueTime = time, notes = null, done = done)

    // ---- on(date) ----

    @Test
    fun `on returns only the tasks due that day`() {
        val tasks = listOf(
            task(1, due = day),
            task(2, due = day.plusDays(1)),
            task(3, due = day.minusDays(1)),
        )
        assertEquals(listOf(1L), Task.on(tasks, day).map { it.id })
    }

    @Test
    fun `on includes tasks already done that day`() {
        val tasks = listOf(task(1, done = true), task(2))
        assertEquals(setOf(1L, 2L), Task.on(tasks, day).map { it.id }.toSet())
    }

    // ---- ordering ----

    @Test
    fun `done tasks sink below unfinished ones`() {
        val tasks = listOf(
            task(1, time = LocalTime.of(9, 0), done = true),
            task(2, time = LocalTime.of(17, 0)),
        )
        assertEquals(listOf(2L, 1L), Task.ordered(tasks).map { it.id })
    }

    @Test
    fun `timed tasks come before untimed ones on the same day`() {
        val tasks = listOf(task(1), task(2, time = LocalTime.of(23, 30)))
        assertEquals(listOf(2L, 1L), Task.ordered(tasks).map { it.id })
    }

    @Test
    fun `timed tasks are ordered by deadline`() {
        val tasks = listOf(
            task(1, time = LocalTime.of(17, 0)),
            task(2, time = LocalTime.of(9, 0)),
            task(3, time = LocalTime.of(12, 30)),
        )
        assertEquals(listOf(2L, 3L, 1L), Task.ordered(tasks).map { it.id })
    }

    @Test
    fun `earlier due dates come first`() {
        val tasks = listOf(
            task(1, due = day),
            task(2, due = day.minusDays(3)),
        )
        assertEquals(listOf(2L, 1L), Task.ordered(tasks).map { it.id })
    }

    /** Two reads of the same data must not reshuffle the list under the user's finger. */
    @Test
    fun `identical deadlines fall back to title then id`() {
        val tasks = listOf(
            task(9, title = "beta"),
            task(3, title = "alpha"),
            task(1, title = "alpha"),
        )
        assertEquals(listOf(1L, 3L, 9L), Task.ordered(tasks).map { it.id })
    }

    @Test
    fun `ordering ignores case in the title tiebreak`() {
        val tasks = listOf(task(1, title = "Zebra"), task(2, title = "apple"))
        assertEquals(listOf(2L, 1L), Task.ordered(tasks).map { it.id })
    }

    // ---- overdue ----

    @Test
    fun `overdue picks up unfinished tasks from before today`() {
        val tasks = listOf(
            task(1, due = day.minusDays(1)),
            task(2, due = day),
            task(3, due = day.plusDays(1)),
        )
        assertEquals(listOf(1L), Task.overdueAsOf(tasks, day).map { it.id })
    }

    @Test
    fun `a finished task is never overdue`() {
        val tasks = listOf(task(1, due = day.minusDays(5), done = true))
        assertTrue(Task.overdueAsOf(tasks, day).isEmpty())
    }

    /** A task due today is not late yet, whatever time its deadline is. */
    @Test
    fun `today is not overdue`() {
        val tasks = listOf(task(1, due = day, time = LocalTime.of(0, 1)))
        assertTrue(Task.overdueAsOf(tasks, day).isEmpty())
    }

    @Test
    fun `overdue lists the oldest deadline first`() {
        val tasks = listOf(
            task(1, due = day.minusDays(1)),
            task(2, due = day.minusDays(9)),
            task(3, due = day.minusDays(4)),
        )
        assertEquals(listOf(2L, 3L, 1L), Task.overdueAsOf(tasks, day).map { it.id })
    }

    // ---- shownOn(date, today) ----

    private fun shownOn(t: Task, date: LocalDate, today: LocalDate = day) =
        Task.shownOn(listOf(t), date, today).isNotEmpty()

    @Test
    fun `same day shows only on its due date`() {
        val t = task(1, due = day.plusDays(3))
        assertTrue(shownOn(t, day.plusDays(3)))
        assertFalse(shownOn(t, day.plusDays(2)))
        assertFalse(shownOn(t, day.plusDays(4)))
        assertFalse(shownOn(t, day))
    }

    @Test
    fun `week before shows on the due date and the seven days before it`() {
        val due = day.plusDays(20)
        val t = task(1, due = due).copy(show = TaskShow.WeekBefore)
        assertTrue(shownOn(t, due))
        assertTrue(shownOn(t, due.minusDays(7)))
        assertTrue(shownOn(t, due.minusDays(1)))
        assertFalse(shownOn(t, due.minusDays(8)))
        assertFalse(shownOn(t, due.plusDays(1)))
    }

    @Test
    fun `until done shows an unfinished task from today up to its due date`() {
        val due = day.plusDays(10)
        val t = task(1, due = due).copy(show = TaskShow.UntilDone)
        assertTrue(shownOn(t, day))
        assertTrue(shownOn(t, day.plusDays(5)))
        assertTrue(shownOn(t, due))
        assertFalse(shownOn(t, day.minusDays(1)))
        assertFalse(shownOn(t, due.plusDays(1)))
    }

    @Test
    fun `until done shows a finished task only on its due date`() {
        val due = day.plusDays(10)
        val t = task(1, due = due, done = true).copy(show = TaskShow.UntilDone)
        assertTrue(shownOn(t, due))
        assertFalse(shownOn(t, day))
        assertFalse(shownOn(t, day.plusDays(5)))
    }

    @Test
    fun `shownOn keeps the display order`() {
        val done = task(1, done = true)
        val open = task(2)
        assertEquals(listOf(2L, 1L), Task.shownOn(listOf(done, open), day, day).map { it.id })
    }
}
