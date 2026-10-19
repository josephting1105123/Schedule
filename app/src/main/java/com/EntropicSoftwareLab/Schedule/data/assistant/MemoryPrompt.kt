package com.EntropicSoftwareLab.Schedule.data.assistant

/**
 * Renders [AssistantMemory] into the block of prompt text that carries it to the model, and into the
 * same sentences Settings shows the user.
 *
 * One renderer for both surfaces on purpose: the line in Settings *is* the line in the prompt, so
 * what the user reads is literally what the model was told. A memory panel that paraphrased would be
 * the kind of thing that stays plausible for months while the real instruction says something else.
 *
 * Two things are deliberately not here. Corrections are rendered as prose, not as JSON few-shot
 * pairs, so a stored example can never teach a stale date or a wrong array shape — the JSON shape is
 * already demonstrated five times in the static prompt above this block. And nothing here is
 * phrased as a command: every line says "usually", because the user's text in front of it always
 * wins over a habit behind it.
 */
object MemoryPrompt {

    const val MAX_HABIT_LINES = 8

    /** Empty string when there is nothing worth saying, so the prompt stays byte-identical to the
     *  no-memory one until the memory has actually earned its place. */
    fun render(memory: AssistantMemory): String {
        val habits = memory.reliableHabits.take(MAX_HABIT_LINES).map(::habitLine)
        val style = styleLines(memory.style)
        val corrections = memory.corrections.map(::correctionLine)
        if (habits.isEmpty() && style.isEmpty() && corrections.isEmpty()) return ""

        return buildString {
            append("What this user's own confirmed events show about how they schedule. Prefer these\n")
            append("over the general defaults above whenever their text doesn't say otherwise.\n")
            (habits + style).forEach { append(it).append("\n") }
            if (corrections.isNotEmpty()) {
                append("\nPhrases they have corrected before — what they typed, then what they meant:\n")
                corrections.forEach { append(it).append("\n") }
            }
        }
    }

    /** One habit as a sentence. Public because Settings shows the user these exact words. */
    fun habitLine(habit: Habit): String {
        val clauses = buildList {
            if (habit.allDay) {
                add("is usually an all-day event")
            } else {
                habit.startMinute?.let { add("usually starts at ${clock(it)}") }
                habit.durationMinutes?.let {
                    add(if (habit.startMinute == null) "usually runs ${duration(it)}" else "runs ${duration(it)}")
                }
            }
            habit.location?.takeIf { it.isNotBlank() }?.let {
                add(if (isEmpty()) "is usually at $it" else "at $it")
            }
        }
        return "- \"${habit.title}\" ${clauses.joinToString(", ")}."
    }

    fun correctionLine(correction: Correction): String {
        val text = correction.userText.replace(Regex("\\s+"), " ").trim()
        return "- \"$text\" → ${describe(correction.event)}"
    }

    /** The confirmed event as the user would read it back. Also used by the Settings list. */
    fun describe(event: ObservedEvent): String = buildString {
        append(event.title.trim())
        when {
            event.allDay -> append(", all day")
            event.startMinute != null && event.durationMinutes != null ->
                append(", ${clock(event.startMinute)}–${clock(event.startMinute + event.durationMinutes)}")
            event.startMinute != null -> append(", from ${clock(event.startMinute)}")
        }
        event.location?.takeIf { it.isNotBlank() }?.let { append(", at $it") }
    }

    private fun styleLines(style: StyleProfile): List<String> = buildList {
        // Only worth a token when it disagrees with the hour the static prompt already assumes.
        style.defaultDurationMinutes?.takeIf { it != 60 }?.let {
            add("- When no end time is given, assume ${duration(it)} rather than an hour.")
        }
        when (style.titleStyle) {
            TitleStyle.Lowercase -> add("- They write event titles in lower case.")
            TitleStyle.Capitalised -> add("- They write event titles capitalised.")
            null -> Unit
        }
    }

    /** Minutes after midnight as HH:mm. Wraps past midnight rather than printing a 25th hour. */
    private fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h${m}m"
        }
    }
}
