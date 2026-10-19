package com.EntropicSoftwareLab.Schedule.ai

import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Turns whatever a small language model actually emitted into validated drafts.
 *
 * A 1B model will sometimes wrap JSON in prose, fence it in markdown, invent a field, or produce a
 * date like "2026-13-45". Everything downstream assumes clean data, so all of that tolerance lives
 * here — and anything that can't be repaired is reported as unparseable rather than guessed at.
 */
object LlmJsonParser {

    /** Longest plausible event; anything beyond this is a model error, not a long meeting. */
    private const val MAX_DURATION_HOURS = 24L
    private const val DEFAULT_DURATION_MINUTES = 60L

    /** Extracts the first balanced JSON array or object, ignoring surrounding prose and fences. */
    fun extractJson(raw: String): String? = extractJsonValues(raw).firstOrNull()

    /**
     * Every balanced top-level JSON value in the text, in order.
     *
     * Returning only the first one silently capped multi-event replies at a single event: a model
     * with no schema to hold it often emits one object per line (`{…}\n{…}`) instead of an array, and
     * everything after the first `}` was discarded without a trace. Scanning for all of them is what
     * makes "standup at 9 then a client call at 2" produce two cards instead of one.
     *
     * Fragments that don't turn out to be JSON are the caller's problem to skip — a stray `{` in
     * prose is balanced text but not an object.
     */
    internal fun extractJsonValues(raw: String): List<String> {
        val text = raw.trim().removeMarkdownFences()
        val values = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '[' && text[i] != '{') {
                i++
                continue
            }
            // An unterminated value is the last thing in the text worth reading; anything after it
            // would be inside the truncation.
            val end = balancedEnd(text, i) ?: break
            values += text.substring(i, end + 1)
            i = end + 1
        }
        return values
    }

    /** Index of the bracket closing the one at [startIndex], or null if it is never closed. */
    private fun balancedEnd(text: String, startIndex: Int): Int? {
        val open = text[startIndex]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inString = false
        var escaped = false

        for (i in startIndex until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun String.removeMarkdownFences(): String =
        replace(Regex("```(?:json)?\\s*"), "").replace("```", "")

    /**
     * Parses an event-extraction response.
     *
     * [today] anchors any date the model returned as a weekday name rather than an ISO date.
     */
    fun parseEventDrafts(raw: String, today: LocalDate): AssistantResult<List<EventDraft>> {
        val values = extractJsonValues(raw)
        if (values.isEmpty()) return AssistantResult.Unparseable(raw)

        // Flatten every value into one event list. Arrays contribute their elements, and a bare
        // object contributes itself — the common shape when there's exactly one event, and also what
        // a model emits repeatedly when it lists events without wrapping them in an array.
        val objects = mutableListOf<JSONObject>()
        for (value in values) {
            try {
                if (value.startsWith("[")) {
                    val array = JSONArray(value)
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { objects += it }
                    }
                } else {
                    objects += JSONObject(value)
                }
            } catch (e: Exception) {
                // One malformed fragment must not sink the well-formed events beside it.
                continue
            }
        }

        val drafts = mutableListOf<EventDraft>()
        for (obj in objects) {
            drafts += obj.toDraft(today) ?: continue
        }

        // An empty array is a valid answer ("no events in this text"); entries we could not read a
        // single event out of are not.
        if (drafts.isEmpty() && objects.isNotEmpty()) return AssistantResult.Unparseable(raw)
        return AssistantResult.Success(drafts)
    }

    private fun JSONObject.toDraft(today: LocalDate): EventDraft? {
        val title = optTextOrNull("title") ?: return null
        val date = parseDate(optString("date"), today) ?: return null
        val allDay = optBoolean("all_day", false)

        if (allDay) {
            return EventDraft(
                title = title,
                date = date,
                startTime = null,
                endTime = null,
                allDay = true,
                location = optTextOrNull("location"),
                description = optTextOrNull("description"),
                repeat = parseRepeatFreq(optTextOrNull("repeat")),
                assumption = optTextOrNull("assumption"),
            )
        }

        val start = parseTime(optString("start_time"))
        var end = parseTime(optString("end_time"))

        // A start with no end is the single most common partial answer; assume an hour rather than
        // discarding an otherwise good draft.
        if (start != null && end == null) {
            val minutes = optLong("duration_minutes", DEFAULT_DURATION_MINUTES)
                .takeIf { it in 1..(MAX_DURATION_HOURS * 60) } ?: DEFAULT_DURATION_MINUTES
            end = start.plusMinutes(minutes)
        }

        // An end at or before the start is either a typo or an overnight event. Treating it as
        // overnight silently would be a guess, so extend by the default instead and say so.
        var assumption = optTextOrNull("assumption")
        if (start != null && end != null && !end.isAfter(start)) {
            end = start.plusMinutes(DEFAULT_DURATION_MINUTES)
            assumption = listOfNotNull(assumption, "End time looked wrong; assumed 1 hour.")
                .joinToString(" ")
        }

        return EventDraft(
            title = title,
            date = date,
            startTime = start,
            endTime = end,
            allDay = false,
            location = optTextOrNull("location"),
            description = optTextOrNull("description"),
            repeat = parseRepeatFreq(optTextOrNull("repeat")),
            assumption = assumption,
        )
    }

    /**
     * The repeat word, or null for anything unrecognised.
     *
     * Unrecognised means null rather than a best guess: a model writing "fortnightly" means something
     * this field can't hold, and quietly storing "weekly" would halve the interval on an event the
     * user then has to notice is wrong.
     */
    private fun parseRepeatFreq(value: String?): RecurrenceFreq? =
        when (value?.trim()?.lowercase()) {
            "daily", "day" -> RecurrenceFreq.Daily
            "weekly", "week" -> RecurrenceFreq.Weekly
            "monthly", "month" -> RecurrenceFreq.Monthly
            "yearly", "year", "annually" -> RecurrenceFreq.Yearly
            else -> null
        }

    /**
     * A trimmed string value, or null when the model wrote JSON null, omitted the key, or left it
     * empty.
     *
     * `optString` cannot be used directly: on a JSON null it returns the four-character string
     * "null" rather than "", so `ifBlank { null }` never fires and the literal word "null" reaches
     * the UI as if the model had written it. The prompt asks for null on most of these fields, so
     * this is the common path, not an edge case.
     */
    internal fun JSONObject.optTextOrNull(key: String): String? {
        if (isNull(key)) return null
        val text = optString(key).trim()
        return text.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    /** Parses an availability-question response into an absolute window. */
    fun parseAvailabilityQuery(
        raw: String,
        now: LocalDateTime,
    ): AssistantResult<AvailabilityQuery> {
        val json = extractJson(raw) ?: return AssistantResult.Unparseable(raw)
        val obj = try {
            if (json.startsWith("[")) JSONArray(json).optJSONObject(0) ?: return AssistantResult.Unparseable(raw)
            else JSONObject(json)
        } catch (e: Exception) {
            return AssistantResult.Unparseable(raw)
        }

        val date = parseDate(obj.optString("date"), now.toLocalDate())
            ?: return AssistantResult.Unparseable(raw)
        val start = parseTime(obj.optString("start_time"))
        val end = parseTime(obj.optString("end_time"))

        // No times means the question was about a whole day ("am I free Tuesday?").
        val window = when {
            start == null -> date.atStartOfDay() to date.plusDays(1).atStartOfDay()
            end == null -> date.atTime(start) to date.atTime(start).plusHours(1)
            !end.isAfter(start) -> date.atTime(start) to date.atTime(start).plusHours(1)
            else -> date.atTime(start) to date.atTime(end)
        }

        return AssistantResult.Success(
            AvailabilityQuery(
                start = window.first,
                end = window.second,
                phrasing = obj.optString("phrasing").trim().ifBlank { "that time" },
            )
        )
    }

    /**
     * Parses a cancellation request into a search.
     *
     * A date is optional here, unlike the other two parsers: "get rid of that lunch with Sam" names
     * no day and is still a usable search. What is *not* usable is a reply with nothing in it at all
     * — that comes back unparseable rather than as a query matching the whole month.
     */
    fun parseCancellation(raw: String, today: LocalDate): AssistantResult<CancellationQuery> {
        val json = extractJson(raw) ?: return AssistantResult.Unparseable(raw)
        val obj = try {
            if (json.startsWith("[")) JSONArray(json).optJSONObject(0)
                ?: return AssistantResult.Unparseable(raw)
            else JSONObject(json)
        } catch (e: Exception) {
            return AssistantResult.Unparseable(raw)
        }

        val query = CancellationQuery(
            titleQuery = obj.optTextOrNull("title_query"),
            date = parseDate(obj.optString("date"), today),
            startTime = parseTime(obj.optString("start_time")),
            endTime = parseTime(obj.optString("end_time")),
            phrasing = obj.optTextOrNull("phrasing") ?: "that",
        )
        // Nothing to search on is not a search. Treating it as one would hand the user a list of
        // everything they have coming up in answer to a request to cancel one thing.
        if (query.isEmptyQuery) return AssistantResult.Unparseable(raw)
        return AssistantResult.Success(query)
    }

    /**
     * Accepts ISO dates, and rejects anything implausible. A model that returns a date two years
     * out has misread the input, and silently accepting it would put an event somewhere the user
     * will never look.
     */
    internal fun parseDate(value: String?, today: LocalDate): LocalDate? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            return null
        }
        val distance = Duration.between(today.atStartOfDay(), parsed.atStartOfDay()).abs().toDays()
        return if (distance <= 730) parsed else null
    }

    /** Accepts "HH:mm" and "H:mm"; rejects out-of-range values rather than clamping them. */
    internal fun parseTime(value: String?): LocalTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null
        val match = Regex("""^(\d{1,2}):(\d{2})""").find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }
}
