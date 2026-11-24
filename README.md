# Pocket Crawler

**Pocket Crawler** is a native Android offline web crawler and full-text search engine built with **Kotlin**, **Jetpack Compose (Material 3)**, **Kotlin Coroutines**, **Jsoup**, and Android's built-in **SQLite FTS5** (`SQLiteOpenHelper`).

---

## Features

### Tab 1: Crawl (Indexer)
- **Seed URL** input field with default placeholder `https://books.toscrape.com/` (plus a 1-tap demo fill chip).
- **Max Pages** slider (default `10`, range `1–50`) and **Polite Delay** slider in seconds (default `1.0s`, range `0.0s–5.0s`).
- **Start / Stop Crawl** button with live progress bar (`visited / maxPages`).
- Real-time scrolling monospace log console displaying `[CRAWLING]`, `[SAVED]`, and `[SKIPPED]` events.
- Polite crawling logic on `Dispatchers.IO`:
  - Custom `User-Agent` header.
  - Stays within the same domain as the seed URL.
  - Strips `#` URL fragments and tracks visited URLs to avoid revisits.
  - Uses **Jsoup** to fetch HTML, extract `<title>`, queue internal links, strip `<script>`, `<style>`, `<noscript>`, `<header>`, and `<footer>`, and save clean text into SQLite.

### Tab 2: Search (Offline)
- Top search bar querying the local SQLite **FTS5** virtual table (`pages`) completely offline.
- Results ranked by relevance (`ORDER BY rank`) showing **Page Title**, **URL**, and **highlighted snippet** via SQLite's `snippet()` function.
- Tapping any result opens a clean **Reader View** displaying the full saved offline text with an **Open Original URL in Browser** button.

### Database Management
- Displays the live **Total Indexed Pages** count and a **Clear Database** button backed by a confirmation dialog.

---

## SQLite FTS5 Schema

Managed by `PocketCrawlerDbHelper` via Jetpack's `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`, which bundles SQLite with `SQLITE_ENABLE_FTS5` enabled across all Android devices):

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS pages USING fts5(url UNINDEXED, title, content)
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
