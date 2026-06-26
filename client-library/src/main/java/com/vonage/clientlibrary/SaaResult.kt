package com.vonage.clientlibrary

import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * Result of a Silent Auth Advanced operator token request.
 *
 * @see SilentAuthAdvancedManager.requestOperatorToken
 */
@ExperimentalSaaApi
sealed class SaaResult {

    /**
     * The TS.43 challenge-response completed successfully.
     *
     * @property token The operator token to submit to `POST /v2/verify/{request_id}`.
     */
    data class Success(val token: String) : SaaResult()

    /**
     * The native TS.43 path is unavailable on this device/carrier. The host app
     * should launch [intent] to open the carrier's native app, which will return
     * the token via an Activity result or deep-link callback.
     *
     * Only returned when [SimBasedAuthzData.androidAppUrl] is non-null.
     */
    data class DeepLinkRequired(val intent: Intent) : SaaResult()

    /**
     * The SAA flow failed.
     *
     * @property code A machine-readable error code.
     * @property message A human-readable description of the failure.
     */
    data class Error(val code: SaaErrorCode, val message: String) : SaaResult()
}

/**
 * Machine-readable error codes returned in [SaaResult.Error].
 */
@ExperimentalSaaApi
enum class SaaErrorCode {
    /** The device or carrier does not support TS.43. No deep-link fallback is available. */
    UNSUPPORTED_NETWORK,
    /** The `sim_based_authz_data` payload is missing required fields or is malformed. */
    MALFORMED_PAYLOAD,
    /** The operator token returned by the carrier exceeds the 5 KB size limit. */
    TOKEN_TOO_LARGE,
    /** The OS-level credential request was cancelled by the user or system. */
    CANCELLED,
    /** An unexpected error occurred. */
    UNKNOWN
}

/**
 * Interface for the Silent Auth Advanced token provider.
 *
 * The default implementation ([DefaultSaaTokenProvider]) uses the Android
 * `DigitalCredentialManager` API. Swap this out in tests or to support
 * future credential providers.
 */
@ExperimentalSaaApi
interface SaaTokenProvider {
    /**
     * Request an operator token using the device's TS.43 credential manager.
     *
     * @param activity The activity context required to present any system UI.
     * @param credentialAuthorizationJwt The JWT from [VpMeta.credentialAuthorizationJwt].
     * @param callback Invoked on the main thread with the token or an error.
     */
    fun requestToken(
        activity: Activity,
        credentialAuthorizationJwt: String,
        callback: (token: String?, error: Exception?) -> Unit
    )

    /**
     * Returns true if the TS.43 native credential path is available on this device.
     * Implementations may check for OS version, carrier support, or Play Services.
     */
    fun isNativePathAvailable(activity: Activity): Boolean
}
