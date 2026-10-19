package com.EntropicSoftwareLab.Schedule.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Cloud extraction with the Gemini API — the only [ScheduleAssistant] backend the app has.
 *
 * A hosted Flash model with an enforced response schema follows the schema reliably, so the reply
 * comes back as schema-valid JSON handled by [LlmJsonParser].
 *
 * The cost is the obvious one: the user's text goes to Google. That's gated behind the user pasting
 * their own key — with no key, [RoutingAssistant] refuses rather than falling back to anything else.
 *
 * Availability answers stay arithmetic: this only resolves a phrase into a time range. `FreeBusy`
 * still decides free-or-busy from real rows.
 */
class GeminiAssistant(
    private val apiKey: String,
    private val zoneProvider: () -> ZoneId,
    private val model: String = DEFAULT_MODEL,
    private val thinkingLevel: String = DEFAULT_THINKING_LEVEL,
    /**
     * The learned-habit block, read per request. It sits between the static instructions and the
     * volatile date so the expensive prefix stays stable across requests — see [PromptBuilder].
     */
    private val memoryProvider: suspend () -> String = { "" },
    /**
     * The recent conversation, from [ConversationPrompt]. Sits after the date because it changes on
     * every message — putting it any higher would end the stable prefix at the second turn.
     */
    private val historyProvider: suspend () -> String = { "" },
) : ScheduleAssistant {

    private companion object {
        const val TAG = "GeminiAssistant"

        /**
         * The id now comes from [GeminiModels.DEFAULT] and is overridable in Settings, because the
         * user's own subscription tier decides what is worth pointing at — see that constant for
         * why it cannot be verified offline.
         *
         * History worth keeping: this was once `gemini-3.1-flash-lite` with thinking switched off,
         * chosen when the model's only job was one phrase to one event. That reasoning didn't
         * survive contact with multi-event input — pulling four events out of "gym at 7, standup
         * 9:30, lunch at 1, then dentist 4:15" means segmenting the sentence, carrying one date
         * across all of it, and resolving four ambiguous clock times, which is exactly the
         * multi-step work a no-thinking lite model is worst at. It showed up as merged or missing
         * events. Whatever model is pinned here, thinking stays on for that reason.
         */
        const val DEFAULT_MODEL = GeminiModels.DEFAULT

        const val DEFAULT_THINKING_LEVEL = GeminiModels.DEFAULT_THINKING

        // High thinking on a multi-event prompt is seconds of work, not the ~1s a no-thinking lite
        // model took. 30s cut off answers that were still coming.
        const val READ_TIMEOUT_MS = 90_000

        /**
         * Thinking tokens are drawn from this same ceiling, which is the real reason the earlier
         * attempt at a thinking model truncated: 979 tokens went to reasoning against a 2048 cap and
         * the answer was cut off mid-JSON. The fix is headroom, not less thinking. A few events of
         * JSON is ~200 tokens, so nearly all of this is room for the reasoning to finish.
         */
        const val MAX_OUTPUT_TOKENS = 16_384
    }

    // Cloud has nothing to load, so once a key exists the assistant is usable immediately. A bad
    // key surfaces per-call as Unavailable rather than as a load failure, because there's no cheap
    // way to validate a key without spending a request.
    private val _state = MutableStateFlow<AssistantState>(AssistantState.Ready)
    override val state: StateFlow<AssistantState> = _state.asStateFlow()

    override suspend fun warmUp() = Unit

    override suspend fun parseEvents(
        text: String,
        now: LocalDateTime,
        attachments: List<Attachment>,
    ): AssistantResult<List<EventDraft>> {
        // Blank text with a file is a real message — "here, read this". Blank with nothing is not.
        if (text.isBlank() && attachments.isEmpty()) return AssistantResult.Success(emptyList())
        if (AttachmentReader.tooLarge(attachments)) {
            return AssistantResult.Unavailable(
                "Those files are too big to send. The limit is " +
                    "${AttachmentReader.MAX_TOTAL_BYTES / 1_000_000} MB per message."
            )
        }
        val prompt = PromptBuilder.eventExtraction(
            text = text,
            now = now,
            zoneId = zoneProvider().id,
            memory = memoryProvider(),
            history = historyProvider(),
            attachmentNames = attachments.map { it.name },
        )
        return request(prompt, EVENT_ARRAY_SCHEMA, attachments) { json ->
            LlmJsonParser.parseEventDrafts(json, now.toLocalDate())
        }
    }

    override suspend fun parseQuery(
        text: String,
        now: LocalDateTime,
    ): AssistantResult<AvailabilityQuery> {
        val prompt = PromptBuilder.availabilityQuery(text, now, zoneProvider().id, memoryProvider(), historyProvider())
        return request(prompt, AVAILABILITY_SCHEMA) { json ->
            LlmJsonParser.parseAvailabilityQuery(json, now)
        }
    }

    override suspend fun parseCancellation(
        text: String,
        now: LocalDateTime,
    ): AssistantResult<CancellationQuery> {
        val prompt = PromptBuilder.cancellation(text, now, zoneProvider().id, memoryProvider(), historyProvider())
        return request(prompt, CANCELLATION_SCHEMA) { json ->
            LlmJsonParser.parseCancellation(json, now.toLocalDate())
        }
    }

    /**
     * One grounded answer, in prose.
     *
     * Deliberately shaped unlike every other call here. No `responseSchema` and no JSON mime type,
     * because the API rejects a structured-output request that also carries the search tool — and
     * the tool is the point: "the next race" is a fact about this week, and the alternative to
     * searching is a date recalled from training data that is wrong and sounds right.
     */
    override suspend fun answerQuestion(
        text: String,
        now: LocalDateTime,
    ): AssistantResult<GeneralAnswer> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext AssistantResult.Unavailable("There was no question there.")

        val body = groundedRequestBody(PromptBuilder.generalQuestion(text, now, zoneProvider().id))
        val response = runCatching {
            GeminiHttp.post(
                url = "${GeminiHttp.ENDPOINT}/$model:generateContent",
                apiKey = apiKey,
                body = body,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        }.getOrElse { e ->
            Log.e(TAG, "Grounded request failed", e)
            return@withContext AssistantResult.Unavailable(GeminiHttp.describeFailure(e))
        }

        val answer = GeminiHttp.extractText(response)?.trim()
        if (answer.isNullOrEmpty()) {
            return@withContext AssistantResult.Unavailable(
                GeminiHttp.describeEmptyReply(GeminiHttp.finishReason(response))
            )
        }
        AssistantResult.Success(GeneralAnswer(text = answer, sources = groundingSources(response)))
    }

    /** Search-grounded, free-text. Internal so a test can pin the tool block without a live key. */
    internal fun groundedRequestBody(prompt: String): String {
        val generationConfig = JSONObject()
            .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            .put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))

        val content = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))

        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", generationConfig)
            // An empty object is the whole declaration: the model decides when to search.
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .toString()
    }

    /**
     * The pages the answer was actually built from.
     *
     * Absent when the model chose not to search, which is a real outcome and not an error — but the
     * caller shows the difference, because an answer with no sources was recalled rather than looked
     * up and deserves to be read that way.
     */
    private fun groundingSources(response: String): List<String> = runCatching {
        val chunks = JSONObject(response)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("groundingMetadata")
            ?.optJSONArray("groundingChunks")
            ?: return emptyList()
        (0 until chunks.length()).mapNotNull { i ->
            chunks.optJSONObject(i)?.optJSONObject("web")?.let { web ->
                web.optString("title").takeIf { it.isNotBlank() }
                    ?: web.optString("uri").takeIf { it.isNotBlank() }
            }
        }.distinct().take(4)
    }.getOrDefault(emptyList())

    override fun close() = Unit

    /**
     * One generateContent call. [parse] runs on the returned JSON text, which the response schema
     * guarantees is well-formed — the parser's own failure modes become effectively unreachable on
     * this path, but it's reused rather than trusted-and-bypassed so both paths agree on dates.
     */
    private suspend fun <T> request(
        prompt: String,
        schema: JSONObject,
        attachments: List<Attachment> = emptyList(),
        parse: (String) -> AssistantResult<T>,
    ): AssistantResult<T> = withContext(Dispatchers.IO) {
        val body = requestBody(prompt, schema, attachments)
        val result = runCatching {
            GeminiHttp.post(
                url = "${GeminiHttp.ENDPOINT}/$model:generateContent",
                apiKey = apiKey,
                body = body,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        }
        val response = result.getOrElse { e ->
            Log.e(TAG, "Gemini request failed", e)
            return@withContext AssistantResult.Unavailable(GeminiHttp.describeFailure(e))
        }
        val finish = GeminiHttp.finishReason(response)
        val jsonText = GeminiHttp.extractText(response)
            ?: return@withContext AssistantResult.Unavailable(GeminiHttp.describeEmptyReply(finish))

        // Parse first, then blame the finish reason only if it actually failed. A reply can be
        // flagged MAX_TOKENS and still contain a complete array, and throwing that away would be a
        // false alarm; a genuinely truncated one fails here and deserves the specific message rather
        // than the generic "I couldn't read that".
        val parsed = parse(jsonText)
        if (parsed is AssistantResult.Unparseable && finish == "MAX_TOKENS") {
            Log.w(TAG, "Reply hit the output ceiling before finishing")
            return@withContext AssistantResult.Unavailable(
                "The assistant ran out of room before it finished. Try describing fewer events at " +
                    "once."
            )
        }
        parsed
    }

    /** Internal rather than private so a test can pin the generationConfig without a live key. */
    internal fun requestBody(
        prompt: String,
        schema: JSONObject,
        attachments: List<Attachment> = emptyList(),
    ): String {
        val generationConfig = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", schema)
            // No temperature override on purpose. This used to pin 0.2 for determinism, which was
            // reasonable for a non-thinking model, but Google warns that anything below the default
            // 1.0 on Gemini 3 can make it loop or reason worse — the opposite of why thinking is on.
            // The response schema, not the temperature, is what holds the output shape steady.
            .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            // thinkingLevel, never alongside thinkingBudget — a 3.x model rejects the request if it
            // receives both.
            .put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))

        // Text first, files after. The instruction block is the same on every request and the files
        // are not, so this order is what leaves a stable prefix for implicit caching to match on —
        // the same reasoning that decides the order inside the prompt itself. See [PromptBuilder].
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        attachments.forEach { attachment ->
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", attachment.mimeType)
                        // NO_WRAP: the default inserts newlines every 76 characters, which is valid
                        // base64 to a decoder and a malformed JSON string value to the API.
                        .put("data", Base64.encodeToString(attachment.bytes, Base64.NO_WRAP)),
                )
            )
        }
        val content = JSONObject()
            .put("role", "user")
            .put("parts", parts)

        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", generationConfig)
            .toString()
    }
}

