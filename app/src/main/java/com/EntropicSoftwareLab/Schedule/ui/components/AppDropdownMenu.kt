package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.R
import androidx.compose.ui.res.stringResource

/**
 * The app's one dropdown menu: a white card with rounded corners, a hairline border and a soft
 * shadow, in place of Material3's lavender fill with 4dp corners. It sits a few dp below its
 * anchor so it doesn't cover the anchor's own text.
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 180.dp),
        offset = DpOffset(0.dp, d.spaceXs),
        shape = RoundedCornerShape(d.radiusMd),
        containerColor = colors.card,
        tonalElevation = 0.dp,
        shadowElevation = AppThemeTokens.elevation.level3,
        border = BorderStroke(d.hairlineWidth, colors.hairline),
        content = content,
    )
}

/**
 * [AppDropdownMenu] anchored to the bottom-right corner of the enclosing Box, so it opens under a
 * row's right-hand value, right-aligned, instead of covering the row's label on the left.
 */
@Composable
fun BoxScope.EndAnchoredDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
        Box(Modifier.size(0.dp)) {
            AppDropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                content = content,
            )
        }
    }
}

/** One row of an [AppDropdownMenu]: a rounded pill, filled pale blue with a check when [selected]. */
@Composable
fun AppDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colors.greenText else colors.textPrimary,
                fontWeight = if (selected) FontWeight.Medium else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = d.spaceXs)
            .clip(RoundedCornerShape(d.radiusSm))
            .background(if (selected) colors.accentMuted else Color.Transparent),
        leadingIcon = leadingIcon,
        trailingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.cmp_selected),
                    tint = colors.greenText,
                    modifier = Modifier.size(d.iconSm),
                )
            }
        } else null,
        contentPadding = PaddingValues(horizontal = d.spaceMd),
    )
}
