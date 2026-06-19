package com.vonage.clientlibrary

import android.content.Context
import android.net.Uri
import com.vonage.clientlibrary.network.CellularNetworkManager
import com.vonage.clientlibrary.network.NetworkManager
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URL

class VGCellularRequestClient private constructor(networkManager: CellularNetworkManager) {
    private val networkManager: NetworkManager = networkManager

    /* This method performs a GET request given a URL with cellular connectivity
    - Parameters:
      - params: Parameters to configure your GET request
      - debug: A flag to include or not the url trace in the response
    */
    fun startCellularGetRequest(params: VGCellularRequestParameters, debug: Boolean): JSONObject {
        val uri = constructURL(params)
        val networkManager: NetworkManager = getCellularNetworkManager()
        return networkManager.openWithDataCellular(
            uri,
            params.headers,
            params.maxRedirectCount,
            debug
        )
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
        private var instance: VGCellularRequestClient? = null
        private var currentContext: Context? = null

        @Synchronized
        fun initializeSdk(context: Context): VGCellularRequestClient {
            var currentInstance = instance
            if (null == currentInstance || currentContext != context) {
                val nm = CellularNetworkManager(context)
                currentContext = context.applicationContext
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