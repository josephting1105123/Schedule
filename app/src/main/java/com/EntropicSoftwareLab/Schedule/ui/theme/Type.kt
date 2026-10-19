package com.EntropicSoftwareLab.Schedule.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Single-voice serif type system. Every M3 slot draws from FontFamily.Serif — headings, body and
 * controls alike — matching the current Clipboard Share build. No bundled font files: Serif resolves
 * to the platform serif (Noto Serif on Android), which carries Latin/Cyrillic/CJK coverage for free.
 * Numeric/data fields merge [TabularFigures] (tnum) so live values don't reflow as digits change width.
 */
private val Serif = FontFamily.Serif

/**
 * Type scale: Medium headings/titles/labels, Normal body. Starts from the M3 default scale so every
 * slot a screen might touch is covered, puts Serif on all of them, then overrides the five app styles
 * with their sizes/weights. Body floor is 15sp; nothing drops below 12sp.
 */
private val Base = Typography()
val AppTypography = Base.copy(
    displayLarge = Base.displayLarge.copy(fontFamily = Serif),
    displayMedium = Base.displayMedium.copy(fontFamily = Serif),
    displaySmall = Base.displaySmall.copy(fontFamily = Serif),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Serif),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Serif),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 24.sp), // App.Headline
    titleLarge = Base.titleLarge.copy(fontFamily = Serif),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 18.sp),   // App.Title
    titleSmall = Base.titleSmall.copy(fontFamily = Serif),
    bodyLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 15.sp),     // App.Body
    bodyMedium = Base.bodyMedium.copy(fontFamily = Serif),
    bodySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 12.sp),     // App.Caption
    labelLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 14.sp),    // App.Label
    labelMedium = Base.labelMedium.copy(fontFamily = Serif),
    labelSmall = Base.labelSmall.copy(fontFamily = Serif),
)

/**
 * Tabular (monospaced) figures — apply to live numeric/data text (event times, durations, counters)
 * so values don't reflow as digits change width. Merge onto any base style:
 * `style.merge(TabularFigures)`.
 */
val TabularFigures = TextStyle(fontFamily = Serif, fontFeatureSettings = "tnum")
