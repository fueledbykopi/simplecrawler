package com.pocketcrawler.app.data.db

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.pocketcrawler.app.data.search.FtsQueryBuilder
import com.pocketcrawler.app.model.IndexSortMode
import com.pocketcrawler.app.model.SavedPage
import com.pocketcrawler.app.model.SearchResult
import com.pocketcrawler.app.ranking.PageRankCalculator
import com.pocketcrawler.app.ranking.SearchRanker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local SQLite FTS5 + PageRank storage and full-text search engine backed by Jetpack's
 * [BundledSQLiteDriver] (`androidx.sqlite:sqlite-bundled`).
 *
 * Persists:
 * - `pages`: FTS5 virtual table `(url UNINDEXED, title, content)` for offline full-text search & BM25
 * - `page_meta`: Durable index metadata (`page_rank`, `in_links`, `out_links`, `crawl_depth`, `word_count`, `content_hash`, `indexed_at`)
 * - `page_links`: Persistent directed internal link graph `(from_url, to_url)` used for iterative PageRank
 */
class PocketCrawlerDbHelper(context: Context) {

    private val dbFile: File = context.applicationContext
        .getDatabasePath(DATABASE_NAME)
        .also { it.parentFile?.mkdirs() }

    private val driver = BundledSQLiteDriver()
    private val lock = Mutex()
    private var connection: SQLiteConnection? = null

    private inline fun <T> SQLiteStatement.useStmt(block: (SQLiteStatement) -> T): T {
        return try {
            block(this)
        } finally {
            close()
        }
    }

