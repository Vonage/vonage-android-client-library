package com.vonage.clientlibrary.network

import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpCookie
import java.net.Socket
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONException
import org.json.JSONObject

internal class ClientSocket constructor(
    var tracer: TraceCollector = TraceCollector.instance,
    private val isDebuggable: Boolean = false
) {
    private lateinit var socket: Socket
    private lateinit var output: OutputStream
    private lateinit var rawInput: InputStream  // kept raw for byte-accurate Content-Length reads

    private val httpLogger = HttpRequestLogger(isDebuggable)

    var lastOperatorTrackingHeaders: Map<String, String> = emptyMap()
        private set

    fun open(
        url: URL,
        headers: Map<String, String>?,
        operator: String?,
        maxRedirectCount: Int
    ): JSONObject {
        val requestId: String = UUID.randomUUID().toString()
        val deadline = System.currentTimeMillis() + GLOBAL_DEADLINE_MS
        var redirectURL: URL? = null
        var redirectCount = 0
        var result: ResultHandler? = null
        var connectedAuthority: String? = null  // "host:port" — guards against same-host/different-port reuse
        var connectionKeptAlive = false         // did the previous hop leave the socket open?

        httpLogger.logRequest("GET", url.toString(), headers, body = null)

        do {
            redirectCount += 1
            val nurl = redirectURL ?: url
            tracer.addDebug(Log.DEBUG, TAG, "Requesting: $nurl")

            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0)
                return convertError("sdk_timeout_error", "Operation deadline exceeded")

            val nurlAuthority = "${nurl.host}:${if (nurl.port > 0) nurl.port else PORT_443}"

            try {
                // Reuse the existing TCP+TLS connection for same-host redirects (DEVX-11219).
                // Open a new connection when the authority (host:port) changes or on the first request.
                //
                // A matching authority is necessary but not sufficient: the socket is only reusable
                // if the previous hop actually left it open. When we send "Connection: close" the
                // server tears the connection down after responding, so writing the next hop into
                // that socket reads EOF — which surfaced as a phantom {"http_status": 0}.
                val reusingConnection = (nurlAuthority == connectedAuthority) && connectionKeptAlive
                if (!reusingConnection) {
                    if (connectedAuthority != null) stopConnection()
                    startConnection(nurl, remainingMs)
                    connectedAuthority = nurlAuthority
                } else {
                    // Refresh soTimeout to reflect remaining deadline budget
                    socket.soTimeout = remainingMs.coerceAtLeast(1L).toInt()
                    tracer.addDebug(Log.DEBUG, TAG, "Reusing connection, updated timeout to ${socket.soTimeout}ms")
                }

                // Request keep-alive on every hop so the server leaves the TCP+TLS socket open for
                // a following same-authority redirect (DEVX-11219). Whether we actually reuse it is
                // still gated by connectionKeptAlive below: we only reuse when the peer did not send
                // "Connection: close" and the response body was fully framed (drained by
                // Content-Length or chunked), so we never write into a socket being torn down.
                val keepAlive = true

                result = if (redirectCount == 1)
                    sendCommand(nurl, headers, operator, null, requestId, keepAlive = keepAlive)
                else
                    sendCommand(nurl, null, null, result?.getCookies(), requestId, keepAlive = keepAlive)

                // This hop leaves the socket usable for the next one only if we asked to keep it
                // alive and the peer did not ask us to close.
                connectionKeptAlive = keepAlive && result?.mustCloseConnection != true

                // Check if the result signals we must close (no Content-Length drain, or the peer
                // sent "Connection: close")
                if (result?.mustCloseConnection == true) {
                    stopConnection()
                    connectedAuthority = null
                    connectionKeptAlive = false
                }

                redirectURL = result?.getRedirect()

                // Close the connection when there are no more redirects, or when the
                // next redirect goes to a different authority.
                if (connectedAuthority != null &&
                    (redirectURL == null ||
                     "${redirectURL!!.host}:${if (redirectURL!!.port > 0) redirectURL!!.port else PORT_443}" != connectedAuthority)) {
                    stopConnection()
                    connectedAuthority = null
                    connectionKeptAlive = false
                }
            } catch (ex: Exception) {
                tracer.addDebug(Log.DEBUG, TAG, "Cannot start connection: $nurl")
                httpLogger.logError("Connection failed to $nurl", ex)
                if (connectedAuthority != null) {
                    runCatching { stopConnection() }
                    connectedAuthority = null
                }
                connectionKeptAlive = false
                return convertError("sdk_connection_error", "ex: ".plus(ex.localizedMessage))
            }
        } while (redirectURL != null && redirectCount <= maxRedirectCount)
        if (redirectCount >= maxRedirectCount)
            return convertError("sdk_redirect_error", "Too many redirects")
        tracer.addDebug(Log.DEBUG, TAG, "Open completed")
        if (result != null)
            return convertResultHandler(result)
        return convertError("sdk_error", "internal error")
    }

    private fun convertResultHandler(res: ResultResponse): JSONObject {
        // A status outside the HTTP range means no status line was ever parsed — the peer closed
        // the connection without sending a response. Report that as an error instead of leaking
        // the uninitialised 0 into the success shape, where callers checking for "error" miss it.
        if (res.getHttpStatus() !in HTTP_STATUS_RANGE) {
            return convertError("sdk_connection_error", "No HTTP response received from server")
        }
        var json: JSONObject = JSONObject()
        json.put("http_status", res.getHttpStatus())
        try {

            if (res.getBody() != null)
                json.put("response_body", JSONObject(res.getBody()))
            return json
        } catch (e: JSONException) {
            if (res.getBody() != null)
                json.put("response_raw_body", res.getBody())
            else
                return convertError("sdk_error", "ex: ".plus(e.localizedMessage))
        }
        return json
    }

    private fun convertError(code: String, description: String): JSONObject {
        var json: JSONObject = JSONObject()
        json.put("error", code)
        json.put("error_description", description)
        return json
    }

    private fun makePost(url: URL, headers: Map<String, String>, body: String?): String {
        val cmd = StringBuffer()
        cmd.append("POST " + url.path)
        cmd.append(" HTTP/1.1$CRLF")
        cmd.append("Host: " + url.host)
        if (url.protocol == "https" && url.port > 0 && url.port != PORT_443) {
            cmd.append(":" + url.port)
        } else if (url.protocol == "http" && url.port > 0 && url.port != PORT_80) {
            cmd.append(":" + url.port)
        }
        cmd.append(CRLF)
        headers.forEach { entry ->
            cmd.append(entry.key + ": " + entry.value + "$CRLF")
        }
        if (body != null) {
            cmd.append("Content-Length: " + body.length + "$CRLF")
            cmd.append("Connection: close$CRLF$CRLF")
            cmd.append(body)
            cmd.append("$CRLF$CRLF")
        } else {
            cmd.append("Content-Length: 0$CRLF")
            cmd.append("Connection: close$CRLF$CRLF")
        }
        return cmd.toString()
    }

    private fun sendAndReceive(request: String): ResponseHandler? {
        if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Client sending \n$request\n")
        try {
            val bytesOfRequest: ByteArray =
                request.toByteArray(Charset.forName(StandardCharsets.UTF_8.name()))
            output.write(bytesOfRequest)
            output.flush()
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Client sending exception : ${ex.message}")
            throw ex
        }
        if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Response " + "\n")
        var status: Int = 0
        var body: String = String()
        var result: ResponseHandler? = null
        var chunked: Boolean = false
        try {
            var response: String? = readMultipleBytes(rawInput, 65536)
            if (isDebuggable) {
                tracer.addDebug(Log.DEBUG, TAG, "$response \n")
                tracer.addDebug(Log.DEBUG, TAG, "--------" + "\n")
            }
            response?.let {
                val lines = response.split("\n")
                for (line in lines) {
                    if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, line)
                    tracer.addTrace(line)
                    if (line.startsWith("HTTP/")) {
                        val parts = line.split(" ")
                        if (parts.isNotEmpty() && parts.size >= 2) {
                            status = Integer.valueOf(parts[1].trim())
                            if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Status - $status")
                        }
                    } else if (line.startsWith("Transfer-Encoding:")) {
                        var parts = line.split(" ")
                        if (!parts.isEmpty() && parts.size > 1) {
                            if (parts[1].contains("chunked")) chunked = true
                        }
                    } else if (line.contains(": ") && body.isEmpty()) {
                        // do nothing
                    } else {
                        body += line.replace("\r", "")
                        if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Adding to body - $body\n")
                    }
                }
                if (chunked && !body.isNullOrBlank()) {
                    val r1: Int = body.indexOf("{")
                    val r2: Int = body.lastIndexOf("}")
                    if (r1 in 1 until r2) {
                        body = body.substring(r1, r2 + 1)
                    }
                }
                if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Status - $status [$chunked]\nBody - $body\n")
                result = ResponseHandler(status, body)
                httpLogger.logResponse(status, null, body)
            }
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Client reading exception : ${ex.message}")
            throw ex
        }
        return result
    }

    fun post(url: URL, headers: Map<String, String>, body: String?): JSONObject {
        httpLogger.logRequest("POST", url.toString(), headers, body)

        try {
            startConnection(url)
            val request = makePost(url, headers, body)
            httpLogger.logRawRequest(request)
            val response = sendAndReceive(request)
            if (response != null) {
                return convertResultHandler(response)
            }
            return convertError("sdk_error", "internal error")
        } catch (ex: Exception) {
            tracer.addDebug(Log.DEBUG, TAG, "Cannot complete post: $url")
            httpLogger.logError("POST request failed to $url", ex)
            return convertError("sdk_connection_error", "Connection failed: ${ex.localizedMessage ?: ex}")
        } finally {
            if (this::socket.isInitialized) {
                try {
                    stopConnection()
                } catch (e: Exception) {
                    tracer.addDebug(
                        Log.ERROR,
                        TAG,
                        "Exception received while closing the socket ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun makeHTTPCommand(
        url: URL,
        headers: Map<String, String>?,
        operator: String?,
        cookies: ArrayList<HttpCookie>?,
        requestId: String?,
        keepAlive: Boolean = false
    ): String {
        val CRLF = "\r\n"
        val cmd = StringBuffer()
        cmd.append("GET " + url.path)
        if (url.path.isEmpty()) {
            cmd.append("/")
        }
        if (url.query != null) {
            cmd.append("?" + url.query)
        }
        cmd.append(" HTTP/1.1$CRLF")
        cmd.append("Host: " + url.host)
        if (url.protocol == "https" && url.port > 0 && url.port != PORT_443) {
            cmd.append(":" + url.port)
        } else if (url.protocol == "http" && url.port > 0 && url.port != PORT_80) {
            cmd.append(":" + url.port)
        }
        cmd.append(CRLF)
        headers?.forEach { entry ->
            cmd.append(entry.key + ": " + entry.value + "${Companion.CRLF}")
        }
        val userAgent = userAgent()
        cmd.append("$HEADER_USER_AGENT: $userAgent$CRLF")

        if (requestId != null)
            cmd.append("x-silentauth-sdk-request: ${requestId}$CRLF")
        if (operator != null) {
            cmd.append("x-silentauth-ops: ${operator}$CRLF")
        }
        if (isEmulator()) {
            cmd.append("x-silentauth-mode: sandbox$CRLF")
        }
        cmd.append("Accept: text/html,application/xhtml+xml,application/xml,*/*$CRLF")
        var cs = StringBuffer()
        var cookieCount = 0
        val iterator = cookies.orEmpty().listIterator()
        for (cookie in iterator) {
            val normalizedHost = url.host.lowercase()
            val normalizedDomain = cookie.domain?.trimStart('.')?.lowercase()
            val domainMatch = normalizedDomain == null ||
                normalizedHost == normalizedDomain ||
                normalizedHost.endsWith(".$normalizedDomain")
            if (((cookie.secure && url.protocol == "https") || (!cookie.secure)) &&
                domainMatch &&
                (cookie.path == null || url.path.startsWith(cookie.path))
            ) {
                if (cookieCount > 0) cs.append("; ")
                cs.append(cookie.name + "=" + cookie.value)
                cookieCount++
            }
        }
        if (cs.length > 1) cmd.append("Cookie: " + cs.toString() + "$CRLF")

        // For same-host keep-alive chains, omit Connection: close so the server
        // keeps the TCP+TLS socket open for the next hop (DEVX-11219).
        if (!keepAlive) cmd.append("Connection: close$CRLF")
        cmd.append(CRLF)
        return cmd.toString()
    }

    private fun sendCommand(
        url: URL,
        headers: Map<String, String>?,
        operator: String?,
        cookies: ArrayList<HttpCookie>?,
        requestId: String?,
        keepAlive: Boolean = false
    ): ResultHandler? {
        val command = makeHTTPCommand(url, headers, operator, cookies, requestId, keepAlive)
        httpLogger.logRawRequest(command)
        return sendAndReceive(url, command, cookies)
    }

    private fun startConnection(url: URL, timeoutMs: Long = 5_000) {
        if (url.protocol != "https") {
            throw IOException("Only HTTPS URLs are supported. Received: ${url.protocol}://")
        }
        var port = PORT_443
        if (url.port > 0) port = url.port

        httpLogger.logConnection("Opening", url.host, port)

        tracer.addDebug(Log.DEBUG, TAG, "start : ${url.host} ${url.port} ${url.protocol}")
        tracer.addTrace("\nStart connection ${url.host} ${url.port} ${url.protocol} ${DateUtils.now()}\n")
        val sslSocket = SSLSocketFactory.getDefault().createSocket(url.host, port) as SSLSocket
        try {
            sslSocket.soTimeout = timeoutMs.coerceAtLeast(1L).toInt()
            val params = sslSocket.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            sslSocket.sslParameters = params
            sslSocket.startHandshake()
            socket = sslSocket
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Cannot create socket exception : ${ex.message}")
            tracer.addTrace("Cannot create socket exception ${ex.message}\n")
            runCatching { sslSocket.close() }
            throw ex
        }
        return try {
            tracer.addDebug(
                Log.DEBUG,
                TAG,
                "Client created : ${socket.inetAddress.hostAddress} ${socket.port}"
            )
            output = socket.getOutputStream()
            rawInput = socket.getInputStream()
            tracer.addDebug(
                Log.DEBUG,
                TAG,
                "Client connected : ${socket.inetAddress.hostAddress} ${socket.port}"
            )
            tracer.addTrace("Connected ${DateUtils.now()}\n")
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Client exception : ${ex.message}")
            tracer.addTrace("Client exception ${ex.message}\n")
            if (!socket.isClosed) socket.close()
            throw ex
        }
    }

    private fun sendAndReceive(
        requestURL: URL,
        message: String,
        existingCookies: ArrayList<HttpCookie>?
    ): ResultHandler? {
        if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Client sending \n$message\n")
        tracer.addTrace(message)
        try {
            val bytesOfRequest: ByteArray =
                message.toByteArray(Charset.forName(StandardCharsets.UTF_8.name()))
            output.write(bytesOfRequest)
            output.flush()
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Client sending exception : ${ex.message}")
            tracer.addTrace("Client sending exception ${ex.message}\n")
            throw ex
        }
        if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Response\n")
        tracer.addTrace("Response - ${DateUtils.now()} \n")
        var status: Int = 0
        var type: String = ""
        var redirectResult: ResultHandler? = null
        var bodyBegin: Boolean = false
        var contentLength: Int = -1
        var chunked: Boolean = false
        var earlyRedirect: Boolean = false
        var mustClose: Boolean = false
        val bodyBuilder = StringBuilder()  // DEVX-11223: avoid O(n²) string concat
        val cookies: ArrayList<HttpCookie> = ArrayList()
        if (existingCookies != null) cookies.addAll(existingCookies)
        val trackingHeaders: MutableMap<String, String> = mutableMapOf()

        try {
            // DEVX-11221: Parse headers line-by-line instead of buffering the full response.
            // Uses raw InputStream so subsequent byte-counted body reads are accurate (DEVX bytes/chars fix).
            var line: String? = readHttpLine(rawInput)
            while (line != null) {
                tracer.addTrace(line + "\n")
                when {
                    line.startsWith("HTTP/") -> {
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            status = parts[1].trim().toIntOrNull() ?: 0
                            if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Status - $status")
                            tracer.addTrace("Status - $status ${DateUtils.now()}\n")
                        }
                    }
                    line.startsWith("Set-Cookie:", ignoreCase = true) -> {
                        val parts: List<String> = line.split("ookie:")
                        if (parts.size > 1) {
                            try {
                                for (cookie in HttpCookie.parse(parts[1])) {
                                    cookies.add(cookie)
                                    if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "cookie - $cookie")
                                    tracer.addTrace("cookie - $cookie\n")
                                }
                            } catch (ex: IllegalArgumentException) {
                                tracer.addTrace("Cannot parse cookie ${parts[1]}  ${ex.message}\n")
                            }
                        }
                    }
                    line.startsWith("Location:", ignoreCase = true) -> {
                        redirectResult = parseRedirect(status, requestURL, line, cookies)
                        if (redirectResult != null && status in 300..399) {
                            tracer.addTrace("Redirect detected - ${DateUtils.now()}\n")
                            earlyRedirect = true
                        }
                    }
                    line.startsWith("Content-Type:", ignoreCase = true) -> {
                        val parts = line.split(" ")
                        if (parts.size > 1) {
                            type = parts[1].replace(";", "")
                        }
                        if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Type - $type\n")
                    }
                    line.startsWith("Content-Length:", ignoreCase = true) -> {
                        val parts = line.split(":")
                        if (parts.size > 1) {
                            contentLength = parts[1].trim().toIntOrNull() ?: -1
                            if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Content-Length - $contentLength")
                        }
                    }
                    line.startsWith("Transfer-Encoding:", ignoreCase = true) -> {
                        if (line.substringAfter(':').contains("chunked", ignoreCase = true)) {
                            chunked = true
                            if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Transfer-Encoding: chunked")
                        }
                    }
                    line.startsWith("Connection:", ignoreCase = true) -> {
                        // The peer is telling us this is the last exchange on this socket. Honour
                        // it, or the next hop gets written into a connection being torn down.
                        val wantsClose = line.substringAfter(':')
                            .split(',')
                            .any { it.trim().equals("close", ignoreCase = true) }
                        if (wantsClose) {
                            mustClose = true
                            tracer.addDebug(Log.DEBUG, TAG, "Peer sent Connection: close")
                            tracer.addTrace("Peer sent Connection: close\n")
                        }
                    }
                    OPERATOR_TRACKING_HEADERS.any { line!!.startsWith(it, ignoreCase = true) } -> {
                        val currentLine = line!!
                        val colonIdx = currentLine.indexOf(':')
                        if (colonIdx > 0) {
                            val name = currentLine.substring(0, colonIdx).trim()
                            val value = currentLine.substring(colonIdx + 1).trim()
                            trackingHeaders[name] = value
                            tracer.addDebug(Log.DEBUG, TAG, "Operator tracking header: $name=$value")
                        }
                    }
                    line.isEmpty() && earlyRedirect -> {
                        // End of headers on a redirect. Drain the body so the socket stays clean for
                        // reuse. Chunked or a known Content-Length can be drained exactly; without
                        // either we can't safely frame the body, so mark the connection to be closed.
                        when {
                            chunked -> {
                                tracer.addTrace("Draining chunked redirect body\n")
                                readChunkedBody(rawInput)
                            }
                            contentLength >= 0 -> {
                                tracer.addTrace("Draining $contentLength bytes for redirect body\n")
                                readFixedLengthBody(rawInput, contentLength)
                            }
                            else -> {
                                tracer.addTrace("Redirect with no Content-Length — closing connection\n")
                                mustClose = true
                            }
                        }
                        break
                    }
                    line.isEmpty() -> {
                        // End of headers on a non-redirect response. Read the body deterministically
                        // so a kept-alive socket never blocks waiting for EOF (DEVX-11219). A body
                        // with no Content-Length and no chunked framing ends at connection close, so
                        // read to EOF — a conforming keep-alive server always supplies framing.
                        // Only JSON content types are surfaced as a body; anything else is consumed
                        // to keep the stream framed but not returned, matching prior behaviour.
                        val rawBody = when {
                            chunked -> readChunkedBody(rawInput)
                            contentLength >= 0 -> readFixedLengthBody(rawInput, contentLength)
                            else -> readMultipleBytes(rawInput, 65536) ?: ""
                        }
                        if (isJsonType(type)) {
                            bodyBuilder.append(rawBody)
                            bodyBegin = bodyBuilder.isNotEmpty()
                        }
                        if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Body complete - ${DateUtils.now()}\n")
                        break
                    }
                }
                line = readHttpLine(rawInput)
            }

            if (earlyRedirect) {
                tracer.addTrace("Returning redirect - ${DateUtils.now()}\n")
                httpLogger.logRedirect(requestURL.toString(), redirectResult?.getRedirect()?.toString() ?: "unknown", status)
                // Signal open() to close the connection if we couldn't drain
                return if (mustClose) redirectResult?.withMustClose() else redirectResult
            }

            val body: String? = if (bodyBegin && bodyBuilder.isNotEmpty()) bodyBuilder.toString() else null
            if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Status - $status\nBody - $body\n")
            tracer.addTrace("Status - $status ${DateUtils.now()}\nBody - $body\n")
            httpLogger.logResponse(status, trackingHeaders.ifEmpty { null }, body)
            lastOperatorTrackingHeaders = trackingHeaders
            return redirectResult ?: if (bodyBegin && body != null) {
                ResultHandler(
                    status, null, parseBodyIntoJSONString(body), cookies,
                    mustCloseConnection = mustClose, operatorTrackingHeaders = trackingHeaders
                )
            } else {
                ResultHandler(
                    status, null, null, null,
                    mustCloseConnection = mustClose, operatorTrackingHeaders = trackingHeaders
                )
            }
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Client reading exception : ${ex.message}")
            tracer.addTrace("Client reading exception ${ex.message}\n")
            throw ex
        }
    }

    /**
     * Reads one HTTP header line from the raw InputStream, stripping the trailing CRLF.
     * Returns null on EOF, empty string on a blank line (end-of-headers).
     * Bytes-accurate: no buffering ahead into the body.
     */
    private fun readHttpLine(stream: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = stream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                // Drop the CR we already appended
                if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun isJsonType(type: String): Boolean =
        type == "application/json" || type == "application/hal+json" || type == "application/problem+json"

    /**
     * Reads exactly [length] body bytes from the stream and decodes them as UTF-8. Bytes are
     * buffered and decoded once so multibyte characters split across TCP segments stay intact.
     * Stops early on EOF. A definite length lets us return without waiting for the peer to close,
     * which is what makes a kept-alive socket reusable.
     */
    private fun readFixedLengthBody(stream: InputStream, length: Int): String {
        if (length <= 0) return ""
        val out = ByteArrayOutputStream(minOf(length, 8192))
        val buf = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    /**
     * Reads a chunked (Transfer-Encoding: chunked) body to its terminating zero-length chunk and
     * decodes the concatenated data as UTF-8. Terminates on the final chunk rather than on EOF, so
     * the socket is left clean and reusable. Trailer headers after the last chunk are consumed.
     */
    private fun readChunkedBody(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readHttpLine(stream) ?: break
            val token = sizeLine.trim().substringBefore(';')
            if (token.isEmpty()) continue
            val size = token.toIntOrNull(16) ?: break
            if (size <= 0) {
                // Consume any trailer headers up to the blank line that ends the message.
                var trailer = readHttpLine(stream)
                while (trailer != null && trailer.isNotEmpty()) trailer = readHttpLine(stream)
                break
            }
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = stream.read(buf, read, size - read)
                if (n <= 0) break
                read += n
            }
            out.write(buf, 0, read)
            // Consume the CRLF that terminates the chunk data.
            readHttpLine(stream)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    fun parseBodyIntoJSONString(body: String?): String? {
        if (body != null) {
            val start = body.indexOf("{")
            var end = body.lastIndexOf("}")
            var json = body.subSequence(start, end + 1).toString()
            return json
        }
        return null
    }

    fun parseRedirect(
        httpStatus: Int,
        requestURL: URL,
        redirectLine: String,
        cookies: ArrayList<HttpCookie>?
    ): ResultHandler? {
        tracer.addDebug(Log.DEBUG, TAG, "parseRedirect : $redirectLine")
        var parts = redirectLine.split("ocation: ")
        if (parts.isNotEmpty() && parts.size > 1) {
            if (parts[1].isBlank()) return null
            val redirect = parts[1]
            // some location header are not properly encoded
            var cleanRedirect = redirect.replace(" ", "+")
            tracer.addDebug(Log.DEBUG, TAG, "cleanRedirect : $cleanRedirect")
            if (!cleanRedirect.startsWith("http")) { // relative redirect
                return ResultHandler(httpStatus, URL(requestURL, cleanRedirect), null, cookies)
            }
            val redirectUrl = URL(cleanRedirect)
            if (requestURL.protocol == "https" && redirectUrl.protocol == "http") {
                tracer.addDebug(Log.DEBUG, TAG, "Blocked HTTPS-to-HTTP redirect downgrade")
                tracer.addTrace("Blocked HTTPS-to-HTTP redirect downgrade\n")
                return null
            }
            tracer.addDebug(Log.DEBUG, TAG, "Found redirect")
            tracer.addTrace("Found redirect - ${DateUtils.now()} \n")
            return ResultHandler(httpStatus, redirectUrl, null, cookies)
        }
        return null
    }

    private fun stopConnection() {
        httpLogger.logConnection("Closing", socket.inetAddress.hostAddress ?: "unknown", socket.port)
        tracer.addDebug(Log.DEBUG, TAG, "closed the connection ${socket.inetAddress.hostAddress}")
        try {
            rawInput.close()
            output.close()
            socket.close()
        } catch (e: Throwable) {
            tracer.addDebug(
                Log.ERROR,
                TAG,
                "Exception received whilst closing the socket ${e.localizedMessage}"
            )
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                Build.PRODUCT.contains("sdk_gphone_x86")
    }

    @Throws(IOException::class)
    private fun readMultipleBytes(stream: InputStream, length: Int): String? {
        // DEVX-11222: Loop until EOF — a single read() can return a partial buffer on
        // slow cellular networks where data trickles in across multiple TCP segments.
        val sb = StringBuilder()
        val buf = ByteArray(length)
        var bytesRead: Int
        while (stream.read(buf, 0, length).also { bytesRead = it } != -1) {
            sb.append(String(buf, 0, bytesRead, StandardCharsets.UTF_8))
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "CellularClient"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val PORT_80 = 80
        private const val PORT_443 = 443
        private const val CRLF = "\r\n"
        private const val GLOBAL_DEADLINE_MS: Long = 30_000
        private val HTTP_STATUS_RANGE = 100..599

        // Operator-injected tracking headers to capture and log for troubleshooting.
        // Orange uses X-Orange-Trace-Id; Vodafone uses X-VIG-Trace-Id.
        internal val OPERATOR_TRACKING_HEADERS = listOf(
            "X-Orange-Trace-Id",
            "X-VIG-Trace-Id"
        )
    }

    class ResultHandler(
        httpStatus: Int,
        redirect: URL?,
        body: String?,
        cookies: ArrayList<HttpCookie>?,
        val mustCloseConnection: Boolean = false,
        val operatorTrackingHeaders: Map<String, String> = emptyMap()
    ) : ResultResponse {
        val s: Int = httpStatus
        val r: URL? = redirect
        val b: String? = body
        val cs: ArrayList<HttpCookie>? = cookies

        override fun getHttpStatus(): Int {
            return s
        }

        fun getRedirect(): URL? {
            return r
        }

        override fun getBody(): String? {
            return b
        }

        fun getCookies(): ArrayList<HttpCookie>? {
            return cs
        }

        fun withMustClose(): ResultHandler =
            ResultHandler(s, r, b, cs, mustCloseConnection = true, operatorTrackingHeaders)
    }

    class ResponseHandler(httpStatus: Int, body: String?) : ResultResponse {
        val s: Int = httpStatus
        val b: String? = body

        override fun getHttpStatus(): Int {
            return s
        }

        override fun getBody(): String? {
            return b
        }
    }

    internal interface ResultResponse {
        fun getHttpStatus(): Int

        fun getBody(): String?
    }
}