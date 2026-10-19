package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.LocalDate
import java.time.ZoneId

/**
 * Buckets a day's timed events into 24 one-hour cells and decides what colour each event gets.
 *
 * [FreeBusy] can't do this job: it merges overlapping events into anonymous spans, which is right
 * for a free/busy answer and wrong for a grid where every event needs to stay individually visible
 * and tappable. Pure functions over plain data, so the hour maths is unit-testable without a device.
 */
object HourGrid {

    const val HOURS_PER_DAY = 24

    /**
     * The part of one hour cell an event actually covers, as fractions of that hour.
     *
     * [startFraction] 0 and [endFraction] 1 is a full hour; 0 to 0.5 is an event ending at half
     * past. Fractions rather than minutes because the cell is drawn by proportion, and because a
     * DST-shortened hour is still one cell wide.
     */
    data class HourSlice(
        val event: EventInstance,
        val startFraction: Float,
        val endFraction: Float,
    )

    /**
     * A day split into lanes, each lane a 24-cell row holding events that never overlap each other.
     *
     * Overlapping events land in different lanes, so the grid grows a row instead of subdividing an
     * hour between two events — an hour cell is subdivided only to show how much of that hour one
     * event covers. Lane count is the day's peak concurrency: one lane for a normal day, two when
     * two things clash.
     *
     * Greedy first-fit over events ordered by start time, which is optimal for interval
     * partitioning — it never uses more lanes than the peak overlap requires.
     */
    fun lanes(
        events: List<EventInstance>,
        date: LocalDate,
        zone: ZoneId,
    ): List<List<List<HourSlice>>> {
        val bounds = hourBoundaries(date, zone)
        val dayStart = bounds.first()
        val dayEnd = bounds.last()

        val clipped = events
            .asSequence()
            .filterNot { it.allDay }
            .mapNotNull { event ->
                val start = maxOf(event.startMillis, dayStart)
                val end = minOf(event.endMillis, dayEnd)
                if (end <= start) null else Triple(event, start, end)
            }
            .sortedWith(compareBy({ it.second }, { it.first.instanceId }))
            .toList()

        // laneEnds[i] is where lane i's last event finishes; an event joins the first lane it
        // doesn't collide with. Back-to-back events (one ends exactly as the next starts) share a
        // lane — they don't overlap, and forcing them apart would imply a clash that isn't there.
        val laneEnds = mutableListOf<Long>()
        val laneEvents = mutableListOf<MutableList<Triple<EventInstance, Long, Long>>>()
        clipped.forEach { entry ->
            val (_, start, end) = entry
            val lane = laneEnds.indexOfFirst { it <= start }
            if (lane >= 0) {
                laneEnds[lane] = end
                laneEvents[lane] += entry
            } else {
                laneEnds += end
                laneEvents += mutableListOf(entry)
            }
        }

        return laneEvents.map { entries ->
            List(HOURS_PER_DAY) { hour ->
                val cellStart = bounds[hour]
                val cellEnd = bounds[hour + 1]
                val span = (cellEnd - cellStart).toFloat()
                // A DST-skipped hour is zero-width; nothing can be drawn in it.
                if (span <= 0f) return@List emptyList()
                entries.mapNotNull { (event, start, end) ->
                    if (start >= cellEnd || end <= cellStart) return@mapNotNull null
                    val from = ((maxOf(start, cellStart) - cellStart) / span).coerceIn(0f, 1f)
                    val to = ((minOf(end, cellEnd) - cellStart) / span).coerceIn(0f, 1f)
                    if (to <= from) null else HourSlice(event, from, to)
                }
            }
        }
    }

    /** How an event's colour is decided: the user's own choice, or a slot in the app's palette. */
    /**
     * One event's unbroken stretch across a row of hour cells: [start] and [end] are in hours from
     * the row's first hour, fractional.
     */
    data class Run(val event: EventInstance, val start: Float, val end: Float)

    /**
     * [lane]'s slices over [hours], joined wherever one event carries on from one hour into the next.
     *
     * The Today grid used to draw each hour's slice in its own cell, so a two-hour lecture was two
     * separate blocks with a gap between them and read as two events. A run is drawn as one bar
     * across the cells it covers, gaps included. Only a slice that ends at the top of the hour and
     * one of the same occurrence starting at the bottom of the next are joined — the same event
     * twice with time between is two runs.
     */
    fun runs(lane: List<List<HourSlice>>, hours: IntRange): List<Run> {
        val out = mutableListOf<Run>()
        hours.forEachIndexed { offset, hour ->
            lane.getOrElse(hour) { emptyList() }.forEach { slice ->
                val start = offset + slice.startFraction
                val end = offset + slice.endFraction
                val last = out.lastOrNull()
                if (last != null &&
                    last.event.instanceId == slice.event.instanceId &&
                    start - last.end < CONTIGUOUS_EPSILON
                ) {
                    out[out.lastIndex] = last.copy(end = end)
                } else {
                    out += Run(slice.event, start, end)
                }
            }
        }
        return out
    }

