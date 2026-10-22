package com.trailguide.app.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Lightweight Markdown renderer for GitHub release notes.
 * Supports: # headers, **bold**, *italic*, lists, [links](url), `code`.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    linkColor: Color = MaterialTheme.colorScheme.primary
) {
    val spanned = remember(markdown) { parseSimpleMarkdown(markdown) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            }
        },
        update = { tv ->
            tv.setTextColor(textColor.toArgb())
            tv.setLinkTextColor(linkColor.toArgb())
            tv.text = spanned
        }
    )
}

internal fun parseSimpleMarkdown(input: String): Spanned {
    val builder = SpannableStringBuilder()
    val lines = input.replace("\r\n", "\n").split("\n")
    var first = true
    for (raw in lines) {
        if (!first) builder.append("\n")
        first = false
        val line = raw

        val headerMatch = Regex("""^(#{1,3})\s+(.*)$""").find(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val text = headerMatch.groupValues[2]
            val start = builder.length
            appendInlineMarkdown(builder, text)
            val end = builder.length
            val scale = when (level) {
                1 -> 1.35f
                2 -> 1.2f
                else -> 1.1f
            }
            builder.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            continue
        }

        val listMatch = Regex("""^\s*[-*+]\s+(.*)$""").find(line)
        if (listMatch != null) {
            appendInlineMarkdown(builder, "• ${listMatch.groupValues[1]}")
            continue
        }

        val ordered = Regex("""^\s*(\d+)[.)]\s+(.*)$""").find(line)
        if (ordered != null) {
            appendInlineMarkdown(builder, "${ordered.groupValues[1]}. ${ordered.groupValues[2]}")
            continue
        }

        if (line.isBlank()) {
            builder.append(" ")
            continue
        }

        appendInlineMarkdown(builder, line)
    }
    return builder
}

private fun appendInlineMarkdown(builder: SpannableStringBuilder, text: String) {
    var i = 0
    val s = text
    while (i < s.length) {
        if (s[i] == '[') {
            val close = s.indexOf(']', i)
            if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                val urlEnd = s.indexOf(')', close + 2)
                if (urlEnd > close) {
                    val label = s.substring(i + 1, close)
                    val url = s.substring(close + 2, urlEnd)
                    val start = builder.length
                    builder.append(label)
                    builder.setSpan(URLSpan(url), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = urlEnd + 1
                    continue
                }
            }
        }
        if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
            val end = s.indexOf("**", i + 2)
            if (end > i) {
                val start = builder.length
                builder.append(s.substring(i + 2, end))
                builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = end + 2
                continue
            }
        }
        if (s[i] == '*') {
            val end = s.indexOf('*', i + 1)
            if (end > i) {
                val start = builder.length
                builder.append(s.substring(i + 1, end))
                builder.setSpan(StyleSpan(Typeface.ITALIC), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = end + 1
                continue
            }
        }
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i) {
                val start = builder.length
                builder.append(s.substring(i + 1, end))
                // StyleSpan only accepts BOLD/ITALIC — use TypefaceSpan for code
                builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = end + 1
                continue
            }
        }
        builder.append(s[i])
        i++
    }
}
