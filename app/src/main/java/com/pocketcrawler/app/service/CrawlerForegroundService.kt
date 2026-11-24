package com.pocketcrawler.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pocketcrawler.app.MainActivity
import com.pocketcrawler.app.PocketCrawlerApplication
import com.pocketcrawler.app.model.CrawlConfig
import com.pocketcrawler.app.model.CrawlState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Foreground Service (`dataSync`) that keeps [com.pocketcrawler.app.crawler.WebCrawlerEngine]
 * running reliably in the background even when the user switches apps or locks the screen.
 *
 * Shows a live progress notification with visited/maxPages progress bar and a direct Stop action.
 */
class CrawlerForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PocketCrawlerApplication

        when (intent?.action) {
            ACTION_STOP -> {
                app.crawlerEngine.stopCrawl()
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val seedUrl = intent.getStringExtra(EXTRA_SEED_URL)
                    ?: CrawlConfig.DEFAULT_SEED_PLACEHOLDER
                val maxPages = intent.getIntExtra(EXTRA_MAX_PAGES, CrawlConfig.DEFAULT_MAX_PAGES)
                    .coerceAtLeast(CrawlConfig.MIN_PAGES_LIMIT)
                val politeDelay = intent.getFloatExtra(EXTRA_POLITE_DELAY, 1.0f)

                val initialState = app.crawlerEngine.state.value.let { current ->
                    if (current.isRunning) {
                        current
                    } else {
                        CrawlState(
                            isRunning = true,
                            maxPages = maxPages,
                            currentUrl = seedUrl,
                            statusMessage = "Starting background crawl…"
                        )
                    }
                }

                startInForeground(buildProgressNotification(initialState))
                acquireWakeLock()

                app.crawlerEngine.startCrawl(
                    CrawlConfig(
                        seedUrl = seedUrl,
                        maxPages = maxPages,
                        politeDelaySeconds = politeDelay
                    )
                )

                observeCrawlState(app)
            }

            else -> {
                if (!app.crawlerEngine.state.value.isRunning) {
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun observeCrawlState(app: PocketCrawlerApplication) {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            var seenRunning = false
            var lastNotifyAtMs = 0L

            app.crawlerEngine.state.collect { state ->
                if (state.isRunning) {
                    seenRunning = true
                    val now = System.currentTimeMillis()
                    if (state.isRanking || now - lastNotifyAtMs >= NOTIFY_THROTTLE_MS) {
                        lastNotifyAtMs = now
                        notificationManager.notify(
                            PROGRESS_NOTIFICATION_ID,
                            buildProgressNotification(state)
                        )
                    }
                } else if (seenRunning) {
                    releaseWakeLock()
                    ServiceCompat.stopForeground(
                        this@CrawlerForegroundService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    if (state.statusMessage.isNotBlank()) {
                        notificationManager.notify(
                            SUMMARY_NOTIFICATION_ID,
                            buildCompletionNotification(state)
                        )
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, PROGRESS_NOTIFICATION_ID, notification, fgType)
    }

    private fun buildProgressNotification(state: CrawlState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CrawlerForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (state.isRanking) {
            "Ranking Saved Indexes (PageRank)"
        } else {
            "Crawling in Background (${state.visitedCount}/${state.maxPages})"
        }

        val detailLine = buildString {
            append("Saved: ").append(state.savedCount)
            append(" · Queued: ").append(state.queuedCount)
            if (state.currentUrl.isNotBlank()) {
                append("\n").append(state.currentUrl)
            }
        }

        val maxProgress = state.maxPages.coerceAtLeast(1)
        val currentProgress = state.visitedCount.coerceIn(0, maxProgress)

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText("Saved ${state.savedCount} · Queued ${state.queuedCount}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailLine))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(maxProgress, currentProgress, state.isRanking)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun buildCompletionNotification(state: CrawlState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pocket Crawler Ready")
            .setContentText(state.statusMessage)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_PROGRESS_ID,
            "Background Crawler",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live background web crawling and PageRank indexing progress"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PocketCrawler::BackgroundCrawlWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Throwable) {
            // WakeLock is optional; foreground service still operates without it
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
            // Ignore release errors
        }
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_PROGRESS_ID = "pocket_crawler_background_channel"
        private const val PROGRESS_NOTIFICATION_ID = 1001
        private const val SUMMARY_NOTIFICATION_ID = 1002
        private const val NOTIFY_THROTTLE_MS = 400L
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L // 60 minutes safety cap

        const val ACTION_START = "com.pocketcrawler.app.action.START_CRAWL"
        const val ACTION_STOP = "com.pocketcrawler.app.action.STOP_CRAWL"

        const val EXTRA_SEED_URL = "extra_seed_url"
        const val EXTRA_MAX_PAGES = "extra_max_pages"
        const val EXTRA_POLITE_DELAY = "extra_polite_delay"

        fun startCrawl(context: Context, config: CrawlConfig) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, CrawlerForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SEED_URL, config.seedUrl)
                putExtra(EXTRA_MAX_PAGES, config.maxPages)
                putExtra(EXTRA_POLITE_DELAY, config.politeDelaySeconds)
            }
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (_: Throwable) {
                // Fallback if OS blocks foreground service start
                (appContext as? PocketCrawlerApplication)?.crawlerEngine?.startCrawl(config)
            }
        }

        fun stopCrawl(context: Context) {
            val app = context.applicationContext as? PocketCrawlerApplication
            app?.crawlerEngine?.stopCrawl()
        }
    }
}
