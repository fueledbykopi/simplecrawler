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
    val maxPages: Int = 10,
    val politeDelaySeconds: Float = 1.0f,
    val userAgent: String = DEFAULT_USER_AGENT,
    val timeoutMillis: Int = 12_000
) {
    companion object {
        const val DEFAULT_SEED_PLACEHOLDER = "https://books.toscrape.com/"
        const val DEFAULT_USER_AGENT = "PocketCrawler/1.0 (Android Offline Indexer; +https://github.com/pocket-crawler)"
    }
}

data class CrawlState(
    val isRunning: Boolean = false,
    val visitedCount: Int = 0,
    val savedCount: Int = 0,
    val skippedCount: Int = 0,
    val maxPages: Int = 10,
    val currentUrl: String = "",
    val statusMessage: String = "Ready to crawl",
    val logs: List<CrawlLogEntry> = emptyList()
) {
    val progress: Float
        get() = if (maxPages <= 0) 0f else (visitedCount.toFloat() / maxPages.toFloat()).coerceIn(0f, 1f)
}

data class SavedPage(
    val rowId: Long,
    val url: String,
    val title: String,
    val content: String
)

data class SearchResult(
    val rowId: Long,
    val url: String,
    val title: String,
    val highlightedSnippet: String,
    val rank: Double
)
