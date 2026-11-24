package com.pocketcrawler.app.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlContentExtractorTest {

    @Test
    fun `extracts title, internal same-domain links, and clean text without boilerplate elements`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <title>A Light in the Attic | Books to Scrape</title>
                <style>body { color: black; }</style>
                <script>console.log("ignore script");</script>
              </head>
              <body>
                <header>
                  <a href="/catalogue/category/books_1/index.html#top">All Books</a>
                  <span>Header Navigation Banner</span>
                </header>
                <noscript>Enable JavaScript notice</noscript>
                <main>
                  <h1>A Light in the Attic</h1>
                  <p>It's hard to imagine a world without A Light in the Attic.</p>
                  <a href="https://books.toscrape.com/catalogue/tipping-the-velvet_999/index.html">Tipping the Velvet</a>
                  <a href="https://external.example.org/other">External Link</a>
                  <a href="/media/cache/cover.jpg">Cover Image</a>
                </main>
                <footer>Footer Copyright Text</footer>
              </body>
            </html>
        """.trimIndent()

        val extracted = HtmlContentExtractor.extract(
            html = html,
            baseUrl = "https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html",
            seedDomain = "books.toscrape.com"
        )

        assertEquals("A Light in the Attic | Books to Scrape", extracted.title)
        assertTrue(extracted.cleanText.contains("hard to imagine a world without A Light in the Attic"))
        assertFalse(extracted.cleanText.contains("ignore script"))
        assertFalse(extracted.cleanText.contains("color: black"))
        assertFalse(extracted.cleanText.contains("Enable JavaScript notice"))
        assertFalse(extracted.cleanText.contains("Header Navigation Banner"))
        assertFalse(extracted.cleanText.contains("Footer Copyright Text"))

        // Internal same-domain links queued with fragments stripped; external & images excluded
        assertTrue("https://books.toscrape.com/catalogue/category/books_1/index.html" in extracted.internalLinks)
        assertTrue("https://books.toscrape.com/catalogue/tipping-the-velvet_999/index.html" in extracted.internalLinks)
        assertFalse(extracted.internalLinks.any { it.contains("external.example.org") })
        assertFalse(extracted.internalLinks.any { it.endsWith(".jpg") })
    }
}
