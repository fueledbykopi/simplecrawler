package com.pocketcrawler.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketcrawler.app.PocketCrawlerApplication
import com.pocketcrawler.app.data.search.FtsQueryBuilder
import com.pocketcrawler.app.model.CrawlConfig
import com.pocketcrawler.app.model.CrawlState
import com.pocketcrawler.app.model.SavedPage
import com.pocketcrawler.app.model.SearchResult
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
    val maxPages: Int = 10,
    val politeDelaySeconds: Float = 1.0f
)

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val recentPages: List<SavedPage> = emptyList(),
    val isSearching: Boolean = false,
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
            app.dbVersion.collect {
                try {
                    val count = dbHelper.getIndexedPageCount()
                    val recent = dbHelper.getRecentPages(limit = 30)
                    _indexedCount.value = count
                    _searchUiState.update { it.copy(recentPages = recent) }
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
                    val matches = dbHelper.searchPages(query)
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
    // Tab 1: Crawler Actions
    // -------------------------------------------------------------------------

    fun updateSeedUrl(url: String) {
        _crawlerForm.update { it.copy(seedUrl = url) }
    }

    fun updateMaxPages(maxPages: Int) {
        _crawlerForm.update {
            it.copy(maxPages = maxPages.coerceIn(CrawlConfig.MIN_PAGES_LIMIT, CrawlConfig.MAX_PAGES_LIMIT))
        }
    }

    fun updatePoliteDelaySeconds(seconds: Float) {
        val rounded = (seconds * 10f).toInt() / 10f
        _crawlerForm.update { it.copy(politeDelaySeconds = rounded.coerceIn(0f, 5f)) }
    }

    fun toggleStartStopCrawl() {
        if (crawlState.value.isRunning) {
            crawlerEngine.stopCrawl()
        } else {
            val form = _crawlerForm.value
            val effectiveSeed = form.seedUrl.trim().ifEmpty { CrawlConfig.DEFAULT_SEED_PLACEHOLDER }
            if (form.seedUrl.isBlank()) {
                _crawlerForm.update { it.copy(seedUrl = effectiveSeed) }
            }
            crawlerEngine.startCrawl(
                CrawlConfig(
                    seedUrl = effectiveSeed,
                    maxPages = form.maxPages,
                    politeDelaySeconds = form.politeDelaySeconds
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Tab 2: Offline Search & Reader View
    // -------------------------------------------------------------------------

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _searchUiState.update { it.copy(query = query) }
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
            crawlerEngine.stopCrawl()
        }
        viewModelScope.launch {
            try {
                dbHelper.clearDatabase()
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
}
