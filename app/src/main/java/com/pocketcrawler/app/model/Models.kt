package com.pocketcrawler.app.model

enum class CrawlLogType(val tag: String) {
    CRAWLING("[CRAWLING]"),
    SAVED("[SAVED]"),
    SKIPPED("[SKIPPED]")
}

data class CrawlLogEntry(
    val id: Long,
    val type: CrawlLogType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class CrawlConfig(
    val seedUrl: String,
    val maxPages: Int = DEFAULT_MAX_PAGES,
    val politeDelaySeconds: Float = 1.0f,
    val userAgent: String = DEFAULT_USER_AGENT,
    val timeoutMillis: Int = 12_000
) {
    companion object {
        const val MIN_PAGES_LIMIT = 1
        const val DEFAULT_MAX_PAGES = 10
        const val SLIDER_MAX_PAGES = 1000
        const val MAX_CUSTOM_PAGES_DIGITS = 7
        const val DEFAULT_SEED_PLACEHOLDER = "https://books.toscrape.com/"
        const val DEFAULT_USER_AGENT = "PocketCrawler/1.0 (Android Offline Indexer; +https://github.com/pocket-crawler)"

        fun sanitizeMaxPagesInput(rawInput: String): String {
            return rawInput.filter { it.isDigit() }.take(MAX_CUSTOM_PAGES_DIGITS)
        }

        fun parseCustomMaxPages(rawInput: String): Int? {
            val parsed = sanitizeMaxPagesInput(rawInput).toIntOrNull() ?: return null
            return if (parsed >= MIN_PAGES_LIMIT) parsed else null
        }
    }
}

data class CrawlState(
    val isRunning: Boolean = false,
    val isRanking: Boolean = false,
    val visitedCount: Int = 0,
    val savedCount: Int = 0,
    val skippedCount: Int = 0,
    val queuedCount: Int = 0,
    val maxPages: Int = CrawlConfig.DEFAULT_MAX_PAGES,
    val currentUrl: String = "",
    val statusMessage: String = "Ready to crawl",
    val logs: List<CrawlLogEntry> = emptyList()
) {
    val progress: Float
        get() = if (maxPages <= 0) 0f else (visitedCount.toFloat() / maxPages.toFloat()).coerceIn(0f, 1f)
}

enum class IndexSortMode(val label: String) {
    TOP_RANKED("Top Ranked"),
    MOST_RECENT("Most Recent")
}

data class SavedPage(
    val rowId: Long,
    val url: String,
    val title: String,
    val content: String,
    val pageRank: Double = 0.0,
    val inLinks: Int = 0,
    val outLinks: Int = 0,
    val crawlDepth: Int = 0,
    val wordCount: Int = 0,
    val indexedAt: Long = 0L
)

data class SearchResult(
    val rowId: Long,
    val url: String,
    val title: String,
    val highlightedSnippet: String,
    val rank: Double,
    val bm25Score: Double = 0.0,
    val pageRank: Double = 0.0,
    val inLinks: Int = 0,
    val crawlDepth: Int = 0
)
