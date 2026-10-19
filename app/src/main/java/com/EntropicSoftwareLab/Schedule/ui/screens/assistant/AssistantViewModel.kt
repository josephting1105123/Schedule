package com.EntropicSoftwareLab.Schedule.ui.screens.assistant

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.EntropicSoftwareLab.Schedule.ai.AssistantResult
import com.EntropicSoftwareLab.Schedule.ai.AssistantSession
import com.EntropicSoftwareLab.Schedule.ai.Attachment
import com.EntropicSoftwareLab.Schedule.ai.AttachmentReader
import com.EntropicSoftwareLab.Schedule.ai.PendingAttachment
import com.EntropicSoftwareLab.Schedule.ai.AssistantState
import com.EntropicSoftwareLab.Schedule.ai.AvailabilityAnswer
import com.EntropicSoftwareLab.Schedule.ai.CancelCard
import com.EntropicSoftwareLab.Schedule.ai.ChatEntry
import com.EntropicSoftwareLab.Schedule.ai.DraftCard
import com.EntropicSoftwareLab.Schedule.ai.ConversationPrompt
import com.EntropicSoftwareLab.Schedule.ai.EventDraft
import com.EntropicSoftwareLab.Schedule.ai.EventHandles
import com.EntropicSoftwareLab.Schedule.ai.GeminiAgent
import com.EntropicSoftwareLab.Schedule.ai.IntentRouter
import com.EntropicSoftwareLab.Schedule.ai.UpdateCard
import com.EntropicSoftwareLab.Schedule.ai.ScheduleAssistant
import com.EntropicSoftwareLab.Schedule.data.assistant.AssistantMemoryRepository
import com.EntropicSoftwareLab.Schedule.data.assistant.ObservedEvent
import com.EntropicSoftwareLab.Schedule.data.assistant.Observation
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarInfo
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.EventEdit
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.EventMatcher
import com.EntropicSoftwareLab.Schedule.data.calendar.FreeBusy
import com.EntropicSoftwareLab.Schedule.data.calendar.GoogleCalendarColors
import com.EntropicSoftwareLab.Schedule.data.calendar.NewEvent
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceRule
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceScope
import com.EntropicSoftwareLab.Schedule.data.calendar.toRRule
import com.EntropicSoftwareLab.Schedule.data.settings.SettingsRepository
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Dates in the "where I looked" caption. Day-month-year, because the range spans years. */
private val SEARCH_RANGE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

data class AssistantUiState(
    val input: String = "",
    val assistantState: AssistantState = AssistantState.Loading,
    val thinking: Boolean = false,
    /** The visible conversation, oldest first. Mirrored from the app-scoped session. */
    val entries: List<ChatEntry> = emptyList(),
    /** Every draft card ever produced this session, referenced from [entries] by id. */
    val cards: List<DraftCard> = emptyList(),
    val cancelCards: List<CancelCard> = emptyList(),
    val updateCards: List<UpdateCard> = emptyList(),
    val calendars: List<CalendarInfo> = emptyList(),
    val selectedCalendarId: Long? = null,
    /** A Gemini key is set, so requests can reach the cloud. */
    val cloudActive: Boolean = false,
    /** Files picked but not yet sent. Held as URIs, so this costs a list, not several megabytes. */
    val attachments: List<PendingAttachment> = emptyList(),
) {
    fun card(id: Int): DraftCard? = cards.firstOrNull { it.id == id }

    fun cancelCard(id: Int): CancelCard? = cancelCards.firstOrNull { it.id == id }

    fun updateCard(id: Int): UpdateCard? = updateCards.firstOrNull { it.id == id }

    /** A file on its own is a message: "here, read this" needs no caption. */
    val canSend: Boolean
        get() = (input.isNotBlank() || attachments.isNotEmpty()) &&
            !thinking && assistantState is AssistantState.Ready

    /**
     * Reading files still needs a real model behind it. Surfaced as a disabled button with a
     * reason rather than as a picker that leads to a refusal after the user has chosen something.
     */
    val canAttach: Boolean get() = cloudActive
}

/**
 * Drives the assistant conversation.
 *
 * The transcript itself lives in [AssistantSession], which is app-scoped: this ViewModel dies when
 * the Assistant tab leaves the back stack, and a conversation kept here would vanish on a tab switch.
 * What stays here is everything genuinely per-screen — the input box, the thinking flag, the download
 * state — plus the logic that turns one message into one reply.
 */
