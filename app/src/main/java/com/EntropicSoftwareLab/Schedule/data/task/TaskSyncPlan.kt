package com.EntropicSoftwareLab.Schedule.data.task

import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What one sync pass should do, worked out before anything is written. */
data class SyncPlan(
    /** Upserts and deletes on the phone. */
    val localWrites: List<LocalWrite>,
    /** Tasks (already carrying a syncId) to add as events. */
    val remoteInserts: List<Task>,
    /** eventId -> the merged task to write over that event. */
    val remoteUpdates: List<Pair<Long, Task>>,
    val remoteDeletes: List<Long>,
    /** SyncIds present on both sides once every operation succeeds. */
    val knownAfter: Set<String>,
)

sealed interface LocalWrite {
    /** The task as the plan saw it; a write applies only if the stored task still equals it. */
    val before: Task?

    data class Upsert(override val before: Task?, val after: Task) : LocalWrite
    data class Delete(override val before: Task) : LocalWrite
}

/**
 * The two-way merge between the phone's tasks and the Tasks calendar. Pure: it reads two lists and
 * a set of ids and says what to write, so the rules are testable without a provider.
 *
 * `known` is the set of syncIds that were on both sides after the last successful sync. That is the
 * only thing that tells "deleted over there" from "never reached there": a task present on one side
 * and missing on the other was deleted if it is in `known`, and is new if it is not.
 */
object TaskSyncPlan {

    /** Equality of what a sync carries; ids are ignored because each side mints its own. */
    fun sameContent(a: Task, b: RemoteTask): Boolean =
        a.title.trim() == b.title &&
            a.due == b.due &&
            a.dueTime?.truncatedTo(ChronoUnit.MINUTES) == b.dueTime &&
            normalised(a.notes) == b.notes &&
            a.show == b.show &&
            a.repeat == b.repeat &&
            a.done == b.done &&
            a.editedAt == b.editedAt &&
            a.tickedAt == b.tickedAt

