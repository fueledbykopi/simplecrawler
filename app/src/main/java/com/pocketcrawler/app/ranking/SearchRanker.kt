package com.pocketcrawler.app.ranking

import kotlin.math.ln

/**
 * Hybrid search ranker combining:
 * - SQLite FTS5 field-weighted BM25 text relevance (title 5x, content 1x)
 * - Persistent PageRank link-graph authority (0..100)
 * - Logarithmic in-link popularity signal
 * - Shallow crawl-depth preference
 *
 * Produces a unified 0..100 score where higher values are more relevant/authoritative.
 */
object SearchRanker {

    private const val WEIGHT_TEXT_BM25 = 0.65
    private const val WEIGHT_PAGE_RANK = 0.22
    private const val WEIGHT_IN_LINKS = 0.08
    private const val WEIGHT_DEPTH = 0.05

    private const val BM25_HALF_SATURATION = 2.0
    private const val IN_LINK_SATURATION_BASE = 50.0

    fun computeCompositeScore(
        rawFts5Bm25: Double,
        normalizedPageRank: Double,
        inLinks: Int,
        crawlDepth: Int
    ): Double {
        // In SQLite FTS5, bm25() returns negative values where more negative = stronger match.
        val positiveRelevance = (-rawFts5Bm25).coerceAtLeast(0.0)
        val textScore = if (positiveRelevance > 0.0) {
            positiveRelevance / (positiveRelevance + BM25_HALF_SATURATION)
        } else {
            0.0
        }

        val prScore = (normalizedPageRank / 100.0).coerceIn(0.0, 1.0)

        val safeInLinks = inLinks.coerceAtLeast(0).toDouble()
        val inLinkScore = (ln(1.0 + safeInLinks) / ln(1.0 + IN_LINK_SATURATION_BASE)).coerceIn(0.0, 1.0)

        val safeDepth = crawlDepth.coerceAtLeast(0).toDouble()
        val depthScore = (1.0 / (1.0 + safeDepth * 0.35)).coerceIn(0.0, 1.0)

        val composite = (
            WEIGHT_TEXT_BM25 * textScore +
                WEIGHT_PAGE_RANK * prScore +
                WEIGHT_IN_LINKS * inLinkScore +
                WEIGHT_DEPTH * depthScore
            ) * 100.0

        return composite.coerceIn(0.0, 100.0)
    }
}
