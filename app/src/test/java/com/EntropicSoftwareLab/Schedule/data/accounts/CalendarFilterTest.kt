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
    private val aTasks = cal(2, "Tasks", "a@gmail.com")
    private val aBirthdays = cal(3, "Birthdays", "a@gmail.com", owner = "a@gmail.com#contacts@group.v.calendar.google.com")
    private val bMain = cal(4, "b@uni.edu", "b@uni.edu", primary = true)
    private val bTasks = cal(5, "Tasks", "b@uni.edu")
    private val all = listOf(aMain, aTasks, aBirthdays, bMain, bTasks)

    private fun added(vararg names: String) = AccountPrefs(added = names.map { AddedAccount(it, "com.google") })

    @Test
    fun `usable returns everything when no account was added`() {
        assertEquals(all, CalendarFilter.usable(all, AccountPrefs()))
    }

    @Test
    fun `usable returns only the added account's calendars`() {
        assertEquals(listOf(aMain, aTasks, aBirthdays), CalendarFilter.usable(all, added("a@gmail.com")))
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
        assertFalse(CalendarFilter.isContactsBirthdays(aTasks))
        assertTrue(
            CalendarFilter.isContactsBirthdays(aTasks.copy(ownerAccount = "X#CONTACTS@GROUP.V.CALENDAR.GOOGLE.COM")),
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
        assertEquals(listOf("Tasks · a@gmail.com", "Tasks · b@uni.edu"), chips.map { it.label })
    }

    @Test
    fun `a unique calendar name stays plain`() {
        val chips = CalendarFilter.chips(listOf(aMain, aTasks), AccountPrefs()).filterIsInstance<CalendarChip.Calendar>()
        assertEquals(listOf("Tasks"), chips.map { it.label })
    }

    @Test
    fun `nickname is used in labels`() {
        val prefs = AccountPrefs(added = listOf(AddedAccount("a@gmail.com", "com.google", "Personal")))
        val chips = CalendarFilter.chips(all, prefs)
        assertEquals("Personal", chips.filterIsInstance<CalendarChip.Account>().single().label)
        assertEquals("Personal", CalendarFilter.label(aMain, all, prefs))
        assertEquals("Tasks · Personal", CalendarFilter.label(aTasks, all, prefs))
    }

    @Test
    fun `label is the account for the primary calendar and name dot account for the rest`() {
        assertEquals("a@gmail.com", CalendarFilter.label(aMain, all, AccountPrefs()))
        assertEquals("Tasks · b@uni.edu", CalendarFilter.label(bTasks, all, AccountPrefs()))
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
        assertEquals(aMain, CalendarFilter.primaryOf(listOf(aTasks, aMain)))
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
        val off = aTasks.copy(syncEvents = false)
        val calendars = listOf(aMain, off)
        assertFalse(CalendarFilter.synced(off))
        assertEquals(listOf(aMain), CalendarFilter.usable(calendars, AccountPrefs()))
        assertEquals(listOf(aMain), CalendarFilter.shown(calendars, AccountPrefs()))
        assertTrue(CalendarFilter.chips(calendars, AccountPrefs()).filterIsInstance<CalendarChip.Calendar>().isEmpty())
        // The account itself is still addable, so the sync card can show it.
        assertEquals(calendars, CalendarFilter.inAddedAccounts(calendars, AccountPrefs()))
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
