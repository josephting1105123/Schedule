package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.data.task.Task
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val OVERDUE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * One task: a checkbox, the deadline, and the title struck through once it's done.
 *
 * The time column is the same 56dp as [EventRow]'s, so a Tasks section and an Agenda section line up
 * down the same edge — a deadline and a meeting read against each other rather than as two unrelated
 * lists. It carries one time rather than a start over an end, which is the visible form of the rule
 * that a task is a point and an event is a span.
 *
 * The checkbox and the row are separate targets on purpose: the checkbox ticks, the row opens the
 * task. Folding them together would mean editing a task required first learning not to tap the
 * obvious thing.
 *
 * [showDueDate] prints the deadline's date under the time. Used only for an overdue task carried
 * onto today, where "09:00" alone would claim the deadline is today.
 */
@Composable
fun TaskRow(
    task: Task,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showDueDate: Boolean = false,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = task.done,
            onCheckedChange = onToggle,
            colors = CheckboxDefaults.colors(checkedColor = colors.green),
        )
        Spacer(Modifier.width(d.spaceSm))

        Column(Modifier.width(56.dp)) {
            if (task.dueTime != null) {
                Text(
                    text = task.dueTime.format(TIME_FORMAT),
                    style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                    color = if (task.done) colors.textSecondary else colors.textPrimary,
                )
            } else {
                Text(
                    text = "any time",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            if (showDueDate) {
                Text(
                    text = task.due.format(OVERDUE_DATE),
                    style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                    color = colors.error,
                )
            }
        }
        Spacer(Modifier.width(d.spaceMd))

        Column(Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.done) colors.textSecondary else colors.textPrimary,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            task.notes?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
