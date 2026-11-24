package com.pocketcrawler.app.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrawlFrontierTest {

    @Test
    fun `seed is popped first followed by higher in-link priority URLs`() {
        val frontier = CrawlFrontier()
        frontier.enqueue("https://example.com/", depth = 0, isSeed = true)
        frontier.enqueue("https://example.com/a", depth = 1)
        frontier.enqueue("https://example.com/b", depth = 1)
        // Page B gets two more inbound links, so it should leapfrog Page A
        frontier.enqueue("https://example.com/b", depth = 1)
        frontier.enqueue("https://example.com/b", depth = 2)

        assertEquals("https://example.com/", frontier.pollNext()?.url)
        assertEquals("https://example.com/b", frontier.pollNext()?.url)
        assertEquals("https://example.com/a", frontier.pollNext()?.url)
        assertNull(frontier.pollNext())
    }

    @Test
    fun `shallower depth URLs are prioritized over deep query URLs`() {
        val frontier = CrawlFrontier()
        frontier.enqueue("https://example.com/a/b/c/page?sort=asc", depth = 3)
        frontier.enqueue("https://example.com/docs", depth = 1)

        assertEquals("https://example.com/docs", frontier.pollNext()?.url)
        assertEquals("https://example.com/a/b/c/page?sort=asc", frontier.pollNext()?.url)
    }

    @Test
    fun `visited URLs are never returned twice`() {
        val frontier = CrawlFrontier()
        assertTrue(frontier.enqueue("https://example.com/x", depth = 1))
        assertEquals("https://example.com/x", frontier.pollNext()?.url)
        assertFalse(frontier.enqueue("https://example.com/x", depth = 1))
        assertNull(frontier.pollNext())
    }
}
