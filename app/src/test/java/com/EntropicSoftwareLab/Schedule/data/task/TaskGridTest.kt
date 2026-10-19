package com.EntropicSoftwareLab.Schedule.data.task

import com.EntropicSoftwareLab.Schedule.data.calendar.HourGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TaskGridTest {

    private val day = LocalDate.of(2026, 8, 7)

    private fun task(
        id: Long,
        title: String = "t$id",
        time: LocalTime? = null,
        done: Boolean = false,
    ) = Task(id = id, title = title, due = day, dueTime = time, notes = null, done = done)

    @Test
    fun `byHour always returns one cell per hour`() {
        assertEquals(HourGrid.HOURS_PER_DAY, TaskGrid.byHour(emptyList()).size)
    }

    @Test
    fun `a deadline lands in its own hour`() {
        val cells = TaskGrid.byHour(listOf(task(1, time = LocalTime.of(14, 0))))
        assertEquals(1, cells[14].size)
        assertEquals("t1", cells[14].first().title)
        assertTrue(cells.filterIndexed { hour, _ -> hour != 14 }.all { it.isEmpty() })
    }

    @Test
    fun `the minute becomes a fraction of the hour`() {
        val cells = TaskGrid.byHour(listOf(task(1, time = LocalTime.of(9, 30))))
        assertEquals(0.5f, cells[9].first().fraction, 0.0001f)
    }

    @Test
    fun `a deadline on the hour sits at the start of its cell`() {
        val cells = TaskGrid.byHour(listOf(task(1, time = LocalTime.of(9, 0))))
        assertEquals(0f, cells[9].first().fraction, 0.0001f)
    }

    /**
     * The rule that keeps a deadline honest: with no time there is no hour to mark, and defaulting
     * one to midnight would print a deadline the user never set.
     */
    @Test
    fun `a task with no time gets no mark`() {
        val cells = TaskGrid.byHour(listOf(task(1, time = null)))
        assertTrue(cells.all { it.isEmpty() })
    }

    @Test
    fun `a done task still marks its hour, flagged as done`() {
        val cells = TaskGrid.byHour(listOf(task(1, time = LocalTime.of(8, 15), done = true)))
        assertEquals(1, cells[8].size)
        assertTrue(cells[8].first().done)
    }

    @Test
    fun `marks in one hour are ordered by position then title`() {
        val cells = TaskGrid.byHour(
            listOf(
                task(1, title = "later", time = LocalTime.of(11, 45)),
                task(2, title = "zulu", time = LocalTime.of(11, 5)),
                task(3, title = "alpha", time = LocalTime.of(11, 5)),
            )
        )
        assertEquals(listOf("alpha", "zulu", "later"), cells[11].map { it.title })
    }

    @Test
    fun `the last minute of the day stays in hour 23`() {
        val cells = TaskGrid.byHour(listOf(task(1, time = LocalTime.of(23, 59))))
        assertEquals(1, cells[23].size)
        assertTrue(cells[23].first().fraction < 1f)
    }

    @Test
    fun `midnight stays in hour zero`() {
        val cells = TaskGrid.byHour(listOf(task(1, time = LocalTime.MIDNIGHT)))
        assertEquals(1, cells[0].size)
        assertEquals(0f, cells[0].first().fraction, 0.0001f)
    }
}
