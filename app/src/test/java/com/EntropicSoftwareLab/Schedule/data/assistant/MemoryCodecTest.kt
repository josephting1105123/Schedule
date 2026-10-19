package com.EntropicSoftwareLab.Schedule.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding has to be total. This blob sits next to a working calendar and is worth far less than it
 * — losing what was learned is a bad day, crashing the assistant tab over a malformed field is a
 * worse one.
 */
class MemoryCodecTest {

    private val populated = AssistantMemory(
        habits = listOf(
            Habit(
                key = "gym",
                title = "Gym",
                startMinute = 390,
                durationMinutes = 90,
                location = "PureGym",
                supporting = 4,
                contradicting = 1,
                lastSeenEpochDay = 20_000,
            ),
            Habit(key = "leave", title = "Leave", allDay = true, supporting = 2),
        ),
        corrections = listOf(
            Correction("gym tomorrow", ObservedEvent("Gym", 390, 90), 20_000),
        ),
        style = StyleProfile(recentDurations = listOf(30, 45, 60), lowercaseTitles = 2, capitalisedTitles = 7),
    )

    @Test
    fun `a full memory survives the round trip unchanged`() {
        assertEquals(populated, MemoryCodec.decode(MemoryCodec.encode(populated)))
    }

    @Test
    fun `an empty memory survives the round trip`() {
        assertEquals(AssistantMemory.EMPTY, MemoryCodec.decode(MemoryCodec.encode(AssistantMemory.EMPTY)))
    }

    @Test
    fun `nulls stay null rather than becoming the string null`() {
        val decoded = MemoryCodec.decode(MemoryCodec.encode(populated))
        val leave = decoded.habits.first { it.key == "leave" }
        assertEquals(null, leave.startMinute)
        assertEquals(null, leave.durationMinutes)
        assertEquals(null, leave.location)
        assertTrue(leave.allDay)
    }

    @Test
    fun `rubbish decodes to an empty memory instead of throwing`() {
        assertEquals(AssistantMemory.EMPTY, MemoryCodec.decode("{not json"))
        assertEquals(AssistantMemory.EMPTY, MemoryCodec.decode(""))
        assertEquals(AssistantMemory.EMPTY, MemoryCodec.decode(null))
        assertEquals(AssistantMemory.EMPTY, MemoryCodec.decode("[]"))
    }

    @Test
    fun `a half-written entry is dropped and the rest survives`() {
        val json = """
            {"habits":[{"title":"No key here"},{"key":"gym","title":"Gym","start":390}],
             "corrections":[{"event":{"title":"Orphan"}}]}
        """.trimIndent()
        val decoded = MemoryCodec.decode(json)
        assertEquals(1, decoded.habits.size)
        assertEquals("gym", decoded.habits.first().key)
        assertTrue(decoded.corrections.isEmpty())
    }

    @Test
    fun `a newer build's extra fields are ignored rather than fatal`() {
        val json = """{"habits":[{"key":"gym","title":"Gym","start":390,"mood":"chipper"}],"future":42}"""
        assertEquals(390, MemoryCodec.decode(json).habits.first().startMinute)
    }
}
