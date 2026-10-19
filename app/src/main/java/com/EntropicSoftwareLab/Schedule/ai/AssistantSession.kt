package com.EntropicSoftwareLab.Schedule.ai

import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One proposed event plus everything the user needs to judge it before it is written. */
data class DraftCard(
    val id: Int,
    val draft: EventDraft,
    /**
     * The model's proposal before the user touched it. [draft] moves as they edit; this doesn't, and
     * the difference between the two at the moment they tap Add is the only labelled correction this
     * app ever gets. See
     * [MemoryLearner][com.EntropicSoftwareLab.Schedule.data.assistant.MemoryLearner].
     */
    val proposed: EventDraft = draft,
    /** What the user typed to produce this card, kept so a correction can be filed against it. */
    val sourceText: String = "",
    /**
     * True when the message produced this card alone. A card pulled out of "gym at 7, standup 9:30"
     * must never be paired with the whole sentence as an example, or the pair teaches that a
     * three-event sentence yields one event.
     */
    val soleEventInMessage: Boolean = true,
    val conflicts: List<EventInstance> = emptyList(),
    val committing: Boolean = false,
    /** Set once written; enables Undo and locks further edits. */
    val committedEventId: Long? = null,
    val error: String? = null,
) {
    val committed: Boolean get() = committedEventId != null
}

/**
 * One event the user might have meant when they asked to cancel something.
 *
 * [event] is a real row read from the provider, not anything the model produced — the model only
 * described what to search for. So a card here always names an event that genuinely exists, and the
 * worst a bad parse can do is offer the wrong one, which the user can see before tapping.
 */
data class CancelCard(
    val id: Int,
    val event: EventInstance,
    /** True when the event repeats, so cancelling has to ask how far it reaches. */
    val recurring: Boolean = false,
    val cancelling: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null,
)

/**
 * A proposed change to an event that already exists, waiting on a tap.
 *
 * Holds the row it came from as well as the change, so the card can show "10:30 → 14:00" rather than
 * just the new value. A change the user cannot compare against what is there now is one they cannot
 * check, and this is the only assistant card that overwrites something rather than adding to it.
 *
 * Every field of the change is nullable and null means *unchanged*. The model is told to send only
 * what moves, so an absent title is "keep the title", never "clear the title".
 */
data class UpdateCard(
    val id: Int,
    val event: EventInstance,
    val newTitle: String? = null,
    val newStart: java.time.LocalDateTime? = null,
    val newEnd: java.time.LocalDateTime? = null,
    val newLocation: String? = null,
    val newColorKey: String? = null,
    val newColorArgb: Int? = null,
    /** True when the row turned out to be a series, so the scope sheet has to ask first. */
    val recurring: Boolean = false,
    val applying: Boolean = false,
    val applied: Boolean = false,
    val error: String? = null,
) {
    /** Nothing actually moves — a proposal worth refusing rather than showing an empty card. */
    val isEmpty: Boolean
        get() = newTitle == null && newStart == null && newEnd == null && newLocation == null &&
            newColorKey == null && newColorArgb == null
}

/** The answer to an availability question. Computed from calendar rows, never from the model. */
data class AvailabilityAnswer(
    val query: AvailabilityQuery,
    val conflicts: List<EventInstance>,
    val freeGapsSummary: String?,
) {
    val isFree: Boolean get() = conflicts.isEmpty()
}

/**
 * One entry in the visible conversation.
 *
 * Draft cards are referenced by id rather than held inline, so the flat `cards` list in the
 * ViewModel stays the single owner of a draft's edit state. A card being edited in two places at
 * once is the bug that arrangement rules out.
 */
sealed interface ChatEntry {
    val id: Int

    /** What the user typed. */
    data class User(
        override val id: Int,
        val text: String,
        /** Files that went with the message, named so the transcript records what was sent. */
        val attachmentNames: List<String> = emptyList(),
    ) : ChatEntry

    /** One or more proposed events, still awaiting a tap. */
    data class Drafts(override val id: Int, val cardIds: List<Int>) : ChatEntry

    /** A free/busy verdict, computed from calendar rows rather than generated. */
    data class Answer(override val id: Int, val answer: AvailabilityAnswer) : ChatEntry

    /**
     * Events matching a cancellation request, for the user to pick from.
     *
     * [phrasing] echoes back what was understood, so a search that found the wrong thing is legible
     * as a misunderstanding rather than as the calendar being wrong.
     */
    data class Cancellations(
        override val id: Int,
        val cardIds: List<Int>,
        val phrasing: String,
        /**
         * Where the calendar was searched, e.g. "Searched 6 Jun 2026 – 5 Aug 2027". Shown because
         * the honest failure of a keyword clean-up is not "there are none" but "there are none
         * *here*", and without the range the user cannot tell those apart.
         */
        val searchedNote: String? = null,
        /** The user asked for all of something, so the list is long and offers to take the lot. */
        val bulk: Boolean = false,
    ) : ChatEntry

