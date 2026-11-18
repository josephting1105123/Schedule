package com.EntropicSoftwareLab.Schedule.data.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Covers the pure halves of a sync pass that `TaskSyncPlanTest` leaves out: how
 * [TaskSyncApply], [TaskSyncOutcome.skipped] and [TaskSyncOutcome.runnable] behave on their own,
 * and what a whole plan -> apply -> outcome -> replan cycle does when the user edits a task in
 * between. `TaskSync` itself takes a `Context`-backed `TaskRepository` and a `ContentResolver`-backed
 * `CalendarRepository`, so it can't be built in a JVM test.
 */
class TaskSyncApplyTest {

    private val day = LocalDate.of(2026, 11, 20)

    private fun task(
        id: Long,
        syncId: String? = "s$id",
        title: String = "Task $id",
        done: Boolean = false,
        editedAt: Long = 100,
        tickedAt: Long = 100,
    ) = Task(id, title, day, null, null, done, syncId, editedAt, tickedAt)

    private fun remote(
        syncId: String,
        eventId: Long = 1,
        title: String = "Task ${syncId.drop(1)}",
        done: Boolean = false,
        editedAt: Long = 100,
        tickedAt: Long = 100,
    ) = RemoteTask(eventId, syncId, title, day, null, null, done, editedAt, tickedAt)

    private var syncCounter = 0
    private var idCounter = 1000L

    private fun plan(
        local: List<Task>,
        remote: List<RemoteTask>,
        known: Set<String> = emptySet(),
        unreadable: Set<String> = emptySet(),
    ) = TaskSyncPlan.plan(
        local, remote, known, unreadable, ZoneOffset.UTC, 1_000_000L,
        newSyncId = { "new${++syncCounter}" }, newLocalId = { idCounter++ },
    )

    // ---- TaskSyncApply ----

