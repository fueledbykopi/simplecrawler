package com.pocketcrawler.app.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun `normalizeSeedUrl prepends https when scheme is omitted`() {
        assertEquals("https://books.toscrape.com/", UrlNormalizer.normalizeSeedUrl("books.toscrape.com"))
        assertEquals("https://example.com/docs", UrlNormalizer.normalizeSeedUrl("https://EXAMPLE.COM:443/docs/"))
    }

    @Test
    fun `normalize strips hash fragments`() {
        assertEquals(
            "https://books.toscrape.com/catalogue/page-2.html",
            UrlNormalizer.normalize("https://books.toscrape.com/catalogue/page-2.html#reviews")
        )
    }

    @Test
    fun `normalize rejects non-http schemes and binary assets`() {
        assertNull(UrlNormalizer.normalize("mailto:test@example.com"))
        assertNull(UrlNormalizer.normalize("javascript:void(0)"))
        assertNull(UrlNormalizer.normalize("https://books.toscrape.com/cover.jpg"))
        assertNull(UrlNormalizer.normalize("https://books.toscrape.com/catalog.pdf"))
        assertNull(UrlNormalizer.normalize("https://books.toscrape.com/style.css"))
    }

    @Test
    fun `isSameDomain stays within seed domain`() {
        assertTrue(UrlNormalizer.isSameDomain("https://books.toscrape.com/catalogue/a.html", "books.toscrape.com"))
        assertTrue(UrlNormalizer.isSameDomain("https://www.books.toscrape.com/", "books.toscrape.com"))
        assertFalse(UrlNormalizer.isSameDomain("https://quotes.toscrape.com/", "books.toscrape.com"))
        assertFalse(UrlNormalizer.isSameDomain("https://example.com/", "books.toscrape.com"))
    }
}
