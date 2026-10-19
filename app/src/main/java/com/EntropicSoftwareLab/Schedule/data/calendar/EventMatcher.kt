package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Finds the events a cancellation phrase could be talking about.
 *
 * This is the cancellation half of the rule that keeps the assistant honest. The model turns "cancel
 * my dentist thing tomorrow" into a title and a date; everything after that is arithmetic and string
 * matching over rows read from the provider. The model never sees an event id, so it cannot name one
 * that doesn't exist, and it cannot pick.
 *
 * The output is a *shortlist*, deliberately. Returning one confident answer would make a wrong match
 * indistinguishable from a right one at the moment it matters — after the event is gone. A short list
 * the user taps through is slower by one tap and wrong in ways they can see.
 */
object EventMatcher {

    /** Nobody scans more than a handful; past this the list is noise rather than choice. */
    const val MAX_MATCHES = 5

    /**
     * The ceiling when the user asked for *all* of something. Still a ceiling: a list past this is
     * no longer something anyone reads before tapping, and an unread list defeats the confirmation.
     */
    const val MAX_BULK_MATCHES = 25

    /** How far ahead to look when the user named no date. Cancellations are about the near future. */
    private const val DEFAULT_HORIZON_DAYS = 30L

    /**
     * How far a *named* thing is searched when no date came with it.
     *
     * "Remove any MLBB events" is a clean-up, not a peek at the next month: the things it names are
     * scattered across the year, and the old 30-day horizon answered it with whichever handful
     * happened to be near — which reads, correctly, as the assistant not knowing where they are.
     * Backwards too, because clearing out something that has finished is a normal thing to want.
     */
    private const val SEARCH_BACK_DAYS = 60L
    private const val SEARCH_FORWARD_DAYS = 365L

    /**
     * Words that turn "cancel my standup" into "cancel every standup".
     *
     * A rule over the user's own words rather than a field the model fills in, for the same reason
     * [com.EntropicSoftwareLab.Schedule.ai.IntentRouter] is: it is a reliable signal sitting in the
     * text, it costs no tokens, and it stays right when no model is installed.
     */
    private val BULK_WORDS = setOf("all", "any", "every", "each", "everything", "anything")

    fun isBulkRequest(text: String): Boolean {
        val words = text.lowercase(Locale.US).split(Regex("[^\\p{L}\\p{N}]+"))
        return words.any { it in BULK_WORDS }
    }

    /**
     * Quantifiers, dropped from a title before anything is matched against it.
     *
     * Kept apart from [STOP_WORDS] because the two mean opposite things when they are *all* a title
     * consists of. "Cancel my appointment" named one thing badly and is refused — answering it with
     * everything on the day would delete the wrong event. "Cancel all of tomorrow" named no thing on
     * purpose, and everything on the day is exactly the answer. Both tokenise to nothing, so the
     * difference has to be drawn before tokenising.
     */
    private val QUANTIFIERS = setOf("any", "all", "every", "each", "everything", "anything", "some")

