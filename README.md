# Pocket Crawler

**Pocket Crawler** is a native Android offline web crawler and full-text search engine built with **Kotlin**, **Jetpack Compose (Material 3)**, **Kotlin Coroutines**, **Jsoup**, and Android's built-in **SQLite FTS5** (`SQLiteOpenHelper`).

---

## Features

### Tab 1: Crawl (Background Priority Indexer)
- **Seed URL** input field with default placeholder `https://books.toscrape.com/` (plus a 1-tap demo fill chip).
- **Max Pages** custom numeric input (any positive number) synced with a quick slider (`1–1000`, default `10`) and **Polite Delay** slider in seconds (default `1.0s`, range `0.0s–5.0s`).
- **Background Foreground Service** (`dataSync`) with live notification progress bar and a direct **Stop** action so crawling continues reliably when switching apps or locking the screen.
- **Start / Stop Crawl** button with live progress bar (`visited / maxPages`) and queue counter.
- Real-time scrolling monospace log console displaying `[CRAWLING]`, `[SAVED]`, and `[SKIPPED]` events.
- Best-first priority crawling logic on `Dispatchers.IO`:
  - **Priority Frontier** (online in-link popularity + shallow crawl-depth bias + clean-URL scoring).
  - **SHA-256 content deduplication** while preserving link edges for PageRank.
  - Custom `User-Agent` header, same-domain restriction, `#` fragment stripping, and polite delay.
  - Uses **Jsoup** to fetch HTML, extract `<title>`, queue internal links, strip `<script>`, `<style>`, `<noscript>`, `<header>`, and `<footer>`, and durably persist pages, metadata, and link edges into SQLite WAL.
  - Runs **Iterative PageRank** periodically during large crawls and upon completion/stop.

### Tab 2: Search & Ranked Indexes (Offline)
- Top search bar querying the local SQLite **FTS5** virtual table (`pages`) completely offline.
- **Hybrid BM25 + PageRank Ranking**: combines field-weighted FTS5 `bm25(pages, 0.0, 5.0, 1.0)` (title 5×, content 1×), iterative **PageRank** authority, in-link popularity, and shallow crawl depth.
- Browse **all saved indexes** sorted by **Top Ranked (PageRank)** or **Most Recent**, with paginated loading and a 1-tap **Re-rank** button.
- Tapping any result opens a clean **Reader View** displaying the full saved offline text, PageRank & link metrics, and an **Open Original URL in Browser** button.

### Database Management
- Displays the live **Total Indexed Pages** count and a **Clear Database** button backed by a confirmation dialog.

---

## SQLite FTS5 & PageRank Schema

Managed by `PocketCrawlerDbHelper` via Jetpack's `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`, WAL mode):

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS pages USING fts5(url UNINDEXED, title, content);
CREATE TABLE IF NOT EXISTS page_meta (url TEXT PRIMARY KEY, title TEXT, domain TEXT, content_hash TEXT, word_count INTEGER, crawl_depth INTEGER, in_links INTEGER, out_links INTEGER, page_rank REAL, indexed_at INTEGER);
CREATE TABLE IF NOT EXISTS page_links (from_url TEXT NOT NULL, to_url TEXT NOT NULL, PRIMARY KEY (from_url, to_url));
```

---

## Build & CI

- **Toolchain:** Java 17, Kotlin 2.0, AGP 8.5.2, `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`.
- **GitHub Actions:** [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml) sets up Temurin JDK 17, runs `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`, and uploads the Debug APK artifact.

```bash
chmod +x gradlew
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
# simplecrawler
