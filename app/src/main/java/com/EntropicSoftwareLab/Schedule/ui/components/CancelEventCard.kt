package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.EntropicSoftwareLab.Schedule.ai.CancelCard
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One candidate for a cancellation, with the tap that actually removes it.
 *
 * The event shown here is a real row read from the calendar, not something the model produced — it
 * only described what to search for. So the card can state the title, day and time exactly as they
 * are stored, and the user is checking a match rather than trusting a claim.
 *
 * Cancel carries the destructive tint and sits alone. There is no "Cancel all" for the same reason
 * the add path has an Accept-all but this doesn't: creating five wrong events is an annoyance, and
 * deleting five right ones is not.
 */
@Composable
fun CancelEventCard(
    card: CancelCard,
    zone: ZoneId,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    AppCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(d.spaceLg),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            if (card.cancelled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = colors.greenText,
                        modifier = Modifier.size(d.iconSm),
                    )
                    Spacer(Modifier.width(d.spaceSm))
                    Text(
                        text = "Cancelled",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.greenText,
                    )
                }
            }

            Text(
                text = card.event.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val start = card.event.localStart(zone)
            val end = card.event.localEnd(zone)
            Text(
                text = if (card.event.allDay) {
                    "${start.format(DATE_FORMAT)} · all day"
                } else {
                    "${start.format(DATE_FORMAT)} · ${start.format(TIME_FORMAT)}–" +
                        end.format(TIME_FORMAT)
                },
                style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                color = colors.textSecondary,
            )

            card.event.location?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Said before the tap, not after. "Cancel" on a standing weekly meeting is a very
            // different action from cancelling a one-off, and the user should know which they're
            // about to take while the button still says Cancel.
            if (card.recurring && !card.cancelled) {
                Text(
                    text = "This event repeats — you'll choose which occurrences.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning,
                )
            }

            card.error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }

            if (!card.cancelled) {
                Spacer(Modifier.height(d.spaceXxs))
                AppTintedButton(
                    text = if (card.cancelling) "Cancelling…" else "Cancel this event",
                    onClick = onCancel,
                    tint = colors.error,
                    enabled = !card.cancelling,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
