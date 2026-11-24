package com.pocketcrawler.app.crawler

import java.net.URI
import java.util.PriorityQueue

data class FrontierItem(
    val url: String,
    val depth: Int,
    val priority: Double
)

/**
 * Best-First Priority Frontier (OPIC / Online In-Link Priority + Shallow-Depth Bias).
 *
 * Prioritizes important hub pages (more inbound links discovered so far),
 * shallower crawl depths, and cleaner URLs (fewer path segments / no query string),
 * while preserving deterministic FIFO tie-breaking for equal priorities.
 */
class CrawlFrontier {

    private data class HeapEntry(
        val url: String,
        val depth: Int,
        val priority: Double,
        val sequence: Long
    ) : Comparable<HeapEntry> {
        override fun compareTo(other: HeapEntry): Int {
            // Higher priority comes first; on ties, earlier sequence comes first
            val cmp = other.priority.compareTo(this.priority)
            return if (cmp != 0) cmp else this.sequence.compareTo(other.sequence)
        }
    }

    private val heap = PriorityQueue<HeapEntry>()
    private val visited = HashSet<String>()
    private val queuedUrls = HashSet<String>()
    private val bestDepth = HashMap<String, Int>()
    private val inLinkCounts = HashMap<String, Int>()
    private var seqCounter = 0L

    fun enqueue(url: String, depth: Int, isSeed: Boolean = false): Boolean {
        val currentInLinks = if (isSeed) {
            inLinkCounts[url] ?: 0
        } else {
            val updated = (inLinkCounts[url] ?: 0) + 1
            inLinkCounts[url] = updated
            updated
        }

        if (url in visited) return false

        val prevDepth = bestDepth[url]
        val effectiveDepth = if (prevDepth == null || depth < prevDepth) depth else prevDepth
        bestDepth[url] = effectiveDepth

        val priority = computePriority(
            url = url,
            depth = effectiveDepth,
            inLinkCount = currentInLinks,
            isSeed = isSeed
        )

        val newlyQueued = queuedUrls.add(url)
        // Re-insert into heap when newly discovered or boosted by additional in-links/shallower depth
        heap.add(
            HeapEntry(
                url = url,
                depth = effectiveDepth,
                priority = priority,
                sequence = seqCounter++
            )
        )
        return newlyQueued
    }

    fun pollNext(): FrontierItem? {
        while (heap.isNotEmpty()) {
            val entry = heap.poll() ?: return null
            if (visited.add(entry.url)) {
                queuedUrls.remove(entry.url)
                return FrontierItem(
                    url = entry.url,
                    depth = bestDepth[entry.url] ?: entry.depth,
                    priority = entry.priority
                )
            }
        }
        return null
    }

    fun markVisited(url: String) {
        visited.add(url)
        queuedUrls.remove(url)
    }

    fun isVisited(url: String): Boolean = url in visited

    fun hasPending(): Boolean = queuedUrls.isNotEmpty()

    fun pendingCount(): Int = queuedUrls.size

    fun inLinkCountOf(url: String): Int = inLinkCounts[url] ?: 0

    companion object {
        internal fun computePriority(
            url: String,
            depth: Int,
            inLinkCount: Int,
            isSeed: Boolean
        ): Double {
            if (isSeed) return 1_000_000.0

            var pathSegments = 0
            var hasQuery = false
            try {
                val uri = URI(url)
                val path = uri.rawPath.orEmpty().trim('/')
                pathSegments = if (path.isEmpty()) 0 else path.count { it == '/' } + 1
                hasQuery = !uri.rawQuery.isNullOrEmpty()
            } catch (_: Exception) {
                // Keep defaults
            }

            val inLinkBoost = inLinkCount * 3.0
            val depthPenalty = depth * 4.0
            val pathPenalty = pathSegments * 0.5
            val queryPenalty = if (hasQuery) 1.5 else 0.0

            return 100.0 + inLinkBoost - depthPenalty - pathPenalty - queryPenalty
        }
    }
}