class AssistantViewModel(
    private val assistant: ScheduleAssistant,
    private val repository: CalendarRepository,
    private val settings: SettingsRepository,
    private val memory: AssistantMemoryRepository,
    private val session: AssistantSession,
    /** Reads picked documents. Held rather than fetched from a Context this class doesn't have. */
    private val contentResolver: ContentResolver,
    /**
     * Builds the tool-calling agent, or returns null when no Gemini key is set. Read per message so
     * pasting a key in Settings switches the assistant over without a restart, the same way
     * [com.EntropicSoftwareLab.Schedule.ai.RoutingAssistant] switches backends.
     */
    private val agentProvider: suspend () -> GeminiAgent?,
    private val zoneProvider: () -> ZoneId,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            assistant.state.collect { s -> _state.update { it.copy(assistantState = s) } }
        }
        viewModelScope.launch {
            session.entries.collect { entries -> _state.update { it.copy(entries = entries) } }
        }
        viewModelScope.launch {
            session.cards.collect { cards -> _state.update { it.copy(cards = cards) } }
        }
        viewModelScope.launch {
            session.cancelCards.collect { cards -> _state.update { it.copy(cancelCards = cards) } }
        }
        viewModelScope.launch {
            session.updateCards.collect { cards -> _state.update { it.copy(updateCards = cards) } }
        }
        viewModelScope.launch {
            runCatching {
                val calendars = repository.listWritableCalendars()
                val stored = settings.defaultCalendarId.first()
                val chosen = calendars.firstOrNull { it.id == stored }
                    ?: calendars.firstOrNull { it.isPrimary }
                    ?: calendars.firstOrNull()
                _state.update { it.copy(calendars = calendars, selectedCalendarId = chosen?.id) }
            }
        }
        viewModelScope.launch { assistant.warmUp() }

        viewModelScope.launch {
            // Only whether a key exists, never the key itself — this reaches the UI.
            settings.geminiApiKey.collect { key ->
                _state.update { it.copy(cloudActive = !key.isNullOrBlank()) }
            }
        }
    }

    fun setInput(value: String) = _state.update { it.copy(input = value) }

    fun setCalendar(id: Long) {
        _state.update { it.copy(selectedCalendarId = id) }
        viewModelScope.launch { settings.setDefaultCalendarId(id) }
    }

    /** Wipes the conversation. The events it created stay on the calendar; only the record goes. */
    fun clearConversation() = session.clear()

    // --- one message in, one reply out ------------------------------------

    fun submit() {
        val current = _state.value
        val text = current.input.trim()
        val pending = current.attachments
        if ((text.isEmpty() && pending.isEmpty()) || current.thinking) return

        AttachmentReader.rejectionReason(pending)?.let { reason ->
            note(reason)
            return
        }

        // The user's line lands immediately and the box empties, so the conversation reads as sent
        // rather than pending while the model works.
        session.addEntry(
            ChatEntry.User(
                id = session.nextEntryId(),
                text = text,
                attachmentNames = pending.map { it.name },
            )
        )
        _state.update { it.copy(input = "", attachments = emptyList(), thinking = true) }

        viewModelScope.launch {
            val now = clock()
            val files = readAttachments(pending)
            val agent = agentProvider()
            when {
                // With a key, the model decides which calendar action the message needs instead of
                // a keyword rule doing it. Files still go down the extraction path: that one takes
                // attachments and the agent's tools are text.
                agent != null && files.isEmpty() -> runAgent(agent, text, now)
                // A file always describes events to add. Routing on the caption would send "cancel
                // this?" written over a photo of a timetable down the cancellation path, where the
                // photo is not looked at at all.
                files.isNotEmpty() -> handleEvents(text, now, files)
                else -> when (IntentRouter.route(text)) {
                    IntentRouter.Intent.CheckAvailability -> handleQuery(text, now)
                    IntentRouter.Intent.AddEvents -> handleEvents(text, now)
                    IntentRouter.Intent.CancelEvents -> handleCancellation(text, now)
                }
            }
            _state.update { it.copy(thinking = false) }
        }
    }

    /**
     * Reads every picked file, dropping any that can no longer be opened and saying so.
     *
     * A document URI can go stale between the picker and Send — a cloud-backed provider revokes it,
     * or the file is deleted. Silently sending fewer files than the user attached would produce a
     * confident answer about the ones that survived.
     */
    private suspend fun readAttachments(pending: List<PendingAttachment>): List<Attachment> {
        if (pending.isEmpty()) return emptyList()
        val read = pending.map { it to AttachmentReader.read(contentResolver, it) }
        read.filter { it.second == null }.forEach { (missing, _) ->
            note("I couldn't open ${missing.name}, so I left it out.")
        }
        return read.mapNotNull { it.second }
    }

    fun addAttachments(uris: List<Uri>) {
        val resolver = contentResolver
        val described = uris.mapNotNull { AttachmentReader.describe(resolver, it) }
        val unsupported = described.filterNot { AttachmentReader.isSupported(it.mimeType) }
        unsupported.forEach {
            note("I can't read ${it.name}. Attach an image, a PDF, or a text file.")
        }
        val accepted = described - unsupported.toSet()
        if (accepted.isEmpty()) return
        _state.update { state ->
            // Same URI twice is one attachment. Picking a file, going back, and picking it again is
            // ordinary, and sending it twice would invite two drafts of the same event.
            val existing = state.attachments.map { it.uri }.toSet()
            state.copy(attachments = state.attachments + accepted.filterNot { it.uri in existing })
        }
    }

    fun removeAttachment(uri: Uri) {
        _state.update { it.copy(attachments = it.attachments.filterNot { a -> a.uri == uri }) }
    }

    private fun note(text: String) = session.addEntry(ChatEntry.Note(session.nextEntryId(), text))

    // --- the tool-calling agent -------------------------------------------

    /**
     * One turn with the agent.
     *
     * Proposals are **buffered until the turn ends** rather than shown as they arrive. Two reasons,
     * and the second is the load-bearing one: cards appearing one at a time mid-conversation while
     * the model is still deciding reads as the calendar being edited live, which is exactly the
     * impression this app exists not to give; and a card only knows whether it was the sole event in
     * a message once the message is over, which is what the memory learner needs to decide whether
     * the phrasing is worth remembering.
     */
    private suspend fun runAgent(agent: GeminiAgent, text: String, now: LocalDateTime) {
        val zone = zoneProvider()
        val handles = EventHandles()
        val proposedEvents = mutableListOf<EventDraft>()
        val proposedUpdates = mutableListOf<ProposedUpdate>()
        val proposedCancels = mutableListOf<EventInstance>()

        val executor = AgentToolExecutor(
            handles = handles,
            onSearch = { query, from, to -> searchForAgent(query, from, to, zone) },
            onAvailability = { date, start, end -> availabilityForAgent(date, start, end, zone) },
            onProposeEvent = { proposedEvents += it },
            onProposeUpdate = { update ->
                proposedUpdates += update
                "Shown to the user as a card. Nothing has changed on their calendar yet."
            },
            onProposeCancel = { proposedCancels += it },
            onResolveColor = { event, name -> resolveColorForAgent(event, name) },
            onLookUp = { question -> lookUpForAgent(question, now) },
            today = { now.toLocalDate() },
        )

        val result = agent.run(
            text = text,
            now = now,
            zoneId = zone.id,
            memory = runCatching { memory.promptBlock() }.getOrDefault(""),
            history = ConversationPrompt.render(
                entries = session.entries.value,
                cards = session.cards.value,
                cancelCards = session.cancelCards.value,
            ),
            tools = executor,
        )

        flushProposals(text, proposedEvents, proposedUpdates, proposedCancels, zone)

        when (result) {
            is AssistantResult.Success -> result.value.takeIf { it.isNotBlank() }?.let(::note)
            is AssistantResult.Unparseable -> note("I couldn't read that answer.")
            is AssistantResult.Unavailable -> note(result.reason)
        }
    }

    /**
     * The grounded answer, put straight into the transcript **and** handed back to the model.
     *
     * Both, deliberately. The entry carries the citations, which the model's own retelling would
     * drop; giving it the text as well is what lets it answer the actual question — "so is the race
     * before my flight?" — instead of parroting a paragraph it cannot reason over.
     */
    private suspend fun lookUpForAgent(question: String, now: LocalDateTime): String =
        when (val result = assistant.answerQuestion(question, now)) {
            is AssistantResult.Success -> {
                session.addEntry(
                    ChatEntry.Fact(
                        id = session.nextEntryId(),
                        text = result.value.text,
                        sources = result.value.sources,
                    )
                )
                result.value.text
            }
            is AssistantResult.Unparseable -> "That lookup came back unreadable."
            is AssistantResult.Unavailable -> "Couldn't look that up: ${result.reason}"
        }

    private suspend fun flushProposals(
        sourceText: String,
        events: List<EventDraft>,
        updates: List<ProposedUpdate>,
        cancels: List<EventInstance>,
        zone: ZoneId,
    ) {
        if (events.isNotEmpty()) {
            val cards = events.map { draft ->
                DraftCard(
                    id = session.nextCardId(),
                    draft = draft,
                    proposed = draft,
                    sourceText = sourceText,
                    soleEventInMessage = events.size == 1,
                )
            }
            session.addCards(cards)
            session.addEntry(ChatEntry.Drafts(session.nextEntryId(), cards.map { it.id }))
            cards.forEach { refreshConflicts(it.id) }
        }

        if (updates.isNotEmpty()) {
            val cards = updates.mapNotNull { update ->
                val start = newStartFor(update, zone) ?: return@mapNotNull null
                UpdateCard(
                    id = session.nextCardId(),
                    event = update.event,
                    newTitle = update.newTitle,
                    newStart = start.takeIf { it != update.event.localStart(zone) },
                    newEnd = newEndFor(update, start, zone),
                    newLocation = update.newLocation,
                    newColorKey = update.newColorKey,
                    newColorArgb = update.newColorArgb,
                    recurring = isRecurring(update.event),
                ).takeUnless { it.isEmpty }
            }
            if (cards.isNotEmpty()) {
                session.addUpdateCards(cards)
                session.addEntry(ChatEntry.Updates(session.nextEntryId(), cards.map { it.id }))
            }
        }

        if (cancels.isNotEmpty()) {
            val cards = cancels.map { event ->
                CancelCard(
                    id = session.nextCardId(),
                    event = event,
                    recurring = isRecurring(event),
                )
            }
            session.addCancelCards(cards)
            session.addEntry(
                ChatEntry.Cancellations(
                    id = session.nextEntryId(),
                    cardIds = cards.map { it.id },
                    phrasing = "what you asked me to remove",
                )
            )
        }
    }

    /**
     * The new start, built from whichever of date and time the model actually sent.
     *
     * A change that names only a time keeps the day, and one that names only a day keeps the time —
     * "move it to Tuesday" must not silently reset a 14:00 meeting to midnight.
     */
    private fun newStartFor(update: ProposedUpdate, zone: ZoneId): LocalDateTime? {
        val current = update.event.localStart(zone)
        if (update.newDate == null && update.newStart == null) return current
        return LocalDateTime.of(
            update.newDate ?: current.toLocalDate(),
            update.newStart ?: current.toLocalTime(),
        )
    }

    /**
     * The new end. When only the start moved, the event keeps its length rather than its old end —
     * dragging a meeting an hour later shouldn't quietly make it an hour shorter.
     */
    private fun newEndFor(update: ProposedUpdate, newStart: LocalDateTime, zone: ZoneId): LocalDateTime? {
        val oldStart = update.event.localStart(zone)
        val oldEnd = update.event.localEnd(zone)
        val explicit = update.newEnd?.let { LocalDateTime.of(newStart.toLocalDate(), it) }
        if (explicit != null) return explicit.takeIf { it != oldEnd }
        if (newStart == oldStart) return null
        val shifted = newStart.plus(java.time.Duration.between(oldStart, oldEnd))
        return shifted.takeIf { it != oldEnd }
    }

    private suspend fun searchForAgent(
        query: String?,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<EventInstance> = runCatching {
        val candidates = repository.eventsInRange(from, to, zone)
        if (query.isNullOrBlank()) candidates.take(20)
        else EventMatcher.match(
            query = AgentSearchQuery(query),
            candidates = candidates,
            zone = zone,
            now = clock(),
            limit = 20,
        )
    }.getOrDefault(emptyList())

    /**
     * Turns a colour name the model proposed into an ARGB, plus the nearest key the account offers.
     *
     * Resolved against this app's own palette first, then Google's twenty-four names, because those
     * are the names the model has in its tool schema and the ones it actually knows. It no longer
     * refuses when the *account* doesn't offer the colour: the app stores the exact ARGB itself, so
     * "your account doesn't have Citron" stopped being a real obstacle the moment the palette
     * stopped being Google's.
     *
     * Reads a fresh [CalendarRepository.listCalendars] rather than `_state.value.calendars`: that
     * state is loaded asynchronously in `init` and filtered down to writable calendars, so it can
     * both miss the event's own calendar and race a message sent on the very first turn.
     */
    private suspend fun resolveColorForAgent(event: EventInstance, name: String): ResolvedColor? =
        runCatching {
            val argb = EventColorPalette.colors
                .firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.argb
                ?: GoogleCalendarColors.argbForName(name)
                ?: return@runCatching null

            // The key is best-effort: it only decides what other calendar apps show, and an account
            // with no palette of its own simply gets none.
            val calendar = repository.listCalendars().firstOrNull { it.id == event.calendarId }
            val key = calendar
                ?.let { runCatching { repository.eventColors(it) }.getOrDefault(emptyList()) }
                ?.let { GoogleCalendarColors.nearestAvailable(argb, it) }
                ?.key
            ResolvedColor(key = key, argb = argb)
        }.getOrNull()

    private suspend fun availabilityForAgent(
        date: LocalDate,
        start: LocalTime?,
        end: LocalTime?,
        zone: ZoneId,
    ): String = runCatching {
        val from = LocalDateTime.of(date, start ?: LocalTime.MIN)
        val until = when {
            end != null -> LocalDateTime.of(date, end)
            start != null -> LocalDateTime.of(date, start).plusHours(1)
            else -> date.plusDays(1).atStartOfDay()
        }
        val events = repository.eventInstances(
            from.atZone(zone).toInstant().toEpochMilli(),
            until.atZone(zone).toInstant().toEpochMilli(),
        )
        // Arithmetic over real rows, exactly as the non-agent path does. The model is told the
        // verdict; it never works it out.
        val conflicts = FreeBusy.conflictsIn(
            events,
            from.atZone(zone).toInstant().toEpochMilli(),
            until.atZone(zone).toInstant().toEpochMilli(),
        )
        if (conflicts.isEmpty()) "Free from $from to $until."
        else "Busy from $from to $until: " + conflicts.joinToString("; ") { it.title }
    }.getOrDefault("Couldn't read the calendar for that time.")

    // --- applying a proposed change ---------------------------------------

    /**
     * Writes one confirmed change. The fifth write entry point, and it carries the same gate as the
     * other four: it is reachable only from Apply on a card that showed the old value beside the new.
     */
    fun applyUpdate(cardId: Int, scope: RecurrenceScope = RecurrenceScope.AllEvents) {
        val card = session.updateCard(cardId) ?: return
        if (card.applying || card.applied) return
        session.updateUpdateCard(cardId) { it.copy(applying = true, error = null) }

        viewModelScope.launch {
            try {
                val zone = zoneProvider()
                val details = repository.eventDetails(
                    eventId = card.event.eventId,
                    instanceStartMillis = card.event.startMillis,
                    instanceEndMillis = card.event.endMillis,
                    zone = zone,
                )
                if (details == null) {
                    session.updateUpdateCard(cardId) {
                        it.copy(applying = false, error = "That event is already gone.")
                    }
                    return@launch
                }
                if (!details.isWritable) {
                    session.updateUpdateCard(cardId) {
                        it.copy(applying = false, error = "That calendar is read-only.")
                    }
                    return@launch
                }
                // Read from the row, not from the card: every column the change doesn't name has to
                // be written back as it stands, or an unmentioned description is cleared.
                val edit = EventEdit(
                    title = card.newTitle ?: details.title,
                    start = card.newStart ?: details.instanceStart,
                    end = card.newEnd ?: details.instanceEnd,
                    allDay = details.allDay,
                    description = details.description,
                    location = card.newLocation ?: details.location,
                    rrule = details.rrule,
                    // Card carries a key+argb pair resolved together at proposal time, or neither —
                    // never one without the other — so falling back per-field still lands on a
                    // consistent pair. CalendarRepository.eventValues() owns the key-vs-raw exclusion;
                    // duplicating that decision here is how the two copies drift.
                    eventColor = card.newColorArgb ?: details.eventColor,
                    eventColorKey = card.newColorKey ?: details.eventColorKey,
                )
                val ok = repository.updateEvent(details, edit, scope, zone)
                session.updateUpdateCard(cardId) {
                    if (ok) it.copy(applying = false, applied = true)
                    else it.copy(applying = false, error = "The calendar wouldn't take that change.")
                }
            } catch (e: SecurityException) {
                session.updateUpdateCard(cardId) {
                    it.copy(applying = false, error = "Calendar write access was revoked.")
                }
            } catch (e: Exception) {
                session.updateUpdateCard(cardId) {
                    it.copy(applying = false, error = e.message ?: "Couldn't apply that.")
                }
            }
        }
    }

    fun discardUpdate(cardId: Int) {
        session.updateUpdateCard(cardId) { it.copy(error = null) }
        session.removeUpdateCard(cardId)
    }

    private suspend fun answerGeneralQuestion(text: String, now: LocalDateTime) {
        when (val result = assistant.answerQuestion(text, now)) {
            is AssistantResult.Success -> session.addEntry(
                ChatEntry.Fact(
                    id = session.nextEntryId(),
                    text = result.value.text,
                    sources = result.value.sources,
                )
            )
            is AssistantResult.Unparseable -> note("I couldn't make sense of the answer to that.")
            is AssistantResult.Unavailable -> note(result.reason)
        }
    }

    private suspend fun handleEvents(
        text: String,
        now: LocalDateTime,
        attachments: List<Attachment> = emptyList(),
    ) {
        when (val result = assistant.parseEvents(text, now, attachments)) {
            is AssistantResult.Success -> {
                if (result.value.isEmpty()) {
                    // Nothing to schedule. If it reads as a question, it was probably never about
                    // the calendar — "when is the next F1 race" is a question, not an event, and
                    // answering it with "I didn't find an event in that" is a non-sequitur. Asked
                    // only on the way out, so ordinary event text never pays for a second request.
                    if (attachments.isEmpty() && looksLikeAQuestion(text)) {
                        answerGeneralQuestion(text, now)
                        return
                    }
                    note(
                        if (attachments.isEmpty()) "I didn't find an event in that."
                        else "I couldn't find an event in that file."
                    )
                    return
                }
                val cards = result.value.map { draft ->
                    DraftCard(
                        id = session.nextCardId(),
                        draft = draft,
                        proposed = draft,
                        sourceText = text,
                        soleEventInMessage = result.value.size == 1,
                    )
                }
                session.addCards(cards)
                session.addEntry(ChatEntry.Drafts(session.nextEntryId(), cards.map { it.id }))
                cards.forEach { refreshConflicts(it.id) }
            }
            is AssistantResult.Unparseable ->
                session.addEntry(ChatEntry.Unparseable(session.nextEntryId(), text))
            is AssistantResult.Unavailable -> note(result.reason)
        }
    }

    private suspend fun handleQuery(text: String, now: LocalDateTime) {
        when (val result = assistant.parseQuery(text, now)) {
            is AssistantResult.Success -> {
                val zone = zoneProvider()
                val query = result.value
                try {
                    val startMillis = query.start.atZone(zone).toInstant().toEpochMilli()
                    val endMillis = query.end.atZone(zone).toInstant().toEpochMilli()
                    val events = repository.eventInstances(startMillis, endMillis)
                    // The verdict is arithmetic over real rows — the model only chose the window.
                    val conflicts = FreeBusy.conflictsIn(events, startMillis, endMillis)
                    val gaps = FreeBusy.freeGaps(events, query.start, query.end, zone)
                    session.addEntry(
                        ChatEntry.Answer(
                            id = session.nextEntryId(),
                            answer = AvailabilityAnswer(
                                query = query,
                                conflicts = conflicts,
                                freeGapsSummary = summariseGaps(gaps, zone, conflicts.isEmpty()),
                            ),
                        )
                    )
                } catch (e: Exception) {
                    note("Couldn't read the calendar for that time.")
                }
            }
            is AssistantResult.Unparseable ->
                session.addEntry(ChatEntry.Unparseable(session.nextEntryId(), text))
            is AssistantResult.Unavailable -> note(result.reason)
        }
    }

    /**
     * Turns "cancel my dentist appointment" into a shortlist of the user's real events.
     *
     * The model's answer is a search, never an event. Rows come from the provider, [EventMatcher]
     * ranks them, and nothing is removed until the user taps a specific card — the same gate the
     * add path has, for the same reason.
     */
    private suspend fun handleCancellation(text: String, now: LocalDateTime) {
        when (val result = assistant.parseCancellation(text, now)) {
            is AssistantResult.Success -> {
                val query = result.value
                val zone = zoneProvider()
                // "Remove any MLBB events" means every one of them, not the first five. The signal
                // is in the user's own words, so it is read there rather than asked of the model.
                val bulk = EventMatcher.isBulkRequest(text)
                val (from, to) = EventMatcher.searchWindow(query, now.toLocalDate())
                val matches = try {
                    val candidates = repository.eventsInRange(from, to, zone)
                    EventMatcher.match(
                        query = query,
                        candidates = candidates,
                        zone = zone,
                        now = now,
                        limit = if (bulk) EventMatcher.MAX_BULK_MATCHES else EventMatcher.MAX_MATCHES,
                    )
                } catch (e: SecurityException) {
                    note("I can't read your calendar — permission was revoked.")
                    return
                } catch (e: Exception) {
                    note("Couldn't search your calendar just now.")
                    return
                }

                // Naming the window in both outcomes. "I found none" and "I found none in the year
                // either side of today" are different claims, and only the second one is true.
                val searched = "Searched ${from.format(SEARCH_RANGE)} – ${to.format(SEARCH_RANGE)}"

                if (matches.isEmpty()) {
                    note("I couldn't find ${query.phrasing} on your calendar. $searched.")
                    return
                }
                // Read each match's row to find out whether it repeats. At most five local provider
                // reads, and it buys a real answer: a one-off is cancelled on one tap, and only a
                // genuine series is asked "this one, or all of them?".
                val cards = matches.map { event ->
                    CancelCard(
                        id = session.nextCardId(),
                        event = event,
                        recurring = isRecurring(event),
                    )
                }
                session.addCancelCards(cards)
                session.addEntry(
                    ChatEntry.Cancellations(
                        id = session.nextEntryId(),
                        cardIds = cards.map { it.id },
                        phrasing = query.phrasing,
                        searchedNote = searched,
                        bulk = bulk,
                    )
                )
            }
            is AssistantResult.Unparseable ->
                note("I couldn't work out what you wanted to cancel. Try naming the event.")
            is AssistantResult.Unavailable -> note(result.reason)
        }
    }

    private fun summariseGaps(
        gaps: List<com.EntropicSoftwareLab.Schedule.data.calendar.BusyBlock>,
        zone: ZoneId,
        alreadyFree: Boolean,
    ): String? {
        // When the whole window is free, listing "the gap" restates the verdict.
        if (alreadyFree || gaps.isEmpty()) return null
        val format = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        return gaps.take(3).joinToString(", ") { gap ->
            val from = java.time.Instant.ofEpochMilli(gap.startMillis).atZone(zone).format(format)
            val to = java.time.Instant.ofEpochMilli(gap.endMillis).atZone(zone).format(format)
            "$from–$to"
        }
    }

    // --- editing a draft before it is committed ---------------------------

    private fun updateDraft(cardId: Int, transform: (EventDraft) -> EventDraft) {
        session.updateCard(cardId) { card ->
            // A committed card is a record of what was written; editing it would make the card
            // disagree with the calendar.
            if (card.committed) card else card.copy(draft = transform(card.draft))
        }
        refreshConflictsAsync(cardId)
    }

    fun editTitle(cardId: Int, title: String) = updateDraft(cardId) { it.copy(title = title) }

    fun editDate(cardId: Int, date: LocalDate) = updateDraft(cardId) { it.copy(date = date) }

    fun editStartTime(cardId: Int, time: LocalTime) = updateDraft(cardId) { draft ->
        val duration = if (draft.startTime != null && draft.endTime != null) {
            java.time.Duration.between(draft.startTime, draft.endTime)
        } else {
            java.time.Duration.ofHours(1)
        }
        val minutes = if (duration.isNegative || duration.isZero) 60 else duration.toMinutes()
        draft.copy(startTime = time, endTime = time.plusMinutes(minutes))
    }

    fun editEndTime(cardId: Int, time: LocalTime) = updateDraft(cardId) { it.copy(endTime = time) }

    fun editLocation(cardId: Int, value: String) = updateDraft(cardId) {
        it.copy(location = value.takeIf(String::isNotBlank))
    }

    fun editNotes(cardId: Int, value: String) = updateDraft(cardId) {
        it.copy(description = value.takeIf(String::isNotBlank))
    }

    fun editRepeat(cardId: Int, freq: com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq?) =
        updateDraft(cardId) { it.copy(repeat = freq) }

    fun editAllDay(cardId: Int, allDay: Boolean) = updateDraft(cardId) { draft ->
        if (allDay) draft.copy(allDay = true, startTime = null, endTime = null)
        else draft.copy(
            allDay = false,
            startTime = draft.startTime ?: LocalTime.of(9, 0),
            endTime = draft.endTime ?: LocalTime.of(10, 0),
        )
    }

    fun discard(cardId: Int) {
        val card = session.card(cardId)
        session.removeCard(cardId)
        // Binning a card is the negative half of the signal. It only scores against a habit when
        // that habit is what shaped the proposal — see MemoryLearner.discard.
        card?.let {
            learn(Observation.Discarded(userText = it.sourceText, proposed = it.proposed.toObserved()))
        }
    }

    private fun refreshConflictsAsync(cardId: Int) {
        viewModelScope.launch { refreshConflicts(cardId) }
    }

    private suspend fun refreshConflicts(cardId: Int) {
        val card = session.card(cardId) ?: return
        val draft = card.draft
        if (draft.allDay || !draft.hasTimes) {
            session.updateCard(cardId) { it.copy(conflicts = emptyList()) }
            return
        }
        try {
            val zone = zoneProvider()
            val events = repository.eventsOnDay(draft.date, zone)
            val proposed = draft.toNewEvent(calendarId = 0L, zone = zone)
            val conflicts = proposed?.let { FreeBusy.conflictsWith(it, events, zone) }.orEmpty()
            session.updateCard(cardId) { it.copy(conflicts = conflicts) }
        } catch (e: Exception) {
            // Advisory only; a failed lookup must not block confirming.
            session.updateCard(cardId) { it.copy(conflicts = emptyList()) }
        }
    }

    // --- commit -----------------------------------------------------------

    /**
     * Writes one confirmed draft. This is the only path from AI output to a *new* calendar entry, and
     * it can only be reached from a tap on that card's Accept button.
     */
    fun confirm(cardId: Int) {
        val card = session.card(cardId) ?: return
        if (card.committed || card.committing) return
        val zone = zoneProvider()
        val calendarId = _state.value.selectedCalendarId ?: run {
            session.updateCard(cardId) { it.copy(error = "No writable calendar available.") }
            return
        }
        val newEvent = card.draft.toNewEvent(calendarId, zone) ?: run {
            session.updateCard(cardId) { it.copy(error = "This event is missing a time.") }
            return
        }

        session.updateCard(cardId) { it.copy(committing = true, error = null) }
        viewModelScope.launch {
            try {
                val id = repository.insertEvent(newEvent, zone)
                if (id == null) {
                    session.updateCard(cardId) {
                        it.copy(committing = false, error = "The calendar rejected it.")
                    }
                } else {
                    session.updateCard(cardId) {
                        it.copy(committing = false, committedEventId = id)
                    }
                    // Only a written event teaches. Learning from the draft instead would let one
                    // bad guess harden into the habit that produces the same bad guess next time.
                    learn(
                        Observation.Confirmed(
                            userText = card.sourceText,
                            proposed = card.proposed.toObserved(),
                            confirmed = card.draft.toObserved(),
                            soleEventInMessage = card.soleEventInMessage,
                        )
                    )
                }
            } catch (e: SecurityException) {
                session.updateCard(cardId) {
                    it.copy(committing = false, error = "Calendar write access was revoked.")
                }
            } catch (e: Exception) {
                session.updateCard(cardId) {
                    it.copy(committing = false, error = e.message ?: "Couldn't save it.")
                }
            }
        }
    }

    fun confirmAll(cardIds: List<Int>) {
        cardIds.mapNotNull { session.card(it) }
            .filterNot { it.committed || it.committing }
            .forEach { confirm(it.id) }
    }

    /** Removes an event that was just written, for when the card was confirmed by mistake. */
    fun undo(cardId: Int) {
        val card = session.card(cardId) ?: return
        val eventId = card.committedEventId ?: return
        viewModelScope.launch {
            runCatching { repository.deleteEvent(eventId) }
            session.updateCard(cardId) { it.copy(committedEventId = null) }
            // Undo has to reach the memory too, or the assistant keeps the lesson from an event the
            // user just deleted. The confirm above scored an agreement; this scores the matching
            // contradiction against the values that were actually written, which nets the habit back
            // below the confidence it needs to speak. The example pair goes entirely.
            learn(Observation.Discarded(userText = card.sourceText, proposed = card.draft.toObserved()))
            runCatching { memory.forgetCorrection(card.sourceText.trim()) }
        }
    }

    // --- cancelling an existing event -------------------------------------

    /**
     * Removes one event the user picked off the cancellation shortlist.
     *
     * [scope] only matters for a repeating event, and the screen only offers the choice once the row
     * has been read back and turns out to be recurring. Reading it here rather than trusting the
     * instance means the decision is made against the actual series, not against a guess.
     */
    fun cancelEvent(cardId: Int, scope: RecurrenceScope = RecurrenceScope.AllEvents) {
        val card = session.cancelCard(cardId) ?: return
        if (card.cancelling || card.cancelled) return
        session.updateCancelCard(cardId) { it.copy(cancelling = true, error = null) }
        viewModelScope.launch {
            try {
                val zone = zoneProvider()
                val details = repository.eventDetails(
                    eventId = card.event.eventId,
                    instanceStartMillis = card.event.startMillis,
                    instanceEndMillis = card.event.endMillis,
                    zone = zone,
                )
                if (details == null) {
                    session.updateCancelCard(cardId) {
                        it.copy(cancelling = false, error = "That event is already gone.")
                    }
                    return@launch
                }
                if (!details.isWritable) {
                    session.updateCancelCard(cardId) {
                        it.copy(cancelling = false, error = "That calendar is read-only.")
                    }
                    return@launch
                }
                val ok = repository.deleteEvent(details, scope, zone)
                session.updateCancelCard(cardId) {
                    if (ok) it.copy(cancelling = false, cancelled = true)
                    else it.copy(cancelling = false, error = "The calendar wouldn't remove it.")
                }
            } catch (e: SecurityException) {
                session.updateCancelCard(cardId) {
                    it.copy(cancelling = false, error = "Calendar write access was revoked.")
                }
            } catch (e: Exception) {
                session.updateCancelCard(cardId) {
                    it.copy(cancelling = false, error = e.message ?: "Couldn't cancel it.")
                }
            }
        }
    }

    /**
     * Removes every event still standing on one cancellation shortlist.
     *
     * The confirmation rule is unchanged, not weakened: the list of exactly what will go is on
     * screen, named, before anything happens, and the screen asks a second time before calling this.
     * What the user is spared is tapping the same button eleven times for one decision they already
     * made. A repeating event goes as a series — a bulk clean-up that left every recurrence behind
     * would have done the opposite of what was asked.
     */
    fun cancelAll(cardIds: List<Int>) {
        cardIds.forEach { id ->
            val card = session.cancelCard(id) ?: return@forEach
            if (!card.cancelled && !card.cancelling) cancelEvent(id, RecurrenceScope.AllEvents)
        }
    }

    /** Whether an event repeats, so the screen knows whether to ask about scope before cancelling. */
    private suspend fun isRecurring(event: EventInstance): Boolean = runCatching {
        repository.eventDetails(
            eventId = event.eventId,
            instanceStartMillis = event.startMillis,
            instanceEndMillis = event.endMillis,
            zone = zoneProvider(),
        )?.isRecurring == true
    }.getOrDefault(false)

    /**
     * Folds one tap into what the assistant knows. Wrapped: a memory write must never be able to
     * fail the calendar write that has already happened, and a lost lesson is worth strictly less
     * than a working confirm button.
     */
    private fun learn(observation: Observation) {
        viewModelScope.launch {
            runCatching { memory.record(observation, clock().toLocalDate().toEpochDay()) }
        }
    }

    // Deliberately no onCleared() close: the assistant is owned by AppContainer and shared, so
    // releasing it here would tear the engine down every time the user leaves this tab and pay the
    // multi-second reload on the way back.

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AssistantViewModel(
                    assistant = container.assistant,
                    repository = container.calendarRepository,
                    settings = container.settingsRepository,
                    memory = container.assistantMemoryRepository,
                    session = container.assistantSession,
                    contentResolver = container.appContext.contentResolver,
                    agentProvider = { container.geminiAgent() },
                    zoneProvider = { container.zone },
                ) as T
        }
    }
}

