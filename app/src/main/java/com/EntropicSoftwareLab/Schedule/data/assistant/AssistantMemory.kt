package com.EntropicSoftwareLab.Schedule.data.assistant

/**
 * What the assistant has worked out about how this user schedules, learned entirely from events they
 * confirmed on a draft card.
 *
 * This is in-context memory, not training. Nothing here changes a weight: the whole structure exists
 * to be rendered into the next prompt by [MemoryPrompt], which is the only mechanism available — the
 * Gemini API takes no tuning traffic from a phone. What it buys is real anyway, because the signal is
 * real: the confirmation card already
 * records what the model proposed and what the user actually accepted, and the difference between
 * those two is a labelled correction nobody had to write down.
 *
 * Everything here is a plain value. The rules live in [MemoryLearner], the wording in [MemoryPrompt]
 * and the storage in [AssistantMemoryRepository], so all three are testable without a device.
 */
data class AssistantMemory(
    val habits: List<Habit> = emptyList(),
    /** Recent (their phrasing → what they actually meant) pairs, newest first. */
    val corrections: List<Correction> = emptyList(),
    val style: StyleProfile = StyleProfile(),
) {
    val isEmpty: Boolean
        get() = habits.isEmpty() && corrections.isEmpty() && style.isEmpty

    /** Only the habits that have earned a place in the prompt. See [Habit.isReliable]. */
    val reliableHabits: List<Habit>
        get() = habits.filter { it.isReliable }.sortedByDescending { it.supporting }

    companion object {
        val EMPTY = AssistantMemory()
    }
}

/**
 * One learned pattern for one kind of event, plus the evidence that earned it.
 *
 * [supporting] and [contradicting] are what make this self-correcting rather than merely
 * accumulating. A habit is only spoken aloud in a prompt once it has been seen agreeing with itself
 * more than once, and it is deleted outright once the user has contradicted it enough times — a
 * pattern that stopped being true has to be able to leave, or the assistant gets confidently worse
 * the longer it runs.
 */
data class Habit(
    /** Normalised match token from the title — see [titleKey]. */
    val key: String,
    /** The user's own wording and casing, echoed back to them in Settings and in the prompt. */
    val title: String,
    /** Usual start, in minutes after local midnight, or null when never timed the same way twice. */
    val startMinute: Int? = null,
    val durationMinutes: Int? = null,
    val location: String? = null,
    /** Whether this kind of event is usually all-day — "Leave" and "Birthday" nearly always are. */
    val allDay: Boolean = false,
    val supporting: Int = 0,
    val contradicting: Int = 0,
    val lastSeenEpochDay: Long = 0,
) {
    val observations: Int get() = supporting + contradicting

    val confidence: Float
        get() = if (observations == 0) 0f else supporting.toFloat() / observations

    /** True when the habit holds something worth saying. A habit of nothing is not worth a token. */
    val hasContent: Boolean
        get() = startMinute != null || durationMinutes != null || !location.isNullOrBlank() || allDay

    /** Has this been seen often enough, and agreed with itself often enough, to steer a draft? */
    val isReliable: Boolean
        get() = observations >= MemoryLearner.MIN_OBSERVATIONS &&
            confidence >= MemoryLearner.MIN_CONFIDENCE &&
            hasContent
}

/** A phrase the user typed and the event they actually meant by it, after they corrected the draft. */
data class Correction(
    val userText: String,
    val event: ObservedEvent,
    val epochDay: Long,
)

/**
 * A confirmed or proposed event, reduced to the fields worth learning from.
 *
 * Deliberately not [com.EntropicSoftwareLab.Schedule.ai.EventDraft]: the date is dropped on purpose.
 * A date is the one field that is never a habit — "the 14th" tells you nothing about the next
 * booking — and keeping it out means a stored correction can never teach the model a stale date.
 */
data class ObservedEvent(
    val title: String,
    /** Minutes after local midnight, or null for an all-day event. */
    val startMinute: Int? = null,
    val durationMinutes: Int? = null,
    val location: String? = null,
    val allDay: Boolean = false,
)

enum class TitleStyle { Lowercase, Capitalised }

/** Cross-cutting habits that aren't tied to one kind of event. */
data class StyleProfile(
    /**
     * The last few confirmed durations, oldest first. The learned default is the *median* of these
     * rather than a running average, because one eight-hour flight would drag an average far enough
     * to make every subsequent guess wrong.
     */
    val recentDurations: List<Int> = emptyList(),
    val lowercaseTitles: Int = 0,
    val capitalisedTitles: Int = 0,
) {
    val isEmpty: Boolean
        get() = recentDurations.isEmpty() && lowercaseTitles == 0 && capitalisedTitles == 0

    /** Median confirmed duration, rounded to 5 minutes. Null until there is enough to be worth saying. */
    val defaultDurationMinutes: Int?
        get() {
            if (recentDurations.size < MemoryLearner.MIN_DURATION_SAMPLES) return null
            val sorted = recentDurations.sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            }
            return ((median + 2) / 5) * 5
        }

    /** Null unless one casing clearly dominates — a near-even split is not a preference. */
    val titleStyle: TitleStyle?
        get() {
            val total = lowercaseTitles + capitalisedTitles
            if (total < MemoryLearner.MIN_TITLE_SAMPLES) return null
            return when {
                lowercaseTitles >= total * 0.8 -> TitleStyle.Lowercase
                capitalisedTitles >= total * 0.8 -> TitleStyle.Capitalised
                else -> null
            }
        }
}

/**
 * Reduces a title to the token a habit is filed under.
 *
 * Trailing prepositional phrases are dropped first, so "Dinner with mum" and "Dinner with the
 * Wilsons" file under the same habit — dinner is dinner-length whoever is at it. What's left is kept
 * whole rather than reduced to its first word, because English compounds put the head last and
 * "Team standup" filed under "team" would silently absorb every other team event.
 *
 * The result is shown to the user in Settings, so predictable beats clever: no stemming, no fuzzy
 * matching, no synonyms. Two titles share a habit when they normalise to the same string.
 */
fun titleKey(title: String): String {
    val cleaned = title.lowercase()
        .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .trim()
    if (cleaned.isEmpty()) return ""

    val words = cleaned.split(Regex("\\s+"))
    val head = words.takeWhile { it !in PHRASE_BOUNDARIES }
    // A title that is nothing but a boundary word ("with") keeps its whole self rather than
    // collapsing to an empty key that every other such title would collide with.
    return (if (head.isEmpty()) words else head).joinToString(" ")
}

/** Words that start a "who/where" tail rather than naming the event. "w" is "w/" after cleaning. */
private val PHRASE_BOUNDARIES =
    setOf("with", "w", "at", "for", "about", "re", "in", "on", "to", "and")
