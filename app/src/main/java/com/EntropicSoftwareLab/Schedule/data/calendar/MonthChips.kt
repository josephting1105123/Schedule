package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The first and last local day an occurrence covers, both inclusive. */
data class DaySpan(val first: LocalDate, val last: LocalDate)

/**
 * One event's run across a single week row of the month grid.
 *
 * [startColumn] and [span] are columns of that week, so a Monday-to-Sunday trip is one chip and not
 * seven. That is the whole point of this type: a month cell is about fifty dp wide, which holds six
 * characters, and "Segamat Mission Trip" repeated across seven cells as "Seg…" tells the user
 * nothing. Drawn as one band it stays readable and the days it covers stay obvious.
 *
 * [continuesBefore]/[continuesAfter] say the run was cut by the week boundary rather than by the
 * event ending, so the band is drawn square where it carries on and rounded at a real edge.
 */
data class MonthChip(
    val event: EventInstance,
    val startColumn: Int,
    val span: Int,
    /** Slot within the cell, 0 at the top. Shared by every column the chip covers. */
    val row: Int,
    val continuesBefore: Boolean,
    val continuesAfter: Boolean,
)

/** What one week row of the grid shows, and what it had to leave out. */
data class WeekChips(
    val chips: List<MonthChip>,
    /** Column index -> how many of that day's events didn't fit, for the "+N" marker. */
    val hidden: Map<Int, Int>,
)

/** One day's dots on the month widget: the band row they sit in, the events drawn, and the rest. */
data class DayDots(
    val row: Int,
    val shown: List<EventInstance>,
    /** How many more the day holds than [shown] — drawn as "+N" after the dots. */
    val extra: Int,
)

/** A widget week: its bands (and `•••` columns, in [WeekChips.hidden]) plus each day's dots. */
data class WeekWithDots(
    val bands: WeekChips,
    /** Column index -> that day's dots. Absent for a day with no timed events on show. */
    val dots: Map<Int, DayDots>,
)

/**
 * Turns occurrences into the bands a month grid draws.
 *
 * Pure and zone-explicit so it can be tested off-device; the grid itself only positions what comes
 * back out of here.
 */
object MonthChips {

    /**
     * The local days [event] covers.
     *
     * An end landing exactly on midnight belongs to the previous day — both for an all-day event,
     * whose provider end is the exclusive day after, and for a meeting running to 00:00, which
     * would otherwise claim a whole extra day of the grid.
     */
    fun daySpan(event: EventInstance, zone: ZoneId): DaySpan {
        val first = event.localStart(zone).toLocalDate()
        val end = event.localEnd(zone)
        val last = if (end.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalDate() > first) {
            end.toLocalDate().minusDays(1)
        } else {
            end.toLocalDate()
        }
        // Some sync adapters emit rows ending before they start; those get the single day they began on.
        return DaySpan(first, maxOf(first, last))
    }

    /**
     * Whether the Month page draws [event] as a named band rather than a dot.
     *
     * Bands are for all-day and multi-day events only. A cell is about fifty dp wide, so a timed
     * event's name was never more than "FO…" there, and a Saturday of F1 sessions, church and
     * breakfasts stacked into unreadable chips. A span is what a band shows well — the days it
     * covers — and a timed event's detail (its time) lives in the agenda underneath anyway.
     */
    fun isBand(event: EventInstance, zone: ZoneId): Boolean =
        event.allDay || daySpan(event, zone).let { it.first != it.last }

    /**
     * Occurrences grouped by every local day they touch, not just the day they start.
     *
     * A multi-day event has to appear on each day it covers or it vanishes from the grid after its
     * first cell. Order within a day is the order [events] arrived in.
     */
    fun byDate(events: List<EventInstance>, zone: ZoneId): Map<LocalDate, List<EventInstance>> {
        val byDate = mutableMapOf<LocalDate, MutableList<EventInstance>>()
        for (event in events) {
            val span = daySpan(event, zone)
            var day = span.first
            while (!day.isAfter(span.last)) {
                byDate.getOrPut(day) { mutableListOf() }.add(event)
                day = day.plusDays(1)
            }
        }
        return byDate
    }

