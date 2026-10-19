package com.EntropicSoftwareLab.Schedule.ui.screens.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.EntropicSoftwareLab.Schedule.data.task.Task
import com.EntropicSoftwareLab.Schedule.data.task.TaskRepository
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class TaskEditorUiState(
    val editing: Boolean = false,
    val loading: Boolean = false,
    val title: String = "",
    val due: LocalDate = LocalDate.now(),
    /** Unchecked stores no time at all, so the task gets no mark on the hour grid. */
    val hasTime: Boolean = false,
    /** Only meaningful while [hasTime]; kept across a toggle so unticking and reticking is free. */
    val time: LocalTime = LocalTime.of(17, 0),
    val notes: String = "",
    /**
     * Carried, never edited here.
     *
     * The checkbox lives on the row, and saving an edit must not quietly untick a finished task —
     * defaulting this to false on the way out would do exactly that to anything opened after it was
     * completed.
     */
    val done: Boolean = false,
    val saving: Boolean = false,
    /** Set once a write lands; the screen pops on it. */
    val finished: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = title.isNotBlank() && !saving && !loading
}

/**
 * One form, two jobs, the same split
 * [com.EntropicSoftwareLab.Schedule.ui.screens.birthday.BirthdayEditorViewModel] makes: creating a
 * task and editing an existing one.
 *
 * Smaller than the event form because a task has no end, no calendar, no colour, no recurrence and
 * no conflict check — a deadline can't clash with anything. It never reaches CalendarContract.
 */
class TaskEditorViewModel(
    private val repository: TaskRepository,
    private val editId: Long? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        TaskEditorUiState(
            editing = editId != null,
            loading = editId != null,
            // A new task starts due today with no particular time — the least it can claim. The
            // "+" chooser passes no seed, matching how Event and Birthday open from the same sheet.
            due = LocalDate.now(),
        )
    )
    val state: StateFlow<TaskEditorUiState> = _state.asStateFlow()

    init {
        val id = editId
        if (id != null) {
            viewModelScope.launch {
                val existing = repository.tasks.first().firstOrNull { it.id == id }
                if (existing == null) {
                    _state.update { it.copy(loading = false, error = "That task is already gone.") }
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        title = existing.title,
                        due = existing.due,
                        hasTime = existing.dueTime != null,
                        time = existing.dueTime ?: it.time,
                        notes = existing.notes.orEmpty(),
                        done = existing.done,
                    )
                }
            }
        }
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value) }

    fun setDue(value: LocalDate) = _state.update { it.copy(due = value) }

    fun setHasTime(value: Boolean) = _state.update { it.copy(hasTime = value) }

    fun setTime(value: LocalTime) = _state.update { it.copy(time = value) }

    fun setNotes(value: String) = _state.update { it.copy(notes = value) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val task = Task(
                id = editId ?: System.currentTimeMillis(),
                title = current.title.trim(),
                due = current.due,
                dueTime = current.time.takeIf { current.hasTime },
                notes = current.notes.trim().takeIf { it.isNotBlank() },
                done = current.done,
            )
            runCatching { repository.upsert(task) }
                .onSuccess { _state.update { it.copy(saving = false, finished = true) } }
                .onFailure { e ->
                    _state.update { it.copy(saving = false, error = e.message ?: "Couldn't save it.") }
                }
        }
    }

    fun delete() {
        val id = editId ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess { _state.update { it.copy(saving = false, finished = true) } }
                .onFailure { e ->
                    _state.update { it.copy(saving = false, error = e.message ?: "Couldn't delete it.") }
                }
        }
    }

    companion object {
        fun factory(container: AppContainer, editId: Long?) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TaskEditorViewModel(
                        repository = container.taskRepository,
                        editId = editId,
                    ) as T
            }
    }
}
