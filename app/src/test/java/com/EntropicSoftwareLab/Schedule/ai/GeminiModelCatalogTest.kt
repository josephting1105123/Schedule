package com.EntropicSoftwareLab.Schedule.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelCatalogTest {

    @Test
    fun `strips the models prefix and sorts`() {
        val json = """
            {"models":[
              {"name":"models/gemini-3.1-pro","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent","countTokens"]}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("gemini-3.1-pro", "gemini-3.6-flash"),
            GeminiModelCatalog.parseModels(json),
        )
    }

    @Test
    fun `drops models that cannot answer generateContent`() {
        // Embedding and tuning-only models are in the same list and would fail confusingly if one
        // were pinned in Settings.
        val json = """
            {"models":[
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-3.1-pro","supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()
        assertEquals(listOf("gemini-3.1-pro"), GeminiModelCatalog.parseModels(json))
    }

    @Test
    fun `a model with no listed methods is dropped rather than assumed usable`() {
        val json = """{"models":[{"name":"models/mystery"}]}"""
        assertTrue(GeminiModelCatalog.parseModels(json).isEmpty())
    }

    @Test
    fun `garbage and empty responses yield an empty list rather than throwing`() {
        assertTrue(GeminiModelCatalog.parseModels("not json").isEmpty())
        assertTrue(GeminiModelCatalog.parseModels("{}").isEmpty())
        assertTrue(GeminiModelCatalog.parseModels("""{"models":[]}""").isEmpty())
    }
}
