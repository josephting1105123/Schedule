package com.EntropicSoftwareLab.Schedule.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Light-only app chrome, 60-30-10: neutral background/card ~60%, slate text/structure ~30%, one
 * indigo accent ~10%. See ui/theme/TOKENS.md (source of truth).
 *
 * The app used to also carry a dark palette; it was removed (the user's call) rather than kept
 * unused, so there is exactly one set of these values now.
 *
 * Accent discipline (unchanged in shape, changed in colour): `LightGreen` is a FILL (place white
 * text ON it); `LightGreenText` is the WCAG-safe accent for text / links / small icons. The names
 * are historical — see the KDoc on `AppColors.green`/`greenText` — the accent itself is indigo now,
 * because green is reserved as the semantic "success" colour (`LightSuccess`) and nothing else.
 */

val LightBackground = Color(0xFFF8FAFC)
val LightCard = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)
val LightDivider = Color(0xFFE2E8F0)
val LightNavBar = Color(0xFFFFFFFF)
val LightNavIcon = Color(0xFF475569)
val LightBottomSheet = Color(0xFFFFFFFF)
val LightGreen = Color(0xFF4F46E5)             // indigo accent fill (name is historical)
val LightGreenText = Color(0xFF4338CA)         // WCAG-safe indigo for text on white (7.9:1)
val LightSuccess = Color(0xFF15803D)
val LightError = Color(0xFFB91C1C)
val LightWarning = Color(0xFFB45309)
val LightInfo = Color(0xFF0369A1)
// Glass / depth tokens
val LightSurfaceElevated = Color(0xFFF8FAFC)
val LightScrim = Color(0x66000000)             // black @ 40%
val LightHairline = Color(0x0F0F172A)          // slate-900 @ 6%
val LightGradientTop = Color(0xFFFFFFFF)
val LightGradientBottom = Color(0xFFF1F5F9)
val LightAccentMuted = Color(0xFFEEF2FF)       // indigo-50, opaque

// ── Event palette ──────────────────────────────────────────────────────────
/*
 * Event colours now live in `ui/theme/EventColorPalette.kt`.
 *
 * What used to sit here was `EventPalette`, eight hues meant to be handed out per event by
 * `HourGrid.assignColors`. That wiring was never finished — `rememberColorAssignment` accepted the
 * day's events and never looked at them — so every surface read the provider's colour and these
 * eight were dead. They are gone rather than left as a decoy: the app has a real palette now, and
 * two lists both claiming to be the event colours is how the wrong one gets edited.
 */
