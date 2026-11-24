package com.pocketcrawler.app.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.pocketcrawler.app.data.search.FtsQueryBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
            count >= 1_000L -> String.format(Locale.US, "%.1fk", count / 1_000.0)
            else -> count.toString()
        }
    }

    fun formatRelativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffMs = (nowMs - epochMs).coerceAtLeast(0L)
        val seconds = diffMs / 1000L
        return when {
            seconds < 45L -> "Just now"
            seconds < 3600L -> "${seconds / 60L}m ago"
            seconds < 86_400L -> "${seconds / 3600L}h ago"
            seconds < 7 * 86_400L -> "${seconds / 86_400L}d ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochMs))
        }
    }

    fun formatFullTimestamp(epochMs: Long): String {
        return SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.US).format(Date(epochMs))
    }

    fun formatDuration(millis: Long): String {
        val totalSec = (millis / 1000L).coerceAtLeast(0L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return if (mins > 0L) "${mins}m ${secs}s" else "${secs}s"
    }

    /**
     * Converts SQLite FTS5 `snippet()` / `highlight()` output delimited by `«` and `»`
     * into a styled [AnnotatedString].
     */
    fun parseFtsHighlightedText(
        markedText: String,
        highlightBg: Color,
        highlightFg: Color
    ): AnnotatedString {
        val startMarker = FtsQueryBuilder.HIGHLIGHT_START[0]
        val endMarker = FtsQueryBuilder.HIGHLIGHT_END[0]

        return buildAnnotatedString {
            var inHighlight = false
            val buffer = StringBuilder()

            fun flush() {
                if (buffer.isEmpty()) return
                if (inHighlight) {
                    withStyle(
                        SpanStyle(
                            background = highlightBg,
                            color = highlightFg,
                            fontWeight = FontWeight.Bold
                        )
                    ) {
                        append(buffer.toString())
                    }
                } else {
                    append(buffer.toString())
                }
                buffer.setLength(0)
            }

            for (ch in markedText) {
                when (ch) {
                    startMarker -> {
                        flush()
                        inHighlight = true
                    }
                    endMarker -> {
                        flush()
                        inHighlight = false
                    }
                    else -> buffer.append(ch)
                }
            }
            flush()
        }
    }

    /**
     * Highlights case-insensitive occurrences of search terms inside an Offline Reader paragraph.
     */
    fun highlightTermsInParagraph(
        paragraph: String,
        terms: List<String>,
        highlightBg: Color,
        highlightFg: Color
    ): AnnotatedString {
        if (terms.isEmpty() || paragraph.isEmpty()) return AnnotatedString(paragraph)

        val lower = paragraph.lowercase(Locale.ROOT)
        val ranges = mutableListOf<IntRange>()

        for (term in terms) {
            if (term.length < 2) continue
            var startIndex = 0
            while (startIndex < lower.length) {
                val found = lower.indexOf(term, startIndex)
                if (found < 0) break
                ranges.add(found until (found + term.length))
                startIndex = found + term.length
            }
        }

        if (ranges.isEmpty()) return AnnotatedString(paragraph)

        // Merge overlapping ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (r in sorted) {
            if (merged.isEmpty() || r.first > merged.last().last + 1) {
                merged.add(r)
            } else {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(prev.first..maxOf(prev.last, r.last))
            }
        }

        return buildAnnotatedString {
            var cursor = 0
            for (r in merged) {
                if (r.first > cursor) {
                    append(paragraph.substring(cursor, r.first))
                }
                withStyle(
                    SpanStyle(
                        background = highlightBg,
                        color = highlightFg,
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(paragraph.substring(r.first, r.last + 1))
                }
                cursor = r.last + 1
            }
            if (cursor < paragraph.length) {
                append(paragraph.substring(cursor))
            }
        }
    }
}
