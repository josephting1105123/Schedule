package com.EntropicSoftwareLab.Schedule.data.accounts

import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFilterTest {

    private fun cal(
        id: Long,
        name: String,
        account: String,
        primary: Boolean = false,
        owner: String = "",
        color: Int = id.toInt(),
    ) = CalendarInfo(
        id = id,
        displayName = name,
        accountName = account,
        accountType = "com.google",
        color = color,
        isPrimary = primary,
        isWritable = true,
        ownerAccount = owner,
    )

    private val aMain = cal(1, "a@gmail.com", "a@gmail.com", primary = true)
    private val aWork = cal(2, "Work", "a@gmail.com")
    private val aBirthdays = cal(3, "Birthdays", "a@gmail.com", owner = "a@gmail.com#contacts@group.v.calendar.google.com")
    private val bMain = cal(4, "b@uni.edu", "b@uni.edu", primary = true)
    private val bWork = cal(5, "Work", "b@uni.edu")
    private val all = listOf(aMain, aWork, aBirthdays, bMain, bWork)

    private fun added(vararg names: String) = AccountPrefs(added = names.map { AddedAccount(it, "com.google") })

    @Test
    fun `usable returns everything when no account was added`() {
        assertEquals(all, CalendarFilter.usable(all, AccountPrefs()))
    }

    @Test
    fun `usable returns only the added account's calendars`() {
        assertEquals(listOf(aMain, aWork, aBirthdays), CalendarFilter.usable(all, added("a@gmail.com")))
    }

    @Test
    fun `shown drops a hidden account and a hidden calendar`() {
        val prefs = added("a@gmail.com", "b@uni.edu").copy(
            hiddenAccounts = setOf("b@uni.edu"),
            hiddenCalendarIds = setOf(2L),
        )
        assertEquals(listOf(aMain, aBirthdays), CalendarFilter.shown(all, prefs))
    }

    @Test
    fun `contacts birthdays calendar is recognised by its owner`() {
        assertTrue(CalendarFilter.isContactsBirthdays(aBirthdays))
        assertFalse(CalendarFilter.isContactsBirthdays(aWork))
        assertTrue(
            CalendarFilter.isContactsBirthdays(aWork.copy(ownerAccount = "X#CONTACTS@GROUP.V.CALENDAR.GOOGLE.COM")),
        )
    }

    @Test
    fun `chips list accounts then extra calendars then Birthday last`() {
        val chips = CalendarFilter.chips(all, AccountPrefs())
        assertEquals(
            listOf(
                CalendarChip.Account::class, CalendarChip.Account::class,
                CalendarChip.Calendar::class, CalendarChip.Calendar::class,
                CalendarChip.Birthdays::class,
            ),
            chips.map { it::class },
        )
        assertEquals(listOf("a@gmail.com", "b@uni.edu"), chips.filterIsInstance<CalendarChip.Account>().map { it.accountName })
        assertTrue(chips.last() is CalendarChip.Birthdays)
    }

    @Test
    fun `the primary calendar and contacts birthdays are not listed as calendar chips`() {
        val ids = CalendarFilter.chips(all, AccountPrefs()).filterIsInstance<CalendarChip.Calendar>().map { it.calendarId }
        assertEquals(listOf(2L, 5L), ids)
    }

    @Test
    fun `account chip takes the primary calendar's colour and the tick state`() {
        val prefs = AccountPrefs(hiddenAccounts = setOf("b@uni.edu"), hiddenCalendarIds = setOf(2L))
        val chips = CalendarFilter.chips(all, prefs)
        val a = chips.filterIsInstance<CalendarChip.Account>().first { it.accountName == "a@gmail.com" }
        val b = chips.filterIsInstance<CalendarChip.Account>().first { it.accountName == "b@uni.edu" }
        assertEquals(aMain.color, a.color)
        assertTrue(a.shown)
        assertFalse(b.shown)
        assertFalse(chips.filterIsInstance<CalendarChip.Calendar>().first { it.calendarId == 2L }.shown)
    }

    @Test
    fun `duplicate calendar names get the account label suffix`() {
        val chips = CalendarFilter.chips(all, AccountPrefs()).filterIsInstance<CalendarChip.Calendar>()
        assertEquals(listOf("Work · a@gmail.com", "Work · b@uni.edu"), chips.map { it.label })
    }

    @Test
    fun `a unique calendar name stays plain`() {
        val chips = CalendarFilter.chips(listOf(aMain, aWork), AccountPrefs()).filterIsInstance<CalendarChip.Calendar>()
        assertEquals(listOf("Work"), chips.map { it.label })
    }

    @Test
    fun `nickname is used in labels`() {
        val prefs = AccountPrefs(added = listOf(AddedAccount("a@gmail.com", "com.google", "Personal")))
        val chips = CalendarFilter.chips(all, prefs)
        assertEquals("Personal", chips.filterIsInstance<CalendarChip.Account>().single().label)
        assertEquals("Personal", CalendarFilter.label(aMain, all, prefs))
        assertEquals("Work · Personal", CalendarFilter.label(aWork, all, prefs))
    }

    @Test
    fun `label is the account for the primary calendar and name dot account for the rest`() {
        assertEquals("a@gmail.com", CalendarFilter.label(aMain, all, AccountPrefs()))
        assertEquals("Work · b@uni.edu", CalendarFilter.label(bWork, all, AccountPrefs()))
    }

    @Test
    fun `accountLabel falls back to the email when the nickname is blank`() {
        val prefs = AccountPrefs(added = listOf(AddedAccount("a@gmail.com", "com.google", "  ")))
        assertEquals("a@gmail.com", CalendarFilter.accountLabel("a@gmail.com", prefs))
        assertEquals("a@gmail.com", CalendarFilter.accountLabel("a@gmail.com", AccountPrefs()))
    }

    @Test
    fun `primaryOf prefers primary then the calendar named after the account then the first`() {
        val named = cal(10, "x@y.z", "x@y.z")
        val other = cal(11, "Other", "x@y.z")
        assertEquals(aMain, CalendarFilter.primaryOf(listOf(aWork, aMain)))
        assertEquals(named, CalendarFilter.primaryOf(listOf(other, named)))
        assertEquals(other, CalendarFilter.primaryOf(listOf(other, cal(12, "More", "x@y.z"))))
        assertEquals(null, CalendarFilter.primaryOf(emptyList()))
    }

    @Test
    fun `the Birthday chip carries the flamingo colour and the tick`() {
        val chip = CalendarFilter.chips(emptyList(), AccountPrefs(birthdaysShown = false)).single()
        assertEquals("Birthday", chip.label)
        assertEquals(BIRTHDAY_CHIP_COLOR, chip.color)
        assertFalse(chip.shown)
    }

    @Test
    fun `a LOCAL calendar is kept even though its sync flag reads false`() {
        val local = cal(20, "On phone", "me@local").copy(accountType = "LOCAL", syncEvents = false)
        assertTrue(CalendarFilter.synced(local))
        assertEquals(listOf(local), CalendarFilter.usable(listOf(local), AccountPrefs()))
        assertTrue(CalendarFilter.chips(listOf(local), AccountPrefs()).any { it is CalendarChip.Account })
    }

    @Test
    fun `a synced-account calendar with sync off is dropped from usable shown and chips`() {
        val off = aWork.copy(syncEvents = false)
        val calendars = listOf(aMain, off)
        assertFalse(CalendarFilter.synced(off))
        assertEquals(listOf(aMain), CalendarFilter.usable(calendars, AccountPrefs()))
        assertEquals(listOf(aMain), CalendarFilter.shown(calendars, AccountPrefs()))
        assertTrue(CalendarFilter.chips(calendars, AccountPrefs()).filterIsInstance<CalendarChip.Calendar>().isEmpty())
        // The account itself is still addable, so the sync card can show it.
        assertEquals(calendars, CalendarFilter.inAddedAccounts(calendars, AccountPrefs()))
    }

    @Test
    fun `the Tasks calendar is recognised by name, ignoring case and padding`() {
        assertTrue(CalendarFilter.isTaskCalendar(cal(30, "Tasks", "a@gmail.com")))
        assertTrue(CalendarFilter.isTaskCalendar(cal(31, " tasks ", "a@gmail.com")))
        assertTrue(CalendarFilter.isTaskCalendar(cal(32, "TASKS", "a@gmail.com")))
        assertFalse(CalendarFilter.isTaskCalendar(cal(33, "Tasks list", "a@gmail.com")))
        assertFalse(CalendarFilter.isTaskCalendar(aMain))
    }

    @Test
    fun `the Tasks calendar is not usable shown or a chip, with no account added`() {
        val tasks = cal(30, "Tasks", "a@gmail.com")
        val padded = cal(31, " tasks ", "a@gmail.com")
        val calendars = listOf(aMain, tasks, padded)
        assertEquals(listOf(aMain), CalendarFilter.usable(calendars, AccountPrefs()))
        assertEquals(listOf(aMain), CalendarFilter.shown(calendars, AccountPrefs()))
        assertTrue(CalendarFilter.chips(calendars, AccountPrefs()).filterIsInstance<CalendarChip.Calendar>().isEmpty())
    }

    @Test
    fun `the Tasks calendar stays hidden when its account is added`() {
        val tasks = cal(30, "Tasks", "a@gmail.com")
        val calendars = listOf(aMain, tasks)
        val prefs = added("a@gmail.com")
        assertEquals(listOf(aMain), CalendarFilter.usable(calendars, prefs))
        assertEquals(listOf(aMain), CalendarFilter.shown(calendars, prefs))
        assertTrue(CalendarFilter.chips(calendars, prefs).filterIsInstance<CalendarChip.Calendar>().isEmpty())
        // It is still in the account's calendars, which is how the repository finds it to sync.
        assertEquals(calendars, CalendarFilter.inAddedAccounts(calendars, prefs))
    }

    @Test
    fun `a calendar named Tasks list is an ordinary calendar`() {
        val list = cal(30, "Tasks list", "a@gmail.com")
        val calendars = listOf(aMain, list)
        assertEquals(calendars, CalendarFilter.usable(calendars, AccountPrefs()))
        assertEquals(listOf(30L), CalendarFilter.chips(calendars, AccountPrefs()).filterIsInstance<CalendarChip.Calendar>().map { it.calendarId })
    }

    @Test
    fun `readKey ignores nicknames and hidden birthdays`() {
        val base = AccountPrefs(added = listOf(AddedAccount("a@gmail.com", "com.google")))
        val renamed = base.copy(
            added = listOf(AddedAccount("a@gmail.com", "com.google", "Personal")),
            hiddenBirthdayKeys = setOf("x|1|1"),
            birthdaysShown = false,
        )
        assertEquals(base.readKey, renamed.readKey)
        assertFalse(base.readKey == base.copy(hiddenCalendarIds = setOf(2L)).readKey)
    }
}
