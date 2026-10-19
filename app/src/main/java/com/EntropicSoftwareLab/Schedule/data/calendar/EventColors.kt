package com.EntropicSoftwareLab.Schedule.data.calendar

import java.util.Locale

/**
 * One colour the user's account actually offers for an event.
 *
 * [key] is the account's own `COLOR_KEY`, and it is the part that matters. Writing a raw ARGB to
 * `EVENT_COLOR` sets the colour locally, but Google's sync adapter only round-trips a colour it
 * recognises — send it a hex it has never heard of and the event comes back on the calendar's
 * default. Writing the key instead means the colour survives the trip to the server and back, and
 * shows up the same in Google Calendar on every other device.
 */
data class EventColorOption(
    val key: String,
    val argb: Int,
    /** The name Google Calendar shows, when this colour is one whose name is known. */
    val name: String?,
    /** False for a canonical slot the account's live palette didn't offer — see [GoogleCalendarColors.canonicalize]. */
    val available: Boolean = true,
) {
    /** What the picker announces. Falls back to the hex, which is at least true. */
    val label: String
        get() = name ?: "#%06X".format(Locale.US, argb and 0xFFFFFF)
}

/**
 * Puts Google Calendar's names on the colours the provider hands back.
 */
object GoogleCalendarColors {

    private val ALL_KNOWN_RGB: Map<Int, String> = mapOf(
        0xD81B60 to "Beetroot",
        0xF48FB1 to "Cherry Blossom",
        0xE67C73 to "Flamingo",
        0xD50000 to "Tomato",
        0xF4511E to "Tangerine",
        0xE65100 to "Pumpkin",
        0xFFB300 to "Mango",
        0xF6BF26 to "Banana",
        0xE4C441 to "Citron",
        0xC0CA33 to "Avocado",
        0x7CB342 to "Pistachio",
        0x0B8043 to "Basil",
        0x33B679 to "Sage",
        0x009688 to "Eucalyptus",
        0x039BE5 to "Peacock",
        0x4285F4 to "Cobalt",
        0x7986CB to "Lavender",
        0x3F51B5 to "Blueberry",
        // Grape is #8E24AA — Google's classic 11 publish it so, and it is what legacy Grape
        // (#DBADFF) must map to. This table once had Grape as #6A1B9A and gave #8E24AA to
        // Wisteria, which drew Grape events far darker than Google Calendar (2026-09-29).
        0xB39DDB to "Wisteria",
        0x9C27B0 to "Amethyst",
        0x8E24AA to "Grape",
        0x795548 to "Cocoa",
        0x616161 to "Graphite",
        0xA1887F to "Birch",
    )

    val PALETTE_ORDER: List<String> = listOf(
        "Beetroot", "Cherry Blossom", "Flamingo", "Tomato",
        "Tangerine", "Pumpkin", "Mango", "Banana",
        "Citron", "Avocado", "Pistachio", "Basil",
        "Sage", "Eucalyptus", "Peacock", "Cobalt",
        "Lavender", "Blueberry", "Wisteria", "Amethyst",
        "Grape", "Cocoa", "Graphite", "Birch",
    )

    /**
     * Four of Google's own colours, pinned to the front of the account list ahead of Google's order.
     *
     * These matched the app's brand set once. They no longer do — the app's palette is six greens
     * now and shares nothing with these — so the ordering is inherited rather than derived. It still
     * only decides how an account's own colours are listed, which is why it was left alone.
     */
    val BRAND_FIRST: List<String> = listOf("Sage", "Eucalyptus", "Peacock", "Lavender")

    private val NAME_TO_RGB: Map<String, Int> =
        ALL_KNOWN_RGB.entries.associate { (rgb, name) -> name to rgb }

    /** Squared-RGB distance (~30 per channel) beyond which a colour is unnamed rather than guessed. */
    private const val MAX_NAME_DISTANCE_SQUARED = 2700

    /**
     * Google Calendar's fixed event colour ids — the public API's `colorId`, which Google's sync
     * adapter stores as `COLOR_KEY` — to their names. These 11 are the only event colours a
     * non-Google app can write; the other 13 of the 24 are settable only in Google's own apps.
     */
    private val GOOGLE_KEY_NAMES: Map<String, String> = mapOf(
        "1" to "Lavender",
        "2" to "Sage",
        "3" to "Grape",
        "4" to "Flamingo",
        "5" to "Banana",
        "6" to "Tangerine",
        "7" to "Peacock",
        "8" to "Graphite",
        "9" to "Blueberry",
        "10" to "Basil",
        "11" to "Tomato",
    )

