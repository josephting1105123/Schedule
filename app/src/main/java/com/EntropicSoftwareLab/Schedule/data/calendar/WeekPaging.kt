package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The arithmetic behind the Week page: which Sunday-to-Saturday week a date belongs to, and what
 * that week is called.
 *
 * **Sunday first, always, everywhere** — [FIRST_DAY] is also what the Month page and the month
 * widget lay their grids out by (the user's choice, 2026-09-27), not the locale's first day. One
 * constant for all three keeps them from disagreeing, and a grid that followed the phone's region
 * would reorder itself on a trip abroad.
 */
object WeekPaging {

    val FIRST_DAY: DayOfWeek = DayOfWeek.SUNDAY
    const val DAYS_PER_WEEK = 7

    /** The Sunday that starts the week containing [date] — [date] itself when it is a Sunday. */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(FIRST_DAY))

    /** The seven days of the week starting [weekStart], Sunday to Saturday. */
    fun days(weekStart: LocalDate): List<LocalDate> =
        (0 until DAYS_PER_WEEK).map { weekStart.plusDays(it.toLong()) }

    /** Whole weeks from [from]'s week to [to]'s — negative when [to] is earlier. */
    fun weeksBetween(from: LocalDate, to: LocalDate): Long =
        ChronoUnit.WEEKS.between(weekStart(from), weekStart(to))

    /**
     * "This week", "Last week" or "Next week" near [today], else the range itself.
     *
     * Relative only one week either way: "3 weeks ago" makes the user do subtraction to find out
     * which dates they are looking at, which the range says outright.
     */
    fun title(weekStart: LocalDate, today: LocalDate): String =
        when (weeksBetween(today, weekStart)) {
            0L -> "This week"
            -1L -> "Last week"
            1L -> "Next week"
            else -> range(weekStart)
        }

    /**
     * "27 Sep – 3 Oct 2026", with the year once at the end — or on both sides when the week
     * straddles New Year, since "28 Dec – 3 Jan 2027" reads as a week running backwards.
     */
    fun range(weekStart: LocalDate): String {
        val end = weekStart.plusDays((DAYS_PER_WEEK - 1).toLong())
        return if (weekStart.year == end.year) {
            "${weekStart.format(DAY_MONTH)} – ${end.format(DAY_MONTH_YEAR)}"
        } else {
            "${weekStart.format(DAY_MONTH_YEAR)} – ${end.format(DAY_MONTH_YEAR)}"
        }
    }

    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    private val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
}