    private fun stripQuantifiers(title: String?): String {
        if (title.isNullOrBlank()) return ""
        return title.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in QUANTIFIERS }
            .joinToString(" ")
    }

    /** A title has to overlap this much of the query to count as a match at all. */
    private const val MIN_SCORE = 0.34

    /**
     * The window to read from the calendar for [query].
     *
     * Separate from [match] because reading rows is I/O and matching is not: the caller fetches once,
     * this decides what to fetch. A named date is that day alone; no date means from today forward,
     * because "cancel my dentist appointment" is never about one that already happened.
     */
    fun searchWindow(query: CancellationQueryLike, today: LocalDate): Pair<LocalDate, LocalDate> {
        query.date?.let { return it to it.plusDays(1) }
        // A named thing with no date is a search of the calendar, not a look at the next month.
        if (!query.titleQuery.isNullOrBlank()) {
            return today.minusDays(SEARCH_BACK_DAYS) to today.plusDays(SEARCH_FORWARD_DAYS)
        }
        return today to today.plusDays(DEFAULT_HORIZON_DAYS)
    }

    /**
     * The events from [candidates] that [query] plausibly names, best first.
     *
     * An empty result is a real answer — it means nothing matched, which the caller reports rather
     * than papering over. A query naming nothing at all returns empty too: "cancel" on its own must
     * not resolve to "everything in the next month".
     */
    fun match(
        query: CancellationQueryLike,
        candidates: List<EventInstance>,
        zone: ZoneId,
        /**
         * When given, equally-good matches are ordered by what is still to come first and then by
         * nearness. Searching a year back and forward otherwise puts a match from two months ago
         * above tomorrow's, which is almost never the one meant. Null keeps the plain
         * earliest-first order.
         */
        now: LocalDateTime? = null,
        limit: Int = MAX_MATCHES,
    ): List<EventInstance> {
        if (query.isEmptyQuery) return emptyList()

        // "any mlbb related" names MLBB; "all" names nothing and means everything.
        val named = stripQuantifiers(query.titleQuery)
        val titleTokens = tokenize(named)

        // A title that survives tokenising as nothing — "the appointment", "that meeting" — is not
        // the same as no title at all. Falling through to the no-title branch would answer a request
        // to cancel one thing with everything on the day.
        if (named.isNotBlank() && titleTokens.isEmpty()) return emptyList()

        // Nothing named and nothing but a quantifier to go on: "cancel everything" must not resolve
        // to a year of someone's calendar.
        if (named.isBlank() && query.date == null && query.startTime == null) return emptyList()

        val scored = candidates.mapNotNull { event ->
            if (!matchesTime(query, event, zone)) return@mapNotNull null
            val score = if (titleTokens.isEmpty()) {
                // No title given, so the time window is the whole query and everything in it counts
                // equally. Ordering then falls to the start-time tiebreak below.
                1.0
            } else {
                titleScore(titleTokens, event.title).takeIf { it >= MIN_SCORE } ?: return@mapNotNull null
            }
            event to score
        }

        val reference = now?.atZone(zone)?.toInstant()?.toEpochMilli()
        return scored
            .sortedWith(
                compareByDescending<Pair<EventInstance, Double>> { it.second }
                    .thenBy { if (reference == null || it.first.startMillis >= reference) 0 else 1 }
                    .thenBy {
                        if (reference == null) it.first.startMillis
                        else kotlin.math.abs(it.first.startMillis - reference)
                    }
            )
            .take(limit.coerceAtLeast(1))
            .map { it.first }
    }

    /**
     * Whether [event] falls inside whatever time the query named.
     *
     * A named time is treated as a point the event has to be running at, not as a range the event
     * has to sit inside — "cancel my 3pm" should find a meeting running 14:30–16:00.
     */
    private fun matchesTime(
        query: CancellationQueryLike,
        event: EventInstance,
        zone: ZoneId,
    ): Boolean {
        val date = query.date ?: return true
        val start = event.localStart(zone)
        val end = event.localEnd(zone)

        // An all-day event is on the day it covers; comparing its clock times would be meaningless.
        if (event.allDay) return !date.isBefore(start.toLocalDate()) && date.isBefore(end.toLocalDate())
        if (start.toLocalDate() != date && end.toLocalDate() != date) return false

        val from = query.startTime ?: return true
        val windowStart = LocalDateTime.of(date, from)
        val windowEnd = query.endTime?.let { LocalDateTime.of(date, it) }
            ?.takeIf { it.isAfter(windowStart) }
            ?: windowStart.plusMinutes(1)
        // Half-open overlap, matching FreeBusy: an event ending at 15:00 doesn't occupy 15:00.
        return start.isBefore(windowEnd) && end.isAfter(windowStart)
    }

    /**
     * How well [title] answers the query tokens, as the fraction of them it accounts for.
     *
     * Fraction of the *query*, not of the title, so "dentist" scores a full 1.0 against "Dentist
     * appointment with Dr Yeo" — the user named part of a longer name, which is how people refer to
     * their own events. A query token counts when it prefixes a word in the title, so "meet" finds
     * "meeting" without stemming.
     */
    private fun titleScore(queryTokens: List<String>, title: String): Double {
        val titleTokens = tokenize(title)
        if (titleTokens.isEmpty()) return 0.0
        val hits = queryTokens.count { q -> titleTokens.any { it.startsWith(q) || q.startsWith(it) } }
        return hits.toDouble() / queryTokens.size
    }

    /** Lowercased words, with the filler people wrap event names in dropped. */
    private fun tokenize(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
    }

    /**
     * Words that carry no identifying weight in an event name. "Appointment" and "meeting" are here
     * because they appear in half of all calendars: matching on them alone would surface every
     * meeting in the month for a query that named one.
     */
    private val STOP_WORDS = setOf(
        "the", "my", "our", "at", "on", "in", "to", "for", "with", "and", "of", "an",
        "cancel", "delete", "remove", "drop", "call", "off", "scrap", "clear", "please",
        "appointment", "meeting", "event", "events", "thing", "things",
        // The filler a clean-up request is wrapped in. Leaving these in actively broke the case they
        // appear in: "any mlbb related" is three tokens of which one can ever match a title, scoring
        // 0.33 against a floor of 0.34 — so the request that most obviously means "find these"
        // matched nothing at all. The quantifiers themselves are handled by [QUANTIFIERS], earlier
        // and for a different reason.
        "related", "stuff", "session", "sessions",
    )
}

/**
 * What [EventMatcher] needs from a cancellation query.
 *
 * An interface rather than a direct dependency on the `ai` package's `CancellationQuery`, so the
 * matching logic stays in `data/calendar` beside the rows it works on and can be unit-tested without
 * dragging the assistant in.
 */
interface CancellationQueryLike {
    val titleQuery: String?
    val date: LocalDate?
    val startTime: LocalTime?
    val endTime: LocalTime?

    /** True when nothing was named to search on. */
    val isEmptyQuery: Boolean
        get() = titleQuery.isNullOrBlank() && date == null && startTime == null
}
