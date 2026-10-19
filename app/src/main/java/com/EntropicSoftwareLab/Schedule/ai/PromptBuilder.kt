package com.EntropicSoftwareLab.Schedule.ai

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the prompts sent to the model, local or cloud.
 *
 * Three things make a 1B model usable for this:
 *  - the current date/time/weekday is stated explicitly, so "next friday" resolves inside the model
 *    instead of against whatever it thinks today is;
 *  - the output shape is demonstrated several times rather than described, which small models
 *    follow far more reliably than an English spec;
 *  - what the user's own confirmed events have shown is spliced in as habits, so the second time
 *    they say "gym tomorrow" the draft already has their hour on it.
 *
 * **Everything is ordered by how often it changes.** The instruction block and its examples are
 * identical on every request and are built once, into [EVENT_INSTRUCTIONS] and
 * [AVAILABILITY_INSTRUCTIONS]. The learned-habit block changes only when the user confirms something
 * new. The date changes daily. The user's text changes every time. Laying them out in that order is
 * what makes the expensive part of the prompt a stable prefix — the precondition for Gemini's
 * implicit prompt caching to be able to hit at all, and free to arrange even where it doesn't.
 *
 * It also happens to be the better arrangement for the on-device model: the date now sits directly
 * above the sentence it has to be applied to, rather than a thousand tokens of examples away.
 */
object PromptBuilder {

    // Fixed dates in the few-shot examples. They are deliberately unrelated to the real "today" so
    // the model learns the field shape from them rather than copying a date verbatim. Declared
    // before the blocks that interpolate them, so the reading order matches the dependency order.
    private const val EXAMPLE_FRIDAY = "2025-03-14"
    private const val EXAMPLE_TOMORROW = "2025-03-11"
    private const val EXAMPLE_MONDAY = "2025-03-17"
    private const val EXAMPLE_TUESDAY = "2025-03-11"

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private fun context(now: LocalDateTime, zoneId: String): String = buildString {
        append("Today is ").append(now.format(WEEKDAY)).append(", ").append(now.format(DATE))
        append(". The current time is ").append(now.format(TIME))
        append(" in ").append(zoneId).append(".\n")
    }

    /**
     * [memory] is the block from
     * [MemoryPrompt.render][com.EntropicSoftwareLab.Schedule.data.assistant.MemoryPrompt.render], or
     * "" when nothing has been learned yet — in which case no memory block is emitted at all and the
     * prompt is exactly the static instructions, the date, and the user's text.
     */
    fun eventExtraction(
        text: String,
        now: LocalDateTime,
        zoneId: String,
        memory: String = "",
        /** The recent conversation from [ConversationPrompt], or "" on the first message. */
        history: String = "",
        /**
         * Names of any files travelling with this request. The bytes go as separate parts; this is
         * the sentence that tells the model they are there and what to do with them. It sits in the
         * volatile tail, after the date, so attaching a file never disturbs the cached prefix.
         */
        attachmentNames: List<String> = emptyList(),
    ): String = buildString {
        append(EVENT_INSTRUCTIONS)
        appendMemory(memory)
        append("\n")
        append(context(now, zoneId))
        appendHistory(history)
        if (attachmentNames.isNotEmpty()) {
            append(
                if (attachmentNames.size == 1) "One file is attached: "
                else "${attachmentNames.size} files are attached: "
            )
            append(attachmentNames.joinToString(", "))
            append(
                ".\nRead the events out of the attached file(s) as well as the text. A date shown " +
                    "without a year means the next time that date occurs. If a file holds a " +
                    "timetable or a fixture list, return one element per row that names a real " +
                    "event, and ignore headers, totals and page furniture.\n"
            )
        }
        append("Now do the same for this text.\n\nUser:\n")
        append(text.trim())
        append("\n")
    }

    fun availabilityQuery(
        text: String,
        now: LocalDateTime,
        zoneId: String,
        memory: String = "",
        /** The recent conversation from [ConversationPrompt], or "" on the first message. */
        history: String = "",
    ): String = buildString {
        append(AVAILABILITY_INSTRUCTIONS)
        appendMemory(memory)
        append("\n")
        append(context(now, zoneId))
        appendHistory(history)
        append("Now do the same for this question.\n\nUser:\n")
        append(text.trim())
        append("\n")
    }

