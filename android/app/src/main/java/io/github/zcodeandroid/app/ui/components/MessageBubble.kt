package io.github.zcodeandroid.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * Message bubbles (owner UI brief): the user bubble carries the primary
 * color and hugs the trailing edge; the agent bubble is a quiet surface
 * on the leading edge. Both render the same minimal Markdown. Flat design:
 * color + hairline border only, never shadows or gradients.
 */

/** User message — primary surface, trailing-aligned. */
@Composable
fun UserMessageBubble(text: String, modifier: Modifier = Modifier) {
    BubbleRow(alignment = Alignment.CenterEnd, modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = bubbleShape(),
            modifier = Modifier.widthIn(max = BubbleMaxWidth),
        ) {
            MarkdownText(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(
                    horizontal = ZcodeThemeValues.spacing.md,
                    vertical = ZcodeThemeValues.spacing.sm,
                ),
            )
        }
    }
}

/** Agent message — surface tone, leading-aligned, hairline border. */
@Composable
fun AgentMessageBubble(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    BubbleRow(alignment = Alignment.CenterStart, modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = bubbleShape(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = BubbleMaxWidth),
        ) {
            MarkdownText(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    horizontal = ZcodeThemeValues.spacing.md,
                    vertical = ZcodeThemeValues.spacing.sm,
                ),
            )
        }
    }
}

@Composable
private fun bubbleShape(): RoundedCornerShape =
    RoundedCornerShape(ZcodeThemeValues.radii.lg)

private val BubbleMaxWidth = 340.dp

@Composable
private fun BubbleRow(
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = ZcodeThemeValues.spacing.lg,
                vertical = ZcodeThemeValues.spacing.xs,
            ),
        contentAlignment = alignment,
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Minimal Markdown rendering
//
// Scope discipline (no dependency added): the subset the agent actually
// emits in prose — fenced code blocks, inline code, bold, italic, #/##/###
// headings, "-" and "*" bullets, numbered items, and [text](url) links
// rendered as underlined text. Anything unrecognized renders as plain text,
// never as fake structure.
// ---------------------------------------------------------------------------

@Composable
fun MarkdownText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val annotated = rememberMarkdown(text)
    Text(text = annotated, style = style, color = color, modifier = modifier)
}

@Composable
private fun rememberMarkdown(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, codeBackground) { renderMarkdown(text, codeBackground) }
}

private fun renderMarkdown(text: String, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        var inFence = false
        val lines = text.split('\n')
        lines.forEachIndexed { index, raw ->
            if (index > 0) append('\n')
            val line = raw.trimEnd()
            if (line.startsWith("```")) {
                inFence = !inFence
                // The fence marker itself carries no content — skip it.
                return@forEachIndexed
            }
            val trimmed = if (inFence) line else line.trimStart()
            when {
                inFence -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        background = codeBackground,
                    ),
                ) { append(line) }
                trimmed.startsWith("### ") -> heading(trimmed.removePrefix("### "), 15.sp)
                trimmed.startsWith("## ") -> heading(trimmed.removePrefix("## "), 16.sp)
                trimmed.startsWith("# ") -> heading(trimmed.removePrefix("# "), 17.sp)
                trimmed.startsWith("- ") -> bullet(trimmed.removePrefix("- "))
                trimmed.startsWith("* ") -> bullet(trimmed.removePrefix("* "))
                trimmed.startsWith("> ") -> quote(trimmed.removePrefix("> "))
                else -> inline(trimmed)
            }
        }
    }

private val NUMBERED = Regex("""^\d+\.\s.*""")

private fun AnnotatedString.Builder.heading(text: String, size: androidx.compose.ui.unit.TextUnit) {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = size)) { inline(text) }
}

private fun AnnotatedString.Builder.bullet(text: String) {
    append("•  ")
    inline(text)
}

private fun AnnotatedString.Builder.quote(text: String) {
    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { inline(text) }
}

/** Split a plain segment into bold/italic/inline-code/link spans. */
private fun AnnotatedString.Builder.inline(text: String) {
    if (text.isEmpty()) return
    val regex = Regex("""\*\*([^*\n]+)\*\*|\*([^*\n]+)\*|`([^`\n]+)`|\[([^\]\n]+)]\(([^)\n]+)\)""")
    var cursor = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val (content, style) = when {
            match.groupValues[1].isNotEmpty() -> match.groupValues[1] to SpanStyle(fontWeight = FontWeight.SemiBold)
            match.groupValues[2].isNotEmpty() -> match.groupValues[2] to SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            match.groupValues[3].isNotEmpty() -> match.groupValues[3] to SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            else -> match.groupValues[4] to SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
        }
        withStyle(style) { append(content) }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
