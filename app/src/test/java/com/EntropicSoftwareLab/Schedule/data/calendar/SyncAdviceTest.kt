package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAdviceTest {

    private fun status(
        master: Boolean? = true,
        auto: Boolean? = true,
        active: Boolean? = false,
        pending: Boolean? = false,
        rows: Int? = 0,
    ) = AccountSyncStatus(
        accountName = "a@gmail.com",
        accountType = "com.google",
        masterAutoSync = master,
        autoSync = auto,
        syncActive = active,
        syncPending = pending,
        pendingUploads = rows,
    )

    @Test
    fun phoneSyncOffBeatsDirtyRows() {
        assertEquals(SyncHeadline.PhoneSyncOff, SyncAdvice.headline(status(master = false, rows = 3)))
    }

    @Test
    fun calendarSyncOffBeatsUploading() {
        assertEquals(
            SyncHeadline.CalendarSyncOff,
            SyncAdvice.headline(status(auto = false, active = true, rows = 2)),
        )
    }

    @Test
    fun nullCountIsUnknownEvenWhenActive() {
        assertEquals(SyncHeadline.Unknown, SyncAdvice.headline(status(active = true, rows = null)))
    }

    @Test
    fun rowsAndActiveIsUploading() {
        assertEquals(SyncHeadline.Uploading, SyncAdvice.headline(status(active = true, pending = true, rows = 1)))
    }

    @Test
    fun rowsAndQueuedIsWaitingQueued() {
        assertEquals(SyncHeadline.WaitingQueued, SyncAdvice.headline(status(pending = true, rows = 4)))
    }

    @Test
    fun rowsAndNothingQueuedIsWaitingNotQueued() {
        assertEquals(SyncHeadline.WaitingNotQueued, SyncAdvice.headline(status(rows = 4)))
        assertEquals(
            SyncHeadline.Waiting,
            SyncAdvice.headline(status(active = null, pending = null, rows = 1)),
        )
        assertEquals(
            SyncHeadline.WaitingNotQueued,
            SyncAdvice.headline(status(active = false, pending = null, rows = 1)),
        )
    }

    @Test
    fun activeWithNoRowsIsChecking() {
        assertEquals(SyncHeadline.Checking, SyncAdvice.headline(status(active = true, rows = 0)))
    }

    @Test
    fun pendingWithNoRowsIsUpToDate() {
        assertEquals(SyncHeadline.UpToDate, SyncAdvice.headline(status(pending = true, rows = 0)))
    }

    @Test
    fun idleWithNoRowsIsUpToDate() {
        assertEquals(SyncHeadline.UpToDate, SyncAdvice.headline(status()))
    }

    @Test
    fun unknownSwitchesDoNotBlockTheAnswer() {
        assertEquals(SyncHeadline.UpToDate, SyncAdvice.headline(status(master = null, auto = null)))
    }

    @Test
    fun xiaomiFamilyMatchesTrimmedCaseInsensitive() {
        assertTrue(SyncAdvice.isXiaomiFamily("Xiaomi", null))
        assertTrue(SyncAdvice.isXiaomiFamily("Google", "POCO"))
        assertTrue(SyncAdvice.isXiaomiFamily(" redmi ", " "))
    }

    @Test
    fun otherBrandsAndNullsAreNotXiaomi() {
        assertFalse(SyncAdvice.isXiaomiFamily("Google", "google"))
        assertFalse(SyncAdvice.isXiaomiFamily("samsung", "samsung"))
        assertFalse(SyncAdvice.isXiaomiFamily(null, null))
        assertFalse(SyncAdvice.isXiaomiFamily("", "  "))
    }
}
