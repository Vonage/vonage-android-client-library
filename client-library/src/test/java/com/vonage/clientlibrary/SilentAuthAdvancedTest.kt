package com.vonage.clientlibrary

import android.app.Activity
import io.mockk.*
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
    // Full Verify webhook event fixture
    // ------------------------------------------------------------------

    private val fullEventJson = """
        {
          "request_id": "a2fd32bf-b13a-42a9-a325-69270216d204",
          "triggered_at": "2026-07-16T15:02:43.374Z",
          "channel": "silent_auth",
          "status": "action_pending",
          "action": {
            "type": "auth",
            "sim_based_authz_data": {
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
          },
          "mode": "advanced",
          "type": "event"
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // SimBasedAuthzData.fromVerifyEvent — parsing tests
    // ------------------------------------------------------------------

    @Test
    fun `fromVerifyEvent parses request_id as the nonce`() {
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(fullEventJson))
        assertEquals("a2fd32bf-b13a-42a9-a325-69270216d204", data.requestId)
    }

    @Test
    fun `fromVerifyEvent parses vpResponse id and format`() {
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(fullEventJson))
        assertEquals("gnp", data.vpResponse.id)
        assertEquals("dc-authorization+sd-jwt", data.vpResponse.format)
    }

    @Test
    fun `fromVerifyEvent parses vpResponse meta vct_values`() {
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(fullEventJson))
        assertEquals(listOf("number-verification/device-phone-number/ts43"), data.vpResponse.meta.vctValues)
    }

    @Test
    fun `fromVerifyEvent parses vpResponse meta credential_authorization_jwt`() {
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(fullEventJson))
        assertEquals("aaa.bbb.ccc", data.vpResponse.meta.credentialAuthorizationJwt)
    }

    @Test
    fun `fromVerifyEvent parses claims path and values`() {
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(fullEventJson))
        assertEquals(1, data.vpResponse.claims.size)
        assertEquals(listOf("phone_number_hint"), data.vpResponse.claims[0].path)
        assertEquals(listOf("+467234524553"), data.vpResponse.claims[0].values)
    }

    @Test
    fun `fromVerifyEvent parses androidAppUrl appInfoJwt and iOSAppClipUrl`() {
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(fullEventJson))
        assertEquals("https://carrier.example.com/app?scope=verify", data.androidAppUrl)
        assertEquals("app-info-jwt-value", data.appInfoJwt)
        assertEquals("https://appclip.example.com/verify", data.iOSAppClipUrl)
    }

    @Test
    fun `fromVerifyEvent sets optional fields to null when absent`() {
        val minimal = """
            {
              "request_id": "req-1",
              "action": {
                "sim_based_authz_data": {
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
              }
            }
        """.trimIndent()
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(minimal))
        assertNull(data.androidAppUrl)
        assertNull(data.appInfoJwt)
        assertNull(data.iOSAppClipUrl)
    }

    @Test
    fun `fromVerifyEvent accepts sim_based_authz_data at the top level without action wrapper`() {
        val topLevel = """
            {
              "request_id": "req-2",
              "sim_based_authz_data": {
                "vpResponse": {
                  "id": "gnp",
                  "format": "dc-authorization+sd-jwt",
                  "meta": { "vct_values": [], "credential_authorization_jwt": "jwt" },
                  "claims": []
                }
              }
            }
        """.trimIndent()
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(topLevel))
        assertEquals("req-2", data.requestId)
        assertEquals("gnp", data.vpResponse.id)
    }

    @Test
    fun `fromVerifyEvent throws when request_id is missing`() {
        val noRequestId = """
            {
              "action": {
                "sim_based_authz_data": {
                  "vpResponse": {
                    "id": "gnp", "format": "f",
                    "meta": { "vct_values": [], "credential_authorization_jwt": "j" },
                    "claims": []
                  }
                }
              }
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            SimBasedAuthzData.fromVerifyEvent(JSONObject(noRequestId))
        }
    }

    @Test
    fun `fromVerifyEvent throws when sim_based_authz_data is missing`() {
        val noSimData = """{ "request_id": "req-1", "action": { "type": "auth" } }"""
        assertThrows(IllegalArgumentException::class.java) {
            SimBasedAuthzData.fromVerifyEvent(JSONObject(noSimData))
        }
    }

    @Test
    fun `fromVerifyEvent throws when vpResponse is missing`() {
        val noVpResponse = """
            {
              "request_id": "req-1",
              "action": { "sim_based_authz_data": { "androidAppUrl": "https://x" } }
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            SimBasedAuthzData.fromVerifyEvent(JSONObject(noVpResponse))
        }
    }

    @Test
    fun `fromVerifyEvent handles multiple claims`() {
        val json = """
            {
              "request_id": "req-1",
              "action": {
                "sim_based_authz_data": {
                  "vpResponse": {
                    "id": "gnp",
                    "format": "dc-authorization+sd-jwt",
                    "meta": { "vct_values": [], "credential_authorization_jwt": "jwt" },
                    "claims": [
                      { "path": ["carrier_hint"], "values": ["310150"] },
                      { "path": ["phone_number_hint"], "values": ["+1234567890"] }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val data = SimBasedAuthzData.fromVerifyEvent(JSONObject(json))
        assertEquals(2, data.vpResponse.claims.size)
        assertEquals("carrier_hint", data.vpResponse.claims[0].path[0])
        assertEquals("310150", data.vpResponse.claims[0].values[0])
        assertEquals("phone_number_hint", data.vpResponse.claims[1].path[0])
    }
}

@OptIn(ExperimentalSaaApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Ts43CredentialRequestBuilderTest {

    private fun sampleVpResponse(): VpResponse = VpResponse(
        id = "gnp",
        format = "dc-authorization+sd-jwt",
        meta = VpMeta(
            vctValues = listOf("number-verification/device-phone-number/ts43"),
            credentialAuthorizationJwt = "aaa.bbb.ccc"
        ),
        claims = listOf(
            VpClaim(path = listOf("carrier_hint"), values = listOf("310150")),
            VpClaim(path = listOf("phone_number_hint"), values = listOf("+467234524553"))
        )
    )

    @Test
    fun `builds the top-level requests array with unsigned protocol`() {
        val json = buildTs43CredentialRequestJson("req-123", sampleVpResponse())
        val parsed = JSONObject(json)

        val requests = parsed.getJSONArray("requests")
        assertEquals(1, requests.length())
        assertEquals("openid4vp-v1-unsigned", requests.getJSONObject(0).getString("protocol"))
    }

    @Test
    fun `data carries nonce from request_id and hardcoded response params`() {
        val json = buildTs43CredentialRequestJson("req-123", sampleVpResponse())
        val data = JSONObject(json).getJSONArray("requests").getJSONObject(0).getJSONObject("data")

        assertEquals("req-123", data.getString("nonce"))
        assertEquals("vp_token", data.getString("response_type"))
        assertEquals("dc_api", data.getString("response_mode"))
    }

    @Test
    fun `dcql_query credential carries id format and jwt inside meta`() {
        val json = buildTs43CredentialRequestJson("req-123", sampleVpResponse())
        val credential = JSONObject(json)
            .getJSONArray("requests").getJSONObject(0)
            .getJSONObject("data")
            .getJSONObject("dcql_query")
            .getJSONArray("credentials").getJSONObject(0)

        assertEquals("gnp", credential.getString("id"))
        assertEquals("dc-authorization+sd-jwt", credential.getString("format"))

        val meta = credential.getJSONObject("meta")
        assertEquals("number-verification/device-phone-number/ts43", meta.getJSONArray("vct_values").getString(0))
        // The JWT must be nested inside meta, next to vct_values.
        assertEquals("aaa.bbb.ccc", meta.getString("credential_authorization_jwt"))
    }

    @Test
    fun `dcql_query credential copies claims path and values`() {
        val json = buildTs43CredentialRequestJson("req-123", sampleVpResponse())
        val claims = JSONObject(json)
            .getJSONArray("requests").getJSONObject(0)
            .getJSONObject("data")
            .getJSONObject("dcql_query")
            .getJSONArray("credentials").getJSONObject(0)
            .getJSONArray("claims")

        assertEquals(2, claims.length())
        assertEquals("carrier_hint", claims.getJSONObject(0).getJSONArray("path").getString(0))
        assertEquals("310150", claims.getJSONObject(0).getJSONArray("values").getString(0))
        assertEquals("phone_number_hint", claims.getJSONObject(1).getJSONArray("path").getString(0))
        assertEquals("+467234524553", claims.getJSONObject(1).getJSONArray("values").getString(0))
    }

    @Test
    fun `end-to-end from webhook event produces a valid request`() {
        val event = """
            {
              "request_id": "req-xyz",
              "action": {
                "sim_based_authz_data": {
                  "vpResponse": {
                    "id": "gnp",
                    "format": "dc-authorization+sd-jwt",
                    "meta": { "vct_values": ["v"], "credential_authorization_jwt": "the-jwt" },
                    "claims": []
                  }
                }
              }
            }
        """.trimIndent()
        val authzData = SimBasedAuthzData.fromVerifyEvent(JSONObject(event))
        val json = buildTs43CredentialRequestJson(authzData.requestId, authzData.vpResponse)
        val data = JSONObject(json).getJSONArray("requests").getJSONObject(0).getJSONObject("data")

        assertEquals("req-xyz", data.getString("nonce"))
        val meta = data.getJSONObject("dcql_query").getJSONArray("credentials").getJSONObject(0).getJSONObject("meta")
        assertEquals("the-jwt", meta.getString("credential_authorization_jwt"))
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
        requestId: String = "req-123",
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
            requestId = requestId,
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
    // Request construction — the manager builds the full requestJson
    // ------------------------------------------------------------------

    @Test
    fun `native path sends a requestJson containing the nonce and jwt inside meta`() {
        every { mockProvider.isNativePathAvailable(any()) } returns true
        val requestJsonSlot = slot<String>()
        every { mockProvider.requestToken(any(), capture(requestJsonSlot), any()) } answers {
            thirdArg<(String?, Exception?) -> Unit>()(null, IllegalStateException("simulated OS rejection"))
        }

        val manager = SilentAuthAdvancedManager(mockProvider)
        // Use a non-virtual phone hint so it reaches the native path.
        manager.requestOperatorToken(mockActivity, makeAuthzData(requestId = "req-abc", phoneHint = "+15551234567")) {}

        val parsed = JSONObject(requestJsonSlot.captured)
        val data = parsed.getJSONArray("requests").getJSONObject(0).getJSONObject("data")
        assertEquals("req-abc", data.getString("nonce"))
        val meta = data.getJSONObject("dcql_query").getJSONArray("credentials").getJSONObject(0).getJSONObject("meta")
        assertEquals("aaa.bbb.ccc", meta.getString("credential_authorization_jwt"))
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
        manager.requestOperatorToken(mockActivity, makeAuthzData(phoneHint = "+15551234567")) { result = it }

        val success = result as SaaResult.Success
        assertEquals("valid-operator-token", success.token)
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
        manager.requestOperatorToken(mockActivity, makeAuthzData(phoneHint = "+15551234567")) { result = it }

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
            makeAuthzData(phoneHint = "+15551234567", androidAppUrl = "https://carrier.example.com/app")
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
            makeAuthzData(phoneHint = "+15551234567", androidAppUrl = "https://carrier.example.com/app", appInfoJwt = "my-app-jwt")
        ) { result = it }

        val deepLink = result as SaaResult.DeepLinkRequired
        assertEquals("my-app-jwt", deepLink.intent.getStringExtra(SilentAuthAdvancedManager.EXTRA_APP_INFO_JWT))
    }

    @Test
    fun `returns UNSUPPORTED_NETWORK when native path unavailable and no androidAppUrl`() {
        every { mockProvider.isNativePathAvailable(any()) } returns false

        val manager = SilentAuthAdvancedManager(mockProvider)
        var result: SaaResult? = null
        manager.requestOperatorToken(mockActivity, makeAuthzData(phoneHint = "+15551234567")) { result = it }

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
                phoneHint = "+15551234567",
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
        manager.requestOperatorToken(mockActivity, makeAuthzData(phoneHint = "+15551234567")) { result = it }

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
            makeAuthzData(phoneHint = "+15551234567", androidAppUrl = "https://carrier.example.com/app")
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
            makeAuthzData(phoneHint = "+15551234567", androidAppUrl = "https://carrier.example.com/app")
        ) { result = it }

        val error = result as SaaResult.Error
        assertEquals(SaaErrorCode.UNKNOWN, error.code)
    }
}

@OptIn(ExperimentalSaaApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OperatorTokenExtractionTest {

    // ------------------------------------------------------------------
    // extractOperatorToken — response-shape handling
    // ------------------------------------------------------------------

    @Test
    fun `virtual operator shape returns the flat token field`() {
        val token = extractOperatorToken("""{"token":"eyJ0b2tlbiI6InRlc3QifQ"}""")
        assertEquals("eyJ0b2tlbiI6InRlc3QifQ", token)
    }

    @Test
    fun `flat token is trimmed of surrounding whitespace before parsing`() {
        val token = extractOperatorToken("""  {"token":"abc123"}  """)
        assertEquals("abc123", token)
    }

    @Test
    fun `real dc_api vp_token response is forwarded verbatim`() {
        // A real carrier returns the full OpenID4VP response with no flat
        // `token` field; the whole response must be forwarded unchanged.
        val response = """{"vp_token":{"gnp":"eyJhbGciOiJSUzI1NiJ9.presentation.sig"}}"""
        val token = extractOperatorToken(response)
        assertEquals(response, token)
    }

    @Test
    fun `object with empty token field falls back to forwarding verbatim`() {
        val response = """{"token":"","vp_token":{"gnp":"abc"}}"""
        val token = extractOperatorToken(response)
        assertEquals(response, token)
    }

    @Test
    fun `non-json response is forwarded verbatim as a last resort`() {
        val token = extractOperatorToken("raw-opaque-operator-token")
        assertEquals("raw-opaque-operator-token", token)
    }

    @Test
    fun `blank response returns null`() {
        assertNull(extractOperatorToken("   "))
    }

    @Test
    fun `empty response returns null`() {
        assertNull(extractOperatorToken(""))
    }
}
