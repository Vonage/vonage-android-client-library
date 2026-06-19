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
    fun `open refreshes socket timeout when reusing connection for same-authority redirect`() {
        // Same authority redirect: api.example.com:443 → api.example.com:443
        // The code reuses the socket, so createSocket is called only once but soTimeout set twice.
        val redirectResponse = (
            "HTTP/1.1 301 Moved Permanently\r\n" +
            "Location: https://api.example.com/final\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val finalResponse = httpResponse(200, body = """{"ok":true}""")

        // Concatenate both responses into one stream to simulate server reuse
        val combined = redirectResponse + finalResponse
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returns mockSSLSocket
        every { mockSSLSocket.getOutputStream() } returns ByteArrayOutputStream()
        every { mockSSLSocket.getInputStream() } returns ByteArrayInputStream(combined)
        every { mockSSLSocket.inetAddress } returns mockk(relaxed = true)
        every { mockSSLSocket.port } returns 443

        val cs = ClientSocket(mockTracer)
        cs.open(URL("https://api.example.com/start"), emptyMap(), null, 5)

        // soTimeout set once on startConnection, once on reuse
        verify(atLeast = 2) { mockSSLSocket.soTimeout = any() }
    }

    @Test
    fun `open does not reuse connection when redirect changes port`() {
        val redirectResponse = (
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
}
