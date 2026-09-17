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

            val nurlAuthority = "${nurl.host}:${if (nurl.port > 0) nurl.port else PORT_443}"
            // Captured before the attempt loop so a retry resends the same request.
            val hopHeaders = if (redirectCount == 1) headers else null
            val hopOperator = if (redirectCount == 1) operator else null
            val hopCookies = if (redirectCount == 1) null else result?.getCookies()

            try {
                var hopResult: ResultHandler? = null
                var attempt = 0
                while (true) {
                    attempt += 1

                    val remainingMs = deadline - System.currentTimeMillis()
                    if (remainingMs <= 0) {
                        // A pending same-authority redirect leaves the socket open for a hop that
                        // will never run, so close it here or every timeout leaks a socket.
                        if (connectedAuthority != null) runCatching { stopConnection() }
                        return convertError("sdk_timeout_error", "Operation deadline exceeded")
                    }

                    // Reuse the existing TCP+TLS connection for same-host redirects (DEVX-11219).
                    // Open a new connection when the authority (host:port) changes or on the first
                    // request. A matching authority is necessary but not sufficient: the socket is
                    // only reusable if the previous hop left it open and fully framed.
                    val reusingConnection = (nurlAuthority == connectedAuthority) && connectionKeptAlive
                    if (!reusingConnection) {
                        if (connectedAuthority != null) stopConnection()
                        startConnection(nurl, remainingMs)
                        connectedAuthority = nurlAuthority
                    } else {
                        // Bound the reuse attempt to part of the remaining budget. A half-open
                        // connection can silently blackhole packets instead of returning EOF, and
                        // spending the whole deadline here would leave nothing for the retry below —
                        // making stale-connection recovery work only for immediate closes.
                        socket.soTimeout = (remainingMs / REUSE_PROBE_BUDGET_DIVISOR).coerceAtLeast(1L).toInt()
                        tracer.addDebug(Log.DEBUG, TAG, "Reusing connection, probe timeout ${socket.soTimeout}ms")
                    }

                    // Request keep-alive so the server leaves the TCP+TLS socket open for a
                    // following same-authority redirect. Whether we then reuse it is decided by
                    // connectionKeptAlive below.
                    val attemptResult = runCatching {
                        sendCommand(nurl, hopHeaders, hopOperator, hopCookies, requestId, keepAlive = true)
                    }
                    val attemptStatus = attemptResult.getOrNull()?.getHttpStatus()
                    if (attemptResult.isSuccess && attemptStatus != null && attemptStatus in HTTP_STATUS_RANGE) {
                        hopResult = attemptResult.getOrThrow()
                        break
                    }

                    // A peer may close an idle kept-alive socket at any time, so a reused
                    // connection that fails to answer is not an error yet. Drop it and resend once
                    // on a fresh connection; open() only issues GETs, so replaying is safe.
                    if (reusingConnection && attempt == 1) {
                        tracer.addDebug(Log.DEBUG, TAG, "Reused connection closed by peer; retrying on a new connection")
                        tracer.addTrace("Reused connection closed by peer; retrying on a new connection\n")
                        runCatching { stopConnection() }
                        connectedAuthority = null
                        connectionKeptAlive = false
                        continue
                    }

                    // A fresh connection failing is a real error; report it as before.
                    attemptResult.exceptionOrNull()?.let { throw it }
                    hopResult = attemptResult.getOrNull()
                    break
                }
                result = hopResult

                // This hop leaves the socket usable for the next one only if we got a response and
                // the peer did not require a close.
                connectionKeptAlive = result != null && result.mustCloseConnection != true

                // Check if the result signals we must close (unframed body, legacy HTTP version, or
                // the peer sent "Connection: close")
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
        // The limit is only exceeded if we stopped while a redirect was still pending. Counting
        // requests instead rejected chains that had already completed: a successful chain using its
        // full redirect budget returned "Too many redirects", and with maxRedirectCount = 0 even a
        // direct response was rejected.
        if (redirectURL != null) {
            // The pending redirect kept the socket open for a hop that will never run, and the
            // cleanup below the loop was skipped for the same reason. Close it here or repeated
            // over-limit chains leak a TCP+TLS socket each.
            if (connectedAuthority != null) runCatching { stopConnection() }
            return convertError("sdk_redirect_error", "Too many redirects")
        }
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

        // For same-host keep-alive chains, ask for persistence explicitly (DEVX-11219). HTTP/1.1 is
        // persistent by default, but an HTTP/1.0 peer only keeps the socket open when both sides send
        // the token — omitting "Connection: close" alone is not enough for those gateways.
        if (keepAlive) cmd.append("Connection: keep-alive$CRLF") else cmd.append("Connection: close$CRLF")
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
        // HTTP/1.0 and earlier are not persistent unless the peer opts in with
        // "Connection: keep-alive". Track both so reuse is never assumed.
        var legacyHttpVersion: Boolean = false
        var peerRequestedKeepAlive: Boolean = false
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
                        if (status in 100..199) {
                            // Advancing past an interim response. Its per-response metadata must not
                            // frame, route or annotate the final response; only connection-scoped
                            // state (mustClose) carries over.
                            contentLength = -1
                            chunked = false
                            type = ""
                            redirectResult = null
                            earlyRedirect = false
                            peerRequestedKeepAlive = false
                            trackingHeaders.clear()
                        }
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            status = parts[1].trim().toIntOrNull() ?: 0
                            if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Status - $status")
                            tracer.addTrace("Status - $status ${DateUtils.now()}\n")
                        }
                        val version = parts[0].trim().substringAfter('/', "")
                        val major = version.substringBefore('.').toIntOrNull() ?: 1
                        val minor = version.substringAfter('.', "0").toIntOrNull() ?: 0
                        legacyHttpVersion = major < 1 || (major == 1 && minor < 1)
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
                        // Compare codings as exact tokens: a substring match would send
                        // "x-chunked" — a different transfer-coding — into the chunk decoder.
                        val codings = line.substringAfter(':').split(',').map { it.trim() }
                        if (codings.any { it.equals("chunked", ignoreCase = true) }) {
                            chunked = true
                            if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Transfer-Encoding: chunked")
                        }
                    }
                    line.startsWith("Connection:", ignoreCase = true) -> {
                        // The peer is telling us whether this is the last exchange on this socket.
                        // Honour "close", or the next hop gets written into a connection being torn
                        // down; record "keep-alive" so a legacy response can opt into reuse.
                        val tokens = line.substringAfter(':').split(',').map { it.trim() }
                        if (tokens.any { it.equals("close", ignoreCase = true) }) {
                            mustClose = true
                            tracer.addDebug(Log.DEBUG, TAG, "Peer sent Connection: close")
                            tracer.addTrace("Peer sent Connection: close\n")
                        }
                        if (tokens.any { it.equals("keep-alive", ignoreCase = true) }) {
                            peerRequestedKeepAlive = true
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
                    line.isEmpty() && status in 100..199 -> {
                        // 101 hands the socket to another protocol. This client never requests an
                        // upgrade, so whatever follows is not HTTP: parsing it would consume
                        // upgraded-protocol bytes as headers or block until the deadline.
                        if (status == 101) {
                            throw IOException("Unexpected protocol upgrade (101 Switching Protocols)")
                        }
                        // Any other informational response has no body and precedes the final
                        // response on this connection. Keep parsing rather than treating it as
                        // the completed request.
                        tracer.addTrace("Informational response received; awaiting final response\n")
                    }
                    line.isEmpty() && earlyRedirect -> {
                        // End of headers on a redirect. Drain the body so the socket stays clean for
                        // reuse, without retaining bytes we are about to discard: the length is
                        // peer-controlled and buffering it could exhaust the heap. Chunked or a known
                        // Content-Length can be drained exactly; without either we cannot frame the
                        // body, so mark the connection to be closed.
                        when {
                            chunked -> {
                                tracer.addTrace("Draining chunked redirect body\n")
                                drainChunkedBody(rawInput)
                            }
                            contentLength >= 0 -> {
                                tracer.addTrace("Draining $contentLength bytes for redirect body\n")
                                drainFixedLengthBody(rawInput, contentLength)
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
                        // so a kept-alive socket never blocks waiting for EOF (DEVX-11219).
                        //
                        // Only JSON content types are surfaced to the caller, so decide read-versus-
                        // drain *before* consuming: buffering a peer-controlled non-JSON body just to
                        // discard it could exhaust the heap. Drains run in constant memory.
                        val keepBody = isJsonType(type)
                        when {
                            !responseCanHaveBody(status) -> {}
                            chunked ->
                                if (keepBody) bodyBuilder.append(readChunkedBody(rawInput))
                                else drainChunkedBody(rawInput)
                            contentLength >= 0 ->
                                if (keepBody) bodyBuilder.append(readFixedLengthBody(rawInput, contentLength))
                                else drainFixedLengthBody(rawInput, contentLength)
                            else -> {
                                // No framing: the body is delimited by connection close, so once we
                                // reach EOF this socket is finished and must not be reused.
                                mustClose = true
                                if (keepBody) bodyBuilder.append(readMultipleBytes(rawInput, 65536) ?: "")
                                else drainToEndOfStream(rawInput)
                            }
                        }
                        bodyBegin = bodyBuilder.isNotEmpty()
                        if (isDebuggable) tracer.addDebug(Log.DEBUG, TAG, "Body complete - ${DateUtils.now()}\n")
                        break
                    }
                }
                line = readHttpLine(rawInput)
            }

            // An informational response is not a final response. If the peer closed after sending
            // one, no final status line ever arrived: failing here lets a reused socket be retried
            // and stops an interim status leaking to the caller inside the success shape.
            if (status in 100..199) {
                throw IOException("Connection closed after an informational response; no final response received")
            }

            // An HTTP/1.0 (or earlier) response is not persistent unless the peer explicitly asked
            // to keep the connection alive, so a following same-host hop must not reuse this socket.
            if (legacyHttpVersion && !peerRequestedKeepAlive) {
                tracer.addTrace("Legacy HTTP version without keep-alive — closing connection\n")
                mustClose = true
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
            // A non-3xx response may still carry a Location header. redirectResult was built by
            // parseRedirect() with the default mustCloseConnection = false, so every close decision
            // made above — unframed body, legacy version, explicit "Connection: close" — has to be
            // applied here or it is silently dropped and the next hop reuses a spent socket.
            redirectResult?.let { return if (mustClose) it.withMustClose() else it }
            return if (bodyBegin && body != null) {
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
     * Accepts a bare LF as well as CRLF: without a terminator match the parser would keep reading,
     * and on a kept-alive socket there is no EOF to stop it, so the request would stall.
     * Bytes-accurate: no buffering ahead into the body.
     */
    private fun readHttpLine(stream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                // Drop the CR of a CRLF pair when present.
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun isJsonType(type: String): Boolean =
        type == "application/json" || type == "application/hal+json" || type == "application/problem+json"

    private fun responseCanHaveBody(status: Int): Boolean =
        status != 204 && status != 205 && status != 304

    /**
     * Reads exactly [length] body bytes and decodes them as UTF-8. Bytes are buffered and decoded
     * once so multibyte characters split across TCP segments stay intact.
     */
    private fun readFixedLengthBody(stream: InputStream, length: Int): String {
        val out = ByteArrayOutputStream(minOf(length.coerceAtLeast(0), 8192))
        transferFixedLengthBody(stream, length, out)
        return out.toString(StandardCharsets.UTF_8.name())
    }

    /** Consumes exactly [length] body bytes without retaining them. */
    private fun drainFixedLengthBody(stream: InputStream, length: Int) =
        transferFixedLengthBody(stream, length, null)

    /**
     * Copies [length] body bytes through a fixed buffer into [sink], or discards them when [sink] is
     * null. A premature EOF is an invalid HTTP response and must fail rather than leave a partial
     * body on a socket that could otherwise be reused.
     */
    private fun transferFixedLengthBody(stream: InputStream, length: Int, sink: ByteArrayOutputStream?) {
        if (length <= 0) return
        val buf = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) throw IOException("Unexpected EOF while reading $length-byte response body")
            sink?.write(buf, 0, n)
            remaining -= n
        }
    }

    /** Consumes a close-delimited body without retaining it. */
    private fun drainToEndOfStream(stream: InputStream) {
        val buf = ByteArray(8192)
        while (stream.read(buf, 0, buf.size) != -1) {
            // Discard: the caller does not surface this body.
        }
    }

    /**
     * Reads a chunked (Transfer-Encoding: chunked) body to its terminating zero-length chunk and
     * decodes the concatenated data as UTF-8.
     */
    private fun readChunkedBody(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        transferChunkedBody(stream, out)
        return out.toString(StandardCharsets.UTF_8.name())
    }

    /** Consumes a chunked body without retaining it, still validating the framing. */
    private fun drainChunkedBody(stream: InputStream) = transferChunkedBody(stream, null)

    /**
     * Walks a chunked body to its terminating zero-length chunk, copying data into [sink] or
     * discarding it when [sink] is null. Any truncated or malformed frame is rejected so we never
     * treat a dead or unclean connection as reusable. Chunk data is copied through a fixed buffer:
     * the advertised size is peer-controlled, and allocating from it would risk an OutOfMemoryError
     * that our Exception handling cannot catch. Trailer headers are consumed before returning.
     */
    private fun transferChunkedBody(stream: InputStream, sink: ByteArrayOutputStream?) {
        val buf = ByteArray(8192)
        while (true) {
            val sizeLine = readHttpLine(stream)
                ?: throw IOException("Unexpected EOF while reading chunk size")
            val token = sizeLine.trim().substringBefore(';')
            val size = token.toIntOrNull(16)
                ?: throw IOException("Invalid chunk size: $sizeLine")
            if (size < 0) throw IOException("Negative chunk size: $sizeLine")
            if (size == 0) {
                // Consume any trailer headers up to the blank line that ends the message.
                while (true) {
                    val trailer = readHttpLine(stream)
                        ?: throw IOException("Unexpected EOF while reading chunk trailers")
                    if (trailer.isEmpty()) return
                }
            }
            var remaining = size
            while (remaining > 0) {
                val n = stream.read(buf, 0, minOf(buf.size, remaining))
                if (n <= 0) throw IOException("Unexpected EOF while reading chunk data")
                sink?.write(buf, 0, n)
                remaining -= n
            }
            // A CRLF must immediately follow every chunk's data.
            if (readHttpLine(stream) != "") throw IOException("Missing chunk delimiter")
        }
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
        // A reused socket gets this fraction of the remaining deadline, leaving the rest for a retry
        // on a fresh connection if the reused one turns out to be half-open.
        private const val REUSE_PROBE_BUDGET_DIVISOR: Long = 2
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