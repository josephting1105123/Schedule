package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

/**
 * A two-way capsule slider: a thumb that slides between two icon segments.
 *
 * Grey and translucent rather than the app's usual glass tokens — this floats over the header, not
 * part of its surface, the same treatment [CalendarSizeButton] gets in the same trailing slot next
 * to it. [selectedFirst] picks which side the thumb sits on; the caller owns what that means.
 */
@Composable
fun ModeSlider(
    selectedFirst: Boolean,
    firstIcon: ImageVector,
    firstDescription: String,
    secondIcon: ImageVector,
    secondDescription: String,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppThemeTokens.colors
    val d = AppThemeTokens.dimens
    val segment = d.touchTarget
    val shape = RoundedCornerShape(d.radiusPill)
    val thumbOffset by animateDpAsState(
        targetValue = if (selectedFirst) 0.dp else segment,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "modeSliderThumb",
    )
    Box(
        modifier = modifier
            .size(width = segment * 2, height = segment)
            .clip(shape)
            .background(Color.Gray.copy(alpha = 0.18f))
            .border(d.hairlineWidth, colors.hairline, shape),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(segment)
                .padding(3.dp)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.4f)),
        )
        Row(Modifier.fillMaxSize()) {
            SliderSegment(
                icon = firstIcon,
                description = firstDescription,
                selected = selectedFirst,
                size = segment,
                onClick = { onSelect(true) },
            )
            SliderSegment(
                icon = secondIcon,
                description = secondDescription,
                selected = !selectedFirst,
                size = segment,
                onClick = { onSelect(false) },
            )
        }
    }
}

@Composable
private fun SliderSegment(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) colors.textPrimary else colors.textSecondary,
        )
    }
}
