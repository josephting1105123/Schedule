package com.EntropicSoftwareLab.Schedule.ai

/**
 * The model ids, in one place.
 *
 * These used to live in [GeminiAssistant]'s private companion, which meant `SettingsRepository`
 * could not read the default to show it as a placeholder. Anything that needs to name a model names
 * it from here.
 */
object GeminiModels {

    /**
     * The default for every Gemini call in the app.
     *
     * **This id is not verified from inside this repo.** Resolving the exact string requires a live
     * key, and there is no offline way to check which models a given key can reach. If it is wrong
     * the failure is loud and recoverable rather than silent — the API returns 404,
     * [GeminiHttp.describeHttpFailure] says so in as many words, the caller falls back to its
     * bundled text, and Settings has a field to correct it plus a button that lists exactly what the
     * key can actually use. See [GeminiModelCatalog].
     *
     * Flash rather than Pro because the user reported this one working against their own key, and
     * extraction is a small structured job: the response schema does the shape-holding, and Pro's
     * extra reasoning bought latency rather than accuracy. Thinking stays on regardless — see
     * [GeminiAssistant] for the multi-event failure that rule came from.
     */
    const val DEFAULT = "gemini-3.6-flash"

    /**
     * `minimal`, `low`, `medium` or `high`. Gemini 3 replaced 2.5's numeric `thinkingBudget` with
     * this, and sending both to a 3.x model is a hard error — so a request body must carry exactly
     * one of them, never the pair.
     */
    const val DEFAULT_THINKING = "high"
}