    @Test
    fun `an upsert of a task the user deleted since is dropped, not resurrected`() {
        val seen = task(1)
        val (tasks, applied) = TaskSyncApply.apply(
            emptyList(), listOf(LocalWrite.Upsert(seen, seen.copy(title = "Merged"))),
        )
        assertTrue(tasks.isEmpty())
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a delete of a task that is already gone applies nothing`() {
        val (tasks, applied) = TaskSyncApply.apply(listOf(task(2)), listOf(LocalWrite.Delete(task(1))))
        assertEquals(listOf(task(2)), tasks)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `an imported task keeps its own id when it is free`() {
        val (tasks, applied) = TaskSyncApply.apply(
            listOf(task(1)), listOf(LocalWrite.Upsert(null, task(7, syncId = "s9"))),
        )
        assertEquals(setOf("s9"), applied)
        assertEquals(7L, tasks.first { it.syncId == "s9" }.id)
    }

    @Test
    fun `a taken id moves above the highest id on the phone, not just by one`() {
        val existing = listOf(task(5, syncId = null), task(900, syncId = null))
        val (tasks, _) = TaskSyncApply.apply(
            existing, listOf(LocalWrite.Upsert(null, task(5, syncId = "s9"))),
        )
        assertEquals(901L, tasks.first { it.syncId == "s9" }.id)
        assertEquals(3, tasks.map { it.id }.toSet().size)
    }

    @Test
    fun `an import without a syncId is never applied`() {
        val (tasks, applied) = TaskSyncApply.apply(
            emptyList(), listOf(LocalWrite.Upsert(null, task(1, syncId = null))),
        )
        assertTrue(tasks.isEmpty())
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `writes apply in order and one skipped write does not stop the rest`() {
        val a = task(1)
        val b = task(2)
        val staleA = a.copy(title = "stale view")
        val (tasks, applied) = TaskSyncApply.apply(
            listOf(a, b),
            listOf(
                LocalWrite.Upsert(staleA, staleA.copy(title = "X")),
                LocalWrite.Upsert(b, b.copy(done = true)),
            ),
        )
        assertEquals(listOf(a, b.copy(done = true)), tasks)
        assertEquals(setOf("s2"), applied)
    }

    @Test
    fun `a write that gives a task its first syncId reports that id as applied`() {
        val bare = task(1, syncId = null)
        val (tasks, applied) = TaskSyncApply.apply(
            listOf(bare), listOf(LocalWrite.Upsert(bare, bare.copy(syncId = "new1"))),
        )
        assertEquals("new1", tasks.single().syncId)
        assertEquals(setOf("new1"), applied)
    }

    @Test
    fun `deleting a task that has no syncId applies but reports no id`() {
        val bare = task(1, syncId = null)
        val (tasks, applied) = TaskSyncApply.apply(listOf(bare), listOf(LocalWrite.Delete(bare)))
        assertTrue(tasks.isEmpty())
        assertTrue(applied.isEmpty())
    }

    // ---- TaskSyncOutcome.skipped / runnable ----

    @Test
    fun `skipped names every planned local write that did not apply, upserts and deletes alike`() {
        val a = task(1)
        val b = task(2)
        val p = SyncPlan(
            localWrites = listOf(LocalWrite.Upsert(a, a.copy(title = "m")), LocalWrite.Delete(b)),
            remoteInserts = emptyList(), remoteUpdates = emptyList(), remoteDeletes = emptyList(),
            knownAfter = emptySet(),
        )
        assertEquals(setOf("s1", "s2"), TaskSyncOutcome.skipped(p, emptySet()))
        assertEquals(setOf("s2"), TaskSyncOutcome.skipped(p, setOf("s1")))
        assertTrue(TaskSyncOutcome.skipped(p, setOf("s1", "s2")).isEmpty())
    }

    @Test
    fun `runnable keeps every calendar operation when everything applied`() {
        val r = remote("s1", 11, title = "Theirs", editedAt = 200, tickedAt = 50)
        val p = plan(listOf(task(1, title = "Mine", editedAt = 100)), listOf(r), known = setOf("s1"))
        val applied = TaskSyncApply.apply(listOf(task(1, title = "Mine", editedAt = 100)), p.localWrites).second
        assertEquals(p, TaskSyncOutcome.runnable(p, listOf(r), applied))
    }

    @Test
    fun `runnable drops a calendar delete whose phone-side write was skipped`() {
        val gone = task(1)
        val r = remote("s1", 11)
        // Hand-built: a delete of the event and a phone write for the same id that didn't apply.
        val p = SyncPlan(
            localWrites = listOf(LocalWrite.Delete(gone)),
            remoteInserts = emptyList(), remoteUpdates = emptyList(), remoteDeletes = listOf(11L),
            knownAfter = emptySet(),
        )
        assertTrue(TaskSyncOutcome.runnable(p, listOf(r), emptySet()).remoteDeletes.isEmpty())
        assertEquals(listOf(11L), TaskSyncOutcome.runnable(p, listOf(r), setOf("s1")).remoteDeletes)
    }

    @Test
    fun `runnable leaves unrelated operations alone when one write was skipped`() {
        val l1 = task(1, title = "Mine1", editedAt = 300)
        val l2 = task(2, syncId = null)
        val r1 = remote("s1", 11, title = "Theirs", editedAt = 100)
        val p = plan(listOf(l1, l2), listOf(r1), known = setOf("s1"))
        // s1's merge needs no local write; only the id assignment for the unsynced task exists.
        assertEquals(listOf(11L to l1), p.remoteUpdates)
        val run = TaskSyncOutcome.runnable(p, listOf(r1), emptySet())
        assertTrue(run.remoteInserts.isEmpty())
        assertEquals(p.remoteUpdates, run.remoteUpdates)
    }

    // ---- TaskSyncOutcome.knownAfter ----

    @Test
    fun `a failed delete for an event not in the calendar read adds nothing`() {
        val r = remote("s1", 11)
        val p = plan(listOf(task(1)), listOf(r), known = setOf("s1"))
        assertEquals(setOf("s1"), TaskSyncOutcome.knownAfter(p, listOf(r), emptySet(), emptySet(), setOf(99L)))
    }

    @Test
    fun `a frozen id stays known whatever else failed`() {
        val p = plan(listOf(task(1)), emptyList(), known = setOf("s1"), unreadable = setOf("s1"))
        assertEquals(
            setOf("s1"),
            TaskSyncOutcome.knownAfter(p, emptyList(), emptySet(), setOf("zzz"), setOf(5L)),
        )
    }

    @Test
    fun `a failed insert does not touch the ids that did go through`() {
        val a = task(1, syncId = null)
        val b = task(2, syncId = null)
        val p = plan(listOf(a, b), emptyList())
        val applied = TaskSyncApply.apply(listOf(a, b), p.localWrites).second
        assertEquals(setOf("new1", "new2"), applied)
        assertEquals(setOf("new2"), TaskSyncOutcome.knownAfter(p, emptyList(), applied, setOf("new1"), emptySet()))
    }

    // ---- whole cycles ----

    /** One pass against fixed lists: plan, apply on `current` (which may differ), outcome. */
    private class Pass(val applied: Set<String>, val tasksAfter: List<Task>, val known: Set<String>, val plan: SyncPlan)

    private fun pass(
        stored: List<Task>,
        seenByPlan: List<Task>,
        remote: List<RemoteTask>,
        known: Set<String>,
        failedInserts: Set<String> = emptySet(),
        failedDeletes: Set<Long> = emptySet(),
    ): Pass {
        val p = plan(seenByPlan, remote, known)
        val (after, applied) = TaskSyncApply.apply(stored, p.localWrites)
        val known2 = TaskSyncOutcome.knownAfter(p, remote, applied, failedInserts, failedDeletes)
        return Pass(applied, after, known2, p)
    }

    @Test
    fun `a task the user edits while a delete is planned survives and is pushed back next pass`() {
        val seen = task(1, title = "Before")
        val edited = seen.copy(title = "Edited meanwhile", editedAt = 500)
        // The calendar lost it and it was known, so the plan deletes the phone's copy.
        val first = pass(stored = listOf(edited), seenByPlan = listOf(seen), remote = listOf(remote("s2", 12)), known = setOf("s1", "s2"))
        assertTrue(first.plan.localWrites.single() is LocalWrite.Delete)
        assertEquals(listOf(edited), first.tasksAfter)
        assertTrue("s1" !in first.known)
        // Next pass: no longer believed deleted, so it goes back to the calendar.
        val second = plan(first.tasksAfter, listOf(remote("s2", 12)), first.known)
        assertEquals(listOf(edited), second.remoteInserts)
        assertTrue(second.localWrites.none { it is LocalWrite.Delete })
    }

    @Test
    fun `a merge skipped because of a local tick is replanned and still wins by stamp`() {
        val seen = task(1, title = "Mine", editedAt = 100, tickedAt = 100)
        val r = remote("s1", 11, title = "Theirs", editedAt = 200, tickedAt = 100)
        val ticked = seen.copy(done = true, tickedAt = 400)
        val first = pass(stored = listOf(ticked), seenByPlan = listOf(seen), remote = listOf(r), known = setOf("s1"))
        assertTrue(first.applied.isEmpty())
        assertEquals(listOf(ticked), first.tasksAfter)
        assertEquals(setOf("s1"), first.known)
        // Second pass sees both changes and keeps the tick and the rename.
        val second = plan(first.tasksAfter, listOf(r), first.known)
        val merged = (second.localWrites.single() as LocalWrite.Upsert).after
        assertEquals("Theirs", merged.title)
        assertEquals(true, merged.done)
        assertEquals(400L, merged.tickedAt)
    }

    @Test
    fun `a failed calendar insert is retried next pass, not read as a deletion`() {
        val t = task(1)
        val other = remote("s2", 12)
        val first = pass(
            stored = listOf(t), seenByPlan = listOf(t), remote = listOf(other),
            known = setOf("s2"), failedInserts = setOf("s1"),
        )
        assertTrue("s1" !in first.known)
        val second = plan(first.tasksAfter, listOf(other), first.known)
        // Not known, so not deleted: inserted again.
        assertTrue(second.localWrites.none { it is LocalWrite.Delete })
        assertEquals(listOf(t), second.remoteInserts)
    }

    @Test
    fun `a failed calendar delete is retried next pass and the survivor is not re-imported`() {
        val keep = task(2)
        val gone = remote("s1", 11)
        val keepRemote = remote("s2", 12)
        val first = pass(
            stored = listOf(keep), seenByPlan = listOf(keep), remote = listOf(gone, keepRemote),
            known = setOf("s1", "s2"), failedDeletes = setOf(11L),
        )
        assertEquals(setOf("s1", "s2"), first.known)
        val second = plan(first.tasksAfter, listOf(gone, keepRemote), first.known)
        assertEquals(listOf(11L), second.remoteDeletes)
        assertTrue(second.localWrites.isEmpty())
    }

    @Test
    fun `an id taken during the pass moves the import and stays known`() {
        val r = remote("s9", 19)
        val p = plan(emptyList(), listOf(r))
        val importedId = (p.localWrites.single() as LocalWrite.Upsert).after.id
        val mine = task(importedId, syncId = null)
        val (after, applied) = TaskSyncApply.apply(listOf(mine), p.localWrites)
        assertEquals(setOf("s9"), applied)
        assertEquals(2, after.size)
        assertEquals(setOf("s9"), TaskSyncOutcome.knownAfter(p, listOf(r), applied, emptySet(), emptySet()))
    }
}
