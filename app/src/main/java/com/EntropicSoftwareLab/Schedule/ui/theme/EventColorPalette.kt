package com.EntropicSoftwareLab.Schedule.ui.theme

/** One colour the picker offers by name. [argb] is always opaque. */
data class PaletteColor(val name: String, val argb: Int)

/**
 * The colours this app hands out on its own: the automatic colours for events nobody coloured.
 *
 * **Google Calendar's own colours** (2026-09-29, the user's call — "make Google Calendar's colours
 * comply with my app's", answered by making the app's match Google's, since Google Calendar can show
 * no colour outside its fixed set). The picker already offers only the account's Google colours
 * (see `EventEditorScreen.ColorPickerRow`); with this, every colour the app shows is one Google
 * Calendar also has, by name and hex (`GoogleCalendarColors`' canonical values).
 *
 * The six [brand] entries back the automatic per-day assignment, chosen as six clearly different
 * Google hues. Google's paler colours (Banana, Citron, Mango, Avocado, Cherry Blossom, Birch) are
 * left out of this list because they barely stand off a white card; they remain pickable in the
 * editor, where the user chooses them knowingly. Text on every fill is black or white by
 * `onChipColor`, and [EventColorPaletteTest] requires the better of the two to clear 4.5:1.
 */
object EventColorPalette {

    /**
     * The six that back the automatic per-day assignment, in assignment order.
     *
     * Six distinct hues, not six shades of one colour, so a day of uncoloured events wears six
     * different colours and consecutive automatic picks are always easy to tell apart.
     */
    val brand: List<PaletteColor> = listOf(
        PaletteColor("Peacock", 0xFF039BE5.toInt()),
        PaletteColor("Tomato", 0xFFD50000.toInt()),
        PaletteColor("Basil", 0xFF0B8043.toInt()),
        PaletteColor("Grape", 0xFF8E24AA.toInt()),
        PaletteColor("Tangerine", 0xFFF4511E.toInt()),
        PaletteColor("Lavender", 0xFF7986CB.toInt()),
    )

    /**
     * [brand], for the automatic per-day assignment — an alias, kept so a future rebalancing has
     * somewhere to diverge; `EventColorPaletteTest` checks consecutive entries clear its
     * neighbour-distance floor.
     */
    val assignmentOrder: List<PaletteColor> = brand

    /** [brand] and the rest of Google's colours that read well on a white card, by Google's names. */
    val colors: List<PaletteColor> = brand + listOf(
        PaletteColor("Beetroot", 0xFFD81B60.toInt()),
        PaletteColor("Flamingo", 0xFFE67C73.toInt()),
        PaletteColor("Pumpkin", 0xFFE65100.toInt()),
        PaletteColor("Pistachio", 0xFF7CB342.toInt()),
        PaletteColor("Sage", 0xFF33B679.toInt()),
        PaletteColor("Eucalyptus", 0xFF009688.toInt()),
        PaletteColor("Cobalt", 0xFF4285F4.toInt()),
        PaletteColor("Blueberry", 0xFF3F51B5.toInt()),
        PaletteColor("Amethyst", 0xFF9C27B0.toInt()),
        PaletteColor("Cocoa", 0xFF795548.toInt()),
        PaletteColor("Graphite", 0xFF616161.toInt()),
    )

    /** The palette's name for an exact colour, for captioning a swatch the user picked from it. */
    fun nameFor(argb: Int): String? =
        colors.firstOrNull { it.argb and 0xFFFFFF == argb and 0xFFFFFF }?.name
}
