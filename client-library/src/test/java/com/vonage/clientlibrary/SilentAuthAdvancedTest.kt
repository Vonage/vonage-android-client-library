package com.vonage.clientlibrary

import android.app.Activity
import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalSaaApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SimBasedAuthzDataTest {

    // ------------------------------------------------------------------
    // Full payload fixture
    // ------------------------------------------------------------------

    private val fullPayloadJson = """
        {
          "vpResponse": {
            "id": "gnp",
            "format": "dc-authorization+sd-jwt",
            "meta": {
              "vct_values": ["number-verification/device-phone-number/ts43"],
              "credential_authorization_jwt": "aaa.bbb.ccc"
            },
            "claims": [
              {
                "path": ["phone_number_hint"],
                "values": ["+467234524553"]
              }
            ]
          },
          "androidAppUrl": "https://carrier.example.com/app?scope=verify",
          "appInfoJwt": "app-info-jwt-value",
          "iOSAppClipUrl": "https://appclip.example.com/verify"
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // SimBasedAuthzData.fromJson — parsing tests
    // ------------------------------------------------------------------

    @Test
    fun `fromJson parses vpResponse id and format`() {
        val data = SimBasedAuthzData.fromJson(JSONObject(fullPayloadJson))
        assertEquals("gnp", data.vpResponse!!.id)
        assertEquals("dc-authorization+sd-jwt", data.vpResponse!!.format)
    }

    @Test
    fun `fromJson parses vpResponse meta vct_values`() {
        val data = SimBasedAuthzData.fromJson(JSONObject(fullPayloadJson))
        assertEquals(listOf("number-verification/device-phone-number/ts43"), data.vpResponse!!.meta.vctValues)
    }

    @Test
    fun `fromJson parses vpResponse meta credential_authorization_jwt`() {
        val data = SimBasedAuthzData.fromJson(JSONObject(fullPayloadJson))
        assertEquals("aaa.bbb.ccc", data.vpResponse!!.meta.credentialAuthorizationJwt)
    }

    @Test
    fun `fromJson parses claims path and values`() {
        val data = SimBasedAuthzData.fromJson(JSONObject(fullPayloadJson))
        assertEquals(1, data.vpResponse!!.claims.size)
        assertEquals(listOf("phone_number_hint"), data.vpResponse!!.claims[0].path)
        assertEquals(listOf("+467234524553"), data.vpResponse!!.claims[0].values)
    }

    @Test
    fun `fromJson parses androidAppUrl`() {
        val data = SimBasedAuthzData.fromJson(JSONObject(fullPayloadJson))
        assertEquals("https://carrier.example.com/app?scope=verify", data.androidAppUrl)
    }

    @Test
    fun `fromJson parses appInfoJwt`() {
        val data = SimBasedAuthzData.fromJson(JSONObject(fullPayloadJson))
        assertEquals("app-info-jwt-value", data.appInfoJwt)
    }

    @Test
    fun `fromJson parses iOSAppClipUrl`() {
        val data = SimBasedAuthzData.fromJson(JSONObject(fullPayloadJson))
        assertEquals("https://appclip.example.com/verify", data.iOSAppClipUrl)
    }

    @Test
    fun `fromJson sets optional fields to null when absent`() {
        val minimal = """
            {
              "vpResponse": {
                "id": "gnp",
                "format": "dc-authorization+sd-jwt",
                "meta": {
                  "vct_values": [],
                  "credential_authorization_jwt": "aaa.bbb.ccc"
                },
                "claims": []
              }
            }
        """.trimIndent()
        val data = SimBasedAuthzData.fromJson(JSONObject(minimal))
        assertNull(data.androidAppUrl)
        assertNull(data.appInfoJwt)
        assertNull(data.iOSAppClipUrl)
    }

    @Test
    fun `fromJson treats JSON null values for optional string fields as null`() {
        // Issue 1: JSONObject.optString returns the literal string "null" for JSON null;
        // optStringOrNull must guard against that.
        val withJsonNulls = """
            {
              "vpResponse": {
                "id": "gnp",
                "format": "dc-authorization+sd-jwt",
                "meta": {
                  "vct_values": [],
                  "credential_authorization_jwt": "aaa.bbb.ccc"
                },
                "claims": []
              },
              "androidAppUrl": null,
              "appInfoJwt": null,
              "iOSAppClipUrl": null
            }
        """.trimIndent()
        val data = SimBasedAuthzData.fromJson(JSONObject(withJsonNulls))
        assertNull(data.androidAppUrl)
        assertNull(data.appInfoJwt)
        assertNull(data.iOSAppClipUrl)
    }

    @Test
    fun `fromJson treats empty string values for optional string fields as null`() {
        val withEmptyStrings = """
            {
              "vpResponse": {
                "id": "gnp",
                "format": "dc-authorization+sd-jwt",
                "meta": {
                  "vct_values": [],
                  "credential_authorization_jwt": "aaa.bbb.ccc"
                },
                "claims": []
              },
              "androidAppUrl": "",
              "appInfoJwt": "",
              "iOSAppClipUrl": ""
            }
        """.trimIndent()
        val data = SimBasedAuthzData.fromJson(JSONObject(withEmptyStrings))
        assertNull(data.androidAppUrl)
        assertNull(data.appInfoJwt)
        assertNull(data.iOSAppClipUrl)
    }

    @Test
    fun `fromJson sets vpResponse to null when absent`() {
        // vpResponse is not part of any fixed spec for this payload — its
        // absence is not treated as malformed input at parse time.
        val data = SimBasedAuthzData.fromJson(JSONObject("{}"))
        assertNull(data.vpResponse)
    }

    @Test
    fun `fromJson handles multiple claims`() {
        val json = """
            {
              "vpResponse": {
                "id": "gnp",
                "format": "dc-authorization+sd-jwt",
                "meta": {
                  "vct_values": [],
                  "credential_authorization_jwt": "jwt"
                },
                "claims": [
                  { "path": ["phone_number_hint"], "values": ["+1234567890"] },
                  { "path": ["country"], "values": ["US"] }
                ]
              }
            }
        """.trimIndent()
        val data = SimBasedAuthzData.fromJson(JSONObject(json))
        assertEquals(2, data.vpResponse!!.claims.size)
        assertEquals("country", data.vpResponse!!.claims[1].path[0])
        assertEquals("US", data.vpResponse!!.claims[1].values[0])
    }
}

@OptIn(ExperimentalSaaApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SaaRequestBuildersTest {

    // ------------------------------------------------------------------
    // buildDefaultRequestJson — Task 1 refactor, literal output check
    // ------------------------------------------------------------------

    @Test
    fun `buildDefaultRequestJson produces literal expected JSON`() {
        val json = buildDefaultRequestJson("aaa.bbb.ccc")
        assertEquals(
            JSONObject(mapOf("credential_authorization_jwt" to "aaa.bbb.ccc")).toString(),
            json
        )
        assertEquals("aaa.bbb.ccc", JSONObject(json).getString("credential_authorization_jwt"))
    }

    // ------------------------------------------------------------------
    // buildSignedPassthroughRequestJson
    // ------------------------------------------------------------------

    @Test
    fun `buildSignedPassthroughRequestJson wraps jwt under protocol and data-request`() {
        val json = buildSignedPassthroughRequestJson("aaa.bbb.ccc")
        val parsed = JSONObject(json)

        assertEquals("openid4vp-v1-signed", parsed.getString("protocol"))
        assertEquals("aaa.bbb.ccc", parsed.getJSONObject("data").getString("request"))
    }

    @Test
    fun `buildSignedPassthroughRequestJson produces literal expected JSON`() {
        val json = buildSignedPassthroughRequestJson("aaa.bbb.ccc")
        val expected = JSONObject()
            .put("protocol", "openid4vp-v1-signed")
            .put("data", JSONObject().put("request", "aaa.bbb.ccc"))
            .toString()
        assertEquals(expected, json)
    }

    // ------------------------------------------------------------------
    // buildMergedDcqlRequestJson
    // ------------------------------------------------------------------

    private fun sampleVpResponse(): VpResponse = VpResponse(
        id = "gnp",
        format = "dc-authorization+sd-jwt",
        meta = VpMeta(
            vctValues = listOf("number-verification/device-phone-number/ts43"),
            credentialAuthorizationJwt = "aaa.bbb.ccc"
        ),
        claims = listOf(VpClaim(path = listOf("phone_number_hint"), values = listOf("+467234524553")))
    )

    @Test
    fun `buildMergedDcqlRequestJson produces literal expected JSON`() {
        val json = buildMergedDcqlRequestJson(sampleVpResponse(), "aaa.bbb.ccc")

        val credential = JSONObject()
            .put("id", "gnp")
            .put("format", "dc-authorization+sd-jwt")
            .put("meta", JSONObject().put("vct_values", JSONArray(listOf("number-verification/device-phone-number/ts43"))))
            .put("claims", JSONArray().put(
                JSONObject()
                    .put("path", JSONArray(listOf("phone_number_hint")))
                    .put("values", JSONArray(listOf("+467234524553")))
            ))
        val dcqlQuery = JSONObject().put("credentials", JSONArray().put(credential))
        val expected = JSONObject()
            .put("protocol", "openid4vp-v1-unsigned")
            .put("data", JSONObject()
                .put("response_type", "vp_token")
                .put("response_mode", "dc_api")
                .put("dcql_query", dcqlQuery)
                .put("request", "aaa.bbb.ccc")
            )
            .toString()

        assertEquals(expected, json)
    }

    @Test
    fun `buildMergedDcqlRequestJson embeds vpResponse fields into dcql_query credentials`() {
        val json = buildMergedDcqlRequestJson(sampleVpResponse(), "aaa.bbb.ccc")
        val parsed = JSONObject(json)

        assertEquals("openid4vp-v1-unsigned", parsed.getString("protocol"))
        val data = parsed.getJSONObject("data")
        assertEquals("vp_token", data.getString("response_type"))
        assertEquals("dc_api", data.getString("response_mode"))
        assertEquals("aaa.bbb.ccc", data.getString("request"))

        val credential = data.getJSONObject("dcql_query").getJSONArray("credentials").getJSONObject(0)
        assertEquals("gnp", credential.getString("id"))
        assertEquals("dc-authorization+sd-jwt", credential.getString("format"))
        assertEquals(
            "phone_number_hint",
            credential.getJSONArray("claims").getJSONObject(0).getJSONArray("path").getString(0)
        )
    }
}

@OptIn(ExperimentalSaaApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SilentAuthAdvancedManagerTest {

    private lateinit var mockActivity: Activity
    private lateinit var mockProvider: SaaTokenProvider

    @Before
    fun setUp() {
        mockActivity = mockk(relaxed = true)
        mockProvider = mockk()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun makeAuthzData(
        jwt: String = "aaa.bbb.ccc",
        phoneHint: String? = "+467234524553",
        androidAppUrl: String? = null,
        appInfoJwt: String? = null
    ): SimBasedAuthzData {
        val claims = if (phoneHint != null) {
            listOf(VpClaim(path = listOf("phone_number_hint"), values = listOf(phoneHint)))
        } else {
            emptyList()
        }
        return SimBasedAuthzData(
            vpResponse = VpResponse(
                id = "gnp",
                format = "dc-authorization+sd-jwt",
                meta = VpMeta(
                    vctValues = listOf("number-verification/device-phone-number/ts43"),
                    credentialAuthorizationJwt = jwt
                ),
                claims = claims
            ),
            androidAppUrl = androidAppUrl,
            appInfoJwt = appInfoJwt
        )
    }

    // ------------------------------------------------------------------
    // Malformed payload
    // ------------------------------------------------------------------

    @Test
    fun `returns MALFORMED_PAYLOAD error when vpResponse is null`() {
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        val authzData = SimBasedAuthzData(
            vpResponse = null,
            androidAppUrl = null,
            appInfoJwt = null
        )
        manager.requestOperatorToken(mockActivity, authzData) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.MALFORMED_PAYLOAD, error.code)
    }

    @Test
    fun `returns MALFORMED_PAYLOAD error when credential_authorization_jwt is blank`() {
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData(jwt = "")) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.MALFORMED_PAYLOAD, error.code)
    }

    // ------------------------------------------------------------------
    // Virtual operator
    // ------------------------------------------------------------------

    @Test
    fun `virtual operator with even last digit returns Success`() {
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData(phoneHint = "+9901234")) { result = it }

        val success = result as SaaResult.Success
        assertEquals(SilentAuthAdvancedManager.VIRTUAL_OPERATOR_TEST_TOKEN, success.token)
    }

    @Test
    fun `virtual operator with odd last digit returns UNSUPPORTED_NETWORK error`() {
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData(phoneHint = "+9901235")) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.UNSUPPORTED_NETWORK, error.code)
    }

    @Test
    fun `virtual operator with zero last digit (even) returns Success`() {
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData(phoneHint = "+9900")) { result = it }

        assertTrue(result is SaaResult.Success)
    }

    // ------------------------------------------------------------------
    // Native TS.43 happy path
    // ------------------------------------------------------------------

    @Test
    fun `native path returns Success when provider returns token`() {
        every { mockProvider.isNativePathAvailable(any()) } returns true
        every { mockProvider.requestToken(any(), any(), any()) } answers {
            val callback = thirdArg<(String?, Exception?) -> Unit>()
            callback("valid-operator-token", null)
        }

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData()) { result = it }

        val success = result as SaaResult.Success
        assertEquals("valid-operator-token", success.token)
    }

    @Test
    fun `native path returns TOKEN_TOO_LARGE when token exceeds 5KB`() {
        val oversizedToken = "x".repeat(SilentAuthAdvancedManager.MAX_TOKEN_BYTES + 1)
        every { mockProvider.isNativePathAvailable(any()) } returns true
        every { mockProvider.requestToken(any(), any(), any()) } answers {
            val callback = thirdArg<(String?, Exception?) -> Unit>()
            callback(oversizedToken, null)
        }

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData()) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.TOKEN_TOO_LARGE, error.code)
    }

    @Test
    fun `native path returns UNKNOWN error when provider returns null token and null error`() {
        every { mockProvider.isNativePathAvailable(any()) } returns true
        every { mockProvider.requestToken(any(), any(), any()) } answers {
            val callback = thirdArg<(String?, Exception?) -> Unit>()
            callback(null, null)
        }

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData()) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.UNKNOWN, error.code)
    }

    // ------------------------------------------------------------------
    // Deep-link fallback
    // ------------------------------------------------------------------

    @Test
    fun `returns DeepLinkRequired when native path unavailable and androidAppUrl present`() {
        every { mockProvider.isNativePathAvailable(any()) } returns false

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(
            mockActivity,
            makeAuthzData(androidAppUrl = "https://carrier.example.com/app")
        ) { result = it }

        assertTrue(result is SaaResult.DeepLinkRequired)
        val deepLink = result as SaaResult.DeepLinkRequired
        assertEquals("https://carrier.example.com/app", deepLink.intent.data?.toString())
    }

    @Test
    fun `DeepLinkRequired intent carries appInfoJwt as extra`() {
        every { mockProvider.isNativePathAvailable(any()) } returns false

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(
            mockActivity,
            makeAuthzData(androidAppUrl = "https://carrier.example.com/app", appInfoJwt = "my-app-jwt")
        ) { result = it }

        val deepLink = result as SaaResult.DeepLinkRequired
        assertEquals("my-app-jwt", deepLink.intent.getStringExtra(SilentAuthAdvancedManager.EXTRA_APP_INFO_JWT))
    }

    @Test
    fun `returns UNSUPPORTED_NETWORK when native path unavailable and no androidAppUrl`() {
        every { mockProvider.isNativePathAvailable(any()) } returns false

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData()) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.UNSUPPORTED_NETWORK, error.code)
    }

    // ------------------------------------------------------------------
    // handleDeepLinkResult
    // ------------------------------------------------------------------

    @Test
    fun `handleDeepLinkResult returns Success for valid token`() {
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.handleDeepLinkResult("carrier-returned-token") { result = it }

        val success = result as SaaResult.Success
        assertEquals("carrier-returned-token", success.token)
    }

    @Test
    fun `handleDeepLinkResult returns CANCELLED for null token`() {
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.handleDeepLinkResult(null) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.CANCELLED, error.code)
    }

    @Test
    fun `handleDeepLinkResult returns CANCELLED for blank token`() {
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.handleDeepLinkResult("   ") { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.CANCELLED, error.code)
    }

    @Test
    fun `handleDeepLinkResult returns TOKEN_TOO_LARGE for oversized token`() {
        val oversizedToken = "x".repeat(SilentAuthAdvancedManager.MAX_TOKEN_BYTES + 1)
        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.handleDeepLinkResult(oversizedToken) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.TOKEN_TOO_LARGE, error.code)
    }

    // ------------------------------------------------------------------
    // Provider-error mapping
    // ------------------------------------------------------------------

    @Test
    fun `provider GetCredentialUnsupportedException falls back to DeepLinkRequired when androidAppUrl present`() {
        every { mockProvider.isNativePathAvailable(any()) } returns true
        every { mockProvider.requestToken(any(), any(), any()) } answers {
            val cb = thirdArg<(String?, Exception?) -> Unit>()
            cb(null, androidx.credentials.exceptions.GetCredentialUnsupportedException("unsupported"))
        }

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(
            mockActivity,
            makeAuthzData(
                androidAppUrl = "https://carrier.example.com/app",
                appInfoJwt = "my-app-jwt"
            )
        ) { result = it }

        val deepLink = result as SaaResult.DeepLinkRequired
        assertEquals("https://carrier.example.com/app", deepLink.intent.data?.toString())
        // Issue 3: appInfoJwt extra must also be set on the error-fallback deep-link path
        assertEquals("my-app-jwt", deepLink.intent.getStringExtra(SilentAuthAdvancedManager.EXTRA_APP_INFO_JWT))
    }

    @Test
    fun `provider GetCredentialUnsupportedException returns UNSUPPORTED_NETWORK when no androidAppUrl`() {
        every { mockProvider.isNativePathAvailable(any()) } returns true
        every { mockProvider.requestToken(any(), any(), any()) } answers {
            val cb = thirdArg<(String?, Exception?) -> Unit>()
            cb(null, androidx.credentials.exceptions.GetCredentialUnsupportedException("unsupported"))
        }

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData()) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.UNSUPPORTED_NETWORK, error.code)
    }

    @Test
    fun `provider GetCredentialCancellationException surfaces CANCELLED even when androidAppUrl present`() {
        // Issue 2: cancellation must NOT re-launch the carrier app behind the user's back
        every { mockProvider.isNativePathAvailable(any()) } returns true
        every { mockProvider.requestToken(any(), any(), any()) } answers {
            val cb = thirdArg<(String?, Exception?) -> Unit>()
            cb(null, androidx.credentials.exceptions.GetCredentialCancellationException("user cancelled"))
        }

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(
            mockActivity,
            makeAuthzData(androidAppUrl = "https://carrier.example.com/app")
        ) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.CANCELLED, error.code)
    }

    @Test
    fun `provider generic exception surfaces UNKNOWN even when androidAppUrl present`() {
        // Issue 2: unrelated errors must NOT re-route to deep-link
        every { mockProvider.isNativePathAvailable(any()) } returns true
        every { mockProvider.requestToken(any(), any(), any()) } answers {
            val cb = thirdArg<(String?, Exception?) -> Unit>()
            cb(null, RuntimeException("network blew up"))
        }

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(
            mockActivity,
            makeAuthzData(androidAppUrl = "https://carrier.example.com/app")
        ) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.UNKNOWN, error.code)
    }
}
