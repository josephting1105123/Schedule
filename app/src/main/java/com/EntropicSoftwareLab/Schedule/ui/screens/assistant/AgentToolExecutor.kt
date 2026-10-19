package com.EntropicSoftwareLab.Schedule.ui.screens.assistant

import com.EntropicSoftwareLab.Schedule.ai.AgentTool
import com.EntropicSoftwareLab.Schedule.ai.AgentTools
import com.EntropicSoftwareLab.Schedule.ai.EventDraft
import com.EntropicSoftwareLab.Schedule.ai.EventHandles
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Turns one of the agent's tool calls into either a real calendar read or a card on screen.
 *
 * The split it enforces is the whole safety story of the feature: the two reads run immediately,
 * the three proposals only ever call back into the screen to show something. Nothing in this file
 * can write to a calendar, and that is checkable by reading it — there is no repository write method
 * in scope.
 *
 * [onSearch], [onAvailability] and the three `onPropose*` callbacks are supplied by the ViewModel,
 * which owns the repository and the session.
 */
class AgentToolExecutor(
    private val handles: EventHandles,
    private val onSearch: suspend (query: String?, from: LocalDate, to: LocalDate) -> List<EventInstance>,
    private val onAvailability: suspend (date: LocalDate, start: LocalTime?, end: LocalTime?) -> String,
    private val onProposeEvent: suspend (EventDraft) -> Unit,
    private val onProposeUpdate: suspend (ProposedUpdate) -> String,
    private val onProposeCancel: suspend (EventInstance) -> Unit,
    /**
     * Looks up the account's real key + argb for a colour name, or null when that account has no
     * usable slot for it. Resolved here, inside the tool call, rather than downstream in the
     * ViewModel's card-building — by the time a proposal becomes a card the model has already been
     * told "shown to the user" and can no longer see or correct a resolution failure.
     */
    private val onResolveColor: suspend (EventInstance, String) -> ResolvedColor?,
    /**
     * Answers a question about the outside world, grounded in a web search.
     *
     * Its own tool rather than something the agent answers itself. The agent's request carries
     * function declarations, and a request carrying those cannot also carry the search tool — so
     * without this the model would answer "when is the next race" from training data, which is the
     * failure the grounded path was built to avoid. Routing it through a second, tool-free request
     * keeps the citation with the answer.
     */
    private val onLookUp: suspend (String) -> String,
    private val today: () -> LocalDate,
) : AgentTools {

    override suspend fun call(name: String, args: JSONObject): String = when (name) {
        AgentTool.SEARCH_EVENTS -> search(args)
        AgentTool.CHECK_AVAILABILITY -> availability(args)
        AgentTool.PROPOSE_EVENT -> proposeEvent(args)
        AgentTool.PROPOSE_UPDATE -> proposeUpdate(args)
        AgentTool.PROPOSE_CANCEL -> proposeCancel(args)
        AgentTool.LOOK_UP -> args.text("question")
            ?.let { onLookUp(it) }
            ?: "That lookup had no question in it."
        // A tool the model invented. Named back to it so it can correct itself rather than retry.
        else -> "There is no tool called $name."
    }

    private suspend fun search(args: JSONObject): String {
        val from = args.date("from_date") ?: today()
        val to = args.date("to_date") ?: today().plusYears(1)
        val query = args.text("query")
        val found = onSearch(query, minOf(from, to), maxOf(from, to).plusDays(1))
        if (found.isEmpty()) {
            return "No events matched between $from and $to."
        }
        // Handles, never provider ids — see EventHandles for why that boundary matters.
        return handles.putAll(found.take(MAX_SEARCH_RESULTS)).joinToString("\n") { (handle, event) ->
            "$handle: ${describe(event)}"
        }
    }

    private suspend fun availability(args: JSONObject): String {
        val date = args.date("date") ?: return "That date wasn't a date I could read."
        return onAvailability(date, args.time("start_time"), args.time("end_time"))
    }

    private suspend fun proposeEvent(args: JSONObject): String {
        val title = args.text("title") ?: return "A proposed event needs a title."
        val date = args.date("date") ?: return "A proposed event needs a date as YYYY-MM-DD."
        val allDay = args.optBoolean("all_day", false)
        val start = args.time("start_time")

        onProposeEvent(
            EventDraft(
                title = title,
                date = date,
                // The one contradiction a model reliably produces: a start time alongside all-day.
                // Resolved the same way the extraction parser resolves it — a stated time wins,
                // because someone who said a time meant a time.
                startTime = if (allDay && start == null) null else start,
                endTime = if (allDay && start == null) null else args.time("end_time"),
                allDay = allDay && start == null,
                location = args.text("location"),
                description = args.text("description"),
                repeat = args.text("repeat")?.let { freq ->
                    RecurrenceFreq.entries.firstOrNull { it.name.equals(freq, ignoreCase = true) }
                },
                assumption = args.text("assumption"),
            )
        )
        return "Shown to the user as a card. They have not accepted it yet."
    }

    private suspend fun proposeUpdate(args: JSONObject): String {
        val handle = args.text("event")
        val event = handles[handle]
            ?: return "No event called $handle. Call ${AgentTool.SEARCH_EVENTS} first and use a " +
                "handle it returns."

        val colorName = args.text("color")
        val resolvedColor = colorName?.let { name ->
            onResolveColor(event, name)
                // Told back to the model rather than silently dropped, so it can relay the limit
                // to the user in its own words instead of claiming a change that never happened.
                ?: return "That account doesn't offer $name as an event colour."
        }

        val update = ProposedUpdate(
            event = event,
            newTitle = args.text("title"),
            newDate = args.date("date"),
            newStart = args.time("start_time"),
            newEnd = args.time("end_time"),
            newLocation = args.text("location"),
            newColorKey = resolvedColor?.key,
            newColorArgb = resolvedColor?.argb,
        )
        if (update.isEmpty) return "That change had nothing in it. Say which field moves."
        return onProposeUpdate(update)
    }

    private suspend fun proposeCancel(args: JSONObject): String {
        val handle = args.text("event")
        val event = handles[handle]
            ?: return "No event called $handle. Call ${AgentTool.SEARCH_EVENTS} first and use a " +
                "handle it returns."
        onProposeCancel(event)
        return "Shown to the user as a card. Nothing has been removed yet."
    }

    private companion object {
        /** Enough for the model to choose from; past this the results are noise in the context. */
        const val MAX_SEARCH_RESULTS = 20
    }

    private fun describe(event: EventInstance): String = buildString {
        append(event.title)
        append(" — ")
        if (event.allDay) {
            append(LocalDate.ofEpochDay(event.startMillis / 86_400_000L)).append(" all day")
        } else {
            append(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(localStart(event)))
        }
        event.location?.takeIf { it.isNotBlank() }?.let { append(" at ").append(it) }
    }

    private fun localStart(event: EventInstance): LocalDateTime =
        LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(event.startMillis),
            java.time.ZoneId.systemDefault(),
        )
}

