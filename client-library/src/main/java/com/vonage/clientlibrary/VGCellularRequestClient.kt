package com.vonage.clientlibrary

import android.content.Context
import android.net.Uri
import com.vonage.clientlibrary.network.CellularNetworkManager
import com.vonage.clientlibrary.network.NetworkManager
import org.json.JSONObject
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
        return URL(uri)
    }

    companion object {
        private var instance: VGCellularRequestClient? = null

        @Synchronized
        fun initializeSdk(context: Context): VGCellularRequestClient {
            var currentInstance = instance
            if (currentInstance != null) return currentInstance
            currentInstance = VGCellularRequestClient(
                networkManager = CellularNetworkManager(context = context.applicationContext)
            )
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
