package com.krystelligence.solipsism.browser.engine

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.annotation.WorkerThread
import com.krystelligence.solipsism.adblock.custom.CustomFilterRepository
import com.krystelligence.solipsism.concurrency.CoroutineDispatchers
import com.krystelligence.solipsism.database.adblock.HostsRepository
import com.krystelligence.solipsism.preference.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the compact network policy transferred to the isolated Antares process. */
@Singleton
class AntaresContentBlockingPolicy @Inject constructor(
    private val application: Application,
    private val hostsRepository: HostsRepository,
    private val customFilterRepository: CustomFilterRepository,
    private val userPreferences: UserPreferences,
    private val appCoroutineScope: CoroutineScope,
    private val coroutineDispatchers: CoroutineDispatchers,
) {
    private val policyDirectory by lazy {
        File(application.cacheDir, "antares-content-blocking")
    }
    private var cachedSignature: Int? = null

    /**
     * In-memory policy text. Tab construction calls [readText] on the main
     * thread once per Antares tab; it must never touch the database or disk
     * there. A bulk hosts rewrite ([HostsDatabase.addHosts]) occupies the
     * single database dispatcher for seconds, and the old synchronous
     * `runBlocking { allHosts() }` parked main behind it past the 5s input
     * timeout — a full phone freeze on core switch. The cache may lag the
     * database until the next background refresh; filtering briefly uses the
     * previous snapshot instead of hanging the UI.
     */
    @Volatile private var cachedText: String? = null
    @Volatile private var cachedMemorySignature: MemorySignature? = null
    private var refreshJob: Job? = null

    private data class MemorySignature(
        val hostsVersion: Long,
        val ublockEnabled: Boolean,
        val customVersion: Long,
    )

    init {
        refreshAsync()
    }

    /**
     * The returned descriptor owns a read-only snapshot and must be closed by
     * the caller. Blocking: worker threads only, never the main thread.
     */
    @WorkerThread
    @Synchronized
    fun openFileDescriptor(): ParcelFileDescriptor {
        ensurePolicyFile()
        val policyFile = File(policyDirectory, POLICY_FILE_NAME)
        return ParcelFileDescriptor.open(policyFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Returns the current policy snapshot for the in-process renderer. */
    fun readText(): String {
        // Main-thread safe by construction: memory reads only. A stale snapshot
        // is served while a background rebuild catches up; an empty policy on
        // very first launch simply means "block nothing yet" for milliseconds.
        if (cachedText == null || cachedMemorySignature != memorySignature()) {
            refreshAsync()
        }
        return cachedText.orEmpty()
    }

    /**
     * Rebuilds the snapshot off the main thread. Coalesced: concurrent callers
     * share one in-flight rebuild.
     */
    @Synchronized
    fun refreshAsync() {
        if (refreshJob?.isActive == true) return
        refreshJob = appCoroutineScope.launch(coroutineDispatchers.io) {
            rebuildSnapshot()
        }
    }

    /** Blocking database + disk work. Must only run on [coroutineDispatchers.io]. */
    private fun rebuildSnapshot() {
        ensurePolicyFile()
        val policyFile = File(policyDirectory, POLICY_FILE_NAME)
        cachedText = ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.open(policyFile, ParcelFileDescriptor.MODE_READ_ONLY)
        ).bufferedReader().use { it.readText() }
        cachedMemorySignature = memorySignature()
    }

    /** Rewrites the policy file when inputs changed. Blocking: worker threads only. */
    private fun ensurePolicyFile() {
        val hosts = runBlocking { hostsRepository.allHosts() }
        val customRules = customFilterRepository.all()
            .filter { it.enabled }
            .map { it.line }
        val signature = 31 * hosts.hashCode() +
            17 * customRules.hashCode() +
            userPreferences.uBlockOriginEnabled.hashCode()
        val policyFile = File(policyDirectory, POLICY_FILE_NAME)

        if (cachedSignature != signature || !policyFile.isFile) {
            policyDirectory.mkdirs()
            val temporary = File(policyDirectory, "$POLICY_FILE_NAME.tmp")
            temporary.bufferedWriter().use { writer ->
                if (hosts.isEmpty()) {
                    application.assets.open(HOSTS_ASSET).bufferedReader().use { reader ->
                        reader.copyTo(writer)
                    }
                } else {
                    hosts.forEach { host ->
                        if (!isNeverBlockHost(host.name)) {
                            writer.append("||").append(host.name).appendLine("^")
                        }
                    }
                }
                if (userPreferences.uBlockOriginEnabled) {
                    writer.appendLine()
                    application.assets.open(UBLOCK_ASSET).bufferedReader().use { reader ->
                        reader.copyTo(writer)
                    }
                }
                customRules.forEach { rule -> writer.appendLine().append(rule) }
            }
            check(temporary.renameTo(policyFile)) { "Unable to update Antares content policy" }
            cachedSignature = signature
        }

        cachedText = ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.open(policyFile, ParcelFileDescriptor.MODE_READ_ONLY)
        ).bufferedReader().use { it.readText() }
        cachedMemorySignature = memorySignature()
    }

    private fun memorySignature() = MemorySignature(
        hostsVersion = hostsRepository.hostsVersion(),
        ublockEnabled = userPreferences.uBlockOriginEnabled,
        customVersion = customFilterRepository.version,
    )

    private companion object {
        const val POLICY_FILE_NAME = "network-policy.txt"
        const val HOSTS_ASSET = "hosts.txt"
        const val UBLOCK_ASSET = "ublock_origin_filters.txt"

        /**
         * Auth/captcha hosts that must never be content-blocked. Blocking
         * `gstatic.com/recaptcha` broke reddit challenge JS with
         * `SecurityError: The operation is insecure` in eval logs.
         */
        private val NEVER_BLOCK_SUFFIXES = listOf(
            "gstatic.com",
            "recaptcha.net",
            "google.com/recaptcha",
        )

        private fun isNeverBlockHost(name: String): Boolean {
            val host = name.trim().lowercase()
            return NEVER_BLOCK_SUFFIXES.any { suffix ->
                host == suffix || host.endsWith(".$suffix") || host.contains(suffix)
            }
        }
    }
}
