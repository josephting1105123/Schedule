package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE")
private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")

/** Share of the screen the pop-up takes: most of it, with the page still visible around it. */
private const val POPUP_WIDTH_FRACTION = 0.9f
private const val POPUP_HEIGHT_FRACTION = 0.7f

/**
 * One day's agenda floating over whatever was on screen — a long-press on the Month page, a tap on
 * the month widget (a launcher keeps long-press for itself, so the widget cannot have one).
 *
 * It is a look, not a destination: nothing about the page underneath changes, and dismissing it
 * (outside, back, or the close button) leaves the user exactly where they were. Going further is
 * explicit — "Open day" for Today's grid, "Add event" for the editor seeded with this date.
 *
 * The caller supplies the rows through [content], because the Month page's agenda and the widget's
 * do not share a data source: the page has its loaded window and its brand colours, the widget's
 * pop-up reads the widget cache. [isEmpty] chooses [emptyMessage] in their place.
 */
@Composable
fun DayAgendaDialog(
    date: LocalDate,
    today: LocalDate,
    isEmpty: Boolean,
    emptyMessage: String,
    onDismiss: () -> Unit,
    onOpenDay: () -> Unit,
    onAddEvent: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // Off, or the platform caps the width at its own dialog size and the fractions below are
        // fractions of that rather than of the screen.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DayAgendaCard(
            date = date,
            today = today,
            isEmpty = isEmpty,
            emptyMessage = emptyMessage,
            onDismiss = onDismiss,
            onOpenDay = onOpenDay,
            onAddEvent = onAddEvent,
            content = content,
        )
    }
}

/**
 * The pop-up's card on its own: header, the day's rows, and the two ways onward. [DayAgendaDialog]
 * puts it in a dialog window; the widget's `DayAgendaActivity` draws it straight into its own
 * window instead, so it can animate the card out before the activity finishes — a dialog window
 * torn down by `finish()` vanishes in the same frame.
 */
@Composable
fun DayAgendaCard(
    date: LocalDate,
    today: LocalDate,
    isEmpty: Boolean,
    emptyMessage: String,
    onDismiss: () -> Unit,
    onOpenDay: () -> Unit,
    onAddEvent: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val colors = AppThemeTokens.colors
    val d = AppThemeTokens.dimens

    Column(
        modifier
            .fillMaxWidth(POPUP_WIDTH_FRACTION)
            .fillMaxHeight(POPUP_HEIGHT_FRACTION)
            .clip(RoundedCornerShape(d.radiusXl))
            .background(colors.card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = d.spaceLg, end = d.spaceXs, top = d.spaceMd, bottom = d.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (date == today) "Today" else date.format(WEEKDAY),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (date == today) colors.greenText else colors.textSecondary,
                )
                Text(
                    text = date.format(FULL_DATE),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(d.iconMd),
                )
            }
        }
        AppDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = d.spaceLg, vertical = d.spaceSm),
            verticalArrangement = Arrangement.spacedBy(d.spaceXs),
        ) {
            if (isEmpty) {
                item { EmptyState(emptyMessage) }
            } else {
                content()
            }
        }

        AppDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.spaceSm, vertical = d.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(text = "Add event", onClick = onAddEvent)
            Spacer(Modifier.weight(1f))
            AppTextButton(text = "Open day", onClick = onOpenDay)
        }
    }
}

/** A day's events for the pop-up: all-day first, then by start. */
fun sortForAgenda(events: List<EventInstance>): List<EventInstance> =
    events.sortedWith(compareByDescending<EventInstance> { it.allDay }.thenBy { it.startMillis })
