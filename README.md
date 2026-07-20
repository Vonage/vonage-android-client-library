# Vonage Client Library

A library to support using the Vonage APIs on Android. Features:

* Force a cellular network request for use with [Vonage Number Verification](https://developer.vonage.com/en/number-verification/overview) and [Vonage Verify Silent Authentication](https://developer.vonage.com/en/verify/guides/silent-authentication)
* Check cellular connectivity status before initiating a Silent Authentication flow
* Silent Auth Advanced (SAA) — SIM-based authentication via GSMA TS.43 *(alpha)*

## Installation

build.gradle -> dependencies add

```
implementation 'com.vonage:client-library:1.3.0-alpha08'
```

> **Silent Auth Advanced** is available as of the `1.3.0-alpha08` pre-release.

## Usage

### Check Cellular Connectivity

Before initiating a Silent Authentication flow, you can check whether the device has cellular connectivity to determine the appropriate Vonage Verify channel. This is a fast, synchronous check — it does not attempt a network connection.

```kotlin
import com.vonage.clientlibrary.VGCellularRequestClient
import com.vonage.clientlibrary.CellularStatus

VGCellularRequestClient.initializeSdk(this.applicationContext)

when (VGCellularRequestClient.getInstance().getCellularStatus()) {
    CellularStatus.Available -> {
        // Cellular is the active network — proceed with Silent Auth
    }
    CellularStatus.AvailableNotActive -> {
        // A cellular interface exists but the device is on Wi-Fi.
        // Silent Auth may still work but will take longer.
        // Consider falling back to SMS or WhatsApp if latency matters.
    }
    CellularStatus.Unavailable -> {
        // No cellular interface detected — skip Silent Auth and use
        // another Vonage Verify channel (e.g. SMS or WhatsApp)
    }
}
```

### Force a Cellular Network Request

```kotlin
import com.vonage.clientlibrary.VGCellularRequestClient
import com.vonage.clientlibrary.VGCellularRequestParameters

VGCellularRequestClient.initializeSdk(this.applicationContext)

val params = VGCellularRequestParameters(
    url = "https://www.vonage.com",
    headers = mapOf("x-my-header" to "My Value"),
    queryParameters = mapOf("query-param" to "value"),
    maxRedirectCount = 10
)

val response = VGCellularRequestClient.getInstance().startCellularGetRequest(params, false)
if (response.has("error")) {
    // error
} else {
    val status = response.optInt("http_status")
    val jsonResponse = response.optJSONObject("response_body") // Body of response parsed to JSON (NULL if not JSON)
    val rawResponse = response.optString("response_raw_body") // RAW string of response body (Only populated if not JSON)
    if (status == 200) {
        // 200 OK
    } else {
        // error
    }
}
```

* `maxRedirectCount` in `VGCellularRequestParameters` is optional and defaults to 10.
* `debug` parameter for `startCellularGetRequest` is optional and defaults to false.
* Only `https://` URLs are accepted. Passing an `http://` URL will throw a `MalformedURLException`.

#### Responses

* Success - When the data connectivity has been achieved, and a response has been received from the url endpoint:
```json
{
    "http_status": 200,
    "response_body": {
        "...": "..."
    },
    "debug": {
        "device_info": "string",
        "url_trace": "string",
        "operator_tracking": {
            "X-Orange-Trace-Id": "string"
        }
    }
}
```

* Error - When data connectivity is not available and/or an internal SDK error occurred:

```json
{
    "error": "string",
    "error_description": "string",
    "debug": {
        "device_info": "string",
        "url_trace": "string",
        "operator_tracking": {
            "X-Orange-Trace-Id": "string"
        }
    }
}
```

`operator_tracking` is only present in the `debug` block when `debug = true` and the operator returned tracking headers in the response. Currently captured headers: `X-Orange-Trace-Id` (Orange), `X-VIG-Trace-Id` (Vodafone).

Potential error codes: `sdk_no_data_connectivity`, `sdk_connection_error`, `sdk_redirect_error`, `sdk_error`.

### Silent Auth Advanced (SAA) *(alpha)*

> **This API is experimental.** It is subject to change as carrier adoption of the GSMA TS.43 standard matures. All SAA types are annotated with `@ExperimentalSaaApi`. You must opt in at the call site:
> ```kotlin
> @OptIn(ExperimentalSaaApi::class)
> ```

Silent Auth Advanced performs a cryptographic challenge-response directly with the SIM card via the Android OS. Unlike Silent Auth Standard, SAA works over Wi-Fi, VPN, and cellular — no data session forcing is needed.

#### Prerequisites

* Device running Android 14 (API 34) or higher
* A SIM card from a carrier that supports TS.43
* `androidx.credentials:credentials:1.5.0` and `androidx.credentials:credentials-play-services-auth:1.5.0` dependencies

#### How it works

1. Your backend calls `POST https://api.nexmo.com/v2/verify` with `"channel": "silent_auth", "mode": "advanced"`.
2. Vonage sends an `action_pending` webhook event to your backend. The event contains the `request_id` at its top level and a `sim_based_authz_data` object (with a `vpResponse`) under `action`.
3. Your backend delivers the **full webhook event** to the app (via push or polling). The whole event is needed — the app uses `request_id` as the OpenID4VP `nonce` and `action.sim_based_authz_data.vpResponse` to build the credential request.
4. The app calls the SDK with the payload to obtain an operator token.
5. Your backend submits the token to `POST https://api.nexmo.com/v2/verify/{request_id}` to complete verification.

#### Usage

```kotlin
import com.vonage.clientlibrary.ExperimentalSaaApi
import com.vonage.clientlibrary.SaaResult
import com.vonage.clientlibrary.SimBasedAuthzData
import com.vonage.clientlibrary.VGCellularRequestClient
import org.json.JSONObject

@OptIn(ExperimentalSaaApi::class)
fun performSilentAuthAdvanced(activity: Activity, verifyWebhookEventJson: String) {
    VGCellularRequestClient.initializeSdk(activity.applicationContext)

    // Parse the full Verify webhook event your backend received from Vonage.
    // fromVerifyEvent extracts request_id (the nonce) and
    // action.sim_based_authz_data.vpResponse. It throws IllegalArgumentException
    // if any of those required fields are missing.
    val authzData = SimBasedAuthzData.fromVerifyEvent(JSONObject(verifyWebhookEventJson))

    VGCellularRequestClient.getInstance().requestSilentAuthAdvancedToken(activity, authzData) { result ->
        when (result) {
            is SaaResult.Success -> {
                // Send result.token to your backend:
                // POST /v2/verify/{request_id} with { "token": result.token }
            }
            is SaaResult.DeepLinkRequired -> {
                // The native TS.43 path is unavailable on this device/carrier.
                // Launch the carrier's app to obtain the token.
                activity.startActivityForResult(result.intent, SAA_REQUEST_CODE)
                // Handle the returned token in onActivityResult (see below)
            }
            is SaaResult.Error -> {
                // result.code — SaaErrorCode enum value
                // result.message — human-readable description
            }
        }
    }
}
```

#### Handling the deep-link result

If `SaaResult.DeepLinkRequired` is returned, launch the intent and handle the response in `onActivityResult`:

```kotlin
@OptIn(ExperimentalSaaApi::class)
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == SAA_REQUEST_CODE) {
        val token = data?.getStringExtra("token")
        SilentAuthAdvancedManager().handleDeepLinkResult(token) { result ->
            when (result) {
                is SaaResult.Success -> { /* submit token to your backend */ }
                is SaaResult.Error -> { /* handle error */ }
                else -> {}
            }
        }
    }
}
```

#### Error codes

| Code | Meaning |
|---|---|
| `UNSUPPORTED_NETWORK` | Device or carrier does not support TS.43. No deep-link fallback available. |
| `MALFORMED_PAYLOAD` | The `sim_based_authz_data` payload is missing required fields. |
| `TOKEN_TOO_LARGE` | The operator token exceeds the 5 KB size limit. |
| `CANCELLED` | The credential request was cancelled by the user or system. |
| `UNKNOWN` | An unexpected error occurred. |

#### Testing with the virtual operator

You can test the SAA flow without a live carrier SIM using virtual operator phone numbers (prefix `+990`):

* `+990` number ending in an **even** digit → SAA succeeds with a test token
* `+990` number ending in an **odd** digit → SAA returns `UNSUPPORTED_NETWORK`

The virtual operator path is triggered automatically when the `phone_number_hint` claim in `sim_based_authz_data` starts with `+990`.


## Logging & Debugging

The SDK provides detailed debug logging to help diagnose integration issues. All debug output is gated behind the host app's debuggable flag (`ApplicationInfo.FLAG_DEBUGGABLE`) and is **only emitted when your app is built as a debug variant** — release builds produce no log output from this library.

### Viewing logs

Run your app via **Run → Debug** in Android Studio. Open the **Logcat** tab and filter by the SDK tags:

```
tag:VonageSAA | tag:VonageHttpLogger | tag:CellularClient | tag:CellularNetworkManager
```

Or filter all SDK output at once:

```
tag:Vonage | tag:CellularClient | tag:CellularNetworkManager
```

### Log tags reference

| Tag | Component | What it logs |
|---|---|---|
| `VonageSAA` | Silent Auth Advanced | Full SAA flow: incoming `authzData` payload, CredentialManager request/response JSON, token result or error, deep-link fallback |
| `VonageHttpLogger` | HTTP request logger | Structured request/response logging: method, URL, headers, body, status code, redirects, connection events, errors |
| `CellularClient` | Raw socket client | Low-level HTTP/1.1 request strings sent over the wire, response parsing, redirect handling, cookie management |
| `CellularNetworkManager` | Cellular network binding | Network forcing flow, cellular availability checks, timeout/callback events |

### Silent Auth Advanced (SAA) debug output

When debugging the SAA flow, the `VonageSAA` tag logs:

1. **Entry** — The full `SimBasedAuthzData` payload: `vpResponse.id`, `format`, `vctValues`, the first 50 characters of the `credentialAuthorizationJwt`, claims, `androidAppUrl`, and whether `appInfoJwt` is present.
2. **Native path check** — Whether the device meets the API 34 requirement and the TS.43 native path is available.
3. **CredentialManager request** — The exact JSON sent to the Android `DigitalCredentialManager` API.
4. **CredentialManager response** — The credential type, data keys, raw `credentialJson`, and the extracted token (truncated to 50 chars).
5. **Errors** — Any `GetCredentialException` details, missing token fields, or parsing failures.
6. **Deep-link fallback** — Logged when the native path is unavailable and the SDK falls back to the carrier app intent.

Example Logcat output for a successful SAA flow:

```
D/VonageSAA: ┌────── SAA: requestOperatorToken ──────────────────────────
D/VonageSAA: │ requestId (nonce): a2fd32bf-b13a-42a9-a325-69270216d204
D/VonageSAA: │ vpResponse.id: gnp
D/VonageSAA: │ vpResponse.format: dc-authorization+sd-jwt
D/VonageSAA: │ vpResponse.meta.vctValues: [number-verification/device-phone-number/ts43]
D/VonageSAA: │ vpResponse.meta.credentialAuthorizationJwt: eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3...
D/VonageSAA: │ vpResponse.claims: [VpClaim(path=[phone_number_hint], values=[+447234524553])]
D/VonageSAA: │ androidAppUrl: null
D/VonageSAA: │ appInfoJwt present: false
D/VonageSAA: └────────────────────────────────────────────────────────────
D/VonageSAA: isNativePathAvailable: true (SDK_INT=34, required>=34)
D/VonageSAA: Native TS.43 path available: true
D/VonageSAA: Requesting token via native CredentialManager...
D/VonageSAA: ┌────── CredentialManager Request ──────────────────────
D/VonageSAA: │ requestJson: {"requests":[{"protocol":"openid4vp-v1-unsigned","data":{"nonce":"a2fd32bf...","response_type":"vp_token","response_mode":"dc_api","dcql_query":{"credentials":[{"id":"gnp","format":"dc-authorization+sd-jwt","meta":{"vct_values":[...],"credential_authorization_jwt":"eyJhbG..."},"claims":[...]}]}}}]}
D/VonageSAA: └──────────────────────────────────────────────────────
D/VonageSAA: Calling credentialManager.getCredentialAsync...
D/VonageSAA: ┌────── CredentialManager Response ─────────────────────
D/VonageSAA: │ credential.type: type.GetDigitalCredentialOption
D/VonageSAA: │ credential.data keys: [credentialJson]
D/VonageSAA: │ credentialJson: {"token":"eyJ0b2tlbi..."}
D/VonageSAA: │ token: eyJ0b2tlbiI6InRlc3QtdG9rZW4tZm9yLXZlcmlma... (256 chars)
D/VonageSAA: └──────────────────────────────────────────────────────
D/VonageSAA: Token received (256 bytes)
D/VonageSAA: SAA completed successfully
```

### Cellular network request debug output

When debugging Silent Auth Standard (cellular GET requests), use `tag:VonageHttpLogger | tag:CellularClient`:

```
D/VonageHttpLogger: ┌────── HTTP REQUEST ──────────────────────────────────────
D/VonageHttpLogger: │ GET https://api.example.com/verify/check
D/VonageHttpLogger: │
D/VonageHttpLogger: │ Headers:
D/VonageHttpLogger: │   x-my-header: My Value
D/VonageHttpLogger: │
D/VonageHttpLogger: └──────────────────────────────────────────────────────────
D/VonageHttpLogger: ⚡ CONNECTION: Opening → api.example.com:443
D/VonageHttpLogger: ┌────── HTTP RESPONSE ─────────────────────────────────────
D/VonageHttpLogger: │ Status: 200
D/VonageHttpLogger: │
D/VonageHttpLogger: │ Body:
D/VonageHttpLogger: │   {"request_id":"abc123","status":"completed"}
D/VonageHttpLogger: │
D/VonageHttpLogger: └──────────────────────────────────────────────────────────
```

### Disabling console logs at runtime

If you want to suppress Logcat output from the SDK while keeping debug builds (e.g. for cleaner logs during unrelated debugging), call:

```kotlin
import com.vonage.clientlibrary.network.TraceCollector

TraceCollector.instance.shouldLogDebugInfoToConsole(false)
```

This only affects the `CellularClient` and `CellularNetworkManager` tags. The `VonageSAA` and `VonageHttpLogger` tags are controlled solely by the app's debuggable flag.

## Migrating from `com.vonage:client-sdk-silent-auth` or `com.vonage:client-sdk-number-verification`

`com.vonage:client-library` replaces both `com.vonage:client-sdk-silent-auth` and `com.vonage:client-sdk-number-verification`. To migrate from them do the following:

### Update your Dependencies:

You will need to add `com.vonage:client-library` as a [dependency](#installation) and remove either `com.vonage:client-sdk-silent-auth` or `com.vonage:client-sdk-number-verification` depending on which one you were using.

### Update the Imports:

```kotlin
// com.vonage:client-sdk-silent-auth
import com.vonage.silentauth.VGSilentAuthClient
``` 

or

```kotlin
// com.vonage:client-sdk-number-verification
import com.vonage.numberverification.VGNumberVerificationClient
``` 
 
should be replaced with:

```kotlin
import com.vonage.clientlibrary.VGCellularRequestClient
```

### Use the new Client:

```kotlin
// com.vonage:client-sdk-silent-auth
VGSilentAuthClient.initializeSdk(this.applicationContext)
``` 

or

```kotlin
// com.vonage:client-sdk-number-verification
VGNumberVerificationClient.initializeSdk(this.applicationContext)
``` 
 
should be replaced with:

```kotlin
VGCellularRequestClient.initializeSdk(this.applicationContext)
```

### Make the new Network Call:

`com.vonage:client-library` uses a params object to pass information to the function that makes the network call. This is a similar approach to `com.vonage:client-sdk-number-verification`, but new if you are using `com.vonage:client-sdk-silent-auth`.

```kotlin
// com.vonage:client-sdk-silent-auth
val resp: JSONObject = VGSilentAuthClient.getInstance().openWithDataCellular(URL(endpoint), false)
```

or 

```kotlin
// com.vonage:client-sdk-number-verification
val params = VGNumberVerificationParameters(
    url = "https://www.vonage.com",
    headers = mapOf("x-my-header" to "My Value"),
    queryParameters = mapOf("query-param" to "value"),
    maxRedirectCount = 10
)

val response = VGNumberVerificationClient.getInstance().startNumberVerification(params, true)
```

should be replaced with the `com.vonage:client-library` [example](#force-a-cellular-network-request) above.
