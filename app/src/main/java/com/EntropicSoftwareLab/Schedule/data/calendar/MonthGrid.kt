package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The 6×7 day grid a month is drawn on.
 *
 * Both the in-app Month page and the month widget draw from this. The page used to take its grid
 * from the kizitonwose calendar library, which is Compose-UI only and unusable from Glance; the page
 * now pages months with a `HorizontalPager` so it can keep the months either side composed, and
 * that left this the only grid arithmetic in the app — pure and testable rather than hand-inlined.
 *
 * **Always six rows, never five.** A month that happens to fit in five would otherwise render
 * shorter than its neighbours, and a widget that changes height when you tap `›` looks broken. The
 * grid is padded into the following month instead.
 */
object MonthGrid {

    const val DAYS_PER_WEEK = 7
    const val WEEKS = 6
    const val CELLS = DAYS_PER_WEEK * WEEKS

    /**
     * Exactly [CELLS] consecutive dates: the days before the 1st needed to reach [firstDayOfWeek],
     * the month itself, then enough of the next month to fill six rows.
     */
    fun days(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
        val first = month.atDay(1)
        // Math.floorMod equivalent: DayOfWeek.value is 1..7, and the offset must stay in 0..6 even
        // when the month starts before the week does.
        val offset = ((first.dayOfWeek.value - firstDayOfWeek.value) + DAYS_PER_WEEK) % DAYS_PER_WEEK
        val start = first.minusDays(offset.toLong())
        return (0 until CELLS).map { start.plusDays(it.toLong()) }
    }

    /** [days] split into six rows of seven, which is the shape both the grid and the chips want. */
    fun weeks(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<LocalDate>> =
        days(month, firstDayOfWeek).chunked(DAYS_PER_WEEK)

    /** The seven weekday labels in the order [days] lays them out. */
    fun weekdayOrder(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        (0 until DAYS_PER_WEEK).map { firstDayOfWeek.plus(it.toLong()) }

    /**
     * The [columns] + 1 column boundaries of a grid [gridWidth] wide: cumulative left edges, plus the
     * grid's own right edge as the final entry. Column `i` runs from `edges[i]` to `edges[i + 1]`, so
     * its width is `edges[i + 1] - edges[i]`.
     *
     * The month widget lays its grid out in computed dp instead of Glance's `defaultWeight()`, because
     * `defaultWeight()` is equal division with no fractional weight — a multi-day band can only be
     * drawn as several adjacent equal-width boxes sharing one colour, which clips its title to
     * whichever cell holds the first one. Fixed dp widths fix that, but only if they sum *exactly* to
     * [gridWidth]: naively repeating `gridWidth / columns` [columns] times does not, and a grid that's
     * even a fraction short or long makes the day-number row and the chip rows beneath it creep out of
     * alignment with each other — which reads as the widget being broken, not as a rounding error.
     * Deriving each edge directly as `gridWidth * i / columns`, rather than accumulating a running
     * per-column width, keeps every edge its own exact fraction of [gridWidth] instead of compounding
     * rounding error from one column into the next, and [columns] equal real-valued divisions of a
     * continuous width naturally come out equal (up to float precision), with nothing to spread.
     *
     * [columns] must be positive — a caller asking for a zero- or negative-column grid has a bug, and
     * this throws rather than handing back edges nothing could use. [gridWidth] must not be negative
     * for the same reason; the widget floors its own grid width well above zero before ever calling
     * this. A [gridWidth] of exactly `0f` is not a bug, though — it's what a collapsing widget passes
     * in the instant before it has any room at all — so that returns [columns] + 1 zeros rather than
     * throwing.
     */
    fun columnEdges(gridWidth: Float, columns: Int = DAYS_PER_WEEK): List<Float> {
        require(columns > 0) { "columns must be positive, was $columns" }
        require(gridWidth >= 0f) { "gridWidth must not be negative, was $gridWidth" }
        // i == columns is pinned to gridWidth directly rather than left to the formula, so the right
        // edge is bit-exact even if float rounding inside the division wouldn't otherwise land there.
        return (0..columns).map { i -> if (i == columns) gridWidth else gridWidth * i / columns }
    }
}

/**
 * Which month a widget instance is showing, and whether that choice has gone stale.
 *
 * The `‹`/`›` buttons store an offset from the current month. Storing the day it was set on as well
 * is what lets it expire: browse to December and glance at the widget tomorrow, and it is showing
 * this month again, because an offset from *yesterday* means nothing. Ten minutes later on the same
 * day it is still where you left it, and no calendar write moves it either — a widget that snapped
 * back while you were reading it would be worse than one that drifts.
 */
object MonthWidgetState {

    /** The month to render for a stored [offset] set on [offsetDay], given [today]. */
    fun monthFor(today: LocalDate, offset: Int, offsetDay: Long): YearMonth {
        val current = YearMonth.from(today)
        return if (offsetDay == today.toEpochDay()) current.plusMonths(offset.toLong()) else current
    }

    /**
     * The offset after a step, clamped so a stuck button can't wander off into year 3000 — and
     * reset to a step from *this* month when the stored one is stale.
     */
    fun step(today: LocalDate, offset: Int, offsetDay: Long, delta: Int): Int {
        val base = if (offsetDay == today.toEpochDay()) offset else 0
        return (base + delta).coerceIn(-MAX_OFFSET, MAX_OFFSET)
    }

    /** Ten years either way is further than anyone taps, and keeps the arithmetic in range. */
    const val MAX_OFFSET = 120
}
