package com.EntropicSoftwareLab.Schedule.data.appearance

import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.GoogleCalendarColors

/**
 * The one place that decides what colour an event is drawn in.
 *
 * Every surface that shows an event — the Today hour grid, the agenda rows, the month chips, the
 * month widget — used to read `EventInstance.displayColor` straight through `asCalendarColor`. They
 * all go through here instead, so "the app's colour wins over the provider's" is a rule with one
 * implementation rather than four copies that can drift.
 *
 * Pure and free of Compose on purpose: the widget resolves colours inside `provideGlance`, where
 * there is no composition to read a theme from.
 */
object EventColorResolver {

    /**
     * The event's colour, or null when nothing has an opinion and the caller should use its own
     * fallback.
     *
     * `displayColor` is the provider's already-resolved value — the event's own colour if it has
     * one, otherwise the calendar's — and it comes back as 0 for a row that carries neither, which
     * is why 0 is treated as absent rather than as opaque black.
     */
    fun argbFor(eventId: Long, displayColor: Int, overrides: Map<Long, Int>): Int? =
        (overrides[eventId] ?: displayColor.takeIf { it != 0 })
            // Drawn the way Google Calendar's app draws it — an older shade brought up to date,
            // and a classic colour in the softer tone Google's app uses — so the same event looks
            // the same in both apps. The automatic colours, which arrive through [overrides], too.
            ?.let(GoogleCalendarColors::displayShade)

    fun argbFor(event: EventInstance, overrides: Map<Long, Int>): Int? =
        argbFor(event.eventId, event.displayColor, overrides)
}
