package com.vonage.clientlibrary

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException

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

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Requests an operator token for the given [SimBasedAuthzData].
     *
     * The manager first attempts the native TS.43 path via the Android OS credential
     * manager. If the native path is unavailable and [SimBasedAuthzData.androidAppUrl]
     * is present, it falls back to a deep-link [Intent] that the host app can start
     * to open the carrier's native app.
     *
     * The [callback] is always invoked exactly once, on the main thread. Call this
     * method from the main thread.
     *
     * @param activity The Activity context required for credential UI presentation.
     * @param authzData The `sim_based_authz_data` payload from the Vonage Verify callback.
     * @param callback Invoked with a [SaaResult] when the flow completes or fails.
     */
    @MainThread
    fun requestOperatorToken(
        activity: Activity,
        authzData: SimBasedAuthzData,
        callback: (SaaResult) -> Unit
    ) {
        // Validate the payload before attempting anything
        val jwt = authzData.vpResponse.meta.credentialAuthorizationJwt
        if (jwt.isBlank()) {
            dispatch(callback, SaaResult.Error(
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
                            dispatch(callback, SaaResult.Error(
                                SaaErrorCode.TOKEN_TOO_LARGE,
                                "Operator token exceeds the 5 KB size limit"
                            ))
                        } else {
                            dispatch(callback, SaaResult.Success(token))
                        }
                    }
                    error != null -> dispatch(callback, mapProviderError(error, authzData))
                    else -> dispatch(callback, SaaResult.Error(SaaErrorCode.UNKNOWN, "No token and no error returned"))
                }
            }
        } else {
            // Native path unavailable — try deep-link fallback
            val deepLinkResult = buildDeepLinkResult(
                authzData,
                fallbackErrorMessage = "Native TS.43 path is unavailable and no androidAppUrl fallback is provided"
            )
            dispatch(callback, deepLinkResult)
        }
    }

    /**
     * Handles the token returned by the carrier's native app after a deep-link launch.
     *
     * Call this from your Activity's `onActivityResult` or from the intent that the
     * carrier app returns to your app. The [callback] is invoked on the main thread.
     *
     * @param token The raw operator token string returned by the carrier app.
     * @param callback Invoked with a [SaaResult].
     */
    @MainThread
    fun handleDeepLinkResult(
        token: String?,
        callback: (SaaResult) -> Unit
    ) {
        if (token.isNullOrBlank()) {
            dispatch(callback, SaaResult.Error(SaaErrorCode.CANCELLED, "No token returned from carrier app"))
            return
        }
        if (token.toByteArray(Charsets.UTF_8).size > MAX_TOKEN_BYTES) {
            dispatch(callback, SaaResult.Error(SaaErrorCode.TOKEN_TOO_LARGE, "Operator token exceeds the 5 KB size limit"))
            return
        }
        dispatch(callback, SaaResult.Success(token))
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun handleVirtualOperator(phoneHint: String, callback: (SaaResult) -> Unit) {
        val lastDigit = phoneHint.trimEnd().lastOrNull()?.digitToIntOrNull()
        when {
            lastDigit == null -> dispatch(callback, SaaResult.Error(
                SaaErrorCode.MALFORMED_PAYLOAD,
                "Cannot determine last digit of virtual operator phone hint: $phoneHint"
            ))
            lastDigit % 2 == 0 -> dispatch(callback, SaaResult.Success(VIRTUAL_OPERATOR_TEST_TOKEN))
            else -> dispatch(callback, SaaResult.Error(
                SaaErrorCode.UNSUPPORTED_NETWORK,
                "Virtual operator simulated failure for phone hint: $phoneHint"
            ))
        }
    }

    /**
     * Translates a provider error into a [SaaResult].
     *
     * The deep-link fallback is only used when the error indicates that the native
     * TS.43 path is not available on this device. User-cancellation and other
     * errors are surfaced directly so the host app can decide what to do — we do
     * not re-prompt the user by opening the carrier app behind their back.
     */
    private fun mapProviderError(error: Exception, authzData: SimBasedAuthzData): SaaResult {
        return when (error) {
            is GetCredentialUnsupportedException ->
                buildDeepLinkResult(
                    authzData,
                    fallbackErrorMessage = error.message ?: "Native TS.43 path is unsupported on this device"
                )
            is GetCredentialCancellationException ->
                SaaResult.Error(SaaErrorCode.CANCELLED, error.message ?: "Cancelled")
            else ->
                SaaResult.Error(SaaErrorCode.UNKNOWN, error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Builds either a [SaaResult.DeepLinkRequired] (when [SimBasedAuthzData.androidAppUrl]
     * is present) or a [SaaResult.Error] with [SaaErrorCode.UNSUPPORTED_NETWORK] otherwise.
     *
     * Always attaches [EXTRA_APP_INFO_JWT] to the intent when [SimBasedAuthzData.appInfoJwt]
     * is present so both fallback paths stay consistent.
     */
    private fun buildDeepLinkResult(
        authzData: SimBasedAuthzData,
        fallbackErrorMessage: String
    ): SaaResult {
        val appUrl = authzData.androidAppUrl
        if (appUrl.isNullOrBlank()) {
            return SaaResult.Error(SaaErrorCode.UNSUPPORTED_NETWORK, fallbackErrorMessage)
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(appUrl)).apply {
            authzData.appInfoJwt?.let { jwt ->
                putExtra(EXTRA_APP_INFO_JWT, jwt)
            }
        }
        return SaaResult.DeepLinkRequired(intent)
    }

    /**
     * Posts [result] to [callback] on the main thread. If the current thread is
     * already the main thread we still post to keep ordering deterministic — the
     * callback always runs *after* the current public-API call returns.
     */
    private fun dispatch(callback: (SaaResult) -> Unit, result: SaaResult) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // We're already on the main thread. Invoke directly to avoid forcing
            // an unconditional async hop, which would surprise simple test setups
            // and synchronous-feeling early-return paths.
            callback(result)
        } else {
            mainHandler.post { callback(result) }
        }
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
