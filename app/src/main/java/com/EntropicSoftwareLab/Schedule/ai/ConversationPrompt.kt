package com.EntropicSoftwareLab.Schedule.ai

/**
 * Renders the recent conversation into the block that gives the model a memory of this chat.
 *
 * Every request used to be self-contained — the model saw one sentence and nothing before it. That
 * is fine for "dinner Friday 7pm" and falls apart on the third message, because by then people are
 * writing "make it 8" and "add this to last week's one", which name things only the transcript
 * knows. What came back was a confident draft about nothing, which reads as the assistant getting
 * worse the longer you talk to it.
 *
 * Two things keep this from becoming its own problem:
 *
 *  - **The last entry is always dropped.** The caller appends the user's new message to the session
 *    before asking, so the newest entry is the very question being answered — it already sits at the
 *    end of the prompt, and repeating it above invites the model to answer it twice.
 *  - **It is a summary, not a replay.** Cards are one line each with the values that were proposed,
 *    not the JSON. Feeding old JSON back teaches the shape of an answer to a question nobody asked.
 */
object ConversationPrompt {

    /** Enough to resolve "it" and "that one"; short enough not to crowd out the instructions. */
    const val MAX_TURNS = 8

    /** One line never gets to be longer than this, whatever was pasted in. */
    private const val MAX_LINE = 160

    fun render(
        entries: List<ChatEntry>,
        cards: List<DraftCard>,
        cancelCards: List<CancelCard>,
    ): String {
        // dropLast(1): the newest entry is the message being answered right now.
        val past = entries.dropLast(1).takeLast(MAX_TURNS)
        if (past.isEmpty()) return ""

        val lines = past.mapNotNull { entry -> line(entry, cards, cancelCards) }
        if (lines.isEmpty()) return ""

        return buildString {
            append("Earlier in this conversation, oldest first. Use it to resolve what the user is\n")
            append("referring to — \"it\", \"that one\", \"the same time\" — and nothing else.\n")
            lines.forEach { append(it).append("\n") }
        }
    }

    private fun line(
        entry: ChatEntry,
        cards: List<DraftCard>,
        cancelCards: List<CancelCard>,
    ): String? = when (entry) {
        is ChatEntry.User -> {
            val body = listOfNotNull(
                entry.text.takeIf { it.isNotBlank() },
                entry.attachmentNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?.let { "[attached $it]" },
            ).joinToString(" ")
            body.takeIf { it.isNotBlank() }?.let { "User: ${it.clean()}" }
        }

        is ChatEntry.Drafts -> {
            val described = entry.cardIds
                .mapNotNull { id -> cards.firstOrNull { it.id == id } }
                .joinToString("; ") { describe(it) }
            described.takeIf { it.isNotEmpty() }?.let { "You proposed: ${it.clean()}" }
        }

        is ChatEntry.Cancellations -> {
            val titles = entry.cardIds
                .mapNotNull { id -> cancelCards.firstOrNull { it.id == id } }
                .joinToString("; ") { it.event.title + if (it.cancelled) " (cancelled)" else "" }
            titles.takeIf { it.isNotEmpty() }?.let { "You offered to cancel: ${it.clean()}" }
        }

        is ChatEntry.Updates -> entry.cardIds.size.takeIf { it > 0 }
            ?.let { "You proposed $it change(s) to events that already exist." }

        is ChatEntry.Answer -> "You answered: ${entry.answer.query.phrasing} is " +
            if (entry.answer.isFree) "free." else "busy."

        is ChatEntry.Note -> "You said: ${entry.text.clean()}"

        is ChatEntry.Fact -> "You looked up: ${entry.text.clean()}"

        // Nothing was understood, so there is nothing here worth carrying forward.
        is ChatEntry.Unparseable -> null
    }

    /** The values the user saw on the card, and whether it went onto the calendar. */
    private fun describe(card: DraftCard): String = buildString {
        val draft = card.draft
        append(draft.title)
        append(" on ").append(draft.date)
        when {
            draft.allDay -> append(" all day")
            draft.startTime != null -> {
                append(" at ").append(draft.startTime)
                draft.endTime?.let { append("–").append(it) }
            }
        }
        draft.location?.takeIf { it.isNotBlank() }?.let { append(" at ").append(it) }
        append(if (card.committed) " (added)" else " (not added)")
    }

    private fun String.clean(): String {
        val flat = replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_LINE) flat else flat.take(MAX_LINE - 1) + "…"
    }
}
