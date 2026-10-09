package com.vonage.clientlibrary

import android.app.Activity
import android.content.Context
import com.vonage.clientlibrary.network.NetworkManager
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

    /** Injects a mock NetworkManager into the singleton instance via reflection. */
    private fun injectMockNetworkManager(client: VGCellularRequestClient, mock: NetworkManager) {
        val field = VGCellularRequestClient::class.java.getDeclaredField("networkManager")
        field.isAccessible = true
        field.set(client, mock)
    }

    private fun buildClient(): VGCellularRequestClient {
        val appContext = mockk<Context>(relaxed = true)
        every { appContext.applicationContext } returns appContext
        VGCellularRequestClient.initializeSdk(appContext)
        return VGCellularRequestClient.getInstance()
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

    @Test
    fun `startCellularGetRequest passes handshakeTimeoutMs to the network manager`() {
        val client = buildClient()
        val mockNm = mockk<NetworkManager>(relaxed = true)
        injectMockNetworkManager(client, mockNm)

        val params = VGCellularRequestParameters(
            url = "https://api.example.com/test",
            headers = emptyMap(),
            queryParameters = emptyMap()
        )
        client.startCellularGetRequest(params, false, 3_000)

        verify(exactly = 1) { mockNm.openWithDataCellular(any(), any(), 10, false, 3_000) }
    }

    @Test
    fun `startCellularGetRequest without a timeout uses the 5s default`() {
        val client = buildClient()
        val mockNm = mockk<NetworkManager>(relaxed = true)
        injectMockNetworkManager(client, mockNm)

        val params = VGCellularRequestParameters("https://api.example.com/test", emptyMap(), emptyMap())
        client.startCellularGetRequest(params, false)

        verify(exactly = 1) { mockNm.openWithDataCellular(any(), any(), 10, false, 5_000) }
    }

    @Test
    fun `startCellularGetRequest accepts the 1s and 10s bounds`() {
        val client = buildClient()
        val mockNm = mockk<NetworkManager>(relaxed = true)
        injectMockNetworkManager(client, mockNm)
        val params = VGCellularRequestParameters("https://api.example.com/test", emptyMap(), emptyMap())

        client.startCellularGetRequest(params, false, 1_000)
        client.startCellularGetRequest(params, false, 10_000)

        verify(exactly = 1) { mockNm.openWithDataCellular(any(), any(), any(), any(), 1_000) }
        verify(exactly = 1) { mockNm.openWithDataCellular(any(), any(), any(), any(), 10_000) }
    }

    @Test
    fun `startCellularGetRequest rejects a timeout outside 1-10s without making a request`() {
        val client = buildClient()
        val mockNm = mockk<NetworkManager>(relaxed = true)
        injectMockNetworkManager(client, mockNm)
        val params = VGCellularRequestParameters("https://api.example.com/test", emptyMap(), emptyMap())

        assertThrows(IllegalArgumentException::class.java) { client.startCellularGetRequest(params, false, 999) }
        assertThrows(IllegalArgumentException::class.java) { client.startCellularGetRequest(params, false, 10_001) }
        verify(exactly = 0) { mockNm.openWithDataCellular(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `original two-argument startCellularGetRequest signature is preserved for compiled callers`() {
        // Apps and libraries compiled against <= 1.3.x link to this exact JVM signature.
        val method = VGCellularRequestClient::class.java.getMethod(
            "startCellularGetRequest",
            VGCellularRequestParameters::class.java,
            Boolean::class.javaPrimitiveType
        )
        assertEquals(org.json.JSONObject::class.java, method.returnType)
    }

    // ------------------------------------------------------------------
    // DEVX-11183: getCellularStatus()
    // ------------------------------------------------------------------

    @Test
    fun `getCellularStatus returns Available when NetworkManager returns Available`() {
        val client = buildClient()
        val mockNm = mockk<NetworkManager>(relaxed = true)
        every { mockNm.getCellularStatus() } returns CellularStatus.Available
        injectMockNetworkManager(client, mockNm)

        assertEquals(CellularStatus.Available, client.getCellularStatus())
    }

    @Test
    fun `getCellularStatus returns AvailableNotActive when NetworkManager returns AvailableNotActive`() {
        val client = buildClient()
        val mockNm = mockk<NetworkManager>(relaxed = true)
        every { mockNm.getCellularStatus() } returns CellularStatus.AvailableNotActive
        injectMockNetworkManager(client, mockNm)

        assertEquals(CellularStatus.AvailableNotActive, client.getCellularStatus())
    }

    @Test
    fun `getCellularStatus returns Unavailable when NetworkManager returns Unavailable`() {
        val client = buildClient()
        val mockNm = mockk<NetworkManager>(relaxed = true)
        every { mockNm.getCellularStatus() } returns CellularStatus.Unavailable
        injectMockNetworkManager(client, mockNm)

        assertEquals(CellularStatus.Unavailable, client.getCellularStatus())
    }

    @Test
    fun `getCellularStatus delegates to NetworkManager exactly once`() {
        val client = buildClient()
        val mockNm = mockk<NetworkManager>(relaxed = true)
        every { mockNm.getCellularStatus() } returns CellularStatus.Available
        injectMockNetworkManager(client, mockNm)

        client.getCellularStatus()

        verify(exactly = 1) { mockNm.getCellularStatus() }
    }

    // ------------------------------------------------------------------
    // SAA: requestSilentAuthAdvancedToken()
    // ------------------------------------------------------------------

    @OptIn(ExperimentalSaaApi::class)
    @Test
    fun `requestSilentAuthAdvancedToken delegates to SilentAuthAdvancedManager`() {
        val client = buildClient()
        val mockActivity = mockk<Activity>(relaxed = true)
        val mockManager = mockk<SilentAuthAdvancedManager>(relaxed = true)

        val authzData = SimBasedAuthzData(
            requestId = "req-123",
            vpResponse = VpResponse(
                id = "gnp",
                format = "dc-authorization+sd-jwt",
                meta = VpMeta(
                    vctValues = listOf("number-verification/device-phone-number/ts43"),
                    credentialAuthorizationJwt = "aaa.bbb.ccc"
                ),
                claims = emptyList()
            ),
            androidAppUrl = null,
            appInfoJwt = null
        )

        val slot = slot<(SaaResult) -> Unit>()
        every { mockManager.requestOperatorToken(any(), any(), capture(slot)) } answers {
            slot.captured(SaaResult.Success("test-token"))
        }

        var result: SaaResult? = null
        client.requestSilentAuthAdvancedToken(mockActivity, authzData, mockManager) { result = it }

        verify(exactly = 1) { mockManager.requestOperatorToken(mockActivity, authzData, any()) }
        assertEquals(SaaResult.Success("test-token"), result)
    }

    @OptIn(ExperimentalSaaApi::class)
    @Test
    fun `requestSilentAuthAdvancedToken passes error result through`() {
        val client = buildClient()
        val mockActivity = mockk<Activity>(relaxed = true)
        val mockManager = mockk<SilentAuthAdvancedManager>(relaxed = true)

        val authzData = SimBasedAuthzData(
            requestId = "req-456",
            vpResponse = VpResponse(
                id = "gnp",
                format = "dc-authorization+sd-jwt",
                meta = VpMeta(vctValues = emptyList(), credentialAuthorizationJwt = "jwt"),
                claims = emptyList()
            ),
            androidAppUrl = null,
            appInfoJwt = null
        )

        val slot = slot<(SaaResult) -> Unit>()
        every { mockManager.requestOperatorToken(any(), any(), capture(slot)) } answers {
            slot.captured(SaaResult.Error(SaaErrorCode.UNSUPPORTED_NETWORK, "no carrier support"))
        }

        var result: SaaResult? = null
        client.requestSilentAuthAdvancedToken(mockActivity, authzData, mockManager) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.UNSUPPORTED_NETWORK, error.code)
    }
}
