package com.EntropicSoftwareLab.Schedule.ui.screens.assistant

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.EntropicSoftwareLab.Schedule.ai.AssistantState
import com.EntropicSoftwareLab.Schedule.ai.AttachmentReader
import com.EntropicSoftwareLab.Schedule.ai.AvailabilityAnswer
import com.EntropicSoftwareLab.Schedule.ai.PendingAttachment
import com.EntropicSoftwareLab.Schedule.ai.CancelCard
import com.EntropicSoftwareLab.Schedule.ai.ChatEntry
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceScope
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.AppSecondaryButton
import com.EntropicSoftwareLab.Schedule.ui.components.CancelEventCard
import com.EntropicSoftwareLab.Schedule.ui.components.DatePickerSheet
import com.EntropicSoftwareLab.Schedule.ui.components.EventDraftCard
import com.EntropicSoftwareLab.Schedule.ui.components.EventRow
import com.EntropicSoftwareLab.Schedule.ui.components.TimePickerSheet
import com.EntropicSoftwareLab.Schedule.ui.components.UpdateEventCard
import com.EntropicSoftwareLab.Schedule.ui.components.glassSurface
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Which picker is open, and for which card. */
private sealed interface ActivePicker {
    data class Date(val cardId: Int, val initial: LocalDate) : ActivePicker
    data class Start(val cardId: Int, val initial: LocalTime) : ActivePicker
    data class End(val cardId: Int, val initial: LocalTime) : ActivePicker
}

/**
 * The assistant, as a conversation.
 *
 * A transcript rather than a form because the old single-shot layout threw away the previous answer
 * on every send: two events described in two messages meant the first card vanished before it could
 * be confirmed. Keeping the history means a card stays put until it is accepted or rejected, and the
 * user can see what they asked alongside what they got.
 *
 * Cards are not rendered inline in their entry. Entries hold ids; the cards themselves live in one
 * flat list in the session, which is what stops the same draft existing twice with two different
 * edit states.
 */
@Composable
fun AssistantScreen(
    onOpenSettings: () -> Unit,
    onManualAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: AssistantViewModel = viewModel(factory = AssistantViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = container.zone
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    var picker by remember { mutableStateOf<ActivePicker?>(null) }
    var scopeForCard by remember { mutableStateOf<Int?>(null) }
    var updateScopeForCard by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    // OpenMultipleDocuments, not GetContent: a photographed timetable is often several pages, and
    // the returned URIs stay readable long enough to send.
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.addAttachments(uris) }

    // Follow the conversation down as it grows. Keyed on the last entry rather than the count so a
    // card changing state in place doesn't yank the list.
    LaunchedEffect(state.entries.lastOrNull()?.id, state.thinking) {
        val target = state.entries.size + if (state.thinking) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    // The composer is anchored to the bottom of a box that fills, rather than pushed there by a
    // weight. A weight only reaches the bottom if the column's height came out right; an anchor is
    // at the bottom of whatever height it gets. That is the difference between "the keyboard leaves
    // an odd gap" and "the composer is at the top of the screen under the clock".
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }

    Box(modifier.fillMaxSize().imePadding()) {
        // Long-press anywhere in the transcript to select and copy, including part of one message.
        // Wrapping the whole list rather than each bubble is what lets a selection run across a
        // question and the answer under it, which is usually what someone is copying.
        SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = d.spaceLg,
                end = d.spaceLg,
                top = d.spaceXxl,
                // Measured, not guessed: the composer grows with attachment chips and with a
                // multi-line message, and a fixed inset would bury the newest card behind it.
                bottom = d.spaceLg + composerHeight,
            ),
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            if (state.entries.isEmpty()) {
                item { EmptyConversation(cloudActive = state.cloudActive) }
            }

            if (state.assistantState is AssistantState.NoKey) {
                item { NoKeyCard(onOpenSettings = onOpenSettings) }
            }

            (state.assistantState as? AssistantState.Failed)?.let { failed ->
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(d.spaceLg)) {
                            Text(
                                text = "The model couldn't start",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                            )
                            Spacer(Modifier.height(d.spaceXs))
                            Text(
                                text = failed.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }

            items(state.entries.size, key = { state.entries[it].id }) { index ->
                Entry(
                    entry = state.entries[index],
                    state = state,
                    vm = vm,
                    zone = zone,
                    onManualAdd = onManualAdd,
                    onPickDate = { id, initial -> picker = ActivePicker.Date(id, initial) },
                    onPickStart = { id, initial -> picker = ActivePicker.Start(id, initial) },
                    onPickEnd = { id, initial -> picker = ActivePicker.End(id, initial) },
                    onAskScope = { scopeForCard = it },
                    onAskUpdateScope = { updateScopeForCard = it },
                )
            }

            if (state.thinking) {
                item { ThinkingRow() }
            }
        }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { composerHeightPx = it.height },
        ) {
        Composer(
            value = state.input,
            enabled = state.assistantState is AssistantState.Ready,
            canSend = state.canSend,
            canAttach = state.canAttach,
            attachments = state.attachments,
            onValueChange = vm::setInput,
            onSend = vm::submit,
            onAttach = { attachLauncher.launch(AttachmentReader.ACCEPTED_MIME_TYPES) },
            onRemoveAttachment = vm::removeAttachment,
            onClear = vm::clearConversation.takeIf { state.entries.isNotEmpty() },
        )
        }
    }

    when (val active = picker) {
        is ActivePicker.Date -> DatePickerSheet(
            initial = active.initial,
            onDismiss = { picker = null },
            onPick = { vm.editDate(active.cardId, it); picker = null },
        )
        is ActivePicker.Start -> TimePickerSheet(
            initial = active.initial,
            onDismiss = { picker = null },
            onPick = { vm.editStartTime(active.cardId, it); picker = null },
        )
        is ActivePicker.End -> TimePickerSheet(
            initial = active.initial,
            onDismiss = { picker = null },
            onPick = { vm.editEndTime(active.cardId, it); picker = null },
        )
        null -> Unit
    }

    scopeForCard?.let { cardId ->
        CancelScopeDialog(
            onDismiss = { scopeForCard = null },
            onPick = { scope ->
                scopeForCard = null
                vm.cancelEvent(cardId, scope)
            },
        )
    }

    updateScopeForCard?.let { cardId ->
        CancelScopeDialog(
            onDismiss = { updateScopeForCard = null },
            onPick = { scope ->
                updateScopeForCard = null
                vm.applyUpdate(cardId, scope)
            },
        )
    }
}