    /** Proposed changes to events that already exist, still awaiting a tap. */
    data class Updates(override val id: Int, val cardIds: List<Int>) : ChatEntry

    /** Plain assistant text — "I didn't find an event in that", or a failure reason. */
    data class Note(override val id: Int, val text: String) : ChatEntry

    /**
     * An answer to something the calendar couldn't answer, looked up on the web.
     *
     * Its own entry type rather than a [Note] because it is the only text in this app the model
     * wrote rather than read off the user's rows, and it has to be able to say where it came from.
     * [sources] empty means the model answered without searching — worth showing differently.
     */
    data class Fact(
        override val id: Int,
        val text: String,
        val sources: List<String> = emptyList(),
    ) : ChatEntry

    /** Input the model couldn't turn into anything, kept so the manual form can be offered. */
    data class Unparseable(override val id: Int, val raw: String) : ChatEntry
}

/**
 * The visible conversation, held for the life of the process.
 *
 * Deliberately app-scoped rather than ViewModel-scoped: a ViewModel dies when its nav entry leaves
 * the back stack, so a transcript kept there would vanish on a tab switch. Held here it survives
 * moving between Today, Month and Assistant, and dies when the process does — which is exactly the
 * "resets when the app is closed" behaviour wanted, with no persistence to write or clear.
 *
 * Not to be confused with the write log, which is the opposite: invisible, and persisted precisely
 * so it outlives the process.
 */
class AssistantSession {

    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()

    private val _cards = MutableStateFlow<List<DraftCard>>(emptyList())
    val cards: StateFlow<List<DraftCard>> = _cards.asStateFlow()

    private val _cancelCards = MutableStateFlow<List<CancelCard>>(emptyList())
    val cancelCards: StateFlow<List<CancelCard>> = _cancelCards.asStateFlow()

    // One counter for entries and both kinds of card. They live in separate lists, so separate
    // counters would work — until an entry holds card ids and the two namespaces have to be told
    // apart by eye. One source makes every id in the session unique by construction.
    private var nextId = 0

    fun nextEntryId(): Int = nextId++

    fun nextCardId(): Int = nextId++

    fun addEntry(entry: ChatEntry) = _entries.update { it + entry }

    fun addCards(new: List<DraftCard>) = _cards.update { it + new }

    fun addCancelCards(new: List<CancelCard>) = _cancelCards.update { it + new }

    fun updateCancelCard(cardId: Int, transform: (CancelCard) -> CancelCard) =
        _cancelCards.update { cards -> cards.map { if (it.id == cardId) transform(it) else it } }

    fun cancelCard(cardId: Int): CancelCard? = _cancelCards.value.firstOrNull { it.id == cardId }

    private val _updateCards = MutableStateFlow<List<UpdateCard>>(emptyList())
    val updateCards: StateFlow<List<UpdateCard>> = _updateCards.asStateFlow()

    fun addUpdateCards(new: List<UpdateCard>) = _updateCards.update { it + new }

    fun updateUpdateCard(cardId: Int, transform: (UpdateCard) -> UpdateCard) =
        _updateCards.update { cards -> cards.map { if (it.id == cardId) transform(it) else it } }

    fun updateCard(cardId: Int): UpdateCard? = _updateCards.value.firstOrNull { it.id == cardId }

    fun removeUpdateCard(cardId: Int) {
        _updateCards.update { cards -> cards.filterNot { it.id == cardId } }
        _entries.update { entries ->
            entries.map { entry ->
                if (entry is ChatEntry.Updates) entry.copy(cardIds = entry.cardIds - cardId) else entry
            }
        }
    }

    fun updateCard(cardId: Int, transform: (DraftCard) -> DraftCard) = _cards.update { cards ->
        cards.map { if (it.id == cardId) transform(it) else it }
    }

    fun removeCard(cardId: Int) {
        _cards.update { cards -> cards.filterNot { it.id == cardId } }
        // Leave the entry in place even when it empties out: the user's message above it is still
        // part of the conversation, and silently deleting the reply reads as the app losing track.
        _entries.update { entries ->
            entries.map { entry ->
                if (entry is ChatEntry.Drafts) entry.copy(cardIds = entry.cardIds - cardId) else entry
            }
        }
    }

    fun card(cardId: Int): DraftCard? = _cards.value.firstOrNull { it.id == cardId }

    fun clear() {
        _entries.value = emptyList()
        _cards.value = emptyList()
        _cancelCards.value = emptyList()
        _updateCards.value = emptyList()
        nextId = 0
    }
}
