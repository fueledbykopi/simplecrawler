package com.pocketcrawler.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketcrawler.app.ui.screens.CrawlerScreen
import com.pocketcrawler.app.ui.screens.ReaderScreen
import com.pocketcrawler.app.ui.screens.SearchScreen
import com.pocketcrawler.app.ui.theme.PocketCrawlerTheme
import com.pocketcrawler.app.viewmodel.AppTab
import com.pocketcrawler.app.viewmodel.PocketCrawlerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketCrawlerApp(viewModel: PocketCrawlerViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val crawlerForm by viewModel.crawlerForm.collectAsStateWithLifecycle()
    val crawlState by viewModel.crawlState.collectAsStateWithLifecycle()
    val indexedCount by viewModel.indexedCount.collectAsStateWithLifecycle()
    val searchState by viewModel.searchUiState.collectAsStateWithLifecycle()
    val readerState by viewModel.readerUiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showClearDbDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    PocketCrawlerTheme {
        val isReaderOpen = readerState.page != null || readerState.isLoading

        if (isReaderOpen) {
            BackHandler { viewModel.closeReader() }
            ReaderScreen(
                readerState = readerState,
                onBack = viewModel::closeReader
            )
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = "Pocket Crawler",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        actions = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "$indexedCount indexed",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { showClearDbDialog = true },
                                    enabled = indexedCount > 0
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteSweep,
                                        contentDescription = "Clear Database",
                                        tint = if (indexedCount > 0) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        },
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            val icon = when (tab) {
                                AppTab.CRAWL -> Icons.Rounded.TravelExplore
                                AppTab.SEARCH -> Icons.Rounded.Search
                            }
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                icon = {
                                    if (tab == AppTab.CRAWL && crawlState.isRunning) {
                                        BadgedBox(
                                            badge = {
                                                Badge {
                                                    Text(crawlState.visitedCount.toString())
                                                }
                                            }
                                        ) {
                                            Icon(icon, contentDescription = tab.label)
                                        }
                                    } else {
                                        Icon(icon, contentDescription = tab.label)
                                    }
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                val contentModifier = Modifier.padding(innerPadding)
                when (selectedTab) {
                    AppTab.CRAWL -> CrawlerScreen(
                        formState = crawlerForm,
                        crawlState = crawlState,
                        indexedCount = indexedCount,
                        onSeedUrlChange = viewModel::updateSeedUrl,
                        onMaxPagesChange = viewModel::updateMaxPages,
                        onMaxPagesInputChange = viewModel::updateMaxPagesInput,
                        onCommitMaxPagesInput = viewModel::commitMaxPagesInput,
                        onPoliteDelayChange = viewModel::updatePoliteDelaySeconds,
                        onToggleStartStop = viewModel::toggleStartStopCrawl,
                        onRequestClearDatabase = { showClearDbDialog = true },
                        modifier = contentModifier
                    )

                    AppTab.SEARCH -> SearchScreen(
                        searchState = searchState,
                        indexedCount = indexedCount,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onOpenPage = viewModel::openPage,
                        onGoToCrawler = { viewModel.selectTab(AppTab.CRAWL) },
                        modifier = contentModifier
                    )
                }
            }
        }

        if (showClearDbDialog) {
            AlertDialog(
                onDismissRequest = { showClearDbDialog = false },
                title = { Text("Clear Database?", fontWeight = FontWeight.Bold) },
                text = {
                    Text("This will permanently delete all $indexedCount indexed page(s) from the offline SQLite FTS5 database.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearDatabase()
                            showClearDbDialog = false
                        }
                    ) {
                        Text("Clear Database", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDbDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
