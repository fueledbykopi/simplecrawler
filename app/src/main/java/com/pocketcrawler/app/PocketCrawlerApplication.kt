package com.pocketcrawler.app

import android.app.Application
import com.pocketcrawler.app.crawler.WebCrawlerEngine
import com.pocketcrawler.app.data.db.PocketCrawlerDbHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PocketCrawlerApplication : Application() {

    lateinit var dbHelper: PocketCrawlerDbHelper
        private set

    lateinit var crawlerEngine: WebCrawlerEngine
        private set

    private val _dbVersion = MutableStateFlow(0L)
    val dbVersion: StateFlow<Long> = _dbVersion.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        dbHelper = PocketCrawlerDbHelper(this)
        crawlerEngine = WebCrawlerEngine(
            dbHelper = dbHelper,
            onDatabaseMutated = { notifyDatabaseChanged() }
        )
    }

    fun notifyDatabaseChanged() {
        _dbVersion.value = _dbVersion.value + 1L
    }
}
