package com.EntropicSoftwareLab.Schedule.data.birthday

import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BirthdayEventsTest {

    private val today = LocalDate.of(2026, 10, 16)

    private fun row(
        id: Long,
        title: String,
        date: LocalDate,
        rrule: String? = "FREQ=YEARLY",
        owner: String? = "me@gmail.com",
        writable: Boolean = true,
    ) = BirthdayEventRow(id, title, date, rrule, owner, writable)

    // ---- writing ----

    @Test
    fun `title and name round trip`() {
        assertEquals("Sarah's birthday", BirthdayEvents.titleFor(" Sarah "))
        assertEquals("Sarah", BirthdayEvents.nameFrom(BirthdayEvents.titleFor("Sarah")))
    }

    @Test
    fun `name is read from the common title shapes`() {
        assertEquals("Sarah", BirthdayEvents.nameFrom("Sarah’s Birthday"))
        assertEquals("Mary Ann", BirthdayEvents.nameFrom("Mary Ann's birthday"))
        assertEquals("Ahmad", BirthdayEvents.nameFrom("Birthday: Ahmad"))
        assertEquals("Ahmad", BirthdayEvents.nameFrom("Ahmad birthday"))
        assertEquals("Ahmad Tan", BirthdayEvents.nameFrom("Ahmad Tan"))
    }

    @Test
    fun `a leap-day birthday recurs on the last day of February`() {
        assertEquals("FREQ=YEARLY", BirthdayEvents.rruleFor(8, 6))
        assertEquals("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1", BirthdayEvents.rruleFor(2, 29))
    }

    @Test
    fun `series starts in the birth year, or this year without one`() {
        val withYear = Birthday(1, "A", 8, 6, 1995)
        val noYear = Birthday(2, "B", 8, 6, null)
        assertEquals(LocalDate.of(1995, 8, 6), BirthdayEvents.seriesStart(withYear, today))
        assertEquals(LocalDate.of(2026, 8, 6), BirthdayEvents.seriesStart(noYear, today))
    }

    @Test
    fun `a leap-day series without a year starts on a real 29 February`() {
        val leap = Birthday(1, "A", 2, 29, null)
        assertEquals(LocalDate.of(2024, 2, 29), BirthdayEvents.seriesStart(leap, today))
    }

    // ---- ids ----

    @Test
    fun `calendar ids never collide with app ids and stay positive`() {
        val id = BirthdayEvents.idForEvent(42)
        assertTrue(id > System.currentTimeMillis())
        assertEquals(42L, BirthdayEvents.eventIdFor(id))
        assertNull(BirthdayEvents.eventIdFor(System.currentTimeMillis()))
    }

    // ---- recognising ----

    @Test
    fun `contacts calendar rows are birthdays whatever their title`() {
        val r = row(1, "Ahmad Tan", today, rrule = null,
            owner = "addressbook" + BirthdayEvents.CONTACTS_OWNER_SUFFIX, writable = false)
        assertTrue(BirthdayEvents.isBirthday(r))
    }

    @Test
    fun `a birthday title counts whether or not the row repeats`() {
        assertTrue(BirthdayEvents.isBirthday(row(1, "Mum's birthday", today)))
        // Google Calendar's own Birthday events reach the provider with no yearly rule.
        assertTrue(BirthdayEvents.isBirthday(row(2, "Sarah's birthday", today, rrule = null)))
        assertTrue(BirthdayEvents.isBirthday(row(3, "Birthday: Ahmad", today, rrule = null)))
    }

    @Test
    fun `an event about a birthday, or no birthday at all, does not count`() {
        assertFalse(BirthdayEvents.isBirthday(row(1, "Mum's birthday dinner", today, rrule = null)))
        assertFalse(BirthdayEvents.isBirthday(row(2, "Anniversary", today)))
        assertFalse(BirthdayEvents.isBirthday(row(3, "Birthday", today)))
    }

    // ---- merging ----

    @Test
    fun `without a provider read the stored list is shown unchanged`() {
        val local = listOf(Birthday(1, "A", 1, 2, null, eventId = 9))
        assertEquals(local, BirthdayEvents.merge(local, null))
    }

    @Test
    fun `a linked birthday takes the name and day edited in Google, keeping its year`() {
        val local = listOf(Birthday(1, "Sara", 8, 6, 1995, eventId = 9))
        val merged = BirthdayEvents.merge(local, listOf(row(9, "Sarah's birthday", LocalDate.of(1995, 8, 7))))
        assertEquals(listOf(Birthday(1, "Sarah", 8, 7, 1995, eventId = 9)), merged)
    }

    @Test
    fun `a linked birthday whose event is gone is left out`() {
        val local = listOf(Birthday(1, "A", 1, 2, null, eventId = 9))
        assertTrue(BirthdayEvents.merge(local, emptyList()).isEmpty())
        assertEquals(local, BirthdayEvents.deletedElsewhere(local, emptyList()))
    }

    @Test
    fun `an unlinked birthday is shown and never counted as deleted`() {
        val local = listOf(Birthday(1, "A", 1, 2, null))
        assertEquals(local, BirthdayEvents.merge(local, emptyList()))
        assertTrue(BirthdayEvents.deletedElsewhere(local, emptyList()).isEmpty())
    }

    @Test
    fun `a calendar-only birthday is added once, read-only when its calendar is`() {
        val contacts = "addressbook" + BirthdayEvents.CONTACTS_OWNER_SUFFIX
        val rows = listOf(
            row(5, "Ahmad", LocalDate.of(2020, 3, 4), owner = contacts, writable = false),
            row(6, "Ahmad's birthday", LocalDate.of(2026, 3, 4)),
        )
        val merged = BirthdayEvents.merge(emptyList(), rows)
        assertEquals(1, merged.size)
        val b = merged.single()
        assertEquals("Ahmad", b.name)
        assertEquals(3 to 4, b.month to b.day)
        assertNull(b.year)
        assertEquals(5L, b.eventId)
        assertEquals(BirthdayEvents.idForEvent(5), b.id)
        assertTrue(b.readOnly)
        assertEquals(setOf(6L), b.otherEventIds)
    }

    @Test
    fun `one row per year is one birthday, and every copy is hidden from the event list`() {
        val rows = listOf(
            row(7, "Sarah's birthday", LocalDate.of(2025, 8, 6), rrule = null),
            row(8, "Sarah's birthday", LocalDate.of(2026, 8, 6), rrule = null),
        )
        val merged = BirthdayEvents.merge(emptyList(), rows)
        assertEquals(1, merged.size)
        fun event(id: Long) = EventInstance(id, id, "Sarah's birthday", null, 0, 1, true, 1, 0)
        assertTrue(Birthday.withoutBirthdayEvents(listOf(event(7), event(8)), merged).isEmpty())
    }

    @Test
    fun `the app's own event is not listed a second time as a calendar birthday`() {
        val local = listOf(Birthday(1, "Sarah", 8, 6, null, eventId = 9))
        val merged = BirthdayEvents.merge(local, listOf(row(9, "Sarah's birthday", LocalDate.of(2026, 8, 6))))
        assertEquals(1, merged.size)
        assertEquals(1L, merged.single().id)
    }

    @Test
    fun `birthday events are dropped from an event list`() {
        fun event(id: Long) = EventInstance(id, id, "e$id", null, 0, 1, true, 1, 0)
        val events = listOf(event(9), event(10))
        val birthdays = listOf(Birthday(1, "A", 1, 2, null, eventId = 9))
        assertEquals(listOf(event(10)), Birthday.withoutBirthdayEvents(events, birthdays))
    }
}
