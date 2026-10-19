package com.EntropicSoftwareLab.Schedule.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Pins the shape of the generateContent request. None of this can be caught by the compiler and none
 * of it is reachable without a live key, so the two ways to get it wrong both fail silently at build
 * time and loudly on the user's first request:
 *
 *  - sending `thinkingBudget` next to `thinkingLevel`, which a Gemini 3 model rejects outright;
 *  - sending a temperature below 1.0, which Google warns degrades reasoning on Gemini 3.
 */
class GeminiRequestBodyTest {

    private val assistant = GeminiAssistant(
        apiKey = "test-key-not-real",
        zoneProvider = { ZoneId.of("Europe/London") },
    )

    private fun generationConfig(): JSONObject =
        JSONObject(assistant.requestBody("hello", JSONObject().put("type", "array")))
            .getJSONObject("generationConfig")

    @Test
    fun `thinking level is sent and is a level, not a budget`() {
        val thinking = generationConfig().getJSONObject("thinkingConfig")
        assertEquals("high", thinking.getString("thinkingLevel"))
        // The pair is a hard error on a 3.x model, so the old key must be gone, not merely ignored.
        assertFalse(
            "thinkingBudget must never accompany thinkingLevel",
            thinking.has("thinkingBudget"),
        )
    }

    @Test
    fun `no temperature override is sent`() {
        // Absent means the API default of 1.0, which is what Google recommends for Gemini 3.
        assertFalse(generationConfig().has("temperature"))
    }

    @Test
    fun `output ceiling leaves room for thinking plus the answer`() {
        // Thinking is drawn from this same ceiling; 2048 truncated real replies mid-JSON.
        assertTrue(generationConfig().getInt("maxOutputTokens") >= 8192)
    }

    @Test
    fun `the reply is pinned to schema-valid JSON`() {
        val config = generationConfig()
        assertEquals("application/json", config.getString("responseMimeType"))
        assertTrue(config.has("responseSchema"))
    }

    @Test
    fun `the prompt is sent as a single user part`() {
        val body = JSONObject(assistant.requestBody("dinner friday", JSONObject()))
        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())
        val content = contents.getJSONObject(0)
        assertEquals("user", content.getString("role"))
        val parts = content.getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals("dinner friday", parts.getJSONObject(0).getString("text"))
    }

    @Test
    fun `an explicit thinking level overrides the default`() {
        val low = GeminiAssistant(
            apiKey = "test-key-not-real",
            zoneProvider = { ZoneId.of("UTC") },
            thinkingLevel = "low",
        )
        val thinking = JSONObject(low.requestBody("x", JSONObject()))
            .getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig")
        assertEquals("low", thinking.getString("thinkingLevel"))
    }
}
