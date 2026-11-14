package com.EntropicSoftwareLab.Schedule.data.task

import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID

/**
 * Runs one two-way sync between the stored tasks and the "Tasks" calendar. The decisions are
 * [TaskSyncPlan]'s; this class only gathers the inputs, applies the result, and works out what to
 * remember afterwards.
 *
 * What it remembers is the set of syncIds that made it to both sides ([TaskRepository.setKnown]),
 * and every rule below serves one end: that set must never claim a task reached a side it didn't.
 * A task wrongly believed synced and then missing from one side is read as *deleted there* by the
 * next pass, and deleted here to match.
 */
class TaskSync(
    private val tasks: TaskRepository,
    private val calendar: CalendarRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /**
     * Never throws: no calendar permission, a missing provider or a half-failed pass all end here
     * as a failed [Result], and whatever was left undone is planned again next time. Calls queue
     * rather than overlap, since two passes reading the same state would each insert the same task.
     */
    suspend fun sync(): Result<Unit> = mutex.withLock {
        try {
            Result.success(syncOnce())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private suspend fun syncOnce() {
        val cal = calendar.taskCalendar() ?: return
        // Null means the query failed. Reading that as "no tasks" would, with a stale known set,
        // look like every task having been deleted on the other side.
        val rows = calendar.taskEventRows(cal.id) ?: return
        val zone = ZoneId.systemDefault()
        val decoded = rows.map { it to TaskEventFormat.decode(it, zone) }
        val remote = decoded.mapNotNull { it.second }
        // Rows that say they are tasks but can't be read. They are present, not missing: see
        // TaskSyncPlan.plan.
        val unreadable = decoded.filter { it.second == null }
            .mapNotNull { TaskEventFormat.claimedSyncId(it.first) }.toSet()

        // Null is a damaged local blob. Reading it as "no tasks" would delete everything from the
        // calendar, so it is repaired first (which also forgets the sync history) and read again.
        val state = tasks.syncState() ?: run {
            tasks.repairDamaged()
            tasks.syncState()
        } ?: return
        val (local, knownStored, knownCalendar) = state
        // A different calendar than last time (deleted and recreated, or another account's) has no
        // shared history with the stored ids.
        val known = if (knownCalendar == cal.id) knownStored else emptySet()

        var nextLocalId = maxOf((local.maxOfOrNull { it.id } ?: 0L) + 1, clock())
        val plan = TaskSyncPlan.plan(
            local = local,
            remote = remote,
            known = known,
            unreadable = unreadable,
            zone = zone,
            now = clock(),
            newSyncId = { UUID.randomUUID().toString().replace("-", "") },
            newLocalId = { nextLocalId++ },
        )

        val applied = tasks.applyLocal(plan.localWrites)
        val toRun = TaskSyncOutcome.runnable(plan, remote, applied)

        val failedInserts = mutableSetOf<String>()
        val failedDeletes = mutableSetOf<Long>()
        var remoteOps = 0

        for (task in toRun.remoteInserts) {
            remoteOps++
            val id = runCatching {
                calendar.insertTaskEvent(cal.id, TaskEventFormat.fields(task, zone))
            }.getOrNull()
            if (id == null) task.syncId?.let { failedInserts += it }
        }

        for ((eventId, task) in toRun.remoteUpdates) {
            remoteOps++
            // A failure leaves the id known: both sides do hold the task, and the merge reruns.
            runCatching { calendar.updateTaskEvent(eventId, TaskEventFormat.fields(task, zone)) }
        }

        for (eventId in toRun.remoteDeletes) {
            remoteOps++
            val ok = runCatching { calendar.deleteTaskEvent(eventId) }.getOrDefault(false)
            if (!ok) failedDeletes += eventId
        }

        tasks.setKnown(cal.id, TaskSyncOutcome.knownAfter(plan, remote, applied, failedInserts, failedDeletes))
        if (remoteOps > 0) runCatching { calendar.requestSync() }
    }
}
