package com.pocketcrawler.app.ranking

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {

    @Test
    fun `stronger BM25 relevance outranks weaker text match`() {
        val strongMatch = SearchRanker.computeCompositeScore(
            rawFts5Bm25 = -8.0,
            normalizedPageRank = 50.0,
            inLinks = 5,
            crawlDepth = 1
        )
        val weakMatch = SearchRanker.computeCompositeScore(
            rawFts5Bm25 = -1.0,
            normalizedPageRank = 50.0,
            inLinks = 5,
            crawlDepth = 1
        )
        assertTrue(strongMatch > weakMatch)
    }

    @Test
    fun `higher PageRank and in-links boost equal text matches`() {
        val authoritative = SearchRanker.computeCompositeScore(
            rawFts5Bm25 = -4.0,
            normalizedPageRank = 95.0,
            inLinks = 30,
            crawlDepth = 1
        )
        val obscure = SearchRanker.computeCompositeScore(
            rawFts5Bm25 = -4.0,
            normalizedPageRank = 10.0,
            inLinks = 0,
            crawlDepth = 4
        )
        assertTrue(authoritative > obscure)
        assertTrue(authoritative in 0.0..100.0)
        assertTrue(obscure in 0.0..100.0)
    }
}
