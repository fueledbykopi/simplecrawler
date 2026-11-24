package com.pocketcrawler.app.crawler

import java.net.URI
import java.util.Locale

/**
 * Normalizes and filters URLs for polite, same-domain crawling:
 * - Strips `#` fragments
 * - Normalizes scheme/host casing and default ports
 * - Rejects non-HTTP(S) schemes and binary non-HTML assets
 */
object UrlNormalizer {

    private val NON_HTML_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif",
        "pdf", "zip", "gz", "tar", "rar", "7z",
        "mp3", "wav", "ogg", "mp4", "webm", "mov", "avi",
        "woff", "woff2", "ttf", "otf", "eot",
        "css", "js", "mjs", "map", "json", "xml",
        "apk", "exe", "dmg", "iso"
    )

    fun normalizeSeedUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return normalize(withScheme)
    }

    /**
     * Canonicalizes an HTTP/HTTPS URL, stripping any `#` fragment.
     */
    fun normalize(rawUrl: String): String? {
        val withoutFragment = rawUrl.trim().substringBefore('#')
        if (withoutFragment.isEmpty()) return null

        val uri = try {
            URI(withoutFragment)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host.isEmpty() || (!host.contains('.') && host != "localhost")) return null

        val port = uri.port
        val normalizedPort = when {
            port == -1 -> -1
            scheme == "http" && port == 80 -> -1
            scheme == "https" && port == 443 -> -1
            else -> port
        }

        val rawPath = uri.rawPath.orEmpty()
        if (hasBlockedExtension(rawPath)) return null

        val cleanPath = when {
            rawPath.isEmpty() -> "/"
            rawPath.length > 1 && rawPath.endsWith("/") -> rawPath.dropLast(1)
            else -> rawPath
        }

        val query = uri.rawQuery

        return buildString {
            append(scheme).append("://").append(host)
            if (normalizedPort != -1) append(':').append(normalizedPort)
            append(cleanPath)
            if (!query.isNullOrEmpty()) append('?').append(query)
        }
    }

    fun extractHost(url: String): String {
        return try {
            URI(url).host?.lowercase(Locale.ROOT)?.trimEnd('.')?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Returns true if [candidateUrl] is on the same domain as [seedDomain].
     */
    fun isSameDomain(candidateUrl: String, seedDomain: String): Boolean {
        val candidateHost = extractHost(candidateUrl)
        val normalizedSeed = seedDomain.lowercase(Locale.ROOT).removePrefix("www.")
        if (candidateHost.isEmpty() || normalizedSeed.isEmpty()) return false
        return candidateHost == normalizedSeed || candidateHost.endsWith(".$normalizedSeed")
    }

    private fun hasBlockedExtension(path: String): Boolean {
        val lastSegment = path.substringAfterLast('/')
        val dotIndex = lastSegment.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == lastSegment.lastIndex) return false
        val ext = lastSegment.substring(dotIndex + 1).lowercase(Locale.ROOT)
        return ext in NON_HTML_EXTENSIONS
    }
}
