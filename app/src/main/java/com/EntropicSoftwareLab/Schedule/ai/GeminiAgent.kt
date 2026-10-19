package com.EntropicSoftwareLab.Schedule.ai

import android.util.Log
import com.EntropicSoftwareLab.Schedule.data.calendar.GoogleCalendarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** The tool names the declarations and the executor both have to agree on. */
object AgentTool {
    const val SEARCH_EVENTS = "search_events"
    const val CHECK_AVAILABILITY = "check_availability"
    const val PROPOSE_EVENT = "propose_event"
    const val PROPOSE_UPDATE = "propose_update"
    const val PROPOSE_CANCEL = "propose_cancel"
    const val LOOK_UP = "look_up"
}

/**
 * Runs one tool call against the real calendar and returns what the model should be told.
 *
 * Deliberately stringly-typed at this boundary. The agent's job is the conversation loop and it has
 * no business knowing what a `CalendarRepository` is; the ViewModel's job is the calendar and it has
 * no business parsing Gemini's wire format. One `call` keeps each on its own side of the line.
 */
interface AgentTools {
    suspend fun call(name: String, args: JSONObject): String
}

/**
 * The conversational half of the assistant: Gemini decides which calendar action a message needs,
 * calls for it, and can chain several before answering.
 *
 * This is what makes "move my dentist to Tuesday and make it an hour" possible, which no amount of
 * rule-based routing was going to reach. What it does **not** change is the rule everything else is
 * built on. Only two of the five tools touch the provider at all, and both are reads. The other
 * three are named `propose_*` because that is all they do: each one puts a card on screen and
 * returns "shown to the user". Nothing here can write to a calendar; a tap still does that.
 *
 * The model also cannot name an event. `search_events` returns [EventHandles] — `e1`, `e2` — and a
 * proposal has to cite one. A handle it invents resolves to nothing and comes back as an error it
 * can see and recover from, where a hallucinated provider id would have been a perfectly valid
 * request to move someone's meeting.
 */
class GeminiAgent(
    private val apiKey: String,
    private val model: String,
    private val thinkingLevel: String,
) {

    private companion object {
        const val TAG = "GeminiAgent"
        const val READ_TIMEOUT_MS = 90_000
        const val MAX_OUTPUT_TOKENS = 16_384

        /**
         * How many times the model may call tools before it has to answer.
         *
         * A real request needs two or three — search, then propose. The ceiling is here for the
         * failure mode where a model keeps re-searching for something that isn't there: without it
         * that is an unbounded number of billed requests and a spinner that never stops.
         */
        const val MAX_ROUNDS = 6
    }

    /**
     * One exchange. Returns the model's closing prose; any cards were already put on screen by the
     * tool calls along the way.
     */
    suspend fun run(
        text: String,
        now: LocalDateTime,
        zoneId: String,
        memory: String,
        history: String,
        tools: AgentTools,
    ): AssistantResult<String> = withContext(Dispatchers.IO) {
        val contents = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            PromptBuilder.agentTurn(text, now, zoneId, memory, history),
                        )
                    ),
                )
        )

        repeat(MAX_ROUNDS) {
            val response = runCatching {
                GeminiHttp.post(
                    url = "${GeminiHttp.ENDPOINT}/$model:generateContent",
                    apiKey = apiKey,
                    body = requestBody(contents),
                    readTimeoutMs = READ_TIMEOUT_MS,
                )
            }.getOrElse { e ->
                Log.e(TAG, "Agent request failed", e)
                return@withContext AssistantResult.Unavailable(GeminiHttp.describeFailure(e))
            }

            val content = modelContent(response)
                ?: return@withContext AssistantResult.Unavailable(
                    GeminiHttp.describeEmptyReply(GeminiHttp.finishReason(response))
                )

            val calls = functionCalls(content)
            if (calls.isEmpty()) {
                val prose = textOf(content).trim()
                return@withContext if (prose.isEmpty()) {
                    // The model finished having done only tool work. The cards are already up, so
                    // there is nothing wrong here — it just said nothing, and an empty bubble reads
                    // as a failure.
                    AssistantResult.Success("")
                } else {
                    AssistantResult.Success(prose)
                }
            }

            // The model's own turn goes back verbatim. Reconstructing it loses the thinking
            // signature some models attach, and the next request is then rejected as inconsistent.
            contents.put(content)

            val results = JSONArray()
            calls.forEach { (name, args) ->
                val result = runCatching { tools.call(name, args) }
                    .getOrElse { e ->
                        Log.e(TAG, "Tool $name failed", e)
                        "That didn't work: ${e.message ?: "unknown error"}"
                    }
                results.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", name)
                            .put("response", JSONObject().put("result", result)),
                    )
                )
            }
            contents.put(JSONObject().put("role", "user").put("parts", results))
        }

        // Out of rounds with no answer. Said plainly rather than silently: the cards from the calls
        // that did land are on screen and the user needs to know that is all there is.
        AssistantResult.Success(
            "I went round in circles on that one. Anything I did propose is above — try asking " +
                "for one thing at a time."
        )
    }

    internal fun requestBody(contents: JSONArray): String = JSONObject()
        .put("contents", contents)
        .put(
            "generationConfig",
            JSONObject()
                .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                .put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel)),
        )
        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", DECLARATIONS)))
        .toString()

    private fun modelContent(response: String): JSONObject? = runCatching {
        JSONObject(response)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
    }.getOrNull()

    private fun functionCalls(content: JSONObject): List<Pair<String, JSONObject>> {
        val parts = content.optJSONArray("parts") ?: return emptyList()
        return (0 until parts.length()).mapNotNull { i ->
            val call = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: return@mapNotNull null
            val name = call.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            name to (call.optJSONObject("args") ?: JSONObject())
        }
    }

    private fun textOf(content: JSONObject): String {
        val parts = content.optJSONArray("parts") ?: return ""
        return (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
            .joinToString("\n")
    }
}

