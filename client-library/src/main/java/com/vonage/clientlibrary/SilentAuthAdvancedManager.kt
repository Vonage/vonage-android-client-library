package com.vonage.clientlibrary

import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * Manages the Silent Auth Advanced (SAA) flow for GSMA TS.43 SIM-based authentication.
 *
 * SAA performs a cryptographic challenge-response directly with the SIM card via the
 * Android OS. Unlike Silent Auth Standard, SAA works over Wi-Fi, VPN, and cellular —
 * no data session forcing is needed.
 *
 * ## Typical usage
 *
 * ```kotlin
 * // 1. Receive sim_based_authz_data from your backend
 * val authzData = SimBasedAuthzData.fromJson(JSONObject(jsonFromBackend))
 *
 * // 2. Request the operator token
 * val manager = SilentAuthAdvancedManager()
 * manager.requestOperatorToken(activity, authzData) { result ->
 *     when (result) {
 *         is SaaResult.Success -> {
 *             // Send result.token to your backend: POST /v2/verify/{request_id}
 *         }
 *         is SaaResult.DeepLinkRequired -> {
 *             // Launch the carrier app
 *             activity.startActivityForResult(result.intent, SAA_REQUEST_CODE)
 *             // Handle the returned token in onActivityResult
 *         }
 *         is SaaResult.Error -> {
 *             // Handle result.code / result.message
 *         }
 *     }
 * }
 * ```
 *
 * @param tokenProvider The TS.43 credential provider. Defaults to [DefaultSaaTokenProvider].
 *   Inject a custom implementation in tests or to support alternative credential paths.
 */
@ExperimentalSaaApi
class SilentAuthAdvancedManager(
    private val tokenProvider: SaaTokenProvider = DefaultSaaTokenProvider()
) {

    /**
     * Requests an operator token for the given [SimBasedAuthzData].
     *
     * The manager first attempts the native TS.43 path via the Android OS credential
     * manager. If the native path is unavailable and [SimBasedAuthzData.androidAppUrl]
     * is present, it falls back to a deep-link [Intent] that the host app can start
     * to open the carrier's native app.
     *
     * The [callback] is always invoked exactly once, on the main thread.
     *
     * @param activity The Activity context required for credential UI presentation.
     * @param authzData The `sim_based_authz_data` payload from the Vonage Verify callback.
     * @param callback Invoked with a [SaaResult] when the flow completes or fails.
     */
    fun requestOperatorToken(
        activity: Activity,
        authzData: SimBasedAuthzData,
        callback: (SaaResult) -> Unit
    ) {
        // Validate the payload before attempting anything
        val jwt = authzData.vpResponse.meta.credentialAuthorizationJwt
        if (jwt.isBlank()) {
            callback(SaaResult.Error(
                SaaErrorCode.MALFORMED_PAYLOAD,
                "credential_authorization_jwt is missing or empty"
            ))
            return
        }

        // Virtual operator test numbers: prefix +990
        // Even last digit → success (simulated); odd last digit → failure
        val phoneHint = authzData.vpResponse.claims
            .firstOrNull { it.path.contains("phone_number_hint") }
            ?.values
            ?.firstOrNull()

        if (phoneHint != null && phoneHint.startsWith("+990")) {
            handleVirtualOperator(phoneHint, callback)
            return
        }

        // Attempt native TS.43 path
        if (tokenProvider.isNativePathAvailable(activity)) {
            tokenProvider.requestToken(activity, jwt) { token, error ->
                when {
                    token != null -> {
                        if (token.toByteArray(Charsets.UTF_8).size > MAX_TOKEN_BYTES) {
                            callback(SaaResult.Error(
                                SaaErrorCode.TOKEN_TOO_LARGE,
                                "Operator token exceeds the 5 KB size limit"
                            ))
                        } else {
                            callback(SaaResult.Success(token))
                        }
                    }
                    error != null -> callback(mapProviderError(error, authzData.androidAppUrl))
                    else -> callback(SaaResult.Error(SaaErrorCode.UNKNOWN, "No token and no error returned"))
                }
            }
        } else {
            // Native path unavailable — try deep-link fallback
            val appUrl = authzData.androidAppUrl
            if (!appUrl.isNullOrBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(appUrl)).apply {
                    authzData.appInfoJwt?.let { jwt ->
                        putExtra(EXTRA_APP_INFO_JWT, jwt)
                    }
                }
                callback(SaaResult.DeepLinkRequired(intent))
            } else {
                callback(SaaResult.Error(
                    SaaErrorCode.UNSUPPORTED_NETWORK,
                    "Native TS.43 path is unavailable and no androidAppUrl fallback is provided"
                ))
            }
        }
    }

    /**
     * Handles the token returned by the carrier's native app after a deep-link launch.
     *
     * Call this from your Activity's `onActivityResult` or from the intent that the
     * carrier app returns to your app.
     *
     * @param token The raw operator token string returned by the carrier app.
     * @param callback Invoked with a [SaaResult].
     */
    fun handleDeepLinkResult(
        token: String?,
        callback: (SaaResult) -> Unit
    ) {
        if (token.isNullOrBlank()) {
            callback(SaaResult.Error(SaaErrorCode.CANCELLED, "No token returned from carrier app"))
            return
        }
        if (token.toByteArray(Charsets.UTF_8).size > MAX_TOKEN_BYTES) {
            callback(SaaResult.Error(SaaErrorCode.TOKEN_TOO_LARGE, "Operator token exceeds the 5 KB size limit"))
            return
        }
        callback(SaaResult.Success(token))
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun handleVirtualOperator(phoneHint: String, callback: (SaaResult) -> Unit) {
        val lastDigit = phoneHint.trimEnd().lastOrNull()?.digitToIntOrNull()
        when {
            lastDigit == null -> callback(SaaResult.Error(
                SaaErrorCode.MALFORMED_PAYLOAD,
                "Cannot determine last digit of virtual operator phone hint: $phoneHint"
            ))
            lastDigit % 2 == 0 -> callback(SaaResult.Success(VIRTUAL_OPERATOR_TEST_TOKEN))
            else -> callback(SaaResult.Error(
                SaaErrorCode.UNSUPPORTED_NETWORK,
                "Virtual operator simulated failure for phone hint: $phoneHint"
            ))
        }
    }

    private fun mapProviderError(error: Exception, androidAppUrl: String?): SaaResult {
        // Try deep-link fallback before returning an error if the URL is available
        if (!androidAppUrl.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(androidAppUrl))
            return SaaResult.DeepLinkRequired(intent)
        }
        val code = when (error) {
            is androidx.credentials.exceptions.GetCredentialUnsupportedException ->
                SaaErrorCode.UNSUPPORTED_NETWORK
            is androidx.credentials.exceptions.GetCredentialCancellationException ->
                SaaErrorCode.CANCELLED
            else -> SaaErrorCode.UNKNOWN
        }
        return SaaResult.Error(code, error.message ?: error.javaClass.simpleName)
    }

    companion object {
        /** Maximum permitted operator token size in bytes (5 KB). */
        const val MAX_TOKEN_BYTES = 5 * 1024

        /** Intent extra key for the appInfoJwt passed to the carrier deep-link app. */
        const val EXTRA_APP_INFO_JWT = "com.vonage.clientlibrary.EXTRA_APP_INFO_JWT"

        /** Synthetic token returned for virtual operator test numbers that should succeed. */
        internal const val VIRTUAL_OPERATOR_TEST_TOKEN = "virtual-operator-test-token"
    }
}
