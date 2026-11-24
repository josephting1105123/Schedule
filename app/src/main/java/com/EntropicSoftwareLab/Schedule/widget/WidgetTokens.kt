package com.EntropicSoftwareLab.Schedule.widget

import androidx.glance.color.ColorProvider
import androidx.glance.text.FontFamily
import androidx.compose.ui.graphics.Color

/**
 * What the Month widget still reads from the app's design tokens, duplicated here by hand.
 *
 * Glance renders outside the Compose composition, so it can't read `LocalAppColors`. These values
 * are copied from `ui/theme/Color.kt` — if a colour changes there, change it here too (TOKENS.md
 * records this as the second surface). Only the fallback event fill and the two inks drawn on an
 * event's own colour are left; the two Today widgets (Agenda, Day at a glance) take
 * `GlanceTheme.colors` (Material You) and `FontFamily.SansSerif` instead, and
 * `MonthWidgetColors` below is the month widget's deliberate fixed dark-plum design, never
 * day/night.
 */
internal object WidgetTokens {
    /** Blue accent fill. Matches `AppColors.green`. Name is historical, see `AppColors.green`. */
    val LightGreen = Color(0xFF2F6BFF)

    /**
     * Deprecated alias for [LightGreen], kept only because `MonthWidget.kt` still reads it as the
     * fallback fill colour for an event with no colour of its own. Do not add new uses; read
     * [LightGreen] instead.
     */
    @Deprecated("Use LightGreen — this alias exists only for MonthWidget.kt's existing call site.")
    val DarkGreen = LightGreen

    // Text drawn *on* an event's own colour, which is not the card colour. A band can be any colour
    // the user picked, so the month widget chooses between these by the fill's luminance rather than
    // by the theme — a pale band still needs dark text on it. Raw Colors, not ColorProviders: the
    // choice is made per band before a provider is built.
    val OnLightFill = Color(0xFF101215)
    val OnDarkFill = Color(0xFFFAFAFA)
}

/**
 * The month widget's palette — a dark plum card, light text and a lavender "+" pill, sampled from
 * the reference design it was asked to match.
 *
 * Deliberately **not** the app's tokens and **not** day/night: the reference is a dark card on a
 * phone in light mode, so the widget is the same card whatever the system theme. The drawables that
 * need these (card, pill, today disc, column rules, icons) read the `widget_month_*` colour
 * resources instead, because a shape can't take a ColorProvider; keep the two in step.
 */
internal object MonthWidgetColors {
    val Card = Color(0xFF352C3D)
    val Text = Color(0xFFE8E0EA)
    val Muted = Color(0xFFCFC4D3)
    val OutOfMonth = Color(0xFF8E8794)
    val OnPill = Color(0xFF3C2454)

    val text = ColorProvider(day = Text, night = Text)
    val muted = ColorProvider(day = Muted, night = Muted)
    val outOfMonth = ColorProvider(day = OutOfMonth, night = OutOfMonth)
    val onPill = ColorProvider(day = OnPill, night = OnPill)

    /** The reference sets everything in the platform sans-serif, not the app's serif. */
    val sans = FontFamily.SansSerif
}
