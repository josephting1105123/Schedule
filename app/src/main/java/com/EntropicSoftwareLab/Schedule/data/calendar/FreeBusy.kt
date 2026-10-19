package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The deterministic half of "am I free?".
 *
 * Every availability answer the app gives comes from here, over real calendar rows — never from the
 * language model. The model's only job is turning a phrase into a time range; whether that range is
 * occupied is arithmetic, and arithmetic shouldn't be guessed.
 *
 * Pure functions on absolute millis, so all of it is unit-testable without a device.
 */
object FreeBusy {

    /**
     * Merges [events] into non-overlapping occupied spans, clipped to [windowStart, windowEnd).
     *
     * All-day events are excluded: they mark a date, not a block of unavailable time, and treating
     * them as busy would paint every such day solid.
     */
    fun busyBlocks(
        events: List<EventInstance>,
        windowStart: Long,
        windowEnd: Long,
    ): List<BusyBlock> {
        val spans = events
            .filterNot { it.allDay }
            .mapNotNull { event ->
                val start = maxOf(event.startMillis, windowStart)
                val end = minOf(event.endMillis, windowEnd)
                // Drop zero-length and inverted rows; some sync adapters emit them.
                if (end > start) BusyBlock(start, end) else null
            }
            .sortedBy { it.startMillis }

        if (spans.isEmpty()) return emptyList()

        val merged = mutableListOf<BusyBlock>()
        var current = spans.first()
        for (span in spans.drop(1)) {
            current = if (span.startMillis <= current.endMillis) {
                // Touching or overlapping — extend rather than emit. Back-to-back meetings read as
                // one solid block, which is what they feel like.
                BusyBlock(current.startMillis, maxOf(current.endMillis, span.endMillis))
            } else {
                merged += current
                span
            }
        }
        merged += current
        return merged
    }

    /** Busy spans for [date] in [zone], clipped to that day. */
    fun busyBlocksForDay(
        events: List<EventInstance>,
        date: LocalDate,
        zone: ZoneId,
    ): List<BusyBlock> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return busyBlocks(events, start, end)
    }

    /**
     * Events overlapping [startMillis, endMillis). Half-open on both sides, so an event ending at
     * 15:00 does not conflict with one starting at 15:00.
     */
    fun conflictsIn(
        events: List<EventInstance>,
        startMillis: Long,
        endMillis: Long,
    ): List<EventInstance> = events.filterNot { it.allDay }.filter { event ->
        event.startMillis < endMillis && event.endMillis > startMillis
    }

    /** Events overlapping a proposed [draft]. Empty means the slot is free. */
    fun conflictsWith(
        draft: NewEvent,
        events: List<EventInstance>,
        zone: ZoneId,
    ): List<EventInstance> {
        // An all-day event doesn't claim hours, so it can't clash with anything.
        if (draft.allDay) return emptyList()
        val start = draft.start.atZone(zone).toInstant().toEpochMilli()
        val end = draft.end.atZone(zone).toInstant().toEpochMilli()
        if (end <= start) return emptyList()
        return conflictsIn(events, start, end)
    }

    /**
     * The stretches of [date] booked twice over, and what's booking them.
     *
     * A sweep rather than a pairwise comparison: the boundaries of every event cut the day into
     * elementary intervals, and any interval covered by two or more events is contested. Adjacent
     * contested intervals merge into one [Clash] even when the cast changes across them, so three
     * meetings overlapping in a chain read as one problem window rather than three fragments.
     *
     * Back-to-back events never clash — the coverage test is half-open, so an event ending at 15:00
     * and one starting at 15:00 share no instant. All-day events are excluded for the same reason
     * they're excluded from [busyBlocks]: they mark a date, not a claim on the clock, and every
     * timed event would appear to clash with them.
     *
     * Events are clipped to [date] first, so only clashes that actually happen on this day are
     * reported — an overlap two events share at 23:00 yesterday isn't today's problem.
     */
    fun clashesOnDay(
        events: List<EventInstance>,
        date: LocalDate,
        zone: ZoneId,
    ): List<Clash> {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val spans = events
            .filterNot { it.allDay }
            .mapNotNull { event ->
                val start = maxOf(event.startMillis, dayStart)
                val end = minOf(event.endMillis, dayEnd)
                if (end > start) Triple(event, start, end) else null
            }
            .sortedWith(compareBy({ it.second }, { it.first.instanceId }))
        if (spans.size < 2) return emptyList()

        val boundaries = spans.flatMap { listOf(it.second, it.third) }.distinct().sorted()
        val clashes = mutableListOf<Clash>()
        for (i in 0 until boundaries.size - 1) {
            val from = boundaries[i]
            val to = boundaries[i + 1]
            val covering = spans.filter { (_, start, end) -> start <= from && end >= to }
            if (covering.size < 2) continue

            val previous = clashes.lastOrNull()
            if (previous != null && previous.endMillis == from) {
                clashes[clashes.lastIndex] = previous.copy(
                    endMillis = to,
                    events = (previous.events + covering.map { it.first })
                        .distinctBy { it.instanceId }
                        .sortedWith(compareBy({ it.startMillis }, { it.instanceId })),
                )
            } else {
                clashes += Clash(from, to, covering.map { it.first })
            }
        }
        return clashes
    }

    /**
     * Free gaps between [from] and [to] that are at least [minimumMinutes] long — for answering
     * "when am I free this afternoon?" rather than a yes/no about one slot.
     */
    fun freeGaps(
        events: List<EventInstance>,
        from: LocalDateTime,
        to: LocalDateTime,
        zone: ZoneId,
        minimumMinutes: Long = 15,
    ): List<BusyBlock> {
        val windowStart = from.atZone(zone).toInstant().toEpochMilli()
        val windowEnd = to.atZone(zone).toInstant().toEpochMilli()
        if (windowEnd <= windowStart) return emptyList()

        val busy = busyBlocks(events, windowStart, windowEnd)
        val minimumMillis = minimumMinutes * 60_000L
        val gaps = mutableListOf<BusyBlock>()
        var cursor = windowStart
        for (block in busy) {
            if (block.startMillis - cursor >= minimumMillis) {
                gaps += BusyBlock(cursor, block.startMillis)
            }
            cursor = maxOf(cursor, block.endMillis)
        }
        if (windowEnd - cursor >= minimumMillis) {
            gaps += BusyBlock(cursor, windowEnd)
        }
        return gaps
    }

    /** Fraction of [windowStart, windowEnd) that is occupied, 0f–1f. Drives the free-busy strip. */
    fun occupancyFraction(
        events: List<EventInstance>,
        windowStart: Long,
        windowEnd: Long,
    ): Float {
        val total = windowEnd - windowStart
        if (total <= 0) return 0f
        val busy = busyBlocks(events, windowStart, windowEnd).sumOf { it.endMillis - it.startMillis }
        return (busy.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}
