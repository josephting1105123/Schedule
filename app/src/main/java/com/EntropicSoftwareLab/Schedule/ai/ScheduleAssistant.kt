package com.EntropicSoftwareLab.Schedule.ai

import com.EntropicSoftwareLab.Schedule.data.calendar.CancellationQueryLike
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Turns free text into structured intent.
 *
 * The model's job stops at extraction. It never decides whether a slot is free (that's
 * [com.EntropicSoftwareLab.Schedule.data.calendar.FreeBusy], over real rows) and it never writes to
 * the calendar (that needs a user tap on a confirmation card). Keeping those boundaries means a
 * hallucinated time shows up as a wrong card the user can correct, not a wrong event on their
 * calendar.
 *
 * This interface is the swap point: [GeminiAssistant] is the only implementation today, but any
 * other backend could replace it without any screen changing.
 */
interface ScheduleAssistant {
    val state: StateFlow<AssistantState>

    /**
     * Extracts every event described in [text] and in any [attachments]. Empty list when none.
     *
     * [attachments] is a photo of a timetable, a PDF of a fixture list — the things a schedule
     * actually arrives as. Only a backend that can read them accepts them: the on-device model
     * refuses the request outright rather than silently answering from the text alone, because a
     * draft built from an ignored attachment is wrong in a way the user cannot see.
     */
    suspend fun parseEvents(
        text: String,
        now: LocalDateTime,
        attachments: List<Attachment> = emptyList(),
    ): AssistantResult<List<EventDraft>>

    /** Resolves an availability question ("am I free Tuesday 3pm?") into a time range. */
    suspend fun parseQuery(text: String, now: LocalDateTime): AssistantResult<AvailabilityQuery>

    /**
     * Resolves a cancellation ("cancel my dentist appointment tomorrow") into a *search*.
     *
     * Note what this deliberately is not: the model never names an event id and never decides what
     * gets deleted. It turns a phrase into a description of what to look for, real calendar rows are
     * matched against it by [com.EntropicSoftwareLab.Schedule.data.calendar.EventMatcher], and the
     * user taps the one they meant. A hallucinated title is then a search that finds nothing, not a
     * meeting quietly removed from someone's calendar.
     */
    suspend fun parseCancellation(
        text: String,
        now: LocalDateTime,
    ): AssistantResult<CancellationQuery>

    /**
     * Answers a question that isn't about this calendar at all — "when is the next F1 race".
     *
     * Reached only as a fallback, after extraction has found no event in a message that reads as a
     * question, so ordinary event text never spends a second request. The answer is **grounded in a
     * web search**, not recalled: "the next race" is a fact about this week, and a model answering
     * it from training data gives a date that is wrong and sounds right. A backend that cannot
     * search says so instead of guessing.
     */
    suspend fun answerQuestion(text: String, now: LocalDateTime): AssistantResult<GeneralAnswer>

    /** Loads the model ahead of first use. Safe to call repeatedly. */
    suspend fun warmUp()

    fun close()
}

sealed interface AssistantState {
    /** No Gemini key set — the app still works, the assistant tab just explains how to add one. */
    data object NoKey : AssistantState
    data object Loading : AssistantState
    data object Ready : AssistantState
    data class Failed(val message: String) : AssistantState
}

sealed interface AssistantResult<out T> {
    data class Success<T>(val value: T) : AssistantResult<T>

    /** The model answered, but not with anything usable. [raw] is kept for the manual fallback. */
    data class Unparseable(val raw: String) : AssistantResult<Nothing>

    data class Unavailable(val reason: String) : AssistantResult<Nothing>
}

/**
 * A proposed event, before the user has confirmed it. Everything here is editable in the
 * confirmation card — including [notes], which carries the model's own reasoning when it guessed.
 */
data class EventDraft(
    val title: String,
    val date: LocalDate,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val allDay: Boolean = false,
    val location: String? = null,
    /** Goes to the event's DESCRIPTION — the "remember to bring the X-rays" half of a sentence. */
    val description: String? = null,
    /**
     * How the event repeats, when the text said so ("gym every monday").
     *
     * Only a frequency, not a full [RecurrenceRule][com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceRule]:
     * intervals and end dates are rarely spoken and easy for a model to get subtly wrong, and the
     * card offers the full picker for anything more than this.
     */
    val repeat: RecurrenceFreq? = null,
    /** Human-readable note about anything inferred rather than stated, e.g. "7ish → 19:00". */
    val assumption: String? = null,
) {
    val hasTimes: Boolean get() = startTime != null && endTime != null
}

/** A resolved availability window plus the phrasing it came from, for echoing back to the user. */
data class AvailabilityQuery(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val phrasing: String,
)

/**
 * What to look for when the user asks to cancel something. Every field is a hint, not an instruction.
 *
 * All of them can be absent: "cancel my meeting" gives only a title, "clear tomorrow" gives only a
 * date. The matcher widens or narrows accordingly, and an empty query that would match everything is
 * refused rather than answered — see [com.EntropicSoftwareLab.Schedule.data.calendar.EventMatcher].
 */
data class CancellationQuery(
    /** Words from the event's name, e.g. "dentist". Null when the user only named a time. */
    override val titleQuery: String?,
    /** The day named, or null when none was. */
    override val date: LocalDate?,
    override val startTime: LocalTime?,
    override val endTime: LocalTime?,
    /** How to refer to this in a reply, e.g. "your dentist appointment tomorrow". */
    val phrasing: String,
) : CancellationQueryLike

/**
 * A plain-prose answer to a question the calendar can't answer, plus where it came from.
 *
 * [sources] is not decoration. This is the one reply in the app that is generated rather than read
 * off the user's own rows, so it carries its citations and the UI prints them — an ungrounded date
 * and a grounded one look identical otherwise.
 */
data class GeneralAnswer(
    val text: String,
    val sources: List<String> = emptyList(),
)
