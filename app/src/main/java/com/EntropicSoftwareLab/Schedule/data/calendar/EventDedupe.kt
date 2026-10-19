package com.EntropicSoftwareLab.Schedule.data.calendar

/**
 * Collapses occurrences that are the same occurrence arriving more than once.
 *
 * A phone signed into several Google accounts subscribes to the same national holiday calendar from
 * each of them, so "The Prophet Muhammad's Birthday" comes back four times — four provider rows,
 * four different calendars, one actual day off. The provider is not wrong; four is what is stored.
 * Repeating it four times down the agenda is what's wrong, and it also invents clashes, because two
 * copies of the same meeting overlap perfectly.
 *
 * The key is deliberately strict: same title, same start, same end, same all-day flag. Anything that
 * differs by a minute or a word is two events and stays two events. What this does lose is a genuine
 * pair — "Lunch" at 12:00 on both a work and a personal calendar — collapsed into one row. That is
 * the same trade every calendar app makes, and the alternative is the screenshot.
 */
object EventDedupe {

    /**
     * The surviving occurrence of each duplicate set, in the order they arrived.
     *
     * The keeper is the lowest calendar id, then the lowest event id — not simply the first seen.
     * The provider does not promise an order among rows sharing a start time, and the row the user
     * taps to edit or cancel should be the same row on every read.
     */
    fun collapseIdentical(events: List<EventInstance>): List<EventInstance> {
        if (events.size < 2) return events

        val keepers = HashMap<Key, EventInstance>(events.size)
        events.forEach { event ->
            val key = Key(event.title, event.startMillis, event.endMillis, event.allDay)
            val held = keepers[key]
            if (held == null || betterKeeper(event, held)) keepers[key] = event
        }
        if (keepers.size == events.size) return events

        // Filtered rather than rebuilt from the map, so the caller's ordering survives.
        val surviving = keepers.values.mapTo(HashSet()) { it.instanceId }
        return events.filter { it.instanceId in surviving }
    }

    private fun betterKeeper(candidate: EventInstance, held: EventInstance): Boolean = when {
        candidate.calendarId != held.calendarId -> candidate.calendarId < held.calendarId
        else -> candidate.eventId < held.eventId
    }

    private data class Key(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
    )
}
