package com.EntropicSoftwareLab.Schedule.data.task

import com.EntropicSoftwareLab.Schedule.data.calendar.HourGrid

/**
 * Where a day's deadlines fall across the 24 hour cells of the Today grid.
 *
 * Deliberately not part of [HourGrid]: that file's two rules are "horizontal extent always means
 * time" and "a stacked row always means a clash", and a task must break neither. So a task never
 * produces an [HourGrid.HourSlice] and never becomes a lane — it produces a [Mark], a single
 * position inside one cell, drawn as a tick over whatever is already there. An hour carrying a
 * deadline is still a free hour to book.
 *
 * Pure, so the placement is unit-testable without a device.
 */
object TaskGrid {

    /**
     * One deadline's position on the grid.
     *
     * [fraction] is how far into [hour] the deadline falls — 0 at the top of the hour, 0.5 at half
     * past — matching how [HourGrid.HourSlice] measures a cell, so a tick lines up with the fill it
     * sits on. Never a width: a deadline occupies no time.
     */
    data class Mark(
        val hour: Int,
        val fraction: Float,
        val title: String,
        val done: Boolean,
    )

    /**
     * One list per hour, index 0 = midnight, so the grid can look a cell's deadlines up by hour.
     *
     * Tasks with no time are absent entirely — see [Task.dueTime]. Marks within an hour are ordered
     * by position, then by title, so two deadlines at the same minute always draw in the same order.
     */
    fun byHour(tasks: List<Task>): List<List<Mark>> {
        val cells = List(HourGrid.HOURS_PER_DAY) { mutableListOf<Mark>() }
        tasks.forEach { task ->
            val time = task.dueTime ?: return@forEach
            if (time.hour !in 0 until HourGrid.HOURS_PER_DAY) return@forEach
            cells[time.hour] += Mark(
                hour = time.hour,
                // Minute resolution: the picker offers nothing finer, and a cell is ~25dp wide.
                fraction = time.minute / 60f,
                title = task.title,
                done = task.done,
            )
        }
        return cells.map { marks -> marks.sortedWith(compareBy({ it.fraction }, { it.title })) }
    }
}
