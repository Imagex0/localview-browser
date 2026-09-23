package com.krystelligence.solipsism.browser.console

import android.webkit.ConsoleMessage
import com.krystelligence.solipsism.browser.engine.BrowserCore

/**
 * Severity of a console entry, mirroring desktop Chromium's
 * Verbose / Info / Warning / Error groups plus local REPL traffic.
 *
 * See https://developer.chrome.com/docs/devtools/console/api and
 * https://developer.chrome.com/docs/devtools/console/reference
 */
enum class ConsoleLevel {
    VERBOSE,
    INFO,
    WARNING,
    ERROR,
    COMMAND,
    RESULT
}

/**
 * Maps Chromium severities onto console levels, per
 * https://developer.chrome.com/docs/devtools/console/api
 */
fun ConsoleMessage.MessageLevel.toConsoleLevel(): ConsoleLevel = when (this) {
    ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.VERBOSE
    ConsoleMessage.MessageLevel.LOG -> ConsoleLevel.INFO
    ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.WARNING
    ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.ERROR
    ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.VERBOSE
}

/**
 * One shaped console record for the log list UI, filtering, and per-tab
 * stores. Field set mirrors CDP `Runtime.consoleAPICalled`
 * (type, args, stackTrace, timestamp, executionContextId), adapted to
 * engine + tab + url.
 *
 * See https://chromedevtools.github.io/devtools-protocol/tot/Runtime/#event-consoleAPICalled
 */
data class ConsoleEntry(
    val engine: BrowserCore,
    val tabId: Int,
    val url: String?,
    val level: ConsoleLevel,
    val message: String,
    val source: String?,
    val line: Int?,
    val timestamp: Long = System.currentTimeMillis()
)
