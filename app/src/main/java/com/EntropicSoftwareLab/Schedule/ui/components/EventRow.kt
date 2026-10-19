package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One agenda row: a colour bar identifying the event, the time in tabular figures so rows align
 * down the column, then title and location.
 *
 * [barColor] overrides the bar so the Today agenda can match the per-event colour the hour grid
 * assigned that day; left null, the bar falls back to the source calendar's colour, which is what
 * every other screen wants.
 *
 * [onClick] opens the event for editing. Left null the row stays inert, which is what the assistant's
 * read-only conflict lists want — a row there is evidence for a decision, not a thing to go and edit.
 *
 * [showNotes] prints up to two lines of the event's description below the location. Off by default:
 * it reads as browsing on a daily agenda but as noise on a list that's evidence for a decision
 * (Clashes) or already showing its own summary (the assistant's conflict list).
 */
@Composable
fun EventRow(
    event: EventInstance,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    barColor: Color? = null,
    showNotes: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = d.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(d.radiusPill))
                .background(barColor ?: event.displayColor.asCalendarColor(colors.green)),
        )
        Spacer(Modifier.width(d.spaceMd))

        Column(Modifier.width(56.dp)) {
            if (event.allDay) {
                Text(
                    text = "all day",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            } else {
                Text(
                    text = event.localStart(zone).format(TIME_FORMAT),
                    style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                    color = colors.textPrimary,
                )
                Text(
                    text = event.localEnd(zone).format(TIME_FORMAT),
                    style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                    color = colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.width(d.spaceMd))

        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            event.location?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showNotes) {
                event.notes?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Quiet placeholder for a screen or card with nothing to show. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppThemeTokens.dimens.spaceXl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = AppThemeTokens.colors.textSecondary,
        )
    }
}
