package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceEnd
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceFreq
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceRule
import com.EntropicSoftwareLab.Schedule.data.calendar.RepeatSelection
import com.EntropicSoftwareLab.Schedule.data.calendar.label
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.time.LocalDate

/**
 * The Repeats sheet: four presets, or a custom rule with an interval and an end.
 *
 * The presets cover what most events need and are one tap each. Custom exists because "every other
 * Tuesday until the end of term" is a real thing a calendar has to express, and the alternative is
 * sending the user to Google Calendar to make the event and back here to look at it.
 *
 * A rule this app can't model ([RepeatSelection.Unsupported]) gets its own row rather than being
 * hidden: the user can see their repeat survived, and choosing anything else is an explicit,
 * deliberate replacement rather than a silent rewrite on save.
 */
@Composable
fun RepeatPickerDialog(
    current: RepeatSelection,
    /** The event's start date, which anchors "Weekly on Monday" and the custom end-date default. */
    anchor: LocalDate,
    /**
     * The earliest day the series may end on: the series' own first day. Not [anchor] — editing the
     * 18 January occurrence of a weekly lecture that began in October must still allow ending it in
     * December, and clamping to the opened occurrence silently turned every such pick into 18 January
     * (reported 2026-09-29). Never earlier than this, though: an UNTIL before DTSTART leaves a row
     * that renders nowhere.
     */
    earliestEnd: LocalDate = anchor,
    onDismiss: () -> Unit,
    onPick: (RepeatSelection) -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    // Seed the custom editor from whatever is already selected, so opening it on "Weekly" starts
    // there rather than resetting to a default the user then has to undo.
    val seed = (current as? RepeatSelection.Simple)?.rule ?: RecurrenceRule(RecurrenceFreq.Weekly)
    var custom by remember { mutableStateOf(seed) }
    var customOpen by remember {
        mutableStateOf(current is RepeatSelection.Simple && !current.rule.isPreset)
    }

    Dialog(onDismissRequest = onDismiss) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .padding(d.spaceLg)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(d.spaceXs),
            ) {
                Text(
                    text = "Repeats",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(d.spaceSm))

                ChoiceRow(
                    label = "Does not repeat",
                    selected = current is RepeatSelection.None && !customOpen,
                    onClick = { onPick(RepeatSelection.None) },
                )

                RecurrenceFreq.entries.forEach { freq ->
                    val rule = RecurrenceRule(freq)
                    ChoiceRow(
                        label = rule.label(anchor),
                        selected = !customOpen && (current as? RepeatSelection.Simple)?.rule == rule,
                        onClick = { onPick(RepeatSelection.Simple(rule)) },
                    )
                }

                if (current is RepeatSelection.Unsupported) {
                    ChoiceRow(
                        label = current.label(anchor),
                        selected = !customOpen,
                        onClick = { onPick(current) },
                    )
                    Text(
                        text = "This event repeats on a pattern this app doesn't build. It's kept " +
                            "exactly as it is unless you pick something else here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(start = d.spaceXl, bottom = d.spaceSm),
                    )
                }

                ChoiceRow(
                    label = "Custom…",
                    selected = customOpen,
                    onClick = { customOpen = true },
                )

                if (customOpen) {
                    CustomRepeatEditor(
                        rule = custom,
                        anchor = anchor,
                        earliestEnd = earliestEnd,
                        onChange = { custom = it },
                    )
                }

                Spacer(Modifier.height(d.spaceMd))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                    if (customOpen) {
                        Spacer(Modifier.width(d.spaceSm))
                        TextButton(onClick = { onPick(RepeatSelection.Simple(custom)) }) {
                            Text("Set repeat", color = colors.greenText)
                        }
                    }
                }
            }
        }
    }
}

/** True when the rule is exactly one of the one-tap presets, so Custom shouldn't claim it. */
private val RecurrenceRule.isPreset: Boolean
    get() = interval == 1 && end == RecurrenceEnd.Never