/** A colour name resolved to the account's real key and its argb, for a proposed update. */
/**
 * A colour the agent proposed, resolved.
 *
 * [key] is nullable because it is only the account's nearest approximation, used so other calendar
 * apps show something close. The app's own colour is [argb], and an account offering no palette of
 * its own is no longer a reason to refuse the colour outright.
 */
data class ResolvedColor(val key: String?, val argb: Int)

/** What the model asked to change. Null fields mean "leave it alone", never "clear it". */
data class ProposedUpdate(
    val event: EventInstance,
    val newTitle: String? = null,
    val newDate: LocalDate? = null,
    val newStart: LocalTime? = null,
    val newEnd: LocalTime? = null,
    val newLocation: String? = null,
    val newColorKey: String? = null,
    val newColorArgb: Int? = null,
) {
    val isEmpty: Boolean
        get() = newTitle == null && newDate == null && newStart == null &&
            newEnd == null && newLocation == null && newColorKey == null && newColorArgb == null
}

// ---- lenient argument reading -------------------------------------------
//
// A model sends "null", "", and a missing key interchangeably, and JSONObject.optString turns a JSON
// null into the four-character string "null". Every read goes through these so an absent field is
// absent once, here, rather than in five call sites that each get it slightly differently.

private fun JSONObject.text(name: String): String? {
    if (isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

private fun JSONObject.date(name: String): LocalDate? =
    text(name)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Accepts "9:05" as readily as "09:05"; a model is not consistent about the leading zero. */
private fun JSONObject.time(name: String): LocalTime? = text(name)?.let { raw ->
    runCatching { LocalTime.parse(raw) }.getOrNull()
        ?: runCatching { LocalTime.parse(raw.padStart(5, '0')) }.getOrNull()
}