/**
 * Response schema for event extraction — the field names must match what [LlmJsonParser] reads.
 *
 * `all_day` and the time fields are described so the model can't return both a time and all-day at
 * once, the single most common contradiction from the local model. The schema can't express "these
 * are mutually exclusive" structurally, so the wording carries it; the parser still drops times on
 * an all-day event regardless, as a backstop.
 */
private val EVENT_ARRAY_SCHEMA: JSONObject
    get() = JSONObject()
        .put("type", "array")
        .put(
            "items",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("title", strField("Short event name."))
                        .put("date", strField("Event date as YYYY-MM-DD."))
                        .put(
                            "start_time",
                            strField("Start as HH:mm 24-hour, or null for an all-day event.", nullable = true),
                        )
                        .put(
                            "end_time",
                            strField("End as HH:mm 24-hour, or null if unknown.", nullable = true),
                        )
                        .put(
                            "all_day",
                            JSONObject().put("type", "boolean")
                                .put("description", "True only when no start or end time is given."),
                        )
                        .put("location", strField("Place, or null.", nullable = true))
                        .put(
                            "description",
                            strField(
                                "A detail worth keeping about the event, or null. Not a restatement " +
                                    "of the title.",
                                nullable = true,
                            ),
                        )
                        .put(
                            "repeat",
                            JSONObject()
                                .put("type", "string")
                                .put("nullable", true)
                                .put(
                                    "description",
                                    "How the event recurs, only when the text says it does. Null " +
                                        "otherwise.",
                                )
                                // An enum rather than a free string: "fortnightly" has no
                                // representation downstream, and the parser would drop it. Better the
                                // model can't offer it than that it does and the repeat vanishes.
                                .put(
                                    "enum",
                                    JSONArray().put("daily").put("weekly").put("monthly").put("yearly"),
                                ),
                        )
                        .put(
                            "assumption",
                            strField("Short note if a time or date was inferred, else null.", nullable = true),
                        ),
                )
                .put("required", JSONArray().put("title").put("date").put("all_day")),
        )

