package com.EntropicSoftwareLab.Schedule.data.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventColorCodecTest {

    @Test
    fun `round-trips a map of colours`() {
        val colors = mapOf(1L to 0xFF3560C4.toInt(), 99L to 0xFFE04F6B.toInt())
        assertEquals(colors, EventColorCodec.decode(EventColorCodec.encode(colors)))
    }

    @Test
    fun `empty and blank decode to nothing`() {
        assertTrue(EventColorCodec.decode(null).isEmpty())
        assertTrue(EventColorCodec.decode("").isEmpty())
        assertTrue(EventColorCodec.decode("   ").isEmpty())
        assertTrue(EventColorCodec.decode(EventColorCodec.encode(emptyMap())).isEmpty())
    }

    @Test
    fun `a corrupt blob decodes to nothing rather than throwing`() {
        assertTrue(EventColorCodec.decode("{not json").isEmpty())
        assertTrue(EventColorCodec.decode("null").isEmpty())
    }

    @Test
    fun `one unparseable entry is dropped and the rest survive`() {
        val json = """[{"id":1,"argb":-16777216},{"argb":123},{"id":7},{"id":2,"argb":-1}]"""
        val decoded = EventColorCodec.decode(json)
        assertEquals(setOf(1L, 2L), decoded.keys)
    }

    @Test
    fun `a stored colour is forced opaque`() {
        // Nothing in the picker can produce a transparent fill, so an alpha here came from a corrupt
        // blob — and a half-transparent event reads as a rendering bug, not as a colour.
        val decoded = EventColorCodec.decode("""[{"id":5,"argb":${0x0033B679}}]""")
        assertEquals(0xFF33B679.toInt(), decoded[5L])
    }

    @Test
    fun `negative event ids survive the trip`() {
        // Provider ids are positive today, but the codec must not silently lose a row to a sign bug.
        val colors = mapOf(-42L to 0xFF2F7D4F.toInt())
        assertEquals(colors, EventColorCodec.decode(EventColorCodec.encode(colors)))
    }
}
