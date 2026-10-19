package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

/** Static glass card. Use [GlassCard] when the whole card is tappable. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    radius: Dp = AppThemeTokens.dimens.radiusLg,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.glassSurface(radius = radius, elevated = elevated),
        content = content,
    )
}

/** Tappable glass card: adds press-scale and announces itself as a button to TalkBack. */
@Composable
fun GlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = AppThemeTokens.dimens.radiusLg,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(interaction)
            .glassSurface(radius = radius, elevated = elevated)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            ),
        content = content,
    )
}

/** A titled card — the standard way to group related rows on a screen. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** Accent green by default; override for a section whose meaning isn't "all good". */
    iconTint: Color = AppThemeTokens.colors.greenText,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val d = AppThemeTokens.dimens
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.spaceLg, vertical = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(d.iconSm),
                    )
                    Spacer(Modifier.size(d.spaceSm))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppThemeTokens.colors.textPrimary,
                )
            }
            trailing?.invoke()
        }
        AppDivider()
        Column(Modifier.padding(d.spaceLg), content = content)
    }
}

@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = AppThemeTokens.dimens.hairlineWidth,
        color = AppThemeTokens.colors.divider,
    )
}

/** Screen title block. [subtitle] carries the quiet secondary line (date, count, status). */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Makes the title a button; null leaves it plain text. */
    onTitleClick: (() -> Unit)? = null,
    /** What TalkBack announces the title tap as doing. */
    titleClickLabel: String? = null,
) {
    val d = AppThemeTokens.dimens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = d.spaceLg, vertical = d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = AppThemeTokens.colors.textPrimary,
                modifier = if (onTitleClick != null) {
                    Modifier.clickable(
                        onClickLabel = titleClickLabel,
                        role = Role.Button,
                        onClick = onTitleClick,
                    )
                } else {
                    Modifier
                },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(d.spaceXxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppThemeTokens.colors.textSecondary,
                )
            }
        }
        trailing?.invoke()
    }
}
