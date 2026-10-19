package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val PILL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M")

/**
 * Floating "back to today" capsule for the Today page's day pager.
 *
 * Shows today's own date, not whichever day is on screen — the pill's whole job is to say where
 * tapping it goes, and "Today 6/8" answers that in one glance without the user having to first read
 * the header to work out how far they've wandered.
 */
@Composable
fun TodayPill(today: LocalDate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = modifier
            .glassSurface(radius = d.radiusPill, elevated = true)
            .clickable(onClick = onClick)
            .padding(horizontal = d.spaceLg, vertical = d.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ArrowUpward,
            contentDescription = null,
            tint = colors.greenText,
            modifier = Modifier.size(d.iconSm),
        )
        Spacer(Modifier.width(d.spaceXs))
        Text(
            text = "Today " + today.format(PILL_DATE),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
        )
    }
}
