package com.EntropicSoftwareLab.Schedule.widget

import android.content.Context
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthChips
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthGrid
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The month widget's events, by month, held in the app process between renders.
 *
 * `‹`/`›` used to query the provider on the tap's own path: step the offset, re-run `provideGlance`,
 * read 42 days of Instances, then draw. The live session now holds the shown month and its
 * neighbours itself (see `MonthWidget`); this is what it loads them through, and what a session that
 * has to start over reads from, so a restart still finds recently shown months without a query.
 *
 * Two things keep it honest:
 * - **[clear] on every [refreshWidgets]** — which is every write this app makes and every periodic
 *   refresh — so nothing the app itself changed is ever served stale.
 * - **[TTL_MS]** bounds what the app didn't change (a Google sync landing between refreshes): an
 *   entry older than that is re-read rather than trusted.
 *
 * Keyed by month *and* the zone and first day of week it was built for, because both decide which
 * 42 days the entry covers and which day each event lands on.
 */
internal object MonthWidgetCache {

    private const val TTL_MS = 2 * 60_000L
    /** The shown month and one either side, plus slack for a user tapping back and forth. */
    private const val MAX_MONTHS = 5

    private data class Key(val month: YearMonth, val zone: ZoneId, val firstDayOfWeek: DayOfWeek)
    private data class Entry(val loadedAtMillis: Long, val eventsByDate: Map<LocalDate, List<EventInstance>>)

    private val entries = ConcurrentHashMap<Key, Entry>()

    private val _generation = MutableStateFlow(0)

    /**
     * Bumped by every [clear]. A widget session that is still alive holds its months in composition
     * rather than re-reading them, so it watches this to know the calendar changed under it.
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun clear() {
        entries.clear()
        _generation.update { it + 1 }
    }

    /**
     * [month]'s events grouped by every day of its 42-day grid, from here when fresh, else from the
     * provider. Throws what the provider throws — the caller decides what an unreadable calendar
     * looks like.
     */
    suspend fun load(
        context: Context,
        month: YearMonth,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<LocalDate, List<EventInstance>> {
        val key = Key(month, zone, firstDayOfWeek)
        entries[key]?.let { if (nowMillis - it.loadedAtMillis < TTL_MS) return it.eventsByDate }

        // The visible 42 days rather than the calendar month — the days spilling in from either side
        // have to carry their own events or they render bare.
        val days = MonthGrid.days(month, firstDayOfWeek)
        val events = CalendarRepository(context.contentResolver)
            .eventsInRange(days.first(), days.last().plusDays(1), zone)
        val byDate = MonthChips.byDate(events, zone)
        entries[key] = Entry(nowMillis, byDate)
        trim(month)
        return byDate
    }

    /** Drops whatever is farthest from [center] until the cache is back under [MAX_MONTHS]. */
    private fun trim(center: YearMonth) {
        while (entries.size > MAX_MONTHS) {
            val farthest = entries.keys.maxByOrNull { abs(ChronoUnit.MONTHS.between(center, it.month)) }
                ?: return
            entries.remove(farthest)
        }
    }
}
