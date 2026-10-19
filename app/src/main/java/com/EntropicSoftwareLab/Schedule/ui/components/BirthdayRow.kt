package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.time.LocalDate

/**
 * One birthday: a cake icon, the name, and the age it turns when a birth year was given.
 *
 * Shared between the Month page's birthday agenda and the Today page's Birthdays section, so the
 * two read identically wherever a birthday shows up.
 */
@Composable
fun BirthdayRow(
    birthday: Birthday,
    on: LocalDate,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Cake,
            contentDescription = null,
            tint = colors.greenText,
            modifier = Modifier.size(d.iconSm),
        )
        Spacer(Modifier.width(d.spaceSm))
        val age = birthday.ageOn(on)
        Text(
            text = if (age != null) "${birthday.name} — turns $age" else birthday.name,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
        )
    }
}
