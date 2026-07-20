package com.vonage.clientlibrary

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single claim within a [VpResponse], representing a path/values pair
 * as defined in the TS.43 credential presentation format.
 */
@ExperimentalSaaApi
data class VpClaim(
    /** JSON Pointer path segments identifying the claim (e.g. `["phone_number_hint"]`). */
    val path: List<String>,
    /** Values associated with this claim path (e.g. `["+467234524553"]`). */
    val values: List<String>
) {
    companion object {
        internal fun fromJson(json: JSONObject): VpClaim {
            val path = mutableListOf<String>()
            val pathArray = json.optJSONArray("path") ?: JSONArray()
            for (i in 0 until pathArray.length()) {
                path.add(pathArray.getString(i))
            }

            val values = mutableListOf<String>()
            val valuesArray = json.optJSONArray("values") ?: JSONArray()
            for (i in 0 until valuesArray.length()) {
                values.add(valuesArray.getString(i))
            }

            return VpClaim(path = path, values = values)
        }
    }
}

/**
 * Metadata associated with a [VpResponse], carrying the TS.43 credential
 * authorization JWT and the verifiable credential type values.
 */
@ExperimentalSaaApi
data class VpMeta(
    /** Verifiable credential type values (e.g. `["number-verification/device-phone-number/ts43"]`). */
    val vctValues: List<String>,
    /**
     * The credential authorization JWT issued by the Vonage Verify backend.
     * This is passed to the Android OS TS.43 API to obtain the operator token.
     */
    val credentialAuthorizationJwt: String
) {
    companion object {
        internal fun fromJson(json: JSONObject): VpMeta {
            val vctValues = mutableListOf<String>()
            val vctArray = json.optJSONArray("vct_values") ?: JSONArray()
            for (i in 0 until vctArray.length()) {
                vctValues.add(vctArray.getString(i))
            }
            return VpMeta(
                vctValues = vctValues,
                credentialAuthorizationJwt = json.optString("credential_authorization_jwt", "")
            )
        }
    }
}

/**
 * The verifiable presentation response object within [SimBasedAuthzData],
 * describing the credential request format and claims to be verified.
 */
@ExperimentalSaaApi
data class VpResponse(
    /** Identifier for the verifiable presentation (e.g. `"gnp"`). */
    val id: String,
    /** Credential format (e.g. `"dc-authorization+sd-jwt"`). */
    val format: String,
    /** Metadata carrying the credential authorization JWT and VC type values. */
    val meta: VpMeta,
    /** Claims to be included in the presentation. */
    val claims: List<VpClaim>
) {
    companion object {
        internal fun fromJson(json: JSONObject): VpResponse {
            val claims = mutableListOf<VpClaim>()
            val claimsArray = json.optJSONArray("claims") ?: JSONArray()
            for (i in 0 until claimsArray.length()) {
                claims.add(VpClaim.fromJson(claimsArray.getJSONObject(i)))
            }
            return VpResponse(
                id = json.optString("id", ""),
                format = json.optString("format", ""),
                meta = VpMeta.fromJson(json.optJSONObject("meta") ?: JSONObject()),
                claims = claims
            )
        }
    }
}

/**
 * Represents the data the app needs to perform a Silent Auth Advanced (TS.43)
 * challenge, extracted from the Vonage Verify `action_pending` webhook event.
 *
 * Construct this from the **full webhook event** your backend received from
 * Vonage (via [fromVerifyEvent]). The event carries the `request_id` (used as
 * the OpenID4VP `nonce`) at its top level and the `sim_based_authz_data`
 * (containing `vpResponse`) under `action`. Both are required to build a valid
 * TS.43 CredentialManager request.
 *
 * Pass this object to [SilentAuthAdvancedManager.requestOperatorToken] to
 * perform the TS.43 challenge-response and obtain an operator token for
 * submission to the Vonage Verify API.
 *
 * Example construction from the raw webhook JSON:
 * ```kotlin
 * val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(webhookEventJson))
 * ```
 */
@ExperimentalSaaApi
data class SimBasedAuthzData(
    /**
     * The Verify `request_id` from the webhook event. Used as the OpenID4VP
     * `nonce` in the CredentialManager request and to submit the resulting
     * token to `POST /v2/verify/{request_id}`.
     */
    val requestId: String,
    /**
     * The verifiable presentation response containing the credential request
     * details (id, format, meta with the credential authorization JWT, and
     * claims). Required to build the TS.43 CredentialManager request.
     */
    val vpResponse: VpResponse,
    /**
     * Deep-link URL to the carrier's native app, used as a fallback on devices
     * that do not support the native TS.43 SDK path.
     */
    val androidAppUrl: String?,
    /**
     * JWT carrying app metadata, used by some carriers to validate the calling app.
     */
    val appInfoJwt: String?,
    /**
     * iOS App Clip URL — included for payload completeness. Not used on Android.
     */
    val iOSAppClipUrl: String? = null
) {
    companion object {
        /**
         * Parses a [SimBasedAuthzData] from the **full Vonage Verify webhook
         * event** delivered to your backend when a Silent Auth Advanced request
         * enters the `action_pending` state.
         *
         * Expected structure (fields not shown are ignored):
         * ```json
         * {
         *   "request_id": "…",
         *   "action": {
         *     "sim_based_authz_data": {
         *       "vpResponse": { "id": "…", "format": "…", "meta": { … }, "claims": [ … ] },
         *       "androidAppUrl": "…",
         *       "appInfoJwt": "…"
         *     }
         *   }
         * }
         * ```
         *
         * `sim_based_authz_data` is also accepted at the top level of [event]
         * (i.e. without the `action` wrapper) for resilience to minor delivery
         * differences.
         *
         * @throws IllegalArgumentException if `request_id`, `sim_based_authz_data`,
         *   or `vpResponse` is missing. These fields are required to build a valid
         *   TS.43 request; treat this as a `MALFORMED_PAYLOAD` condition.
         */
        fun fromVerifyEvent(event: JSONObject): SimBasedAuthzData {
            val requestId = event.optStringOrNull("request_id")
                ?: throw IllegalArgumentException(
                    "Verify event is missing required 'request_id'"
                )

            val simData = event.optJSONObject("action")?.optJSONObject("sim_based_authz_data")
                ?: event.optJSONObject("sim_based_authz_data")
                ?: throw IllegalArgumentException(
                    "Verify event is missing 'action.sim_based_authz_data'"
                )

            val vpResponseJson = simData.optJSONObject("vpResponse")
                ?: throw IllegalArgumentException(
                    "sim_based_authz_data is missing required 'vpResponse'"
                )

            return SimBasedAuthzData(
                requestId = requestId,
                vpResponse = VpResponse.fromJson(vpResponseJson),
                androidAppUrl = simData.optStringOrNull("androidAppUrl"),
                appInfoJwt = simData.optStringOrNull("appInfoJwt"),
                iOSAppClipUrl = simData.optStringOrNull("iOSAppClipUrl")
            )
        }
    }
}

/**
 * Returns the string value for [key], or null if the key is absent, JSON null,
 * or an empty string.
 *
 * `JSONObject.optString` returns the literal string "null" when the underlying
 * value is JSON null — this helper guards against that.
 */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key)
    return value.takeIf { it.isNotEmpty() }
}
