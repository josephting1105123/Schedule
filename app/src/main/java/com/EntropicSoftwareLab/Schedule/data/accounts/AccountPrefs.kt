package com.EntropicSoftwareLab.Schedule.data.accounts

import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayEvents
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarInfo

/** An account the user chose to use in this app, with the name they gave it. */
data class AddedAccount(val name: String, val type: String, val nickname: String = "")

/**
 * Which phone accounts this app uses, and what is ticked within them.
 *
 * Nothing here changes the phone: it is a filter over what the calendar provider already holds.
 */
data class AccountPrefs(
    /** Empty = every account on the phone is used. */
    val added: List<AddedAccount> = emptyList(),
    /** Account names unticked in the filter. */
    val hiddenAccounts: Set<String> = emptySet(),
    val hiddenCalendarIds: Set<Long> = emptySet(),
    val birthdaysShown: Boolean = true,
    /** [BirthdayEvents.hideKey] values. */
    val hiddenBirthdayKeys: Set<String> = emptySet(),
) {
    val isUnfiltered: Boolean
        get() = added.isEmpty() && hiddenAccounts.isEmpty() && hiddenCalendarIds.isEmpty()

    /**
     * The part of the prefs that changes which events a read returns. A nickname or a hidden
     * birthday is not in it, so renaming an account does not reload every screen.
     */
    val readKey: ReadKey
        get() = ReadKey(added.map { it.name }, hiddenAccounts, hiddenCalendarIds)
}

/** See [AccountPrefs.readKey]. */
data class ReadKey(
    val addedNames: List<String>,
    val hiddenAccounts: Set<String>,
    val hiddenCalendarIds: Set<Long>,
)

/** Google's Flamingo, the colour the Birthday chip is drawn in. */
const val BIRTHDAY_CHIP_COLOR: Int = 0xFFE67C73.toInt()

/** One tick in the filter row: an account, one of its extra calendars, or Birthday. */
sealed interface CalendarChip {
    val label: String
    val color: Int
    val shown: Boolean

    data class Account(
        val accountName: String,
        override val label: String,
        override val color: Int,
        override val shown: Boolean,
    ) : CalendarChip

    data class Calendar(
        val calendarId: Long,
        override val label: String,
        override val color: Int,
        override val shown: Boolean,
    ) : CalendarChip

    data class Birthdays(override val shown: Boolean) : CalendarChip {
        override val label: String = "Birthday"
        override val color: Int = BIRTHDAY_CHIP_COLOR
    }
}

/** The account and tick rules, pure so they can be tested without a provider. */
object CalendarFilter {

    /** Google Contacts' birthday calendar, which has no row of its own in the filter. */
    fun isContactsBirthdays(c: CalendarInfo): Boolean =
        c.ownerAccount.endsWith(BirthdayEvents.CONTACTS_OWNER_SUFFIX, ignoreCase = true)

    /**
     * Whether the phone syncs [c], so its rows can be trusted to be current. `SYNC_EVENTS` defaults
     * to 0 in AOSP, so a LOCAL calendar (no sync adapter, nothing to switch on) reads false and is
     * still kept. A synced-account calendar with sync off keeps stale rows Google will never
     * remove, so it is treated as not there.
     */
    fun synced(c: CalendarInfo): Boolean = c.syncEvents || c.accountType == LOCAL_ACCOUNT_TYPE

    /** `CalendarContract.ACCOUNT_TYPE_LOCAL`, spelled out so this object stays free of android imports. */
    private const val LOCAL_ACCOUNT_TYPE = "LOCAL"

    /** The calendars of the added accounts (every account when none was added), synced or not. */
    fun inAddedAccounts(calendars: List<CalendarInfo>, prefs: AccountPrefs): List<CalendarInfo> {
        if (prefs.added.isEmpty()) return calendars
        val names = prefs.added.map { it.name }.toSet()
        return calendars.filter { it.accountName in names }
    }

    /** The name of the calendar tasks are mirrored to, which Jarvis reads and writes too. */
    const val TASK_CALENDAR_NAME = "Tasks"

    /**
     * Whether [c] is the calendar tasks are mirrored to for Jarvis. It holds tasks, not events, so
     * it is never read as events, offered as a write target, or shown as a chip: a task is a
     * deadline, and a deadline must not take a lane or clash with anything.
     */
    fun isTaskCalendar(c: CalendarInfo): Boolean =
        c.displayName.trim().equals(TASK_CALENDAR_NAME, ignoreCase = true)

    /**
     * [inAddedAccounts] that can show events: calendars the phone does not sync are left out, and so
     * is the Tasks calendar ([isTaskCalendar]). Every event read, write target and chip goes
     * through here, so this one filter hides it everywhere.
     */
    fun usable(calendars: List<CalendarInfo>, prefs: AccountPrefs): List<CalendarInfo> =
        inAddedAccounts(calendars, prefs).filter { synced(it) && !isTaskCalendar(it) }

    /** [usable] less what the user unticked, by account or by calendar. */
    fun shown(calendars: List<CalendarInfo>, prefs: AccountPrefs): List<CalendarInfo> =
        usable(calendars, prefs).filter {
            it.accountName !in prefs.hiddenAccounts && it.id !in prefs.hiddenCalendarIds
        }

    /** The nickname when there is one, else the account's own name. */
    fun accountLabel(accountName: String, prefs: AccountPrefs): String =
        prefs.added.firstOrNull { it.name == accountName }?.nickname?.takeIf { it.isNotBlank() }
            ?: accountName

    /** An account's main calendar: the primary one, else the one named after the account, else the first. */
    fun primaryOf(accountCalendars: List<CalendarInfo>): CalendarInfo? =
        accountCalendars.firstOrNull { it.isPrimary }
            ?: accountCalendars.firstOrNull { it.displayName == it.accountName }
            ?: accountCalendars.firstOrNull()

    /** What to call [c]: its account for the main calendar, "name · account" for the rest. */
    fun label(c: CalendarInfo, all: List<CalendarInfo>, prefs: AccountPrefs): String {
        val primary = primaryOf(all.filter { it.accountName == c.accountName })
        val account = accountLabel(c.accountName, prefs)
        return if (primary != null && primary.id == c.id) account else "${c.displayName} · $account"
    }

    /** Accounts, then each account's extra calendars, then Birthday last. */
    fun chips(calendars: List<CalendarInfo>, prefs: AccountPrefs): List<CalendarChip> {
        val usable = usable(calendars, prefs)
        val byAccount = LinkedHashMap<String, List<CalendarInfo>>()
        for (c in usable) byAccount[c.accountName] = (byAccount[c.accountName] ?: emptyList()) + c

        val out = mutableListOf<CalendarChip>()
        val primaryIds = HashSet<Long>()
        for ((name, list) in byAccount) {
            val primary = primaryOf(list)
            primary?.let { primaryIds += it.id }
            out += CalendarChip.Account(
                accountName = name,
                label = accountLabel(name, prefs),
                color = primary?.color ?: 0,
                shown = name !in prefs.hiddenAccounts,
            )
        }
        val extras = usable.filter { it.id !in primaryIds && !isContactsBirthdays(it) }
        for (c in extras) {
            val clash = extras.any { it.id != c.id && it.displayName == c.displayName }
            out += CalendarChip.Calendar(
                calendarId = c.id,
                label = if (clash) "${c.displayName} · ${accountLabel(c.accountName, prefs)}" else c.displayName,
                color = c.color,
                shown = c.id !in prefs.hiddenCalendarIds,
            )
        }
        out += CalendarChip.Birthdays(prefs.birthdaysShown)
        return out
    }
}