/**
 * The learnable half of a draft: the title, when it starts, how long it runs, where, and whether
 * it's all-day. The **date is dropped on purpose** — it is the one field that is never a habit, and
 * carrying it would let a stored lesson teach a stale date.
 *
 * A duration that comes out negative is an event crossing midnight, not a broken one, so it wraps
 * rather than being discarded; a zero-length event teaches nothing and becomes null.
 */
fun EventDraft.toObserved(): ObservedEvent {
    val start = startTime?.takeIf { !allDay }
    val minutes = if (start != null && endTime != null && !allDay) {
        val raw = java.time.Duration.between(start, endTime).toMinutes().toInt()
        (((raw % 1440) + 1440) % 1440).takeIf { it > 0 }
    } else {
        null
    }
    return ObservedEvent(
        title = title.trim(),
        startMinute = start?.let { it.hour * 60 + it.minute },
        durationMinutes = minutes,
        location = location?.trim()?.takeIf(String::isNotEmpty),
        allDay = allDay,
    )
}

/** null when the draft has no usable times, which the caller surfaces rather than guessing. */
fun EventDraft.toNewEvent(calendarId: Long, zone: ZoneId): NewEvent? {
    if (allDay) {
        val start = date.atStartOfDay()
        return NewEvent(
            calendarId = calendarId,
            title = title,
            start = start,
            end = date.plusDays(1).atStartOfDay(),
            allDay = true,
            description = description,
            location = location,
            rrule = rruleFor(start, zone, allDay = true),
        )
    }
    val from = startTime ?: return null
    val to = endTime ?: from.plusHours(1)
    val start = LocalDateTime.of(date, from)
    return NewEvent(
        calendarId = calendarId,
        title = title,
        start = start,
        end = LocalDateTime.of(date, to),
        allDay = false,
        description = description,
        location = location,
        rrule = rruleFor(start, zone, allDay = false),
    )
}

