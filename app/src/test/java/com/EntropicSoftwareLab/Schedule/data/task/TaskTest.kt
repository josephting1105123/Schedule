package com.EntropicSoftwareLab.Schedule.data.task

import org.junit.Assert.assertEquals
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
}
