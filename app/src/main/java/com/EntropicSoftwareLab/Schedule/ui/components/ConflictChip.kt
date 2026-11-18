package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.ZoneId
import com.EntropicSoftwareLab.Schedule.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/**
 * Verdict for a proposed time: free, or the list of things already there.
 *
 * A conflict is advisory. Double-booking is sometimes deliberate, so this never disables the save
 * button — it just makes sure the clash isn't a surprise afterwards.
 */
@Composable
fun ConflictPanel(
    conflicts: List<EventInstance>,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val colors = AppThemeTokens.colors
    val d = AppThemeTokens.dimens
    val timeFormat = rememberClockFormatter()

    if (conflicts.isEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(d.radiusMd))
                .background(colors.accentMuted)
                .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = colors.greenText,
                modifier = Modifier.size(d.iconSm),
            )
            Spacer(Modifier.width(d.spaceSm))
            Text(
                text = stringResource(R.string.cmp_slot_free),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.greenText,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(colors.warning.copy(alpha = 0.12f))
            .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
        verticalArrangement = Arrangement.spacedBy(d.spaceXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = colors.warning,
                modifier = Modifier.size(d.iconSm),
            )
            Spacer(Modifier.width(d.spaceSm))
            Text(
                text = pluralStringResource(R.plurals.cmp_overlaps_events, conflicts.size, conflicts.size),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.warning,
            )
        }
        conflicts.take(4).forEach { event ->
            Text(
                text = "${event.localStart(zone).format(timeFormat)}–" +
                    "${event.localEnd(zone).format(timeFormat)}  ${event.title}",
                style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (conflicts.size > 4) {
            Text(
                text = stringResource(R.string.cmp_and_more, conflicts.size - 4),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}
