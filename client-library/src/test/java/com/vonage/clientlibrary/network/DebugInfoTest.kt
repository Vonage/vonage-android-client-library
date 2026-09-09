package com.vonage.clientlibrary.network

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for issue #24: SDK console logs were emitted in release
 * (non-debuggable) builds because the console sink defaulted to enabled and was
 * never gated on the host app's FLAG_DEBUGGABLE.
 *
 * The contract: console logging is OFF by default; it only emits once explicitly enabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DebugInfoTest {

    private val tag = "CellularClient"
    private val msg = "Requesting: https://api-eu.vonage.com/v2/verify/.../silent-auth/redirect"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `console logging is disabled by default so nothing is emitted`() {
        val debugInfo = DebugInfo()

        debugInfo.addLog(Log.DEBUG, tag, msg)

        verify(exactly = 0) { Log.d(any(), any<String>()) }
    }

    @Test
    fun `enabling console logging emits the log`() {
        val debugInfo = DebugInfo()
        debugInfo.enableConsole(true)

        debugInfo.addLog(Log.DEBUG, tag, msg)

        verify(exactly = 1) { Log.d(tag, msg) }
    }
}