    /**
     * Packs everything happening in [week] into at most [maxRows] slots per cell.
     *
     * Longest run first, so multi-day bands settle at the top of the week and short events fill in
     * beneath them — the reverse would strand a band below a Monday-only meeting and leave a ragged
     * gap under it for the rest of the week. Ties break on all-day, then start, then title, then id:
     * a total order, so the same week lays out identically on every recomposition and a reload can't
     * shuffle the rows under the user's eyes.
     *
     * A run that lands past [maxRows] is dropped and counted against each day it covered, which is
     * what the cell's "+N" marker reports. Counting per column rather than per event is deliberate:
     * a Tuesday with three hidden events and a Wednesday with none are different days, and one
     * number spanning both would be true of neither.
     */
    fun layout(
        week: List<LocalDate>,
        eventsByDate: Map<LocalDate, List<EventInstance>>,
        zone: ZoneId,
        maxRows: Int,
    ): WeekChips {
        if (week.isEmpty() || maxRows <= 0) return WeekChips(emptyList(), emptyMap())

        // First and last column each occurrence appears in, keyed by occurrence so a band is one run.
        val firstColumn = LinkedHashMap<Long, Int>()
        val lastColumn = HashMap<Long, Int>()
        val events = HashMap<Long, EventInstance>()
        week.forEachIndexed { column, date ->
            for (event in eventsByDate[date].orEmpty()) {
                if (!firstColumn.containsKey(event.instanceId)) {
                    firstColumn[event.instanceId] = column
                    events[event.instanceId] = event
                }
                lastColumn[event.instanceId] = column
            }
        }

        val runs = firstColumn.keys
            .map { id -> Run(events.getValue(id), firstColumn.getValue(id), lastColumn.getValue(id)) }
            .sortedWith(
                compareByDescending<Run> { it.last - it.first }
                    .thenByDescending { it.event.allDay }
                    .thenBy { it.event.startMillis }
                    .thenBy { it.event.title }
                    .thenBy { it.event.instanceId },
            )

        val occupied = mutableListOf<BooleanArray>()
        val chips = mutableListOf<MonthChip>()
        val hidden = mutableMapOf<Int, Int>()
        for (run in runs) {
            var row = 0
            while (true) {
                if (row == occupied.size) occupied.add(BooleanArray(week.size))
                if ((run.first..run.last).none { occupied[row][it] }) break
                row++
            }
            for (column in run.first..run.last) occupied[row][column] = true

            if (row < maxRows) {
                val span = daySpan(run.event, zone)
                chips.add(
                    MonthChip(
                        event = run.event,
                        startColumn = run.first,
                        span = run.last - run.first + 1,
                        row = row,
                        continuesBefore = span.first < week[run.first],
                        continuesAfter = span.last > week[run.last],
                    ),
                )
            } else {
                for (column in run.first..run.last) hidden[column] = (hidden[column] ?: 0) + 1
            }
        }
        return WeekChips(chips, hidden)
    }

    /**
     * [layout], but with the last of [maxRows] given over to a "more" marker on any day that
     * overflows — the month widget's `•••`.
     *
     * A day that fits keeps every row for chips, so a Wednesday with exactly two events shows both.
     * A day that doesn't gets one row fewer of chips and the marker in the last: showing two chips
     * and nothing else would claim the day holds two events. Any last-row chip crossing a marked day
     * is taken out too, because a band cannot share a slot with the marker, and each day it covered
     * is then counted as overflowing — which can mark a neighbour that fitted on its own. That is
     * the honest outcome: that neighbour's event is no longer on screen either.
     *
     * In the result, [WeekChips.hidden] names exactly the columns whose last row carries the marker.
     * With one row there is nothing to give up, so this is plain [layout].
     */
    fun layoutWithMoreRow(
        week: List<LocalDate>,
        eventsByDate: Map<LocalDate, List<EventInstance>>,
        zone: ZoneId,
        maxRows: Int,
    ): WeekChips {
        val full = layout(week, eventsByDate, zone, maxRows)
        if (maxRows <= 1 || full.hidden.isEmpty()) return full
        return giveLastRowToMarker(full.chips, full.hidden, maxRows - 1)
    }