// ---- tool declarations ---------------------------------------------------

private fun str(
    description: String,
    nullable: Boolean = false,
    enum: List<String>? = null,
): JSONObject =
    JSONObject().put("type", "string").put("description", description)
        .apply {
            if (nullable) put("nullable", true)
            if (enum != null) put("enum", JSONArray().apply { enum.forEach { put(it) } })
        }

private fun bool(description: String): JSONObject =
    JSONObject().put("type", "boolean").put("description", description)

private fun declaration(
    name: String,
    description: String,
    properties: JSONObject,
    required: List<String>,
): JSONObject = JSONObject()
    .put("name", name)
    .put("description", description)
    .put(
        "parameters",
        JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray().apply { required.forEach { put(it) } }),
    )

/**
 * What the model is allowed to do.
 *
 * Two reads and three proposals, and the descriptions say so in as many words — a model that
 * believes `propose_cancel` deletes something will describe its own actions to the user wrongly,
 * which is a worse failure than a wrong draft because it reads as a done deal.
 */
private val DECLARATIONS: JSONArray = JSONArray()
    .put(
        declaration(
            name = AgentTool.SEARCH_EVENTS,
            description = "Find events already on the user's calendar. Returns each match with a " +
                "short handle like e1. You must call this before proposing any change to an " +
                "existing event, because a change can only name a handle this returned.",
            properties = JSONObject()
                .put("query", str("Words from the event's name, e.g. 'dentist'. Omit to list everything in the range.", nullable = true))
                .put("from_date", str("Start of the range to search, YYYY-MM-DD. Defaults to today.", nullable = true))
                .put("to_date", str("End of the range, YYYY-MM-DD. Defaults to a year ahead.", nullable = true)),
            required = emptyList(),
        )
    )
    .put(
        declaration(
            name = AgentTool.CHECK_AVAILABILITY,
            description = "Whether the user is free in a window. The answer is worked out from " +
                "their real events — never answer this from memory or by reasoning about what you " +
                "have seen.",
            properties = JSONObject()
                .put("date", str("The day, YYYY-MM-DD."))
                .put("start_time", str("HH:mm, or omit for the whole day.", nullable = true))
                .put("end_time", str("HH:mm, or omit.", nullable = true)),
            required = listOf("date"),
        )
    )
    .put(
        declaration(
            name = AgentTool.PROPOSE_EVENT,
            description = "Show the user a card proposing a new event. This does NOT create " +
                "anything — the user taps Add. Call it once per event.",
            properties = JSONObject()
                .put("title", str("Short event name, without the user's instruction verb."))
                .put("date", str("YYYY-MM-DD."))
                .put("start_time", str("HH:mm, or omit for an all-day event.", nullable = true))
                .put("end_time", str("HH:mm, or omit if unknown.", nullable = true))
                .put("all_day", bool("True only when no clock time was given."))
                .put("location", str("Place, or omit.", nullable = true))
                .put("description", str("Anything to remember about it, or omit.", nullable = true))
                .put("repeat", str("daily, weekly, monthly or yearly. Omit if it doesn't repeat.", nullable = true))
                .put("assumption", str("Short note if you inferred a time or date.", nullable = true)),
            required = listOf("title", "date", "all_day"),
        )
    )
    .put(
        declaration(
            name = AgentTool.PROPOSE_UPDATE,
            description = "Show the user a card proposing a change to an event that already " +
                "exists. This does NOT change anything — the user taps Apply. Only include the " +
                "fields that change; leave the rest out and they stay as they are.",
            properties = JSONObject()
                .put("event", str("A handle from search_events, e.g. e1."))
                .put("title", str("New name, or omit to keep it.", nullable = true))
                .put("date", str("New date YYYY-MM-DD, or omit.", nullable = true))
                .put("start_time", str("New start HH:mm, or omit.", nullable = true))
                .put("end_time", str("New end HH:mm, or omit.", nullable = true))
                .put("location", str("New place, or omit.", nullable = true))
                .put(
                    "color",
                    str(
                        "New colour, or omit to keep it.",
                        nullable = true,
                        enum = GoogleCalendarColors.PALETTE_ORDER,
                    ),
                ),
            required = listOf("event"),
        )
    )
    .put(
        declaration(
            name = AgentTool.LOOK_UP,
            description = "Look something up on the web — a fixture list, an opening time, when " +
                "the next race is. Use this for ANY fact about the outside world, including ones " +
                "you think you know: your training data is older than today and a stale date " +
                "sounds exactly like a correct one. Not for anything about the user's own calendar.",
            properties = JSONObject()
                .put("question", str("The question, in full, as a sentence.")),
            required = listOf("question"),
        )
    )
    .put(
        declaration(
            name = AgentTool.PROPOSE_CANCEL,
            description = "Show the user a card offering to remove an event. This does NOT delete " +
                "anything — the user taps Cancel event.",
            properties = JSONObject()
                .put("event", str("A handle from search_events, e.g. e1.")),
            required = listOf("event"),
        )
    )
