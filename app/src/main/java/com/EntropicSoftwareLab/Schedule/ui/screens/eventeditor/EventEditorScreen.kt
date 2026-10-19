package com.EntropicSoftwareLab.Schedule.ui.screens.eventeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.EntropicSoftwareLab.Schedule.data.calendar.EventColorOption
import com.EntropicSoftwareLab.Schedule.data.calendar.GoogleCalendarColors
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceScope
import com.EntropicSoftwareLab.Schedule.data.calendar.RepeatSelection
import com.EntropicSoftwareLab.Schedule.data.calendar.label
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppButton
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.AppTintedButton
import com.EntropicSoftwareLab.Schedule.ui.components.ConflictPanel
import com.EntropicSoftwareLab.Schedule.ui.components.DatePickerSheet
import com.EntropicSoftwareLab.Schedule.ui.components.RepeatPickerDialog
import com.EntropicSoftwareLab.Schedule.ui.components.ScreenHeader
import com.EntropicSoftwareLab.Schedule.ui.components.TimePickerSheet
import com.EntropicSoftwareLab.Schedule.ui.components.asCalendarColor
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Which picker is currently open. One value, so two can never be up at once. */
private enum class OpenPicker { StartDate, StartTime, EndDate, EndTime, Repeat }

/**
 * Creating and editing an event.
 *
 * [seedDate]/[seedHour] pre-fill a new event from a tapped empty cell on the Today grid.
 * [editEventId] with its instance bounds opens an existing one instead. They only reach the
 * ViewModel's factory, so they apply once at creation and never fight the user's later edits.
 */
