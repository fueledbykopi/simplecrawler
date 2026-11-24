package com.pocketcrawler.app.ranking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRankCalculatorTest {

    @Test
    fun `hub page with most inbound links ranks highest`() {
        val nodes = listOf(
            "https://example.com/",
            "https://example.com/a",
            "https://example.com/b",
            "https://example.com/hub"
        )
        val edges = listOf(
            "https://example.com/" to "https://example.com/hub",
            "https://example.com/a" to "https://example.com/hub",
            "https://example.com/b" to "https://example.com/hub",
            "https://example.com/" to "https://example.com/a",
            "https://example.com/" to "https://example.com/b"
        )

        val result = PageRankCalculator.compute(nodes, edges)

        val hub = result.getValue("https://example.com/hub")
        val pageA = result.getValue("https://example.com/a")
        val root = result.getValue("https://example.com/")

        assertEquals(3, hub.inLinks)
        assertEquals(0, hub.outLinks)
        assertEquals(100.0, hub.normalizedScore, 0.01)
        assertTrue(hub.rawPageRank > pageA.rawPageRank)
        assertTrue(pageA.rawPageRank > root.rawPageRank)

        // Probability mass across all nodes (including dangling hub) must sum to 1.0
        val totalMass = result.values.sumOf { it.rawPageRank }
        assertEquals(1.0, totalMass, 1e-4)
    }

    @Test
    fun `self loops and duplicate edges are ignored`() {
        val nodes = listOf("https://example.com/a", "https://example.com/b")
        val edges = listOf(
            "https://example.com/a" to "https://example.com/a",
            "https://example.com/a" to "https://example.com/b",
            "https://example.com/a" to "https://example.com/b"
        )

        val result = PageRankCalculator.compute(nodes, edges)
        assertEquals(1, result.getValue("https://example.com/b").inLinks)
        assertEquals(1, result.getValue("https://example.com/a").outLinks)
        assertEquals(0, result.getValue("https://example.com/a").inLinks)
    }

    @Test
    fun `single node graph gets full normalized score`() {
        val result = PageRankCalculator.compute(
            nodes = listOf("https://example.com/"),
            edges = emptyList()
        )
        val root = result.getValue("https://example.com/")
        assertEquals(1.0, root.rawPageRank, 1e-6)
        assertEquals(100.0, root.normalizedScore, 1e-6)
    }
}