    fun cancellation(
        text: String,
        now: LocalDateTime,
        zoneId: String,
        memory: String = "",
        /** The recent conversation from [ConversationPrompt], or "" on the first message. */
        history: String = "",
    ): String = buildString {
        append(CANCELLATION_INSTRUCTIONS)
        appendMemory(memory)
        append("\n")
        append(context(now, zoneId))
        appendHistory(history)
        append("Now do the same for this request.\n\nUser:\n")
        append(text.trim())
        append("\n")
    }

    /**
     * A question the calendar can't answer.
     *
     * No examples and no schema — this one wants prose, and the few-shot block that makes the other
     * three reliable would only push it back towards emitting JSON. The date is still stated,
     * because "the next race" and "this weekend" are resolved against it.
     */
    fun generalQuestion(text: String, now: LocalDateTime, zoneId: String): String = buildString {
        append(
            """
            Answer the user's question directly and briefly — two or three sentences.

            Rules:
            - Search the web for anything that could have changed recently: fixtures, schedules,
              results, prices, opening times. Do not answer those from memory.
            - Give the date and time in full when the answer is a date, and say which timezone.
            - If you cannot find it, say so plainly. Never invent a date to fill the gap.
            - No preamble and no sign-off. Just the answer.

            """.trimIndent()
        )
        append("\n")
        append(context(now, zoneId))
        append("\nQuestion:\n")
        append(text.trim())
        append("\n")
    }

    /**
     * The opening turn for the tool-calling agent.
     *
     * Shorter than the extraction prompt on purpose: the tool declarations already carry the field
     * shapes and the model is not being asked to emit JSON, so the few-shot block that makes
     * extraction reliable would only be weight. What is left is the part tools cannot express — that
     * a proposal is not a change, that availability is arithmetic and not recall, and that one
     * sentence can hold several events.
     */
    fun agentTurn(
        text: String,
        now: LocalDateTime,
        zoneId: String,
        memory: String = "",
        history: String = "",
    ): String = buildString {
        append(AGENT_INSTRUCTIONS)
        appendMemory(memory)
        append("\n")
        append(context(now, zoneId))
        appendHistory(history)
        append("\nUser:\n")
        append(text.trim())
        append("\n")
    }

    private fun StringBuilder.appendMemory(memory: String) {
        val block = memory.trim()
        if (block.isEmpty()) return
        append("\n").append(block).append("\n")
    }

    /**
     * The conversation goes **after** the date, not before it.
     *
     * It changes on every single message, where the date changes once a day and the habits less
     * often than that. Anything placed above it would stop being a stable prefix the moment a
     * second message was sent, which is the whole property the ordering exists to protect.
     */
    private fun StringBuilder.appendHistory(history: String) {
        val block = history.trim()
        if (block.isEmpty()) return
        append("\n").append(block).append("\n")
    }

    /**
     * Appended on a retry when the first response wasn't parseable. Small models often recover with
     * nothing more than a blunter restatement of the format.
     */
    const val RETRY_SUFFIX = "\n\nYour previous reply was not valid JSON. Reply with ONLY the JSON, starting with [ or {."

    private val AGENT_INSTRUCTIONS: String = """
        You are the assistant inside a calendar app. Use the tools to do what the user asks, then
        reply in one or two short sentences saying what you did. No preamble, no sign-off.

        Rules:
        - A propose_* tool shows the user a card. It does not change their calendar; they tap to
          accept. Say "I've put that up for you to confirm", never "I've added it" or "done".
        - To change or remove an event that already exists, call search_events first and use the
          handle it returns. You cannot name an event any other way.
        - When a search returns several plausible matches, propose the one that best fits and say
          what else you found. Do not guess silently between two similar events.
        - Never answer whether the user is free from what you have seen. Call check_availability;
          it is worked out from their real events.
        - One sentence can describe several events. "gym at 7, standup 9:30, lunch at 1" is three
          calls to propose_event, not one.
        - "morning" is 09:00, "lunch" is 12:00, "afternoon" is 14:00, "evening" is 19:00. "7ish" in
          the evening is 19:00 — record that in the assumption field.
        - If the message is not about the calendar at all, call look_up. Anything about the outside
          world goes through it, including facts you believe you know — your training data is older
          than today, and a stale date sounds exactly like a correct one. Do not invent an event.
        - Anything the user's own text says beats anything you have been told they usually do.
    """.trimIndent() + "\n"