    private const val GOOGLE_ACCOUNT_TYPE = "com.google"

    /**
     * The older shades Google's public API — and so, on many phones, its sync adapter — still uses
     * for the classic 11, by name. Google Calendar itself now draws those 11 in the 24-colour set's
     * shades ([ALL_KNOWN_RGB]), so an event read with one of these looked visibly different here from
     * the same event there — Tomato `#DC2127` against `#D50000` (reported 2026-09-29).
     */
    private val LEGACY_RGB_NAMES: Map<Int, String> = mapOf(
        0xA4BDFC to "Lavender",
        0x7AE7BF to "Sage",
        0xDBADFF to "Grape",
        0xFF887C to "Flamingo",
        0xFBD75B to "Banana",
        0xFFB878 to "Tangerine",
        0x46D6DB to "Peacock",
        0xE1E1E1 to "Graphite",
        0x5484ED to "Blueberry",
        0x51B749 to "Basil",
        0xDC2127 to "Tomato",
    )

    /**
     * The shade Google Calendar draws [argb] in: the current one for a legacy shade of the classic
     * 11, otherwise [argb] unchanged. Display only — the provider's value is what gets compared and
     * written back, so nothing here changes what is stored.
     */
    fun currentShade(argb: Int): Int {
        val rgb = argb and 0xFFFFFF
        // Exact first; then the nearest legacy shade within a small tolerance, in case a phone's
        // adapter reports one a few steps off. Tight enough that no current colour of the 24 is
        // within it of any legacy one, so a current shade is never remapped.
        val name = LEGACY_RGB_NAMES[rgb] ?: LEGACY_RGB_NAMES.entries
            .map { (legacy, name) -> name to squaredDistance(legacy, rgb) }
            .filter { (_, distance) -> distance <= LEGACY_TOLERANCE_SQUARED }
            .minByOrNull { (_, distance) -> distance }
            ?.first
            ?: return argb
        return argbForName(name) ?: argb
    }

    /**
     * How Google Calendar's Android app actually draws each classic colour in light mode — a
     * softer, lighter tone than the published hex (Tomato `#D50000` is drawn about `#D26042`).
     * Measured from the user's side-by-side screenshot, 2026-09-29; Basil and Blueberry were not on
     * screen and are estimated from the others' shift (lighter, less saturated). Only the look
     * changes: stored and written colours stay the published ones.
     */
    private val DRAWN_RGB: Map<Int, Int> = mapOf(
        0xD50000 to 0xD26042, // Tomato
        0xE67C73 to 0xDE9690, // Flamingo
        0xF4511E to 0xEA7E5A, // Tangerine
        0xF6BF26 to 0xF0CC72, // Banana
        0x33B679 to 0x7EC096, // Sage
        0x0B8043 to 0x3F9A64, // Basil (estimated)
        0x039BE5 to 0x78A8E4, // Peacock
        0x3F51B5 to 0x727DB9, // Blueberry (estimated)
        0x7986CB to 0x969CCC, // Lavender
        0x8E24AA to 0x965AAE, // Grape
        0x616161 to 0x7E7E7E, // Graphite
    )

    /**
     * The colour to draw [argb] in so it matches Google Calendar's app: an older shade is first
     * brought to the current one ([currentShade]), then a classic colour is given the tone
     * Google's app draws it in. Anything else is returned unchanged.
     */
    fun displayShade(argb: Int): Int {
        val current = currentShade(argb)
        val drawn = DRAWN_RGB[current and 0xFFFFFF] ?: return current
        return (current and 0xFF000000.toInt()) or drawn
    }

    /**
     * About 10 steps a channel. Must stay below 374, the distance from Cobalt (`#4285F4`, current)
     * to legacy Blueberry (`#5484ED`) — the closest current/legacy pair — or Cobalt would be redrawn.
     */
    private const val LEGACY_TOLERANCE_SQUARED = 300