@Composable
fun EventEditorScreen(
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    seedDate: LocalDate? = null,
    seedHour: Int? = null,
    editEventId: Long? = null,
    editInstanceStart: Long = 0L,
    editInstanceEnd: Long = 0L,
) {
    val container = LocalAppContainer.current
    val vm: EventEditorViewModel = viewModel(
        factory = EventEditorViewModel.factory(
            container = container,
            initialDate = seedDate,
            initialStartTime = seedHour?.let { LocalTime.of(it, 0) },
            editEventId = editEventId,
            editInstanceStart = editInstanceStart,
            editInstanceEnd = editInstanceEnd,
        ),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = container.zone
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    var picker by remember { mutableStateOf<OpenPicker?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var showCalendarMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    LazyColumn(
        // The list ends at the top of the keyboard rather than running on underneath it, so the
        // field being typed in — Notes especially, which grows with every line — is scrolled up
        // into view instead of hidden. Safe only because AppNavHost has already consumed the
        // Scaffold's insets: this subtracts just the part of the keyboard above the navigation bar.
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(
            start = d.spaceLg, end = d.spaceLg, top = d.spaceXxl, bottom = d.spaceHuge,
        ),
        verticalArrangement = Arrangement.spacedBy(d.spaceLg),
    ) {
        item {
            ScreenHeader(
                title = if (state.editing) "Edit event" else "New event",
                trailing = {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                },
            )
        }

        if (state.readOnly) {
            item {
                Text(
                    text = "This calendar is read-only, so this event can't be changed here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            }
        }

        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(d.spaceLg),
                    verticalArrangement = Arrangement.spacedBy(d.spaceMd),
                ) {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = vm::setTitle,
                        label = { Text("Title") },
                        singleLine = true,
                        enabled = !state.readOnly,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "All day",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                        )
                        Switch(
                            checked = state.allDay,
                            onCheckedChange = vm::setAllDay,
                            enabled = !state.readOnly,
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.green),
                        )
                    }

                    // Starts and Ends each carry a date and a time. A single-date form silently
                    // truncates any event that spans midnight or several days — which, once editing
                    // exists, means opening someone's week-long holiday and saving it as one day.
                    WhenRow(
                        label = "Starts",
                        date = state.startDate.format(DATE_FORMAT),
                        time = if (state.allDay) null else state.startTime.format(TIME_FORMAT),
                        enabled = !state.readOnly,
                        onDateClick = { picker = OpenPicker.StartDate },
                        onTimeClick = { picker = OpenPicker.StartTime },
                    )
                    WhenRow(
                        label = "Ends",
                        date = state.endDate.format(DATE_FORMAT),
                        time = if (state.allDay) null else state.endTime.format(TIME_FORMAT),
                        enabled = !state.readOnly,
                        onDateClick = { picker = OpenPicker.EndDate },
                        onTimeClick = { picker = OpenPicker.EndTime },
                    )

                    Box {
                        FieldRow(
                            label = "Calendar",
                            value = state.selectedCalendar?.displayName ?: "—",
                            enabled = !state.readOnly && !state.editing,
                        ) { showCalendarMenu = true }
                        DropdownMenu(
                            expanded = showCalendarMenu,
                            onDismissRequest = { showCalendarMenu = false },
                        ) {
                            state.calendars.forEach { calendar ->
                                DropdownMenuItem(
                                    text = { Text("${calendar.displayName} · ${calendar.accountName}") },
                                    onClick = {
                                        vm.setCalendar(calendar.id)
                                        showCalendarMenu = false
                                    },
                                )
                            }
                        }
                    }

                    FieldRow(
                        label = "Repeats",
                        value = state.repeat.label(state.startDate),
                        enabled = !state.readOnly,
                    ) { picker = OpenPicker.Repeat }

                    ColorPickerRow(
                        selectedArgb = state.selectedColor,
                        colorsUsedToday = state.colorsUsedToday,
                        automaticArgb = state.automaticColor,
                        options = state.colorOptions,
                        originalArgb = state.details?.eventColor,
                        enabled = !state.readOnly,
                        onSelect = vm::setColor,
                    )

                    OutlinedTextField(
                        value = state.location,
                        onValueChange = vm::setLocation,
                        label = { Text("Location") },
                        singleLine = true,
                        enabled = !state.readOnly,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = vm::setNotes,
                        label = { Text("Notes") },
                        minLines = 2,
                        // Past this the field scrolls inside itself and keeps the line being typed
                        // on screen; a field taller than the space above the keyboard can't.
                        maxLines = 10,
                        enabled = !state.readOnly,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (!state.allDay) {
            item { ConflictPanel(conflicts = state.conflicts, zone = zone) }
            // The check only looks at this occurrence's own start/end, so for a repeating event it
            // speaks for one date alone — say so rather than imply every future date is clear.
            if (state.repeat != RepeatSelection.None) {
                item {
                    Text(
                        text = "Conflicts checked for this occurrence only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        state.error?.let { message ->
            item {
                Text(text = message, style = MaterialTheme.typography.bodyLarge, color = colors.error)
            }
        }

        item {
            AppButton(
                text = when {
                    state.saving -> "Saving…"
                    state.editing -> "Save changes"
                    else -> "Add to calendar"
                },
                onClick = vm::save,
                enabled = state.canSave,
            )
        }

        if (state.editing && !state.readOnly) {
            item {
                AppTintedButton(
                    text = "Cancel this event",
                    onClick = { confirmingDelete = true },
                    tint = colors.error,
                    filled = false,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Text(
                text = "Changes sync to your account in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }

    when (picker) {
        OpenPicker.StartDate -> DatePickerSheet(
            initial = state.startDate,
            onDismiss = { picker = null },
            onPick = { vm.setStartDate(it); picker = null },
        )
        OpenPicker.EndDate -> DatePickerSheet(
            initial = state.endDate,
            onDismiss = { picker = null },
            onPick = { vm.setEndDate(it); picker = null },
        )
        OpenPicker.StartTime -> TimePickerSheet(
            initial = state.startTime,
            onDismiss = { picker = null },
            onPick = { vm.setStartTime(it); picker = null },
        )
        OpenPicker.EndTime -> TimePickerSheet(
            initial = state.endTime,
            onDismiss = { picker = null },
            onPick = { vm.setEndTime(it); picker = null },
        )
        OpenPicker.Repeat -> RepeatPickerDialog(
            current = state.repeat,
            anchor = state.startDate,
            earliestEnd = minOf(state.details?.seriesStart?.toLocalDate() ?: state.startDate, state.startDate),
            onDismiss = { picker = null },
            onPick = { vm.setRepeat(it); picker = null },
        )
        null -> Unit
    }

    if (confirmingDelete) {
        ConfirmCancelDialog(
            title = state.title,
            onDismiss = { confirmingDelete = false },
            onConfirm = { confirmingDelete = false; vm.delete() },
        )
    }

    state.pendingWrite?.let { pending ->
        RecurrenceScopeDialog(
            deleting = pending == PendingWrite.Delete,
            onDismiss = vm::dismissScopePrompt,
            onPick = vm::confirmScope,
        )
    }
}

/**
 * The "this / this and following / all" choice.
 *
 * Shown only for a repeating event, and only once the user has already committed to the action — it
 * asks how far the change reaches, not whether to make it. Wording differs between saving and
 * cancelling because "All events" deleting a two-year standing meeting deserves to read as what it
 * is.
 */
@Composable
private fun RecurrenceScopeDialog(
    deleting: Boolean,
    onDismiss: () -> Unit,
    onPick: (RecurrenceScope) -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Dialog(onDismissRequest = onDismiss) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(d.spaceLg)) {
                Text(
                    text = if (deleting) "Cancel which events?" else "Save changes to which events?",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    text = "This event repeats.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(d.spaceMd))

                ScopeRow("This event", onClick = { onPick(RecurrenceScope.ThisEvent) })
                ScopeRow(
                    "This and following events",
                    onClick = { onPick(RecurrenceScope.ThisAndFollowing) },
                )
                ScopeRow(
                    if (deleting) "All events in the series" else "All events",
                    onClick = { onPick(RecurrenceScope.AllEvents) },
                )

                Spacer(Modifier.height(d.spaceSm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Back", color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeRow(label: String, onClick: () -> Unit) {
    val d = AppThemeTokens.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.touchTarget)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = AppThemeTokens.colors.textPrimary,
        )
    }
}

@Composable
private fun ConfirmCancelDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Dialog(onDismissRequest = onDismiss) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(d.spaceLg)) {
                Text(
                    text = "Cancel this event?",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    text = "\"${title.ifBlank { "Untitled event" }}\" will be removed from your " +
                        "calendar and from every device it syncs to.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(d.spaceMd))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                ) {
                    AppTintedButton(
                        text = "Keep it",
                        onClick = onDismiss,
                        tint = colors.textSecondary,
                        filled = false,
                        modifier = Modifier.weight(1f),
                    )
                    AppTintedButton(
                        text = "Cancel event",
                        onClick = onConfirm,
                        tint = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** A "Starts"/"Ends" row: label on the left, date and (for a timed event) time on the right. */
@Composable
private fun WhenRow(
    label: String,
    date: String,
    time: String?,
    enabled: Boolean,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(d.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = date,
                style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                color = colors.textPrimary,
                modifier = Modifier.clickable(enabled = enabled, onClick = onDateClick),
            )
            if (time != null) {
                Spacer(Modifier.width(d.spaceMd))
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                    color = colors.textPrimary,
                    modifier = Modifier.clickable(enabled = enabled, onClick = onTimeClick),
                )
            }
        }
    }
}

/**
 * Swatches for the event's colour: Automatic, then the Google Calendar colours this app can set.
 *
 * Google's, not the app's (2026-09-29, the user's call). A pick writes the account's own
 * `EVENT_COLOR_KEY`, so Google Calendar shows exactly the same colour on every device.
 *
 * Only colours the account exposes to other apps are shown — on a Google account the classic 11,
 * named by key ([GoogleCalendarColors.nameForKey]). The other 13 of Google's 24 can only be set in
 * Google's own apps: writing one from here would revert on sync, so they aren't offered. An event
 * already wearing one of them (set in Google Calendar) shows it as its own swatch, [originalArgb],
 * and keeps it on save unless another colour is picked — see `EventEditorUiState.colorUnchanged`.
 *
 * A colour another event already wears today is **hinted, not blocked**.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerRow(
    selectedArgb: Int?,
    /** Resolved colours another event on this day wears — see `EventEditorUiState.colorsUsedToday`. */
    colorsUsedToday: Set<Int>,
    /** What "Automatic" would give this event today; null until the editor has worked it out. */
    automaticArgb: Int?,
    /** The selected calendar's account colours; empty while loading or for a local calendar. */
    options: List<EventColorOption>,
    /** The colour the event had when opened, or null for a new or uncoloured event. */
    originalArgb: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    val ordered = remember(options) {
        val rank = GoogleCalendarColors.PALETTE_ORDER.withIndex().associate { (i, name) -> name to i }
        options.filter { it.available }.sortedBy { rank[it.name] ?: Int.MAX_VALUE }
    }
    fun sameRgb(a: Int, b: Int) = a and 0xFFFFFF == b and 0xFFFFFF
    val selectedOption = selectedArgb?.let { argb -> ordered.firstOrNull { sameRgb(it.argb, argb) } }
    // The event's own colour from Google Calendar when it isn't one this app can set.
    val keptOriginal = originalArgb?.takeIf { argb -> ordered.none { sameRgb(it.argb, argb) } }
    fun nameOf(argb: Int) =
        GoogleCalendarColors.nameFor(argb) ?: "#%06X".format(Locale.US, argb and 0xFFFFFF)
    val selectedName = when {
        selectedArgb == null -> "Automatic"
        selectedOption != null -> selectedOption.label
        else -> nameOf(selectedArgb)
    }

    Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
            Text("Colour", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
            Text(
                text = selectedName,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
            )
        }
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            // An event nobody colours is shown in one of the app's automatic colours, chosen so it
            // differs from the others that day; the swatch previews the one it would get. Google
            // Calendar shows it in the calendar's own colour.
            Swatch(
                color = Color(
                    GoogleCalendarColors.displayShade(
                        automaticArgb ?: EventColorPalette.assignmentOrder.first().argb,
                    ),
                ),
                isSelected = selectedArgb == null,
                // Never hinted: which colour this event lands on depends on the rest of the day,
                // so this screen can't say whether it collides with anything.
                usedToday = false,
                label = "Automatic colour",
                enabled = enabled,
                onClick = { onSelect(null) },
            )
            keptOriginal?.let { argb ->
                Swatch(
                    color = Color(GoogleCalendarColors.displayShade(argb)),
                    isSelected = selectedArgb != null && sameRgb(selectedArgb, argb),
                    usedToday = false,
                    label = "${nameOf(argb)}, set in Google Calendar",
                    enabled = enabled,
                    onClick = { onSelect(argb) },
                )
            }
            ordered.forEach { option ->
                Swatch(
                    color = Color(GoogleCalendarColors.displayShade(option.argb)),
                    isSelected = option === selectedOption,
                    usedToday = GoogleCalendarColors.displayShade(option.argb) in colorsUsedToday,
                    label = option.label,
                    enabled = enabled,
                    onClick = { onSelect(option.argb) },
                )
            }
        }
        Text(
            text = when {
                selectedArgb == null ->
                    "Automatic picks a colour that differs from the others that day. Google " +
                        "Calendar shows it in the calendar's colour."
                selectedOption == null && keptOriginal != null ->
                    "Set in Google Calendar. It stays as it is unless you pick another; only " +
                        "Google's own apps can set this one."
                selectedOption == null ->
                    "Google Calendar shows its closest colour to this one."
                else -> "Same colour in Google Calendar."
            } + if (ordered.isEmpty()) " This calendar offers no colours of its own." else "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun Swatch(
    color: Color,
    isSelected: Boolean,
    /** Another event today already wears this. A hint only — the swatch stays selectable. */
    usedToday: Boolean,
    label: String,
    enabled: Boolean,
    showPlus: Boolean = false,
    onClick: () -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val description = if (usedToday) "$label, already used today" else label
    Box(
        modifier = Modifier
            .size(d.touchTarget)
            .alpha(if (enabled) 1f else 0.3f)
            .selectable(selected = isSelected, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (isSelected) 2.dp else d.hairlineWidth,
                    color = if (isSelected) colors.greenText else colors.divider,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // A hollow ring inside the swatch: legible on any hue, and clearly a mark rather
                // than the strike-through that used to mean "you can't have this".
                usedToday -> Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, colors.background, CircleShape),
                )
                showPlus -> Text(
                    text = "+",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.touchTarget)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
            color = if (enabled) colors.textPrimary else colors.textSecondary,
        )
    }
}