    private fun normalised(notes: String?): String? = notes?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The task as the calendar will give it back after it is written in [zone]: the real write
     * ([TaskEventFormat.fields]) followed by the real read ([TaskEventFormat.decode]). Anything
     * writing can't carry exactly (a wall time inside a spring-forward gap, line endings,
     * whitespace) is already settled here, so comparing against it can't disagree with itself and
     * rewrite an event on every pass. Null only if the write is unreadable, which a titled task
     * with a syncId never is.
     */
    private fun roundTrip(task: Task, zone: ZoneId): RemoteTask? {
        val f = TaskEventFormat.fields(task, zone)
        return TaskEventFormat.decode(
            TaskEventRow(0L, f.title, f.description, f.allDay, f.startMillis, f.timezone),
            zone,
        )
    }

    private fun sameText(a: RemoteTask, b: RemoteTask): Boolean =
        a.title == b.title && a.due == b.due && a.dueTime == b.dueTime && a.notes == b.notes &&
            a.show == b.show && a.repeat == b.repeat

    private fun sameAll(a: RemoteTask, b: RemoteTask): Boolean =
        sameText(a, b) && a.done == b.done && a.editedAt == b.editedAt && a.tickedAt == b.tickedAt

    /**
     * [unreadable] holds the syncIds of calendar rows that claim to be tasks but didn't decode (a
     * cleared title, a damaged tag). They are present, not absent: such a task is frozen, with no
     * write on either side, and stays known if it was known. Treating the row as missing would
     * delete the phone's copy, and the row would never decode again to heal it.
     */
    fun plan(
        local: List<Task>,
        remote: List<RemoteTask>,
        known: Set<String>,
        unreadable: Set<String>,
        zone: ZoneId,
        now: Long,
        newSyncId: () -> String,
        newLocalId: () -> Long,
    ): SyncPlan {
        // An empty calendar with a non-empty history means it was emptied or recreated, not that
        // every task was deleted; believing the history would wipe the phone. An unreadable row
        // means the calendar is not empty.
        val knownIds = if (remote.isEmpty() && unreadable.isEmpty()) emptySet() else known

        val localWrites = mutableListOf<LocalWrite>()
        val remoteInserts = mutableListOf<Task>()
        val remoteUpdates = mutableListOf<Pair<Long, Task>>()
        val remoteDeletes = mutableListOf<Long>()
        val knownAfter = mutableSetOf<String>()

        // Two events with one syncId: the lowest eventId is the task, the rest are stray copies.
        // A frozen id's rows are left alone entirely.
        val remoteById = LinkedHashMap<String, RemoteTask>()
        for (r in remote.sortedBy { it.eventId }) {
            if (r.syncId in unreadable) continue
            if (remoteById.containsKey(r.syncId)) remoteDeletes += r.eventId else remoteById[r.syncId] = r
        }

        // A local task is "unsynced" if it has no id, or shares one with a lower-id task. The
        // second case shouldn't happen; giving the extra a fresh id loses nothing.
        val localById = LinkedHashMap<String, Task>()
        val unsynced = mutableListOf<Task>()
        for (t in local.sortedBy { it.id }) {
            val sid = t.syncId
            if (sid == null || localById.containsKey(sid)) unsynced += t else localById[sid] = t
        }

        for (t in unsynced) {
            val withId = t.copy(syncId = newSyncId())
            localWrites += LocalWrite.Upsert(before = t, after = withId)
            remoteInserts += withId
            knownAfter += withId.syncId!!
        }

        for (sid in (localById.keys + remoteById.keys + unreadable).toSortedSet()) {
            if (sid in unreadable) {
                if (sid in knownIds) knownAfter += sid
                continue
            }
            val l = localById[sid]
            val r = remoteById[sid]
            when {
                l != null && r != null -> {
                    // What the local task would read back as, compared with what is on the calendar:
                    // writing noise doesn't count as a difference.
                    val written = roundTrip(l, zone)
                    val textDiffers = written != null && !sameText(written, r)
                    val tickDiffers = l.done != r.done

                    var merged = l
                    if (r.editedAt > l.editedAt) {
                        merged = merged.copy(
                            title = r.title, due = r.due, dueTime = r.dueTime,
                            notes = r.notes, show = r.show, repeat = r.repeat, editedAt = r.editedAt,
                        )
                    } else if (r.editedAt == l.editedAt && textDiffers) {
                        // Equal stamps with different text: neither app made this edit (both stamp
                        // a newer time), so it came from Google Calendar's own UI. Take it, and
                        // stamp it newer than the calendar's tag so the write-back is not a tie.
                        merged = merged.copy(
                            title = r.title, due = r.due, dueTime = r.dueTime,
                            notes = r.notes, show = r.show, repeat = r.repeat, editedAt = maxOf(now, r.editedAt + 1),
                        )
                    }
                    if (r.tickedAt > l.tickedAt) {
                        merged = merged.copy(done = r.done, tickedAt = r.tickedAt)
                    } else if (r.tickedAt == l.tickedAt && tickDiffers) {
                        merged = merged.copy(done = r.done, tickedAt = maxOf(now, r.tickedAt + 1))
                    }
                    if (merged != l) localWrites += LocalWrite.Upsert(before = l, after = merged)
                    val mergedWritten = roundTrip(merged, zone)
                    if (mergedWritten != null && !sameAll(mergedWritten, r)) remoteUpdates += r.eventId to merged
                    knownAfter += sid
                }
                l != null -> {
                    if (sid in knownIds) {
                        localWrites += LocalWrite.Delete(l)
                    } else {
                        remoteInserts += l
                        knownAfter += sid
                    }
                }
                r != null -> {
                    if (sid in knownIds) {
                        remoteDeletes += r.eventId
                    } else {
                        localWrites += LocalWrite.Upsert(
                            before = null,
                            after = Task(
                                id = newLocalId(),
                                title = r.title,
                                due = r.due,
                                dueTime = r.dueTime,
                                notes = r.notes,
                                done = r.done,
                                syncId = sid,
                                editedAt = r.editedAt,
                                tickedAt = r.tickedAt,
                                show = r.show,
                                repeat = r.repeat,
                            ),
                        )
                        knownAfter += sid
                    }
                }
            }
        }

        return SyncPlan(
            localWrites = localWrites,
            remoteInserts = remoteInserts,
            remoteUpdates = remoteUpdates,
            remoteDeletes = remoteDeletes.sorted(),
            knownAfter = knownAfter,
        )
    }
}

/**
 * The compare-and-set rule for the phone-side half of a plan, on a plain list so it can be tested
 * without DataStore. [TaskRepository.applyLocal] runs this inside one edit.
 */
object TaskSyncApply {

