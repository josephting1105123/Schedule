package com.EntropicSoftwareLab.Schedule.data.calendar

import android.provider.CalendarContract

/**
 * Which accounts a named sync request should be sent to. Pure so the rule can be tested without a
 * `ContentResolver`: the caller supplies what Android says about each account.
 */
object SyncTargets {

    /**
     * The distinct [candidates] that are worth asking, in input order: not the on-device LOCAL
     * account (nothing to sync), visible to this app (Android drops a named request for any other
     * account), and not [isBusy] (one is already queued or running, so another request only
     * lengthens the queue).
     */
    fun toAsk(
        candidates: List<CalendarAccount>,
        isVisible: (CalendarAccount) -> Boolean,
        isBusy: (CalendarAccount) -> Boolean,
    ): List<CalendarAccount> = candidates
        .distinct()
        .filter { it.type != CalendarContract.ACCOUNT_TYPE_LOCAL }
        .filter { isVisible(it) }
        .filter { !isBusy(it) }
}
