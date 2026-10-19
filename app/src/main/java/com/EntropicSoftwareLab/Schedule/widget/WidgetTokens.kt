package com.EntropicSoftwareLab.Schedule.widget

import androidx.glance.color.ColorProvider
import androidx.glance.text.FontFamily
import androidx.compose.ui.graphics.Color

/**
 * The widget's copy of the design tokens.
 *
 * Glance renders outside the Compose composition, so it can't read `LocalAppColors`. These values
 * are duplicated from `ui/theme/Color.kt` by hand — if a colour changes there, change it here too
 * (TOKENS.md records this as the second surface).
 *
 * A widget sits on the launcher's wallpaper rather than on the app's window, so its surface is the
 * app's **card** colour and there is no `background` here at all: what the user is looking at is one
 * card floating on whatever is behind it, which is exactly what the Month page's calendar is.
 *
 * The app is light-only now. Every [ColorProvider] here still carries a `night` value (Glance
 * requires one), but it is set equal to `day` on purpose — the Today widget stays the same light
 * card in system dark mode rather than following it, matching the app itself. `MonthWidgetColors`
 * below is unrelated to this and untouched: it is a deliberate fixed dark-plum design, never day/night.
 */
internal object WidgetTokens {
    val LightCard = Color(0xFFFFFFFF)

    private val LightTextPrimary = Color(0xFF0F172A)
    private val LightTextSecondary = Color(0xFF475569)

    /** WCAG-safe indigo accent text on the card (>=4.5:1). Matches `AppColors.greenText`. */
    private val LightGreenText = Color(0xFF4338CA)

    /** Indigo accent fill. Matches `AppColors.green`. Name is historical, see `AppColors.green`. */
    val LightGreen = Color(0xFF4F46E5)

    /**
     * Deprecated alias for [LightGreen], kept only because `MonthWidget.kt` still reads it as the
     * fallback fill colour for an event with no colour of its own. Do not add new uses; read
     * [LightGreen] instead.
     */
    @Deprecated("Use LightGreen — this alias exists only for MonthWidget.kt's existing call site.")
    val DarkGreen = LightGreen

    // 1dp glass border on the app's cards; on the widget it rules one week off from the next, the
    // same job MonthChipLayer gives it on the Month page.
    private val LightHairline = Color(0x0F0F172A)   // slate-900 @ 6%

    // A day from the neighbouring month stays readable and recedes — DayCell's
    // `textSecondary.copy(alpha = 0.4f)`, flattened here because Glance has no alpha modifier.
    private val LightOutOfMonth = Color(0x66475569)

    // Text drawn *on* an event's own colour, which is not the card colour. A band can be any colour
    // the user picked, so the month widget chooses between these by the fill's luminance rather than
    // by the theme — a pale band still needs dark text on it. Raw Colors, not ColorProviders: the
    // choice is made per band before a provider is built.
    val OnLightFill = Color(0xFF101215)
    val OnDarkFill = Color(0xFFFAFAFA)

    val card = ColorProvider(day = LightCard, night = LightCard)
    val textPrimary = ColorProvider(day = LightTextPrimary, night = LightTextPrimary)
    val textSecondary = ColorProvider(day = LightTextSecondary, night = LightTextSecondary)
    val outOfMonth = ColorProvider(day = LightOutOfMonth, night = LightOutOfMonth)
    val greenText = ColorProvider(day = LightGreenText, night = LightGreenText)
    val green = ColorProvider(day = LightGreen, night = LightGreen)
    val hairline = ColorProvider(day = LightHairline, night = LightHairline)

    /**
     * The app is a single-voice serif system (`ui/theme/Type.kt`), and a widget rendering the same
     * calendar in the platform sans-serif reads as a different app's. `FontFamily.Serif` resolves to
     * the platform serif, which is what `FontFamily.Serif` in Compose resolves to as well, so the two
     * surfaces land on the same typeface without either bundling a font.
     */
    val serif = FontFamily.Serif
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
