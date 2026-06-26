package com.vonage.clientlibrary

import android.app.Activity
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import org.json.JSONObject

/**
 * Default [SaaTokenProvider] implementation using the Android
 * `DigitalCredentialManager` API (androidx.credentials 1.5.0+).
 *
 * Requires `androidx.credentials:credentials` and
 * `androidx.credentials:credentials-play-services-auth` dependencies.
 */
@ExperimentalSaaApi
@OptIn(androidx.credentials.ExperimentalDigitalCredentialApi::class)
internal class DefaultSaaTokenProvider : SaaTokenProvider {

    override fun isNativePathAvailable(activity: Activity): Boolean {
        // The DigitalCredential API requires Android 14 (API 34) and a carrier
        // that has provisioned a TS.43 applet on the SIM. We can only determine
        // full availability at request time; this is a best-effort OS version check.
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    @OptIn(ExperimentalSaaApi::class)
    override fun requestToken(
        activity: Activity,
        credentialAuthorizationJwt: String,
        callback: (token: String?, error: Exception?) -> Unit
    ) {
        try {
            // Build the DigitalCredential request JSON as required by the TS.43 spec.
            val requestJson = JSONObject()
                .put("credential_authorization_jwt", credentialAuthorizationJwt)
                .toString()

            val option = GetDigitalCredentialOption(requestJson)
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

            val credentialManager = CredentialManager.create(activity)

            credentialManager.getCredentialAsync(
                context = activity,
                request = request,
                cancellationSignal = CancellationSignal(),
                executor = ContextCompat.getMainExecutor(activity),
                callback = object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        val credentialJson = result.credential.data.getString("credentialJson")
                        if (credentialJson != null) {
                            try {
                                val token = JSONObject(credentialJson).optString("token")
                                if (token.isNotEmpty()) {
                                    callback(token, null)
                                } else {
                                    callback(null, IllegalStateException("Token not found in credential response"))
                                }
                            } catch (e: Exception) {
                                callback(null, e)
                            }
                        } else {
                            callback(null, IllegalStateException("credentialJson not present in response"))
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
}
