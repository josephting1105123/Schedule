package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.Schedule.data.task.Task
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import com.EntropicSoftwareLab.Schedule.R
import androidx.compose.ui.res.stringResource

private val OVERDUE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** How urgent a task looks, which decides only the badge's tint. */
private enum class Tone { Overdue, Today, Neutral }

/**
 * One task as its own small card: a date badge on the left, the title over a monospace meta line in
 * the middle, and a checkbox on the right.
 *
 * The badge always shows the deadline's weekday and day of month, tinted by urgency — red once it's
 * overdue, amber when it's due [today], neutral otherwise (and neutral for anything already done, so
 * a ticked task stops asking for attention). The meta line carries the time when there is one; an
 * overdue task also gets its date there ("14 Nov"), because the badge's day number alone is
 * ambiguous across months. A task due today with no time has nothing to add, so the line is just the
 * "TASK" tag.
 *
 * The checkbox and the card are separate targets on purpose: the checkbox ticks, the card opens the
 * task. Folding them together would mean editing a task required first learning not to tap the
 * obvious thing.
 */
@Composable
fun TaskRow(
    task: Task,
    today: LocalDate,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val shape = RoundedCornerShape(d.radiusMd)
    val timeFormat = rememberClockFormatter()

    val tone = when {
        task.done -> Tone.Neutral
        task.due.isBefore(today) -> Tone.Overdue
        task.due == today -> Tone.Today
        else -> Tone.Neutral
    }
    val badgeBackground = when (tone) {
        Tone.Overdue -> colors.error.copy(alpha = 0.12f)
        Tone.Today -> colors.warning.copy(alpha = 0.10f)
        Tone.Neutral -> colors.divider
    }
    val weekdayColor = when (tone) {
        Tone.Overdue -> colors.error
        Tone.Today -> colors.warning
        Tone.Neutral -> colors.textSecondary
    }
    val dayColor = when (tone) {
        Tone.Overdue -> colors.error
        Tone.Today -> colors.warning
        Tone.Neutral -> colors.textPrimary
    }

    val whenText = when {
        tone == Tone.Overdue || (!task.done && task.due.isAfter(today.plusDays(6))) -> buildString {
            append(task.due.format(OVERDUE_DATE))
            task.dueTime?.let { append(' ').append(it.format(timeFormat)) }
        }
        else -> task.dueTime?.format(timeFormat).orEmpty()
    }

    val doneDescription = stringResource(R.string.cmp_task_done, task.title)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(d.hairlineWidth, colors.hairline, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .width(44.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(d.radiusSm))
                .background(badgeBackground),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = task.due.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    .take(3)
                    .uppercase(Locale.getDefault()),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                color = weekdayColor,
            )
            Text(
                text = task.due.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                color = dayColor,
            )
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
            Row {
                Text(
                    text = stringResource(R.string.cmp_task_tag),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.green,
                )
                if (whenText.isNotEmpty()) {
                    Text(
                        text = " $whenText",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary,
                    )
                }
            }
            task.notes?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(d.spaceSm))

        Checkbox(
            checked = task.done,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = doneDescription },
            colors = CheckboxDefaults.colors(
                checkedColor = colors.green,
                uncheckedColor = colors.textSecondary,
            ),
        )
    }
}