    /**
     * The static half of the extraction prompt. A `val`, not a function: `trimIndent` walks and
     * rebuilds the whole string, and this one is the largest thing in the request. Built once when
     * the object loads, then reused for the life of the process.
     */
    private val EVENT_INSTRUCTIONS: String = """
        Extract every calendar event described by the user.

        Reply with a JSON array and nothing else. No explanation, no markdown.
        Each element has these fields:
          "title"            short event name
          "date"             "YYYY-MM-DD"
          "start_time"       "HH:mm" in 24-hour time, or null for an all-day event
          "end_time"         "HH:mm", or null if unknown
          "all_day"          true or false
          "location"         place, or null
          "description"      any detail to remember about it, or null
          "repeat"           "daily", "weekly", "monthly", "yearly", or null if it doesn't repeat
          "assumption"       short note if you inferred a time or date, otherwise null

        Rules:
        - One array element per event. Text describing three events returns three elements.
          Never merge two events into one, and never stop after the first.
        - "and", "then", "also", ";" and a new line usually separate one event from the next.
        - Each event keeps its own date. When only the first states a day, the rest are that same
          day unless the text says otherwise — repeat the date on every element.
        - Resolve relative dates against the current date stated below these examples.
        - "morning" is 09:00, "lunch" is 12:00, "afternoon" is 14:00, "evening" is 19:00.
        - "7ish" or "around 7" in the evening means 19:00. Record it in "assumption".
        - If no end time is given, leave "end_time" null.
        - Set "all_day" true ONLY when the text gives no clock time at all. If any start time is
          present, "all_day" is false. Never return a start_time together with all_day true.
        - "title" is the event, not the user's instruction: drop leading verbs like "add",
          "schedule", "put in". "add work at 6am" has the title "Work".
        - If the text describes no event at all, reply with [].
        - "description" is for a detail worth keeping, not a restatement of the title. "dentist
          tomorrow, bring the X-rays" has the description "Bring the X-rays". Null when there is none.
        - Set "repeat" only when the text says it recurs — "every monday", "weekly", "each morning".
          A single date is not a repeat. When in doubt, null.
        - "date" is still the FIRST occurrence when "repeat" is set, not a range.
        - Fields you have nothing for are null. Never omit a field and never invent one.
        - Anything the user's own text says beats anything you have been told they usually do. Their
          habits fill gaps; they never overrule a stated time, date or place.

        Examples:

        User: dinner with mum next friday 7ish
        [{"title":"Dinner with mum","date":"$EXAMPLE_FRIDAY","start_time":"19:00","end_time":null,"all_day":false,"location":null,"description":null,"repeat":null,"assumption":"Read \"7ish\" as 19:00."}]

        User: dentist tomorrow at 10:30, should take half an hour — bring the X-rays
        [{"title":"Dentist","date":"$EXAMPLE_TOMORROW","start_time":"10:30","end_time":"11:00","all_day":false,"location":null,"description":"Bring the X-rays.","repeat":null,"assumption":null}]

        User: I'm on leave all day monday
        [{"title":"Leave","date":"$EXAMPLE_MONDAY","start_time":null,"end_time":null,"all_day":true,"location":null,"description":null,"repeat":null,"assumption":null}]

        User: gym every monday at 7am
        [{"title":"Gym","date":"$EXAMPLE_MONDAY","start_time":"07:00","end_time":"08:00","all_day":false,"location":null,"description":null,"repeat":"weekly","assumption":"No end time given; assumed 1 hour."}]

        User: team standup 9-9:30 tomorrow then a client call at 2pm at the office
        [{"title":"Team standup","date":"$EXAMPLE_TOMORROW","start_time":"09:00","end_time":"09:30","all_day":false,"location":null,"description":null,"repeat":null,"assumption":null},{"title":"Client call","date":"$EXAMPLE_TOMORROW","start_time":"14:00","end_time":null,"all_day":false,"location":"The office","description":null,"repeat":null,"assumption":null}]

        User: monday: gym at 7, standup 9:30, lunch with Sam at 1, then dentist at 4:15
        [{"title":"Gym","date":"$EXAMPLE_MONDAY","start_time":"07:00","end_time":"08:00","all_day":false,"location":null,"description":null,"repeat":null,"assumption":"No end time given; assumed 1 hour."},{"title":"Standup","date":"$EXAMPLE_MONDAY","start_time":"09:30","end_time":"10:30","all_day":false,"location":null,"description":null,"repeat":null,"assumption":"No end time given; assumed 1 hour."},{"title":"Lunch with Sam","date":"$EXAMPLE_MONDAY","start_time":"13:00","end_time":"14:00","all_day":false,"location":null,"description":null,"repeat":null,"assumption":"Read \"1\" as 13:00."},{"title":"Dentist","date":"$EXAMPLE_MONDAY","start_time":"16:15","end_time":"17:15","all_day":false,"location":null,"description":null,"repeat":null,"assumption":"Read \"4:15\" as 16:15."}]

        User: thanks, that was helpful
        []
    """.trimIndent() + "\n"

