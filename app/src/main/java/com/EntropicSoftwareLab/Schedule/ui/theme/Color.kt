package com.EntropicSoftwareLab.Schedule.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Light-only app chrome, 60-30-10: neutral background/card ~60%, ink text/structure ~30%, one
 * blue accent ~10%. See ui/theme/TOKENS.md (source of truth).
 *
 * Palette taken from the Jarvis app (2026-11-15, the user's call).
 *
 * The app used to also carry a dark palette; it was removed (the user's call) rather than kept
 * unused, so there is exactly one set of these values now.
 *
 * Accent discipline (unchanged in shape, changed in colour): `LightGreen` is a FILL (place white
 * text ON it); `LightGreenText` is the WCAG-safe accent for text / links / small icons. The names
 * are historical — see the KDoc on `AppColors.green`/`greenText` — the accent itself is blue now (Jarvis's #2F6BFF),
 * because green is reserved as the semantic "success" colour (`LightSuccess`) and nothing else.
 */

val LightBackground = Color(0xFFF7F8FC)
val LightCard = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF18202B)
val LightTextSecondary = Color(0xFF4F5A6B)
val LightDivider = Color(0xFFECEDEE)
val LightNavBar = Color(0xFFFFFFFF)
val LightNavIcon = Color(0xFF4F5A6B)
val LightBottomSheet = Color(0xFFFFFFFF)
val LightGreen = Color(0xFF2F6BFF)             // Jarvis blue accent fill (name is historical)
val LightGreenText = Color(0xFF1F5AE6)         // WCAG-safe blue for text on white (~5.7:1)
val LightSuccess = Color(0xFF0A7F5C)
val LightError = Color(0xFFD93636)
val LightWarning = Color(0xFFA8650A)
val LightInfo = Color(0xFF1F5AE6)
// Glass / depth tokens
val LightSurfaceElevated = Color(0xFFF7F8FC)
val LightScrim = Color(0x66000000)             // black @ 40%
val LightHairline = Color(0x1418202B)          // ink @ 8%
val LightGradientTop = Color(0xFFFFFFFF)
val LightGradientBottom = Color(0xFFF1F3F9)
val LightAccentMuted = Color(0xFFE6EDFF)       // blue @ 12% on white, opaque

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
