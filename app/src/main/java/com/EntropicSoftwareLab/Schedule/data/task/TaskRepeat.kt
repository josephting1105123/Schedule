package com.EntropicSoftwareLab.Schedule.data.task

import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceEnd
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceRule
import java.time.LocalDate

private const val MAX_STEPS = 100_000L
private val MAX_WIRE_DATE: LocalDate = LocalDate.of(9999, 12, 31)

/**
 * How a repeating task rolls its deadline forward when ticked.
 *
 * Wire form is shared with Jarvis via the `repeat=` tag key in the Tasks calendar:
 * `<freq>`, `<freq>/<interval>`, or `<freq>/<interval>/<until>`, where `<freq>` is one of
 * `day|week|month|year`, `<interval>` is `1..999`, and `<until>` is ISO `yyyy-MM-dd`.
 */
data class TaskRepeat(
    val freq: RecurrenceFreq,
    val interval: Int = 1,
    val until: LocalDate? = null,
) {
    /** Wire form, shared with Jarvis. Always written in shortest form and free of whitespace. */
    fun wire(): String {
        val f = freq.wire
        val n = interval.coerceIn(1, 999)
        return when {
            until != null -> "$f/$n/$until"
            n > 1 -> "$f/$n"
            else -> f
        }
    }

    companion object {
        /**
         * Null for null, blank, or anything malformed (unknown freq, interval outside 1..999,
         * unparseable date, extra parts). Never throws.
         */
        fun fromWire(value: String?): TaskRepeat? {
            if (value.isNullOrBlank()) return null
            val parts = value.split('/')
            if (parts.size !in 1..3) return null
            val freq = when (parts[0]) {
                "day" -> RecurrenceFreq.Daily
                "week" -> RecurrenceFreq.Weekly
                "month" -> RecurrenceFreq.Monthly
                "year" -> RecurrenceFreq.Yearly
                else -> return null
            }
            val interval = if (parts.size >= 2) {
                val raw = parts[1]
                if (raw.isEmpty() || !raw.all { it in '0'..'9' }) return null
                raw.toIntOrNull()?.takeIf { it in 1..999 } ?: return null
            } else {
                1
            }
            val until = if (parts.size == 3) {
                val raw = parts[2]
                if (raw.length != 10) return null
                val parsed = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return null
                if (parsed.year !in 1..9999) return null
                parsed
            } else {
                null
            }
            return TaskRepeat(freq = freq, interval = interval, until = until)
        }
    }
}

/**
 * The due date a repeating task moves to when it is ticked, or null when the series is over
 * (the tick then finishes the task as normal).
 *
 * Step `k` (`k = 1, 2, ...`) is computed from [due] directly, never from the previous step:
 * - Daily  -> `due.plusDays(k * interval)`
 * - Weekly -> `due.plusWeeks(k * interval)`
 * - Monthly -> `due.plusMonths(k * interval)` (Java clamps 31 Jan + 1 month to 28/29 Feb)
 * - Yearly -> `due.plusYears(k * interval)`
 *
 * Takes the first step strictly after [due] and not before [today]. Returns null if that step is
 * after [TaskRepeat.until], or after [MAX_STEPS] steps.
 */
fun TaskRepeat.nextDue(due: LocalDate, today: LocalDate): LocalDate? {
    if (interval < 1) return null
    val step = interval.toLong()
    for (k in 1L..MAX_STEPS) {
        val candidate = due.addSteps(freq, k * step)
        if (until != null && candidate.isAfter(until)) return null
        if (candidate.isAfter(due) && !candidate.isBefore(today)) {
            return candidate
        }
    }
    return null
}

/** For the editor's picker and label. [TaskRepeat.until] -> [RecurrenceEnd.OnDate], null -> [RecurrenceEnd.Never]. */
fun TaskRepeat.toRule(): RecurrenceRule = RecurrenceRule(
    freq = freq,
    interval = interval.coerceIn(1, 999),
    end = until?.let { RecurrenceEnd.OnDate(it) } ?: RecurrenceEnd.Never,
)

/**
 * Back from the picker. [RecurrenceEnd.Never] -> `until = null`; [RecurrenceEnd.OnDate] -> `until = date`;
 * [RecurrenceEnd.AfterCount] -> `until` is the `(count - 1)`th step from [due] (`count = 1` means `until = due`).
 * `interval` is clamped to `1..999`.
 */
fun RecurrenceRule.toTaskRepeat(due: LocalDate): TaskRepeat {
    val clamped = interval.coerceIn(1, 999)
    val until = when (val e = end) {
        RecurrenceEnd.Never -> null
        is RecurrenceEnd.OnDate -> e.date
        is RecurrenceEnd.AfterCount -> {
            val steps = (e.count.coerceAtLeast(1) - 1).toLong()
            val computed = if (steps == 0L) due else due.addSteps(freq, steps * clamped)
            minOf(computed, MAX_WIRE_DATE)
        }
    }
    return TaskRepeat(freq = freq, interval = clamped, until = until)
}

private val RecurrenceFreq.wire: String
    get() = when (this) {
        RecurrenceFreq.Daily -> "day"
        RecurrenceFreq.Weekly -> "week"
        RecurrenceFreq.Monthly -> "month"
        RecurrenceFreq.Yearly -> "year"
    }

private fun LocalDate.addSteps(freq: RecurrenceFreq, amount: Long): LocalDate = when (freq) {
    RecurrenceFreq.Daily -> plusDays(amount)
    RecurrenceFreq.Weekly -> plusWeeks(amount)
    RecurrenceFreq.Monthly -> plusMonths(amount)
    RecurrenceFreq.Yearly -> plusYears(amount)
}
