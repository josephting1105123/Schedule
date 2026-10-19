package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.ai.UpdateCard
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

/**
 * A proposed change to an event that already exists.
 *
 * Every changed field is shown **old beside new**, struck through and then replaced. This is the one
 * assistant card that overwrites something rather than adding to it, and a card showing only the new
 * value asks the user to approve a change they cannot check — they would have to remember what the
 * event said before, which is exactly the thing a person opening their calendar app does not know.
 *
 * Fields the model didn't mention are not listed at all. Silence means unchanged, and printing
 * "Location: (unchanged)" for four fields buries the one line that matters.
 */
@Composable
fun UpdateEventCard(
    card: UpdateCard,
    zone: ZoneId,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(d.spaceLg)) {
            Text(
                text = if (card.applied) "Changed" else "Change this event?",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(d.spaceSm))

            card.newTitle?.let { ChangeRow("Name", card.event.title, it) }
            card.newStart?.let { ChangeRow("Starts", card.event.localStart(zone).format(WHEN), it.format(WHEN)) }
            card.newEnd?.let { ChangeRow("Ends", card.event.localEnd(zone).format(WHEN), it.format(WHEN)) }
            card.newLocation?.let { ChangeRow("Where", card.event.location.orEmpty(), it) }
            card.newColorArgb?.let { ColorChangeRow("Colour", card.event.displayColor, it) }

            if (card.newTitle == null) {
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    text = card.event.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            if (card.recurring && !card.applied) {
                Spacer(Modifier.height(d.spaceXs))
                Text(
                    text = "This event repeats — you'll be asked which occurrences.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            card.error?.let {
                Spacer(Modifier.height(d.spaceSm))
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }

            Spacer(Modifier.height(d.spaceMd))
            if (card.applied) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = colors.greenText,
                    )
                    Spacer(Modifier.width(d.spaceSm))
                    Text(
                        text = "Saved to your calendar.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                    AppButton(
                        text = if (card.applying) "Applying…" else "Apply",
                        onClick = onApply,
                        enabled = !card.applying,
                    )
                    AppSecondaryButton(
                        text = "Discard",
                        onClick = onDiscard,
                        enabled = !card.applying,
                    )
                }
            }
        }
    }
}

/** One field, struck-through old value then new. An empty old value reads as "(none)". */
@Composable
private fun ChangeRow(label: String, oldValue: String, newValue: String) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(Modifier.fillMaxWidth().padding(vertical = d.spaceXxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.width(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = oldValue.ifBlank { "(none)" },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textDecoration = TextDecoration.LineThrough,
            )
            Text(
                text = newValue.ifBlank { "(none)" },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
            )
        }
    }
}

/** Swatch variant of [ChangeRow] — same label column, but a colour circle old and new. Read-only. */
@Composable
private fun ColorChangeRow(label: String, oldArgb: Int, newArgb: Int) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = d.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.width(64.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(oldArgb)
            Text("→", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
            ColorDot(newArgb)
        }
    }
}

@Composable
private fun ColorDot(argb: Int) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(width = d.hairlineWidth, color = colors.divider, shape = CircleShape),
    )
}
