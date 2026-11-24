package com.pocketcrawler.app.ranking

import kotlin.math.abs

data class PageRankMetrics(
    val rawPageRank: Double,
    val normalizedScore: Double,
    val inLinks: Int,
    val outLinks: Int
)

/**
 * Iterative PageRank authority calculator over the crawled link graph.
 *
 * Uses power iteration with damping factor [DAMPING_FACTOR] (0.85), proper
 * dangling-node probability mass redistribution, and L1 convergence check.
 * Produces normalized 0..100 authority scores alongside raw PageRank and
 * in-link / out-link counts.
 */
object PageRankCalculator {

    const val DAMPING_FACTOR = 0.85
    const val DEFAULT_MAX_ITERATIONS = 40
    const val CONVERGENCE_TOLERANCE = 1e-6

    fun compute(
        nodes: Collection<String>,
        edges: List<Pair<String, String>>,
        damping: Double = DAMPING_FACTOR,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        tolerance: Double = CONVERGENCE_TOLERANCE
    ): Map<String, PageRankMetrics> {
        val uniqueNodes = nodes.distinct()
        val n = uniqueNodes.size
        if (n == 0) return emptyMap()

        val nodeToIndex = HashMap<String, Int>(n * 2)
        uniqueNodes.forEachIndexed { idx, url -> nodeToIndex[url] = idx }

        val outbound = Array(n) { LinkedHashSet<Int>() }
        val inDegree = IntArray(n)

        for ((from, to) in edges) {
            val fromIdx = nodeToIndex[from] ?: continue
            val toIdx = nodeToIndex[to] ?: continue
            if (fromIdx == toIdx) continue // Ignore self-loops
            if (outbound[fromIdx].add(toIdx)) {
                inDegree[toIdx]++
            }
        }

        val outLists = Array(n) { i -> outbound[i].toIntArray() }

        if (n == 1) {
            return mapOf(
                uniqueNodes[0] to PageRankMetrics(
                    rawPageRank = 1.0,
                    normalizedScore = 100.0,
                    inLinks = 0,
                    outLinks = 0
                )
            )
        }

        var rank = DoubleArray(n) { 1.0 / n.toDouble() }
        var nextRank = DoubleArray(n)
        val baseScore = (1.0 - damping) / n.toDouble()

        for (iter in 0 until maxIterations) {
            var danglingSum = 0.0
            for (i in 0 until n) {
                if (outLists[i].isEmpty()) {
                    danglingSum += rank[i]
                }
            }

            val distributedBase = baseScore + (damping * danglingSum / n.toDouble())
            nextRank.fill(distributedBase)

            for (i in 0 until n) {
                val targets = outLists[i]
                if (targets.isEmpty()) continue
                val contribution = damping * rank[i] / targets.size.toDouble()
                for (targetIdx in targets) {
                    nextRank[targetIdx] += contribution
                }
            }

            var diff = 0.0
            for (i in 0 until n) {
                diff += abs(nextRank[i] - rank[i])
            }

            val swap = rank
            rank = nextRank
            nextRank = swap

            if (diff < tolerance) break
        }

        val maxRaw = rank.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val result = LinkedHashMap<String, PageRankMetrics>(n)
        for (i in 0 until n) {
            val raw = rank[i]
            val normalized = ((raw / maxRaw) * 100.0).coerceIn(0.0, 100.0)
            result[uniqueNodes[i]] = PageRankMetrics(
                rawPageRank = raw,
                normalizedScore = normalized,
                inLinks = inDegree[i],
                outLinks = outLists[i].size
            )
        }
        return result
    }
}