    private suspend fun <T> withDb(block: (SQLiteConnection) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val conn = ensureOpen()
            block(conn)
        }
    }

    private fun ensureOpen(): SQLiteConnection {
        connection?.let { return it }
        dbFile.parentFile?.mkdirs()
        val conn = driver.open(dbFile.absolutePath)
        conn.execSQL("PRAGMA journal_mode = WAL;")
        conn.execSQL("PRAGMA synchronous = NORMAL;")
        conn.execSQL("PRAGMA busy_timeout = 5000;")
        conn.execSQL(CREATE_PAGES_FTS5_TABLE)
        conn.execSQL(CREATE_PAGE_META_TABLE)
        conn.execSQL(CREATE_PAGE_LINKS_TABLE)
        conn.execSQL(CREATE_PAGE_META_RANK_INDEX)
        conn.execSQL(CREATE_PAGE_META_RECENT_INDEX)
        conn.execSQL(CREATE_PAGE_LINKS_TO_INDEX)
        backfillMissingMetadata(conn)
        connection = conn
        return conn
    }

    /**
     * Ensures any rows indexed by older app versions (before `page_meta` existed)
     * are preserved and visible in ranked listings.
     */
    private fun backfillMissingMetadata(conn: SQLiteConnection) {
        conn.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_PAGE_META ($COL_URL, $COL_TITLE, $COL_DOMAIN, $COL_INDEXED_AT)
            SELECT $COL_URL, $COL_TITLE, '', CAST(strftime('%s', 'now') AS INTEGER) * 1000
            FROM $TABLE_PAGES
            """.trimIndent()
        )
    }

    /**
     * Atomically saves/updates an indexed page in `pages` (FTS5), its metadata in `page_meta`,
     * and its outbound internal links in `page_links`.
     */
    suspend fun savePage(
        url: String,
        title: String,
        content: String,
        domain: String = "",
        contentHash: String = "",
        crawlDepth: Int = 0,
        outboundLinks: List<String> = emptyList()
    ) = withDb { conn ->
        val now = System.currentTimeMillis()
        val wordCount = countWords(content)
        val distinctLinks = outboundLinks.asSequence().filter { it != url }.distinct().toList()

        conn.execSQL("BEGIN IMMEDIATE;")
        try {
            // 1. Upsert into FTS5 `pages` table
            conn.prepare("DELETE FROM $TABLE_PAGES WHERE $COL_URL = ?").useStmt { stmt ->
                stmt.bindText(1, url)
                stmt.step()
            }
            conn.prepare(
                "INSERT INTO $TABLE_PAGES ($COL_URL, $COL_TITLE, $COL_CONTENT) VALUES (?, ?, ?)"
            ).useStmt { stmt ->
                stmt.bindText(1, url)
                stmt.bindText(2, title)
                stmt.bindText(3, content)
                stmt.step()
            }

            // 2. Upsert into persistent `page_meta` table (preserving existing rank until recomputed)
            conn.prepare(
                """
                INSERT INTO $TABLE_PAGE_META (
                    $COL_URL, $COL_TITLE, $COL_DOMAIN, $COL_CONTENT_HASH,
                    $COL_WORD_COUNT, $COL_CRAWL_DEPTH, $COL_OUT_LINKS, $COL_INDEXED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT($COL_URL) DO UPDATE SET
                    $COL_TITLE = excluded.$COL_TITLE,
                    $COL_DOMAIN = excluded.$COL_DOMAIN,
                    $COL_CONTENT_HASH = excluded.$COL_CONTENT_HASH,
                    $COL_WORD_COUNT = excluded.$COL_WORD_COUNT,
                    $COL_CRAWL_DEPTH = MIN($TABLE_PAGE_META.$COL_CRAWL_DEPTH, excluded.$COL_CRAWL_DEPTH),
                    $COL_OUT_LINKS = excluded.$COL_OUT_LINKS,
                    $COL_INDEXED_AT = excluded.$COL_INDEXED_AT
                """.trimIndent()
            ).useStmt { stmt ->
                stmt.bindText(1, url)
                stmt.bindText(2, title)
                stmt.bindText(3, domain)
                stmt.bindText(4, contentHash)
                stmt.bindLong(5, wordCount.toLong())
                stmt.bindLong(6, crawlDepth.toLong())
                stmt.bindLong(7, distinctLinks.size.toLong())
                stmt.bindLong(8, now)
                stmt.step()
            }

            // 3. Replace outbound edges in `page_links`
            conn.prepare("DELETE FROM $TABLE_PAGE_LINKS WHERE $COL_FROM_URL = ?").useStmt { stmt ->
                stmt.bindText(1, url)
                stmt.step()
            }
            if (distinctLinks.isNotEmpty()) {
                conn.prepare(
                    "INSERT OR IGNORE INTO $TABLE_PAGE_LINKS ($COL_FROM_URL, $COL_TO_URL) VALUES (?, ?)"
                ).useStmt { stmt ->
                    for (targetUrl in distinctLinks) {
                        stmt.reset()
                        stmt.clearBindings()
                        stmt.bindText(1, url)
                        stmt.bindText(2, targetUrl)
                        stmt.step()
                    }
                }
            }

            conn.execSQL("COMMIT;")
        } catch (t: Throwable) {
            conn.execSQL("ROLLBACK;")
            throw t
        }
    }

    /**
     * Records outbound links for a skipped/duplicate page without overwriting content.
     */
    suspend fun recordLinks(fromUrl: String, outboundLinks: List<String>) = withDb { conn ->
        val distinctLinks = outboundLinks.asSequence().filter { it != fromUrl }.distinct().toList()
        if (distinctLinks.isEmpty()) return@withDb
        conn.execSQL("BEGIN IMMEDIATE;")
        try {
            conn.prepare(
                "INSERT OR IGNORE INTO $TABLE_PAGE_LINKS ($COL_FROM_URL, $COL_TO_URL) VALUES (?, ?)"
            ).useStmt { stmt ->
                for (targetUrl in distinctLinks) {
                    stmt.reset()
                    stmt.clearBindings()
                    stmt.bindText(1, fromUrl)
                    stmt.bindText(2, targetUrl)
                    stmt.step()
                }
            }
            conn.execSQL("COMMIT;")
        } catch (t: Throwable) {
            conn.execSQL("ROLLBACK;")
            throw t
        }
    }

    /**
     * Returns a map of existing `contentHash -> url` for deduplication across crawls.
     */
    suspend fun getExistingContentHashes(): Map<String, String> = withDb { conn ->
        val map = HashMap<String, String>()
        conn.prepare(
            "SELECT $COL_CONTENT_HASH, $COL_URL FROM $TABLE_PAGE_META WHERE $COL_CONTENT_HASH != ''"
        ).useStmt { stmt ->
            while (stmt.step()) {
                val hash = if (stmt.isNull(0)) "" else stmt.getText(0)
                val url = if (stmt.isNull(1)) "" else stmt.getText(1)
                if (hash.isNotEmpty() && url.isNotEmpty()) {
                    map[hash] = url
                }
            }
        }
        map
    }

    /**
     * Runs iterative PageRank over the entire persisted link graph and updates
     * `page_rank`, `in_links`, and `out_links` for all indexed pages.
     *
     * @return Number of indexed pages ranked.
     */
    suspend fun recomputePageRanks(): Int = withDb { conn ->
        val nodes = mutableListOf<String>()
        conn.prepare("SELECT $COL_URL FROM $TABLE_PAGE_META").useStmt { stmt ->
            while (stmt.step()) {
                if (!stmt.isNull(0)) nodes.add(stmt.getText(0))
            }
        }
        if (nodes.isEmpty()) return@withDb 0

        val edges = mutableListOf<Pair<String, String>>()
        conn.prepare("SELECT $COL_FROM_URL, $COL_TO_URL FROM $TABLE_PAGE_LINKS").useStmt { stmt ->
            while (stmt.step()) {
                val from = if (stmt.isNull(0)) "" else stmt.getText(0)
                val to = if (stmt.isNull(1)) "" else stmt.getText(1)
                if (from.isNotEmpty() && to.isNotEmpty()) {
                    edges.add(from to to)
                }
            }
        }

        val metrics = PageRankCalculator.compute(nodes = nodes, edges = edges)

        conn.execSQL("BEGIN IMMEDIATE;")
        try {
            conn.prepare(
                """
                UPDATE $TABLE_PAGE_META
                SET $COL_PAGE_RANK = ?, $COL_IN_LINKS = ?, $COL_OUT_LINKS = ?
                WHERE $COL_URL = ?
                """.trimIndent()
            ).useStmt { stmt ->
                for ((url, m) in metrics) {
                    stmt.reset()
                    stmt.clearBindings()
                    stmt.bindDouble(1, m.normalizedScore)
                    stmt.bindLong(2, m.inLinks.toLong())
                    stmt.bindLong(3, m.outLinks.toLong())
                    stmt.bindText(4, url)
                    stmt.step()
                }
            }
            conn.execSQL("COMMIT;")
        } catch (t: Throwable) {
            conn.execSQL("ROLLBACK;")
            throw t
        }
        metrics.size
    }

    /**
     * Queries the local SQLite FTS5 `pages` table completely offline, using field-weighted
     * BM25 (title 5x, content 1x) fused with persistent PageRank authority, in-link count,
     * and shallow crawl-depth signals via [SearchRanker].
     */
    suspend fun searchPages(rawQuery: String, limit: Int = 100): List<SearchResult> = withDb { conn ->
        val ftsQuery = FtsQueryBuilder.build(rawQuery) ?: return@withDb emptyList()
        val candidateLimit = (limit * 4).coerceAtLeast(200)

        val sql = """
            SELECT
                $TABLE_PAGES.rowid,
                $TABLE_PAGES.$COL_URL,
                $TABLE_PAGES.$COL_TITLE,
                snippet($TABLE_PAGES, 2, '${FtsQueryBuilder.HIGHLIGHT_START}', '${FtsQueryBuilder.HIGHLIGHT_END}', '…', 24) AS body_snippet,
                snippet($TABLE_PAGES, 1, '${FtsQueryBuilder.HIGHLIGHT_START}', '${FtsQueryBuilder.HIGHLIGHT_END}', '…', 16) AS title_snippet,
                substr($TABLE_PAGES.$COL_CONTENT, 1, 200) AS preview_text,
                bm25($TABLE_PAGES, 0.0, 5.0, 1.0) AS bm25_score,
                COALESCE(m.$COL_PAGE_RANK, 0.0) AS page_rank,
                COALESCE(m.$COL_IN_LINKS, 0) AS in_links,
                COALESCE(m.$COL_CRAWL_DEPTH, 0) AS crawl_depth
            FROM $TABLE_PAGES
            LEFT JOIN $TABLE_PAGE_META m ON m.$COL_URL = $TABLE_PAGES.$COL_URL
            WHERE $TABLE_PAGES MATCH ?
            ORDER BY bm25($TABLE_PAGES, 0.0, 5.0, 1.0)
            LIMIT ?
        """.trimIndent()

        val candidates = mutableListOf<SearchResult>()
        conn.prepare(sql).useStmt { stmt ->
            stmt.bindText(1, ftsQuery)
            stmt.bindLong(2, candidateLimit.toLong())

            while (stmt.step()) {
                val rowId = stmt.getLong(0)
                val url = if (stmt.isNull(1)) "" else stmt.getText(1)
                val title = if (stmt.isNull(2)) "" else stmt.getText(2)
                val bodySnippet = if (stmt.isNull(3)) "" else stmt.getText(3)
                val titleSnippet = if (stmt.isNull(4)) "" else stmt.getText(4)
                val previewText = if (stmt.isNull(5)) "" else stmt.getText(5)
                val bm25Score = stmt.getDouble(6)
                val pageRank = stmt.getDouble(7)
                val inLinks = stmt.getLong(8).toInt()
                val crawlDepth = stmt.getLong(9).toInt()

                val bestSnippet = when {
                    bodySnippet.contains(FtsQueryBuilder.HIGHLIGHT_START) -> bodySnippet
                    titleSnippet.contains(FtsQueryBuilder.HIGHLIGHT_START) -> {
                        if (previewText.isNotBlank()) "$titleSnippet — $previewText" else titleSnippet
                    }
                    bodySnippet.isNotBlank() -> bodySnippet
                    else -> previewText
                }

                val compositeScore = SearchRanker.computeCompositeScore(
                    rawFts5Bm25 = bm25Score,
                    normalizedPageRank = pageRank,
                    inLinks = inLinks,
                    crawlDepth = crawlDepth
                )

                candidates.add(
                    SearchResult(
                        rowId = rowId,
                        url = url,
                        title = title,
                        highlightedSnippet = bestSnippet,
                        rank = compositeScore,
                        bm25Score = bm25Score,
                        pageRank = pageRank,
                        inLinks = inLinks,
                        crawlDepth = crawlDepth
                    )
                )
            }
        }

        candidates
            .sortedWith(
                compareByDescending<SearchResult> { it.rank }
                    .thenByDescending { it.pageRank }
                    .thenBy { it.bm25Score }
            )
            .take(limit)
    }

    /**
     * Loads the full offline text and ranking metadata of an indexed page for the Reader View.
     */
    suspend fun getPageByRowId(rowId: Long): SavedPage? = withDb { conn ->
        val sql = """
            SELECT
                p.rowid,
                p.$COL_URL,
                p.$COL_TITLE,
                p.$COL_CONTENT,
                COALESCE(m.$COL_PAGE_RANK, 0.0),
                COALESCE(m.$COL_IN_LINKS, 0),
                COALESCE(m.$COL_OUT_LINKS, 0),
                COALESCE(m.$COL_CRAWL_DEPTH, 0),
                COALESCE(m.$COL_WORD_COUNT, 0),
                COALESCE(m.$COL_INDEXED_AT, 0)
            FROM $TABLE_PAGES p
            LEFT JOIN $TABLE_PAGE_META m ON m.$COL_URL = p.$COL_URL
            WHERE p.rowid = ?
        """.trimIndent()
        conn.prepare(sql).useStmt { stmt ->
            stmt.bindLong(1, rowId)
            if (stmt.step()) {
                SavedPage(
                    rowId = stmt.getLong(0),
                    url = if (stmt.isNull(1)) "" else stmt.getText(1),
                    title = if (stmt.isNull(2)) "" else stmt.getText(2),
                    content = if (stmt.isNull(3)) "" else stmt.getText(3),
                    pageRank = stmt.getDouble(4),
                    inLinks = stmt.getLong(5).toInt(),
                    outLinks = stmt.getLong(6).toInt(),
                    crawlDepth = stmt.getLong(7).toInt(),
                    wordCount = stmt.getLong(8).toInt(),
                    indexedAt = stmt.getLong(9)
                )
            } else {
                null
            }
        }
    }

    /**
     * Lists indexed pages ordered either by PageRank authority ([IndexSortMode.TOP_RANKED])
     * or by recency ([IndexSortMode.MOST_RECENT]), with pagination support so all saved
     * indexes can be browsed cleanly without loading full bodies into memory.
     */
    suspend fun getIndexedPages(
        sortMode: IndexSortMode = IndexSortMode.TOP_RANKED,
        limit: Int = 100,
        offset: Int = 0
    ): List<SavedPage> = withDb { conn ->
        val orderClause = when (sortMode) {
            IndexSortMode.TOP_RANKED ->
                "COALESCE(m.$COL_PAGE_RANK, 0.0) DESC, COALESCE(m.$COL_IN_LINKS, 0) DESC, p.rowid DESC"
            IndexSortMode.MOST_RECENT ->
                "COALESCE(m.$COL_INDEXED_AT, 0) DESC, p.rowid DESC"
        }

        val sql = """
            SELECT
                p.rowid,
                p.$COL_URL,
                p.$COL_TITLE,
                substr(p.$COL_CONTENT, 1, 220),
                COALESCE(m.$COL_PAGE_RANK, 0.0),
                COALESCE(m.$COL_IN_LINKS, 0),
                COALESCE(m.$COL_OUT_LINKS, 0),
                COALESCE(m.$COL_CRAWL_DEPTH, 0),
                COALESCE(m.$COL_WORD_COUNT, 0),
                COALESCE(m.$COL_INDEXED_AT, 0)
            FROM $TABLE_PAGES p
            LEFT JOIN $TABLE_PAGE_META m ON m.$COL_URL = p.$COL_URL
            ORDER BY $orderClause
            LIMIT ? OFFSET ?
        """.trimIndent()

        val pages = mutableListOf<SavedPage>()
        conn.prepare(sql).useStmt { stmt ->
            stmt.bindLong(1, limit.toLong())
            stmt.bindLong(2, offset.toLong())
            while (stmt.step()) {
                pages.add(
                    SavedPage(
                        rowId = stmt.getLong(0),
                        url = if (stmt.isNull(1)) "" else stmt.getText(1),
                        title = if (stmt.isNull(2)) "" else stmt.getText(2),
                        content = if (stmt.isNull(3)) "" else stmt.getText(3),
                        pageRank = stmt.getDouble(4),
                        inLinks = stmt.getLong(5).toInt(),
                        outLinks = stmt.getLong(6).toInt(),
                        crawlDepth = stmt.getLong(7).toInt(),
                        wordCount = stmt.getLong(8).toInt(),
                        indexedAt = stmt.getLong(9)
                    )
                )
            }
        }
        pages
    }

    /**
     * Backward-compatible convenience wrapper for recent pages.
     */
    suspend fun getRecentPages(limit: Int = 100): List<SavedPage> {
        return getIndexedPages(sortMode = IndexSortMode.MOST_RECENT, limit = limit, offset = 0)
    }

    /**
     * Returns total number of indexed pages in the FTS5 table.
     */
    suspend fun getIndexedPageCount(): Int = withDb { conn ->
        conn.prepare("SELECT COUNT(*) FROM $TABLE_PAGES").useStmt { stmt ->
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }
    }

    /**
     * Deletes all rows from `pages`, `page_meta`, and `page_links` and compacts storage.
     */
    suspend fun clearDatabase() = withDb { conn ->
        conn.execSQL("BEGIN IMMEDIATE;")
        try {
            conn.execSQL("DELETE FROM $TABLE_PAGES;")
            conn.execSQL("DELETE FROM $TABLE_PAGE_META;")
            conn.execSQL("DELETE FROM $TABLE_PAGE_LINKS;")
            conn.execSQL("COMMIT;")
        } catch (t: Throwable) {
            conn.execSQL("ROLLBACK;")
            throw t
        }
        conn.execSQL("VACUUM;")
    }

    private fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                count++
                inWord = true
            }
        }
        return count
    }

    companion object {
        const val DATABASE_NAME = "pocket_crawler.db"

        const val TABLE_PAGES = "pages"
        const val TABLE_PAGE_META = "page_meta"
        const val TABLE_PAGE_LINKS = "page_links"

        const val COL_URL = "url"
        const val COL_TITLE = "title"
        const val COL_CONTENT = "content"
        const val COL_DOMAIN = "domain"
        const val COL_CONTENT_HASH = "content_hash"
        const val COL_WORD_COUNT = "word_count"
        const val COL_CRAWL_DEPTH = "crawl_depth"
        const val COL_IN_LINKS = "in_links"
        const val COL_OUT_LINKS = "out_links"
        const val COL_PAGE_RANK = "page_rank"
        const val COL_INDEXED_AT = "indexed_at"
        const val COL_FROM_URL = "from_url"
        const val COL_TO_URL = "to_url"

        const val CREATE_PAGES_FTS5_TABLE =
            "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_PAGES USING fts5($COL_URL UNINDEXED, $COL_TITLE, $COL_CONTENT)"

        const val CREATE_PAGE_META_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_PAGE_META (
                $COL_URL TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_DOMAIN TEXT NOT NULL DEFAULT '',
                $COL_CONTENT_HASH TEXT NOT NULL DEFAULT '',
                $COL_WORD_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_CRAWL_DEPTH INTEGER NOT NULL DEFAULT 0,
                $COL_IN_LINKS INTEGER NOT NULL DEFAULT 0,
                $COL_OUT_LINKS INTEGER NOT NULL DEFAULT 0,
                $COL_PAGE_RANK REAL NOT NULL DEFAULT 0.0,
                $COL_INDEXED_AT INTEGER NOT NULL DEFAULT 0
            )
        """

        const val CREATE_PAGE_LINKS_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_PAGE_LINKS (
                $COL_FROM_URL TEXT NOT NULL,
                $COL_TO_URL TEXT NOT NULL,
                PRIMARY KEY ($COL_FROM_URL, $COL_TO_URL)
            )
        """

        const val CREATE_PAGE_META_RANK_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_page_meta_rank ON $TABLE_PAGE_META($COL_PAGE_RANK DESC, $COL_IN_LINKS DESC)"

        const val CREATE_PAGE_META_RECENT_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_page_meta_recent ON $TABLE_PAGE_META($COL_INDEXED_AT DESC)"

        const val CREATE_PAGE_LINKS_TO_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_page_links_to ON $TABLE_PAGE_LINKS($COL_TO_URL)"
    }
}
