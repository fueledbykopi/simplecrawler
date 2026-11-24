package com.pocketcrawler.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketcrawler.app.PocketCrawlerApplication
import com.pocketcrawler.app.data.search.FtsQueryBuilder
import com.pocketcrawler.app.model.CrawlConfig
import com.pocketcrawler.app.model.CrawlState
import com.pocketcrawler.app.model.IndexSortMode
import com.pocketcrawler.app.model.SavedPage
import com.pocketcrawler.app.model.SearchResult
import com.pocketcrawler.app.service.CrawlerForegroundService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab(val label: String) {
    CRAWL("Crawl"),
    SEARCH("Search")
}

data class CrawlerFormState(
    val seedUrl: String = "",
    val maxPages: Int = CrawlConfig.DEFAULT_MAX_PAGES,
    val maxPagesInput: String = CrawlConfig.DEFAULT_MAX_PAGES.toString(),
    val politeDelaySeconds: Float = 1.0f
)

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val indexedPages: List<SavedPage> = emptyList(),
    val sortMode: IndexSortMode = IndexSortMode.TOP_RANKED,
    val isSearching: Boolean = false,
    val isReRanking: Boolean = false,
    val errorMessage: String? = null
)

data class ReaderUiState(
    val page: SavedPage? = null,
    val highlightTerms: List<String> = emptyList(),
    val isLoading: Boolean = false
)

