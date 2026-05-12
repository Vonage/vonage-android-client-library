package com.vonage.clientlibrary.network

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.IOException
import java.net.HttpCookie
import java.net.Socket
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.net.ssl.SSLSocketFactory
import org.json.JSONException
import org.json.JSONObject

internal class ClientSocket constructor(var tracer: TraceCollector = TraceCollector.instance) {
    private lateinit var socket: Socket
    private lateinit var output: OutputStream
    private lateinit var input: BufferedReader

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
        var connectedHost: String? = null
        do {
            redirectCount += 1
            val nurl = redirectURL ?: url
            tracer.addDebug(Log.DEBUG, TAG, "Requesting: $nurl")

            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0)
                return convertError("sdk_timeout_error", "Operation deadline exceeded")

            try {
                // Reuse the existing TCP+TLS connection for same-host redirects (DEVX-11219).
                // Open a new connection when the host changes or on the first request.
                if (nurl.host != connectedHost) {
                    if (connectedHost != null) stopConnection()
                    startConnection(nurl, remainingMs)
                    connectedHost = nurl.host
                }

                result = if (redirectCount == 1)
                    sendCommand(nurl, headers, operator, null, requestId, keepAlive = true)
                else
                    sendCommand(nurl, null, null, result?.getCookies(), requestId, keepAlive = true)

                redirectURL = result?.getRedirect()

                // Close the connection when there are no more redirects, or when the
                // next redirect goes to a different host (Connection: close was not sent
                // for same-host hops, so the server is keeping the socket open).
                if (redirectURL == null || redirectURL!!.host != nurl.host) {
                    stopConnection()
                    connectedHost = null
                }
            } catch (ex: Exception) {
                tracer.addDebug(Log.DEBUG, TAG, "Cannot start connection: $nurl")
                return convertError("sdk_connection_error", "ex: ".plus(ex.localizedMessage))
            }
        } while (redirectURL != null && redirectCount <= maxRedirectCount)
        if (redirectCount == maxRedirectCount)
            return convertError("sdk_redirect_error", "Too many redirects")
        tracer.addDebug(Log.DEBUG, TAG, "Open completed")
        if (result != null)
            return convertResultHandler(result)
        return convertError("sdk_error", "internal error")
    }

    private fun convertResultHandler(res: ResultResponse): JSONObject {
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
        tracer.addDebug(Log.DEBUG, TAG, "Client sending \n$request\n")
        try {
            val bytesOfRequest: ByteArray =
                request.toByteArray(Charset.forName(StandardCharsets.UTF_8.name()))
            output.write(bytesOfRequest)
            output.flush()
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Client sending exception : ${ex.message}")
            throw ex
        }
        tracer.addDebug(Log.DEBUG, TAG, "Response " + "\n")
        var status: Int = 0
        var body: String = String()
        var result: ResponseHandler? = null
        var chunked: Boolean = false
        try {
            var response: String? = readMultipleChars(input, 65536)
            tracer.addDebug(Log.DEBUG, TAG, "$response \n")
            tracer.addDebug(Log.DEBUG, TAG, "--------" + "\n")
            response?.let {
                val lines = response.split("\n")
                for (line in lines) {
                    tracer.addDebug(Log.DEBUG, TAG, line)
                    tracer.addTrace(line)
                    if (line.startsWith("HTTP/")) {
                        val parts = line.split(" ")
                        if (parts.isNotEmpty() && parts.size >= 2) {
                            status = Integer.valueOf(parts[1].trim())
                            tracer.addDebug(Log.DEBUG, TAG, "Status - $status")
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
                        tracer.addDebug(Log.DEBUG, TAG, "Adding to body - $body\n")
                    }
                }
                if (chunked && !body.isNullOrBlank()) {
                    val r1: Int = body.indexOf("{")
                    val r2: Int = body.lastIndexOf("}")
                    if (r1 in 1 until r2) {
                        body = body.substring(r1, r2 + 1)
                    }
                }
                tracer.addDebug(Log.DEBUG, TAG, "Status - $status [$chunked]\nBody - $body\n")
                result = ResponseHandler(status, body)
            }
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Client reading exception : ${ex.message}")
            throw ex
        }
        return result
    }

    fun post(url: URL, headers: Map<String, String>, body: String?): JSONObject {
        startConnection(url)
        val request = makePost(url, headers, body)
        val response = sendAndReceive(request)
        stopConnection()
        if (response != null) {
            return convertResultHandler(response)
        }
        return convertError("sdk_error", "internal error")
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
            if (((cookie.secure && url.protocol == "https") || (!cookie.secure)) &&
                (cookie.domain == null || (cookie.domain != null && url.host.contains(cookie.domain))) &&
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
        return sendAndReceive(url, command, cookies)
    }

    private fun startConnection(url: URL, timeoutMs: Long = 5_000) {
        var port = PORT_80
        if (url.port > 0) port = url.port
        tracer.addDebug(Log.DEBUG, TAG, "start : ${url.host} ${url.port} ${url.protocol}")
        tracer.addTrace("\nStart connection ${url.host} ${url.port} ${url.protocol} ${DateUtils.now()}\n")
        try {
            socket = if (url.protocol == "https") {
                port = PORT_443
                if (url.port > 0) port = url.port
                SSLSocketFactory.getDefault().createSocket(url.host, port)
            } else {
                Socket(url.host, port)
            }
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Cannot create socket exception : ${ex.message}")
            tracer.addTrace("Cannot create socket exception ${ex.message}\n")
            throw ex
        }
        return try {
            tracer.addDebug(
                Log.DEBUG,
                TAG,
                "Client created : ${socket.inetAddress.hostAddress} ${socket.port}"
            )
            socket.soTimeout = timeoutMs.coerceIn(1_000, GLOBAL_DEADLINE_MS).toInt()
            output = socket.getOutputStream()
            input = BufferedReader(InputStreamReader(socket.inputStream))
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
        tracer.addDebug(Log.DEBUG, TAG, "Client sending \n$message\n")
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
        tracer.addDebug(Log.DEBUG, TAG, "Response\n")
        tracer.addTrace("Response - ${DateUtils.now()} \n")
        var status: Int = 0
        var type: String = ""
        var redirectResult: ResultHandler? = null
        var bodyBegin: Boolean = false
        val bodyBuilder = StringBuilder()  // DEVX-11223: avoid O(n²) string concat
        val cookies: ArrayList<HttpCookie> = ArrayList()
        if (existingCookies != null) cookies.addAll(existingCookies)

        try {
            // DEVX-11221: Parse headers line-by-line instead of buffering the full response.
            // On a 3xx redirect, return as soon as the Location header is seen — no body read.
            var line: String? = input.readLine()
            while (line != null) {
                tracer.addTrace(line + "\n")
                when {
                    line.startsWith("HTTP/") -> {
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            status = parts[1].trim().toIntOrNull() ?: 0
                            tracer.addDebug(Log.DEBUG, TAG, "Status - $status")
                            tracer.addTrace("Status - $status ${DateUtils.now()}\n")
                        }
                    }
                    line.startsWith("Set-Cookie:", ignoreCase = true) -> {
                        val parts: List<String> = line.split("ookie:")
                        if (parts.size > 1) {
                            try {
                                for (cookie in HttpCookie.parse(parts[1])) {
                                    cookies.add(cookie)
                                    if (BuildConfig.DEBUG) tracer.addDebug(Log.DEBUG, TAG, "cookie - $cookie")
                                    tracer.addTrace("cookie - $cookie\n")
                                }
                            } catch (ex: IllegalArgumentException) {
                                tracer.addTrace("Cannot parse cookie ${parts[1]}  ${ex.message}\n")
                            }
                        }
                    }
                    line.startsWith("Location:", ignoreCase = true) -> {
                        redirectResult = parseRedirect(status, requestURL, line.trimEnd('\r'), cookies)
                        // DEVX-11221: Early return on redirect — skip downloading the body.
                        if (redirectResult != null && status in 300..399) {
                            tracer.addTrace("Redirect detected, skipping body - ${DateUtils.now()}\n")
                            return redirectResult
                        }
                    }
                    line.startsWith("Content-Type:", ignoreCase = true) -> {
                        val parts = line.split(" ")
                        if (parts.size > 1) {
                            type = parts[1].replace(";", "").trimEnd('\r')
                        }
                        tracer.addDebug(Log.DEBUG, TAG, "Type - $type\n")
                    }
                    (type == "application/json" || type == "application/hal+json" || type == "application/problem+json") && line.trimEnd('\r').isEmpty() -> {
                        bodyBegin = true
                    }
                    bodyBegin -> {
                        bodyBuilder.append(line.trimEnd('\r'))  // DEVX-11223: StringBuilder
                        if (BuildConfig.DEBUG) tracer.addDebug(Log.DEBUG, TAG, "Adding to body\n")
                    }
                }
                line = input.readLine()
            }
            val body: String? = if (bodyBegin && bodyBuilder.isNotEmpty()) bodyBuilder.toString() else null
            if (BuildConfig.DEBUG) tracer.addDebug(Log.DEBUG, TAG, "Status - $status\nBody - $body\n")
            tracer.addTrace("Status - $status ${DateUtils.now()}\nBody - $body\n")
            return redirectResult ?: if (bodyBegin && body != null) {
                ResultHandler(status, null, parseBodyIntoJSONString(body), cookies)
            } else {
                ResultHandler(status, null, null, null)
            }
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "Client reading exception : ${ex.message}")
            tracer.addTrace("Client reading exception ${ex.message}\n")
            throw ex
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
            if (!cleanRedirect.startsWith("http")) { // http & https
                return ResultHandler(httpStatus, URL(requestURL, cleanRedirect), null, cookies)
            }
            tracer.addDebug(Log.DEBUG, TAG, "Found redirect")
            tracer.addTrace("Found redirect - ${DateUtils.now()} \n")
            return ResultHandler(httpStatus, URL(cleanRedirect), null, cookies)
        }
        return null
    }

    private fun stopConnection() {
        tracer.addDebug(Log.DEBUG, TAG, "closed the connection ${socket.inetAddress.hostAddress}")
        try {
            input.close()
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
    private fun readMultipleChars(reader: BufferedReader, length: Int): String? {
        // DEVX-11222: Loop until EOF — a single read() can return a partial buffer on
        // slow cellular networks where data trickles in across multiple TCP segments.
        val sb = StringBuilder()
        val buf = CharArray(length)
        var charsRead: Int
        while (reader.read(buf, 0, length).also { charsRead = it } != -1) {
            sb.append(buf, 0, charsRead)
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
    }

    class ResultHandler(
        httpStatus: Int,
        redirect: URL?,
        body: String?,
        cookies: ArrayList<HttpCookie>?
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