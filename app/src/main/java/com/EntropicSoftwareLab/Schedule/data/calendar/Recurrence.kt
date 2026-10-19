package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How often a repeat fires. The four frequencies a calendar UI actually offers. */
enum class RecurrenceFreq { Daily, Weekly, Monthly, Yearly }

/** When a repeat stops. RFC 2445 allows COUNT or UNTIL, never both. */
sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd

    /** Last day the series may fire, inclusive. */
    data class OnDate(val date: LocalDate) : RecurrenceEnd

    /** Total number of occurrences, including the first. */
    data class AfterCount(val count: Int) : RecurrenceEnd
}

/**
 * A repeat this app can build and read back — frequency, interval, and an end.
 *
 * Deliberately narrower than RRULE. The grammar allows BYSETPOS, multiple BYDAY codes, BYMONTH and
 * more; modelling all of it is a screen of its own. What matters is that the gap is handled by
 * [RepeatSelection.Unsupported] rather than by quietly rewriting a rule into the nearest thing this
 * type can express — see [parseRepeat].
 */
data class RecurrenceRule(
    val freq: RecurrenceFreq,
    val interval: Int = 1,
    val end: RecurrenceEnd = RecurrenceEnd.Never,
)

/**
 * What the editor's Repeats row is currently set to.
 *
 * [Unsupported] is the load-bearing case. An event created in Google Calendar can carry a rule this
 * app can't model ("the last Friday of every month"). Reading that into the nearest [RecurrenceRule]
 * and writing it back would silently change the user's series on an unrelated save — editing a
 * title would move every future occurrence. Keeping the original string verbatim means the repeat
 * survives a save untouched, and the UI can say so instead of lying about it.
 */
sealed interface RepeatSelection {
    data object None : RepeatSelection

    data class Simple(val rule: RecurrenceRule) : RepeatSelection

    /** A rule this app can't model, kept exactly as the provider stored it. */
    data class Unsupported(val rrule: String) : RepeatSelection
}

/** The quick-pick list, all at interval 1 with no end. */
val REPEAT_PRESETS: List<RecurrenceRule> =
    RecurrenceFreq.entries.map { RecurrenceRule(freq = it) }

/**
 * The rule body for Events.RRULE — no "RRULE:" prefix, no trailing newline.
 *
 * [start] and [allDay] are needed for more than decoration: BYDAY/BYMONTHDAY anchor to the start,
 * and UNTIL has to be written as a DATE for an all-day event and as a UTC DATE-TIME for a timed one.
 * Getting that pair wrong is the classic "the series ends a day early in one timezone" bug.
 */
fun RecurrenceRule.toRRule(start: LocalDateTime, zone: ZoneId, allDay: Boolean): String =
    buildString {
        append("FREQ=").append(freq.rfcName)
        // INTERVAL=1 is the spec default. Omitting it keeps rules byte-identical to what earlier
        // versions of this app wrote, so an untouched event doesn't churn on a save.
        if (interval > 1) append(";INTERVAL=").append(interval)
        when (freq) {
            RecurrenceFreq.Weekly -> append(";BYDAY=").append(byDayCode(start.dayOfWeek))
            // Days 29-31 skip the months too short to hold them. That's RRULE semantics, not a bug
            // to work around here; "last day of month" would need BYMONTHDAY=-1 as its own option.
            RecurrenceFreq.Monthly -> append(";BYMONTHDAY=").append(start.dayOfMonth)
            RecurrenceFreq.Daily, RecurrenceFreq.Yearly -> Unit
        }
        when (val e = end) {
            RecurrenceEnd.Never -> Unit
            is RecurrenceEnd.AfterCount -> append(";COUNT=").append(e.count.coerceAtLeast(1))
            is RecurrenceEnd.OnDate -> append(";UNTIL=").append(untilValue(e.date, zone, allDay))
        }
    }

/**
 * UNTIL is inclusive, so an all-day series ending "on the 30th" writes the 30th as a plain DATE.
 *
 * A timed series can't do that: when DTSTART is a DATE-TIME, RFC 2445 requires UNTIL to be UTC. The
 * last second of the local day is converted through [zone], which is what keeps "until the 30th"
 * from cutting the 30th off for anyone east of UTC.
 */
