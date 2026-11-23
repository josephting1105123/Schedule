package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncHealthTest {

    private val now = 1_000_000L

    private fun status(
        name: String = "a@gmail.com",
        master: Boolean? = true,
        auto: Boolean? = true,
        active: Boolean? = false,
        pending: Int? = 0,
        visible: Boolean = true,
    ) = AccountSyncStatus(
        accountName = name,
        accountType = "com.google",
        masterAutoSync = master,
        autoSync = auto,
        syncActive = active,
        syncPending = null,
        pendingUploads = pending,
        visible = visible,
    )

    private fun timing(name: String = "a@gmail.com", since: Long?) =
        CalendarAccount(name, "com.google") to UploadTimingEntry(since, null, null)

    @Test
    fun noAccounts() {
        assertEquals(SyncVerdict.NoAccounts, syncHealth(emptyList(), emptyMap(), now).verdict)
    }

    @Test
    fun phoneSyncOff() {
        val h = syncHealth(listOf(status(master = false)), emptyMap(), now)
        assertEquals(SyncVerdict.PhoneSyncOff, h.verdict)
    }

    @Test
    fun calendarSyncOffListsTheAccount() {
        val off = status("b@gmail.com", auto = false)
        val h = syncHealth(listOf(status(), off), emptyMap(), now)
        assertEquals(SyncVerdict.CalendarSyncOff, h.verdict)
        assertEquals(listOf(off), h.calendarSyncOff)
    }

    @Test
    fun slowWaiting() {
        val h = syncHealth(
            listOf(status(pending = 2)),
            mapOf(timing(since = now - 60_000)),
            now,
        )
        assertEquals(SyncVerdict.SlowWaiting, h.verdict)
        assertEquals(2, h.waiting)
        assertEquals(60_000L, h.oldestPendingMs)
    }

    @Test
    fun syncing() {
        val h = syncHealth(listOf(status(active = true)), emptyMap(), now)
        assertEquals(SyncVerdict.Syncing, h.verdict)
    }

    @Test
    fun waiting() {
        val h = syncHealth(listOf(status(pending = 1)), emptyMap(), now)
        assertEquals(SyncVerdict.Waiting, h.verdict)
    }

    @Test
    fun upToDate() {
        val h = syncHealth(listOf(status()), emptyMap(), now)
        assertEquals(SyncVerdict.UpToDate, h.verdict)
        assertEquals(0, h.waiting)
        assertNull(h.oldestPendingMs)
    }

    @Test
    fun phoneOffBeatsCalendarOffBeatsSlow() {
        val slowTimings = mapOf(timing(since = now - 120_000))
        val slow = status(pending = 5)
        val calendarOff = status("b@gmail.com", auto = false)
        val phoneOff = status("c@gmail.com", master = false)
        assertEquals(SyncVerdict.SlowWaiting, syncHealth(listOf(slow), slowTimings, now).verdict)
        assertEquals(SyncVerdict.CalendarSyncOff, syncHealth(listOf(slow, calendarOff), slowTimings, now).verdict)
        assertEquals(SyncVerdict.PhoneSyncOff, syncHealth(listOf(slow, calendarOff, phoneOff), slowTimings, now).verdict)
    }

    @Test
    fun nullPendingCountsAsZero() {
        val h = syncHealth(listOf(status(pending = null), status("b@gmail.com", pending = 2)), emptyMap(), now)
        assertEquals(2, h.waiting)
        assertEquals(SyncVerdict.Waiting, h.verdict)
        assertEquals(SyncVerdict.Unknown, syncHealth(listOf(status(pending = null)), emptyMap(), now).verdict)
    }

    @Test
    fun oldestPendingPicksTheOldestAndNeverGoesNegative() {
        val statuses = listOf(status(pending = 1), status("b@gmail.com", pending = 1))
        val h = syncHealth(
            statuses,
            mapOf(timing(since = now - 5_000), timing("b@gmail.com", since = now - 50_000)),
            now,
        )
        assertEquals(50_000L, h.oldestPendingMs)
        val future = syncHealth(statuses, mapOf(timing(since = now + 9_000)), now)
        assertEquals(0L, future.oldestPendingMs)
    }

    @Test
    fun waitingUnderTheThresholdIsNotSlow() {
        val one = listOf(status(pending = 1))
        assertEquals(SyncVerdict.Waiting, syncHealth(one, mapOf(timing(since = now - 10_000)), now).verdict)
        // Exactly at the threshold is not yet slow; one millisecond past it is.
        assertEquals(SyncVerdict.Waiting, syncHealth(one, mapOf(timing(since = now - 30_000)), now).verdict)
        assertEquals(SyncVerdict.SlowWaiting, syncHealth(one, mapOf(timing(since = now - 30_001)), now).verdict)
    }

    @Test
    fun unknownPendingCountIsNotUpToDate() {
        val h = syncHealth(listOf(status(), status("b@gmail.com", pending = null)), emptyMap(), now)
        assertEquals(SyncVerdict.Unknown, h.verdict)
        // Something known to be waiting still wins over a count the phone wouldn't give.
        val waiting = syncHealth(listOf(status(pending = 1), status("b@gmail.com", pending = null)), emptyMap(), now)
        assertEquals(SyncVerdict.Waiting, waiting.verdict)
    }

    @Test
    fun aClockOnAnAccountWithNothingWaitingIsNotSlow() {
        val h = syncHealth(listOf(status(pending = 0)), mapOf(timing(since = now - 120_000)), now)
        assertEquals(SyncVerdict.UpToDate, h.verdict)
        assertNull(h.oldestPendingMs)
        assertEquals(0, h.stuckCount)
    }

    @Test
    fun stuckCountCoversOnlyTheStuckAccounts() {
        val statuses = listOf(status(pending = 4), status("b@gmail.com", pending = 2))
        val h = syncHealth(
            statuses,
            mapOf(timing(since = now - 90_000), timing("b@gmail.com", since = now - 5_000)),
            now,
        )
        assertEquals(SyncVerdict.SlowWaiting, h.verdict)
        assertEquals(6, h.waiting)
        assertEquals(4, h.stuckCount)
        assertEquals(90_000L, h.oldestPendingMs)
    }
}

