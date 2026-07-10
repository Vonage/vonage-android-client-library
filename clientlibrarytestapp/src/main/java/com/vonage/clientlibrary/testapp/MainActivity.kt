package com.vonage.clientlibrary.testapp

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.vonage.clientlibrary.VGCellularRequestClient
import com.vonage.clientlibrary.VGCellularRequestParameters
import com.vonage.clientlibrary.testapp.ui.theme.VonageClientLibraryTheme

class MainActivity : ComponentActivity() {

    private val myServerURL = "https://MYSERVER.com" //URL to your server running the Server SDK
    private val headers = mapOf("x-my-header" to "My Value") //Headers to be sent with the request (useful if your server requires authentication)
    private val queryParameters = mapOf("query-param" to "value") //Query parameters to be sent with the request.
    private val maxRedirectCount = 15 //Maximum number of redirects to follow, default is 10

    private val isDebugBuild: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VonageClientLibraryTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RootScreen(
                        activity = this,
                        showHarnessEntryPoint = isDebugBuild,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        VGCellularRequestClient.initializeSdk(this.applicationContext)

        val params = VGCellularRequestParameters(
            url = myServerURL,
            headers = headers,
            queryParameters = queryParameters,
            maxRedirectCount = maxRedirectCount
        )

        val response = VGCellularRequestClient.getInstance().startCellularGetRequest(params, false)
        if (response.optString("error") != "") {
            // error
        } else {
            val status = response.optInt("http_status") // Return HTTP status code
            val jsonReponse = response.getJSONObject("response_body") // Body of response parsed to JSON (NULL if not JSON)
            val rawReponse = response.optString("response_raw_body") // RAW string of response body (Only populated if not JSON)
            if (status == 200) {
                // 200 OK
            } else {
                // error
            }
        }

    }
}

/**
 * Root composable for the test app. Shows the standard greeting, and — only
 * in debug builds — an entry point into the SAA TS.43 request-shape test
 * harness (see [SaaTestHarnessScreen]). The harness is debug-only because it
 * is a manual testing tool, not a feature of the library.
 */
@Composable
private fun RootScreen(
    activity: android.app.Activity,
    showHarnessEntryPoint: Boolean,
    modifier: Modifier = Modifier
) {
    var showHarness by remember { mutableStateOf(false) }

    if (showHarness) {
        SaaTestHarnessScreen(activity = activity, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Greeting(name = "Android")
        if (showHarnessEntryPoint) {
            Button(onClick = { showHarness = true }) {
                Text("Open SAA Test Harness (debug only)")
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    VonageClientLibraryTheme {
        Greeting("Android")
    }
}