/**
 * The RRULE for a draft's [EventDraft.repeat], or null when it doesn't repeat.
 *
 * A frequency alone is enough: the series repeats from this event's own start, at interval 1, with
 * no end — which is all the card can express and all a spoken "every monday" ever says. Anything
 * richer belongs to the editor's repeat picker.
 */
private fun EventDraft.rruleFor(start: LocalDateTime, zone: ZoneId, allDay: Boolean): String? =
    repeat?.let { RecurrenceRule(it).toRRule(start, zone, allDay) }

/**
 * Whether a message that produced no event should be treated as a question worth looking up.
 *
 * Deliberately narrow. This gate decides whether to spend a second network request, and — more to
 * the point — whether a reply comes back that was *generated* rather than read off the user's own
 * calendar rows. A sentence that merely failed to parse as an event is not automatically a question,
 * so it needs a question mark or an opening word that can only start one.
 */
private fun looksLikeAQuestion(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.endsWith("?")) return true
    val first = trimmed.lowercase().substringBefore(' ')
    return first in QUESTION_WORDS
}

private val QUESTION_WORDS = setOf(
    "what", "what's", "whats", "when", "when's", "whens", "where", "where's", "wheres",
    "who", "who's", "whos", "why", "how", "which", "is", "are", "was", "were", "does", "do", "did",
)

/**
 * A title-only search, for the agent's `search_events` tool.
 *
 * Reuses [EventMatcher] rather than filtering on `contains`, so the agent gets the same ranking,
 * stop-word handling and quantifier stripping the cancellation path does. The date fields are null
 * because the tool carries its own range — the matcher is being used purely to score titles.
 */
private class AgentSearchQuery(
    override val titleQuery: String?,
) : com.EntropicSoftwareLab.Schedule.data.calendar.CancellationQueryLike {
    override val date: LocalDate? = null
    override val startTime: LocalTime? = null
    override val endTime: LocalTime? = null
}
