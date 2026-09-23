package com.krystelligence.solipsism.browser.console

import org.json.JSONObject

/**
 * Shared REPL envelope for both engines. The evaluated script must produce
 * `{"ok":true,"value":"…"}` or `{"ok":false,"error":"…"}` JSON text:
 * WebView wraps user code to that shape in [buildConsoleEvalScript], while
 * the Antares engine builds it natively around the evaluation result.
 */
const val MAX_CONSOLE_COMMAND_LENGTH = 8_000
const val MAX_CONSOLE_RESULT_LENGTH = 8_000

data class DecodedEvalResult(val ok: Boolean, val text: String)

/**
 * Wraps arbitrary REPL [code] so errors (including stacks) come back as data.
 * `JSONObject.quote` keeps the input safely embeddable. Mirrors desktop REPLs:
 * full window access, expression completion value returned.
 */
fun buildConsoleEvalScript(code: String): String =
    "(function(){try{var r=eval(${JSONObject.quote(code)});" +
        "var v=(typeof r==='undefined')?'undefined':(JSON.stringify(r)??String(r));" +
        "return JSON.stringify({ok:true,value:String(v)});}catch(e){" +
        "return JSON.stringify({ok:false,error:String((e&&e.stack)||e)});}})()"

fun decodeConsoleEvalResult(resultJson: String?): DecodedEvalResult? {
    if (resultJson.isNullOrBlank()) return null
    return runCatching {
        // WebView nests the envelope in the evaluateJavascript string
        // encoding; the Antares JNI callback delivers it directly.
        val json = when (val first = org.json.JSONTokener(resultJson).nextValue()) {
            is String -> JSONObject(first)
            is JSONObject -> first
            else -> return null
        }
        val ok = json.optBoolean("ok", true)
        val text = if (ok) json.optString("value") else json.optString("error")
        DecodedEvalResult(ok, text)
    }.getOrNull()
}
