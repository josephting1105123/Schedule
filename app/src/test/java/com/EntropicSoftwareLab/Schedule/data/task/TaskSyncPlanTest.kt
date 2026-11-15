package com.EntropicSoftwareLab.Schedule.data.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class TaskSyncPlanTest {

    private val day = LocalDate.of(2026, 11, 20)

    private fun local(
        id: Long,
        syncId: String? = "s$id",
        title: String = "Task $id",
        due: LocalDate = day,
        time: LocalTime? = null,
        notes: String? = null,
        done: Boolean = false,
        editedAt: Long = 100,
        tickedAt: Long = 100,
    ) = Task(id, title, due, time, notes, done, syncId, editedAt, tickedAt)

    private fun remote(
        syncId: String,
        eventId: Long = 1,
        title: String = "Task ${syncId.drop(1)}",
        due: LocalDate = day,
        time: LocalTime? = null,
        notes: String? = null,
        done: Boolean = false,
        editedAt: Long = 100,
        tickedAt: Long = 100,
    ) = RemoteTask(eventId, syncId, title, due, time, notes, done, editedAt, tickedAt)

    private val NOW = 1_000_000L

    private var syncCounter = 0
    private var idCounter = 1000L

    private fun plan(
        local: List<Task>,
        remote: List<RemoteTask>,
        known: Set<String> = emptySet(),
        unreadable: Set<String> = emptySet(),
        zone: ZoneId = ZoneOffset.UTC,
        now: Long = NOW,
    ) = TaskSyncPlan.plan(
        local, remote, known, unreadable, zone, now,
        newSyncId = { "new${++syncCounter}" }, newLocalId = { idCounter++ },
    )

    private fun upserts(p: SyncPlan) = p.localWrites.filterIsInstance<LocalWrite.Upsert>()

    // ---- identical ----

    @Test
    fun `identical sides produce an empty plan`() {
        val p = plan(listOf(local(1), local(2)), listOf(remote("s1", 11), remote("s2", 12)), known = setOf("s1", "s2"))
        assertTrue(p.localWrites.isEmpty())
        assertTrue(p.remoteInserts.isEmpty())
        assertTrue(p.remoteUpdates.isEmpty())
        assertTrue(p.remoteDeletes.isEmpty())
        assertEquals(setOf("s1", "s2"), p.knownAfter)
    }

    // ---- merge ----

    @Test
    fun `a newer remote edit overwrites the local fields`() {
        val p = plan(
            listOf(local(1, editedAt = 100)),
            listOf(remote("s1", 11, title = "Renamed", due = day.plusDays(2), time = LocalTime.of(9, 0), notes = "n", editedAt = 200)),
        )
        val merged = upserts(p).single().after
        assertEquals("Renamed", merged.title)
        assertEquals(day.plusDays(2), merged.due)
        assertEquals(LocalTime.of(9, 0), merged.dueTime)
        assertEquals("n", merged.notes)
        assertEquals(200L, merged.editedAt)
        assertEquals(1L, merged.id)
        assertEquals("s1", merged.syncId)
        assertTrue(p.remoteUpdates.isEmpty())
    }

    @Test
    fun `a newer local edit is written to the remote`() {
        val l = local(1, title = "Mine", editedAt = 300)
        val p = plan(listOf(l), listOf(remote("s1", 11, title = "Theirs", editedAt = 200)))
        assertTrue(p.localWrites.isEmpty())
        assertEquals(listOf(11L to l), p.remoteUpdates)
    }

    @Test
    fun `a tick on one side and a rename on the other both survive`() {
        // Phone ticked it (tickedAt newer); Jarvis renamed it (editedAt newer).
        val l = local(1, title = "Old", done = true, editedAt = 100, tickedAt = 300)
        val r = remote("s1", 11, title = "New", done = false, editedAt = 300, tickedAt = 100)
        val p = plan(listOf(l), listOf(r))
        val merged = upserts(p).single().after
        assertEquals("New", merged.title)
        assertEquals(300L, merged.editedAt)
        assertEquals(true, merged.done)
        assertEquals(300L, merged.tickedAt)
        // The remote lacks the tick, so it is updated with the merge too.
        assertEquals(listOf(11L to merged), p.remoteUpdates)
    }

    @Test
    fun `a newer remote tick unticks or ticks locally and leaves the text alone`() {
        val l = local(1, title = "Mine", done = false, editedAt = 300, tickedAt = 100)
        val r = remote("s1", 11, title = "Theirs", done = true, editedAt = 200, tickedAt = 250)
        val p = plan(listOf(l), listOf(r))
        val merged = upserts(p).single().after
        assertEquals("Mine", merged.title)
        assertEquals(true, merged.done)
        assertEquals(250L, merged.tickedAt)
        assertEquals(listOf(11L to merged), p.remoteUpdates)
    }

    @Test
    fun `an equal stamp with different text is a Google Calendar edit and wins with a new stamp`() {
        val l = local(1, title = "Mine", editedAt = 100, tickedAt = 100)
        val r = remote("s1", 11, title = "Renamed in Google", editedAt = 100, tickedAt = 100)
        val p = plan(listOf(l), listOf(r))
        val merged = upserts(p).single().after
        assertEquals("Renamed in Google", merged.title)
        assertEquals(NOW, merged.editedAt)
        // The merge carries a newer stamp than the calendar's tag, so the calendar is rewritten
        // and Jarvis follows.
        assertEquals(listOf(11L to merged), p.remoteUpdates)
    }

    @Test
    fun `an equal tick stamp with a different done flag is adopted with a new stamp`() {
        val l = local(1, done = false, editedAt = 100, tickedAt = 100)
        val r = remote("s1", 11, done = true, editedAt = 100, tickedAt = 100)
        val p = plan(listOf(l), listOf(r))
        val merged = upserts(p).single().after
        assertEquals(true, merged.done)
        assertEquals(NOW, merged.tickedAt)
        assertEquals(100L, merged.editedAt)
        assertEquals(listOf(11L to merged), p.remoteUpdates)
    }

    @Test
    fun `a stamp from the clock never ties with the calendar's`() {
        val l = local(1, title = "Mine", editedAt = 5_000_000)
        val r = remote("s1", 11, title = "Google", editedAt = 5_000_000)
        val merged = upserts(plan(listOf(l), listOf(r), now = 1_000)).single().after
        assertEquals(5_000_001L, merged.editedAt)
    }

    @Test
    fun `a tie with identical content is an empty plan`() {
        val p = plan(listOf(local(1, time = LocalTime.of(9, 0), notes = "n")), listOf(remote("s1", 11, time = LocalTime.of(9, 0), notes = "n")), known = setOf("s1"))
        assertTrue(p.localWrites.isEmpty())
        assertTrue(p.remoteUpdates.isEmpty())
    }

    @Test
    fun `a note appended after the tag in Google survives and is written back once`() {
        val l = local(1, notes = "n", editedAt = 100, tickedAt = 100)
        val f = TaskEventFormat.fields(l, ZoneOffset.UTC)
        val row = TaskEventRow(11, f.title, f.description + "\nadded in Google", f.allDay, f.startMillis, f.timezone)
        val r = TaskEventFormat.decode(row, ZoneOffset.UTC)!!
        assertEquals("n\n\nadded in Google", r.notes)

        val p = plan(listOf(l), listOf(r), known = setOf("s1"))
        val merged = upserts(p).single().after
        assertEquals("n\n\nadded in Google", merged.notes)
        assertEquals(NOW, merged.editedAt)
        assertEquals(listOf(11L to merged), p.remoteUpdates)

        // Once written back, the next pass has nothing to do.
        val f2 = TaskEventFormat.fields(merged, ZoneOffset.UTC)
        val r2 = TaskEventFormat.decode(
            TaskEventRow(11, f2.title, f2.description, f2.allDay, f2.startMillis, f2.timezone), ZoneOffset.UTC,
        )!!
        val again = plan(listOf(merged), listOf(r2), known = setOf("s1"))
        assertTrue(again.localWrites.isEmpty())
        assertTrue(again.remoteUpdates.isEmpty())
    }

    // ---- write then read is stable ----

    @Test
    fun `notes the format cannot carry byte for byte still settle after one pass`() {
        val notes = listOf(
            "<a@b.c>",
            "John <john@x.com> and a<b>c",
            "Tom &amp; Jerry &lt;3",
            "line one\r\nline two",
            "[task] this line starts like a tag",
            "  padded  ",
        )
        for (n in notes) {
            val t = local(1, notes = n, time = LocalTime.of(9, 30))
            val f = TaskEventFormat.fields(t, ZoneOffset.UTC)
            val r = TaskEventFormat.decode(
                TaskEventRow(11, f.title, f.description, f.allDay, f.startMillis, f.timezone), ZoneOffset.UTC,
            )!!
            assertEquals(n.replace("\r\n", "\n").trim(), r.notes)
            val p = plan(listOf(t), listOf(r), known = setOf("s1"))
            assertTrue("local write for <$n>", p.localWrites.isEmpty())
            assertTrue("remote update for <$n>", p.remoteUpdates.isEmpty())
        }
    }

    // ---- repair of a damaged local list ----

    @Test
    fun `a damaged blob resets the sync history so nothing is deleted`() {
        assertTrue(TaskSyncRepair.resetsKnown("{not json"))
        assertTrue(TaskSyncRepair.resetsKnown("""[{"nonsense":true}]"""))
        assertEquals(false, TaskSyncRepair.resetsKnown(null))
        assertEquals(false, TaskSyncRepair.resetsKnown("   "))
        assertEquals(false, TaskSyncRepair.resetsKnown(TaskCodec.encode(listOf(local(1)))))

        val rows = listOf(remote("s1", 11), remote("s2", 12))
        // Unrepaired: the phone "has no tasks" and everything known is deleted from the calendar.
        val unrepaired = plan(emptyList(), rows, known = setOf("s1", "s2"))
        assertEquals(listOf(11L, 12L), unrepaired.remoteDeletes)
        // Repaired: history forgotten, nothing deleted, both come back to the phone.
        val repaired = plan(emptyList(), rows, known = emptySet())
        assertTrue(repaired.remoteDeletes.isEmpty())
        assertEquals(2, upserts(repaired).size)
    }

    // ---- unsynced local ----

    @Test
    fun `an unsynced local task gets an id and an insert`() {
        val t = local(1, syncId = null)
        val p = plan(listOf(t), emptyList())
        val withId = t.copy(syncId = "new1")
        assertEquals(listOf<LocalWrite>(LocalWrite.Upsert(t, withId)), p.localWrites)
        assertEquals(listOf(withId), p.remoteInserts)
        assertEquals(setOf("new1"), p.knownAfter)
    }

    // ---- one side only ----

    @Test
    fun `a local task that was known and is gone from the remote is deleted locally`() {
        val t = local(1)
        val p = plan(listOf(t), listOf(remote("s2", 12)), known = setOf("s1", "s2"))
        assertTrue(LocalWrite.Delete(t) in p.localWrites)
        assertTrue(p.remoteInserts.isEmpty())
        assertTrue("s1" !in p.knownAfter)
    }

    @Test
    fun `a local task never seen on the remote is inserted there`() {
        val t = local(1)
        val p = plan(listOf(t), listOf(remote("s2", 12)), known = setOf("s2"))
        assertEquals(listOf(t), p.remoteInserts)
        assertTrue("s1" in p.knownAfter)
    }

    @Test
    fun `a remote task that was known and is gone locally is deleted from the remote`() {
        val p = plan(listOf(local(2)), listOf(remote("s1", 11), remote("s2", 12)), known = setOf("s1", "s2"))
        assertEquals(listOf(11L), p.remoteDeletes)
        assertTrue(p.localWrites.isEmpty())
        assertEquals(setOf("s2"), p.knownAfter)
    }

    @Test
    fun `a new remote task becomes a local task with a fresh id`() {
        val r = remote("s9", 19, title = "From Jarvis", time = LocalTime.of(8, 15), notes = "n", done = true, editedAt = 50, tickedAt = 60)
        val p = plan(listOf(local(1)), listOf(remote("s1", 11), r), known = setOf("s1"))
        val up = upserts(p).single()
        assertEquals(null, up.before)
        assertEquals(
            Task(1000, "From Jarvis", day, LocalTime.of(8, 15), "n", true, "s9", 50, 60),
            up.after,
        )
        assertTrue("s9" in p.knownAfter)
        assertTrue(p.remoteUpdates.isEmpty())
    }

    // ---- safety ----

    @Test
    fun `an empty remote with a known history deletes nothing and re-inserts`() {
        val a = local(1)
        val b = local(2)
        val p = plan(listOf(a, b), emptyList(), known = setOf("s1", "s2", "s3"))
        assertTrue(p.localWrites.none { it is LocalWrite.Delete })
        assertEquals(listOf(a, b), p.remoteInserts)
        assertEquals(setOf("s1", "s2"), p.knownAfter)
    }

    // ---- duplicates ----

    @Test
    fun `duplicate remote rows keep the lowest event id and delete the rest`() {
        val p = plan(
            listOf(local(1)),
            listOf(remote("s1", 30), remote("s1", 10), remote("s1", 20)),
            known = setOf("s1"),
        )
        assertEquals(listOf(20L, 30L), p.remoteDeletes)
        assertTrue(p.localWrites.isEmpty())
        assertTrue(p.remoteUpdates.isEmpty())
        assertEquals(setOf("s1"), p.knownAfter)
    }

    @Test
    fun `the surviving duplicate is the one that is merged`() {
        val l = local(1, title = "Mine", editedAt = 300)
        val p = plan(listOf(l), listOf(remote("s1", 30, title = "A"), remote("s1", 10, title = "B")), known = setOf("s1"))
        assertEquals(listOf(10L to l), p.remoteUpdates)
        assertEquals(listOf(30L), p.remoteDeletes)
    }

    // ---- determinism ----

    @Test
    fun `new remote tasks are minted ids in syncId order`() {
        val p = plan(emptyList(), listOf(remote("sb", 2), remote("sa", 1)))
        assertEquals(listOf("sa", "sb"), upserts(p).map { it.after.syncId })
        assertEquals(listOf(1000L, 1001L), upserts(p).map { it.after.id })
    }

    // ---- sameContent ----

    @Test
    fun `sameContent ignores ids and compares everything else`() {
        val l = local(5, syncId = "x", notes = " a ", time = LocalTime.of(9, 0))
        val r = remote("x", 99, title = "Task 5", notes = "a", time = LocalTime.of(9, 0))
        assertTrue(TaskSyncPlan.sameContent(l, r))
        assertEquals(false, TaskSyncPlan.sameContent(l, r.copy(done = true)))
        assertEquals(false, TaskSyncPlan.sameContent(l, r.copy(tickedAt = 1)))
        assertEquals(false, TaskSyncPlan.sameContent(l, r.copy(dueTime = null)))
    }

    // ---- unreadable rows ----

    @Test
    fun `a known local task with an unreadable remote row is frozen, not deleted`() {
        val p = plan(listOf(local(1)), emptyList(), known = setOf("s1"), unreadable = setOf("s1"))
        assertTrue(p.localWrites.isEmpty())
        assertTrue(p.remoteInserts.isEmpty())
        assertTrue(p.remoteUpdates.isEmpty())
        assertTrue(p.remoteDeletes.isEmpty())
        assertEquals(setOf("s1"), p.knownAfter)
    }

    @Test
    fun `an unknown local task with an unreadable row is not inserted a second time`() {
        val p = plan(
            listOf(local(1), local(2)), listOf(remote("s2", 12)),
            known = setOf("s2"), unreadable = setOf("s1"),
        )
        assertTrue(p.localWrites.isEmpty())
        assertTrue(p.remoteInserts.isEmpty())
        assertEquals(setOf("s2"), p.knownAfter)
    }

    @Test
    fun `an unreadable row is never imported`() {
        val p = plan(emptyList(), emptyList(), unreadable = setOf("s1"))
        assertTrue(p.localWrites.isEmpty())
        assertTrue(p.knownAfter.isEmpty())
    }

    @Test
    fun `readable rows sharing a frozen id are left alone too`() {
        val p = plan(
            listOf(local(1, title = "Mine", editedAt = 300)),
            listOf(remote("s1", 10), remote("s1", 20)),
            known = setOf("s1"), unreadable = setOf("s1"),
        )
        assertTrue(p.remoteDeletes.isEmpty())
        assertTrue(p.remoteUpdates.isEmpty())
        assertTrue(p.localWrites.isEmpty())
        assertEquals(setOf("s1"), p.knownAfter)
    }

    @Test
    fun `an unreadable row means the calendar is not empty for the safety guard`() {
        // With no unreadable row this would be "recreated calendar": nothing deleted. With one, the
        // history is believed, so the task that really is missing is a deletion.
        val a = local(1)
        val b = local(2)
        val p = plan(listOf(a, b), emptyList(), known = setOf("s1", "s2"), unreadable = setOf("s1"))
        assertEquals(listOf<LocalWrite>(LocalWrite.Delete(b)), p.localWrites)
        assertEquals(setOf("s1"), p.knownAfter)
    }

    // ---- DST ----

    @Test
    fun `a time inside a spring forward gap does not rewrite the event on every sync`() {
        val london = ZoneId.of("Europe/London")
        val l = local(1, due = LocalDate.of(2026, 3, 29), time = LocalTime.of(1, 30))
        val f = TaskEventFormat.fields(l, london)
        val r = TaskEventFormat.decode(
            TaskEventRow(11, f.title, f.description, f.allDay, f.startMillis, f.timezone), london,
        )!!
        assertEquals(LocalTime.of(2, 30), r.dueTime)
        val p = plan(listOf(l), listOf(r), known = setOf("s1"), zone = london)
        assertTrue(p.remoteUpdates.isEmpty())
        assertTrue(p.localWrites.isEmpty())
    }

    // ---- TaskSyncApply ----

    @Test
    fun `apply changes a task only while it still equals what the plan saw`() {
        val seen = local(1, title = "Seen")
        val edited = seen.copy(title = "Edited since")
        val write = LocalWrite.Upsert(seen, seen.copy(title = "Merged"))

        val (fresh, freshApplied) = TaskSyncApply.apply(listOf(seen), listOf(write))
        assertEquals("Merged", fresh.single().title)
        assertEquals(setOf("s1"), freshApplied)

        val (stale, staleApplied) = TaskSyncApply.apply(listOf(edited), listOf(write))
        assertEquals(listOf(edited), stale)
        assertTrue(staleApplied.isEmpty())
    }

    @Test
    fun `apply deletes only an unchanged task`() {
        val seen = local(1)
        val (gone, applied) = TaskSyncApply.apply(listOf(seen), listOf(LocalWrite.Delete(seen)))
        assertTrue(gone.isEmpty())
        assertEquals(setOf("s1"), applied)

        val changed = seen.copy(done = true)
        val (kept, skipped) = TaskSyncApply.apply(listOf(changed), listOf(LocalWrite.Delete(seen)))
        assertEquals(listOf(changed), kept)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `apply gives an imported task the next free id when its id is taken`() {
        val mine = local(500, syncId = null)
        val imported = local(500, syncId = "s9")
        val (tasks, applied) = TaskSyncApply.apply(listOf(mine), listOf(LocalWrite.Upsert(null, imported)))
        assertEquals(setOf("s9"), applied)
        assertEquals(mine, tasks.first { it.syncId == null })
        assertEquals(501L, tasks.first { it.syncId == "s9" }.id)
    }

    @Test
    fun `apply skips an import whose syncId is already on the phone`() {
        val existing = local(1, syncId = "s9")
        val (tasks, applied) = TaskSyncApply.apply(
            listOf(existing), listOf(LocalWrite.Upsert(null, local(2, syncId = "s9"))),
        )
        assertEquals(listOf(existing), tasks)
        assertTrue(applied.isEmpty())
    }

    // ---- TaskSyncOutcome ----

    private fun knownAfter(
        p: SyncPlan,
        remote: List<RemoteTask>,
        applied: Set<String> = emptySet(),
        failedInserts: Set<String> = emptySet(),
        failedDeletes: Set<Long> = emptySet(),
    ) = TaskSyncOutcome.knownAfter(p, remote, applied, failedInserts, failedDeletes)

    @Test
    fun `a skipped import is not remembered as known`() {
        val r = remote("s9", 19)
        val p = plan(emptyList(), listOf(r))
        assertEquals(setOf("s9"), p.knownAfter)
        assertEquals(emptySet<String>(), knownAfter(p, listOf(r)))
        assertEquals(setOf("s9"), knownAfter(p, listOf(r), applied = setOf("s9")))
    }

    @Test
    fun `a skipped id assignment suppresses its insert and is not remembered`() {
        val t = local(1, syncId = null)
        val p = plan(listOf(t), emptyList())
        assertEquals(1, p.remoteInserts.size)
        assertTrue(TaskSyncOutcome.runnable(p, emptyList(), emptySet()).remoteInserts.isEmpty())
        assertEquals(1, TaskSyncOutcome.runnable(p, emptyList(), setOf("new1")).remoteInserts.size)
        assertTrue(knownAfter(p, emptyList()).isEmpty())
    }

    @Test
    fun `a skipped re-id of a duplicate local task does not remember the new id`() {
        val r = remote("s1", 11)
        val p = plan(listOf(local(1, "s1"), local(2, "s1")), listOf(r), known = setOf("s1"))
        assertEquals(setOf("s1", "new1"), p.knownAfter)
        assertEquals(setOf("s1"), knownAfter(p, listOf(r)))
        assertTrue(TaskSyncOutcome.runnable(p, listOf(r), emptySet()).remoteInserts.isEmpty())
    }

    @Test
    fun `a skipped merge keeps the id known but does not push the merge`() {
        val l = local(1, title = "Mine", editedAt = 100)
        val r = remote("s1", 11, title = "Theirs", editedAt = 200, tickedAt = 50)
        val p = plan(listOf(l), listOf(r), known = setOf("s1"))
        assertEquals(setOf("s1"), knownAfter(p, listOf(r)))
        assertTrue(TaskSyncOutcome.runnable(p, listOf(r), emptySet()).remoteUpdates.isEmpty())
    }

    @Test
    fun `a failed calendar delete stays known so it is retried`() {
        val gone = remote("s1", 11)
        val keep = remote("s2", 12)
        val p = plan(listOf(local(2)), listOf(gone, keep), known = setOf("s1", "s2"))
        assertEquals(listOf(11L), p.remoteDeletes)
        assertEquals(setOf("s2"), knownAfter(p, listOf(gone, keep)))
        assertEquals(setOf("s1", "s2"), knownAfter(p, listOf(gone, keep), failedDeletes = setOf(11L)))
    }

    @Test
    fun `a failed calendar insert is forgotten`() {
        val t = local(1)
        val other = remote("s2", 12)
        val p = plan(listOf(t), listOf(other), known = setOf("s2"))
        // s2 is known and remote-only, so it is a calendar delete; s1 is the insert.
        assertEquals(listOf(t), p.remoteInserts)
        assertEquals(setOf("s1"), knownAfter(p, listOf(other)))
        assertEquals(emptySet<String>(), knownAfter(p, listOf(other), failedInserts = setOf("s1")))
    }

    // ---- show ----

    @Test
    fun `a newer remote edit carries its show option to the phone`() {
        val p = plan(
            listOf(local(1, editedAt = 100)),
            listOf(remote("s1", 11, editedAt = 200).copy(show = TaskShow.UntilDone)),
        )
        val merged = upserts(p).single().after
        assertEquals(TaskShow.UntilDone, merged.show)
        assertEquals(200L, merged.editedAt)
        assertTrue(p.remoteUpdates.isEmpty())
    }

    @Test
    fun `equal stamps differing only in show adopt the remote show with a new stamp`() {
        val l = local(1, editedAt = 100, tickedAt = 100)
        val r = remote("s1", 11, editedAt = 100, tickedAt = 100).copy(show = TaskShow.WeekBefore)
        val p = plan(listOf(l), listOf(r))
        val merged = upserts(p).single().after
        assertEquals(TaskShow.WeekBefore, merged.show)
        assertEquals(NOW, merged.editedAt)
        assertEquals(listOf(11L to merged), p.remoteUpdates)
    }

    @Test
    fun `a newer local show is written to the remote`() {
        val l = local(1, editedAt = 300).copy(show = TaskShow.UntilDone)
        val p = plan(listOf(l), listOf(remote("s1", 11, editedAt = 100)))
        assertTrue(upserts(p).isEmpty())
        assertEquals(listOf(11L to l), p.remoteUpdates)
    }

    @Test
    fun `a new remote task imports its show option`() {
        val p = plan(emptyList(), listOf(remote("s9", 19).copy(show = TaskShow.UntilDone)))
        assertEquals(TaskShow.UntilDone, upserts(p).single().after.show)
    }
}
