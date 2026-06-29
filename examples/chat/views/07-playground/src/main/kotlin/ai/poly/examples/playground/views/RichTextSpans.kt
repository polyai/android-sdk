// Copyright PolyAI Limited

package ai.poly.examples.playground.views

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan

/**
 * Renders agent Markdown into a [Spanned]. Agent text arrives raw — usually Markdown (`**bold**`,
 * `*italic*`, `` `code` ``, `[links](url)`), but it can carry a small HTML subset (most often
 * `<br>`) because the backend serves the same content to the web chat widget. We normalize that
 * HTML allow-list to newlines + Markdown, then fold `[text](url)` links + bold/italic/code into a
 * [Spanned]. Bare `https://…` URLs are NOT auto-linkified (only explicit Markdown links). Set the
 * result on a TextView with `LinkMovementMethod` so links are tappable.
 */
internal fun markdownSpanned(raw: String, linkColor: Int): CharSequence {
    val text = normalizeAgentHtml(raw)
    val sb = SpannableStringBuilder()
    var cursor = 0
    for (m in MD_LINK.findAll(text)) {
        if (m.range.first > cursor) appendInline(sb, text.substring(cursor, m.range.first))
        val label = m.groupValues[1]
        val url = m.groupValues[2]
        val start = sb.length
        appendInline(sb, label) // inline emphasis (**bold** etc.) can nest inside a link label
        if (url.isNotEmpty() && url.none { it.isWhitespace() }) {
            sb.setSpan(URLSpan(url), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(linkColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) appendInline(sb, text.substring(cursor))
    return sb
}

/** Folds `**bold**` / `*italic*` / `` `code` `` over a plain span. */
private fun appendInline(sb: SpannableStringBuilder, text: String) {
    var remaining = text
    while (remaining.isNotEmpty()) {
        val bold = BOLD.find(remaining)
        if (bold != null) {
            sb.append(remaining.substring(0, bold.range.first))
            val s = sb.length; sb.append(bold.groupValues[1])
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            remaining = remaining.substring(bold.range.last + 1); continue
        }
        val italic = ITALIC.find(remaining)
        if (italic != null) {
            sb.append(remaining.substring(0, italic.range.first))
            val s = sb.length; sb.append(italic.groupValues[1])
            sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            remaining = remaining.substring(italic.range.last + 1); continue
        }
        val code = CODE.find(remaining)
        if (code != null) {
            sb.append(remaining.substring(0, code.range.first))
            val s = sb.length; sb.append(code.groupValues[1])
            sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            remaining = remaining.substring(code.range.last + 1); continue
        }
        sb.append(remaining)
        break
    }
}

private val MD_LINK = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
private val BOLD = Regex("""\*\*(.+?)\*\*""")
private val ITALIC = Regex("""(?<!\*)\*([^*]+?)\*(?!\*)""")
private val CODE = Regex("""`([^`]+?)`""")

/**
 * Maps the small HTML subset the agent may emit (the web widget's DOMPurify allow-list:
 * `a, br, b, i, em, strong, p, ul, ol, li, code`) onto newlines + Markdown; drops anything else.
 */
internal fun normalizeAgentHtml(html: String): String {
    if (!html.contains("<") && !html.contains("&")) return html
    var s = html
    val ci = setOf(RegexOption.IGNORE_CASE)
    s = Regex("""<a\b[^>]*\bhref=["']([^"']*)["'][^>]*>(.*?)</a>""", ci).replace(s) { m ->
        "[" + m.groupValues[2] + "](" + m.groupValues[1] + ")"
    }
    fun sub(pattern: String, replacement: String) {
        s = Regex(pattern, ci).replace(s, replacement)
    }
    sub("""<br\s*/?>""", "\n")
    sub("""</p\s*>""", "\n\n"); sub("""<p\b[^>]*>""", "")
    sub("""</?(?:strong|b)\b[^>]*>""", "**")
    sub("""</?(?:em|i)\b[^>]*>""", "*")
    sub("""</?code\b[^>]*>""", "`")
    sub("""<li\b[^>]*>""", "\n• "); sub("""</li\s*>""", "")
    sub("""</?(?:ul|ol)\b[^>]*>""", "\n")
    sub("""<[^>]+>""", "")
    val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&#x27;" to "'", "&apos;" to "'",
    )
    for ((k, v) in entities) s = s.replace(k, v)
    s = Regex("""\n{3,}""").replace(s, "\n\n")
    return s.trim()
}