    /**
     * The month widget's week: named bands for all-day and multi-day events, dots for the rest —
     * the Month page's rule ([isBand]), with [layoutWithMoreRow]'s `•••` for a day that overflows.
     *
     * The widget used to name every event, so a timed one in a fifty-dp cell read "Churc…", the
     * exact thing the Month page stopped doing on 2026-09-28. Bands are packed first; a day's dots
     * then take the first row no band uses in that column, as they sit directly under the bands on
     * the page. A day whose rows are all taken by bands has its timed events counted into the
     * marker instead, so every event on a day is still represented by something.
     *
     * At most [maxDots] dots per day; past that, [maxDots] - 1 and a count of the rest
     * ([DayDots.extra]) — a row of eight dots is a smear.
     */
    fun layoutWithDots(
        week: List<LocalDate>,
        eventsByDate: Map<LocalDate, List<EventInstance>>,
        zone: ZoneId,
        maxRows: Int,
        maxDots: Int,
    ): WeekWithDots {
        if (week.isEmpty() || maxRows <= 0) return WeekWithDots(WeekChips(emptyList(), emptyMap()), emptyMap())

        val bandsByDate = eventsByDate.mapValues { (_, events) -> events.filter { isBand(it, zone) } }
        val timed = week.map { date -> eventsByDate[date].orEmpty().filterNot { isBand(it, zone) } }
        val full = layout(week, bandsByDate, zone, maxRows)

        // A day with timed events and no free row overflows just as a band that didn't fit does.
        val hidden = full.hidden.toMutableMap()
        val usedBefore = rowsUsed(full.chips, week.size)
        timed.forEachIndexed { column, events ->
            if (events.isNotEmpty() && usedBefore[column] >= maxRows) {
                hidden[column] = (hidden[column] ?: 0) + events.size
            }
        }
        val bands = if (maxRows <= 1 || hidden.isEmpty()) {
            WeekChips(full.chips, hidden)
        } else {
            giveLastRowToMarker(full.chips, hidden, maxRows - 1)
        }

        val used = rowsUsed(bands.chips, week.size)
        val marked = bands.hidden.toMutableMap()
        val dots = mutableMapOf<Int, DayDots>()
        timed.forEachIndexed { column, events ->
            if (events.isEmpty()) return@forEachIndexed
            val row = used[column]
            // The marker owns the last row of a day it is on, so dots there would sit on top of it.
            val limit = if (column in marked && maxRows > 1) maxRows - 1 else maxRows
            if (row < limit) {
                val shown = if (events.size > maxDots) (maxDots - 1).coerceAtLeast(0) else events.size
                dots[column] = DayDots(row, events.take(shown), events.size - shown)
            } else if (usedBefore[column] < maxRows) {
                // Only reachable on a day already carrying the marker: counted there, not lost.
                marked[column] = (marked[column] ?: 0) + events.size
            }
        }
        return WeekWithDots(WeekChips(bands.chips, marked), dots)
    }

    /** Per column, how many band rows are in use there — the first free row is this number. */
    private fun rowsUsed(chips: List<MonthChip>, columns: Int): IntArray {
        val used = IntArray(columns)
        for (chip in chips) {
            for (column in chip.startColumn until chip.startColumn + chip.span) {
                used[column] = maxOf(used[column], chip.row + 1)
            }
        }
        return used
    }

    /**
     * Takes out every [lastRow] chip crossing a column in [hidden], so that row can carry the marker,
     * and counts each day such a chip covered as overflowing. See [layoutWithMoreRow].
     */
    private fun giveLastRowToMarker(chips: List<MonthChip>, hidden: Map<Int, Int>, lastRow: Int): WeekChips {
        val marked = hidden.toMutableMap()
        val kept = mutableListOf<MonthChip>()
        for (chip in chips) {
            val columns = chip.startColumn until chip.startColumn + chip.span
            if (chip.row == lastRow && columns.any { it in hidden }) {
                for (column in columns) marked[column] = (marked[column] ?: 0) + 1
            } else {
                kept += chip
            }
        }
        return WeekChips(kept, marked)
    }

    private data class Run(val event: EventInstance, val first: Int, val last: Int)
}