    /**
     * Returns the new list and the syncIds of the writes that took effect. A write applies only if
     * the stored task is still exactly what the plan saw: the user may have edited, ticked or
     * deleted it since, and overwriting that with a stale merge would lose it. A skipped write is
     * planned again by the next sync.
     *
     * A task new from the calendar needs its syncId to be unclaimed. If its id is taken (the
     * user's own new task landed on it) it gets the next free id rather than being dropped.
     */
    fun apply(current: List<Task>, writes: List<LocalWrite>): Pair<List<Task>, Set<String>> {
        var tasks = current
        val applied = mutableSetOf<String>()
        for (write in writes) {
            when (write) {
                is LocalWrite.Upsert -> {
                    val after = write.after
                    val before = write.before
                    if (before == null) {
                        val sid = after.syncId
                        if (sid != null && tasks.none { it.syncId == sid }) {
                            val id = if (tasks.none { it.id == after.id }) {
                                after.id
                            } else {
                                maxOf(after.id, (tasks.maxOfOrNull { it.id } ?: 0L) + 1)
                            }
                            tasks = tasks + after.copy(id = id)
                            applied += sid
                        }
                    } else if (tasks.firstOrNull { it.id == before.id } == before) {
                        tasks = tasks.map { if (it.id == before.id) after else it }
                        after.syncId?.let { applied += it }
                    }
                }
                is LocalWrite.Delete -> {
                    val before = write.before
                    if (tasks.firstOrNull { it.id == before.id } == before) {
                        tasks = tasks.filterNot { it.id == before.id }
                        before.syncId?.let { applied += it }
                    }
                }
            }
        }
        return tasks to applied
    }
}

/**
 * What a pass remembers once its writes have run, kept pure because getting it wrong is silent:
 * the `known` set must never claim a task reached a side it didn't, or the next pass reads the
 * missing copy as a deletion and spreads it.
 */
object TaskSyncOutcome {

    /** SyncIds of phone-side writes that did not apply. */
    fun skipped(plan: SyncPlan, applied: Set<String>): Set<String> = plan.localWrites.mapNotNull { write ->
        when (write) {
            is LocalWrite.Upsert -> write.after.syncId
            is LocalWrite.Delete -> write.before.syncId
        }
    }.filter { it !in applied }.toSet()

    /**
     * The plan's calendar-side operations minus those that depend on a skipped phone-side write.
     * An insert whose id assignment didn't apply would create an event the phone doesn't know it
     * has; an update built from a merge that didn't apply would write something the phone never
     * adopted.
     */
    fun runnable(plan: SyncPlan, remote: List<RemoteTask>, applied: Set<String>): SyncPlan {
        val skipped = skipped(plan, applied)
        val syncIdByEvent = remote.associate { it.eventId to it.syncId }
        return plan.copy(
            remoteInserts = plan.remoteInserts.filter { it.syncId !in skipped },
            remoteUpdates = plan.remoteUpdates.filter { it.second.syncId !in skipped },
            remoteDeletes = plan.remoteDeletes.filter { syncIdByEvent[it] !in skipped },
        )
    }

    /**
     * The ids to remember: the plan's, less any that a skipped write or a failed insert means
     * didn't pair up, plus any whose calendar delete failed (still known, so the delete is retried
     * rather than the survivor being re-imported as a new task).
     */
    fun knownAfter(
        plan: SyncPlan,
        remote: List<RemoteTask>,
        applied: Set<String>,
        failedInserts: Set<String>,
        failedDeletes: Set<Long>,
    ): Set<String> {
        val result = plan.knownAfter.toMutableSet()
        for (write in plan.localWrites) {
            val upsert = write as? LocalWrite.Upsert ?: continue
            val sid = upsert.after.syncId ?: continue
            // A write that gives a task its first syncId, replaces a duplicate's, or brings a
            // calendar task onto the phone pairs the sides; if it didn't apply, nothing is paired.
            if (sid !in applied && upsert.before?.syncId != sid) result -= sid
        }
        result -= failedInserts
        val syncIdByEvent = remote.associate { it.eventId to it.syncId }
        for (eventId in failedDeletes) syncIdByEvent[eventId]?.let { result += it }
        return result
    }
}

/**
 * When the stored task list is damaged, the remembered sync history describes tasks the phone no
 * longer has, and must go with it. Left in place, the next sync reads every known task as deleted
 * on the phone and deletes it from the calendar, and so from Jarvis. With the history cleared,
 * nothing is believed deleted: the calendar's tasks are imported again and the phone's survivors
 * are matched against them.
 */
object TaskSyncRepair {

    /** True when [blob] is non-blank and can't be read strictly. */
    fun resetsKnown(blob: String?): Boolean = !blob.isNullOrBlank() && TaskCodec.decodeStrict(blob) == null
}
