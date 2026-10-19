package com.EntropicSoftwareLab.Schedule.data.task

import java.time.LocalDate
import java.time.LocalTime

/**
 * A task: one deadline and a checkbox, kept entirely out of the calendar provider the same way
 * [com.EntropicSoftwareLab.Schedule.data.birthday.Birthday] is. [id] is app-minted, not a provider id.
 *
 * A task is a *point* in time, not a span, and that is the whole difference from an event. It claims
 * no hours, so it never reaches [com.EntropicSoftwareLab.Schedule.data.calendar.FreeBusy], never
 * takes a lane on the hour grid, and can never be reported as a clash — a deadline falling inside a
 * meeting is the normal case, not a double booking. Nothing here can make an hour look busy.
 *
 * [dueTime] is wall-clock, not an instant, and there is deliberately no [java.time.ZoneId] in this
 * file. An event is stored as a UTC millisecond and so keeps its absolute moment when the user
 * travels; "submit the report by 17:00" means five in the afternoon wherever they are. Storing a
 * `LocalTime` is what makes that true, and it also means there is no DST-skipped hour to resolve —
 * the trap [com.EntropicSoftwareLab.Schedule.data.calendar.HourGrid.bucketByHour] has to work
 * around does not exist for a task.
 */
data class Task(
    val id: Long,
    val title: String,
    val due: LocalDate,
    /**
     * Null means "some time that day". Such a task appears in the day's list and gets **no mark on
     * the hour grid** — there is no hour to put it at, and parking it at midnight would state a
     * deadline the user never set.
     */
    val dueTime: LocalTime?,
    val notes: String?,
    val done: Boolean,
) {
    companion object {

        /** The tasks due on [date], in display order. */
        fun on(tasks: List<Task>, date: LocalDate): List<Task> =
            ordered(tasks.filter { it.due == date })

        /**
         * Unfinished tasks whose deadline is already behind [today], oldest first.
         *
         * Shown only on the actual current day, and never marked on the grid: their deadline hour
         * belongs to another date, and drawing it on today's timetable would put a tick where
         * nothing is due. Without this a task that was never ticked off simply disappears the day
         * after it was due, which reads as the app losing it rather than as a deadline passing.
         */
        fun overdueAsOf(tasks: List<Task>, today: LocalDate): List<Task> =
            ordered(tasks.filter { !it.done && it.due.isBefore(today) })

        /**
         * Display order: unfinished before done, then by deadline, then timed before untimed.
         *
         * Ticking a task therefore drops it to the bottom of the section rather than leaving a
         * struck-through line in the middle of what is still outstanding. Title and id break the
         * remaining ties so a list never reshuffles between two reads of the same data.
         */
        fun ordered(tasks: List<Task>): List<Task> = tasks.sortedWith(
            compareBy(
                { it.done },
                { it.due },
                // Untimed after timed within a day: "by 09:00" is a firmer claim on the day than
                // "some time today", so it reads first.
                { it.dueTime == null },
                { it.dueTime ?: LocalTime.MIDNIGHT },
                { it.title.lowercase() },
                { it.id },
            )
        )
    }
}
