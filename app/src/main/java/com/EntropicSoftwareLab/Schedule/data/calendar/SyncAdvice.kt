package com.EntropicSoftwareLab.Schedule.data.calendar

/** The one line the sync card leads each account with. */
enum class SyncHeadline {
    PhoneSyncOff,
    CalendarSyncOff,
    Uploading,
    /** Rows waiting, but this account's sync is not visible to the app, so nothing is said about a queue. */
    Waiting,
    WaitingQueued,
    WaitingNotQueued,
    Checking,
    UpToDate,
    Unknown,
}

/** Turns what the phone says about an account into a plain answer. Pure, so it is tested. */
object SyncAdvice {
    /** The package that owns the calendar sync adapter, and so does the uploading. */
    const val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"

    /**
     * First match wins. A queued sync with nothing to upload is [SyncHeadline.UpToDate] on purpose:
     * the app's own poll keeps one queued, and that is noise.
     */
    fun headline(s: AccountSyncStatus): SyncHeadline {
        val rows = s.pendingUploads
        return when {
            s.masterAutoSync == false -> SyncHeadline.PhoneSyncOff
            s.autoSync == false -> SyncHeadline.CalendarSyncOff
            rows == null -> SyncHeadline.Unknown
            rows > 0 && s.syncActive == true -> SyncHeadline.Uploading
            rows > 0 && s.syncActive == null && s.syncPending == null -> SyncHeadline.Waiting
            rows > 0 && s.syncPending == true -> SyncHeadline.WaitingQueued
            rows > 0 -> SyncHeadline.WaitingNotQueued
            s.syncActive == true -> SyncHeadline.Checking
            else -> SyncHeadline.UpToDate
        }
    }

    /** Xiaomi, Redmi and POCO phones share HyperOS/MIUI, whose Autostart switch blocks the adapter. */
    fun isXiaomiFamily(manufacturer: String?, brand: String?): Boolean =
        listOf(manufacturer, brand).any {
            when (it?.trim()?.lowercase()) {
                "xiaomi", "redmi", "poco" -> true
                else -> false
            }
        }
}
