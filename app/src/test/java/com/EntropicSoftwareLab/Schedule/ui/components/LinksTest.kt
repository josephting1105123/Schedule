package com.EntropicSoftwareLab.Schedule.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {

    private fun urls(text: String) = Links.find(text).map { it.url }

    @Test
    fun `finds a maps link on its own line`() {
        val text = "Rmb to leave on dot or slightly earlier\n\nhttps://maps.app.goo.gl/mNAkAxjsyGQKWnUP7"
        val link = Links.find(text).single()
        assertEquals("https://maps.app.goo.gl/mNAkAxjsyGQKWnUP7", link.url)
        assertEquals(link.url, text.substring(link.range))
    }

    @Test
    fun `sentence punctuation is not part of the link`() {
        assertEquals(listOf("https://x.com/a"), urls("See https://x.com/a."))
        assertEquals(listOf("https://x.com/a"), urls("(https://x.com/a)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/A_(b)"), urls("https://en.wikipedia.org/wiki/A_(b)"))
    }

    @Test
    fun `www addresses open over https`() {
        assertEquals(listOf("https://www.example.com"), urls("go to www.example.com, then"))
    }

    @Test
    fun `several links, and none in plain text`() {
        assertEquals(listOf("http://a.com", "https://b.com/x?y=1"), urls("http://a.com and https://b.com/x?y=1"))
        assertTrue(Links.find("Meet at PJEFC, 9am").isEmpty())
        assertTrue(Links.find("https://").isEmpty())
    }
}
