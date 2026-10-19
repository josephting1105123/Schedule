package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/** A web address found in free text: where it sits, and the URL to open. */
data class TextLink(val range: IntRange, val url: String)

/**
 * Web addresses in event notes, so they can be tapped rather than copied out by hand.
 *
 * Pure, so it is tested without Compose. A scheme-less "www." address is opened over https.
 * Punctuation that ends a sentence ("see https://x.com/a.") is left out of the link, and so is a
 * closing bracket the link never opened — "(https://x.com/a)" links to "https://x.com/a".
 * Linear in the length of the text.
 */
object Links {

    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")
    private const val TRAILING = ".,;:!?'\"*"

    fun find(text: String): List<TextLink> = URL.findAll(text).mapNotNull { match ->
        var value = match.value
        while (value.isNotEmpty()) {
            val last = value.last()
            val unopened = (last == ')' && value.count { it == '(' } < value.count { it == ')' }) ||
                (last == ']' && value.count { it == '[' } < value.count { it == ']' })
            if (last in TRAILING || unopened) value = value.dropLast(1) else break
        }
        if (value.isEmpty() || value.endsWith("://") || value.equals("www.", ignoreCase = true)) {
            return@mapNotNull null
        }
        val url = if (value.startsWith("www.", ignoreCase = true)) "https://$value" else value
        TextLink(match.range.first until match.range.first + value.length, url)
    }.toList()

    /** [text] with every address in it a tappable link, underlined in [color]. */
    fun annotate(text: String, color: Color): AnnotatedString {
        val links = find(text)
        if (links.isEmpty()) return AnnotatedString(text)
        val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
        return buildAnnotatedString {
            var at = 0
            for (link in links) {
                append(text, at, link.range.first)
                withLink(LinkAnnotation.Url(link.url, styles)) {
                    append(text, link.range.first, link.range.last + 1)
                }
                at = link.range.last + 1
            }
            append(text, at, text.length)
        }
    }
}
