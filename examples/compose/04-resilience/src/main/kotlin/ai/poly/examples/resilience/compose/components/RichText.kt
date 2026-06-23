// Copyright PolyAI Limited

package ai.poly.examples.resilience.compose.components

import ai.poly.examples.resilience.compose.SystemBlue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Agent text arrives raw — usually Markdown (`**bold**`,
 * `*italic*`, `` `code` ``, `[links](url)`), but it can also carry a small HTML subset (most often
 * `<br>`) because the backend serves the same content to the web chat widget. We first normalize that
 * HTML allow-list to newlines + Markdown, then parse Markdown links + bare URLs + bold/italic/code into
 * a clickable [AnnotatedString]. Tolerates half-open Markdown mid-stream (leaves it literal).
 */
@Composable
fun RichText(text: String, modifier: Modifier = Modifier, color: Color = Color.Black) {
    val annotated = remember(text) { parse(normalizeAgentHtml(text)) }
    Text(annotated, modifier = modifier, color = color)
}

/**
 * Maps the small HTML subset the agent may emit (mirrors the web widget's DOMPurify allow-list:
 * `a, br, b, i, em, strong, p, ul, ol, li, code`) onto newlines + Markdown that [parse] understands.
 * Anything outside the allow-list is dropped, mirroring DOMPurify sanitising disallowed tags.
 */
internal fun normalizeAgentHtml(html: String): String {
    if (!html.contains("<") && !html.contains("&")) return html
    var s = html
    val ci = setOf(RegexOption.IGNORE_CASE)
    // <a href="…">text</a> → [text](url)
    s = Regex("""<a\b[^>]*\bhref=["']([^"']*)["'][^>]*>(.*?)</a>""", ci).replace(s) { m ->
        "[" + m.groupValues[2] + "](" + m.groupValues[1] + ")"
    }
    fun sub(pattern: String, replacement: String) {
        s = Regex(pattern, ci).replace(s, replacement)
    }
    sub("""<br\s*/?>""", "\n")                        // line break
    sub("""</p\s*>""", "\n\n"); sub("""<p\b[^>]*>""", "") // paragraph
    sub("""</?(?:strong|b)\b[^>]*>""", "**")           // bold
    sub("""</?(?:em|i)\b[^>]*>""", "*")                // italic
    sub("""</?code\b[^>]*>""", "`")                    // inline code
    sub("""<li\b[^>]*>""", "\n• "); sub("""</li\s*>""", "") // list item
    sub("""</?(?:ul|ol)\b[^>]*>""", "\n")              // list container
    sub("""<[^>]+>""", "")                             // drop any other tag
    val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&#x27;" to "'", "&apos;" to "'",
    )
    for ((k, v) in entities) s = s.replace(k, v)
    s = Regex("""\n{3,}""").replace(s, "\n\n")
    return s.trim()
}

private val MD_LINK = """\[([^\]]+)\]\(([^)]+)\)"""
private val BARE_URL = """https?://[^\s<>"'`\]\[]+[^\s<>"'`\]\[.,;:!?)]"""
// Group 1 = whole md-link, 2 = link text, 3 = url, 4 = bare url.
private val COMBINED = Regex("($MD_LINK)|($BARE_URL)")
private val BOLD = Regex("""\*\*(.+?)\*\*""")
private val ITALIC = Regex("""(?<!\*)\*([^*]+?)\*(?!\*)""")
private val CODE = Regex("""`([^`]+?)`""")

private val LINK_STYLE = TextLinkStyles(SpanStyle(color = SystemBlue, textDecoration = TextDecoration.Underline))

/** Markdown `[text](url)` + bare `https?://…` → links, with bold/italic/code folded over the gaps. */
private fun parse(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (m in COMBINED.findAll(text)) {
        if (m.range.first > cursor) appendInline(text.substring(cursor, m.range.first))
        if (m.groups[1] != null) {
            appendLink(label = m.groups[2]?.value ?: "", url = m.groups[3]?.value ?: "")
        } else {
            val url = m.groups[4]?.value ?: ""
            appendLink(label = url, url = url)
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) appendInline(text.substring(cursor))
}

private fun AnnotatedString.Builder.appendLink(label: String, url: String) {
    // A URL with whitespace (e.g. `[x](my url)`) is not a valid URL, so render it as plain
    // unstyled text.
    if (url.isNotEmpty() && url.none { it.isWhitespace() }) {
        withLink(LinkAnnotation.Url(url, LINK_STYLE)) { append(label) }
    } else {
        append(label)
    }
}

/** Folds `**bold**` / `*italic*` / `` `code` `` over a plain span (bold/italic/code priority). */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var remaining = text
    while (remaining.isNotEmpty()) {
        val bold = BOLD.find(remaining)
        if (bold != null) {
            append(remaining.substring(0, bold.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.groupValues[1]) }
            remaining = remaining.substring(bold.range.last + 1)
            continue
        }
        val italic = ITALIC.find(remaining)
        if (italic != null) {
            append(remaining.substring(0, italic.range.first))
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic.groupValues[1]) }
            remaining = remaining.substring(italic.range.last + 1)
            continue
        }
        val code = CODE.find(remaining)
        if (code != null) {
            append(remaining.substring(0, code.range.first))
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code.groupValues[1]) }
            remaining = remaining.substring(code.range.last + 1)
            continue
        }
        append(remaining)
        break
    }
}
