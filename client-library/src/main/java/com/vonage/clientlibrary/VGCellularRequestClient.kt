package com.vonage.clientlibrary

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialRequest
import androidx.credentials.GetPhoneNumberAction
import androidx.credentials.GetPhoneNumberResponse
import androidx.credentials.exceptions.GetCredentialException
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

    /**
     * Retrieves the TS.43 token using the Google Digital Credentials API.
     * This token can be exchanged with an aggregator for phone number verification.
     *
     * @param activity The activity context required to show the credential selector.
     * @param callback A callback that will be invoked with the token or an exception.
     */
    fun getTS43Token(activity: Activity, callback: (String?, Exception?) -> Unit) {
        try {
            val getAction = GetPhoneNumberAction()
            val request = GetDigitalCredentialRequest.Builder()
                .addGetCredentialOption(getAction.toGetCredentialOption())
                .build()

            val credentialManager = CredentialManager.create(activity)
            val executor = ContextCompat.getMainExecutor(activity)

            credentialManager.getCredentialAsync(
                activity,
                request,
                null,
                executor,
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        try {
                            val response = GetPhoneNumberResponse.fromCredential(result.credential)
                            val token = response.ts43Token
                            callback(token, null)
                        } catch (e: Exception) {
                            callback(null, e)
                        }
                    }

                    override fun onError(e: GetCredentialException) {
                        callback(null, e)
                    }
                }
            )
        } catch (e: Exception) {
            callback(null, e)
        }
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
        private var currentContext: Context? = null

        @Synchronized
        fun initializeSdk(context: Context): VGCellularRequestClient {
            var currentInstance = instance
            if (null == currentInstance || currentContext != context) {
                val nm = CellularNetworkManager(context)
                currentContext = context
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