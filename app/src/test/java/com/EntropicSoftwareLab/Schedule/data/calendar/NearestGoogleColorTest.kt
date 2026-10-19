package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GoogleCalendarColors.nearestAvailable] is what keeps a custom colour from reverting to the
 * calendar's default in every *other* calendar app — see the doc on the function itself.
 */
class NearestGoogleColorTest {

    private fun option(key: String, rgb: Int, available: Boolean = true) =
        EventColorOption(key = key, argb = 0xFF000000.toInt() or rgb, name = null, available = available)

    private val live = listOf(
        option("1", 0xD50000), // Tomato
        option("2", 0x0B8043), // Basil
        option("3", 0x3F51B5), // Blueberry
    )

    @Test
    fun `picks the closest colour the account offers`() {
        // A custom green nowhere near Google's, but far closer to Basil than to Tomato or Blueberry.
        val picked = GoogleCalendarColors.nearestAvailable(0xFF2F7D4F.toInt(), live)
        assertEquals("2", picked?.key)
    }

    @Test
    fun `an exact match picks itself`() {
        assertEquals("3", GoogleCalendarColors.nearestAvailable(0xFF3F51B5.toInt(), live)?.key)
    }

    @Test
    fun `there is no distance cutoff`() {
        // nameFor would refuse to name this; nearestAvailable must still approximate it, because the
        // alternative is the calendar's default colour, which is further away than any of the three.
        // Cyan lands on Blueberry (39721) rather than Basil (51594) — the blue channel dominates.
        assertNotNull(GoogleCalendarColors.nearestAvailable(0xFF00FFFF.toInt(), live))
        assertEquals("3", GoogleCalendarColors.nearestAvailable(0xFF00FFFF.toInt(), live)?.key)
    }

    @Test
    fun `a sentinel slot is never returned`() {
        // canonicalize emits "canonical:<Name>" for a colour the account did NOT offer. Writing one
        // of those to EVENT_COLOR_KEY sets nothing at all.
        val withSentinel = listOf(
            option("canonical:Tomato", 0xD50000, available = false),
            option("real", 0x616161, available = true),
        )
        val picked = GoogleCalendarColors.nearestAvailable(0xFFD50000.toInt(), withSentinel)
        assertEquals("real", picked?.key)
    }

    @Test
    fun `an account offering nothing returns null`() {
        assertNull(GoogleCalendarColors.nearestAvailable(0xFF3560C4.toInt(), emptyList()))
        assertNull(
            GoogleCalendarColors.nearestAvailable(
                0xFF3560C4.toInt(),
                listOf(option("canonical:Sage", 0x33B679, available = false)),
            )
        )
    }

    @Test
    fun `canonicalize output is safe to pass straight in`() {
        // The realistic call site: the editor holds whatever canonicalize produced, sentinels and all.
        val canonical = GoogleCalendarColors.canonicalize(live)
        val picked = GoogleCalendarColors.nearestAvailable(0xFF2F7D4F.toInt(), canonical)
        assertEquals("2", picked?.key)
    }
}