    private fun squaredDistance(a: Int, b: Int): Int {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /** Google's name for one of its own colour keys, or null for any other account or key. */
    fun nameForKey(key: String, accountType: String): String? =
        if (accountType == GOOGLE_ACCOUNT_TYPE) GOOGLE_KEY_NAMES[key] else null

    /** The documented, opaque ARGB for one of the 24 names, or null when the name isn't one of them. */
    fun argbForName(name: String): Int? =
        NAME_TO_RGB.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.let { 0xFF000000.toInt() or it.value }

    /** Google's name for [argb], or null when it's too far from any of the 24 to guess honestly. */
    fun nameFor(argb: Int): String? {
        val exact = ALL_KNOWN_RGB[argb and 0xFFFFFF]
        if (exact != null) return exact

        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF

        val (nearestName, distance) = ALL_KNOWN_RGB.entries.map { (knownArgb, name) ->
            val kr = knownArgb shr 16 and 0xFF
            val kg = knownArgb shr 8 and 0xFF
            val kb = knownArgb and 0xFF
            name to ((r - kr) * (r - kr) + (g - kg) * (g - kg) + (b - kb) * (b - kb))
        }.minByOrNull { (_, dist) -> dist } ?: return null
        return if (distance <= MAX_NAME_DISTANCE_SQUARED) nearestName else null
    }

    /**
     * The closest colour this account actually offers to [argb], or null when it offers none.
     *
     * This is what lets the app keep its own palette without the provider throwing the colour away.
     * The user picks any colour they like, the app stores it locally
     * ([com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository]), and the nearest of
     * the account's own colours goes to `EVENT_COLOR_KEY` so the event still reads as roughly that
     * colour in Google Calendar instead of reverting to the calendar's default.
     *
     * Unlike [nameFor] there is deliberately **no distance cutoff**. Refusing to name a colour is
     * the honest answer when the alternative is a caption that lies; refusing to approximate one
     * here would just mean falling back to the calendar's default, which is further from the user's
     * choice than any of the 24 could be.
     *
     * Sentinel slots are filtered out first — a `canonical:<Name>` key names a colour the account
     * did *not* offer, and writing one to the provider sets nothing.
     */
    fun nearestAvailable(argb: Int, options: List<EventColorOption>): EventColorOption? {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return options
            .filter { it.available }
            .minByOrNull { option ->
                val or = option.argb shr 16 and 0xFF
                val og = option.argb shr 8 and 0xFF
                val ob = option.argb and 0xFF
                (r - or) * (r - or) + (g - og) * (g - og) + (b - ob) * (b - ob)
            }
    }

    /**
     * The 24 Google colours, always leading the result — never a live-only subset that shrinks when
     * a query fails partway through or an account only defines a handful. [BRAND_FIRST] leads, in
     * that order, then the rest of [PALETTE_ORDER]. A slot [live] didn't offer gets the documented
     * hex and a sentinel key with `available = false`, so the picker can grey it out instead of the
     * palette simply varying from one launch to the next.
     *
     * A live row the 24 slots don't account for — a second row naming a colour already claimed, or
     * one too far from all 24 to name at all — is appended after them rather than dropped: the
     * account genuinely offers it, so it must stay reachable even with no name to show for it.
     */
    fun canonicalize(live: List<EventColorOption>): List<EventColorOption> {
        // Keyed by lowercase name so a match is case-insensitive. Where two live rows name the same
        // canonical colour, the first one encountered wins the slot — deliberate, so the mapping is
        // stable across calls rather than depending on cursor order.
        val byName = mutableMapOf<String, EventColorOption>()
        for (option in live) {
            val name = option.name?.lowercase(Locale.US) ?: continue
            byName.putIfAbsent(name, option)
        }

        val slotOrder = BRAND_FIRST + PALETTE_ORDER.filterNot { it in BRAND_FIRST }
        val slots = slotOrder.map { name ->
            val match = byName[name.lowercase(Locale.US)]
            if (match != null) {
                // The live row's own colour, not the guessed hex — the picker shows the account's
                // real swatch, not this app's stand-in for a name it can't otherwise verify.
                EventColorOption(key = match.key, argb = match.argb, name = name, available = true)
            } else {
                val argb = 0xFF000000.toInt() or NAME_TO_RGB.getValue(name)
                EventColorOption(key = "canonical:$name", argb = argb, name = name, available = false)
            }
        }

        // Every live row that won a slot; anything else is a real colour the account offers that
        // the 24 names can't hold, so it comes back with no name rather than vanishing.
        val consumed = byName.values.toSet()
        val extras = live.filterNot { it in consumed }.map { it.copy(name = null, available = true) }
        return slots + extras
    }
}
