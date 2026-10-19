package com.EntropicSoftwareLab.Schedule.data.task

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Its own DataStore file, the same reason [com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayRepository]
 * has one: Preferences DataStore rewrites the whole file on every `edit`, and ticking a checkbox
 * should not rewrite the settings.
 */
private val Context.taskStore: DataStore<Preferences> by preferencesDataStore(name = "tasks")

class TaskRepository(private val context: Context) {

    private object Keys {
        val TASKS = stringPreferencesKey("tasks")
    }

    val tasks: Flow<List<Task>> =
        context.taskStore.data.map { TaskCodec.decode(it[Keys.TASKS]) }

    /** Adds a new task or replaces the one with the same id. */
    suspend fun upsert(task: Task) {
        context.taskStore.edit { prefs ->
            val current = TaskCodec.decode(prefs[Keys.TASKS])
            prefs[Keys.TASKS] = TaskCodec.encode(current.filterNot { it.id == task.id } + task)
        }
    }

    /**
     * Ticks or unticks one task, reading and writing inside the same `edit` block.
     *
     * Not `upsert(task.copy(done = …))` from a copy the UI is holding: the checkbox writes on every
     * tap, and a stale copy round-tripped through here would quietly undo an edit made in between.
     * A task that has since been deleted is left deleted rather than resurrected by the write.
     */
    suspend fun setDone(id: Long, done: Boolean) {
        context.taskStore.edit { prefs ->
            val current = TaskCodec.decode(prefs[Keys.TASKS])
            prefs[Keys.TASKS] = TaskCodec.encode(
                current.map { if (it.id == id) it.copy(done = done) else it }
            )
        }
    }

    suspend fun delete(id: Long) {
        context.taskStore.edit { prefs ->
            val current = TaskCodec.decode(prefs[Keys.TASKS])
            prefs[Keys.TASKS] = TaskCodec.encode(current.filterNot { it.id == id })
        }
    }
}
