package com.EntropicSoftwareLab.Schedule.ui.screens.birthday

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
import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayRepository
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppButton
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.AppTintedButton
import com.EntropicSoftwareLab.Schedule.ui.components.DatePickerSheet
import com.EntropicSoftwareLab.Schedule.ui.components.ScreenHeader
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/**
 * Creating and editing a birthday — name and date, nothing else.
 *
 * Deliberately not a smaller mode of [com.EntropicSoftwareLab.Schedule.ui.screens.eventeditor.EventEditorScreen]:
 * a birthday has no calendar to pick, no colour and no recurrence to choose — the repository
 * writes it to Google as a yearly all-day event on its own. [seedDate] pre-fills the date from wherever "+" was tapped;
 * [editBirthdayId] instead loads an existing one. Both only reach the ViewModel's factory, so they
 * apply once at creation and never fight a later edit.
 */
@Composable
fun BirthdayEditorScreen(
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    seedDate: LocalDate? = null,
    editBirthdayId: Long? = null,
) {
    val container = LocalAppContainer.current
    val vm: BirthdayEditorViewModel = viewModel(
        factory = BirthdayEditorViewModel.factory(container, seedDate, editBirthdayId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    var showDatePicker by remember { mutableStateOf(false) }
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
                title = if (state.editing) "Edit birthday" else "New birthday",
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
                        value = state.name,
                        onValueChange = vm::setName,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(d.touchTarget)
                            .clickable { showDatePicker = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Date", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                        Text(
                            text = state.date.format(DATE_FORMAT),
                            style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                            color = colors.textPrimary,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setIncludeYear(!state.includeYear) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.includeYear,
                            onCheckedChange = vm::setIncludeYear,
                            colors = CheckboxDefaults.colors(checkedColor = colors.green),
                        )
                        Spacer(Modifier.width(d.spaceSm))
                        Text(
                            "Include birth year",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                        )
                    }
                    if (!state.includeYear) {
                        Text(
                            text = "Without a year, no age is shown.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }

        if (state.readOnly) {
            item {
                Text(
                    text = BirthdayRepository.READ_ONLY_MESSAGE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
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
                    else -> "Add birthday"
                },
                onClick = vm::save,
                enabled = state.canSave,
            )
        }

        if (state.editing && !state.readOnly) {
            item {
                AppTintedButton(
                    text = "Delete this birthday",
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
            initial = state.date,
            onDismiss = { showDatePicker = false },
            onPick = { vm.setDate(it); showDatePicker = false },
        )
    }

    if (confirmingDelete) {
        ConfirmDeleteDialog(
            name = state.name,
            onDismiss = { confirmingDelete = false },
            onConfirm = { confirmingDelete = false; vm.delete() },
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Dialog(onDismissRequest = onDismiss) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(d.spaceLg)) {
                Text(
                    text = "Delete this birthday?",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    text = "\"${name.ifBlank { "Untitled birthday" }}\" will be removed.",
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
