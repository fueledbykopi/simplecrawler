package com.pocketcrawler.app.crawler

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class ExtractedPage(
    val title: String,
    val cleanText: String,
    val internalLinks: List<String>
)

/**
 * Uses Jsoup to extract page `<title>`, queue internal links, strip
 * `<script>`, `<style>`, `<noscript>`, `<header>`, and `<footer>`, and produce
 * clean readable text for SQLite FTS5 indexing.
 */
object HtmlContentExtractor {

    private const val ELEMENTS_TO_REMOVE = "script, style, noscript, header, footer"

    fun extract(
        html: String,
        baseUrl: String,
        seedDomain: String
    ): ExtractedPage {
        val doc = Jsoup.parse(html, baseUrl)
        return extract(doc, baseUrl, seedDomain)
    }

    fun extract(
        document: Document,
        baseUrl: String,
        seedDomain: String
    ): ExtractedPage {
        // 1. Extract and normalize internal same-domain links before stripping header/footer
        val links = linkedSetOf<String>()
        for (anchor in document.select("a[href]")) {
            val absHref = anchor.absUrl("href")
            val normalized = UrlNormalizer.normalize(absHref) ?: continue
            if (normalized != baseUrl && UrlNormalizer.isSameDomain(normalized, seedDomain)) {
                links.add(normalized)
            }
        }

        // 2. Extract page <title> with sensible fallbacks
        val title = sequenceOf(
            document.title(),
            document.selectFirst("h1")?.text()
        )
            .filterNotNull()
            .map { collapseInlineSpaces(it) }
            .firstOrNull { it.isNotBlank() }
            ?: baseUrl.substringAfter("://").ifBlank { baseUrl }

        // 3. Remove <script>, <style>, <noscript>, <header>, and <footer>
        val cleanDoc = document.clone()
        cleanDoc.select(ELEMENTS_TO_REMOVE).remove()

        // 4. Extract clean structured readable text
        val cleanText = extractReadableText(cleanDoc)

        return ExtractedPage(
            title = title.take(300),
            cleanText = cleanText,
            internalLinks = links.toList()
        )
    }

    private fun extractReadableText(doc: Document): String {
        val body = doc.body() ?: return collapseInlineSpaces(doc.text())
        val blockElements = body.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, td, th, dd, dt")

        if (blockElements.isEmpty()) {
            return collapseInlineSpaces(body.text())
        }

        val paragraphs = mutableListOf<String>()
        var previous = ""
        for (el in blockElements) {
            // Avoid repeating text when nested block tags exist
            if (el.tagName() == "li" && el.selectFirst("p") != null) continue
            val text = collapseInlineSpaces(el.text())
            if (text.length < 2 || text == previous) continue
            paragraphs.add(text)
            previous = text
        }

        return if (paragraphs.isEmpty()) {
            collapseInlineSpaces(body.text())
        } else {
            paragraphs.joinToString("\n\n")
        }
    }

    private fun collapseInlineSpaces(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }
}
