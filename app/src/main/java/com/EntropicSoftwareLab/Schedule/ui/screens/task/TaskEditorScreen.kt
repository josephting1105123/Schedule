package com.EntropicSoftwareLab.Schedule.ui.screens.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppButton
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.AppTintedButton
import com.EntropicSoftwareLab.Schedule.ui.components.DatePickerSheet
import com.EntropicSoftwareLab.Schedule.ui.components.ScreenHeader
import com.EntropicSoftwareLab.Schedule.ui.components.TimePickerSheet
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Creating and editing a task — a title, a deadline, and optional notes.
 *
 * Deliberately not a mode of [com.EntropicSoftwareLab.Schedule.ui.screens.eventeditor.EventEditorScreen],
 * for the same reason the birthday form isn't: a task has no end time, no calendar, no colour and no
 * recurrence, so folding it in would mean carrying a "which kind" flag through a screen most of whose
 * fields don't apply. It never writes to CalendarContract.
 *
 * There is no "done" checkbox here on purpose. Ticking a task off is what the row on the Today page
 * is for, and a second control for the same bit inside the same flow gives the user two places to
 * look and the app two things to keep in step.
 */
@Composable
fun TaskEditorScreen(
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    editTaskId: Long? = null,
) {
    val container = LocalAppContainer.current
    val vm: TaskEditorViewModel = viewModel(
        factory = TaskEditorViewModel.factory(container, editTaskId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    LazyColumn(
        // Ends at the keyboard's top edge so the field being typed in stays visible — see
        // EventEditorScreen.
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
                title = if (state.editing) "Edit task" else "New task",
                trailing = {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                },
            )
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
                        label = { Text("Task") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FieldRow(label = "Due", onClick = { showDatePicker = true }) {
                        Text(
                            text = state.due.format(DATE_FORMAT),
                            style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                            color = colors.textPrimary,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setHasTime(!state.hasTime) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.hasTime,
                            onCheckedChange = vm::setHasTime,
                            colors = CheckboxDefaults.colors(checkedColor = colors.green),
                        )
                        Spacer(Modifier.width(d.spaceSm))
                        Text(
                            "Due at a specific time",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                        )
                    }

                    if (state.hasTime) {
                        FieldRow(label = "Time", onClick = { showTimePicker = true }) {
                            Text(
                                text = state.time.format(TIME_FORMAT),
                                style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                                color = colors.textPrimary,
                            )
                        }
                    } else {
                        Text(
                            text = "Without a time, this stays off the hour grid.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }

                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = vm::setNotes,
                        label = { Text("Notes") },
                        minLines = 2,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Text(
                text = "A task has a deadline, not a span — it never blocks out an hour and never " +
                    "counts as a clash.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
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
                    else -> "Add task"
                },
                onClick = vm::save,
                enabled = state.canSave,
            )
        }

        if (state.editing) {
            item {
                AppTintedButton(
                    text = "Delete this task",
                    onClick = { confirmingDelete = true },
                    tint = colors.error,
                    filled = false,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showDatePicker) {
        DatePickerSheet(
            initial = state.due,
            onDismiss = { showDatePicker = false },
            onPick = { vm.setDue(it); showDatePicker = false },
        )
    }

    if (showTimePicker) {
        TimePickerSheet(
            initial = state.time,
            onDismiss = { showTimePicker = false },
            onPick = { vm.setTime(it); showTimePicker = false },
        )
    }

    if (confirmingDelete) {
        ConfirmDeleteDialog(
            title = state.title,
            onDismiss = { confirmingDelete = false },
            onConfirm = { confirmingDelete = false; vm.delete() },
        )
    }
}

/** Label on the left, current value on the right, the whole row opening a picker. */
@Composable
private fun FieldRow(label: String, onClick: () -> Unit, value: @Composable () -> Unit) {
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
        value()
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Dialog(onDismissRequest = onDismiss) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(d.spaceLg)) {
                Text(
                    text = "Delete this task?",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    text = "\"${title.ifBlank { "Untitled task" }}\" will be removed.",
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
                        text = "Delete",
                        onClick = onConfirm,
                        tint = colors.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
