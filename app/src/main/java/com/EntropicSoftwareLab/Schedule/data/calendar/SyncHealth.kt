package com.EntropicSoftwareLab.Schedule.data.calendar

/** The one-line answer to "is my calendar reaching Google?", in the order the causes are checked. */
enum class SyncVerdict { NoAccounts, PhoneSyncOff, CalendarSyncOff, SlowWaiting, Syncing, Waiting, Unknown, UpToDate }

/**
 * What the Settings sync card says, worked out once so the folded line and the open checklist agree.
 *
 * [waiting] is the sum of the accounts' pending uploads (unknown counts as none). [oldestPendingMs]
 * is how long the longest-waiting upload has been outstanding, counting only accounts that have
 * something waiting, or null when no such clock is running. [stuckCount] is the changes waiting in
 * accounts whose own upload has been outstanding longer than the slow threshold.
 */
data class SyncHealth(
    val verdict: SyncVerdict,
    val waiting: Int,
    val calendarSyncOff: List<AccountSyncStatus>,
    val oldestPendingMs: Long?,
    val stuckCount: Int,
)

/** An upload outstanding longer than this counts as stuck. */
const val SLOW_UPLOAD_MS = 30_000L

/**
 * First match wins: no accounts, phone auto-sync off, a calendar's sync off, an upload waiting
 * longer than [slowMs], a sync running, changes waiting, a count the phone wouldn't give, then up
 * to date.
 */
fun syncHealth(
    statuses: List<AccountSyncStatus>,
    timings: Map<CalendarAccount, UploadTimingEntry>,
    nowMs: Long,
    slowMs: Long = SLOW_UPLOAD_MS,
): SyncHealth {
    val waiting = statuses.sumOf { it.pendingUploads ?: 0 }
    val calendarOff = statuses.filter { it.autoSync == false }
    // A clock left running on an account with nothing waiting says nothing about a stuck upload.
    val elapsedByAccount = statuses
        .filter { (it.pendingUploads ?: 0) > 0 }
        .mapNotNull { status ->
            timings[CalendarAccount(status.accountName, status.accountType)]?.pendingSinceMs
                ?.let { since -> status to (nowMs - since).coerceAtLeast(0L) }
        }
    val oldestPending = elapsedByAccount.maxOfOrNull { it.second }
    val stuck = elapsedByAccount
        .filter { it.second > slowMs }
        .sumOf { it.first.pendingUploads ?: 0 }
    val verdict = when {
        statuses.isEmpty() -> SyncVerdict.NoAccounts
        statuses.any { it.masterAutoSync == false } -> SyncVerdict.PhoneSyncOff
        calendarOff.isNotEmpty() -> SyncVerdict.CalendarSyncOff
        stuck > 0 -> SyncVerdict.SlowWaiting
        statuses.any { it.syncActive == true } -> SyncVerdict.Syncing
        waiting > 0 -> SyncVerdict.Waiting
        statuses.any { it.pendingUploads == null } -> SyncVerdict.Unknown
        else -> SyncVerdict.UpToDate
    }
    return SyncHealth(verdict, waiting, calendarOff, oldestPending, stuck)
}
