package com.krystelligence.solipsism.adblock.lists

import com.krystelligence.solipsism.adblock.BloomFilterAdBlocker
import com.krystelligence.solipsism.adblock.custom.CustomFilterRepository
import com.krystelligence.solipsism.adblock.custom.CustomFilterSource
import com.krystelligence.solipsism.browser.di.HostsClient
import com.krystelligence.solipsism.concurrency.CoroutineDispatchers
import com.krystelligence.solipsism.log.Logger
import android.app.Application
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of adding a remote filter list from a URL.
 */
sealed class AddListResult {
    data class Added(val kind: FilterListKind) : AddListResult()
    data object Duplicate : AddListResult()
    data object InvalidUrl : AddListResult()
    data object DownloadFailed : AddListResult()
    data object UnrecognizedFormat : AddListResult()
}

/**
 * Orchestrates user-added remote filter lists: downloading, format sniffing,
 * ingestion into the hosts and ABP engines, refresh, and removal.
 *
 * HOSTS lists merge into the bloom hosts engine on the next populate.
 * ABP lists merge into the custom network filter engine immediately and are
 * rehydrated from disk cache at startup.
 */
@Singleton
class RemoteFilterListManager @Inject constructor(
    private val application: Application,
    private val repository: FilterListRepository,
    private val customFilterRepository: CustomFilterRepository,
    private val bloomFilterAdBlocker: BloomFilterAdBlocker,
    @HostsClient private val okHttpClient: Single<OkHttpClient>,
    private val appCoroutineScope: CoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val logger: Logger
) {

    init {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            hydrateAbpCaches()
        }
    }

    fun lists(): List<FilterListEntry> = repository.lists()

    fun addList(url: String, onDone: (AddListResult) -> Unit) {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            val result = addListSuspend(url.trim())
            withContext(coroutineDispatchers.main) { onDone(result) }
        }
    }

    fun refreshList(url: String, onDone: (Boolean) -> Unit) {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            val entry = repository.entry(url) ?: run {
                withContext(coroutineDispatchers.main) { onDone(false) }
                return@launch
            }
            val ok = refreshSuspend(entry)
            withContext(coroutineDispatchers.main) { onDone(ok) }
        }
    }

    fun refreshAll(onDone: (succeeded: Int, failed: Int) -> Unit) {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            var succeeded = 0
            var failed = 0
            repository.lists().filter { it.enabled }.forEach {
                if (refreshSuspend(it)) succeeded++ else failed++
            }
            if (succeeded > 0) bloomFilterAdBlocker.populateAdBlockerFromDataSource(true)
            withContext(coroutineDispatchers.main) { onDone(succeeded, failed) }
        }
    }

    fun removeList(url: String, onDone: () -> Unit) {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            val entry = repository.entry(url)
            if (entry?.kind == FilterListKind.ABP) {
                customFilterRepository.removeLines(abpCacheFile(url).readLinesSafe())
                abpCacheFile(url).delete()
            }
            repository.remove(url)
            bloomFilterAdBlocker.populateAdBlockerFromDataSource(true)
            withContext(coroutineDispatchers.main) { onDone() }
        }
    }

    fun setListEnabled(url: String, enabled: Boolean, onDone: () -> Unit) {
        appCoroutineScope.launch(coroutineDispatchers.io) {
            val entry = repository.entry(url)
            repository.setEnabled(url, enabled)
            if (entry?.kind == FilterListKind.ABP) {
                customFilterRepository.setEnabledForLines(abpCacheFile(url).readLinesSafe(), enabled)
            }
            bloomFilterAdBlocker.populateAdBlockerFromDataSource(true)
            withContext(coroutineDispatchers.main) { onDone() }
        }
    }

    private suspend fun addListSuspend(url: String): AddListResult {
        val httpUrl = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return AddListResult.InvalidUrl
        if (repository.entry(httpUrl.toString()) != null) return AddListResult.Duplicate

        val text = downloadText(httpUrl.toString()) ?: return AddListResult.DownloadFailed
        val kind = sniffKind(text) ?: return AddListResult.UnrecognizedFormat
        val canonical = httpUrl.toString()

        return when (kind) {
            FilterListKind.HOSTS -> {
                repository.add(canonical, FilterListKind.HOSTS)
                bloomFilterAdBlocker.populateAdBlockerFromDataSource(true)
                AddListResult.Added(FilterListKind.HOSTS)
            }
            FilterListKind.ABP -> {
                val lines = text.lines().filter { it.isNotBlank() }
                val errors = customFilterRepository.addAll(lines, CustomFilterSource.IMPORTED)
                val valid = lines.size - errors.size
                if (valid < MIN_ABP_RULES) {
                    customFilterRepository.removeLines(lines)
                    return AddListResult.UnrecognizedFormat
                }
                writeAbpCache(canonical, lines)
                repository.add(canonical, FilterListKind.ABP)
                repository.updateTimestamp(canonical, System.currentTimeMillis())
                AddListResult.Added(FilterListKind.ABP)
            }
        }
    }

    private suspend fun refreshSuspend(entry: FilterListEntry): Boolean {
        val text = downloadText(entry.url) ?: return false
        return when (entry.kind) {
            FilterListKind.HOSTS -> {
                if (sniffKind(text) == null) return false
                repository.updateTimestamp(entry.url, System.currentTimeMillis())
                bloomFilterAdBlocker.populateAdBlockerFromDataSource(true)
                true
            }
            FilterListKind.ABP -> {
                val lines = text.lines().filter { it.isNotBlank() }
                customFilterRepository.removeLines(abpCacheFile(entry.url).readLinesSafe())
                val errors = customFilterRepository.addAll(lines, CustomFilterSource.IMPORTED)
                if (lines.size - errors.size < MIN_ABP_RULES) return false
                if (!entry.enabled) customFilterRepository.setEnabledForLines(lines, false)
                writeAbpCache(entry.url, lines)
                repository.updateTimestamp(entry.url, System.currentTimeMillis())
                true
            }
        }
    }

    private suspend fun hydrateAbpCaches() {
        repository.lists()
            .filter { it.enabled && it.kind == FilterListKind.ABP }
            .forEach { entry ->
                val lines = abpCacheFile(entry.url).readLinesSafe()
                if (lines.isNotEmpty()) {
                    runCatching {
                        customFilterRepository.addAll(lines, CustomFilterSource.IMPORTED)
                    }.onFailure { logger.log(TAG, "Unable to hydrate ${entry.url}", it) }
                }
            }
    }

    private fun downloadText(url: String): String? {
        return try {
            val client = okHttpClient.blockingGet()
            val request = Request.Builder()
                .url(url)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val buffer = Buffer()
                val source = body.source()
                while (true) {
                    if (buffer.size > MAX_LIST_BYTES) return null
                    if (source.read(buffer, CHUNK_BYTES) == -1L) break
                }
                buffer.readUtf8()
            }
        } catch (e: Exception) {
            logger.log(TAG, "Filter list download failed for $url", e)
            null
        }
    }

    private fun sniffKind(text: String): FilterListKind? {
        var hosts = 0
        var abp = 0
        var examined = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (++examined > SNIFF_LINES) break
            when {
                line.startsWith("||") || line.startsWith("@@") || line.startsWith("##") ||
                    line.startsWith("#@#") || line.startsWith("[Adblock") -> abp++
                HOSTS_LINE.matches(line) -> hosts++
                line.startsWith("!") -> abp++
            }
        }
        return when {
            abp > hosts && abp > 0 -> FilterListKind.ABP
            hosts > 0 -> FilterListKind.HOSTS
            else -> null
        }
    }

    private fun abpCacheFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(application.filesDir, ABP_CACHE_DIR).apply { mkdirs() }
        return File(dir, "$digest.txt")
    }

    private fun writeAbpCache(url: String, lines: List<String>) {
        runCatching {
            abpCacheFile(url).writeText(lines.joinToString("\n"))
        }.onFailure { logger.log(TAG, "Unable to cache $url", it) }
    }

    private fun File.readLinesSafe(): List<String> =
        if (exists()) runCatching { readLines() }.getOrDefault(emptyList()) else emptyList()

    companion object {
        private const val TAG = "RemoteFilterListManager"
        private const val ABP_CACHE_DIR = "adblock/remote-abp"
        private const val MAX_LIST_BYTES = 8L * 1024L * 1024L
        private const val CHUNK_BYTES = 8192L
        private const val SNIFF_LINES = 300
        private const val MIN_ABP_RULES = 10
        private const val USER_AGENT = "SolipsismBrowser/filter-lists"
        private val HOSTS_LINE =
            Regex("""^(0\.0\.0\.0|127\.0\.0\.1|::1)\s+\S+""")
    }
}
