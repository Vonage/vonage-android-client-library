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
 * Represents the `sim_based_authz_data` payload delivered to the app from the
 * customer's backend after a Vonage Verify Silent Auth Advanced request enters
 * the `action_pending` state.
 *
 * Pass this object to [SilentAuthAdvancedManager.requestOperatorToken] to
 * perform the TS.43 challenge-response and obtain an operator token for
 * submission to the Vonage Verify API.
 *
 * Example construction from a JSON string:
 * ```kotlin
 * val data = SimBasedAuthzData.fromJson(JSONObject(jsonString))
 * ```
 */
@ExperimentalSaaApi
data class SimBasedAuthzData(
    /** The verifiable presentation response containing credential request details. */
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
         * Parses a [SimBasedAuthzData] from a [JSONObject] representing the
         * `sim_based_authz_data` field of a Vonage Verify `action_pending` callback.
         *
         * @throws IllegalArgumentException if the `vpResponse` field is missing or malformed.
         */
        fun fromJson(json: JSONObject): SimBasedAuthzData {
            val vpResponseJson = json.optJSONObject("vpResponse")
                ?: throw IllegalArgumentException("Missing required field: vpResponse")
            return SimBasedAuthzData(
                vpResponse = VpResponse.fromJson(vpResponseJson),
                androidAppUrl = json.optStringOrNull("androidAppUrl"),
                appInfoJwt = json.optStringOrNull("appInfoJwt"),
                iOSAppClipUrl = json.optStringOrNull("iOSAppClipUrl")
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
