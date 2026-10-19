package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Where each timed event sits on the Week page's timetable: a column per day, the day's clock
 * running down it, and events as blocks as tall as they are long.
 *
 * Pure and zone-explicit so it can be tested off-device; the screen only turns minutes into dp.
 */
object WeekGrid {

    const val MINUTES_PER_DAY = 24 * 60

    /**
     * One event's block in a day column. [startMinute]/[endMinute] are minutes past that day's local
     * midnight, by the clock — the same clock the hour labels down the side read. Overlapping events
     * split the column: this block takes lane [lane] of [lanes].
     */
    data class Block(
        val event: EventInstance,
        val startMinute: Int,
        val endMinute: Int,
        val lane: Int,
        val lanes: Int,
    )

    /**
     * [date]'s timed events as blocks.
     *
     * Only events [MonthChips.isBand] leaves out — timed and within one day. All-day and
     * multi-day events belong in the strip above the timetable, where a span reads as a span; a
     * multi-day event drawn as a block would fill every column top to bottom.
     *
     * Overlaps are resolved per cluster (a run of events each overlapping the next): the cluster's
     * events share its column width, and events outside it keep the whole column. Splitting every
     * event on the day by the day's busiest moment would make a lone 09:00 meeting one-third wide
     * because of a clash at 15:00. Back-to-back events don't overlap, so they share a lane.
     */
    fun blocks(events: List<EventInstance>, date: LocalDate, zone: ZoneId): List<Block> {
        val timed = events
            .filterNot { MonthChips.isBand(it, zone) }
            .mapNotNull { event ->
                val start = event.localStart(zone)
                val end = event.localEnd(zone)
                if (start.toLocalDate() != date) return@mapNotNull null
                val startMinute = start.toLocalTime().toSecondOfDay() / 60
                // isBand counts an end on the next midnight as the same day; it is the bottom edge.
                val endMinute = if (end.toLocalDate() > date && end.toLocalTime() == LocalTime.MIDNIGHT) {
                    MINUTES_PER_DAY
                } else {
                    end.toLocalTime().toSecondOfDay() / 60
                }
                Triple(event, startMinute, maxOf(endMinute, startMinute))
            }
            .sortedWith(
                compareBy<Triple<EventInstance, Int, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first.instanceId },
            )

        val out = mutableListOf<Block>()
        var cluster = mutableListOf<Pair<Triple<EventInstance, Int, Int>, Int>>()
        val laneEnds = mutableListOf<Int>()
        var clusterEnd = -1

        fun closeCluster() {
            val lanes = laneEnds.size
            cluster.forEach { (entry, lane) -> out += Block(entry.first, entry.second, entry.third, lane, lanes) }
            cluster = mutableListOf()
            laneEnds.clear()
        }

        timed.forEach { entry ->
            val (_, start, end) = entry
            // A zero-length event still has to be seen and tapped, so it counts as occupying the
            // minute it starts on for the purpose of overlap.
            val effectiveEnd = maxOf(end, start + 1)
            if (cluster.isNotEmpty() && start >= clusterEnd) closeCluster()
            val lane = laneEnds.indexOfFirst { it <= start }.let { free ->
                if (free >= 0) free.also { laneEnds[it] = effectiveEnd } else {
                    laneEnds += effectiveEnd
                    laneEnds.lastIndex
                }
            }
            cluster += entry to lane
            clusterEnd = if (cluster.size == 1) effectiveEnd else maxOf(clusterEnd, effectiveEnd)
        }
        if (cluster.isNotEmpty()) closeCluster()
        return out
    }
}