    /**
     * Extraction only, and the wording works hard to keep it that way.
     *
     * The model is told outright that it is describing a search and that the app does the finding.
     * That is not politeness — a model that believes it is choosing an event to delete starts filling
     * in a plausible title when the user was vague, and a confident guess is exactly the failure this
     * flow is built to make impossible.
     */
    private val CANCELLATION_INSTRUCTIONS: String = """
        The user wants to cancel something already on their calendar. Work out what to search for.

        You are NOT choosing which event to delete. You cannot see their calendar. The app searches
        their real events with what you return and shows the user the matches to pick from.

        Reply with a single JSON object and nothing else:
          "title_query"  the distinctive words from the event's name, or null if they named none
          "date"         "YYYY-MM-DD", or null if they named no day
          "start_time"   "HH:mm", or null
          "end_time"     "HH:mm", or null
          "phrasing"     how to refer to what they asked for, e.g. "your dentist appointment tomorrow"

        Rules:
        - "title_query" holds only words that would appear in the event's own name. Drop the
          instruction ("cancel", "delete", "get rid of") and generic nouns ("appointment", "meeting").
        - Never invent a title. If they didn't say what it was, "title_query" is null.
        - Resolve relative dates against the current date stated below these examples.
        - If they named no day at all, "date" is null — the app then searches from today forward.

        Examples:

        User: cancel my dentist appointment tomorrow
        {"title_query":"dentist","date":"$EXAMPLE_TOMORROW","start_time":null,"end_time":null,"phrasing":"your dentist appointment tomorrow"}

        User: delete the 3pm standup on friday
        {"title_query":"standup","date":"$EXAMPLE_FRIDAY","start_time":"15:00","end_time":null,"phrasing":"the 3pm standup on Friday"}

        User: clear my calendar on monday
        {"title_query":null,"date":"$EXAMPLE_MONDAY","start_time":null,"end_time":null,"phrasing":"everything on Monday"}

        User: get rid of that lunch with Sam
        {"title_query":"lunch Sam","date":null,"start_time":null,"end_time":null,"phrasing":"your lunch with Sam"}
    """.trimIndent() + "\n"

    private val AVAILABILITY_INSTRUCTIONS: String = """
        The user is asking whether they are free at some time. Work out which time range they
        mean. Do not answer the question — only identify the range.

        Reply with a single JSON object and nothing else:
          "date"        "YYYY-MM-DD"
          "start_time"  "HH:mm", or null if they mean the whole day
          "end_time"    "HH:mm", or null
          "phrasing"    how to refer to this time in a reply, e.g. "Tuesday at 15:00"

        Resolve relative dates against the current date stated below these examples.

        Examples:

        User: am I free tuesday 3pm?
        {"date":"$EXAMPLE_TUESDAY","start_time":"15:00","end_time":"16:00","phrasing":"Tuesday at 15:00"}

        User: anything on tomorrow?
        {"date":"$EXAMPLE_TOMORROW","start_time":null,"end_time":null,"phrasing":"tomorrow"}

        User: do I have time between 2 and 5 on friday
        {"date":"$EXAMPLE_FRIDAY","start_time":"14:00","end_time":"17:00","phrasing":"Friday 14:00–17:00"}
    """.trimIndent() + "\n"
}
