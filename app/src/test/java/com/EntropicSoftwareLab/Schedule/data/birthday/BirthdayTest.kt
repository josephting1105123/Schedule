package com.EntropicSoftwareLab.Schedule.data.birthday

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BirthdayTest {

    private fun leapBirthday(year: Int? = 2000) =
        Birthday(id = 1, name = "Leap", month = 2, day = 29, year = year)

    private fun ordinaryBirthday(year: Int? = 1990) =
        Birthday(id = 2, name = "Ordinary", month = 6, day = 15, year = year)

    @Test
    fun `29 February falls back to 28 February in a common year`() {
        assertEquals(LocalDate.of(2026, 2, 28), leapBirthday().occurrenceIn(2026))
    }

    @Test
    fun `29 February stays on the 29th in a leap year`() {
        assertEquals(LocalDate.of(2028, 2, 29), leapBirthday().occurrenceIn(2028))
    }

    @Test
    fun `an ordinary date is unaffected`() {
        assertEquals(LocalDate.of(2026, 6, 15), ordinaryBirthday().occurrenceIn(2026))
    }

    @Test
    fun `ageOn is null without a birth year`() {
        assertNull(ordinaryBirthday(year = null).ageOn(LocalDate.of(2026, 6, 15)))
    }

    @Test
    fun `ageOn counts full years elapsed`() {
        val birthday = ordinaryBirthday(year = 1990)
        assertEquals(36, birthday.ageOn(LocalDate.of(2026, 6, 15)))
        // The day before the birthday, the new age hasn't turned yet.
        assertEquals(35, birthday.ageOn(LocalDate.of(2026, 6, 14)))
    }

    @Test
    fun `byDate resolves each birthday onto every year the range touches`() {
        val birthdays = listOf(ordinaryBirthday(year = 1990))
        val byDate = Birthday.byDate(
            birthdays,
            from = LocalDate.of(2026, 1, 1),
            toExclusive = LocalDate.of(2028, 1, 1),
        )
        assertEquals(
            setOf(LocalDate.of(2026, 6, 15), LocalDate.of(2027, 6, 15)),
            byDate.keys,
        )
    }

    @Test
    fun `byDate spans a range crossing a new year`() {
        val birthday = Birthday(id = 3, name = "NewYear", month = 1, day = 3, year = null)
        val byDate = Birthday.byDate(
            listOf(birthday),
            from = LocalDate.of(2026, 12, 20),
            toExclusive = LocalDate.of(2027, 1, 10),
        )
        assertEquals(setOf(LocalDate.of(2027, 1, 3)), byDate.keys)
    }

    @Test
    fun `byDate sorts same-day birthdays by name`() {
        val day = LocalDate.of(2026, 6, 15)
        val birthdays = listOf(
            Birthday(id = 1, name = "Zed", month = 6, day = 15, year = null),
            Birthday(id = 2, name = "Anna", month = 6, day = 15, year = null),
        )
        val byDate = Birthday.byDate(birthdays, day, day.plusDays(1))
        assertEquals(listOf("Anna", "Zed"), byDate.getValue(day).map { it.name })
    }

    @Test
    fun `byDate is empty for a backwards or empty range`() {
        val birthdays = listOf(ordinaryBirthday())
        assertTrue(Birthday.byDate(birthdays, LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15)).isEmpty())
        assertTrue(Birthday.byDate(birthdays, LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 1)).isEmpty())
    }
}
