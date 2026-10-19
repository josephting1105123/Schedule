package com.EntropicSoftwareLab.Schedule.ai

/**
 * Decides whether the user is describing events to add or asking about their availability.
 *
 * Deliberately a rule, not a model call: it's a reliable signal in the text itself, it costs
 * nothing, and it stays right even when no model is installed. Getting it wrong is cheap anyway —
 * the confirmation card means a misrouted event is a card the user discards, not a stray entry.
 */
object IntentRouter {

    enum class Intent { AddEvents, CheckAvailability, CancelEvents }

    /**
     * Verbs that mean "take something off my calendar".
     *
     * Checked before the question openers because "can I cancel my 3pm" opens like a question but
     * isn't one. Getting this wrong is still cheap: a misrouted cancellation shows a shortlist the
     * user ignores, and a misrouted add shows a draft card they reject. Neither writes anything.
     *
     * Matched on whole words. A substring match reads "cancellation policy review" as a request to
     * cancel something, which is an event whose *name* happens to start with the verb.
     */
    private val CANCEL_VERBS = listOf(
        "cancel", "delete", "remove", "call off", "drop", "scrap", "clear my", "clear the",
        "get rid of", "take off", "unschedule", "bin",
    )

    private val CANCEL_PATTERN: Regex = Regex(
        CANCEL_VERBS.joinToString("|") { """\b${Regex.escape(it)}\b""" },
    )

    private val QUESTION_OPENERS = listOf(
        "am i", "are we", "do i", "did i", "is there", "are there", "have i",
        "what's on", "whats on", "what is on", "what do i have", "when am i",
        "when do i", "anything on", "anything happening", "any plans", "any meetings",
        "how busy", "how free", "can i", "could i", "will i be",
    )

    private val AVAILABILITY_WORDS = listOf("free", "busy", "available", "booked", "occupied", "open")

    fun route(text: String): Intent {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return Intent.AddEvents

        // Before the question openers: "can I cancel my 3pm" opens like a question and isn't one.
        if (CANCEL_PATTERN.containsMatchIn(normalized)) return Intent.CancelEvents

        if (QUESTION_OPENERS.any { normalized.startsWith(it) }) return Intent.CheckAvailability

        // A trailing question mark alone isn't enough — "dinner friday?" is someone adding an event
        // they're unsure about. Pair it with an availability word.
        if (normalized.endsWith("?") && AVAILABILITY_WORDS.any { normalized.contains(it) }) {
            return Intent.CheckAvailability
        }

        return Intent.AddEvents
    }
}
