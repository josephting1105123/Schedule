package com.EntropicSoftwareLab.Schedule.data.appearance

import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.GoogleCalendarColors
import java.time.LocalDate

/**
 * Gives an event with no colour of its own one of the brand's, so a light day looks like this app.
 *
 * Most events never get a colour picked for them. Left alone they all render in whichever colour
 * their calendar happens to be, which on a single-account phone means a whole week in one shade —
 * indistinguishable on the hour grid and, in the month widget, a column of identical bands.
 *
 * So: an event that nobody has coloured is *displayed* in a brand colour, handed out so that the
 * events sharing a day get different ones. Under six events a day, every one is distinct.
 *
 * **This writes nothing.** It is a display decision, computed fresh on every read, and it never
 * reaches `CalendarContract` or [EventColorRepository]. Three things follow from that, all of them
 * the point rather than a limitation: no other calendar app sees a colour this app invented, picking
 * a real colour later simply overrides it, and there is nothing to migrate or clean up if the rule
 * changes.
 *
 * ## What counts as "no colour of its own"
 *
 * An app colour ([EventColorRepository]) always wins — the user chose it here. Failing that, an
 * event whose `eventColorKey` is set carries a colour somebody deliberately gave it, in this app or
 * in Google Calendar, and is left alone. Only what is left — an event wearing its *calendar's*
 * colour by default — is assigned. `displayColor` alone cannot make this distinction, which is why
 * the key is what gets tested.
 */
object BrandColorAssignment {

    /**
     * Colours for one day's events: the app's own where set, plus a brand colour for each event that
     * has none.
     *
     * The result is intended to be merged over [overrides] and read by [EventColorResolver], so an
     * event with an explicit provider colour is deliberately **absent** — falling through to
     * `displayColor` is what keeps that colour showing.
     *
     * Assignment walks [palette] in order for the day's uncoloured events, sorted by start time so
     * the same day always produces the same answer. Colours already spoken for that day are skipped
     * first, so an event explicitly set to Jade doesn't get a second Jade beside it.
     *
     * Adding an event earlier in the day does shift the colours of the ones after it. That is the
     * cost of guaranteeing distinctness within a day, and it is the right way round: two events the
     * same colour is a bug the user sees every time they look, where a colour changing is something
     * they notice once.
     */
    fun forDay(
        events: List<EventInstance>,
        overrides: Map<Long, Int>,
        palette: List<Int>,
    ): Map<Long, Int> = forDates(mapOf(EPOCH to events), overrides, palette)

    /**
     * The same, across several days at once — the month grid and its agenda.
     *
     * Iterates dates in order and assigns per day, but **never reassigns an event it has already
     * given a colour**. That is what keeps a multi-day event one colour along its whole band;
     * assigning per day independently would paint a week-long trip a different colour each day.
     */
    fun forDates(
        eventsByDate: Map<LocalDate, List<EventInstance>>,
        overrides: Map<Long, Int>,
        palette: List<Int>,
    ): Map<Long, Int> {
        if (palette.isEmpty()) return overrides

        val assigned = HashMap<Long, Int>(overrides)
        for (date in eventsByDate.keys.sorted()) {
            val onDay = eventsByDate[date].orEmpty()

            // Every colour already visible on this day: explicit ones, plus anything an earlier date
            // assigned to an event that runs into this one.
            val taken = HashSet<Int>()
            val needing = ArrayList<EventInstance>()
            for (event in onDay) {
                val existing = assigned[event.eventId]
                when {
                    existing != null -> taken.add(existing)
                    // In the shade it is drawn in, so an older Google shade of Tomato still counts as
                    // Tomato taken and the day doesn't get a second one beside it.
                    event.hasOwnColor() -> taken.add(GoogleCalendarColors.currentShade(event.displayColor))
                    else -> needing.add(event)
                }
            }
            if (needing.isEmpty()) continue

            // Stable order, so the same day resolves the same way on every recomposition. eventId
            // breaks ties, because two events starting at the same minute must not swap places
            // depending on the order the provider happened to return them.
            needing.sortWith(compareBy({ it.startMillis }, { it.eventId }))

            var slot = 0
            for (event in needing) {
                // Prefer a colour nothing on this day is wearing. Past a full lap every colour is
                // taken, so wrapping and reusing beats leaving events uncoloured.
                var chosen: Int? = null
                for (step in palette.indices) {
                    val candidate = palette[(slot + step) % palette.size]
                    if (candidate !in taken) {
                        chosen = candidate
                        slot = (slot + step + 1) % palette.size
                        break
                    }
                }
                val colour = chosen ?: palette[slot % palette.size].also { slot++ }
                assigned[event.eventId] = colour
                taken.add(colour)
            }
        }
        return assigned
    }

    /**
     * True when somebody deliberately coloured this event, rather than it inheriting its calendar's.
     *
     * `eventColorKey` is the provider's record of a *chosen* colour; `eventColor` also covers one
     * chosen in Google Calendar that has no key this app knows (Cherry Blossom, Pumpkin and the rest
     * outside Google's classic 11). `displayColor` resolves to the calendar's when no choice was
     * made, and so cannot tell the two apart.
     */
    private fun EventInstance.hasOwnColor(): Boolean = eventColorKey != null || eventColor != null

    /** Any date; [forDay] has only one, and its identity never escapes. */
    private val EPOCH: LocalDate = LocalDate.EPOCH
}
