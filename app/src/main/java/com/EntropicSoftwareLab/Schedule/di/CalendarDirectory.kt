package com.EntropicSoftwareLab.Schedule.di

import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarInfo
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.EventColorOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The writable calendars and their palettes, fetched once and shared.
 *
 * Tapping "+" used to start three provider round-trips *after* navigation had begun: the calendar
 * list, then the account's colours, then the day's events for the conflict check. The form appeared
 * with an empty calendar dropdown, a blank swatch strip, and a Save button disabled by `canSave`'s
 * `selectedCalendarId != null` with nothing on screen to say why. Warmed here, the editor opens with
 * all of it already in hand.
 *
 * The same `listWritableCalendars()` query was also duplicated in three ViewModels — the editor, the
 * assistant and Settings — each paying for it separately. They can all read this instead.
 *
 * Deliberately **not** warmed from `AppContainer`'s constructor or `Application.onCreate`. The
 * container's whole design is that nothing touches the ContentResolver until a screen asks, and a
 * provider query on the main thread before the first frame is exactly the cold-start cost that rule
 * exists to avoid. [warm] is called from `MainActivity` once the calendar permission is known to be
 * granted — querying without it throws.
 */
class CalendarDirectory(
    private val repository: CalendarRepository,
    private val scope: CoroutineScope,
) {

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    private val _colorsByCalendar = MutableStateFlow<Map<Long, List<EventColorOption>>>(emptyMap())
    val colorsByCalendar: StateFlow<Map<Long, List<EventColorOption>>> =
        _colorsByCalendar.asStateFlow()

    /** Guards [warm] so two screens racing to warm it don't both run the query. */
    private val lock = Mutex()
    private var warmed = false

    /**
     * Fetches the calendar list, then every account palette behind it.
     *
     * Idempotent: repeated calls after the first do nothing, so a screen may call it freely. Failure
     * is swallowed and leaves [warmed] set — a device that refuses the query once will refuse it
     * again, and the ViewModels all still fall back to fetching for themselves.
     */
    fun warm() {
        scope.launch {
            lock.withLock {
                if (warmed) return@withLock
                warmed = true
                val list = runCatching { repository.listWritableCalendars() }.getOrNull()
                    ?: return@withLock
                _calendars.value = list
                // Sequentially rather than in parallel: this is background warm-up nobody is waiting
                // on, and a handful of concurrent cursor queries on one ContentResolver is the kind
                // of thing that competes with the frame the user is actually looking at.
                val colors = mutableMapOf<Long, List<EventColorOption>>()
                for (calendar in list) {
                    colors[calendar.id] =
                        runCatching { repository.eventColors(calendar) }.getOrDefault(emptyList())
                }
                _colorsByCalendar.value = colors
            }
        }
    }

    /**
     * The palette for one calendar: the warm copy if there is one, otherwise a fetch that also
     * populates the cache.
     *
     * The fallback matters — a calendar created after [warm] ran, or a warm that failed, must still
     * produce colours rather than an empty strip.
     */
    suspend fun colorsFor(calendar: CalendarInfo): List<EventColorOption> {
        _colorsByCalendar.value[calendar.id]?.let { return it }
        val fetched = runCatching { repository.eventColors(calendar) }.getOrDefault(emptyList())
        _colorsByCalendar.value = _colorsByCalendar.value + (calendar.id to fetched)
        return fetched
    }

    /** The warm calendar list, or a fetch when nothing is warm yet. */
    suspend fun writableCalendars(): List<CalendarInfo> {
        _calendars.value.takeIf { it.isNotEmpty() }?.let { return it }
        val list = runCatching { repository.listWritableCalendars() }.getOrDefault(emptyList())
        if (list.isNotEmpty()) _calendars.value = list
        return list
    }

    /** Drops the cache so the next read re-queries. For a provider change that adds a calendar. */
    fun invalidate() {
        scope.launch {
            lock.withLock {
                warmed = false
                _colorsByCalendar.value = emptyMap()
            }
        }
    }
}
