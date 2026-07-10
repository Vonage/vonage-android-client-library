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
        credentialAuthorizationJwt: String,
        callback: (token: String?, error: Exception?) -> Unit
    ) {
        val debug = isDebuggable(activity)
        try {
            // Build the DigitalCredential request JSON as required by the TS.43 spec.
            val requestJson = buildDefaultRequestJson(credentialAuthorizationJwt)

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
 * Builds the `requestJson` payload passed to [GetDigitalCredentialOption] for
 * today's default (production) request path.
 *
 * This is a pure extraction of the request-construction logic previously
 * inlined in [DefaultSaaTokenProvider.requestToken] — no behavior change.
 * It exists to give alternate, experimental builders (see
 * [buildSignedPassthroughRequestJson], [buildMergedDcqlRequestJson]) a seam
 * to be substituted in test harnesses without duplicating the rest of
 * `requestToken`'s logic (native-path check, callback wiring, error mapping,
 * size check, etc.).
 *
 * Not `internal`: exposed (behind [ExperimentalSaaApi] opt-in and
 * [VisibleForTesting]) so the `clientlibrarytestapp` module's manual test
 * harness can call it directly. It is not intended for use by SDK
 * consumers outside of testing/experimentation.
 */
@ExperimentalSaaApi
@VisibleForTesting
fun buildDefaultRequestJson(credentialAuthorizationJwt: String): String {
    return JSONObject()
        .put("credential_authorization_jwt", credentialAuthorizationJwt)
        .toString()
}

/**
 * EXPERIMENTAL / UNVERIFIED. Candidate request shape per OpenID4VP spec
 * Appendix A.3.2.1 (JWS Compact Serialization for a signed DC API request).
 * Passes the aggregator-signed JWT through with minimal wrapping, with no
 * attempt to merge sibling vpResponse fields into the request.
 *
 * NOT CONFIRMED TO WORK. This is not wired into [DefaultSaaTokenProvider]'s
 * production request path — it exists solely so a manual test harness can
 * try this shape against a real device/carrier and observe the raw OS
 * response. Do not call this from production code.
 *
 * Not `internal`: exposed (behind [ExperimentalSaaApi] opt-in and
 * [VisibleForTesting]) so the `clientlibrarytestapp` module's manual test
 * harness can call it directly. It is not intended for use by SDK
 * consumers outside of testing/experimentation.
 */
@ExperimentalSaaApi
@VisibleForTesting
fun buildSignedPassthroughRequestJson(credentialAuthorizationJwt: String): String {
    return JSONObject()
        .put("protocol", "openid4vp-v1-signed")
        .put("data", JSONObject().put("request", credentialAuthorizationJwt))
        .toString()
}

/**
 * EXPERIMENTAL / UNVERIFIED. Candidate request shape that merges the
 * sibling vpResponse fields (format, vct_values, claims) into a dcql_query,
 * on the hypothesis that the JWT alone is insufficient and the DCQL fields
 * are required inputs rather than informational duplicates.
 *
 * NOT CONFIRMED TO WORK. This is not wired into [DefaultSaaTokenProvider]'s
 * production request path — it exists solely so a manual test harness can
 * try this shape against a real device/carrier and observe the raw OS
 * response. Do not call this from production code.
 *
 * Not `internal`: exposed (behind [ExperimentalSaaApi] opt-in and
 * [VisibleForTesting]) so the `clientlibrarytestapp` module's manual test
 * harness can call it directly. It is not intended for use by SDK
 * consumers outside of testing/experimentation.
 */
@ExperimentalSaaApi
@VisibleForTesting
fun buildMergedDcqlRequestJson(
    vpResponse: VpResponse,
    credentialAuthorizationJwt: String
): String {
    val credential = JSONObject()
        .put("id", vpResponse.id)
        .put("format", vpResponse.format)
        .put("meta", JSONObject().put("vct_values", JSONArray(vpResponse.meta.vctValues)))
        .put("claims", JSONArray(vpResponse.claims.map { claim ->
            JSONObject()
                .put("path", JSONArray(claim.path))
                .put("values", JSONArray(claim.values))
        }))

    val dcqlQuery = JSONObject().put("credentials", JSONArray().put(credential))

    return JSONObject()
        .put("protocol", "openid4vp-v1-unsigned")
        .put("data", JSONObject()
            .put("response_type", "vp_token")
            .put("response_mode", "dc_api")
            .put("dcql_query", dcqlQuery)
            .put("request", credentialAuthorizationJwt)  // placement unverified — may belong elsewhere or not at all
        )
        .toString()
}
