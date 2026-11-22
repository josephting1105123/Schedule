package com.EntropicSoftwareLab.Schedule.data.task

import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class TaskEventFormatTest {

    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val london = ZoneId.of("Europe/London")
    private val day = LocalDate.of(2026, 11, 20)
    private val id = "0123456789abcdef0123456789abcdef"

    private fun task(
        time: LocalTime? = null,
        notes: String? = null,
        done: Boolean = false,
        title: String = "Submit the report",
    ) = Task(
        id = 1, title = title, due = day, dueTime = time, notes = notes, done = done,
        syncId = id, editedAt = 1_700_000_000_000L, tickedAt = 1_700_000_500_000L,
    )

    private fun row(fields: TaskEventFields, eventId: Long = 7) = TaskEventRow(
        eventId = eventId,
        title = fields.title,
        description = fields.description,
        allDay = fields.allDay,
        startMillis = fields.startMillis,
        timezone = fields.timezone,
    )

    private fun rawRow(description: String?, allDay: Boolean = true, start: Long = 0L, tz: String? = "UTC") =
        TaskEventRow(1, "T", description, allDay, start, tz)

    // ---- tag ----

    @Test
    fun `tag has the agreed shape`() {
        assertEquals(
            "[task] id=abc-1 edited=10 ticked=20 done=1",
            TaskEventFormat.tag("abc-1", 10, 20, true),
        )
        assertEquals("[task] id=abc-1 edited=10 ticked=20 done=0", TaskEventFormat.tag("abc-1", 10, 20, false))
    }

    // ---- description ----

    @Test
    fun `description is just the tag when there are no notes`() {
        assertEquals(TaskEventFormat.tag(id, 1_700_000_000_000L, 1_700_000_500_000L, false), TaskEventFormat.description(task()))
        assertEquals(TaskEventFormat.description(task()), TaskEventFormat.description(task(notes = "   \n ")))
    }

    @Test
    fun `description puts trimmed notes first and the tag last after a blank line`() {
        val d = TaskEventFormat.description(task(notes = "  bring the form\nsecond line \n"))
        assertEquals(
            "bring the form\nsecond line\n\n[task] id=$id edited=1700000000000 ticked=1700000500000 done=0",
            d,
        )
    }

    // ---- round trips ----

    @Test
    fun `a timed task round trips with notes`() {
        val t = task(time = LocalTime.of(17, 0), notes = "final draft", done = true)
        val f = TaskEventFormat.fields(t, kl)
        assertFalse(f.allDay)
        assertEquals(f.startMillis, f.endMillis)
        assertEquals("Asia/Kuala_Lumpur", f.timezone)
        val back = TaskEventFormat.decode(row(f), kl)!!
        assertEquals(
            RemoteTask(7, id, "Submit the report", day, LocalTime.of(17, 0), "final draft", true, 1_700_000_000_000L, 1_700_000_500_000L),
            back,
        )
        assertTrue(TaskSyncPlan.sameContent(t, back))
    }

    @Test
    fun `an untimed task is all day from UTC midnight to the next`() {
        val f = TaskEventFormat.fields(task(), kl)
        assertTrue(f.allDay)
        assertEquals("UTC", f.timezone)
        assertEquals(day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), f.startMillis)
        assertEquals(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), f.endMillis)
    }

    @Test
    fun `all day decode gives the UTC date and no time whatever the zone`() {
        val f = TaskEventFormat.fields(task(), kl)
        val back = TaskEventFormat.decode(row(f), ZoneId.of("Pacific/Auckland"))!!
        assertEquals(day, back.due)
        assertNull(back.dueTime)
    }

    @Test
    fun `fields trims the title`() {
        assertEquals("Hello", TaskEventFormat.fields(task(title = "  Hello "), kl).title)
    }

    @Test
    fun `a timed row written in Kuala Lumpur still reads as that wall time in London`() {
        val f = TaskEventFormat.fields(task(time = LocalTime.of(17, 0)), kl)
        val back = TaskEventFormat.decode(row(f), london)!!
        assertEquals(day, back.due)
        assertEquals(LocalTime.of(17, 0), back.dueTime)
    }

    @Test
    fun `a missing or invalid timezone falls back to the passed zone`() {
        val f = TaskEventFormat.fields(task(time = LocalTime.of(9, 30)), kl)
        val inLondon = f.startMillis.let { java.time.Instant.ofEpochMilli(it).atZone(london).toLocalTime() }
        assertEquals(inLondon, TaskEventFormat.decode(row(f).copy(timezone = null), london)!!.dueTime)
        assertEquals(inLondon, TaskEventFormat.decode(row(f).copy(timezone = "Not/AZone"), london)!!.dueTime)
    }

    // ---- parsing ----

    @Test
    fun `no tag line means null`() {
        assertNull(TaskEventFormat.decode(rawRow("just some notes"), kl))
        assertNull(TaskEventFormat.decode(rawRow(null), kl))
        assertNull(TaskEventFormat.decode(rawRow(""), kl))
        assertNull(TaskEventFormat.decode(rawRow("  \n \n"), kl))
    }

    @Test
    fun `a note typed after the tag is kept as notes and the row still reads`() {
        val r = TaskEventFormat.decode(rawRow("before\n\n[task] id=abc edited=1 ticked=2 done=0\nadded in Google"), kl)!!
        assertEquals("abc", r.syncId)
        assertEquals("before\n\nadded in Google", r.notes)
        assertEquals(1L, r.editedAt)
        // Written back as notes + tag, it reads the same again, so nothing keeps rewriting it.
        val written = TaskEventFormat.description(task(notes = r.notes))
        assertEquals(r.notes, TaskEventFormat.decode(rawRow(written), kl)!!.notes)
    }

    @Test
    fun `the last tag shaped line wins`() {
        val r = TaskEventFormat.decode(rawRow("[task] id=old edited=1\n[task] id=new edited=2"), kl)!!
        assertEquals("new", r.syncId)
        assertEquals("[task] id=old edited=1", r.notes)
    }

    @Test
    fun `a br joined description reads like a newline joined one`() {
        val joined = "bring the form<br>second<BR/><br />[task] id=abc edited=5 ticked=6 done=1"
        val r = TaskEventFormat.decode(rawRow(joined), kl)!!
        assertEquals("abc", r.syncId)
        assertEquals("bring the form\nsecond", r.notes)
        assertTrue(r.done)
    }

    @Test
    fun `other html tags are stripped and entities unescaped`() {
        val r = TaskEventFormat.decode(
            rawRow("<div>Tom &amp; Jerry &lt;3 &quot;hi&quot; it&#39;s&nbsp;ok &gt;</div><br>[task] id=abc edited=1"),
            kl,
        )!!
        assertEquals("Tom & Jerry <3 \"hi\" it's ok >", r.notes)
    }

    @Test
    fun `an escaped entity is not unescaped twice`() {
        val r = TaskEventFormat.decode(rawRow("a &amp;lt; b<br>[task] id=abc"), kl)!!
        assertEquals("a &lt; b", r.notes)
    }

    @Test
    fun `plain angle brackets in notes survive`() {
        val r = TaskEventFormat.decode(rawRow("if a < b and c > d\n[task] id=abc"), kl)!!
        assertEquals("if a < b and c > d", r.notes)
    }

    @Test
    fun `trailing blank lines after the tag are fine`() {
        val r = TaskEventFormat.decode(rawRow("note\n\n[task] id=abc edited=1 ticked=2 done=1\n\n  \n"), kl)
        assertNotNull(r)
        assertEquals("note", r!!.notes)
        assertTrue(r.done)
    }

    @Test
    fun `unknown keys are ignored and missing numbers default to zero`() {
        val r = TaskEventFormat.decode(rawRow("[task] id=abc color=red edited=notanumber"), kl)!!
        assertEquals("abc", r.syncId)
        assertEquals(0L, r.editedAt)
        assertEquals(0L, r.tickedAt)
        assertFalse(r.done)
        assertNull(r.notes)
    }

    @Test
    fun `done is only true for 1`() {
        assertFalse(TaskEventFormat.decode(rawRow("[task] id=a done=true"), kl)!!.done)
        assertFalse(TaskEventFormat.decode(rawRow("[task] id=a done=0"), kl)!!.done)
        assertTrue(TaskEventFormat.decode(rawRow("[task] id=a done=1"), kl)!!.done)
    }

    @Test
    fun `a bad or missing id means null`() {
        assertNull(TaskEventFormat.decode(rawRow("[task] edited=1"), kl))
        assertNull(TaskEventFormat.decode(rawRow("[task] id=bad_id!"), kl))
        assertNull(TaskEventFormat.decode(rawRow("[task] id="), kl))
        assertNull(TaskEventFormat.decode(rawRow("[task] id=" + "a".repeat(65)), kl))
        assertNotNull(TaskEventFormat.decode(rawRow("[task] id=" + "a".repeat(64)), kl))
    }

    @Test
    fun `the tag word needs whitespace after it`() {
        assertNull(TaskEventFormat.decode(rawRow("[task]id=abc"), kl))
        assertNull(TaskEventFormat.decode(rawRow("task id=abc"), kl))
    }

    @Test
    fun `a blank title means null`() {
        assertNull(TaskEventFormat.decode(rawRow("[task] id=abc").copy(title = "   "), kl))
    }

    @Test
    fun `windows line endings are tolerated`() {
        val r = TaskEventFormat.decode(rawRow("first\r\nsecond\r\n\r\n[task] id=abc edited=5 ticked=6 done=1\r\n"), kl)!!
        assertEquals("first\nsecond", r.notes)
        assertEquals(5L, r.editedAt)
    }

    @Test
    fun `block tags close lines so a tag after a closing div reads`() {
        val r = TaskEventFormat.decode(rawRow("notes</div><div>[task] id=abc edited=1 ticked=2 done=1"), kl)!!
        assertEquals("abc", r.syncId)
        assertEquals("notes", r.notes)
        assertTrue(r.done)
        val p = TaskEventFormat.decode(rawRow("<p>one</p><p>two</p><p>[task] id=abc</p>"), kl)!!
        assertEquals("one\n\ntwo", p.notes)
    }

    @Test
    fun `a description with a newline is never run through the html reading`() {
        val r = TaskEventFormat.decode(rawRow("John <john@x.com> a<b>c &amp; &lt;\n[task] id=abc"), kl)!!
        assertEquals("John <john@x.com> a<b>c &amp; &lt;", r.notes)
    }

    @Test
    fun `a newline free description with no html shape is read as it is`() {
        val r = TaskEventFormat.decode(rawRow("[task] id=abc edited=1"), kl)!!
        assertNull(r.notes)
        assertEquals("abc", r.syncId)
    }

    @Test
    fun `what the phone writes reads back unchanged whatever the notes hold`() {
        val notes = listOf("<a@b.c>", "a<b>c", "&amp;", "x &lt; y", "[task] not a tag", "<br>", "<div>x</div>")
        for (n in notes) {
            val f = TaskEventFormat.fields(task(notes = n), kl)
            assertEquals("notes <$n>", n, TaskEventFormat.decode(row(f), kl)!!.notes)
        }
    }

    // ---- claimedSyncId ----

    private fun claim(description: String?, title: String = "T") =
        TaskEventFormat.claimedSyncId(rawRow(description).copy(title = title))

    @Test
    fun `claimedSyncId finds the id in a row decode rejects`() {
        val row = rawRow("[task] id=abc edited=1").copy(title = "  ")
        assertNull(TaskEventFormat.decode(row, kl))
        assertEquals("abc", TaskEventFormat.claimedSyncId(row))
    }

    @Test
    fun `claimedSyncId finds an id in damaged html and mid text`() {
        assertEquals("abc-1", claim("note<br>[task] id=abc-1 edited=x<br>more"))
        assertEquals("abc", claim("x [task] id=abc"))
    }

    @Test
    fun `claimedSyncId is null for rows that are not tasks`() {
        assertNull(claim(null))
        assertNull(claim("just notes"))
        assertNull(claim("[task] edited=1"))
        assertNull(claim("[task]id=abc"))
        assertNull(claim("[task] id=bad_id"))
    }

    // ---- show ----

    @Test
    fun `a same day task keeps exactly the old tag, with no show key`() {
        assertFalse(TaskEventFormat.description(task()).contains("show="))
        assertEquals(
            "[task] id=abc edited=1 ticked=2 done=0",
            TaskEventFormat.tag("abc", 1, 2, false, TaskShow.SameDay),
        )
    }

    @Test
    fun `week before writes show=week and reads back`() {
        assertEquals(
            "[task] id=abc edited=1 ticked=2 done=0 show=week",
            TaskEventFormat.tag("abc", 1, 2, false, TaskShow.WeekBefore),
        )
        val t = task().copy(show = TaskShow.WeekBefore)
        val f = TaskEventFormat.fields(t, kl)
        assertTrue(f.description.contains("show=week"))
        assertEquals(TaskShow.WeekBefore, TaskEventFormat.decode(row(f), kl)!!.show)
    }

    @Test
    fun `until done round trips`() {
        val t = task(notes = "n").copy(show = TaskShow.UntilDone)
        val r = TaskEventFormat.decode(row(TaskEventFormat.fields(t, kl)), kl)!!
        assertEquals(TaskShow.UntilDone, r.show)
        assertEquals("n", r.notes)
    }

    @Test
    fun `a tag without show reads as same day`() {
        assertEquals(TaskShow.SameDay, TaskEventFormat.decode(rawRow("[task] id=abc edited=1 ticked=2 done=0"), kl)!!.show)
    }

    @Test
    fun `an unknown show value reads as same day`() {
        assertEquals(TaskShow.SameDay, TaskEventFormat.decode(rawRow("[task] id=abc show=xyz"), kl)!!.show)
    }

    // ---- repeat ----

    @Test
    fun `a non-repeating task keeps exactly the old tag, with no repeat key`() {
        assertFalse(TaskEventFormat.description(task()).contains("repeat="))
        assertEquals(
            "[task] id=abc edited=1 ticked=2 done=0",
            TaskEventFormat.tag("abc", 1, 2, false, TaskShow.SameDay, null),
        )
    }

    @Test
    fun `repeat is written after show and round trips through decode`() {
        val repeat = TaskRepeat(RecurrenceFreq.Weekly, interval = 2)
        assertEquals(
            "[task] id=abc edited=1 ticked=2 done=0 show=week repeat=week/2",
            TaskEventFormat.tag("abc", 1, 2, false, TaskShow.WeekBefore, repeat),
        )
        val t = task().copy(show = TaskShow.WeekBefore, repeat = repeat)
        val f = TaskEventFormat.fields(t, kl)
        assertTrue(f.description.contains("show=week repeat=week/2"))
        assertEquals(repeat, TaskEventFormat.decode(row(f), kl)!!.repeat)
    }

    @Test
    fun `a bogus repeat value reads as null without dropping the task`() {
        val r = TaskEventFormat.decode(rawRow("[task] id=abc repeat=bogus"), kl)
        assertNotNull(r)
        assertNull(r!!.repeat)
    }
}
