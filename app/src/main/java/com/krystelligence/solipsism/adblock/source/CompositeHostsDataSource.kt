package com.krystelligence.solipsism.adblock.source

import com.krystelligence.solipsism.database.adblock.Host
import java.io.IOException
import java.security.MessageDigest

/**
 * Merges several [HostsDataSource] instances into one union list.
 *
 * Partial failures are tolerated: sources that load contribute their hosts while
 * failed sources are reported through [onSourceResult] and skipped. Loading fails
 * only when every source fails.
 */
class CompositeHostsDataSource(
    private val sources: List<HostsDataSource>,
    private val onSourceResult: (identifier: String, success: Boolean) -> Unit = { _, _ -> }
) : HostsDataSource {

    override suspend fun loadHosts(): HostsResult {
        val merged = linkedSetOf<Host>()
        var anySuccess = false
        var firstFailure: Exception? = null

        sources.forEach { source ->
            val identifier = runCatching { source.identifier() }.getOrNull()
            val result = runCatching { source.loadHosts() }
                .getOrElse { HostsResult.Failure(it as? Exception ?: IOException(it.message)) }

            when (result) {
                is HostsResult.Success -> {
                    anySuccess = true
                    merged += result.hosts
                    identifier?.let { onSourceResult(it, true) }
                }
                is HostsResult.Failure -> {
                    if (firstFailure == null) firstFailure = result.cause
                    identifier?.let { onSourceResult(it, false) }
                }
            }
        }

        return if (anySuccess) {
            HostsResult.Success(merged.toList())
        } else {
            HostsResult.Failure(firstFailure ?: IOException("No hosts sources available"))
        }
    }

    override suspend fun identifier(): String {
        val ids = sources.mapNotNull { runCatching { it.identifier() }.getOrNull() }.sorted()
        val digest = MessageDigest.getInstance("MD5")
            .digest(ids.joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "composite:$digest"
    }
}
