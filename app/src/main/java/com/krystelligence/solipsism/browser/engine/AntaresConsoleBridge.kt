package com.krystelligence.solipsism.browser.engine

import android.net.Uri
import com.krystelligence.solipsism.browser.console.ConsoleLevel
import org.json.JSONObject

/**
 * Maps the injected wrapper's `console.*` names onto console levels, matching
 * the Chromium severity model used by the WebView path.
 */
fun String.toConsoleLevel(): ConsoleLevel = when (lowercase()) {
    "error" -> ConsoleLevel.ERROR
    "warn" -> ConsoleLevel.WARNING
    "info", "log" -> ConsoleLevel.INFO
    else -> ConsoleLevel.VERBOSE
}

/**
 * Uncaught-error tap for the Antares core.
 *
 * `console.*` output travels natively now (servoshell forwards
 * `show_console_message` over JNI), but uncaught exceptions have no engine
 * hook, so `onerror`/`unhandledrejection` still ride the audited
 * title-signalling channel (same pattern as [AntaresHtmlMediaBridge] and
 * [AntaresCoordinateProbe]): one JSON payload per signal, original title
 * restored afterwards. A small queue with sequence numbers keeps bursts
 * ordered; beyond [MAX_QUEUED] entries the oldest are dropped rather than
 * stalling the title restore.
 */
internal object AntaresConsoleBridge {

    data class Signal(
        val sequence: Int,
        val level: String,
        val message: String,
        val source: String,
        val line: Int
    )

    private const val TITLE_PREFIX = "__SOLIPSISM_CONSOLE_V1__:"

    val installScript: String =
        """
        (() => {
          if (window.__solipsismConsoleInstalled) return;
          window.__solipsismConsoleInstalled = true;
          const queue = [];
          let sequence = 0;
          let pumping = false;
          const pump = () => {
            if (pumping || !queue.length) return;
            if (document.title.indexOf('$TITLE_PREFIX') === 0) {
              setTimeout(pump, 250);
              return;
            }
            pumping = true;
            const entry = queue.shift();
            const original = document.title;
            document.title = '$TITLE_PREFIX' + encodeURIComponent(JSON.stringify(entry));
            setTimeout(() => {
              if (document.title.indexOf('$TITLE_PREFIX') === 0) document.title = original;
              pumping = false;
              if (queue.length) setTimeout(pump, 120);
            }, 400);
          };
          const enqueue = (level, message, source, line) => {
            queue.push({
              sequence: sequence++,
              level,
              message: String(message == null ? '' : message).slice(0, 2000),
              source: String(source || '').slice(0, 512),
              line: line | 0
            });
            if (queue.length > $MAX_QUEUED) queue.splice(0, queue.length - $MAX_QUEUED);
            pump();
          };
          window.addEventListener('error', event => {
            if (event.message === 'Script error.') return;
            enqueue('error', event.message, event.filename || '', event.lineno || 0);
          });
          window.addEventListener('unhandledrejection', event => {
            const reason = event.reason;
            const message = reason && reason.stack ? reason.stack : String(reason);
            enqueue('error', 'Unhandled rejection: ' + message, '', 0);
          });
        })();
        """.trimIndent().replace("\$MAX_QUEUED", MAX_QUEUED.toString())

    fun decodeTitle(title: String): Signal? {        if (!title.startsWith(TITLE_PREFIX)) return null
        return runCatching {
            val json = JSONObject(Uri.decode(title.removePrefix(TITLE_PREFIX)))
            Signal(
                sequence = json.optInt("sequence", 0),
                level = json.optString("level").takeIf(String::isNotBlank) ?: return null,
                message = json.optString("message").takeIf(String::isNotBlank) ?: return null,
                source = json.optString("source"),
                line = json.optInt("line", 0)
            )
        }.getOrNull()
    }

    private const val MAX_QUEUED = 20
}

/**
 * Maps the engine's ConsoleLogLevel integers (see `console_log_level_to_int`
 * in `ports/servoshell/egl/app.rs`) onto console levels.
 */
fun Int.toEngineConsoleLevel(): ConsoleLevel = when (this) {
    0 -> ConsoleLevel.INFO
    1 -> ConsoleLevel.VERBOSE
    2 -> ConsoleLevel.INFO
    3 -> ConsoleLevel.WARNING
    4 -> ConsoleLevel.ERROR
    5 -> ConsoleLevel.VERBOSE
    else -> ConsoleLevel.INFO
}
