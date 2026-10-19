package com.EntropicSoftwareLab.Schedule.ui.theme

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette's shape, internal consistency, and its contrast against the one card it needs to read
 * on now that the app is light-only.
 *
 * Pins that brand leads the picker, that consecutive brand colours differ enough to tell apart, that
 * no two colours collide, that every colour is fully opaque, that `nameFor` round-trips, that the
 * ink `onChipColor` picks clears 4.5:1 (WCAG AA) on every colour, and that every colour still stands
 * off the white card. White text itself is no longer required to clear 4.5:1 — see EventColorPalette.
 */
class EventColorPaletteTest {

    @Test
    fun `brand leads the picker`() {
        assertEquals(EventColorPalette.brand, EventColorPalette.colors.take(EventColorPalette.brand.size))
    }

    @Test
    fun `consecutive assignment colours are far enough apart to tell apart`() {
        // The point of using six distinct hues for brand/assignmentOrder rather than shades of one:
        // two 13dp bands stacked in a widget cell must not read as one colour.
        val order = EventColorPalette.assignmentOrder
        for (i in order.indices) {
            val a = order[i]
            val b = order[(i + 1) % order.size]
            assertTrue(
                "${a.name} and ${b.name} are too close to sit next to each other",
                squaredDistance(a.argb, b.argb) > MIN_NEIGHBOUR_DISTANCE,
            )
        }
    }

    @Test
    fun `no two colours in the palette are the same`() {
        val rgb = EventColorPalette.colors.map { it.argb and 0xFFFFFF }
        assertEquals(rgb.size, rgb.toSet().size)
        val names = EventColorPalette.colors.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every colour is fully opaque`() {
        // A translucent event fill reads as a rendering fault, and the codec forces opacity on read;
        // the source list must not disagree with it.
        assertTrue(EventColorPalette.colors.all { (it.argb ushr 24) and 0xFF == 0xFF })
    }

    @Test
    fun `nameFor round-trips every colour and refuses an unknown one`() {
        EventColorPalette.colors.forEach { assertEquals(it.name, EventColorPalette.nameFor(it.argb)) }
        // A custom colour has no name, and inventing one would caption the swatch with a lie.
        assertEquals(null, EventColorPalette.nameFor(0xFF123456.toInt()))
    }

    @Test
    fun `text on every colour reads at AA`() {
        // Every surface that writes on an event fill picks black or white ink by `onChipColor`, so
        // what has to clear WCAG AA (4.5:1) is the better of the two inks, not white specifically.
        // Holding white to 4.5:1 is what used to force every hue down to a dark shade.
        EventColorPalette.colors.forEach { colour ->
            val fill = relativeLuminance(colour.argb)
            val best = maxOf(
                contrastRatio(fill, relativeLuminance(DARK_INK)),
                contrastRatio(fill, relativeLuminance(LIGHT_INK)),
            )
            assertTrue(
                "${colour.name} (#${hex(colour.argb)}) gives its best ink only " +
                    "${"%.2f".format(best)}:1 — must be >= 4.5. Move it lighter or darker along its hue.",
                best >= 4.5,
            )
        }
    }

    @Test
    fun `every colour stands off the white card`() {
        // A fill still has to read as a block on the card, not a tint of it. Google's palest
        // colours (Banana, Citron, Mango, Avocado at ~1.8:1) are left out of this list for failing this.
        EventColorPalette.colors.forEach { colour ->
            val ratio = contrastRatio(relativeLuminance(colour.argb), relativeLuminance(WHITE))
            assertTrue(
                "${colour.name} (#${hex(colour.argb)}) is only ${"%.2f".format(ratio)}:1 on white",
                ratio >= MIN_CARD_CONTRAST,
            )
        }
    }

    private fun hex(argb: Int) = Integer.toHexString(argb and 0xFFFFFF).uppercase().padStart(6, '0')

    private fun squaredDistance(a: Int, b: Int): Int {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /** WCAG relative luminance (sRGB, D65). `argb` may carry an alpha byte; only RGB is read. */
    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio between two relative luminances — order-independent. */
    private fun contrastRatio(l1: Double, l2: Double): Double {
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    private companion object {
        /** Comfortably past the ~2700 at which `GoogleCalendarColors` stops calling two colours alike. */
        const val MIN_NEIGHBOUR_DISTANCE = 6000

        // LightCard in ui/theme/Color.kt — the fill sits on this card, the only one now.
        const val WHITE = 0xFFFFFF

        // The two inks `onChipColor` (ui/components/ChipColors.kt) chooses between.
        const val DARK_INK = 0x101215
        const val LIGHT_INK = 0xFAFAFA

        const val MIN_CARD_CONTRAST = 1.95
    }
}
