package com.EntropicSoftwareLab.Schedule.data.birthday

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BirthdayCodecTest {

    @Test
    fun `round trips a birthday with a year`() {
        val birthday = Birthday(id = 1, name = "Sarah", month = 8, day = 6, year = 1995)
        val decoded = BirthdayCodec.decode(BirthdayCodec.encode(listOf(birthday)))
        assertEquals(listOf(birthday), decoded)
    }

    @Test
    fun `round trips a birthday with no year`() {
        val birthday = Birthday(id = 2, name = "Ahmad", month = 12, day = 25, year = null)
        val decoded = BirthdayCodec.decode(BirthdayCodec.encode(listOf(birthday)))
        assertEquals(listOf(birthday), decoded)
    }

    @Test
    fun `round trips several birthdays in order`() {
        val birthdays = listOf(
            Birthday(id = 1, name = "A", month = 1, day = 1, year = 2000),
            Birthday(id = 2, name = "B", month = 2, day = 2, year = null),
        )
        assertEquals(birthdays, BirthdayCodec.decode(BirthdayCodec.encode(birthdays)))
    }

    @Test
    fun `round trips the linked event id`() {
        val birthday = Birthday(id = 3, name = "Mei", month = 2, day = 29, year = null, eventId = 77)
        assertEquals(listOf(birthday), BirthdayCodec.decode(BirthdayCodec.encode(listOf(birthday))))
    }

    @Test
    fun `a blob from before sync decodes with no event`() {
        val old = """[{"id":1,"name":"Sarah","month":8,"day":6,"year":1995}]"""
        assertEquals(null, BirthdayCodec.decode(old).single().eventId)
    }

    @Test
    fun `blank input decodes to empty`() {
        assertTrue(BirthdayCodec.decode(null).isEmpty())
        assertTrue(BirthdayCodec.decode("").isEmpty())
    }

    @Test
    fun `corrupt json decodes to empty rather than throwing`() {
        assertTrue(BirthdayCodec.decode("{not valid json").isEmpty())
    }

    @Test
    fun `an entry missing a name is dropped rather than failing the whole list`() {
        val json = """[{"id":1,"month":1,"day":1},{"id":2,"name":"Kept","month":3,"day":4}]"""
        val decoded = BirthdayCodec.decode(json)
        assertEquals(listOf("Kept"), decoded.map { it.name })
    }

    @Test
    fun `an entry with an out-of-range month or day is dropped`() {
        val json = """[
            {"id":1,"name":"BadMonth","month":13,"day":1},
            {"id":2,"name":"BadDay","month":1,"day":32},
            {"id":3,"name":"Good","month":6,"day":15}
        ]"""
        val decoded = BirthdayCodec.decode(json)
        assertEquals(listOf("Good"), decoded.map { it.name })
    }
}
