package com.EntropicSoftwareLab.Schedule.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.ui.components.Links
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * What's in an hour the user tapped on the grid, and the way in to changing it.
 *
 * Tapping a row opens the editor rather than editing in place: the sheet is a glance at a busy hour,
 * and a form crammed into it would be a worse version of the screen that already exists. The rule
 * this doesn't break is the one about *AI* output — a model still can't reach the calendar without a
 * card and a tap. The user's own hands were never the thing being gated.
 */
@Composable
fun HourDetailsSheet(
    hour: Int,
    events: List<EventInstance>,
    colorFor: (EventInstance) -> Color,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onEditEvent: (EventInstance) -> Unit = {},
) = EventsSheet(
    heading = "Slot: ${hourLabel(hour)} - ${hourLabel((hour + 1) % 24)}",
    events = events,
    colorFor = colorFor,
    zone = zone,
    onDismiss = onDismiss,
    onEditEvent = onEditEvent,
)

/**
 * A bottom sheet listing [events] under [heading] — each with its times, place and notes in full —
 * where tapping one opens it in the editor. The Today grid opens it for an hour, the Week timetable
 * for a single block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsSheet(
    heading: String,
    events: List<EventInstance>,
    colorFor: (EventInstance) -> Color,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onEditEvent: (EventInstance) -> Unit = {},
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = colors.bottomSheet,
        scrimColor = colors.scrim,
    ) {
        Column(
            // Scrolls: an event's notes can be a whole programme — a timed run-sheet pasted in —
            // and a sheet that can't scroll would cut it off with no way to read the rest.
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = d.spaceLg, end = d.spaceLg, bottom = d.spaceHuge),
            verticalArrangement = Arrangement.spacedBy(d.spaceLg),
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                color = colors.textPrimary,
            )
            events.forEach { event ->
                EventDetail(
                    event = event,
                    accentColor = colorFor(event),
                    zone = zone,
                    onClick = { onEditEvent(event) },
                )
            }
            Text(
                text = "Tap an event to edit or cancel it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun EventDetail(
    event: EventInstance,
    accentColor: Color,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            Modifier
                .width(4.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(d.radiusPill))
                .background(accentColor),
        )
        Spacer(Modifier.width(d.spaceMd))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.spaceXxs)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val start = event.localStart(zone)
            val end = event.localEnd(zone)
            // An event can start the day before or run into the next; say which day when it does,
            // otherwise a 23:00–01:00 booking reads as ending before it began.
            val sameDay = start.toLocalDate() == end.toLocalDate()
            Text(
                text = if (sameDay) {
                    "${start.format(TIME_FORMAT)} – ${end.format(TIME_FORMAT)}"
                } else {
                    "${start.format(DAY_FORMAT)} ${start.format(TIME_FORMAT)} – " +
                        "${end.format(DAY_FORMAT)} ${end.format(TIME_FORMAT)}"
                },
                style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                color = colors.greenText,
            )
            event.location?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The notes in full: this sheet is where a busy hour is read, and a run-sheet cut to two
            // lines is the part the user opened it for. Which calendar the event is on is not shown —
            // an account address says nothing about the hour. Addresses in it open in the browser
            // (or Maps, for a maps link); a tap anywhere else on the row still opens the editor.
            event.notes?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    text = Links.annotate(it.trim(), colors.greenText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                )
            }
        }
    }
}

/** "0900" — the four-digit clock the Week timetable uses. */
private fun hourLabel(hour: Int): String = "%02d00".format(hour)