private fun untilValue(date: LocalDate, zone: ZoneId, allDay: Boolean): String =
    if (allDay) {
        date.format(UNTIL_DATE)
    } else {
        date.atTime(23, 59, 59).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(UNTIL_UTC)
    }

/**
 * Reads a stored RRULE back into something the editor can show.
 *
 * Anything outside [RecurrenceRule]'s vocabulary comes back as [RepeatSelection.Unsupported] rather
 * than as an approximation — see that type for why the difference matters.
 */
fun parseRepeat(rrule: String?, zone: ZoneId): RepeatSelection {
    val body = rrule?.trim()?.removePrefix("RRULE:")?.trim().orEmpty()
    if (body.isEmpty()) return RepeatSelection.None

    val parts = body.split(';')
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).uppercase(Locale.US) to part.substring(i + 1)
        }
        .toMap()

    val freq = when (parts["FREQ"]?.uppercase(Locale.US)) {
        "DAILY" -> RecurrenceFreq.Daily
        "WEEKLY" -> RecurrenceFreq.Weekly
        "MONTHLY" -> RecurrenceFreq.Monthly
        "YEARLY" -> RecurrenceFreq.Yearly
        else -> return RepeatSelection.Unsupported(body)
    }

    // Every key this app can't round-trip makes the rule unsupported. Listing what IS understood
    // and rejecting the rest is the safe direction: a key added to the spec later can't slip
    // through and get dropped on save.
    val understood = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "WKST", "BYDAY", "BYMONTHDAY")
    if (parts.keys.any { it !in understood }) return RepeatSelection.Unsupported(body)

    // A single BYDAY code is the anchor this app writes for a weekly rule; a list of them
    // ("MO,WE,FR") is a pattern it can't rebuild.
    parts["BYDAY"]?.let { if (it.contains(',') || freq != RecurrenceFreq.Weekly) return RepeatSelection.Unsupported(body) }
    parts["BYMONTHDAY"]?.let {
        if (it.contains(',') || it.startsWith("-") || freq != RecurrenceFreq.Monthly) {
            return RepeatSelection.Unsupported(body)
        }
    }

    val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it >= 1 } ?: 1
    val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it >= 1 }
    val until = parts["UNTIL"]?.let { parseUntil(it, zone) }
    // COUNT and UNTIL together is malformed; rather than pick one, hand it back untouched.
    if (count != null && until != null) return RepeatSelection.Unsupported(body)

    val end = when {
        count != null -> RecurrenceEnd.AfterCount(count)
        until != null -> RecurrenceEnd.OnDate(until)
        // An UNTIL that was present but unreadable must not silently become "repeats forever".
        parts["UNTIL"] != null -> return RepeatSelection.Unsupported(body)
        else -> RecurrenceEnd.Never
    }

    return RepeatSelection.Simple(RecurrenceRule(freq, interval, end))
}

/** Accepts both UNTIL forms: a bare DATE, and a UTC DATE-TIME. */
private fun parseUntil(value: String, zone: ZoneId): LocalDate? {
    val text = value.trim()
    return runCatching {
        when {
            text.endsWith("Z") ->
                LocalDateTime.parse(text, UNTIL_UTC).atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(zone).toLocalDate()
            text.length == 8 -> LocalDate.parse(text, UNTIL_DATE)
            // A local DATE-TIME with no Z is out of spec for a timed series but does turn up.
            else -> LocalDateTime.parse(text, UNTIL_LOCAL).toLocalDate()
        }
    }.getOrNull()
}

/**
 * The same series, cut off immediately before [instanceStart]. Backs "this and following".
 *
 * Any existing COUNT or UNTIL is dropped first: a rule may carry only one end, and the new cut is
 * always the earlier of the two anyway. Operating on the raw string rather than on a parsed
 * [RecurrenceRule] is deliberate — it means "this and following" works on a rule this app can't
 * otherwise model, which is exactly the series a user is most likely to want to trim rather than
 * rebuild.
 *
 * Returns null when the cut would land at or before the series start, i.e. when there would be no
 * occurrence left. The caller deletes the event instead; an UNTIL earlier than DTSTART leaves a ghost
 * row that renders nowhere but still exists.
 */
