package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The confirmation card: what the assistant understood, laid out so it can be checked at a glance
 * and corrected in place.
 *
 * Nothing reaches the calendar until [onConfirm] fires. Every field is editable, anything the model
 * inferred rather than read is called out, and conflicts are shown before the decision rather than
 * discovered after it.
 *
 * The read-only summary shows only what is set. A card for "gym at 7" shouldn't carry four empty
 * rows for fields nobody mentioned — Edit is what opens those.
 */
@Composable
fun EventDraftCard(
    title: String,
    date: LocalDate,
    startTime: LocalTime?,
    endTime: LocalTime?,
    allDay: Boolean,
    location: String?,
    notes: String?,
    repeat: RecurrenceFreq?,
    assumption: String?,
    conflicts: List<EventInstance>,
    zone: ZoneId,
    committed: Boolean,
    committing: Boolean,
    error: String?,
    onTitleChange: (String) -> Unit,
    onLocationChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onRepeatChange: (RecurrenceFreq?) -> Unit,
    onDateClick: () -> Unit,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
    onAllDayChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    // Read-only by default; Edit flips the same card into its fields. Saveable so the mode survives a
    // scroll-off in a long conversation or a rotation, rather than snapping shut mid-correction.
    var editing by rememberSaveable { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }

    AppCard(modifier.fillMaxWidth(), elevated = true) {
        Column(
            Modifier.padding(d.spaceLg),
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            if (committed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = colors.greenText,
                        modifier = Modifier.size(d.iconSm),
                    )
                    Spacer(Modifier.width(d.spaceSm))
                    Text(
                        text = "Added to your calendar",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.greenText,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = summaryLine(date, startTime, endTime, allDay, repeat),
                    style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                    color = colors.textSecondary,
                )
                AppSecondaryButton(text = "Undo", onClick = onUndo)
                return@Column
            }

            if (editing) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                DraftFieldRow(label = "Date", value = date.format(DATE_FORMAT), onClick = onDateClick)

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("All day", style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                    Switch(
                        checked = allDay,
                        onCheckedChange = onAllDayChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.green),
                    )
                }

                if (!allDay) {
                    DraftFieldRow(
                        label = "Starts",
                        value = startTime?.format(TIME_FORMAT) ?: "—",
                        onClick = onStartClick,
                    )
                    DraftFieldRow(
                        label = "Ends",
                        value = endTime?.format(TIME_FORMAT) ?: "—",
                        onClick = onEndClick,
                    )
                }

                // Only the four frequencies, at interval 1 — the same range the model can extract.
                // Anything richer (every other week, until a date) belongs to the full editor, and
                // offering half of it here would be a worse version of that screen.
                Box {
                    DraftFieldRow(
                        label = "Repeats",
                        value = repeat.label(),
                        onClick = { repeatMenu = true },
                    )
                    DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Does not repeat") },
                            onClick = { onRepeatChange(null); repeatMenu = false },
                        )
                        RecurrenceFreq.entries.forEach { freq ->
                            DropdownMenuItem(
                                text = { Text(freq.label()) },
                                onClick = { onRepeatChange(freq); repeatMenu = false },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = location.orEmpty(),
                    onValueChange = onLocationChange,
                    label = { Text("Location") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = notes.orEmpty(),
                    onValueChange = onNotesChange,
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Read-only summary: the parse is here to be checked at a glance, and Edit is what
                // opens the fields. Keeps the card calm until there's actually something to change.
                Text(
                    text = title.ifBlank { "Untitled event" },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = summaryLine(date, startTime, endTime, allDay, repeat),
                    style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                    color = colors.textSecondary,
                )
                location?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                notes?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Anything the model filled in rather than read is stated plainly. A wrong guess the
            // user can see is a correction; a wrong guess they can't is a bad calendar entry.
            assumption?.let {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Outlined.Lightbulb,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(d.iconSm),
                    )
                    Spacer(Modifier.width(d.spaceSm))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }

            if (!allDay) {
                ConflictPanel(conflicts = conflicts, zone = zone)
                // The check looks at the first occurrence alone, so a repeating draft's later dates
                // are unexamined. Say so rather than imply they're clear.
                if (repeat != null && conflicts.isNotEmpty()) {
                    Text(
                        text = "Checked for the first occurrence only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }

            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }

            // Edit sits on its own full-width line above the decision: it's an optional detour, so it
            // gets the lighter outlined treatment and doesn't compete with the choice below it.
            AppTintedButton(
                text = if (editing) "Done" else "Edit",
                onClick = { editing = !editing },
                tint = colors.info,
                filled = false,
                modifier = Modifier.fillMaxWidth(),
            )
            // Reject and Accept carry equal visual weight on purpose. This is the gate that keeps AI
            // output out of the calendar, so the layout shouldn't lean on the user to accept.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            ) {
                AppTintedButton(
                    text = "Reject",
                    onClick = onDiscard,
                    tint = colors.error,
                    modifier = Modifier.weight(1f),
                )
                AppTintedButton(
                    text = if (committing) "Adding…" else "Accept",
                    onClick = onConfirm,
                    tint = colors.greenText,
                    enabled = !committing && title.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun RecurrenceFreq?.label(): String = when (this) {
    null -> "Does not repeat"
    RecurrenceFreq.Daily -> "Daily"
    RecurrenceFreq.Weekly -> "Weekly"
    RecurrenceFreq.Monthly -> "Monthly"
    RecurrenceFreq.Yearly -> "Yearly"
}

private fun summaryLine(
    date: LocalDate,
    startTime: LocalTime?,
    endTime: LocalTime?,
    allDay: Boolean,
    repeat: RecurrenceFreq?,
): String {
    val when_ = when {
        allDay -> "${date.format(DATE_FORMAT)} · all day"
        startTime != null && endTime != null ->
            "${date.format(DATE_FORMAT)} · ${startTime.format(TIME_FORMAT)}–${endTime.format(TIME_FORMAT)}"
        startTime != null -> "${date.format(DATE_FORMAT)} · ${startTime.format(TIME_FORMAT)}"
        else -> date.format(DATE_FORMAT)
    }
    // A repeat changes what the date means — from "on this day" to "from this day on" — so it
    // belongs on the same line rather than in a row the user might not reach.
    return if (repeat == null) when_ else "$when_ · repeats ${repeat.label().lowercase()}"
}

@Composable
private fun DraftFieldRow(label: String, value: String, onClick: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.touchTarget)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
            color = colors.textPrimary,
        )
    }
}