@Composable
private fun CustomRepeatEditor(
    rule: RecurrenceRule,
    anchor: LocalDate,
    earliestEnd: LocalDate,
    onChange: (RecurrenceRule) -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    var freqMenu by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    Column(
        Modifier.padding(start = d.spaceXl, top = d.spaceSm),
        verticalArrangement = Arrangement.spacedBy(d.spaceSm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Every", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
            Spacer(Modifier.width(d.spaceSm))
            OutlinedTextField(
                value = rule.interval.toString(),
                onValueChange = { text ->
                    // An empty field mid-typing must not snap back to 1 under the cursor; treat it
                    // as 1 only once it becomes a number again.
                    val n = text.filter { it.isDigit() }.take(3).toIntOrNull()
                    if (n != null && n >= 1) onChange(rule.copy(interval = n))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(84.dp),
            )
            Spacer(Modifier.width(d.spaceSm))
            Box {
                TextButton(onClick = { freqMenu = true }) {
                    Text(rule.freq.unitLabel(rule.interval), color = colors.textPrimary)
                }
                DropdownMenu(expanded = freqMenu, onDismissRequest = { freqMenu = false }) {
                    RecurrenceFreq.entries.forEach { freq ->
                        DropdownMenuItem(
                            text = { Text(freq.unitLabel(rule.interval)) },
                            onClick = { onChange(rule.copy(freq = freq)); freqMenu = false },
                        )
                    }
                }
            }
        }

        Text(
            text = "Ends",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = colors.textSecondary,
        )

        ChoiceRow(
            label = "Never",
            selected = rule.end is RecurrenceEnd.Never,
            onClick = { onChange(rule.copy(end = RecurrenceEnd.Never)) },
        )

        val endDate = (rule.end as? RecurrenceEnd.OnDate)?.date ?: anchor.plusMonths(3)
        ChoiceRow(
            label = "On ${endDate.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))}",
            selected = rule.end is RecurrenceEnd.OnDate,
            // Choosing "On…" opens the calendar straight away, and so does every tap after: the date
            // is the whole point of this option, and a first tap that only moved the radio button
            // read as the control doing nothing.
            onClick = {
                if (rule.end !is RecurrenceEnd.OnDate) onChange(rule.copy(end = RecurrenceEnd.OnDate(endDate)))
                showEndDatePicker = true
            },
        )

        val count = (rule.end as? RecurrenceEnd.AfterCount)?.count ?: 10
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = rule.end is RecurrenceEnd.AfterCount,
                onClick = { onChange(rule.copy(end = RecurrenceEnd.AfterCount(count))) },
                colors = RadioButtonDefaults.colors(selectedColor = colors.greenText),
            )
            Text("After", style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            Spacer(Modifier.width(d.spaceSm))
            OutlinedTextField(
                value = count.toString(),
                onValueChange = { text ->
                    val n = text.filter { it.isDigit() }.take(4).toIntOrNull()
                    if (n != null && n >= 1) onChange(rule.copy(end = RecurrenceEnd.AfterCount(n)))
                },
                singleLine = true,
                enabled = rule.end is RecurrenceEnd.AfterCount,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
            )
            Spacer(Modifier.width(d.spaceSm))
            Text("times", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        }
    }

    if (showEndDatePicker) {
        DatePickerSheet(
            initial = (rule.end as? RecurrenceEnd.OnDate)?.date ?: anchor.plusMonths(3),
            minDate = earliestEnd,
            onDismiss = { showEndDatePicker = false },
            onPick = {
                // The picker already greys out anything earlier; the max is only a backstop.
                onChange(rule.copy(end = RecurrenceEnd.OnDate(maxOf(it, earliestEnd))))
                showEndDatePicker = false
            },
        )
    }
}

private fun RecurrenceFreq.unitLabel(interval: Int): String {
    val plural = interval != 1
    return when (this) {
        RecurrenceFreq.Daily -> if (plural) "days" else "day"
        RecurrenceFreq.Weekly -> if (plural) "weeks" else "week"
        RecurrenceFreq.Monthly -> if (plural) "months" else "month"
        RecurrenceFreq.Yearly -> if (plural) "years" else "year"
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.touchTarget)
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = colors.greenText),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
        )
    }
}