fun capRRuleBefore(
    rrule: String,
    instanceStart: LocalDateTime,
    seriesStart: LocalDateTime,
    zone: ZoneId,
    allDay: Boolean,
): String? {
    if (!instanceStart.isAfter(seriesStart)) return null

    val kept = rrule.trim().removePrefix("RRULE:").trim()
        .split(';')
        .filter { part ->
            val key = part.substringBefore('=').uppercase(Locale.US)
            key != "COUNT" && key != "UNTIL" && part.isNotBlank()
        }

    val until = if (allDay) {
        // All-day UNTIL is a DATE and inclusive, so the last kept day is the one before this one.
        instanceStart.toLocalDate().minusDays(1).format(UNTIL_DATE)
    } else {
        instanceStart.atZone(zone).minusSeconds(1)
            .withZoneSameInstant(ZoneOffset.UTC).format(UNTIL_UTC)
    }
    return (kept + "UNTIL=$until").joinToString(";")
}

/** Human-readable text for the picker row, e.g. "Every 2 weeks on Monday, until 30 Aug 2026". */
fun RepeatSelection.label(start: LocalDate): String = when (this) {
    RepeatSelection.None -> "Does not repeat"
    is RepeatSelection.Unsupported -> "Custom repeat (kept as is)"
    is RepeatSelection.Simple -> rule.label(start)
}

fun RecurrenceRule.label(start: LocalDate): String {
    val every = when (freq) {
        RecurrenceFreq.Daily -> if (interval == 1) "Daily" else "Every $interval days"
        RecurrenceFreq.Weekly ->
            if (interval == 1) "Weekly on ${start.dayOfWeek.displayName()}"
            else "Every $interval weeks on ${start.dayOfWeek.displayName()}"
        RecurrenceFreq.Monthly ->
            if (interval == 1) "Monthly on day ${start.dayOfMonth}"
            else "Every $interval months on day ${start.dayOfMonth}"
        RecurrenceFreq.Yearly -> if (interval == 1) "Yearly" else "Every $interval years"
    }
    return when (val e = end) {
        RecurrenceEnd.Never -> every
        is RecurrenceEnd.OnDate -> "$every, until ${e.date.format(END_DATE)}"
        is RecurrenceEnd.AfterCount -> "$every, ${e.count} times"
    }
}

private val RecurrenceFreq.rfcName: String
    get() = when (this) {
        RecurrenceFreq.Daily -> "DAILY"
        RecurrenceFreq.Weekly -> "WEEKLY"
        RecurrenceFreq.Monthly -> "MONTHLY"
        RecurrenceFreq.Yearly -> "YEARLY"
    }

private fun byDayCode(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "MO"
    DayOfWeek.TUESDAY -> "TU"
    DayOfWeek.WEDNESDAY -> "WE"
    DayOfWeek.THURSDAY -> "TH"
    DayOfWeek.FRIDAY -> "FR"
    DayOfWeek.SATURDAY -> "SA"
    DayOfWeek.SUNDAY -> "SU"
}

private fun DayOfWeek.displayName(): String =
    name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }

private val UNTIL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
private val UNTIL_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
private val UNTIL_LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)
private val END_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/**
 * The RFC 2445 duration string a recurring event needs in Events.DURATION.
 *
 * A recurring row carries RRULE + DURATION and *no* DTEND — the provider rejects rows holding both,
 * because each occurrence's end is derived from this length rather than a fixed instant.
 *
 * All-day events are measured in whole days (minimum 1, matching the exclusive-DTEND rule the
 * one-off path uses); timed events in seconds, floored at zero for degenerate drafts.
 */
fun rfc2445Duration(start: LocalDateTime, end: LocalDateTime, allDay: Boolean): String =
    if (allDay) {
        val days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()).coerceAtLeast(1L)
        "P${days}D"
    } else {
        val seconds = ChronoUnit.SECONDS.between(start, end).coerceAtLeast(0L)
        "PT${seconds}S"
    }
