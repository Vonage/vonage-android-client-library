package com.vonage.clientlibrary.testapp

import android.app.Activity
import android.os.CancellationSignal
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialException
import com.vonage.clientlibrary.ExperimentalSaaApi
import com.vonage.clientlibrary.SimBasedAuthzData
import com.vonage.clientlibrary.buildTs43CredentialRequestJson
import org.json.JSONObject

/**
 * DEBUG-ONLY TEST HARNESS. Not part of the SDK's public surface.
 *
 * Lets a tester paste a raw **Verify webhook event** (as delivered to the
 * backend when a Silent Auth Advanced request enters `action_pending`), parse
 * it with the SDK's production [SimBasedAuthzData.fromVerifyEvent], build the
 * TS.43 request with [buildTs43CredentialRequestJson], and send it directly to
 * Android's `CredentialManager.getCredentialAsync` — bypassing
 * `SilentAuthAdvancedManager` so the request-shape can be observed in
 * isolation from the rest of the SAA flow (native-path check,
 * virtual-operator handling, error mapping, etc.).
 *
 * The expected input is the full event, e.g.:
 * ```json
 * {
 *   "request_id": "…",
 *   "action": { "sim_based_authz_data": { "vpResponse": { … } } }
 * }
 * ```
 */
private const val TAG = "SaaTestHarness"

@OptIn(ExperimentalSaaApi::class, ExperimentalDigitalCredentialApi::class)
@Composable
fun SaaTestHarnessScreen(activity: Activity, modifier: Modifier = Modifier) {
    var payloadText by remember { mutableStateOf("") }
    var outputText by remember { mutableStateOf("Paste a full Verify webhook event above, then tap Send.") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SAA TS.43 Request Test Harness (debug only)")

        OutlinedTextField(
            value = payloadText,
            onValueChange = { payloadText = it },
            label = { Text("Raw Verify webhook event JSON") },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        Button(
            onClick = {
                outputText = "Running..."
                runHarness(activity, payloadText) { result ->
                    outputText = result
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send to CredentialManager")
        }

        HorizontalDivider()

        Text("Result (also logged to Logcat, tag: $TAG):")
        SelectionContainer {
            Text(outputText)
        }
    }
}

/**
 * Parses [payloadText] with the SDK's production
 * [SimBasedAuthzData.fromVerifyEvent], builds the TS.43 requestJson with
 * [buildTs43CredentialRequestJson], and calls
 * `CredentialManager.getCredentialAsync` directly — bypassing
 * `SilentAuthAdvancedManager`.
 *
 * The full raw response or exception is logged to Logcat (tag "SaaTestHarness")
 * and reported back via [onResult] for on-screen display.
 */
@OptIn(ExperimentalSaaApi::class, ExperimentalDigitalCredentialApi::class)
private fun runHarness(
    activity: Activity,
    payloadText: String,
    onResult: (String) -> Unit
) {
    val authzData: SimBasedAuthzData
    try {
        authzData = SimBasedAuthzData.fromVerifyEvent(JSONObject(payloadText))
    } catch (e: Exception) {
        val msg = "Payload parse error: ${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, msg)
        onResult(msg)
        return
    }

    val requestJson = buildTs43CredentialRequestJson(authzData.requestId, authzData.vpResponse)

    Log.d(TAG, "┌────── SAA Test Harness: sending request ──────────────────")
    Log.d(TAG, "│ requestJson: $requestJson")
    Log.d(TAG, "└─────────────────────────────────────────────────────────────")

    try {
        val option = GetDigitalCredentialOption(requestJson)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val credentialManager = CredentialManager.create(activity)

        credentialManager.getCredentialAsync(
            context = activity,
            request = request,
            cancellationSignal = CancellationSignal(),
            executor = ContextCompat.getMainExecutor(activity),
            callback = object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val dataKeys = result.credential.data.keySet()
                    val sb = StringBuilder()
                    sb.appendLine("SUCCESS")
                    sb.appendLine("credential.type: ${result.credential.type}")
                    sb.appendLine("credential.data keys: $dataKeys")
                    for (key in dataKeys) {
                        sb.appendLine("  $key = ${result.credential.data.getString(key)}")
                    }
                    val output = sb.toString()
                    Log.d(TAG, "┌────── CredentialManager Response (raw) ────────────────")
                    Log.d(TAG, output)
                    Log.d(TAG, "└─────────────────────────────────────────────────────────")
                    onResult(output)
                }

                override fun onError(e: GetCredentialException) {
                    val output = "ERROR: ${e.javaClass.simpleName}: ${e.message}\n" +
                        "type: ${e.type}"
                    Log.e(TAG, "┌────── CredentialManager Error (raw) ───────────────────")
                    Log.e(TAG, output)
                    Log.e(TAG, "└─────────────────────────────────────────────────────────")
                    onResult(output)
                }
            }
        )
    } catch (e: Exception) {
        val msg = "Exception constructing/sending request: ${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, msg)
        onResult(msg)
    }
}
