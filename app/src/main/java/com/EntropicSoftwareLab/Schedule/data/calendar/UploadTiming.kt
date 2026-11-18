package com.EntropicSoftwareLab.Schedule.data.calendar

/**
 * How long one account's last upload took, as far as this app could see it: [pendingSinceMs] is the
 * moment a dirty row was first seen, and the upload counts as finished at the first look that finds
 * none. Wall-clock milliseconds throughout.
 */
data class UploadTimingEntry(
    val pendingSinceMs: Long?,
    val lastDurationMs: Long?,
    val lastFinishedAtMs: Long?,
)

/** Which resource string a duration is shown with. */
enum class DurationUnit { Seconds, MinutesSeconds, HoursMinutes }

/** A duration split for display: [first] and [second] are the numbers the [unit]'s string takes. */
data class DurationParts(val unit: DurationUnit, val first: Int, val second: Int)

object UploadTiming {

    /**
     * Folds one look at the provider into the timings. [dirty] is the per-account count of rows
     * waiting; an account missing from it has none.
     *
     * - Rows waiting and no clock running: start it at [nowMs].
     * - No rows waiting and a clock running: the upload finished, so record how long it took.
     * - Anything else changes nothing, so a clock isn't restarted by every look while still dirty.
     *
     * The caller must not pass a map from a failed read: that would look like every upload
     * finishing at once.
     */
    fun observe(
        prev: Map<CalendarAccount, UploadTimingEntry>,
        dirty: Map<CalendarAccount, Int>,
        nowMs: Long,
    ): Map<CalendarAccount, UploadTimingEntry> {
        val out = LinkedHashMap<CalendarAccount, UploadTimingEntry>()
        for (account in prev.keys + dirty.keys) {
            val old = prev[account] ?: UploadTimingEntry(null, null, null)
            val count = dirty[account] ?: 0
            val since = old.pendingSinceMs
            val next = when {
                count > 0 && since == null -> old.copy(pendingSinceMs = nowMs)
                count == 0 && since != null -> UploadTimingEntry(
                    pendingSinceMs = null,
                    lastDurationMs = (nowMs - since).coerceAtLeast(0L),
                    lastFinishedAtMs = nowMs,
                )
                else -> old
            }
            if (next.pendingSinceMs != null || next.lastDurationMs != null || next.lastFinishedAtMs != null) {
                out[account] = next
            }
        }
        return out
    }

    /**
     * Splits [ms] into the shape the Settings card prints: under a minute as seconds, under an hour
     * as minutes and seconds, otherwise hours and minutes. Truncates; a negative duration is zero.
     */
    fun durationParts(ms: Long): DurationParts {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        return when {
            totalSeconds < 60L -> DurationParts(DurationUnit.Seconds, totalSeconds.toInt(), 0)
            totalSeconds < 3600L -> DurationParts(
                DurationUnit.MinutesSeconds,
                (totalSeconds / 60L).toInt(),
                (totalSeconds % 60L).toInt(),
            )
            else -> DurationParts(
                DurationUnit.HoursMinutes,
                (totalSeconds / 3600L).toInt(),
                ((totalSeconds % 3600L) / 60L).toInt(),
            )
        }
    }
}

/**
 * One line per account, tab-separated: type, name, pendingSince, lastDuration, lastFinishedAt, an
 * empty field for null. A line that doesn't fit is skipped, never thrown on: this is a diagnostic,
 * and a damaged one must not stop the app.
 */
object UploadTimingCodec {
    private const val FIELDS = 5

    fun encode(map: Map<CalendarAccount, UploadTimingEntry>): String =
        map.entries.joinToString("\n") { (account, e) ->
            listOf(
                clean(account.type),
                clean(account.name),
                e.pendingSinceMs?.toString().orEmpty(),
                e.lastDurationMs?.toString().orEmpty(),
                e.lastFinishedAtMs?.toString().orEmpty(),
            ).joinToString("\t")
        }

    fun decode(raw: String?): Map<CalendarAccount, UploadTimingEntry> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<CalendarAccount, UploadTimingEntry>()
        for (line in raw.split('\n')) {
            val parts = line.split('\t')
            if (parts.size != FIELDS) continue
            if (parts[0].isEmpty() && parts[1].isEmpty()) continue
            val pending = field(parts[2]) ?: continue
            val duration = field(parts[3]) ?: continue
            val finished = field(parts[4]) ?: continue
            out[CalendarAccount(name = parts[1], type = parts[0])] =
                UploadTimingEntry(pending.value, duration.value, finished.value)
        }
        return out
    }

    /** A parsed field: [Parsed.value] is null for an empty one. Returns null itself for a bad number. */
    private class Parsed(val value: Long?)

    private fun field(text: String): Parsed? =
        if (text.isEmpty()) Parsed(null) else text.toLongOrNull()?.let { Parsed(it) }

    /** A tab or newline in an account name would break the line format; none is ever valid there. */
    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
