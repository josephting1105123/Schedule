package com.EntropicSoftwareLab.Schedule.ui.screens.birthday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayRepository
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Year

data class BirthdayEditorUiState(
    val editing: Boolean = false,
    val loading: Boolean = false,
    val name: String = "",
    val date: LocalDate = LocalDate.now(),
    /** Unchecked stores no year at all, so no age is ever shown for this birthday. */
    val includeYear: Boolean = true,
    val saving: Boolean = false,
    /** Set once a write lands; the screen pops on it. */
    val finished: Boolean = false,
    val error: String? = null,
    /** From Google Contacts: shown, not editable — the change has to be made there. */
    val readOnly: Boolean = false,
) {
    val canSave: Boolean get() = name.isNotBlank() && !saving && !loading && !readOnly
}

/**
 * One form, two jobs, the same split
 * [com.EntropicSoftwareLab.Schedule.ui.screens.eventeditor.EventEditorViewModel] makes: creating a
 * birthday and editing an existing one. Far smaller than the event form because a birthday carries
 * only a name and a date — no calendar, no colour, no recurrence, no conflicts. The repository
 * mirrors it to a yearly event on the user's Google calendar; this form never sees that.
 */
class BirthdayEditorViewModel(
    private val repository: BirthdayRepository,
    initialDate: LocalDate? = null,
    private val editId: Long? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BirthdayEditorUiState(
            editing = editId != null,
            loading = editId != null,
            date = initialDate ?: LocalDate.now(),
        )
    )
    val state: StateFlow<BirthdayEditorUiState> = _state.asStateFlow()

    /** The birthday being edited, kept so a read-only one can be hidden without a second lookup. */
    private var loaded: Birthday? = null

    init {
        val id = editId
        if (id != null) {
            viewModelScope.launch {
                val existing = repository.birthdays.first().firstOrNull { it.id == id }
                if (existing == null) {
                    _state.update { it.copy(loading = false, error = "That birthday is already gone.") }
                    return@launch
                }
                loaded = existing
                val displayYear = when {
                    existing.year != null -> existing.year
                    existing.month == 2 && existing.day == 29 -> {
                        var y = LocalDate.now().year
                        while (!Year.isLeap(y.toLong())) y++
                        y
                    }
                    else -> LocalDate.now().year
                }
                _state.update {
                    it.copy(
                        loading = false,
                        name = existing.name,
                        date = existing.occurrenceIn(displayYear),
                        includeYear = existing.year != null,
                        readOnly = existing.readOnly,
                    )
                }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setDate(value: LocalDate) = _state.update { it.copy(date = value) }

    fun setIncludeYear(value: Boolean) = _state.update {
        val currentYear = LocalDate.now().year
        val date = if (value && it.date.year > currentYear) {
            val year = if (it.date.monthValue == 2 && it.date.dayOfMonth == 29) {
                var y = currentYear
                while (!Year.isLeap(y.toLong())) y--
                y
            } else {
                currentYear
            }
            LocalDate.of(year, it.date.monthValue, it.date.dayOfMonth)
        } else {
            it.date
        }
        it.copy(includeYear = value, date = date)
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // The repository links this to its calendar event by id, so a calendar-only birthday
            // keeps its id here and an edit updates that event rather than writing a second one.
            val birthday = Birthday(
                id = editId ?: System.currentTimeMillis(),
                name = current.name.trim(),
                month = current.date.monthValue,
                day = current.date.dayOfMonth,
                year = if (current.includeYear) current.date.year else null,
            )
            runCatching { repository.upsert(birthday) }
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

    /** For a birthday that can't be deleted (Google Contacts'): stop showing it in this app. */
    fun hide() {
        val existing = loaded ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.hide(existing) }
                .onSuccess { _state.update { it.copy(saving = false, finished = true) } }
                .onFailure { e ->
                    _state.update { it.copy(saving = false, error = e.message ?: "Couldn't hide it.") }
                }
        }
    }

    companion object {
        fun factory(container: AppContainer, initialDate: LocalDate?, editId: Long?) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BirthdayEditorViewModel(
                        repository = container.birthdayRepository,
                        initialDate = initialDate,
                        editId = editId,
                    ) as T
            }
    }
}
