package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefs
import com.EntropicSoftwareLab.Schedule.data.accounts.CalendarFilter
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarInfo
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

/** What to show for the chosen calendar in a form row: "—" when there is none. */
fun calendarLabel(c: CalendarInfo?, all: List<CalendarInfo>, prefs: AccountPrefs): String =
    if (c == null) "—" else CalendarFilter.label(c, all, prefs)

/**
 * A calendar dropdown grouped by account. The account is a heading and each calendar a row under
 * it, so an email address appears once instead of being repeated after every calendar's name.
 */
@Composable
fun BoxScope.CalendarPickerMenu(
    expanded: Boolean,
    calendars: List<CalendarInfo>,
    prefs: AccountPrefs,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onPick: (CalendarInfo) -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    // groupBy keeps first-seen order of its keys, which is the order the provider listed them in.
    val groups = calendars.groupBy { it.accountName }.entries.toList()

    EndAnchoredDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        groups.forEachIndexed { index, (accountName, list) ->
            if (index > 0) AppDivider()
            Text(
                text = CalendarFilter.accountLabel(accountName, prefs),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = d.spaceMd + d.spaceXs,
                    end = d.spaceMd + d.spaceXs,
                    top = d.spaceSm,
                    bottom = d.spaceXs,
                ),
            )
            list.forEach { calendar ->
                AppDropdownMenuItem(
                    text = CalendarFilter.label(calendar, calendars, prefs),
                    selected = calendar.id == selectedId,
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(calendar.color.asCalendarColor(colors.textSecondary)),
                        )
                    },
                    onClick = { onPick(calendar) },
                )
            }
        }
    }
}
