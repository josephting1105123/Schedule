package com.EntropicSoftwareLab.Schedule.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Lists the models a key can actually reach.
 *
 * This exists because model ids cannot be verified offline and they change: the app once pinned
 * `gemini-2.5-flash`, which now returns 404 for new keys. Rather than guessing and leaving the user
 * to decode a 404, Settings can show the real list from their own key and let them pick.
 */
object GeminiModelCatalog {

    private const val READ_TIMEOUT_MS = 20_000

    suspend fun list(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No Gemini API key set."))
        }
        runCatching { GeminiHttp.get("${GeminiHttp.ENDPOINT}?pageSize=200", apiKey, READ_TIMEOUT_MS) }
            .mapCatching { parseModels(it) }
            .recoverCatching { throw IllegalStateException(GeminiHttp.describeFailure(it), it) }
    }

    /**
     * Keeps only models that can answer a `generateContent` call — the list also contains embedding
     * and tuning-only models, which would fail confusingly if one were pinned in Settings.
     *
     * Internal rather than private so it is unit-testable without a live key.
     */
    internal fun parseModels(json: String): List<String> = runCatching {
        val models = JSONObject(json).optJSONArray("models") ?: return emptyList()
        (0 until models.length()).mapNotNull { i ->
            val o = models.optJSONObject(i) ?: return@mapNotNull null
            val methods = o.optJSONArray("supportedGenerationMethods")
            val supported = methods != null &&
                (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (!supported) return@mapNotNull null
            o.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }
        }.sorted()
    }.getOrDefault(emptyList())
}
