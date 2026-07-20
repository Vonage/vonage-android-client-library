package com.vonage.clientlibrary

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.CancellationSignal
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import org.json.JSONArray
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

    private fun isDebuggable(activity: Activity): Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun isNativePathAvailable(activity: Activity): Boolean {
        // The DigitalCredential API requires Android 14 (API 34) and a carrier
        // that has provisioned a TS.43 applet on the SIM. We can only determine
        // full availability at request time; this is a best-effort OS version check.
        val available = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        if (isDebuggable(activity)) {
            Log.d(TAG, "isNativePathAvailable: $available (SDK_INT=${android.os.Build.VERSION.SDK_INT}, required>=34)")
        }
        return available
    }

    @OptIn(ExperimentalSaaApi::class)
    override fun requestToken(
        activity: Activity,
        requestJson: String,
        callback: (token: String?, error: Exception?) -> Unit
    ) {
        val debug = isDebuggable(activity)
        try {
            if (debug) {
                Log.d(TAG, "┌────── CredentialManager Request ──────────────────────")
                Log.d(TAG, "│ requestJson: $requestJson")
                Log.d(TAG, "└──────────────────────────────────────────────────────")
            }

            val option = GetDigitalCredentialOption(requestJson)
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

            val credentialManager = CredentialManager.create(activity)

            if (debug) Log.d(TAG, "Calling credentialManager.getCredentialAsync...")

            credentialManager.getCredentialAsync(
                context = activity,
                request = request,
                cancellationSignal = CancellationSignal(),
                executor = ContextCompat.getMainExecutor(activity),
                callback = object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        if (debug) {
                            Log.d(TAG, "┌────── CredentialManager Response ─────────────────────")
                            Log.d(TAG, "│ credential.type: ${result.credential.type}")
                            Log.d(TAG, "│ credential.data keys: ${result.credential.data.keySet()}")
                        }
                        val credentialJson = result.credential.data.getString("credentialJson")
                        if (debug) Log.d(TAG, "│ credentialJson: $credentialJson")
                        if (credentialJson != null) {
                            try {
                                val token = JSONObject(credentialJson).optStringOrNull("token")
                                if (!token.isNullOrEmpty()) {
                                    if (debug) {
                                        Log.d(TAG, "│ token: ${token.take(50)}... (${token.length} chars)")
                                        Log.d(TAG, "└──────────────────────────────────────────────────────")
                                    }
                                    callback(token, null)
                                } else {
                                    if (debug) {
                                        Log.e(TAG, "│ ERROR: Token not found in credentialJson")
                                        Log.e(TAG, "└──────────────────────────────────────────────────────")
                                    }
                                    callback(null, IllegalStateException("Token not found in credential response"))
                                }
                            } catch (e: Exception) {
                                if (debug) {
                                    Log.e(TAG, "│ ERROR parsing credentialJson: ${e.message}")
                                    Log.e(TAG, "└──────────────────────────────────────────────────────")
                                }
                                callback(null, e)
                            }
                        } else {
                            if (debug) {
                                Log.e(TAG, "│ ERROR: credentialJson key not present in response data")
                                Log.e(TAG, "└──────────────────────────────────────────────────────")
                            }
                            callback(null, IllegalStateException("credentialJson not present in response"))
                        }
                    }

                    override fun onError(e: GetCredentialException) {
                        if (debug) {
                            Log.e(TAG, "┌────── CredentialManager Error ────────────────────────")
                            Log.e(TAG, "│ ${e.javaClass.simpleName}: ${e.message}")
                            Log.e(TAG, "└──────────────────────────────────────────────────────")
                        }
                        callback(null, e)
                    }
                }
            )
        } catch (e: Exception) {
            if (debug) Log.e(TAG, "requestToken exception: ${e.javaClass.simpleName}: ${e.message}")
            callback(null, e)
        }
    }

    companion object {
        private const val TAG = "VonageSAA"
    }
}

/**
 * Builds the OpenID4VP `requestJson` passed to [GetDigitalCredentialOption] for
 * a TS.43 SIM-based authentication request.
 *
 * This reproduces the request shape proven to work against Android's
 * `DigitalCredentialManager` for the Vonage Verify SAA flow:
 *
 * ```json
 * {
 *   "requests": [{
 *     "protocol": "openid4vp-v1-unsigned",
 *     "data": {
 *       "nonce": "<requestId>",
 *       "response_type": "vp_token",
 *       "response_mode": "dc_api",
 *       "dcql_query": {
 *         "credentials": [{
 *           "id": "<vpResponse.id>",
 *           "format": "<vpResponse.format>",
 *           "meta": {
 *             "vct_values": [ ... ],
 *             "credential_authorization_jwt": "<jwt>"
 *           },
 *           "claims": [ { "path": [...], "values": [...] }, ... ]
 *         }]
 *       }
 *     }
 *   }]
 * }
 * ```
 *
 * The `nonce` is the Verify `request_id`, and the credential authorization JWT
 * is nested inside `meta` alongside `vct_values`.
 *
 * Not `internal`: exposed (behind [ExperimentalSaaApi] opt-in and
 * [VisibleForTesting]) so the `clientlibrarytestapp` module's manual test
 * harness can call it directly. It is not intended for use by SDK consumers
 * outside of testing/experimentation — the SDK builds it internally.
 *
 * @param requestId The Verify `request_id`, used as the OpenID4VP `nonce`.
 * @param vpResponse The verifiable presentation response from the webhook.
 */
@ExperimentalSaaApi
@VisibleForTesting
fun buildTs43CredentialRequestJson(
    requestId: String,
    vpResponse: VpResponse
): String {
    val credential = JSONObject()
        .put("id", vpResponse.id)
        .put("format", vpResponse.format)
        .put("meta", JSONObject()
            .put("vct_values", JSONArray(vpResponse.meta.vctValues))
            .put("credential_authorization_jwt", vpResponse.meta.credentialAuthorizationJwt))
        .put("claims", JSONArray(vpResponse.claims.map { claim ->
            JSONObject()
                .put("path", JSONArray(claim.path))
                .put("values", JSONArray(claim.values))
        }))

    val dcqlQuery = JSONObject().put("credentials", JSONArray().put(credential))

    val data = JSONObject()
        .put("nonce", requestId)
        .put("response_type", "vp_token")
        .put("response_mode", "dc_api")
        .put("dcql_query", dcqlQuery)

    val request = JSONObject()
        .put("protocol", "openid4vp-v1-unsigned")
        .put("data", data)

    return JSONObject()
        .put("requests", JSONArray().put(request))
        .toString()
}
