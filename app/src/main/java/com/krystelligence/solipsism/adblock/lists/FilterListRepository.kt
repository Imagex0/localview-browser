package com.krystelligence.solipsism.adblock.lists

import android.app.Application
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The format of a remote filter list.
 */
enum class FilterListKind {
    /** Hosts-format blocklist merged into the bloom hosts engine. */
    HOSTS,

    /** Adblock-Plus-syntax rules merged into the custom network filter engine. */
    ABP
}

/**
 * A user-added remote filter list.
 *
 * @param url HTTP(S) URL of the list.
 * @param kind How the list is parsed and applied.
 * @param enabled Whether the list participates in blocking and updates.
 * @param lastUpdated Epoch millis of the last successful refresh, 0 if never.
 */
data class FilterListEntry(
    val url: String,
    val kind: FilterListKind,
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L
)

/**
 * Durable store for user-added remote filter lists, backed by SharedPreferences.
 */
@Singleton
class FilterListRepository @Inject constructor(
    application: Application
) {

    private val preferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun lists(): List<FilterListEntry> {
        val raw = preferences.getString(KEY_LISTS, null) ?: return emptyList()
        return runCatching { parseEntries(raw) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(url: String, kind: FilterListKind): Boolean {
        val normalized = url.trim()
        if (normalized.isBlank()) return false
        val current = lists()
        if (current.any { it.url.equals(normalized, ignoreCase = true) }) return false
        save(current + FilterListEntry(url = normalized, kind = kind))
        return true
    }

    @Synchronized
    fun remove(url: String): Boolean {
        val current = lists()
        val kept = current.filterNot { it.url.equals(url, ignoreCase = true) }
        if (kept.size == current.size) return false
        save(kept)
        return true
    }

    @Synchronized
    fun setEnabled(url: String, enabled: Boolean): Boolean {
        val current = lists()
        var changed = false
        val updated = current.map {
            if (it.url.equals(url, ignoreCase = true) && it.enabled != enabled) {
                changed = true
                it.copy(enabled = enabled)
            } else {
                it
            }
        }
        if (changed) save(updated)
        return changed
    }

    @Synchronized
    fun updateTimestamp(url: String, timestamp: Long): Boolean {
        val current = lists()
        var changed = false
        val updated = current.map {
            if (it.url.equals(url, ignoreCase = true)) {
                changed = true
                it.copy(lastUpdated = timestamp)
            } else {
                it
            }
        }
        if (changed) save(updated)
        return changed
    }

    @Synchronized
    fun entry(url: String): FilterListEntry? =
        lists().firstOrNull { it.url.equals(url, ignoreCase = true) }

    private fun save(entries: List<FilterListEntry>) {
        // Minimal hand-rolled JSON (org.json is unavailable in unit tests).
        val json = entries.joinToString(separator = ",", prefix = "[", postfix = "]") {
            "{\"u\":\"${escape(it.url)}\",\"k\":\"${it.kind.name}\"," +
                "\"e\":${it.enabled},\"t\":${it.lastUpdated}}"
        }
        preferences.edit().putString(KEY_LISTS, json).apply()
    }

    private fun parseEntries(raw: String): List<FilterListEntry> =
        OBJECT_REGEX.findAll(raw).mapNotNull { match ->
            val body = match.value
            val url = URL_REGEX.find(body)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            val kind = KIND_REGEX.find(body)?.groupValues?.get(1)?.let {
                runCatching { FilterListKind.valueOf(it) }.getOrDefault(FilterListKind.HOSTS)
            } ?: FilterListKind.HOSTS
            val enabled = ENABLED_REGEX.find(body)?.groupValues?.get(1) != "false"
            val updated = UPDATED_REGEX.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            FilterListEntry(url = url, kind = kind, enabled = enabled, lastUpdated = updated)
        }.toList()

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\")

    companion object {
        private const val PREFS_NAME = "filter_lists"
        private const val KEY_LISTS = "lists"

        private val OBJECT_REGEX = Regex("""\{[^{}]*\}""")
        private val URL_REGEX = Regex(""""u":"((?:[^"\\]|\\.)*)"""")
        private val KIND_REGEX = Regex(""""k":"([A-Z]+)"""")
        private val ENABLED_REGEX = Regex(""""e":(true|false)""")
        private val UPDATED_REGEX = Regex(""""t":(\d+)""")
    }
}
