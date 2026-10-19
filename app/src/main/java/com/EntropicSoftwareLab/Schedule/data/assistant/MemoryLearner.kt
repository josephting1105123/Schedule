package com.EntropicSoftwareLab.Schedule.data.assistant

/**
 * The rules that turn one confirmation card into a change in [AssistantMemory].
 *
 * Pure and total: same inputs, same output, no clock, no I/O, no throwing. That is what lets the
 * whole learning behaviour be pinned by JVM tests instead of discovered on a device weeks later,
 * which matters more here than usual — a learner that drifts wrong is invisible, because its only
 * symptom is drafts that are quietly a bit worse.
 *
 * Three properties are deliberate:
 *
 *  - **Nothing is learned from a draft alone.** Only a tap teaches. The model's own output is
 *    evidence of nothing, and feeding it back would let one bad guess harden into a stored habit
 *    that produces the same bad guess forever.
 *  - **A habit must earn the prompt twice and can be evicted.** One confirmation is a coincidence;
 *    [MIN_OBSERVATIONS] agreements make it a pattern. Enough contradictions delete it outright.
 *  - **Contradiction only counts when memory was actually applied.** Discarding a card the memory
 *    had no hand in says nothing about the memory, so it doesn't score against it.
 */
object MemoryLearner {

    /** Agreements needed before a habit is allowed into a prompt. One tap is a coincidence. */
    const val MIN_OBSERVATIONS = 2

    /**
     * Below this share of agreements a habit stops being spoken, even if often observed.
     *
     * Above a half rather than at it, so that a single confirm-then-undo — which scores one
     * agreement and one contradiction — lands at 0.5 and stays silent. Two agreements against one
     * contradiction reaches 0.67 and speaks.
     */
    const val MIN_CONFIDENCE = 0.6f

    /** At or below this, with enough contradictions, the habit is deleted rather than muted. */
    const val FORGET_CONFIDENCE = 0.34f

    const val FORGET_AFTER_CONTRADICTIONS = 3

    /** Two times this close count as the same habit rather than a change of mind. */
    const val TOLERANCE_MINUTES = 15

    const val MAX_HABITS = 40

    /** Few-shot pairs are the most expensive part of the block; six is plenty to set a voice. */
    const val MAX_CORRECTIONS = 6

    /** A correction should be a phrasing, not an essay pasted in once and carried forever. */
    const val MAX_CORRECTION_TEXT = 120

    const val DURATION_WINDOW = 15
    const val MIN_DURATION_SAMPLES = 4
    const val MIN_TITLE_SAMPLES = 5

    /** Durations outside this are real but not representative, and would poison the median. */
    private val SANE_DURATIONS = 5..(12 * 60)

    fun observe(memory: AssistantMemory, observation: Observation, epochDay: Long): AssistantMemory =
        when (observation) {
            is Observation.Confirmed -> confirm(memory, observation, epochDay)
            is Observation.Discarded -> discard(memory, observation)
        }

    private fun confirm(
        memory: AssistantMemory,
        observation: Observation.Confirmed,
        epochDay: Long,
    ): AssistantMemory {
        val confirmed = observation.confirmed
        val key = titleKey(confirmed.title)
        val habits = if (key.isEmpty()) memory.habits else {
            val existing = memory.habits.firstOrNull { it.key == key }
            val updated = if (existing == null) {
                Habit(
                    key = key,
                    title = confirmed.title.trim(),
                    startMinute = confirmed.startMinute,
                    durationMinutes = confirmed.durationMinutes,
                    location = confirmed.location?.trim()?.takeIf(String::isNotEmpty),
                    allDay = confirmed.allDay,
                    supporting = 1,
                    lastSeenEpochDay = epochDay,
                )
            } else {
                val agreed = agrees(existing, confirmed)
                merge(existing, confirmed, agreed, epochDay)
            }
            val without = memory.habits.filterNot { it.key == key }
            if (forgotten(updated)) without else capacity(without + updated)
        }

        return memory.copy(
            habits = habits,
            corrections = learnCorrection(memory.corrections, observation, epochDay),
            style = learnStyle(memory.style, confirmed),
        )
    }

    /**
     * A discarded card only scores against a habit when that habit is what put the values there.
     * Otherwise every "actually, never mind" would erode memory that had nothing to do with it.
     */
    private fun discard(memory: AssistantMemory, observation: Observation.Discarded): AssistantMemory {
        val key = titleKey(observation.proposed.title)
        val existing = memory.habits.firstOrNull { it.key == key } ?: return memory
        if (!agrees(existing, observation.proposed)) return memory

        val updated = existing.copy(contradicting = existing.contradicting + 1)
        val without = memory.habits.filterNot { it.key == key }
        return memory.copy(habits = if (forgotten(updated)) without else without + updated)
    }

    /** True when nothing the habit holds is contradicted by [event]. Absent fields can't disagree. */
    private fun agrees(habit: Habit, event: ObservedEvent): Boolean {
        if (habit.allDay != event.allDay) return false
        if (habit.startMinute != null && event.startMinute != null &&
            !within(habit.startMinute, event.startMinute)
        ) return false
        if (habit.durationMinutes != null && event.durationMinutes != null &&
            !within(habit.durationMinutes, event.durationMinutes)
        ) return false
        val habitPlace = habit.location?.trim()
        val eventPlace = event.location?.trim()
        if (!habitPlace.isNullOrEmpty() && !eventPlace.isNullOrEmpty() &&
            !habitPlace.equals(eventPlace, ignoreCase = true)
        ) return false
        return true
    }

