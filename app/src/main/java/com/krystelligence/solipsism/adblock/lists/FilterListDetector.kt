package com.krystelligence.solipsism.adblock.lists

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Heuristics for spotting filter-list URLs in navigation, e.g. links shared
 * on easylist.to or direct .txt blocklist links.
 */
object FilterListDetector {

    private val ABP_URL_HINTS = listOf("easylist", "easyprivacy", "abp", "adblockplus", "ublock", "adguard")
    private val HOSTS_URL_HINTS = listOf("hosts", "stevenblack", "energized", "oisd", "adaway", "yoyo", "mvps")

    /**
     * Whether a navigated URL is worth offering as a filter-list subscription.
     * Conservative on purpose: only HTTPS .txt links (plus known list hubs).
     */
    fun isCandidate(rawUrl: String): Boolean {
        val url = rawUrl.toHttpUrlOrNull() ?: return false
        if (!url.isHttps) return false
        val host = url.host.lowercase()
        if (host == "easylist.to" || host.endsWith(".easylist.to")) return true
        if (!url.encodedPath.lowercase().endsWith(".txt")) return false
        val full = rawUrl.lowercase()
        return ABP_URL_HINTS.any { it in full } || HOSTS_URL_HINTS.any { it in full }
    }

    /**
     * Best guess of the list format from the URL alone, refined by content
     * sniffing after download.
     */
    fun probableKind(rawUrl: String): FilterListKind {
        val full = rawUrl.lowercase()
        return if (ABP_URL_HINTS.any { it in full }) FilterListKind.ABP else FilterListKind.HOSTS
    }
}
