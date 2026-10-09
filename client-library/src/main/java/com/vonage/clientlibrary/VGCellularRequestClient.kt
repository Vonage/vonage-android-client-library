package com.vonage.clientlibrary

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.vonage.clientlibrary.network.CellularNetworkManager
import com.vonage.clientlibrary.network.NetworkManager
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URL

class VGCellularRequestClient private constructor(networkManager: CellularNetworkManager) {
    private val networkManager: NetworkManager = networkManager

    /**
     * Returns the current cellular connectivity status of the device.
     *
     * Call this before initiating a Silent Authentication flow to determine whether
     * cellular is available and select the appropriate Vonage Verify channel.
     *
     * This is a fast, synchronous check — it does not attempt a network connection.
     *
     * @return [CellularStatus.Available] if cellular is the active network,
     *         [CellularStatus.AvailableNotActive] if a cellular interface exists but is not active,
     *         [CellularStatus.Unavailable] if no cellular interface is detected.
     */
    fun getCellularStatus(): CellularStatus {
        return networkManager.getCellularStatus()
    }

    /* This method performs a GET request given a URL with cellular connectivity
    - Parameters:
      - params: Parameters to configure your GET request
      - debug: A flag to include or not the url trace in the response
      - handshakeTimeoutMs: Optional. Time allowed, per connection, to resolve the host (DNS), open
        the TCP connection and complete the TLS handshake. Defaults to
        DEFAULT_HANDSHAKE_TIMEOUT_MS (5s); must be between MIN_HANDSHAKE_TIMEOUT_MS (1s) and
        MAX_HANDSHAKE_TIMEOUT_MS (10s), otherwise IllegalArgumentException is thrown.
    - Returns a JSONObject. On failure it contains "error" and "error_description"; an error of
      "sdk_tls_handshake_timeout" means a secure connection could not be established within
      handshakeTimeoutMs.
    */
    @JvmOverloads
    fun startCellularGetRequest(
        params: VGCellularRequestParameters,
        debug: Boolean,
        handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS
    ): JSONObject {
        require(handshakeTimeoutMs in MIN_HANDSHAKE_TIMEOUT_MS..MAX_HANDSHAKE_TIMEOUT_MS) {
            "handshakeTimeoutMs must be between $MIN_HANDSHAKE_TIMEOUT_MS and " +
                "$MAX_HANDSHAKE_TIMEOUT_MS, was $handshakeTimeoutMs"
        }
        val uri = constructURL(params)
        val networkManager: NetworkManager = getCellularNetworkManager()
        return networkManager.openWithDataCellular(
            uri,
            params.headers,
            params.maxRedirectCount,
            debug,
            handshakeTimeoutMs
        )
    }

    /**
     * Requests a Silent Auth Advanced operator token using the GSMA TS.43 SIM-based
     * authentication flow.
     *
     * SAA works over Wi-Fi, VPN, and cellular — no data session forcing is needed.
     * Call this after receiving `sim_based_authz_data` from your backend following
     * a Vonage Verify `action_pending` event.
     *
     * This API is experimental. Opt in with `@OptIn(ExperimentalSaaApi::class)`.
     *
     * @param activity The Activity context required for credential UI presentation.
     * @param authzData The parsed `sim_based_authz_data` payload.
     * @param manager Optional custom [SilentAuthAdvancedManager]. Defaults to a new instance.
     * @param callback Invoked with a [SaaResult] when the flow completes or fails.
     */
    @ExperimentalSaaApi
    fun requestSilentAuthAdvancedToken(
        activity: Activity,
        authzData: SimBasedAuthzData,
        manager: SilentAuthAdvancedManager = SilentAuthAdvancedManager(),
        callback: (SaaResult) -> Unit
    ) {
        manager.requestOperatorToken(activity, authzData, callback)
    }

    private fun getCellularNetworkManager(): NetworkManager {
        return networkManager
    }

    private fun constructURL(params: VGCellularRequestParameters): URL {
        val uriBuilder = Uri.parse(params.url).buildUpon()
        for ((key, value) in params.queryParameters) {
            uriBuilder.appendQueryParameter(key, value)
        }
        val uri = uriBuilder.build().toString()
        val url = URL(uri)
        if (url.protocol != "https") {
            throw MalformedURLException("Only HTTPS URLs are supported. Received: ${url.protocol}://")
        }
        if (url.host.isNullOrEmpty()) {
            throw MalformedURLException("URL must contain a non-empty host.")
        }
        return url
    }

    companion object {
        /** Default for the `handshakeTimeoutMs` argument of [startCellularGetRequest]. */
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS: Int = 5_000
        const val MIN_HANDSHAKE_TIMEOUT_MS: Int = 1_000
        const val MAX_HANDSHAKE_TIMEOUT_MS: Int = 10_000

        private var instance: VGCellularRequestClient? = null
        private var currentContext: Context? = null

        @Synchronized
        fun initializeSdk(context: Context): VGCellularRequestClient {
            val appContext = context.applicationContext
            var currentInstance = instance
            if (null == currentInstance || currentContext != appContext) {
                val nm = CellularNetworkManager(appContext)
                currentContext = appContext
                currentInstance = VGCellularRequestClient(nm)
            }
            instance = currentInstance
            return currentInstance
        }

        @Synchronized
        fun getInstance(): VGCellularRequestClient {
            val currentInstance = instance
            checkNotNull(currentInstance) {
                VGCellularRequestClient::class.java.simpleName +
                        " is not initialized, call initializeSdk(...) first"
            }
            return currentInstance
        }
    }
}