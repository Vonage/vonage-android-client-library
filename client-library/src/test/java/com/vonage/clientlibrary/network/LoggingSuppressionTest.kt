package com.vonage.clientlibrary.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Regression test for issue #24 that does NOT mock android.util.Log.
 *
 * It drives the real ClientSocket request+redirect flow (which emits "CellularClient"
 * lines via tracer.addDebug calls, many of them ungated) through the real
 * TraceCollector singleton, and captures what actually reaches logcat using
 * Robolectric's ShadowLog. This guards against a future ungated log call or a
 * broken debuggable gate re-leaking data in release builds.
 *
 * Release config (console disabled, as CellularNetworkManager does for a
 * non-debuggable app) must produce zero CellularClient log lines.
 * Debuggable config (console enabled) must produce them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LoggingSuppressionTest {

    private lateinit var mockSSLSocketFactory: SSLSocketFactory

    @Before
    fun setUp() {
        ShadowLog.clear()
        mockkStatic(SSLSocketFactory::class)
        mockSSLSocketFactory = mockk()
        every { SSLSocketFactory.getDefault() } returns mockSSLSocketFactory
    }

    @After
    fun tearDown() {
        // Reset the shared singleton so console state doesn't leak into other tests.
        TraceCollector.instance.shouldLogDebugInfoToConsole(false)
        unmockkAll()
    }

    private fun makeMockSocket(responseBytes: ByteArray): SSLSocket {
        val s = mockk<SSLSocket>(relaxed = true)
        every { s.getOutputStream() } returns ByteArrayOutputStream()
        every { s.getInputStream() } returns ByteArrayInputStream(responseBytes)
        every { s.inetAddress } returns mockk(relaxed = true)
        every { s.port } returns 443
        return s
    }

    /** A 302 redirect to a different host, then a 200 — reproduces the issue's redirect chain. */
    private fun stubRedirectThen200() {
        val redirect = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: https://auth.opengateway.example.de/authorize?client_id=abc&state=xyz\r\n" +
            "Content-Length: 0\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
        val ok = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 11\r\n\r\n" +
            """{"ok":true}"""
        ).toByteArray(Charsets.UTF_8)
        every { mockSSLSocketFactory.createSocket(any<String>(), any<Int>()) } returnsMany
            listOf(makeMockSocket(redirect), makeMockSocket(ok))
    }

    private fun cellularClientLogs(): List<String> =
        ShadowLog.getLogs()
            .filter { it.tag == "CellularClient" }
            .map { it.msg }

    @Test
    fun `release build emits zero CellularClient logs through the real logging sink`() {
        // Simulate a non-debuggable (release) app — this is exactly what
        // CellularNetworkManager.init does when FLAG_DEBUGGABLE is false.
        TraceCollector.instance.shouldLogDebugInfoToConsole(false)
        stubRedirectThen200()

        ClientSocket().open(URL("https://api.example.com/verify"), emptyMap(), null, 5)

        val leaked = cellularClientLogs()
        assertEquals("Release build must not emit any CellularClient logs, but did: $leaked", 0, leaked.size)
    }

    @Test
    fun `debuggable build emits CellularClient logs through the real logging sink`() {
        TraceCollector.instance.shouldLogDebugInfoToConsole(true)
        stubRedirectThen200()

        ClientSocket().open(URL("https://api.example.com/verify"), emptyMap(), null, 5)

        assertTrue(
            "Debuggable build should emit CellularClient logs, but none were captured",
            cellularClientLogs().isNotEmpty()
        )
    }
}
