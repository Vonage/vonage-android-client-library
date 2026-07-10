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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.vonage.clientlibrary.buildDefaultRequestJson
import com.vonage.clientlibrary.buildMergedDcqlRequestJson
import com.vonage.clientlibrary.buildSignedPassthroughRequestJson
import org.json.JSONObject

/**
 * DEBUG-ONLY TEST HARNESS. Not part of the SDK's public surface.
 *
 * Lets a tester paste a raw `sim_based_authz_data` payload (as delivered by
 * a real Vonage Verify `action_pending` webhook), parse it with the SDK's
 * production [SimBasedAuthzData.fromJson], and try each of the three
 * candidate `requestJson` shapes directly against Android's
 * `CredentialManager.getCredentialAsync` — bypassing `SilentAuthAdvancedManager`
 * so the request-shape variable is isolated from the rest of the SAA flow
 * (native-path check, virtual-operator handling, error mapping, etc.).
 *
 * The three builders tried here ([buildDefaultRequestJson],
 * [buildSignedPassthroughRequestJson], [buildMergedDcqlRequestJson]) are
 * NOT CONFIRMED TO WORK except for the current production default. This
 * screen's purpose is to capture what the real OS/carrier returns for each
 * shape so that can be decided with evidence instead of spec-reading.
 *
 * `vpResponse` is not part of any fixed spec for this payload, so
 * [SimBasedAuthzData.fromJson] does not require it to be present — pasting a
 * payload without a top-level `vpResponse` key parses successfully with
 * [SimBasedAuthzData.vpResponse] set to `null`, which lets this harness be
 * used to test with and without it. All three builders require `vpResponse`
 * to construct a request, so a `null` `vpResponse` is reported as a harness
 * error before any request is sent.
 */
private enum class RequestBuilderChoice(val label: String) {
    DEFAULT("Default (buildDefaultRequestJson)"),
    SIGNED_PASSTHROUGH("Signed passthrough (EXPERIMENTAL)"),
    MERGED_DCQL("Merged DCQL (EXPERIMENTAL)")
}

private const val TAG = "SaaTestHarness"

@OptIn(ExperimentalSaaApi::class, ExperimentalDigitalCredentialApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SaaTestHarnessScreen(activity: Activity, modifier: Modifier = Modifier) {
    var payloadText by remember { mutableStateOf("") }
    var selectedChoice by remember { mutableStateOf(RequestBuilderChoice.DEFAULT) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var outputText by remember { mutableStateOf("Paste a sim_based_authz_data payload above, pick a request shape, then tap Send.") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SAA TS.43 Request-Shape Test Harness (debug only)")

        OutlinedTextField(
            value = payloadText,
            onValueChange = { payloadText = it },
            label = { Text("Raw sim_based_authz_data JSON") },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedChoice.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Request shape") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                RequestBuilderChoice.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        onClick = {
                            selectedChoice = choice
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        Button(
            onClick = {
                outputText = "Running..."
                runHarness(activity, payloadText, selectedChoice) { result ->
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
 * Parses [payloadText] with the SDK's production [SimBasedAuthzData.fromJson],
 * builds the requestJson for [choice], and calls
 * `CredentialManager.getCredentialAsync` directly — bypassing
 * `SilentAuthAdvancedManager` so only the request-shape variable changes
 * between attempts.
 *
 * The full raw response or exception is logged to Logcat (tag "SaaTestHarness")
 * and reported back via [onResult] for on-screen display.
 */
@OptIn(ExperimentalSaaApi::class, ExperimentalDigitalCredentialApi::class)
private fun runHarness(
    activity: Activity,
    payloadText: String,
    choice: RequestBuilderChoice,
    onResult: (String) -> Unit
) {
    val authzData: SimBasedAuthzData
    try {
        authzData = SimBasedAuthzData.fromJson(JSONObject(payloadText))
    } catch (e: Exception) {
        val msg = "JSON parse error: ${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, msg)
        onResult(msg)
        return
    }

    val vpResponse = authzData.vpResponse
    if (vpResponse == null) {
        val msg = "Pasted payload has no top-level vpResponse key — vpResponse is null. " +
            "All three request-shape builders require vpResponse, so no request was sent. " +
            "This is expected/testable: vpResponse is not part of any fixed spec for this " +
            "payload, so the SDK does not require it at parse time."
        Log.w(TAG, msg)
        onResult(msg)
        return
    }

    val jwt = vpResponse.meta.credentialAuthorizationJwt
    val requestJson = when (choice) {
        RequestBuilderChoice.DEFAULT -> buildDefaultRequestJson(jwt)
        RequestBuilderChoice.SIGNED_PASSTHROUGH -> buildSignedPassthroughRequestJson(jwt)
        RequestBuilderChoice.MERGED_DCQL -> buildMergedDcqlRequestJson(vpResponse, jwt)
    }

    Log.d(TAG, "┌────── SAA Test Harness: sending request ──────────────────")
    Log.d(TAG, "│ builder: ${choice.name}")
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
                    sb.appendLine("SUCCESS (builder=${choice.name})")
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
                    val output = "ERROR (builder=${choice.name}): ${e.javaClass.simpleName}: ${e.message}\n" +
                        "type: ${e.type}"
                    Log.e(TAG, "┌────── CredentialManager Error (raw) ───────────────────")
                    Log.e(TAG, output)
                    Log.e(TAG, "└─────────────────────────────────────────────────────────")
                    onResult(output)
                }
            }
        )
    } catch (e: Exception) {
        val msg = "Exception constructing/sending request (builder=${choice.name}): " +
            "${e.javaClass.simpleName}: ${e.message}"
        Log.e(TAG, msg)
        onResult(msg)
    }
}