    private const val CONTIGUOUS_EPSILON = 1e-4f

    sealed interface EventColorKey {
        /** The user recoloured this event; [argb] is opaque and ready to draw. */
        data class Custom(val argb: Int) : EventColorKey
        /** Position in the palette. Unbounded — callers wrap with `% palette.size`. */
        data class Palette(val index: Int) : EventColorKey
    }

    /**
     * One list per hour of [date] in [zone], index 0 = midnight.
     *
     * An event occupies hour h when it overlaps `[h:00, h+1:00)` — half-open, so a meeting ending
     * at 10:00 lights 9 and not 10, matching how [FreeBusy.conflictsIn] treats back-to-back events.
     * Events running past either end of the day are clipped to it, so a 23:00–01:00 event lights
     * only hour 23 today and only hour 0 tomorrow.
     *
     * All-day events are excluded: the provider stores them at UTC midnight, so putting them on an
     * hour axis would place them at an arbitrary offset. They belong in their own section.
     */
    fun bucketByHour(
        events: List<EventInstance>,
        date: LocalDate,
        zone: ZoneId,
    ): List<List<EventInstance>> {
        val bounds = hourBoundaries(date, zone)
        val dayStart = bounds.first()
        val dayEnd = bounds.last()
        val cells = List(HOURS_PER_DAY) { mutableListOf<EventInstance>() }

        events.forEach { event ->
            if (event.allDay) return@forEach
            if (event.endMillis <= event.startMillis) return@forEach
            val start = maxOf(event.startMillis, dayStart)
            val end = minOf(event.endMillis, dayEnd)
            if (end <= start) return@forEach

            for (hour in 0 until HOURS_PER_DAY) {
                // Half-open, so an event ending exactly at 10:00 fills hour 9 and not 10. The cell
                // for an hour DST skips is zero-width, and an event spanning the transition still
                // fills it — a meeting running across the gap should read as one unbroken block
                // rather than one with a hole punched in the middle.
                if (start < bounds[hour + 1] && end > bounds[hour]) cells[hour] += event
            }
        }
        return cells
    }

    /**
     * The 25 instants bounding each hour cell, derived from local clock times rather than a fixed
     * 3,600,000 ms step. On a DST day the step approach drifts every cell after the transition into
     * the wrong column; resolving `date.atTime(h, 0)` through the zone keeps cell h labelled h:00
     * whatever the offset does — the skipped hour collapses to zero width and the repeated one
     * covers two real hours.
     */
    private fun hourBoundaries(date: LocalDate, zone: ZoneId): LongArray {
        val bounds = LongArray(HOURS_PER_DAY + 1)
        for (hour in 0 until HOURS_PER_DAY) {
            bounds[hour] = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        }
        bounds[HOURS_PER_DAY] = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return bounds
    }

    /**
     * Colour key per instance id, so every event in a day is told apart at a glance.
     *
     * Keyed by instance rather than event id because two occurrences of one recurring series can
     * land on the same day, and each needs its own cell fill.
     *
     * An event the user recoloured keeps that colour and does not consume a palette slot — its
     * identity is already meaningful, and skipping it keeps the remaining events on the low,
     * most-distinct end of the palette. Custom is detected the way the provider defines
     * DISPLAY_COLOR: it returns EVENT_COLOR when set, otherwise CALENDAR_COLOR, so a display colour
     * differing from the event's calendar colour can only have come from the user.
     *
     * Deterministic: events are ordered by start time (ties broken by instance id) before indices
     * are handed out, so the same day always produces the same colours regardless of query order.
     */
    fun assignColors(
        events: List<EventInstance>,
        calendarColors: Map<Long, Int>,
    ): Map<Long, EventColorKey> {
        val ordered = events
            .filterNot { it.allDay }
            .sortedWith(compareBy({ it.startMillis }, { it.instanceId }))
        val out = LinkedHashMap<Long, EventColorKey>(ordered.size)
        var nextPaletteIndex = 0
        ordered.forEach { event ->
            if (out.containsKey(event.instanceId)) return@forEach
            val custom = customColorOf(event, calendarColors[event.calendarId])
            out[event.instanceId] = if (custom != null) {
                EventColorKey.Custom(custom)
            } else {
                EventColorKey.Palette(nextPaletteIndex++)
            }
        }
        return out
    }

    /**
     * The event's own colour, or null when it is just inheriting its calendar's.
     *
     * A calendar colour we don't know (calendar not loaded yet) or an unset one counts as "no
     * evidence of a custom colour" — better to give the event a distinct palette slot than to
     * render every event in one calendar's identical hue.
     */
    private fun customColorOf(event: EventInstance, calendarColor: Int?): Int? {
        val display = normalizeColor(event.displayColor) ?: return null
        val calendar = calendarColor?.let(::normalizeColor) ?: return null
        return display.takeIf { it != calendar }
    }

    /** Mirrors `asCalendarColor`: 0 means the provider has no colour, everything else is opaque. */
    private fun normalizeColor(argb: Int): Int? =
        if (argb == 0) null else argb or 0xFF000000.toInt()
}
