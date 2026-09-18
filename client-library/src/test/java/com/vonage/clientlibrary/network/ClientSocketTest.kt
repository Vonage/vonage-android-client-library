package com.vonage.clientlibrary.network

import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tests for ClientSocket exercising real parsing code paths via a fake SSLSocket.
 *
 * Each test injects a fake SSLSocket whose InputStream returns controlled HTTP response bytes,
 * so the assertions cover the actual production parsing logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClientSocketTest {

    private lateinit var mockTracer: TraceCollector
    private lateinit var mockSSLSocketFactory: SSLSocketFactory
    private lateinit var mockSSLSocket: SSLSocket

    @Before
    fun setUp() {
        mockTracer = mockk(relaxed = true)
        mockkStatic(SSLSocketFactory::class)
        mockSSLSocketFactory = mockk()
        mockSSLSocket = mockk(relaxed = true)
        every { SSLSocketFactory.getDefault() } returns mockSSLSocketFactory
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Wire up the mock SSLSocket to return the given HTTP response bytes. */
    private fun stubResponse(responseBytes: ByteArray) {
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(responseBytes)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443
    }

    /**
     * Create a fresh mock SSLSocket that returns the given response bytes.
     * Each call to createSocket should return a distinct socket so that
     * separate connections get separate InputStreams.
     */
    private fun makeMockSocket(responseBytes: ByteArray): SSLSocket {
        val s = mockk<SSLSocket>(relaxed = true)
        every { s.getOutputStream() } returns ByteArrayOutputStream()
        every { s.getInputStream() } returns ByteArrayInputStream(responseBytes)
        every { s.inetAddress } returns mockk(relaxed = true)
        every { s.port } returns 443
        return s
    }

    private fun httpResponse(
        status: Int = 200,
        headers: String = "",
        body: String = "",
        contentType: String = "application/json"
    ): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status OK\r\n")
        if (body.isNotEmpty()) {
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        if (headers.isNotEmpty()) sb.append(headers)
        sb.append("\r\n")
        sb.append(body)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Serves one header-only response, then fails rather than returning EOF. This models a peer
     * that keeps a bodyless HTTP/1.1 response connection open and proves the parser does not wait
     * for connection close to delimit a response that cannot contain a body.
     */
    private class HeaderOnlyResponseInputStream(private val response: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= response.size) throw IOException("Bodyless response must not be read past headers")
            return response[index++].toInt() and 0xFF
        }
    }

    /**
     * Serves [header], then [bodyLength] generated filler bytes, then [trailer], without ever
     * holding the body in memory. Lets a body far larger than the heap be streamed, so a drain that
     * accumulates bytes fails with OutOfMemoryError while a true drain runs in constant memory.
     */
    private class LargeBodyInputStream(
        private val header: ByteArray,
        private val bodyLength: Long,
        private val trailer: ByteArray
    ) : InputStream() {
        private var headerPos = 0
        private var bodyPos = 0L
        private var trailerPos = 0

        override fun read(): Int {
            if (headerPos < header.size) return header[headerPos++].toInt() and 0xFF
            if (bodyPos < bodyLength) { bodyPos++; return FILLER.toInt() }
            if (trailerPos < trailer.size) return trailer[trailerPos++].toInt() and 0xFF
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (headerPos < header.size) {
                val n = minOf(len, header.size - headerPos)
                System.arraycopy(header, headerPos, b, off, n)
                headerPos += n
                return n
            }
            if (bodyPos < bodyLength) {
                val n = minOf(len.toLong(), bodyLength - bodyPos).toInt()
                b.fill(FILLER, off, off + n)
                bodyPos += n
                return n
            }
            if (trailerPos < trailer.size) {
                val n = minOf(len, trailer.size - trailerPos)
                System.arraycopy(trailer, trailerPos, b, off, n)
                trailerPos += n
                return n
            }
            return -1
        }

        private companion object {
            const val FILLER: Byte = 'x'.code.toByte()
        }
    }

    /** Serves [prefix], then throws [failure] instead of returning EOF. */
    private class ErrorAfterPrefixInputStream(
        private val prefix: ByteArray,
        private val failure: Error
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index < prefix.size) return prefix[index++].toInt() and 0xFF
            throw failure
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `open returns http_status and response_body for 200 JSON response`() {
        stubResponse(httpResponse(200, body = """{"ok":true}"""))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open does not wait for EOF after a persistent 204 response`() {
        val response = "HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.UTF_8)
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns HeaderOnlyResponseInputStream(response)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(204, result.getInt("http_status"))
    }

    @Test
    fun `open skips an interim response and returns its final response`() {
        val interim = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8)
        stubResponse(interim + httpResponse(200, body = """{"ok":true}"""))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open rejects a truncated Content-Length redirect instead of reusing its socket`() {
        val truncatedRedirect = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "xy"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(truncatedRedirect)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        verify { mockSSLSocket.close() }
    }

    @Test
    fun `open rejects a truncated chunked response`() {
        val truncatedChunk = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "b\r\n" +
            "{\"ok\":"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(truncatedChunk)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `post consumes an informational response and returns the final response`() {
        stubResponse(
            "HTTP/1.1 100 Continue\r\nX-Interim: ignored\r\n\r\n".toByteArray(Charsets.UTF_8) +
                httpResponse(200, body = """{"ok":true}""")
        )

        val cs = ClientSocket(mockTracer)
        val result = cs.post(URL("https://api.example.com/"), emptyMap(), "{}")

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `post rejects an informational response without a final response`() {
        stubResponse("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.post(URL("https://api.example.com/"), emptyMap(), "{}")

        assertEquals("sdk_connection_error", result.getString("error"))
        assertFalse("Interim status must not be returned as final", result.has("http_status"))
    }

    @Test
    fun `post rejects a 101 protocol upgrade`() {
        stubResponse("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n".toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.post(URL("https://api.example.com/"), emptyMap(), "{}")

        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open rejects EOF before final response headers complete`() {
        stubResponse("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n".toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
        assertFalse("Incomplete response must not expose an HTTP status", result.has("http_status"))
    }

    @Test
    fun `open rejects a Location header without a status line`() {
        stubResponse("Location: https://api.example.com/final\r\n\r\n".toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open does not follow a non-HTTPS redirect`() {
        val response = (
            "HTTP/1.1 302 Found\r\n" +
                "Location: ftp://api.example.com:443/final\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
        stubResponse(response)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertEquals(302, result.getInt("http_status"))
    }

    @Test
    fun `open preserves UTF-8 split across close-delimited read blocks`() {
        val prefix = "{\"v\":\""
        val filler = "a".repeat(65_536 - prefix.toByteArray(Charsets.UTF_8).size - 1)
        val expected = "${filler}é"
        val body = "$prefix$expected\"}"
        // The first byte of é is the final byte of the first 65,536-byte read.
        assertEquals(65_537, (prefix + expected).toByteArray(Charsets.UTF_8).size)
        val response = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
            ).toByteArray(Charsets.UTF_8)
        stubResponse(response)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(expected, result.getJSONObject("response_body").getString("v"))
    }

    @Test
    fun `open does not retry a VM Error from a reused connection`() {
        val redirect = (
            "HTTP/1.1 302 Found\r\n" +
                "Location: https://api.example.com/final\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns
            ErrorAfterPrefixInputStream(redirect, OutOfMemoryError("simulated VM failure"))
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val thrown = assertThrows(OutOfMemoryError::class.java) {
            cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)
        }

        assertEquals("simulated VM failure", thrown.message)
        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
    }

    @Test
    fun `open returns sdk_connection_error when socket factory throws`() {
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } throws
            RuntimeException("Network unreachable")

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
        assertTrue(result.getString("error_description").contains("Network unreachable"))
    }

    @Test
    fun `open returns sdk_connection_error and closes socket when exception occurs after connection`() {
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } throws RuntimeException("Read failed")
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
        // Socket close should have been attempted
        verify { mockSSLSocket.close() }
    }

    @Test
    fun `open follows a chain that uses its full redirect budget`() {
        // A chain that completes within the limit must succeed. Counting requests rather than
        // pending redirects made a successful two-redirect chain report "Too many redirects".
        val redirect = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/next\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(redirect + redirect + httpResponse(200, body = """{"ok":true}"""))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 2)

        assertFalse("Chain within the limit must not error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open returns sdk_redirect_error one redirect past the limit`() {
        val redirect = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/next\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(redirect + redirect + redirect + redirect)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 2)

        assertEquals("sdk_redirect_error", result.getString("error"))
    }

    @Test
    fun `open returns a direct response when no redirects are allowed`() {
        stubResponse(httpResponse(200, body = """{"ok":true}"""))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 0)

        assertFalse("A direct response must not be treated as a redirect: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
    }

    @Test
    fun `open returns sdk_redirect_error when redirect limit exceeded`() {
        // Use same-host redirects so all responses go through one socket connection.
        // Concatenate enough redirect responses to exceed the limit.
        val singleRedirect = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/redirect\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        // 7 redirects in sequence — more than maxRedirectCount=5
        val combined = (1..7).fold(ByteArray(0)) { acc, _ -> acc + singleRedirect }

        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(combined)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        assertTrue("Expected error in result: $result", result.has("error"))
        assertEquals("sdk_redirect_error", result.getString("error"))
    }

    @Test
    fun `open follows redirect and returns final response`() {
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://other.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"done":true}""")

        // First createSocket → redirect response; second → final response
        val responses = listOf(redirectResponse, finalResponse).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            makeMockSocket(if (responses.hasNext()) responses.next() else finalResponse)
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        assertFalse(result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("done"))
    }

    @Test
    fun `open blocks HTTPS to HTTP redirect downgrade`() {
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: http://evil.example.com/\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(redirectResponse)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        // Downgrade blocked → parseRedirect returns null → no redirect URL → loop ends
        // Result has no redirect_error; it falls through to sdk_error (no body)
        assertFalse("Should not follow http:// redirect", "sdk_redirect_error" == result.optString("error"))
    }

    @Test
    fun `open returns non-JSON body as response_raw_body`() {
        // A valid JSON-ish body that isn't strict JSON — parseBodyIntoJSONString extracts
        // the outer braces, then convertResultHandler tries JSONObject which may fail.
        // Use a body that is valid JSON to confirm the happy path first; non-JSON is
        // handled at a layer below open() and surfaces as sdk_connection_error.
        val nonJsonBody = "plain text response"
        val response = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${nonJsonBody.length}\r\n" +
            "\r\n" +
            nonJsonBody
        ).toByteArray(Charsets.UTF_8)
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(response)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        // parseBodyIntoJSONString throws on no '{' → caught as sdk_connection_error
        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open returns response_raw_body when body has braces but is not valid JSON`() {
        // Body has braces so parseBodyIntoJSONString succeeds, but JSONObject constructor throws
        val nonJsonBody = "{not valid json}"
        val response = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${nonJsonBody.toByteArray().size}\r\n" +
            "\r\n" +
            nonJsonBody
        ).toByteArray(Charsets.UTF_8)
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(response)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse(result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertEquals("{not valid json}", result.getString("response_raw_body"))
    }

    @Test
    fun `open opens a fresh connection for same-authority redirect after Connection close`() {
        // Even though the SDK now requests keep-alive, a redirect whose response carries
        // "Connection: close" tells us the peer is tearing the socket down. Reusing it for the
        // same-authority redirect would read EOF, which surfaced as {"http_status": 0}, so we
        // must open a fresh connection.
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")

        // Each connection gets its own stream. The first socket serves the redirect and then
        // yields EOF — exactly what a server honouring "Connection: close" leaves behind. If the
        // redirect reuses that socket it reads nothing and no status line is ever parsed.
        val opened = mutableListOf<SSLSocket>()
        val responses = listOf(redirectResponse, finalResponse).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            makeMockSocket(if (responses.hasNext()) responses.next() else ByteArray(0))
                .also { opened.add(it) }
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        assertEquals("Same-host redirect must get a fresh connection", 2, opened.size)
        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open reuses one connection across consecutive same-host hops after a cross-host redirect`() {
        // Models the production silent-auth chain shape (DEVX-11219), extended with a fourth hop:
        //
        //   1. api-ap  /v2/verify/../silent-auth/redirect  302 -> api-eu   (cross-host)
        //   2. api-eu  /oauth2/auth                        301 -> api-eu   (same host)
        //   3. api-eu  /v0.1/silent-auth/redirect          302 -> api-eu   (same host)
        //   4. api-eu  /final                              200
        //
        // Only the host change may open a second connection: hops 2, 3 and 4 must all travel over
        // the same socket, proving reuse holds repeatedly rather than just once.
        fun redirect(status: Int, location: String) = (
            "HTTP/1.1 $status Found\r\n" +
            "Location: $location\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        val apStream = ByteArrayInputStream(redirect(302, "https://api-eu.example.com/oauth2/auth"))
        // A keep-alive server answers all three same-host hops back-to-back on one connection.
        val euStream = ByteArrayInputStream(
            redirect(301, "https://api-eu.example.com/v0.1/silent-auth/redirect") +
            redirect(302, "https://api-eu.example.com/final") +
            httpResponse(200, body = """{"ok":true}""")
        )
        val apOut = ByteArrayOutputStream()
        val euOut = ByteArrayOutputStream()

        val apSocket = mockk<SSLSocket>(relaxed = true)
        every { apSocket.getInputStream() } returns apStream
        every { apSocket.getOutputStream() } returns apOut
        every { apSocket.inetAddress } returns mockk(relaxed = true)
        every { apSocket.port } returns 443

        val euSocket = mockk<SSLSocket>(relaxed = true)
        every { euSocket.getInputStream() } returns euStream
        every { euSocket.getOutputStream() } returns euOut
        every { euSocket.inetAddress } returns mockk(relaxed = true)
        every { euSocket.port } returns 443

        val connectedHosts = mutableListOf<String>()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            val host = firstArg<String>()
            connectedHosts.add(host)
            if (host == "api-ap.example.com") apSocket else euSocket
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(
            URL("https://api-ap.example.com/v2/verify/abc/silent-auth/redirect"),
            emptyMap(),
            null,
            10
        )

        // One handshake per distinct authority: the cross-host hop, then a single api-eu connection.
        assertEquals(
            "Only the host change may open a new connection",
            listOf("api-ap.example.com", "api-eu.example.com"),
            connectedHosts
        )

        // All three api-eu hops must have been written to the one reused socket.
        val euRequests = euOut.toString(Charsets.UTF_8.name())
        assertEquals(
            "All three same-host hops should be sent on the reused connection: $euRequests",
            3,
            Regex("^GET ", RegexOption.MULTILINE).findAll(euRequests).count()
        )
        assertTrue("Hop 2 missing", euRequests.contains("GET /oauth2/auth"))
        assertTrue("Hop 3 missing", euRequests.contains("GET /v0.1/silent-auth/redirect"))
        assertTrue("Hop 4 missing", euRequests.contains("GET /final"))
        assertFalse("Reused hops must not force a close", euRequests.contains("Connection: close"))

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open reports sdk_connection_error when peer closes without responding`() {
        // Zero bytes back: readHttpLine hits EOF on its first call, so the parse loop never runs
        // and no status line is read. That must not leak the uninitialised status 0 into the
        // success shape, where a caller checking for "error" would treat it as a good response.
        stubResponse(ByteArray(0))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
        assertFalse("Must not report a phantom http_status", result.has("http_status"))
    }

    @Test
    fun `open does not reuse connection when response sends Connection close with a body`() {
        // A same-authority redirect that carries both a drainable body and "Connection: close".
        // The drain succeeds, so the old mustClose heuristic (contentLength < 0) would have kept
        // the socket; the response header has to be what forces the reconnect.
        val body = "redirecting"
        val redirectResponse = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            body
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")

        val responses = listOf(redirectResponse, finalResponse).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            makeMockSocket(if (responses.hasNext()) responses.next() else ByteArray(0))
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        verify(exactly = 2) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertEquals(200, result.getInt("http_status"))
    }

    @Test
    fun `open reuses one connection for same-host redirect when server keeps it alive`() {
        // DEVX-11219: with keep-alive requested and a redirect that leaves the socket open
        // (framed by Content-Length, no "Connection: close"), the same-authority redirect must
        // reuse the existing socket instead of opening a new one.
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")
        // One socket serves both hops back-to-back, as a keep-alive server would.
        stubResponse(redirectResponse + finalResponse)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open requests keep-alive explicitly on GET requests`() {
        // Omitting "Connection: close" is enough for HTTP/1.1, but an HTTP/1.0 peer only keeps the
        // socket open when the client sends the token, so assert it is actually present.
        val outputStream = ByteArrayOutputStream()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns outputStream
        every { mockSSLSocket.getInputStream() } returns
            ByteArrayInputStream(httpResponse(200, body = """{"ok":true}"""))
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        val sentRequest = outputStream.toString(Charsets.UTF_8.name())
        assertTrue("GET must request keep-alive: $sentRequest", sentRequest.contains("Connection: keep-alive"))
        assertFalse("GET request must not force Connection: close", sentRequest.contains("Connection: close"))
    }

    @Test
    fun `open decodes a chunked final response`() {
        // A keep-alive server frames the body with chunked encoding instead of Content-Length.
        // The reader must terminate on the zero-length chunk rather than waiting for EOF.
        val chunked = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "b\r\n" +               // 0xb = 11 bytes
            "{\"ok\":true}\r\n" +
            "0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(chunked)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open reconnects for same-host redirect when body cannot be framed`() {
        // A redirect with neither Content-Length nor chunked framing can't be drained safely, so
        // the connection must not be reused even though the authority matches and no Connection:
        // close was sent.
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")

        val responses = listOf(redirectResponse, finalResponse).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            makeMockSocket(if (responses.hasNext()) responses.next() else ByteArray(0))
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        verify(exactly = 2) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertEquals(200, result.getInt("http_status"))
    }

    @Test
    fun `open retries on a new connection when the reused socket was closed by the peer`() {
        // A peer may close an idle kept-alive socket at any time. The redirect response invites
        // reuse (HTTP/1.1, framed, no "Connection: close"), but the socket is then dead. That race
        // must be recovered transparently instead of surfacing sdk_connection_error.
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        // First socket serves the redirect then yields EOF; the retry must use a second socket.
        val responses = listOf(redirectResponse, httpResponse(200, body = """{"ok":true}""")).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            makeMockSocket(if (responses.hasNext()) responses.next() else ByteArray(0))
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        verify(exactly = 2) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertFalse("Stale reused socket must not fail the request: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open does not reuse a connection for an HTTP 1_0 response without keep-alive`() {
        // HTTP/1.0 is not persistent unless the peer opts in, so this socket must not be reused
        // even though the authority matches and no "Connection: close" was sent. The first socket
        // also offers a second response: reading it would prove we wrongly reused the connection.
        val legacyRedirect = (
            "HTTP/1.0 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val staleSocket = legacyRedirect + httpResponse(200, body = """{"from":"reused"}""")
        val freshSocket = httpResponse(200, body = """{"from":"fresh"}""")

        val responses = listOf(staleSocket, freshSocket).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            makeMockSocket(if (responses.hasNext()) responses.next() else ByteArray(0))
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        assertEquals("fresh", result.getJSONObject("response_body").getString("from"))
    }

    @Test
    fun `open reuses a connection for an HTTP 1_0 response that opts into keep-alive`() {
        val legacyRedirect = (
            "HTTP/1.0 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(legacyRedirect + httpResponse(200, body = """{"ok":true}"""))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open rejects an oversized chunk size without exhausting memory`() {
        // The chunk size is peer-controlled. Allocating from it would throw OutOfMemoryError, which
        // is an Error and would escape the SDK's Exception handling into the caller.
        val hostileChunk = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "7fffffff\r\n" +
            "{}"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(hostileChunk)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open parses a response that terminates header lines with bare LF`() {
        // Without CRLF the line reader used to consume the whole response as one line and relied on
        // EOF to stop. On a kept-alive socket there is no EOF, so this would stall until timeout.
        val body = """{"ok":true}"""
        val bareLf = (
            "HTTP/1.1 200 OK\n" +
            "Content-Type: application/json\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\n" +
            "\n" +
            body
        ).toByteArray(Charsets.UTF_8)
        stubResponse(bareLf)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open uses a fresh connection when a redirect body is delimited by connection close`() {
        // A non-3xx status carrying Location, with no Content-Length and no chunked framing: the
        // body ends at EOF, so the socket is spent. Asserting only the final 200 is not enough —
        // that passes even when the socket is wrongly reused and the stale-connection retry covers
        // for it. Assert the spent socket is never written to a second time.
        val unframed = (
            "HTTP/1.1 200 OK\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Type: text/html\r\n" +
            "\r\n" +
            "<html>redirecting</html>"
        ).toByteArray(Charsets.UTF_8)

        val firstOut = ByteArrayOutputStream()
        val firstSocket = mockk<SSLSocket>(relaxed = true)
        every { firstSocket.getInputStream() } returns ByteArrayInputStream(unframed)
        every { firstSocket.getOutputStream() } returns firstOut
        every { firstSocket.inetAddress } returns mockk(relaxed = true)
        every { firstSocket.port } returns 443

        val sockets = listOf(firstSocket, makeMockSocket(httpResponse(200, body = """{"ok":true}"""))).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            if (sockets.hasNext()) sockets.next() else makeMockSocket(ByteArray(0))
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        val firstRequests = firstOut.toString(Charsets.UTF_8.name())
        assertEquals(
            "Spent connection must not be written to again: $firstRequests",
            1,
            Regex("^GET ", RegexOption.MULTILINE).findAll(firstRequests).count()
        )
        verify { firstSocket.close() }
        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open reports an error when the peer closes after an informational response`() {
        // An interim 1xx is not a final response. Reporting it as one would hand the caller
        // {"http_status":100} with no error key — the silent success shape this SDK must never emit.
        stubResponse("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
        assertFalse("Interim status must not be returned as a final response", result.has("http_status"))
    }

    @Test
    fun `open closes the connection when the redirect limit is exceeded`() {
        // The pending redirect keeps the socket open for a hop that never runs, so the error path
        // has to close it or every over-limit chain leaks a socket.
        val redirect = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/next\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(redirect + redirect + redirect + redirect)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 2)

        assertEquals("sdk_redirect_error", result.getString("error"))
        verify { mockSSLSocket.close() }
    }

    @Test
    fun `open drains a chunked redirect body and continues on the same connection`() {
        // Draining must consume exactly the chunked frame: reading too little or too much would
        // desynchronise the stream and garble the next response on the reused socket.
        val chunkedRedirect = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "10\r\n" +
            "0123456789abcdef\r\n" +
            "5\r\n" +
            "12345\r\n" +
            "0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(chunkedRedirect + httpResponse(200, body = """{"ok":true}"""))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    // ------------------------------------------------------------------
    // Drain behaviour, retry bounds, and framing edge cases
    // ------------------------------------------------------------------

    @Test
    fun `open drains a huge redirect body without retaining it`() {
        // The drained body is discarded, so it must never be accumulated: buffering a
        // peer-controlled length would exhaust the heap with an OutOfMemoryError that the SDK's
        // Exception handling cannot catch. 1.5 GB is streamed but never held.
        val bodyLength = 1_500_000_000L
        val header = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: $bodyLength\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns LargeBodyInputStream(
            header,
            bodyLength,
            httpResponse(200, body = """{"ok":true}""")
        )
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open retries a stale connection only once`() {
        // The retry must be bounded: a fresh connection that also fails is a real error, not another
        // stale-socket race, so it must not loop.
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        // Socket 1: redirect then EOF. Socket 2 (the retry): EOF immediately.
        val responses = listOf(redirectResponse, ByteArray(0)).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            makeMockSocket(if (responses.hasNext()) responses.next() else ByteArray(0))
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        verify(exactly = 2) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open does not retry a failure on a fresh connection`() {
        // Only a reused socket earns a retry. A fresh connection returning nothing is a genuine
        // failure and must be reported without a second attempt.
        stubResponse(ByteArray(0))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open uses a fresh connection when a non-redirect response sends Connection close`() {
        // The close decision must survive the redirectResult path for every reason, not just an
        // unframed body: here a framed 200 carrying Location also sends "Connection: close".
        val closing = (
            "HTTP/1.1 200 OK\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        val firstOut = ByteArrayOutputStream()
        val firstSocket = mockk<SSLSocket>(relaxed = true)
        every { firstSocket.getInputStream() } returns ByteArrayInputStream(closing)
        every { firstSocket.getOutputStream() } returns firstOut
        every { firstSocket.inetAddress } returns mockk(relaxed = true)
        every { firstSocket.port } returns 443

        val sockets = listOf(firstSocket, makeMockSocket(httpResponse(200, body = """{"ok":true}"""))).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            if (sockets.hasNext()) sockets.next() else makeMockSocket(ByteArray(0))
        }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        val firstRequests = firstOut.toString(Charsets.UTF_8.name())
        assertEquals(
            "Closed connection must not be written to again: $firstRequests",
            1,
            Regex("^GET ", RegexOption.MULTILINE).findAll(firstRequests).count()
        )
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open rejects a non-hexadecimal chunk size`() {
        stubResponse((
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "zz\r\n" +
            "{}\r\n"
        ).toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open rejects a chunk that is not followed by its delimiter`() {
        // Chunk data must be followed by CRLF. Without that check the stream would desynchronise and
        // the next read on a reused socket would parse garbage.
        stubResponse((
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "2\r\n" +
            "{}XX" +
            "0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open handles chunk extensions and trailer headers`() {
        val chunked = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "b;name=value\r\n" +
            "{\"ok\":true}\r\n" +
            "0\r\n" +
            "X-Checksum: abc123\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(chunked)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open parses a chunked body framed with bare LF`() {
        val chunked = (
            "HTTP/1.1 200 OK\n" +
            "Content-Type: application/json\n" +
            "Transfer-Encoding: chunked\n" +
            "\n" +
            "b\n" +
            "{\"ok\":true}\n" +
            "0\n" +
            "\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(chunked)

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open reads a body larger than the read buffer with multibyte characters intact`() {
        // Exercises the multi-read loop and proves the body is decoded from bytes, not assembled
        // per line or per read: the value spans several 8 KB reads and its byte length differs from
        // its character length.
        val value = "é".repeat(6000)
        val body = """{"v":"$value"}"""
        assertTrue("Body must exceed the read buffer", body.toByteArray(Charsets.UTF_8).size > 8192)
        stubResponse(httpResponse(200, body = body))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(value, result.getJSONObject("response_body").getString("v"))
    }

    @Test
    fun `open does not wait for EOF after a persistent 304 response`() {
        val response = "HTTP/1.1 304 Not Modified\r\n\r\n".toByteArray(Charsets.UTF_8)
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns HeaderOnlyResponseInputStream(response)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(304, result.getInt("http_status"))
    }

    @Test
    fun `open does not wait for EOF after a persistent 205 response`() {
        val response = "HTTP/1.1 205 Reset Content\r\n\r\n".toByteArray(Charsets.UTF_8)
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns HeaderOnlyResponseInputStream(response)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(205, result.getInt("http_status"))
    }

    @Test
    fun `open discards interim response metadata before parsing the final response`() {
        // An interim response may carry headers. Leaving its Content-Length and Location active
        // would frame the final response with the wrong length and route it to the interim target.
        val interim = (
            "HTTP/1.1 103 Early Hints\r\n" +
            "Content-Length: 9999\r\n" +
            "Location: https://other.example.com/wrong\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        stubResponse(interim + httpResponse(200, body = """{"ok":true}"""))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        // A second connection would mean the interim Location was followed as a redirect.
        verify(exactly = 1) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
        assertFalse("Should not contain error: $result", result.has("error"))
        assertEquals(200, result.getInt("http_status"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open rejects a 101 protocol upgrade`() {
        // After 101 the stream belongs to another protocol. This client never requests an upgrade,
        // so continuing to parse would read non-HTTP bytes as headers or block until the deadline.
        stubResponse((
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "\r\n" +
            "\u0081\u0085not-http-bytes"
        ).toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open rejects an unsupported x-chunked transfer coding`() {
        // Transfer-Encoding is authoritative over Content-Length. "x-chunked" is a different and
        // unsupported coding, so it must not fall through to the Content-Length body.
        val body = """{"ok":true}"""
        stubResponse((
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: x-chunked\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "\r\n" +
            body
        ).toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open rejects chunked when it is not the final transfer coding`() {
        stubResponse((
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked, gzip\r\n" +
            "\r\n" +
            "b\r\n{\"ok\":true}\r\n0\r\n\r\n"
        ).toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("sdk_connection_error", result.getString("error"))
    }

    @Test
    fun `open uses chunked framing instead of a conflicting Content-Length`() {
        // A proxy may leave both headers. Transfer-Encoding is authoritative, so the deliberately
        // wrong Content-Length must not truncate the chunked body or desynchronise the stream.
        stubResponse((
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Length: 1\r\n" +
            "\r\n" +
            "b\r\n{\"ok\":true}\r\n0\r\n\r\n"
        ).toByteArray(Charsets.UTF_8))

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse("Should not contain error: $result", result.has("error"))
        assertTrue(result.getJSONObject("response_body").getBoolean("ok"))
    }

    @Test
    fun `open drains a huge non-JSON response body without retaining it`() {
        // Only JSON bodies are surfaced, so a non-JSON body must be drained in constant memory
        // rather than buffered and then discarded.
        val bodyLength = 1_500_000_000L
        val header = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html\r\n" +
            "Content-Length: $bodyLength\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns
            LargeBodyInputStream(header, bodyLength, ByteArray(0))
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals(200, result.getInt("http_status"))
        assertFalse("A non-JSON body must not be surfaced", result.has("response_body"))
    }

    @Test
    fun `open limits the reuse probe timeout so budget remains for a retry`() {
        // A half-open socket can blackhole packets instead of returning EOF. Spending the whole
        // deadline on the reuse attempt would leave nothing for the retry on a fresh connection.
        val timeouts = mutableListOf<Int>()
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns
            ByteArrayInputStream(redirectResponse + httpResponse(200, body = """{"ok":true}"""))
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443
        every { mockSSLSocket.soTimeout = any() } answers { timeouts.add(firstArg()) }

        val cs = ClientSocket(mockTracer)
        val result = cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        assertEquals(200, result.getInt("http_status"))
        assertTrue("Expected a connect timeout and a reuse probe timeout: $timeouts", timeouts.size >= 2)
        val connectTimeout = timeouts.first()
        val probeTimeout = timeouts.last()
        assertTrue(
            "Reuse probe must not consume the whole deadline (connect=$connectTimeout, probe=$probeTimeout)",
            probeTimeout <= connectTimeout / 2 + 1_000
        )
    }

    @Test
    fun `open does not reuse connection when redirect changes port`() {        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com:8443/other\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")

        val responses = listOf(redirectResponse, finalResponse).iterator()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } answers {
            makeMockSocket(if (responses.hasNext()) responses.next() else finalResponse)
        }

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        // Different authority (port changed) → two separate connections
        verify(exactly = 2) { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) }
    }

    @Test
    fun `parseRedirect returns null for HTTPS to HTTP downgrade`() {
        val cs = ClientSocket(mockTracer)
        val result = cs.parseRedirect(
            301,
            URL("https://api.example.com/"),
            "Location: http://evil.example.com/",
            null
        )
        assertNull("Downgrade should be blocked", result)
    }

    @Test
    fun `parseRedirect handles relative redirect`() {
        val cs = ClientSocket(mockTracer)
        val result = cs.parseRedirect(
            302,
            URL("https://api.example.com/old"),
            "Location: /new",
            null
        )
        assertNotNull(result)
        assertEquals("https://api.example.com/new", result!!.getRedirect()?.toString())
    }

    @Test
    fun `parseBodyIntoJSONString extracts JSON object from body string`() {
        val cs = ClientSocket(mockTracer)
        val json = cs.parseBodyIntoJSONString("""prefix{"key":"value"}suffix""")
        assertEquals("""{"key":"value"}""", json)
    }

    @Test
    fun `open sends cookie on redirect for exact domain match`() {
        // First response: sets a cookie with exact domain, then redirects to same host
        val redirect = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Set-Cookie: session=abc; Domain=api.example.com\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")
        val combined = redirect + finalResponse

        val outputStream = ByteArrayOutputStream()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns outputStream
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(combined)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        val sentRequests = outputStream.toString(Charsets.UTF_8)
        assertTrue("Cookie header should be sent on redirect", sentRequests.contains("Cookie: session=abc"))
    }

    @Test
    fun `open sends cookie on redirect for leading-dot domain`() {
        // Domain=.example.com should match api.example.com after leading-dot normalization
        val redirect = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Set-Cookie: session=xyz; Domain=.example.com\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")
        val combined = redirect + finalResponse

        val outputStream = ByteArrayOutputStream()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns outputStream
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(combined)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        val sentRequests = outputStream.toString(Charsets.UTF_8)
        assertTrue("Cookie with leading-dot domain should be sent on redirect", sentRequests.contains("Cookie: session=xyz"))
    }

    @Test
    fun `open does not send cookie for mismatched domain`() {
        // Cookie with domain=other.example.com should not be sent to api.example.com
        val redirect = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Set-Cookie: session=nope; Domain=other.example.com\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")
        val combined = redirect + finalResponse

        val outputStream = ByteArrayOutputStream()
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns outputStream
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(combined)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        val sentRequests = outputStream.toString(Charsets.UTF_8)
        assertFalse("Cookie for wrong domain should not be sent", sentRequests.contains("Cookie: session=nope"))
    }

    // ------------------------------------------------------------------
    // DEVX-10955: Operator tracking headers
    // ------------------------------------------------------------------

    @Test
    fun `open captures Orange trace ID header`() {
        stubResponse(httpResponse(200,
            headers = "X-Orange-Trace-Id: orange-abc-123\r\n",
            body = """{"ok":true}"""
        ))

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("orange-abc-123", cs.lastOperatorTrackingHeaders["X-Orange-Trace-Id"])
    }

    @Test
    fun `open captures Vodafone trace ID header`() {
        stubResponse(httpResponse(200,
            headers = "X-VIG-Trace-Id: vodafone-xyz-789\r\n",
            body = """{"ok":true}"""
        ))

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("vodafone-xyz-789", cs.lastOperatorTrackingHeaders["X-VIG-Trace-Id"])
    }

    @Test
    fun `open captures multiple operator tracking headers`() {
        stubResponse(httpResponse(200,
            headers = "X-Orange-Trace-Id: orange-111\r\nX-VIG-Trace-Id: voda-222\r\n",
            body = """{"ok":true}"""
        ))

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertEquals("orange-111", cs.lastOperatorTrackingHeaders["X-Orange-Trace-Id"])
        assertEquals("voda-222", cs.lastOperatorTrackingHeaders["X-VIG-Trace-Id"])
    }

    @Test
    fun `open ignores non-tracking headers and does not include them in tracking map`() {
        stubResponse(httpResponse(200,
            headers = "X-Custom-Header: should-be-ignored\r\n",
            body = """{"ok":true}"""
        ))

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertFalse(cs.lastOperatorTrackingHeaders.containsKey("X-Custom-Header"))
    }

    @Test
    fun `lastOperatorTrackingHeaders is empty when no tracking headers present`() {
        stubResponse(httpResponse(200, body = """{"ok":true}"""))

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        assertTrue(cs.lastOperatorTrackingHeaders.isEmpty())
    }

    @Test
    fun `operator tracking header matching is case-insensitive`() {
        stubResponse(httpResponse(200,
            headers = "x-orange-trace-id: lower-case-value\r\n",
            body = """{"ok":true}"""
        ))

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/"), emptyMap(), null, 5)

        // The key is stored as it appears in the response, value should be captured
        val keys = cs.lastOperatorTrackingHeaders.keys.map { it.lowercase() }
        assertTrue(keys.contains("x-orange-trace-id"))
    }
}
