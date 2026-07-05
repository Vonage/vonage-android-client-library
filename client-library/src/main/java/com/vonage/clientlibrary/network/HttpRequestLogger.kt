package com.vonage.clientlibrary.network

import android.util.Log

/**
 * Logs HTTP request and response details when the application is built in DEBUG mode.
 *
 * This logger is only active when [isDebuggable] is true (i.e., when
 * [android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE] is set). In release builds,
 * all methods are no-ops.
 *
 * Usage:
 * ```
 * val logger = HttpRequestLogger(isDebuggable = true)
 * logger.logRequest("GET", url, headers, body = null)
 * // ... perform request ...
 * logger.logResponse(200, responseHeaders, responseBody)
 * ```
 */
internal class HttpRequestLogger(private val isDebuggable: Boolean) {

    private val tracer = TraceCollector.instance

    /**
     * Logs the outgoing HTTP request details.
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param url The target URL
     * @param headers Request headers, or null if none
     * @param body Request body for POST requests, or null for GET
     */
    fun logRequest(method: String, url: String, headers: Map<String, String>?, body: String?) {
        if (!isDebuggable) return

        val sb = StringBuilder()
        sb.appendLine("┌────── HTTP REQUEST ──────────────────────────────────────")
        sb.appendLine("│ $method $url")
        sb.appendLine("│")
        if (!headers.isNullOrEmpty()) {
            sb.appendLine("│ Headers:")
            headers.forEach { (key, value) ->
                sb.appendLine("│   $key: $value")
            }
            sb.appendLine("│")
        }
        if (body != null) {
            sb.appendLine("│ Body:")
            body.lines().forEach { line ->
                sb.appendLine("│   $line")
            }
            sb.appendLine("│")
        }
        sb.appendLine("└──────────────────────────────────────────────────────────")

        tracer.addDebug(Log.DEBUG, TAG, sb.toString())
    }

    /**
     * Logs the raw HTTP request string as constructed for the socket.
     *
     * @param rawRequest The full HTTP/1.1 request string sent over the wire
     */
    fun logRawRequest(rawRequest: String) {
        if (!isDebuggable) return

        val sb = StringBuilder()
        sb.appendLine("┌────── RAW HTTP REQUEST ───────────────────────────────────")
        rawRequest.lines().forEach { line ->
            sb.appendLine("│ $line")
        }
        sb.appendLine("└──────────────────────────────────────────────────────────")

        tracer.addDebug(Log.DEBUG, TAG, sb.toString())
    }

    /**
     * Logs the HTTP response details.
     *
     * @param statusCode HTTP response status code
     * @param headers Response headers as a map, or null if not parsed
     * @param body Response body, or null if empty
     * @param durationMs Time taken for the request in milliseconds, or -1 if not measured
     */
    fun logResponse(statusCode: Int, headers: Map<String, String>?, body: String?, durationMs: Long = -1) {
        if (!isDebuggable) return

        val sb = StringBuilder()
        sb.appendLine("┌────── HTTP RESPONSE ─────────────────────────────────────")
        sb.append("│ Status: $statusCode")
        if (durationMs >= 0) {
            sb.append(" (${durationMs}ms)")
        }
        sb.appendLine()
        sb.appendLine("│")
        if (!headers.isNullOrEmpty()) {
            sb.appendLine("│ Headers:")
            headers.forEach { (key, value) ->
                sb.appendLine("│   $key: $value")
            }
            sb.appendLine("│")
        }
        if (body != null) {
            sb.appendLine("│ Body:")
            body.lines().forEach { line ->
                sb.appendLine("│   $line")
            }
            sb.appendLine("│")
        }
        sb.appendLine("└──────────────────────────────────────────────────────────")

        tracer.addDebug(Log.DEBUG, TAG, sb.toString())
    }

    /**
     * Logs a redirect event.
     *
     * @param fromUrl The URL that triggered the redirect
     * @param toUrl The target redirect URL
     * @param statusCode The HTTP status code (301, 302, etc.)
     */
    fun logRedirect(fromUrl: String, toUrl: String, statusCode: Int) {
        if (!isDebuggable) return

        tracer.addDebug(Log.DEBUG, TAG, "↪ REDIRECT [$statusCode]: $fromUrl → $toUrl")
    }

    /**
     * Logs a connection event (socket open/close).
     *
     * @param event Description of the connection event
     * @param host The target host
     * @param port The target port
     */
    fun logConnection(event: String, host: String, port: Int) {
        if (!isDebuggable) return

        tracer.addDebug(Log.DEBUG, TAG, "⚡ CONNECTION: $event → $host:$port")
    }

    /**
     * Logs an error that occurred during the HTTP request lifecycle.
     *
     * @param message Error description
     * @param exception The exception, or null
     */
    fun logError(message: String, exception: Exception? = null) {
        if (!isDebuggable) return

        val msg = if (exception != null) {
            "✖ HTTP ERROR: $message — ${exception.javaClass.simpleName}: ${exception.message}"
        } else {
            "✖ HTTP ERROR: $message"
        }
        tracer.addDebug(Log.ERROR, TAG, msg)
    }

    companion object {
        private const val TAG = "VonageHttpLogger"
    }
}
