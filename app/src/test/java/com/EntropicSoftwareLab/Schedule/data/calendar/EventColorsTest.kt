package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventColorsTest {

    private fun option(argb: Int, key: String = "k") =
        EventColorOption(key = key, argb = argb, name = GoogleCalendarColors.nameFor(argb))

    @Test
    fun `the documented Google colours are named exactly`() {
        assertEquals("Tomato", GoogleCalendarColors.nameFor(0xFFD50000.toInt()))
        assertEquals("Peacock", GoogleCalendarColors.nameFor(0xFF039BE5.toInt()))
        assertEquals("Graphite", GoogleCalendarColors.nameFor(0xFF616161.toInt()))
        assertEquals("Basil", GoogleCalendarColors.nameFor(0xFF0B8043.toInt()))
    }

    @Test
    fun `alpha does not cost a colour its name`() {
        // A sync adapter that stores these without the high byte set should still get "Tomato".
        assertEquals("Tomato", GoogleCalendarColors.nameFor(0x00D50000))
    }

    @Test
    fun `a near colour is still named, a far one is not`() {
        // Close enough to Tomato to be worth calling Tomato.
        assertEquals("Tomato", GoogleCalendarColors.nameFor(0xFFD50010.toInt()))

        // White isn't close to any of the 24 — a guessed name here would be a lie.
        assertNull(GoogleCalendarColors.nameFor(0xFFFFFFFF.toInt()))
    }

    // --- canonicalize -------------------------------------------------

    @Test
    fun `canonicalize never returns fewer than the 24 canonical slots`() {
        assertEquals(24, GoogleCalendarColors.canonicalize(emptyList()).size)

        val partial = listOf(option(0xFFD50000.toInt(), "live-tomato"))
        assertEquals(24, GoogleCalendarColors.canonicalize(partial).size)
    }

    @Test
    fun `brand colours lead the picker, in order, regardless of live input`() {
        val result = GoogleCalendarColors.canonicalize(emptyList())
        assertEquals(
            listOf("Sage", "Eucalyptus", "Peacock", "Lavender"),
            result.take(4).map { it.name },
        )
    }

    @Test
    fun `a live row's real key is merged onto its canonical slot and marked available`() {
        val live = listOf(option(0xFFD50000.toInt(), "real-tomato-key"))
        val tomato = GoogleCalendarColors.canonicalize(live).first { it.name == "Tomato" }
        assertEquals("real-tomato-key", tomato.key)
        assertTrue(tomato.available)
    }

    @Test
    fun `a matched slot keeps the live row's own argb, not the guessed hex`() {
        // Close to Tomato but not the exact documented hex — this is what a real account's own
        // shade of a colour looks like.
        val nearTomato = 0xFFD50010.toInt()
        val live = listOf(option(nearTomato, "live-key"))
        val tomato = GoogleCalendarColors.canonicalize(live).first { it.name == "Tomato" }
        assertEquals(nearTomato, tomato.argb)
    }

    @Test
    fun `a canonical colour with no live match is unavailable`() {
        val tomato = GoogleCalendarColors.canonicalize(emptyList()).first { it.name == "Tomato" }
        assertFalse(tomato.available)
        assertEquals("canonical:Tomato", tomato.key)
    }

    @Test
    fun `a name collision keeps the first live row's key, not the last`() {
        // Two keys naming the same colour is exactly the shape of the "different subset on every
        // launch" bug: the mapping must be stable and not depend on cursor order.
        val live = listOf(
            option(0xFFD50000.toInt(), "first-key"),
            option(0xFFD50000.toInt(), "second-key"),
        )
        val tomato = GoogleCalendarColors.canonicalize(live).first { it.name == "Tomato" }
        assertEquals("first-key", tomato.key)
    }

    @Test
    fun `a collision loser is appended rather than dropped`() {
        val live = listOf(
            option(0xFFD50000.toInt(), "first-key"),
            option(0xFFD50000.toInt(), "second-key"),
        )
        val result = GoogleCalendarColors.canonicalize(live)
        val loser = result.first { it.key == "second-key" }
        assertTrue(loser.available)
        assertNull(loser.name)
        // Still real: the account genuinely offers it, so its own hex is what the picker shows.
        assertEquals("#D50000", loser.label)
    }

    @Test
    fun `an unnameable colour is appended with a null name so label falls back to hex`() {
        val far = 0xFFFFFFFF.toInt() // white — too far from all 24 to name.
        val live = listOf(option(far, "far-key"))
        val result = GoogleCalendarColors.canonicalize(live)

        // All 24 canonical slots are still present, unmatched, none of them stealing this colour.
        assertEquals(24, result.count { it.key.startsWith("canonical:") })

        val extra = result.first { it.key == "far-key" }
        assertTrue(extra.available)
        assertNull(extra.name)
        assertEquals("#FFFFFF", extra.label)
    }

    @Test
    fun `Google's colour keys are named by id, other accounts are not`() {
        assertEquals("Lavender", GoogleCalendarColors.nameForKey("1", "com.google"))
        assertEquals("Tomato", GoogleCalendarColors.nameForKey("11", "com.google"))
        assertEquals(null, GoogleCalendarColors.nameForKey("12", "com.google"))
        assertEquals(null, GoogleCalendarColors.nameForKey("1", "LOCAL"))
    }

    @Test
    fun `a Google row with an old shade still fills its named slot`() {
        // The adapter can report Tomato as the legacy #DC2127, too far from #D50000 for nameFor.
        val live = listOf(EventColorOption(key = "11", argb = 0xFFDC2127.toInt(), name = "Tomato"))
        val tomato = GoogleCalendarColors.canonicalize(live).first { it.name == "Tomato" }
        assertEquals("11", tomato.key)
        assertEquals(true, tomato.available)
    }

    @Test
    fun `legacy shades map to current ones, and no current shade is remapped`() {
        assertEquals(0xFFD50000.toInt(), GoogleCalendarColors.currentShade(0xFFDC2127.toInt()))
        // A few steps off legacy Tomato still reads as Tomato.
        assertEquals(0xFFD50000.toInt(), GoogleCalendarColors.currentShade(0xFFD8232B.toInt()))
        // Cobalt sits closest to a legacy shade (Blueberry) of any current colour; it must stay.
        assertEquals(0xFF4285F4.toInt(), GoogleCalendarColors.currentShade(0xFF4285F4.toInt()))
        GoogleCalendarColors.PALETTE_ORDER.forEach { name ->
            val argb = GoogleCalendarColors.argbForName(name)!!
            assertEquals(name, argb, GoogleCalendarColors.currentShade(argb))
        }
    }
}