/** Response schema for an availability question — a single resolved window. */
private val AVAILABILITY_SCHEMA: JSONObject
    get() = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("date", strField("Date in question as YYYY-MM-DD."))
                .put("start_time", strField("Start as HH:mm, or null for the whole day.", nullable = true))
                .put("end_time", strField("End as HH:mm, or null.", nullable = true))
                .put("phrasing", strField("How to refer to this time in a reply.")),
        )
        .put("required", JSONArray().put("date"))

/**
 * Response schema for a cancellation — a description of what to search for.
 *
 * Every field is nullable and none is required except the phrasing, because a usable cancellation
 * can name only a title ("that lunch with Sam") or only a day ("clear Monday"). Notably absent is
 * anything identifying a specific event: there is no id field for the model to fill in, so the
 * "model picks what to delete" failure isn't guarded against here, it's unrepresentable.
 */
private val CANCELLATION_SCHEMA: JSONObject
    get() = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "title_query",
                    strField(
                        "Distinctive words from the event's own name, or null if none were given. " +
                            "Never invent one.",
                        nullable = true,
                    ),
                )
                .put("date", strField("Day named as YYYY-MM-DD, or null if none was.", nullable = true))
                .put("start_time", strField("Time named as HH:mm, or null.", nullable = true))
                .put("end_time", strField("End of the range named as HH:mm, or null.", nullable = true))
                .put("phrasing", strField("How to refer to what the user asked to cancel.")),
        )
        .put("required", JSONArray().put("phrasing"))

private fun strField(description: String, nullable: Boolean = false): JSONObject =
    JSONObject()
        .put("type", "string")
        .put("description", description)
        .apply { if (nullable) put("nullable", true) }