    private fun within(a: Int, b: Int) = kotlin.math.abs(a - b) <= TOLERANCE_MINUTES

    /**
     * On agreement the stored value stays put, so a habit means "usually" rather than "last time".
     * On contradiction it adopts the new value, so a pattern that genuinely changed is followed
     * rather than argued with — while the contradiction still counts, so a habit that keeps changing
     * loses confidence and falls silent instead of asserting whichever value happened to be last.
     */
    private fun merge(
        existing: Habit,
        confirmed: ObservedEvent,
        agreed: Boolean,
        epochDay: Long,
    ): Habit {
        fun <T> pick(old: T?, new: T?): T? = when {
            old == null -> new
            new == null -> old
            agreed -> old
            else -> new
        }
        return existing.copy(
            title = if (agreed) existing.title else confirmed.title.trim().ifEmpty { existing.title },
            startMinute = pick(existing.startMinute, confirmed.startMinute),
            durationMinutes = pick(existing.durationMinutes, confirmed.durationMinutes),
            location = pick(
                existing.location?.takeIf(String::isNotBlank),
                confirmed.location?.trim()?.takeIf(String::isNotEmpty),
            ),
            allDay = if (agreed) existing.allDay else confirmed.allDay,
            supporting = existing.supporting + if (agreed) 1 else 0,
            contradicting = existing.contradicting + if (agreed) 0 else 1,
            lastSeenEpochDay = epochDay,
        )
    }

    private fun forgotten(habit: Habit): Boolean =
        habit.contradicting >= FORGET_AFTER_CONTRADICTIONS && habit.confidence <= FORGET_CONFIDENCE

    /** Evicts the stalest habits first, then the least evidenced, so an old one-off goes before a
     *  well-supported pattern the user simply hasn't repeated this month. */
    private fun capacity(habits: List<Habit>): List<Habit> {
        if (habits.size <= MAX_HABITS) return habits
        return habits
            .sortedWith(compareBy({ it.lastSeenEpochDay }, { it.observations }))
            .drop(habits.size - MAX_HABITS)
    }

    /**
     * Records the pair only when the user changed something, and only when their message produced
     * exactly one card.
     *
     * The single-card condition is load-bearing rather than tidy. Pairing the whole of "gym at 7,
     * standup 9:30, lunch at 1" with one confirmed event teaches, by example, that a sentence listing
     * three events yields one — which is the exact failure the multi-event prompt work was done to
     * fix. A fragment is worse than no example.
     */
    private fun learnCorrection(
        existing: List<Correction>,
        observation: Observation.Confirmed,
        epochDay: Long,
    ): List<Correction> {
        if (!observation.soleEventInMessage) return existing
        val proposed = observation.proposed ?: return existing
        if (!differs(proposed, observation.confirmed)) return existing
        val text = observation.userText.trim()
        if (text.isEmpty() || text.length > MAX_CORRECTION_TEXT) return existing

        val fresh = Correction(userText = text, event = observation.confirmed, epochDay = epochDay)
        return (listOf(fresh) + existing.filterNot { it.userText.equals(text, ignoreCase = true) })
            .take(MAX_CORRECTIONS)
    }

    private fun differs(proposed: ObservedEvent, confirmed: ObservedEvent): Boolean =
        !proposed.title.trim().equals(confirmed.title.trim(), ignoreCase = true) ||
            proposed.startMinute != confirmed.startMinute ||
            proposed.durationMinutes != confirmed.durationMinutes ||
            proposed.allDay != confirmed.allDay ||
            proposed.location?.trim().orEmpty() != confirmed.location?.trim().orEmpty()

    private fun learnStyle(style: StyleProfile, confirmed: ObservedEvent): StyleProfile {
        val durations = confirmed.durationMinutes
            ?.takeIf { !confirmed.allDay && it in SANE_DURATIONS }
            ?.let { (style.recentDurations + it).takeLast(DURATION_WINDOW) }
            ?: style.recentDurations

        val initial = confirmed.title.trim().firstOrNull { it.isLetter() }
        return style.copy(
            recentDurations = durations,
            lowercaseTitles = style.lowercaseTitles + if (initial?.isLowerCase() == true) 1 else 0,
            capitalisedTitles = style.capitalisedTitles + if (initial?.isUpperCase() == true) 1 else 0,
        )
    }
}

/** One thing that happened on a draft card, in the only two flavours that carry information. */
sealed interface Observation {
    val userText: String

    /**
     * The user tapped Add. [proposed] is what the model produced before any editing, so the pair
     * says what the model got wrong; it is null when nothing was proposed to compare against.
     */
    data class Confirmed(
        override val userText: String,
        val proposed: ObservedEvent?,
        val confirmed: ObservedEvent,
        /** True when this card was the only one the message produced. See MemoryLearner. */
        val soleEventInMessage: Boolean,
    ) : Observation

    /** The user binned the card. Only informative when memory is what shaped it. */
    data class Discarded(
        override val userText: String,
        val proposed: ObservedEvent,
    ) : Observation
}
