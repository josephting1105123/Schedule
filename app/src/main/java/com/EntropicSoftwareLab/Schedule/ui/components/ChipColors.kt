package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Black or white on a fill, whichever contrasts better.
 *
 * The crossover is a relative luminance of 0.179 — the point where `(L+0.05)/0.05` and
 * `1.05/(L+0.05)` meet. Calendar colours come from the user's account and span the whole wheel, so
 * one fixed text colour is unreadable on some of them however carefully it is picked.
 */
fun onChipColor(fill: Color): Color =
    if (fill.luminance() > 0.179f) Color(0xFF101215) else Color(0xFFFAFAFA)
