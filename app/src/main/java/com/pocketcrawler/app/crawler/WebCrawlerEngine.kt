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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Polite Breadth-First Search (BFS) offline web crawler running on [Dispatchers.IO].
 *
 * - Sends a custom `User-Agent` header
 * - Restricts crawling to the same domain as the seed URL
 * - Strips `#` fragments and tracks visited URLs to avoid revisits
 * - Waits `politeDelaySeconds` between page fetches
 * - Uses Jsoup to extract `<title>`, internal links, and clean text (without script/style/noscript/header/footer)
 * - Saves clean text directly into Android's SQLite FTS5 `pages` table
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
        val queue = ArrayDeque<String>().apply { addLast(seedUrl) }
        val visited = HashSet<String>()
        val enqueued = HashSet<String>().apply { add(seedUrl) }

        var visitedCount = 0
        var savedCount = 0
        var skippedCount = 0

        _state.value = CrawlState(
            isRunning = true,
            visitedCount = 0,
            savedCount = 0,
            skippedCount = 0,
            maxPages = maxPages,
            currentUrl = seedUrl,
            statusMessage = "Crawling $seedDomain…",
            logs = emptyList()
        )

        try {
            while (coroutineContextIsActive() && queue.isNotEmpty() && visitedCount < maxPages) {
                val url = queue.removeFirst()

                if (url in visited) {
                    skippedCount++
                    appendLog(CrawlLogType.SKIPPED, "Already visited: $url", visitedCount, savedCount, skippedCount, url)
                    continue
                }

                if (!UrlNormalizer.isSameDomain(url, seedDomain)) {
                    skippedCount++
                    appendLog(CrawlLogType.SKIPPED, "External domain: $url", visitedCount, savedCount, skippedCount, url)
                    continue
                }

                // Polite delay between consecutive requests
                if (visitedCount > 0 && delayMs > 0L) {
                    delay(delayMs)
                }

                visited.add(url)
                visitedCount++
                appendLog(CrawlLogType.CRAWLING, url, visitedCount, savedCount, skippedCount, url)

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
                                finalUrl
                            )
                            continue
                        }
                        visited.add(finalUrl)
                    }

                    val extracted = HtmlContentExtractor.extract(
                        html = response.body(),
                        baseUrl = finalUrl,
                        seedDomain = seedDomain
                    )

                    if (extracted.cleanText.isBlank()) {
                        skippedCount++
                        appendLog(
                            CrawlLogType.SKIPPED,
                            "Empty page text — $finalUrl",
                            visitedCount,
                            savedCount,
                            skippedCount,
                            finalUrl
                        )
                    } else {
                        dbHelper.savePage(
                            url = finalUrl,
                            title = extracted.title,
                            content = extracted.cleanText
                        )
                        savedCount++
                        onDatabaseMutated()
                        appendLog(
                            CrawlLogType.SAVED,
                            "${extracted.title} ($finalUrl)",
                            visitedCount,
                            savedCount,
                            skippedCount,
                            finalUrl
                        )
                    }

                    // Enqueue unvisited internal links on the same domain
                    for (link in extracted.internalLinks) {
                        if (link !in visited && link !in enqueued) {
                            enqueued.add(link)
                            queue.addLast(link)
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
                        url
                    )
                }
            }

            _state.update {
                it.copy(
                    isRunning = false,
                    visitedCount = visitedCount,
                    savedCount = savedCount,
                    skippedCount = skippedCount,
                    currentUrl = "",
                    statusMessage = "Finished: saved $savedCount of $visitedCount visited page(s)"
                )
            }
        } catch (ce: CancellationException) {
            _state.update {
                it.copy(
                    isRunning = false,
                    visitedCount = visitedCount,
                    savedCount = savedCount,
                    skippedCount = skippedCount,
                    currentUrl = "",
                    statusMessage = "Stopped by user ($savedCount page(s) saved)"
                )
            }
            throw ce
        }
    }

    private suspend fun coroutineContextIsActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun appendLog(
        type: CrawlLogType,
        message: String,
        visited: Int,
        saved: Int,
        skipped: Int,
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

    companion object {
        private const val MAX_LOG_ENTRIES = 200
    }
}
