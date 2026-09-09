package com.vonage.clientlibrary.network

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.mockk.every
import io.mockk.mockk
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
 * Verifies issue #24 fix: constructing the SDK gates console logging on the host
 * app's FLAG_DEBUGGABLE. Non-debuggable (release) builds suppress console output;
 * debuggable builds emit it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CellularNetworkManagerLoggingTest {

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
        // Reset the shared singleton so tests don't leak console state into each other.
        TraceCollector.instance.shouldLogDebugInfoToConsole(false)
        unmockkAll()
    }

    private fun contextWithFlags(flags: Int): Context {
        val context = mockk<Context>(relaxed = true)
        val appInfo = ApplicationInfo().apply { this.flags = flags }
        every { context.applicationInfo } returns appInfo
        return context
    }

    @Test
    fun `release build (non-debuggable) suppresses console logs`() {
        CellularNetworkManager(contextWithFlags(0))

        TraceCollector.instance.addDebug(Log.DEBUG, tag, msg)

        verify(exactly = 0) { Log.d(any(), any<String>()) }
    }

    @Test
    fun `debuggable build emits console logs`() {
        CellularNetworkManager(contextWithFlags(ApplicationInfo.FLAG_DEBUGGABLE))

        TraceCollector.instance.addDebug(Log.DEBUG, tag, msg)

        verify(exactly = 1) { Log.d(tag, msg) }
    }
}
