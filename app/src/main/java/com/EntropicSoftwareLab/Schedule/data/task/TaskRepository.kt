package com.EntropicSoftwareLab.Schedule.data.task

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Its own DataStore file, the same reason [com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayRepository]
 * has one: Preferences DataStore rewrites the whole file on every `edit`, and ticking a checkbox
 * should not rewrite the settings.
 */
// A corrupt file is replaced with an empty one: the task list starts empty, the sync's known-set
// is empty too, and the "Tasks" calendar refills the tasks (empty memory deletes nothing).
private val Context.taskStore: DataStore<Preferences> by preferencesDataStore(
    name = "tasks",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * [onChange] fires after each write the *user* makes (upsert, tick, delete) so the app can ask for a
 * sync; the writes a sync itself makes ([applyLocal], [setKnown]) never fire it, or a sync would
 * trigger the next one forever.
 */
class TaskRepository(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: () -> Unit = {},
) {

    private object Keys {
        val TASKS = stringPreferencesKey("tasks")
        val SYNC_KNOWN = stringSetPreferencesKey("syncKnown")
        val SYNC_CALENDAR = longPreferencesKey("syncCalendarId")
    }

    val tasks: Flow<List<Task>> =
        context.taskStore.data.map { TaskCodec.decode(it[Keys.TASKS]) }

    /**
     * Adds a task the user just created, minting its id here so it can't land on an existing one.
     *
     * The editor used to mint `System.currentTimeMillis()` itself, and a task imported from the
     * calendar can already hold that id; [upsert] would then read the new task as an edit of the
     * imported one and overwrite it. The id is the clock or one past the highest stored, whichever
     * is larger. Done is as given; both stamps are now.
     */
    suspend fun add(task: Task) {
        context.taskStore.edit { prefs ->
            prefs.dropSyncHistoryIfDamaged()
            val current = TaskCodec.decode(prefs[Keys.TASKS])
            val now = clock()
            val id = maxOf(now, (current.maxOfOrNull { it.id } ?: 0L) + 1)
            prefs[Keys.TASKS] = TaskCodec.encode(current + task.copy(id = id, editedAt = now, tickedAt = now))
        }
        onChange()
    }

    /**
     * Replaces the task with the same id (or adds one if there is none: prefer [add] for new tasks).
     *
     * An existing task keeps its stored done, tickedAt and syncId: the editor doesn't own them, and
     * the copy it holds may be stale (ticked on the list while the form was open, or synced since).
     * Either way the edit stamps [Task.editedAt], which is what a sync compares.
     */
    suspend fun upsert(task: Task) {
        context.taskStore.edit { prefs ->
            prefs.dropSyncHistoryIfDamaged()
            val current = TaskCodec.decode(prefs[Keys.TASKS])
            val existing = current.firstOrNull { it.id == task.id }
            val now = clock()
            val stamped = if (existing != null) {
                task.copy(
                    done = existing.done,
                    tickedAt = existing.tickedAt,
                    syncId = existing.syncId,
                    editedAt = now,
                )
            } else {
                task.copy(editedAt = now, tickedAt = now)
            }
            prefs[Keys.TASKS] = TaskCodec.encode(current.filterNot { it.id == task.id } + stamped)
        }
        onChange()
    }

    /**
     * Ticks or unticks one task, reading and writing inside the same `edit` block.
     *
     * Not `upsert(task.copy(done = …))` from a copy the UI is holding: the checkbox writes on every
     * tap, and a stale copy round-tripped through here would quietly undo an edit made in between.
     * A task that has since been deleted is left deleted rather than resurrected by the write.
     * Only a real change stamps [Task.tickedAt].
     *
     * Ticking an unfinished repeating task moves [Task.due] forward to [TaskRepeat.nextDue] and
     * leaves it unticked, stamping both [Task.editedAt] and [Task.tickedAt]. When the series has
     * no next date left, the tick finishes the task as normal.
     */
    suspend fun setDone(id: Long, done: Boolean) {
        var changed = false
        context.taskStore.edit { prefs ->
            prefs.dropSyncHistoryIfDamaged()
            val current = TaskCodec.decode(prefs[Keys.TASKS])
            val now = clock()
            prefs[Keys.TASKS] = TaskCodec.encode(
                current.map {
                    if (it.id == id && done && !it.done && it.repeat != null) {
                        val next = it.repeat.nextDue(it.due, LocalDate.now())
                        changed = true
                        if (next != null) {
                            it.copy(due = next, done = false, editedAt = now, tickedAt = now)
                        } else {
                            it.copy(done = true, tickedAt = now)
                        }
                    } else if (it.id == id && it.done != done) {
                        changed = true
                        it.copy(done = done, tickedAt = now)
                    } else it
                }
            )
        }
        if (changed) onChange()
    }

    suspend fun delete(id: Long) {
        context.taskStore.edit { prefs ->
            prefs.dropSyncHistoryIfDamaged()
            val current = TaskCodec.decode(prefs[Keys.TASKS])
            prefs[Keys.TASKS] = TaskCodec.encode(current.filterNot { it.id == id })
        }
        onChange()
    }

    /**
     * Called first in every user write. If the stored list is damaged this write is about to
     * replace it with whatever [TaskCodec.decode] salvaged, after which the sync history would
     * describe tasks that are gone; forgetting it here is what stops the next sync from deleting
     * them from the calendar. See [TaskSyncRepair].
     */
    private fun MutablePreferences.dropSyncHistoryIfDamaged() {
        if (TaskSyncRepair.resetsKnown(this[Keys.TASKS])) {
            remove(Keys.SYNC_KNOWN)
            remove(Keys.SYNC_CALENDAR)
        }
    }

    // ---- sync ----

    /**
     * The tasks, the syncIds known on both sides after the last sync, and which calendar that was.
     * Null when the stored list can't be read cleanly: a sync that took a damaged blob for "no
     * tasks" would delete every task from the calendar.
     */
    suspend fun syncState(): Triple<List<Task>, Set<String>, Long?>? {
        val prefs = context.taskStore.data.first()
        val tasks = TaskCodec.decodeStrict(prefs[Keys.TASKS]) ?: return null
        return Triple(tasks, prefs[Keys.SYNC_KNOWN] ?: emptySet(), prefs[Keys.SYNC_CALENDAR])
    }

    /**
     * Heals a damaged stored list before a sync: re-encodes what [TaskCodec.decode] can salvage
     * and forgets the sync history, in one edit. The sync that follows starts with nothing known,
     * so it deletes nothing, imports the calendar's tasks again, and re-inserts the survivors.
     */
    suspend fun repairDamaged() {
        context.taskStore.edit { prefs ->
            val blob = prefs[Keys.TASKS]
            if (TaskSyncRepair.resetsKnown(blob)) {
                prefs[Keys.TASKS] = TaskCodec.encode(TaskCodec.decode(blob))
                prefs.remove(Keys.SYNC_KNOWN)
                prefs.remove(Keys.SYNC_CALENDAR)
            }
        }
    }

    /**
     * Applies a sync plan's phone-side writes in one edit and returns the syncIds of those that
     * took effect; the rule is [TaskSyncApply.apply]. Applies nothing if the stored list can't be
     * read cleanly, because writing back what [TaskCodec.decode] salvaged would make the damage
     * permanent.
     */
    suspend fun applyLocal(writes: List<LocalWrite>): Set<String> {
        if (writes.isEmpty()) return emptySet()
        var applied: Set<String> = emptySet()
        context.taskStore.edit { prefs ->
            val current = TaskCodec.decodeStrict(prefs[Keys.TASKS]) ?: return@edit
            val (updated, ids) = TaskSyncApply.apply(current, writes)
            prefs[Keys.TASKS] = TaskCodec.encode(updated)
            applied = ids
        }
        return applied
    }

    suspend fun setKnown(calendarId: Long, ids: Set<String>) {
        context.taskStore.edit { prefs ->
            prefs[Keys.SYNC_KNOWN] = ids
            prefs[Keys.SYNC_CALENDAR] = calendarId
        }
    }
}
