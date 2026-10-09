package com.vonage.clientlibrary.network

import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * DEVX-11635 / GitHub #28: exercises the real (unmocked) SSL socket against a local server that
 * accepts the TCP connection but never answers the TLS ClientHello — the "network accepts but
 * never responds" case. The request must fail with sdk_tls_handshake_timeout within the
 * configured window instead of blocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HandshakeTimeoutTest {

    private lateinit var server: ServerSocket
    private val accepted = mutableListOf<Socket>()

    @Before
    fun setUp() {
        server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                // Accept and hold connections open without ever writing a byte.
                while (!server.isClosed) synchronized(accepted) { accepted.add(server.accept()) }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
    }

    @Test
    fun `open returns sdk_tls_handshake_timeout within the window when the server never handshakes`() {
        val cs = ClientSocket(tracer = mockk(relaxed = true))
        val url = URL("https://127.0.0.1:${server.localPort}/")

        val start = System.currentTimeMillis()
        val result = cs.open(url, emptyMap(), null, 5, handshakeTimeoutMs = 1_000)
        val elapsed = System.currentTimeMillis() - start

        assertEquals("Unexpected result: $result", "sdk_tls_handshake_timeout", result.getString("error"))
        assertTrue("Expected ~1s, took ${elapsed}ms", elapsed in 900..4_000)
    }
}
