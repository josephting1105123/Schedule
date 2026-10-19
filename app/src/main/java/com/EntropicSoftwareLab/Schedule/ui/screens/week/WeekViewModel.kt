package com.EntropicSoftwareLab.Schedule.ui.screens.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.EntropicSoftwareLab.Schedule.data.appearance.BrandColorAssignment
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthChips
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekPaging
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class WeekUiState(
    /** The Sunday of the week the pager has settled on. */
    val visibleWeek: LocalDate = WeekPaging.weekStart(LocalDate.now()),
    /**
     * Week start to that week's occurrences, grouped by every day they touch. An absent week means
     * "not fetched yet", not "empty" — the same distinction the Today page draws, so a week never
     * flashes "Nothing scheduled" before it loads.
     */
    val eventsByWeek: Map<LocalDate, Map<LocalDate, List<EventInstance>>> = emptyMap(),
    val loadingWeeks: Set<LocalDate> = emptySet(),
    /** Every birthday, unfiltered — resolved onto dates only where a page asks. */
    val birthdays: List<Birthday> = emptyList(),
    /** Event id to its colour: the user's pick, else a brand colour assigned per day. */
    val eventColors: Map<Long, Int> = emptyMap(),
    val error: String? = null,
) {
    fun isLoading(weekStart: LocalDate): Boolean = weekStart !in eventsByWeek

    /** Less the birthday events, which the list under the timetable already shows as birthdays. */
    fun eventsOn(date: LocalDate): List<EventInstance> =
        Birthday.withoutBirthdayEvents(eventsByWeek[WeekPaging.weekStart(date)]?.get(date).orEmpty(), birthdays)

    /** The week's birthdays by date, Sunday to Saturday. */
    fun birthdaysIn(weekStart: LocalDate): Map<LocalDate, List<Birthday>> =
        Birthday.byDate(birthdays, weekStart, weekStart.plusDays(WeekPaging.DAYS_PER_WEEK.toLong()))
}

/** Contrast order, not picker order — see [EventColorPalette.assignmentOrder]. */
private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

/**
 * Backs the Week page: a pager of Sunday-to-Saturday weeks, each a list of days.
 *
 * Shaped like [com.EntropicSoftwareLab.Schedule.ui.screens.dashboard.DashboardViewModel] one level
 * up — a week instead of a day is the unit that is fetched, cached and prefetched. The week on show
 * and the weeks either side are always kept loaded, so a swipe lands on a week that is already here.
 */
class WeekViewModel(
    private val repository: CalendarRepository,
    private val birthdayRepository: BirthdayRepository,
    private val eventColorRepository: EventColorRepository,
    private val zoneProvider: () -> ZoneId,
) : ViewModel() {

    private val _state = MutableStateFlow(WeekUiState())
    val state: StateFlow<WeekUiState> = _state.asStateFlow()

    /** The user's own picks, held aside so a newly loaded week can be recoloured without re-reading. */
    private var colorOverrides: Map<Long, Int> = emptyMap()

    /**
     * Bumped whenever the cache is thrown away. A fetch that started before the bump returns rows
     * read before the change, so it is discarded rather than written back over the refetch.
     */
    private var generation = 0

    init {
        // Emits immediately, then on every provider change. A change could have touched any cached
        // week, so all of them go and the visible neighbourhood is fetched again.
        viewModelScope.launch {
            repository.observeChanges().collect {
                generation++
                _state.update { it.copy(eventsByWeek = emptyMap(), loadingWeeks = emptySet()) }
                showWeek(_state.value.visibleWeek)
            }
        }
        viewModelScope.launch {
            birthdayRepository.birthdays.collect { list -> _state.update { it.copy(birthdays = list) } }
        }
        // No provider change fires when this app recolours an event, so the list repaints off this.
        viewModelScope.launch {
            eventColorRepository.overrides.collect { map ->
                colorOverrides = map
                _state.update { it.copy(eventColors = recolour(it.eventsByWeek)) }
            }
        }
    }

    /** The pager settled on [weekStart]: it and both neighbours are loaded, the rest trimmed. */
    fun showWeek(weekStart: LocalDate) {
        _state.update { it.copy(visibleWeek = weekStart) }
        prefetch(weekStart)
        trimCache(weekStart)
    }

    /**
     * Called while the pager is still moving, for the page it is heading to, so a fast fling lands on
     * a week whose fetch started while the finger was still down. Does not touch `visibleWeek`.
     */
    fun prefetch(weekStart: LocalDate) {
        ensureLoaded(weekStart)
        ensureLoaded(weekStart.minusWeeks(1))
        ensureLoaded(weekStart.plusWeeks(1))
    }

    /** Fetches [weekStart]'s week unless it's cached or already in flight. */
    private fun ensureLoaded(weekStart: LocalDate) {
        val current = _state.value
        if (weekStart in current.eventsByWeek || weekStart in current.loadingWeeks) return
        _state.update { it.copy(loadingWeeks = it.loadingWeeks + weekStart) }
        val startedIn = generation
        viewModelScope.launch {
            try {
                val zone = zoneProvider()
                val end = weekStart.plusDays(WeekPaging.DAYS_PER_WEEK.toLong())
                val events = repository.eventsInRange(weekStart, end, zone)
                // byDate spreads a multi-day event onto every day it touches, which can reach past
                // the week at either end; only this week's seven days belong to this entry.
                val byDate = MonthChips.byDate(events, zone)
                    .filterKeys { !it.isBefore(weekStart) && it.isBefore(end) }
                // The calendar changed while this ran; a newer fetch for the same week owns the slot.
                if (startedIn != generation) return@launch
                _state.update {
                    val merged = it.eventsByWeek + (weekStart to byDate)
                    it.copy(
                        eventsByWeek = merged,
                        eventColors = recolour(merged),
                        loadingWeeks = it.loadingWeeks - weekStart,
                        error = null,
                    )
                }
            } catch (e: SecurityException) {
                if (startedIn != generation) return@launch
                _state.update {
                    it.copy(loadingWeeks = it.loadingWeeks - weekStart, error = "Calendar access was revoked.")
                }
            } catch (e: Exception) {
                if (startedIn != generation) return@launch
                _state.update {
                    it.copy(
                        loadingWeeks = it.loadingWeeks - weekStart,
                        error = e.message ?: "Couldn't read the calendar.",
                    )
                }
            }
        }
    }

    /**
     * Picks plus per-day brand colours across every cached week. Per day, as everywhere else: the
     * rule is that events sharing a day look different.
     */
    private fun recolour(eventsByWeek: Map<LocalDate, Map<LocalDate, List<EventInstance>>>): Map<Long, Int> {
        val byDate = HashMap<LocalDate, List<EventInstance>>()
        eventsByWeek.values.forEach { byDate.putAll(it) }
        return BrandColorAssignment.forDates(byDate, colorOverrides, BRAND_PALETTE)
    }

    /** Keeps the visible week and two either side, so a long swipe session never piles up memory. */
    private fun trimCache(center: LocalDate) {
        _state.update { state ->
            val keep = (-2L..2L).map { center.plusWeeks(it) }.toSet()
            state.copy(eventsByWeek = state.eventsByWeek.filterKeys { it in keep })
        }
    }

    companion object {
        /** Two years of weeks either side of this one — further than any plausible swipe. */
        const val WEEK_WINDOW_RADIUS = 104

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WeekViewModel(
                    repository = container.calendarRepository,
                    birthdayRepository = container.birthdayRepository,
                    eventColorRepository = container.eventColorRepository,
                ) { container.zone } as T
        }
    }
}
