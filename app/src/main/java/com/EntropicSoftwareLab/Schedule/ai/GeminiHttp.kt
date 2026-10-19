package com.EntropicSoftwareLab.Schedule.ai

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The shared Gemini transport: one place that knows the endpoint, how the key is sent, and what
 * each failure means in words a user can act on.
 *
 * Extracted so [GeminiAssistant], [GeminiModelCatalog] and the agent cannot drift
 * apart on it. The error messages in particular are the valuable part — "The Gemini API key was
 * rejected. Check it in Settings." is worth far more than a stack trace, and three copies of it
 * would eventually become three different messages.
 */
internal object GeminiHttp {

    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
    const val CONNECT_TIMEOUT_MS = 15_000

    /** Returns the raw response body, or throws [IOException] with a message suitable to show. */
    fun post(url: String, apiKey: String, body: String, readTimeoutMs: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // Header rather than ?key=: a query param lands in server logs and proxy history far
            // more readily than a header does.
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException(describeHttpFailure(code, error))
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun get(url: String, apiKey: String, readTimeoutMs: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException(describeHttpFailure(code, error))
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Pulls the model's answer out of the candidates envelope, or null if the shape is unexpected.
     *
     * Parts are scanned rather than indexed at 0, and any part flagged `thought` is skipped. A
     * thinking model can put a reasoning summary in the parts array, and taking parts[0] blindly
     * would hand that prose to the JSON parser instead of the answer.
     */
    fun extractText(response: String): String? = runCatching {
        val candidates = JSONObject(response).optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val text = part.optString("text").orEmpty()
            if (text.isNotBlank()) return text
        }
        null
    }.getOrNull()

    /** Why the model stopped, e.g. STOP, MAX_TOKENS, SAFETY. Null when absent. */
    fun finishReason(response: String): String? = runCatching {
        JSONObject(response).optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optString("finishReason")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * A candidate with no usable text. With thinking on, the common cause is the reasoning consuming
     * the whole ceiling, so that case is named rather than reported as a mystery.
     */
    fun describeEmptyReply(finish: String?): String = when (finish) {
        "MAX_TOKENS" ->
            "The assistant used all its room thinking and didn't answer. Try describing fewer " +
                "events at once."
        "SAFETY", "PROHIBITED_CONTENT" ->
            "The assistant service declined to answer that."
        "RECITATION" ->
            "The assistant service blocked its own answer. Try rephrasing."
        else -> "The assistant service returned an unexpected response."
    }

    fun describeHttpFailure(code: Int, error: String): String {
        // Gemini returns { "error": { "message": ... } }; surface that when present.
        val serverMessage = runCatching {
            JSONObject(error).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return when (code) {
            HttpURLConnection.HTTP_BAD_REQUEST ->
                serverMessage ?: "The assistant request was rejected (400)."
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                "The Gemini API key was rejected. Check it in Settings."
            HttpURLConnection.HTTP_NOT_FOUND ->
                serverMessage
                    ?: "That Gemini model isn't available to this key. Check the model id in Settings."
            429 ->
                "The free Gemini quota is used up for now. Try again shortly, or the app can fall " +
                    "back to the on-device model if one is installed."
            in 500..599 ->
                "The assistant service is having trouble (HTTP $code). Try again shortly."
            else -> serverMessage ?: "The assistant service returned HTTP $code."
        }
    }

    fun describeFailure(e: Throwable): String = when (e) {
        is IOException -> e.message ?: "Couldn't reach the assistant service. Check your connection."
        else -> "Something went wrong talking to the assistant service."
    }
}
