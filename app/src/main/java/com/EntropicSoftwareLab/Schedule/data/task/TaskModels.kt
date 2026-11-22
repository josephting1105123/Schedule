package com.EntropicSoftwareLab.Schedule.data.task

import java.time.LocalDate
import java.time.LocalTime

/**
 * Which Today pages a task appears on before its deadline. [wire] is the value of the `show=` key in
 * the Tasks calendar's tag line, shared with Jarvis: change it in both apps or neither.
 */
enum class TaskShow(val wire: String) {
    /** Only on the due date. */
    SameDay("same"),
    /** On the due date and the seven days before it. */
    WeekBefore("week"),
    /** On every day from today until it is ticked off. */
    UntilDone("until");

    companion object {
        /** Missing or unknown reads as [SameDay], so a tag from an older writer keeps its meaning. */
        fun fromWire(value: String?): TaskShow = entries.firstOrNull { it.wire == value } ?: SameDay
    }
}

/**
 * A task: one deadline and a checkbox. [id] is app-minted, not a provider id. A task is mirrored to
 * a calendar named "Tasks" so it can sync with Jarvis, but that calendar is excluded from every event
 * read (`CalendarFilter`), so a task still never reaches
 * [com.EntropicSoftwareLab.Schedule.data.calendar.FreeBusy], the hour grid or the clashes.
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
    /** The id shared with the Tasks calendar's event; null until this task has first been synced. */
    val syncId: String? = null,
    /** Epoch ms of the last change to title, date, time or notes; used only to pick the newer side in a sync. */
    val editedAt: Long = 0L,
    /** Epoch ms of the last change to [done]; used only to pick the newer side in a sync. */
    val tickedAt: Long = 0L,
    /** Which Today pages show it; covered by [editedAt] like the title. */
    val show: TaskShow = TaskShow.SameDay,
    /** Covered by [editedAt] like the title. Ticking a repeating task moves [due] to [TaskRepeat.nextDue] instead of finishing it. */
    val repeat: TaskRepeat? = null,
) {
    /** Whether the Today page for [date] lists this task; see [shownOn]. */
    fun isShownOn(date: LocalDate, today: LocalDate): Boolean = when {
        date.isAfter(due) -> false
        else -> when (show) {
            TaskShow.SameDay -> date == due
            TaskShow.WeekBefore -> !date.isBefore(due.minusDays(7))
            TaskShow.UntilDone ->
                if (done) date == due else !date.isBefore(minOf(today, due))
        }
    }

    companion object {

        /**
         * The tasks shown on the Today page for [date], in display order. A task always shows on its due
         * date; [TaskShow.WeekBefore] also on the seven days before; [TaskShow.UntilDone], while unfinished,
         * on every page from [today] up to the due date (a finished one only on its due date). Past the due
         * date nothing here shows it: an unfinished task is carried onto today by [overdueAsOf] instead.
         */
        fun shownOn(tasks: List<Task>, date: LocalDate, today: LocalDate): List<Task> =
            ordered(tasks.filter { it.isShownOn(date, today) })

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
