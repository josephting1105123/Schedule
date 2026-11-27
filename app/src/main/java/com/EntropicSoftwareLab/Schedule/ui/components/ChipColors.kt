package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Black or white on a solid fill, whichever contrasts better.
 *
 * Solid fills survive only in the month widget (which keeps its own private copy of this rule) and
 * in dots. Event chips and blocks are drawn as [eventTint] with [eventInk] text instead.
 *
 * The crossover is a relative luminance of 0.179 — the point where `(L+0.05)/0.05` and
 * `1.05/(L+0.05)` meet. Calendar colours come from the user's account and span the whole wheel, so
 * one fixed text colour is unreadable on some of them however carefully it is picked.
 */
fun onChipColor(fill: Color): Color =
    if (fill.luminance() > 0.179f) Color(0xFF101215) else Color(0xFFFAFAFA)

/** Jarvis-style event fill: [fraction] of the event colour over white, opaque. */
fun eventTint(color: Color, fraction: Float = 0.28f): Color =
    lerp(Color.White, color.copy(alpha = 1f), fraction)

/**
 * Text in an event's own hue, darkened toward ink so it reads on that event's tint: 40% colour,
 * 60% ink. `EventColorPaletteTest` requires it to clear 4.5:1 on the 0.18 and 0.22 tints.
 */
fun eventInk(color: Color): Color = lerp(color.copy(alpha = 1f), Color(0xFF18202B), 0.60f)

/** Width of the solid colour bar on the leading edge of the Today grid's events only. */
val EVENT_EDGE_WIDTH = 3.dp

/** Width of the outline drawn round Week blocks and Month chips, in the event's own colour. */
val EVENT_OUTLINE_WIDTH = 1.dp
