package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

/**
 * The signature glass surface: solid fill, a top-down sheen, and a 1dp hairline. Depth comes from
 * tint and border rather than a drop shadow, which is what keeps the app reading as flat and calm.
 */
@Composable
fun Modifier.glassSurface(
    radius: Dp = AppThemeTokens.dimens.radiusLg,
    elevated: Boolean = false,
): Modifier {
    val glass = AppThemeTokens.glass
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(if (elevated) glass.surfaceElevated else glass.surface)
        .background(glass.verticalTint())
        .border(AppThemeTokens.dimens.hairlineWidth, glass.hairline, shape)
}

/** Press feedback that collapses to nothing when the user has asked for reduced motion. */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val motion = AppThemeTokens.motion
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (pressed && !motion.reduceMotion) motion.pressScale else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(motion.dur(motion.durFast), easing = motion.emphasized),
        label = "pressScale",
    )
    return this.scale(scale)
}

/** Colour a provider gave us, guarding against the 0 that unset calendar colours come back as. */
fun Int.asCalendarColor(fallback: Color): Color =
    if (this == 0) fallback else Color(this or 0xFF000000.toInt())
