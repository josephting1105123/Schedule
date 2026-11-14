package com.EntropicSoftwareLab.Schedule.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefs
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefsRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.AccountSyncStatus
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarAccount
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarInfo
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.settings.SettingsRepository
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val calendars: List<CalendarInfo> = emptyList(),
    val selectedCalendarId: Long? = null,
    val syncStatus: List<AccountSyncStatus> = emptyList(),
    val prefs: AccountPrefs = AccountPrefs(),
    /** Every account on the phone that owns a calendar, added here or not. */
    val phoneAccounts: List<CalendarAccount> = emptyList(),
    /** Every calendar the provider lists, before the account filter. */
    val allCalendars: List<CalendarInfo> = emptyList(),
) {
    val selectedCalendar: CalendarInfo?
        get() = calendars.firstOrNull { it.id == selectedCalendarId }
}

class SettingsViewModel(
    private val repository: CalendarRepository,
    private val settings: SettingsRepository,
    private val accountPrefs: AccountPrefsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // The writable list, the sync card and the account lists all depend on which accounts are
        // added, so each pref change reloads them rather than patching the old answers.
        viewModelScope.launch {
            accountPrefs.prefs.distinctUntilChanged().collect { prefs ->
                _state.update { it.copy(prefs = prefs) }
                loadCalendars()
                refreshSyncStatus()
            }
        }
    }

    private suspend fun loadCalendars() {
        runCatching {
            val calendars = repository.listWritableCalendars()
            val stored = settings.defaultCalendarId.first()
            val chosen = calendars.firstOrNull { it.id == stored }
                ?: calendars.firstOrNull { it.isPrimary }
                ?: calendars.firstOrNull()
            _state.update { it.copy(calendars = calendars, selectedCalendarId = chosen?.id) }
        }
        runCatching {
            val accounts = repository.calendarAccounts().distinct()
            val all = repository.listCalendars()
            _state.update { it.copy(phoneAccounts = accounts, allCalendars = all) }
        }
    }

    fun addAccount(a: CalendarAccount) {
        viewModelScope.launch { accountPrefs.addAccount(a.name, a.type) }
    }

    fun removeAccount(name: String) {
        viewModelScope.launch { accountPrefs.removeAccount(name) }
    }

    fun rename(name: String, nickname: String) {
        viewModelScope.launch { accountPrefs.setNickname(name, nickname) }
    }

    fun showHiddenBirthdays() {
        viewModelScope.launch { accountPrefs.clearHiddenBirthdays() }
    }

    /** Re-reads each account's sync state for the card. */
    fun refreshSyncStatus() {
        viewModelScope.launch {
            runCatching { repository.syncStatus() }.getOrNull()?.let { status ->
                _state.update { it.copy(syncStatus = status) }
            }
        }
    }

    /**
     * Asks every account to sync now, then re-reads the card a few times while the adapter works,
     * so the waiting count visibly drops — or visibly doesn't, which is the answer being looked for.
     */
    fun syncNow() {
        viewModelScope.launch {
            runCatching { repository.requestSync() }
            repeat(4) {
                runCatching { repository.syncStatus() }.getOrNull()?.let { status ->
                    _state.update { it.copy(syncStatus = status) }
                }
                delay(3_000)
            }
        }
    }

    fun setCalendar(id: Long) {
        _state.update { it.copy(selectedCalendarId = id) }
        viewModelScope.launch { settings.setDefaultCalendarId(id) }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    repository = container.calendarRepository,
                    settings = container.settingsRepository,
                    accountPrefs = container.accountPrefsRepository,
                ) as T
        }
    }
}
