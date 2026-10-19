package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

/**
 * Primary action — green fill, full width, 56dp. One per screen: if a layout seems to want two,
 * one of them is a secondary.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val d = AppThemeTokens.dimens
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(d.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppThemeTokens.colors.green,
            // White on the indigo accent (6.3:1); the old dark ink was chosen for the green fill.
            contentColor = Color.White,
        ),
        modifier = modifier
            .pressScale(interaction)
            .fillMaxWidth()
            .height(d.buttonHeight),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary action — hairline outline, no fill. */
@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val d = AppThemeTokens.dimens
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(d.radiusMd),
        border = BorderStroke(d.hairlineWidth, AppThemeTokens.colors.divider),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = AppThemeTokens.colors.textPrimary,
        ),
        modifier = modifier
            .pressScale(interaction)
            .height(d.buttonHeight),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Muted-green tonal action — for affirmative choices that aren't the screen's primary. */
@Composable
fun AppTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val d = AppThemeTokens.dimens
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(d.radiusMd),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppThemeTokens.colors.accentMuted,
            contentColor = AppThemeTokens.colors.greenText,
        ),
        modifier = modifier
            .pressScale(interaction)
            .height(d.buttonHeight),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Semantic-tinted action — the [AppTonalButton] treatment generalised to any state colour.
 *
 * The tint carries the meaning (`colors.error` reject, `colors.info` edit, `colors.greenText`
 * accept), so all three come from the existing palette rather than new one-off colours. Content sits
 * at full tint and the fill at 14% of it, which is exactly how `accentMuted` relates to `green` — the
 * same ratio the brand already uses, so a red or blue button reads as the green one's sibling.
 *
 * [filled] false drops the fill for a tinted hairline outline, for a lower-emphasis action.
 */
@Composable
fun AppTintedButton(
    text: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val d = AppThemeTokens.dimens
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(d.radiusMd)
    val sizing = modifier
        .pressScale(interaction)
        .height(d.buttonHeight)

    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = tint.copy(alpha = 0.14f),
                contentColor = tint,
            ),
            modifier = sizing,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = shape,
            border = BorderStroke(d.hairlineWidth, tint.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
            modifier = sizing,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Low-emphasis text action. */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = AppThemeTokens.colors.greenText,
        )
    }
}
