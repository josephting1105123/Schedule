package com.EntropicSoftwareLab.Schedule.data.calendar

/**
 * What Today's top card says about right now: the timed events already under way, and the next
 * one to start.
 *
 * It used to know only "next starting after now", so at 19:59 inside a 19:30–22:00 event it found
 * nothing ahead and told the user "Nothing left today — the rest of the day is yours". An event in
 * progress is the most relevant thing on the day, not something to skip. The empty-day line is now
 * reserved for when [current] and [next] are both empty.
 *
 * Instants, not local clock times, so a DST change can't make an event look started or finished
 * an hour early. Pure so it can be tested off-device.
 */
data class DayStatus(
    /** Under way at `now` (started at or before it, ending after it), earliest start first. */
    val current: List<EventInstance>,
    /** The first event starting strictly after `now`. */
    val next: EventInstance?,
) {
    val isClear: Boolean get() = current.isEmpty() && next == null

    companion object {
        /** [timed] should be the day's timed events; all-day ones are not "happening now". */
        fun of(timed: List<EventInstance>, nowMillis: Long): DayStatus {
            val sorted = timed.sortedWith(compareBy({ it.startMillis }, { it.endMillis }))
            return DayStatus(
                current = sorted.filter { it.startMillis <= nowMillis && it.endMillis > nowMillis },
                next = sorted.firstOrNull { it.startMillis > nowMillis },
            )
        }
    }
}