/** One line of the conversation. */
@Composable
private fun Entry(
    entry: ChatEntry,
    state: AssistantUiState,
    vm: AssistantViewModel,
    zone: ZoneId,
    onManualAdd: () -> Unit,
    onPickDate: (Int, LocalDate) -> Unit,
    onPickStart: (Int, LocalTime) -> Unit,
    onPickEnd: (Int, LocalTime) -> Unit,
    onAskScope: (Int) -> Unit,
    onAskUpdateScope: (Int) -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    when (entry) {
        is ChatEntry.User -> UserBubble(entry.text, entry.attachmentNames)

        is ChatEntry.Updates -> {
            val cards = entry.cardIds.mapNotNull { state.updateCard(it) }
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                cards.forEach { card ->
                    UpdateEventCard(
                        card = card,
                        zone = zone,
                        onApply = {
                            // Same question the cancel path asks, for the same reason: "this one or
                            // all of them" has no default that is right more than half the time.
                            if (card.recurring) onAskUpdateScope(card.id)
                            else vm.applyUpdate(card.id, RecurrenceScope.AllEvents)
                        },
                        onDiscard = { vm.discardUpdate(card.id) },
                    )
                }
            }
        }

        is ChatEntry.Fact -> Column(verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
            )
            Text(
                // Stated either way. An answer looked up and an answer recalled read identically,
                // and only one of them is safe to act on.
                text = if (entry.sources.isEmpty()) "Answered without searching — check it."
                else "Sources: ${entry.sources.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        is ChatEntry.Note -> Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
        )

        is ChatEntry.Answer -> AvailabilityCard(answer = entry.answer, zone = zone)

        is ChatEntry.Unparseable -> UnparseableCard(onManualAdd = onManualAdd)

        is ChatEntry.Drafts -> {
            val cards = entry.cardIds.mapNotNull { state.card(it) }
            if (cards.isEmpty()) {
                // Every card in the reply was rejected. Say so rather than leaving the user's
                // message answered by nothing.
                Text(
                    text = "Discarded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                return
            }
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                val pending = cards.count { !it.committed }
                if (cards.size > 1 && pending > 1) {
                    AppSecondaryButton(
                        text = "Accept all $pending",
                        onClick = { vm.confirmAll(entry.cardIds) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                cards.forEach { card ->
                    EventDraftCard(
                        title = card.draft.title,
                        date = card.draft.date,
                        startTime = card.draft.startTime,
                        endTime = card.draft.endTime,
                        allDay = card.draft.allDay,
                        location = card.draft.location,
                        notes = card.draft.description,
                        repeat = card.draft.repeat,
                        assumption = card.draft.assumption,
                        conflicts = card.conflicts,
                        zone = zone,
                        committed = card.committed,
                        committing = card.committing,
                        error = card.error,
                        onTitleChange = { vm.editTitle(card.id, it) },
                        onLocationChange = { vm.editLocation(card.id, it) },
                        onNotesChange = { vm.editNotes(card.id, it) },
                        onRepeatChange = { vm.editRepeat(card.id, it) },
                        onDateClick = { onPickDate(card.id, card.draft.date) },
                        onStartClick = {
                            onPickStart(card.id, card.draft.startTime ?: LocalTime.of(9, 0))
                        },
                        onEndClick = {
                            onPickEnd(card.id, card.draft.endTime ?: LocalTime.of(10, 0))
                        },
                        onAllDayChange = { vm.editAllDay(card.id, it) },
                        onConfirm = { vm.confirm(card.id) },
                        onDiscard = { vm.discard(card.id) },
                        onUndo = { vm.undo(card.id) },
                    )
                }
            }
        }

        is ChatEntry.Cancellations -> {
            val cards = entry.cardIds.mapNotNull { state.cancelCard(it) }
            val remaining = cards.count { !it.cancelled }
            // Scoped to this entry, so scrolling it out of the transcript and back disarms it
            // rather than leaving a one-tap bulk delete waiting halfway up the conversation.
            var armed by remember(entry.id) { mutableStateOf(false) }

            Column(verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                Text(
                    text = if (cards.size == 1) "I found this for ${entry.phrasing}."
                    else "I found ${cards.size} things that could be ${entry.phrasing}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
                entry.searchedNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                cards.forEach { card ->
                    CancelEventCard(
                        card = card,
                        zone = zone,
                        // A one-off has nothing to scope, so it goes straight through. Asking
                        // "this event or all of them?" about an event with one occurrence is a
                        // question with one real answer and three buttons.
                        onCancel = {
                            if (card.recurring) onAskScope(card.id)
                            else vm.cancelEvent(card.id, RecurrenceScope.AllEvents)
                        },
                    )
                }

                // Offered only when they asked for all of something. Two taps, because this is the
                // one control in the app that removes several events at once and none of it undoes.
                if (entry.bulk && remaining > 1) {
                    AppSecondaryButton(
                        text = if (armed) "Tap again to remove all $remaining" else "Remove all $remaining",
                        onClick = {
                            if (armed) {
                                vm.cancelAll(entry.cardIds)
                                armed = false
                            } else {
                                armed = true
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The user's own message.
 *
 * Right-aligned and filled, so the eye can find who said what without reading. Capped at 85% width
 * rather than wrapping edge to edge, which is what keeps a long message legible as one turn.
 */
@Composable
private fun UserBubble(text: String, attachmentNames: List<String> = emptyList()) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    // A file sent without a caption is still a turn in the conversation. Naming it is what stops
    // the reply appearing to answer nothing.
    val body = when {
        attachmentNames.isEmpty() -> text
        text.isBlank() -> attachmentNames.joinToString(", ")
        else -> "$text\n${attachmentNames.joinToString(", ")}"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(d.radiusLg))
                .background(colors.accentMuted)
                .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.greenText,
            )
        }
    }
}

@Composable
private fun ThinkingRow() {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(d.iconSm),
            color = colors.green,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(d.spaceSm))
        Text(
            text = "Thinking it through…",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
        )
    }
}

/**
 * The input row: a floating glass capsule, not a full-width bar flush with the bottom edge.
 *
 * Send is an icon button beside the field rather than a full-width button below it: the field is the
 * whole screen's subject, and a 56dp bar under it would push the conversation up by that much on
 * every frame for an action that's one tap either way.
 *
 * The capsule margin and its own bottom padding are what make it float — `AppNavHost` already
 * reserves the navigation-bar inset via `consumeWindowInsets`, so this must not also call
 * `navigationBarsPadding()`: that double-count is what once threw this composer to the top of the
 * screen with the keyboard open.
 */
@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    canSend: Boolean,
    canAttach: Boolean,
    attachments: List<PendingAttachment>,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Uri) -> Unit,
    onClear: (() -> Unit)?,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = d.spaceMd, vertical = d.spaceSm)
            .glassSurface(radius = d.radiusPill, elevated = true)
            .padding(horizontal = d.spaceLg, vertical = d.spaceSm),
    ) {
        onClear?.let {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = it) {
                    Text("Clear conversation", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
        }

        // Above the field, not inside it: what is about to be sent stays legible while the text is
        // being typed, and each one can be taken back off without clearing the message.
        attachments.forEach { attachment ->
            AttachmentChip(attachment = attachment, onRemove = { onRemoveAttachment(attachment.uri) })
            Spacer(Modifier.height(d.spaceXs))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAttach, enabled = enabled && canAttach) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = "Attach a file",
                    tint = if (enabled && canAttach) colors.textPrimary else colors.textSecondary,
                )
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                // A worked example rather than a description of the field. The empty state above
                // already lists what it can do; what a first-time user needs here is the shape of a
                // sentence that works, and it has to fit on one line at this width.
                placeholder = { Text("Dinner with mum Friday 7pm", maxLines = 1) },
                enabled = enabled,
                maxLines = 5,
                // Transparent and borderless: the capsule itself is the only visible edge, so a
                // Material outline drawn inside it would read as a second, smaller box.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(d.spaceSm))
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) colors.greenText else colors.textSecondary,
                )
            }
        }

        // Said once, next to the disabled button, rather than after the user has picked a file.
        if (!canAttach) {
            Text(
                text = "Files need a Gemini key.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** One queued file: what it is, how big, and a way to take it back off. */
@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg))
            .background(colors.accentMuted)
            .padding(start = d.spaceMd, end = d.spaceXs, top = d.spaceXs, bottom = d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 1,
            )
            attachment.sizeLabel.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove ${attachment.name}",
                tint = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun EmptyConversation(cloudActive: Boolean) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = d.spaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "What's happening?",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(d.spaceSm))
        Text(
            text = "Describe an event to add it, ask whether you're free, or say what to cancel. " +
                "Nothing reaches your calendar until you tap.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (cloudActive) {
            Spacer(Modifier.height(d.spaceMd))
            Text(
                text = "Running in the cloud with your Gemini key.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** Asks how far a cancellation reaches, for an event that turns out to repeat. */
@Composable
private fun CancelScopeDialog(onDismiss: () -> Unit, onPick: (RecurrenceScope) -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(d.spaceLg)) {
                Text(
                    text = "Cancel which events?",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    text = "This event repeats. Choose how far the cancellation reaches.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(d.spaceMd))
                listOf(
                    "This event" to RecurrenceScope.ThisEvent,
                    "This and following events" to RecurrenceScope.ThisAndFollowing,
                    "All events in the series" to RecurrenceScope.AllEvents,
                ).forEach { (label, scope) ->
                    TextButton(
                        onClick = { onPick(scope) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(d.spaceSm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Keep it", color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** Shown while no Gemini key is set — the assistant has nothing to send a request to. */
@Composable
private fun NoKeyCard(onOpenSettings: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(d.spaceLg)) {
            Text(
                text = "No Gemini key set",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(d.spaceXs))
            Text(
                text = "The assistant needs a Gemini API key to read what you type. Add one in " +
                    "Settings — everything else in the app works without it.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(d.spaceMd))
            AppSecondaryButton(text = "Open settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun AvailabilityCard(answer: AvailabilityAnswer, zone: ZoneId) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    AppCard(Modifier.fillMaxWidth(), elevated = true) {
        Column(Modifier.padding(d.spaceLg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (answer.isFree) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = if (answer.isFree) colors.greenText else colors.warning,
                    modifier = Modifier.size(d.iconMd),
                )
                Spacer(Modifier.width(d.spaceSm))
                Text(
                    text = if (answer.isFree) "You're free" else "You're busy",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (answer.isFree) colors.greenText else colors.warning,
                )
            }
            Spacer(Modifier.height(d.spaceXs))
            Text(
                text = answer.query.phrasing,
                style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                color = colors.textSecondary,
            )

            if (answer.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(d.spaceMd))
                answer.conflicts.forEach { EventRow(event = it, zone = zone) }
            }

            answer.freeGapsSummary?.let { gaps ->
                Spacer(Modifier.height(d.spaceSm))
                Text(
                    text = "Free within that window: $gaps",
                    style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun UnparseableCard(onManualAdd: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(d.spaceLg)) {
            Text(
                text = "I couldn't turn that into an event",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(d.spaceXs))
            Text(
                text = "Try rephrasing with a clearer date and time, or add it by hand.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(d.spaceMd))
            AppSecondaryButton(text = "Add it manually", onClick = onManualAdd)
        }
    }
}
