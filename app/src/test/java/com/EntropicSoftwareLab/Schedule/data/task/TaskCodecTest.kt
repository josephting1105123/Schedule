package com.EntropicSoftwareLab.Schedule.data.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TaskCodecTest {

    private val day = LocalDate.of(2026, 8, 7)

    @Test
    fun `round trips a task with a deadline time`() {
        val task = Task(
            id = 1,
            title = "Submit the report",
            due = day,
            dueTime = LocalTime.of(17, 30),
            notes = "final draft",
            done = false,
        )
        assertEquals(listOf(task), TaskCodec.decode(TaskCodec.encode(listOf(task))))
    }

    @Test
    fun `round trips a task with no time and no notes`() {
        val task = Task(id = 2, title = "Water the plants", due = day, dueTime = null, notes = null, done = false)
        assertEquals(listOf(task), TaskCodec.decode(TaskCodec.encode(listOf(task))))
    }

    @Test
    fun `round trips a finished task`() {
        val task = Task(id = 3, title = "Call the bank", due = day, dueTime = LocalTime.of(9, 0), notes = null, done = true)
        assertEquals(listOf(task), TaskCodec.decode(TaskCodec.encode(listOf(task))))
    }

    @Test
    fun `round trips several tasks in order`() {
        val tasks = listOf(
            Task(id = 1, title = "A", due = day, dueTime = LocalTime.of(0, 0), notes = null, done = false),
            Task(id = 2, title = "B", due = day.plusDays(400), dueTime = LocalTime.of(23, 59), notes = "x", done = true),
        )
        assertEquals(tasks, TaskCodec.decode(TaskCodec.encode(tasks)))
    }

    /** A deadline far from the epoch must survive: epoch day, not a formatter, is why it does. */
    @Test
    fun `round trips a date before 1970`() {
        val task = Task(id = 4, title = "Old", due = LocalDate.of(1901, 3, 4), dueTime = null, notes = null, done = false)
        assertEquals(listOf(task), TaskCodec.decode(TaskCodec.encode(listOf(task))))
    }

    // ---- decode is total ----

    @Test
    fun `null and blank decode to an empty list`() {
        assertTrue(TaskCodec.decode(null).isEmpty())
        assertTrue(TaskCodec.decode("").isEmpty())
        assertTrue(TaskCodec.decode("   ").isEmpty())
    }

    @Test
    fun `a corrupt blob decodes to an empty list rather than throwing`() {
        assertTrue(TaskCodec.decode("{not json").isEmpty())
        assertTrue(TaskCodec.decode("[[[").isEmpty())
    }

    @Test
    fun `one unparseable entry is dropped, the rest survive`() {
        val json = """[{"id":1,"title":"Keep me","due":20000},{"nonsense":true},{"id":2,"title":"","due":1}]"""
        val decoded = TaskCodec.decode(json)
        assertEquals(listOf("Keep me"), decoded.map { it.title })
    }

    @Test
    fun `a task with no due date is dropped rather than defaulted onto today`() {
        assertTrue(TaskCodec.decode("""[{"id":1,"title":"No date"}]""").isEmpty())
        assertTrue(TaskCodec.decode("""[{"id":1,"title":"No date","due":null}]""").isEmpty())
    }

    /** The day is the part the user would miss, so a bad time degrades instead of losing the task. */
    @Test
    fun `an out-of-range time degrades to no time`() {
        val decoded = TaskCodec.decode("""[{"id":1,"title":"Odd","due":20000,"dueSecond":999999}]""")
        assertEquals(1, decoded.size)
        assertEquals(null, decoded.first().dueTime)
        assertEquals(LocalDate.ofEpochDay(20000), decoded.first().due)
    }

    @Test
    fun `a missing done flag reads as not done`() {
        val decoded = TaskCodec.decode("""[{"id":1,"title":"Legacy","due":20000}]""")
        assertEquals(false, decoded.first().done)
    }

    /** optString turns a JSON null into the string "null"; notes must come back genuinely absent. */
    @Test
    fun `a null notes field decodes to null, not the text null`() {
        val decoded = TaskCodec.decode("""[{"id":1,"title":"T","due":20000,"notes":null}]""")
        assertEquals(null, decoded.first().notes)
    }

    @Test
    fun `an empty list round trips`() {
        assertTrue(TaskCodec.decode(TaskCodec.encode(emptyList())).isEmpty())
    }
}
