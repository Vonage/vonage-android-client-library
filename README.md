# Vonage Client Library

A library to support using the Vonage APIs on Android. Features:

* Force a cellular network request for use with [Vonage Number Verification](https://developer.vonage.com/en/number-verification/overview) and [Vonage Verify Silent Authentication](https://developer.vonage.com/en/verify/guides/silent-authentication)
* Retrieve TS.43 tokens for [Phone Number Verification](https://developer.android.com/identity/digital-credentials/phone-number-verification) using the Google Digital Credentials API.

## Installation

build.gradle -> dependencies add

```
implementation 'com.vonage:client-library:1.0.1'
```

## Usage

### Force a Cellular Network Request

```kotlin
import com.vonage.clientlibrary.VGCellularRequestClient
import com.vonage.clientlibrary.VGCellularRequestParameters

VGCellularRequestClient.initializeSdk(this.applicationContext)

val params = VGCellularRequestParameters(
    url = "http://www.vonage.com",
    headers = mapOf("x-my-header" to "My Value") ,
    queryParameters = mapOf("query-param" to "value"),
    maxRedirectCount = 10
)

val response = VGCellularRequestClient.getInstance().startCellularGetRequest(params, false)
if (response.has("error")) {
    // error
} else {
    val status = response.optInt("http_status")
    val jsonReponse = response.optJSONObject("response_body") // Body of response parsed to JSON (NULL if not JSON)
    val rawReponse = response.optString("response_raw_body") // RAW string of response body (Only populated if not JSON)
    if (status == 200) {
        // 200 OK
    } else {
        // error
    }
}
```
* `maxRedirectCount` in `VGCellularRequestParameters` is an optional and defaults to 10.
* `debug` parameter for `startCellularGetRequest` is optional and defaults to false.

### Retrieve TS.43 Token

You can use the SDK to retrieve a TS.43 token, which can then be exchanged with an aggregator for phone number verification.

```kotlin
import com.vonage.clientlibrary.VGCellularRequestClient

VGCellularRequestClient.getInstance().getTS43Token(this) { token, exception ->
    if (exception != null) {
        // Handle error
    } else if (token != null) {
        // Use token to exchange with aggregator
    }
}
```

#### Responses

* Success - When the data connectivity has been achieved, and a response has been received from the url endpoint:
```json
{
    "http_status": 200,
    "response_body" : {
        "status": "success",
        "message": "Verification successful"
    },
    "debug" : {
        "device_info": "Android 14", 
        "url_trace" : "..."
    }
}
```

* Error - When data connectivity is not available and/or an internal SDK error occurred:

```json
{
    "error" : "sdk_no_data_connectivity",
    "error_description": "The device is not connected to a cellular network.",
    "debug" : {
        "device_info": "Android 14", 
        "url_trace" : "..."
    }
}
```

Potential error codes: `sdk_no_data_connectivity`, `sdk_connection_error`, `sdk_redirect_error`, `sdk_error`.

## Migrating from `com.vonage:client-sdk-silent-auth` or `com.vonage:client-sdk-number-verification`

`com.vonage:client-library` replaces both `com.vonage:client-sdk-silent-auth` and `com.vonage:client-sdk-number-verification`
. To migrate from them do the following:

### Update your Dependencies:

You will need to add `com.vonage:client-library` as a [dependency](#installation) and remove either `com.vonage:client-sdk-silent-auth` or `com.vonage:client-sdk-number-verification`
 depending on which one you were using. 

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
        url = "http://www.vonage.com",
        headers = mapOf("x-my-header" to "My Value") ,
        queryParameters = mapOf("query-param" to "value"),
        maxRedirectCount = 10
    )

    val response = VGNumberVerificationClient.getInstance().startNumberVerification(params, true)
```

should be replaced with the `com.vonage:client-library` [example](#force-a-cellular-network-request) above.
