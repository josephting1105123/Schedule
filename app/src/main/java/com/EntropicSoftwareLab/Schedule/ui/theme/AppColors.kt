package com.EntropicSoftwareLab.Schedule.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App colour tokens that Material 3's [androidx.compose.material3.ColorScheme] doesn't model
 * (card / divider / navbar / bottom-sheet / secondary text / accent / semantic states).
 * Provided via [LocalAppColors] in [AppTheme]; read through [AppThemeTokens.colors]. Light only —
 * the app no longer has a dark theme.
 */
@Immutable
data class AppColors(
    val background: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val navBar: Color,
    val navIcon: Color,
    val bottomSheet: Color,
    /**
     * The app's one accent fill. The name is historical — this used to be a brand green; the accent
     * is indigo now (`#4F46E5`) and green is reserved for [success] alone. Renaming this property
     * would touch every screen that reads it, so the name stays and the colour changed underneath it.
     */
    val green: Color,
    /**
     * The WCAG-safe form of [green] for text / small icons (7.9:1 on white). Same historical-name
     * caveat as [green] — this is indigo text, not green text.
     */
    val greenText: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    // New glass / depth tokens (appended — keep at the end so positional construction stays stable).
    val surfaceElevated: Color, // bento tile / raised card (one step above `card`)
    val scrim: Color,           // modal / sheet dim
    val hairline: Color,        // 1dp glass border (slate@6%)
    val gradientTop: Color,     // root surface gradient stop A
    val gradientBottom: Color,  // root surface gradient stop B
    /**
     * [green] at low alpha for chip / halo backgrounds. Historical name, same as [green] — this is
     * indigo-50 now, not a green tint.
     */
    val accentMuted: Color,
)

val LightAppColors = AppColors(
    background = LightBackground,
    card = LightCard,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    divider = LightDivider,
    navBar = LightNavBar,
    navIcon = LightNavIcon,
    bottomSheet = LightBottomSheet,
    green = LightGreen,
    greenText = LightGreenText,
    success = LightSuccess,
    error = LightError,
    warning = LightWarning,
    info = LightInfo,
    surfaceElevated = LightSurfaceElevated,
    scrim = LightScrim,
    hairline = LightHairline,
    gradientTop = LightGradientTop,
    gradientBottom = LightGradientBottom,
    accentMuted = LightAccentMuted,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
