package com.vonage.clientlibrary

import android.content.Context
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.MalformedURLException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VGCellularRequestClientTest {

    @After
    fun tearDown() {
        unmockkAll()
        // Reset singleton between tests via reflection
        val field = VGCellularRequestClient::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
        val ctxField = VGCellularRequestClient::class.java.getDeclaredField("currentContext")
        ctxField.isAccessible = true
        ctxField.set(null, null)
    }

    @Test
    fun `initializeSdk stores applicationContext not caller context`() {
        val appContext = mockk<Context>(relaxed = true)
        val activityContext = mockk<Context>(relaxed = true)
        every { activityContext.applicationContext } returns appContext
        every { appContext.applicationContext } returns appContext

        VGCellularRequestClient.initializeSdk(activityContext)

        // Calling again with the same Activity context should not recreate the singleton
        // because currentContext was stored as appContext, and appContext == appContext
        val instance1 = VGCellularRequestClient.getInstance()
        every { activityContext.applicationContext } returns appContext
        VGCellularRequestClient.initializeSdk(activityContext)
        val instance2 = VGCellularRequestClient.getInstance()

        assertSame("Singleton should not be recreated for same application context", instance1, instance2)
    }

    @Test
    fun `startCellularGetRequest throws MalformedURLException for http URL`() {
        val appContext = mockk<Context>(relaxed = true)
        every { appContext.applicationContext } returns appContext

        VGCellularRequestClient.initializeSdk(appContext)
        val client = VGCellularRequestClient.getInstance()

        val params = VGCellularRequestParameters(url = "http://api.example.com/test", headers = emptyMap(), queryParameters = emptyMap())

        assertThrows(MalformedURLException::class.java) {
            client.startCellularGetRequest(params, false)
        }
    }

    @Test
    fun `startCellularGetRequest throws MalformedURLException for empty host`() {
        val appContext = mockk<Context>(relaxed = true)
        every { appContext.applicationContext } returns appContext

        VGCellularRequestClient.initializeSdk(appContext)
        val client = VGCellularRequestClient.getInstance()

        val params = VGCellularRequestParameters(url = "https:///path", headers = emptyMap(), queryParameters = emptyMap())

        assertThrows(MalformedURLException::class.java) {
            client.startCellularGetRequest(params, false)
        }
    }
}
