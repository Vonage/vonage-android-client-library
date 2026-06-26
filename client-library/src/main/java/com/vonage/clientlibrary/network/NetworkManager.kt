package com.vonage.clientlibrary.network

import com.vonage.clientlibrary.CellularStatus
import org.json.JSONObject
import java.net.URL

internal interface NetworkManager {
    fun getCellularStatus(): CellularStatus
    fun openWithDataCellular(url: URL, headers: Map<String, String>?, maxRedirectCount: Int, debug: Boolean): JSONObject
    fun postWithDataCellular(url: URL, headers: Map<String, String>, body: String?): JSONObject
}