@OptIn(FlowPreview::class)
class PocketCrawlerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PocketCrawlerApplication
    private val dbHelper = app.dbHelper
    private val crawlerEngine = app.crawlerEngine

    private val _selectedTab = MutableStateFlow(AppTab.CRAWL)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _crawlerForm = MutableStateFlow(CrawlerFormState())
    val crawlerForm: StateFlow<CrawlerFormState> = _crawlerForm.asStateFlow()

    val crawlState: StateFlow<CrawlState> = crawlerEngine.state

    private val _indexedCount = MutableStateFlow(0)
    val indexedCount: StateFlow<Int> = _indexedCount.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _indexSortMode = MutableStateFlow(IndexSortMode.TOP_RANKED)
    private val _indexedPageLimit = MutableStateFlow(PAGE_BATCH_SIZE)

    private val _searchUiState = MutableStateFlow(SearchUiState())
    val searchUiState: StateFlow<SearchUiState> = _searchUiState.asStateFlow()

    private val _readerUiState = MutableStateFlow(ReaderUiState())
    val readerUiState: StateFlow<ReaderUiState> = _readerUiState.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    init {
        observeDatabaseStats()
        observeOfflineSearch()
    }

    private fun observeDatabaseStats() {
        viewModelScope.launch {
            combine(
                app.dbVersion,
                _indexSortMode,
                _indexedPageLimit
            ) { _, sortMode, limit ->
                sortMode to limit
            }.collect { (sortMode, limit) ->
                try {
                    val count = dbHelper.getIndexedPageCount()
                    val pages = dbHelper.getIndexedPages(
                        sortMode = sortMode,
                        limit = limit,
                        offset = 0
                    )
                    _indexedCount.value = count
                    _searchUiState.update {
                        it.copy(
                            indexedPages = pages,
                            sortMode = sortMode
                        )
                    }
                } catch (_: Throwable) {
                    // Ignore transient DB errors
                }
            }
        }
    }

    private fun observeOfflineSearch() {
        viewModelScope.launch {
            combine(
                _searchQuery.debounce(180L),
                app.dbVersion
            ) { query, _ -> query }.collect { query ->
                if (query.isBlank()) {
                    _searchUiState.update {
                        it.copy(results = emptyList(), isSearching = false, errorMessage = null)
                    }
                    return@collect
                }

                _searchUiState.update { it.copy(isSearching = true, errorMessage = null) }
                try {
                    val matches = dbHelper.searchPages(query, limit = SEARCH_RESULT_LIMIT)
                    _searchUiState.update {
                        it.copy(results = matches, isSearching = false, errorMessage = null)
                    }
                } catch (t: Throwable) {
                    _searchUiState.update {
                        it.copy(
                            results = emptyList(),
                            isSearching = false,
                            errorMessage = t.message ?: "FTS5 search error"
                        )
                    }
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
    }

    fun receiveSharedUrl(sharedText: String) {
        val urlRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        val extracted = urlRegex.find(sharedText)?.value ?: sharedText.trim()
        if (extracted.isNotBlank()) {
            _crawlerForm.update { it.copy(seedUrl = extracted) }
            _selectedTab.value = AppTab.CRAWL
            _snackbarMessages.tryEmit("Loaded shared URL into Crawler")
        }
    }

    // -------------------------------------------------------------------------
    // Tab 1: Background Priority Crawler Actions
    // -------------------------------------------------------------------------

    fun updateSeedUrl(url: String) {
        _crawlerForm.update { it.copy(seedUrl = url) }
    }

    fun updateMaxPages(maxPages: Int) {
        val valid = maxPages.coerceAtLeast(CrawlConfig.MIN_PAGES_LIMIT)
        _crawlerForm.update {
            it.copy(
                maxPages = valid,
                maxPagesInput = valid.toString()
            )
        }
    }

    fun updateMaxPagesInput(rawInput: String) {
        val sanitized = CrawlConfig.sanitizeMaxPagesInput(rawInput)
        val parsed = CrawlConfig.parseCustomMaxPages(sanitized)
        _crawlerForm.update { current ->
            current.copy(
                maxPages = parsed ?: current.maxPages,
                maxPagesInput = sanitized
            )
        }
    }

    fun commitMaxPagesInput() {
        _crawlerForm.update { current ->
            val effective = CrawlConfig.parseCustomMaxPages(current.maxPagesInput)
                ?: current.maxPages.coerceAtLeast(CrawlConfig.MIN_PAGES_LIMIT)
            current.copy(
                maxPages = effective,
                maxPagesInput = effective.toString()
            )
        }
    }

    fun updatePoliteDelaySeconds(seconds: Float) {
        val rounded = (seconds * 10f).toInt() / 10f
        _crawlerForm.update { it.copy(politeDelaySeconds = rounded.coerceIn(0f, 5f)) }
    }

    fun toggleStartStopCrawl() {
        if (crawlState.value.isRunning) {
            CrawlerForegroundService.stopCrawl(app)
        } else {
            val form = _crawlerForm.value
            val effectiveSeed = form.seedUrl.trim().ifEmpty { CrawlConfig.DEFAULT_SEED_PLACEHOLDER }
            val effectiveMaxPages = CrawlConfig.parseCustomMaxPages(form.maxPagesInput)
                ?: form.maxPages.coerceAtLeast(CrawlConfig.MIN_PAGES_LIMIT)
            if (form.seedUrl.isBlank() || form.maxPagesInput != effectiveMaxPages.toString()) {
                _crawlerForm.update {
                    it.copy(
                        seedUrl = effectiveSeed,
                        maxPages = effectiveMaxPages,
                        maxPagesInput = effectiveMaxPages.toString()
                    )
                }
            }
            CrawlerForegroundService.startCrawl(
                context = app,
                config = CrawlConfig(
                    seedUrl = effectiveSeed,
                    maxPages = effectiveMaxPages,
                    politeDelaySeconds = form.politeDelaySeconds
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Tab 2: Offline Search, Ranking & Reader View
    // -------------------------------------------------------------------------

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _searchUiState.update { it.copy(query = query) }
    }

    fun selectIndexSortMode(mode: IndexSortMode) {
        if (_indexSortMode.value != mode) {
            _indexSortMode.value = mode
            _indexedPageLimit.value = PAGE_BATCH_SIZE
        }
    }

    fun loadMoreIndexedPages() {
        _indexedPageLimit.update { it + PAGE_BATCH_SIZE }
    }

    fun recomputeRanks() {
        if (_searchUiState.value.isReRanking) return
        viewModelScope.launch {
            _searchUiState.update { it.copy(isReRanking = true) }
            try {
                val ranked = dbHelper.recomputePageRanks()
                app.notifyDatabaseChanged()
                _snackbarMessages.tryEmit("Ranked $ranked saved index(es) with PageRank")
            } catch (t: Throwable) {
                _snackbarMessages.tryEmit("Ranking failed: ${t.message ?: "unknown error"}")
            } finally {
                _searchUiState.update { it.copy(isReRanking = false) }
            }
        }
    }

    fun openPage(rowId: Long, fromQuery: String = "") {
        val terms = if (fromQuery.isNotBlank()) {
            FtsQueryBuilder.extractHighlightTerms(fromQuery)
        } else {
            emptyList()
        }
        _readerUiState.value = ReaderUiState(page = null, highlightTerms = terms, isLoading = true)
        viewModelScope.launch {
            val loaded = try {
                dbHelper.getPageByRowId(rowId)
            } catch (_: Throwable) {
                null
            }
            _readerUiState.update { it.copy(page = loaded, isLoading = false) }
            if (loaded == null) {
                _snackbarMessages.tryEmit("Page not found in offline database")
            }
        }
    }

    fun closeReader() {
        _readerUiState.value = ReaderUiState()
    }

    // -------------------------------------------------------------------------
    // Database Management
    // -------------------------------------------------------------------------

    fun clearDatabase() {
        if (crawlState.value.isRunning) {
            CrawlerForegroundService.stopCrawl(app)
        }
        viewModelScope.launch {
            try {
                dbHelper.clearDatabase()
                _indexedPageLimit.value = PAGE_BATCH_SIZE
                app.notifyDatabaseChanged()
                if (_readerUiState.value.page != null) {
                    closeReader()
                }
                _snackbarMessages.tryEmit("Offline database cleared")
            } catch (t: Throwable) {
                _snackbarMessages.tryEmit("Failed to clear database: ${t.message ?: "unknown error"}")
            }
        }
    }

    companion object {
        private const val PAGE_BATCH_SIZE = 100
        private const val SEARCH_RESULT_LIMIT = 100
    }
}
