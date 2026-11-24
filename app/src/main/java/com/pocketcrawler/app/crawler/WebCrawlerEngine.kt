package com.pocketcrawler.app.crawler

import com.pocketcrawler.app.data.db.PocketCrawlerDbHelper
import com.pocketcrawler.app.model.CrawlConfig
import com.pocketcrawler.app.model.CrawlLogEntry
import com.pocketcrawler.app.model.CrawlLogType
import com.pocketcrawler.app.model.CrawlState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Polite Best-First Priority Web Crawler running on [Dispatchers.IO].
 *
 * Algorithms & durability:
 * - **Priority Frontier ([CrawlFrontier])**: Best-first priority queue using online in-link
 *   popularity (OPIC-style), shallow crawl-depth preference, and clean-URL scoring.
 * - **Content Deduplication**: SHA-256 content hashing avoids storing exact-duplicate pages
 *   under alternate URLs while still preserving link edges for PageRank.
 * - **Durable Index Persistence**: Saves every page, metadata, and link graph atomically into
 *   SQLite WAL (`pages` FTS5 + `page_meta` + `page_links`).
 * - **Iterative PageRank**: Periodically recomputes PageRank authority across all saved indexes
 *   during long crawls and upon completion/cancellation.
 */
class WebCrawlerEngine(
    private val dbHelper: PocketCrawlerDbHelper,
    private val onDatabaseMutated: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logIdGen = AtomicLong(0L)

    private val _state = MutableStateFlow(CrawlState())
    val state: StateFlow<CrawlState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun startCrawl(config: CrawlConfig) {
        if (_state.value.isRunning) return

        val rawSeed = config.seedUrl.trim().ifEmpty { CrawlConfig.DEFAULT_SEED_PLACEHOLDER }
        val normalizedSeed = UrlNormalizer.normalizeSeedUrl(rawSeed)
        if (normalizedSeed == null) {
            _state.value = CrawlState(
                isRunning = false,
                maxPages = config.maxPages,
                statusMessage = "Invalid URL: please enter a valid http(s) address",
                logs = listOf(
                    newLog(CrawlLogType.SKIPPED, "Invalid seed URL: $rawSeed")
                )
            )
            return
        }

        val seedDomain = UrlNormalizer.extractHost(normalizedSeed)
        val maxPages = config.maxPages.coerceAtLeast(CrawlConfig.MIN_PAGES_LIMIT)
        val delayMs = (config.politeDelaySeconds.coerceIn(0f, 10f) * 1000f).toLong()

        activeJob = scope.launch {
            runCrawlLoop(
                seedUrl = normalizedSeed,
                seedDomain = seedDomain,
                maxPages = maxPages,
                delayMs = delayMs,
                userAgent = config.userAgent,
                timeoutMillis = config.timeoutMillis
            )
        }
    }

    fun stopCrawl() {
        activeJob?.cancel()
    }

    private suspend fun runCrawlLoop(
        seedUrl: String,
        seedDomain: String,
        maxPages: Int,
        delayMs: Long,
        userAgent: String,
        timeoutMillis: Int
    ) {
        val frontier = CrawlFrontier().apply {
            enqueue(url = seedUrl, depth = 0, isSeed = true)
        }

        val seenContentHashes = try {
            HashMap(dbHelper.getExistingContentHashes())
        } catch (_: Throwable) {
            HashMap<String, String>()
        }

        var visitedCount = 0
        var savedCount = 0
        var skippedCount = 0
        var savedSinceLastRank = 0

        _state.value = CrawlState(
            isRunning = true,
            isRanking = false,
            visitedCount = 0,
            savedCount = 0,
            skippedCount = 0,
            queuedCount = frontier.pendingCount(),
            maxPages = maxPages,
            currentUrl = seedUrl,
            statusMessage = "Priority crawling $seedDomain…",
            logs = emptyList()
        )

        var wasCancelled = false

        try {
            while (coroutineContextIsActive() && frontier.hasPending() && visitedCount < maxPages) {
                val item = frontier.pollNext() ?: break
                val url = item.url

                if (!UrlNormalizer.isSameDomain(url, seedDomain)) {
                    skippedCount++
                    appendLog(
                        CrawlLogType.SKIPPED,
                        "External domain: $url",
                        visitedCount,
                        savedCount,
                        skippedCount,
                        frontier.pendingCount(),
                        url
                    )
                    continue
                }

                // Polite delay between consecutive requests
                if (visitedCount > 0 && delayMs > 0L) {
                    delay(delayMs)
                }

                visitedCount++
                appendLog(
                    CrawlLogType.CRAWLING,
                    "[d=${item.depth}] $url",
                    visitedCount,
                    savedCount,
                    skippedCount,
                    frontier.pendingCount(),
                    url
                )

                try {
                    val response = Jsoup.connect(url)
                        .userAgent(userAgent)
                        .timeout(timeoutMillis)
                        .followRedirects(true)
                        .ignoreContentType(true)
                        .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                        .execute()

                    val contentType = response.contentType().orEmpty().lowercase()
                    if (contentType.isNotEmpty() &&
                        !contentType.contains("text/html") &&
                        !contentType.contains("application/xhtml")
                    ) {
                        skippedCount++
                        appendLog(
                            CrawlLogType.SKIPPED,
                            "Non-HTML ($contentType) — $url",
                            visitedCount,
                            savedCount,
                            skippedCount,
                            frontier.pendingCount(),
                            url
                        )
                        continue
                    }

                    val finalUrl = UrlNormalizer.normalize(response.url().toString()) ?: url
                    if (finalUrl != url) {
                        if (!UrlNormalizer.isSameDomain(finalUrl, seedDomain)) {
                            skippedCount++
                            appendLog(
                                CrawlLogType.SKIPPED,
                                "Redirected off-domain — $finalUrl",
                                visitedCount,
                                savedCount,
                                skippedCount,
                                frontier.pendingCount(),
                                finalUrl
                            )
                            continue
                        }
                        if (frontier.isVisited(finalUrl)) {
                            skippedCount++
                            appendLog(
                                CrawlLogType.SKIPPED,
                                "Duplicate redirect — $finalUrl",
                                visitedCount,
                                savedCount,
                                skippedCount,
                                frontier.pendingCount(),
                                finalUrl
                            )
                            continue
                        }
                        frontier.markVisited(finalUrl)
                    }

                    val extracted = HtmlContentExtractor.extract(
                        html = response.body(),
                        baseUrl = finalUrl,
                        seedDomain = seedDomain
                    )

                    // Enqueue internal links into Priority Frontier (boosts in-link priority even for queued URLs)
                    val nextDepth = item.depth + 1
                    for (link in extracted.internalLinks) {
                        frontier.enqueue(url = link, depth = nextDepth, isSeed = false)
                    }

                    if (extracted.cleanText.isBlank()) {
                        skippedCount++
                        appendLog(
                            CrawlLogType.SKIPPED,
                            "Empty page text — $finalUrl",
                            visitedCount,
                            savedCount,
                            skippedCount,
                            frontier.pendingCount(),
                            finalUrl
                        )
                    } else {
                        val contentHash = sha256Hex(extracted.cleanText)
                        val existingOwner = seenContentHashes[contentHash]
                        if (existingOwner != null && existingOwner != finalUrl) {
                            // Duplicate content under another URL: still persist link graph for PageRank
                            dbHelper.recordLinks(finalUrl, extracted.internalLinks)
                            skippedCount++
                            appendLog(
                                CrawlLogType.SKIPPED,
                                "Duplicate content of $existingOwner — $finalUrl",
                                visitedCount,
                                savedCount,
                                skippedCount,
                                frontier.pendingCount(),
                                finalUrl
                            )
                        } else {
                            dbHelper.savePage(
                                url = finalUrl,
                                title = extracted.title,
                                content = extracted.cleanText,
                                domain = seedDomain,
                                contentHash = contentHash,
                                crawlDepth = item.depth,
                                outboundLinks = extracted.internalLinks
                            )
                            seenContentHashes[contentHash] = finalUrl
                            savedCount++
                            savedSinceLastRank++
                            onDatabaseMutated()
                            appendLog(
                                CrawlLogType.SAVED,
                                "${extracted.title} ($finalUrl)",
                                visitedCount,
                                savedCount,
                                skippedCount,
                                frontier.pendingCount(),
                                finalUrl
                            )

                            // Periodically refresh PageRank during large background crawls
                            if (savedSinceLastRank >= RANK_REFRESH_INTERVAL) {
                                savedSinceLastRank = 0
                                _state.update {
                                    it.copy(
                                        isRanking = true,
                                        statusMessage = "Ranking indexes (PageRank)…"
                                    )
                                }
                                dbHelper.recomputePageRanks()
                                onDatabaseMutated()
                                _state.update {
                                    it.copy(
                                        isRanking = false,
                                        statusMessage = "Priority crawling $seedDomain…"
                                    )
                                }
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (httpErr: HttpStatusException) {
                    skippedCount++
                    appendLog(
                        CrawlLogType.SKIPPED,
                        "HTTP ${httpErr.statusCode} — $url",
                        visitedCount,
                        savedCount,
                        skippedCount,
                        frontier.pendingCount(),
                        url
                    )
                } catch (ioErr: IOException) {
                    skippedCount++
                    appendLog(
                        CrawlLogType.SKIPPED,
                        "Network error (${ioErr.message?.take(60) ?: "timeout"}) — $url",
                        visitedCount,
                        savedCount,
                        skippedCount,
                        frontier.pendingCount(),
                        url
                    )
                } catch (t: Throwable) {
                    skippedCount++
                    appendLog(
                        CrawlLogType.SKIPPED,
                        "Error (${t.message?.take(60) ?: t.javaClass.simpleName}) — $url",
                        visitedCount,
                        savedCount,
                        skippedCount,
                        frontier.pendingCount(),
                        url
                    )
                }
            }
        } catch (ce: CancellationException) {
            wasCancelled = true
            throw ce
        } finally {
            // Always run final PageRank over all saved indexes even when stopped early
            withContext(NonCancellable) {
                var rankedTotal = 0
                if (savedSinceLastRank > 0 || savedCount > 0) {
                    _state.update {
                        it.copy(
                            isRanking = true,
                            statusMessage = "Computing PageRank across all indexes…"
                        )
                    }
                    try {
                        rankedTotal = dbHelper.recomputePageRanks()
                        onDatabaseMutated()
                    } catch (_: Throwable) {
                        // Best-effort ranking on shutdown
                    }
                }

                val rankSuffix = if (rankedTotal > 0) " · Ranked $rankedTotal index(es)" else ""
                val finalMessage = if (wasCancelled) {
                    "Stopped by user ($savedCount page(s) saved$rankSuffix)"
                } else {
                    "Finished: saved $savedCount of $visitedCount visited$rankSuffix"
                }

                _state.update {
                    it.copy(
                        isRunning = false,
                        isRanking = false,
                        visitedCount = visitedCount,
                        savedCount = savedCount,
                        skippedCount = skippedCount,
                        queuedCount = 0,
                        currentUrl = "",
                        statusMessage = finalMessage
                    )
                }
            }
        }
    }

    private suspend fun coroutineContextIsActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun appendLog(
        type: CrawlLogType,
        message: String,
        visited: Int,
        saved: Int,
        skipped: Int,
        queued: Int,
        currentUrl: String
    ) {
        val entry = newLog(type, message)
        _state.update { current ->
            val updatedLogs = (current.logs + entry).let { list ->
                if (list.size > MAX_LOG_ENTRIES) list.takeLast(MAX_LOG_ENTRIES) else list
            }
            current.copy(
                visitedCount = visited,
                savedCount = saved,
                skippedCount = skipped,
                queuedCount = queued,
                currentUrl = currentUrl,
                logs = updatedLogs
            )
        }
    }

    private fun newLog(type: CrawlLogType, message: String): CrawlLogEntry {
        return CrawlLogEntry(
            id = logIdGen.incrementAndGet(),
            type = type,
            message = message
        )
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX_CHARS[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 200
        private const val RANK_REFRESH_INTERVAL = 25
        private const val HEX_CHARS = "0123456789abcdef"
    }
}
