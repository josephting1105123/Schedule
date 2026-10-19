package com.EntropicSoftwareLab.Schedule.data.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventColorResolverTest {

    private val custom = 0xFF7A4FC0.toInt()
    // Not one of Google's colours, so displayShade leaves it as it is.
    private val providerColor = 0xFF2E7D6A.toInt()

    @Test
    fun `the app's colour wins over the provider's`() {
        val resolved = EventColorResolver.argbFor(
            eventId = 1L,
            displayColor = providerColor,
            overrides = mapOf(1L to custom),
        )
        assertEquals(custom, resolved)
    }

    @Test
    fun `an event with no app colour keeps the provider's`() {
        val resolved = EventColorResolver.argbFor(
            eventId = 1L,
            displayColor = providerColor,
            overrides = mapOf(2L to custom),
        )
        assertEquals(providerColor, resolved)
    }

    @Test
    fun `no colour anywhere returns null so the caller uses its own fallback`() {
        // displayColor is 0 for a provider row carrying neither an event nor a calendar colour.
        assertNull(EventColorResolver.argbFor(1L, displayColor = 0, overrides = emptyMap()))
    }

    @Test
    fun `an app colour still wins when the provider has none`() {
        assertEquals(custom, EventColorResolver.argbFor(1L, 0, mapOf(1L to custom)))
    }

    @Test
    fun `an older Google shade is drawn as the one Google Calendar shows`() {
        // Tomato as Google's sync reports it, drawn as Google Calendar's app draws Tomato.
        assertEquals(0xFFD26042.toInt(), EventColorResolver.argbFor(1L, 0xFFDC2127.toInt(), emptyMap()))
        // An automatic colour arriving through the overrides map is drawn the same way.
        assertEquals(0xFF78A8E4.toInt(), EventColorResolver.argbFor(1L, 0, mapOf(1L to 0xFF039BE5.toInt())))
        // Any other colour passes through untouched.
        assertEquals(providerColor, EventColorResolver.argbFor(1L, providerColor, emptyMap()))
    }
}
