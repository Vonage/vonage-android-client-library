package com.vonage.clientlibrary.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.os.Build
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.schedule
import kotlin.concurrent.withLock

internal class CellularNetworkManager(context: Context) : NetworkManager {

    private val cellularInfo by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var cellularNetworkCallBack: ConnectivityManager.NetworkCallback? = null

    private var timeoutTask: TimerTask? = null

    private val tracer = TraceCollector.instance

    override fun openWithDataCellular(url: URL, headers: Map<String, String>?, maxRedirectCount: Int, debug: Boolean): JSONObject {
        var calledOnCellularNetwork = false
        var response: JSONObject = JSONObject()
        tracer.addDebug(Log.DEBUG, TAG, "Triggering open check url")

        if (debug)
            checkNetworks()
        execute {
            calledOnCellularNetwork = it
            if (it) {
                if (debug) {
                    tracer.addDebug(
                        Log.DEBUG,
                        TAG,
                        "-> After forcing isAvailable? ${isCellularAvailable()}"
                    )
                    tracer.addDebug(
                        Log.DEBUG,
                        TAG,
                        "-> After forcing isBound? ${isCellularBoundToProcess()}"
                    )
                }
                // We have Mobile Data registered and bound for use
                // However, user may still have no data plan!
                val cs = ClientSocket()
                if (debug) tracer.startTrace()
                response = cs.open(url, headers, getOperator(), maxRedirectCount)
                if (debug) {
                    val json = JSONObject()
                    json.put("device_info", deviceInfo())
                    json.put("url_trace", tracer.getTrace().trace)
                    response.put("debug", json)
                    tracer.stopTrace()
                }
            } else {
                tracer.addDebug(Log.DEBUG, TAG, "We do not have a path")
                response = sendError("sdk_no_data_connectivity", "Data connectivity not available")
            }
        }
        if (response.length() == 0)
            response = sendError("sdk_error", "internal error")
        return response
    }

    override fun postWithDataCellular(url: URL, headers: Map<String, String>, body: String?): JSONObject {
        var calledOnCellularNetwork = false
        var response: JSONObject = JSONObject()
        tracer.addDebug(Log.DEBUG, TAG, "Triggering postWithDataCellular")

        execute {
            calledOnCellularNetwork = it
            if (it) {
                // We have Mobile Data registered and bound for use
                // However, user may still have no data plan!
                val cs = ClientSocket()
                response = cs.post(url, headers, body)
            } else {
                tracer.addDebug(Log.DEBUG, TAG, "We do not have a path")
            }
        }
        if (response.length() == 0)
            response = sendError("sdk_error", "internal error")
        return response
    }
    private fun sendError(code: String, description: String): JSONObject {
        val json: JSONObject = JSONObject()
        json.put("error", code)
        json.put("error_description", description)
        return json
    }

    private fun execute(onCompletion: (isSuccess: Boolean) -> Unit) {
        try {
            val lock = ReentrantLock()
            val condition = lock.newCondition()

            val capabilities = intArrayOf(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val transportTypes = intArrayOf(NetworkCapabilities.TRANSPORT_CELLULAR)

            forceCellular(capabilities, transportTypes) { isOnCellular ->
                lock.withLock {
                    // We have Mobile Data registered and bound for use
                    // However, user may still have no data plan!
                    onCompletion(isOnCellular)
                    condition.signal()
                }
            }

            lock.withLock {
                condition.await()
            }
        } catch (ex: Exception) {
            tracer.addDebug(Log.ERROR, TAG, "execute exception ${ex.message}")
            onCompletion(false)
        }
    }

    /**
     * Configures a network with the capabilities and transport types, registers it and when it is
     * available calls onCompletion lambda with a value of true.
     * 5/20 secs timeout applies if the network never registers, then the lambda is called with
     * a value of false.
     *
     * Requests cellular network. Even though the device may have mobile data, and enabled,
     * some Android devices may not show it on the available networks list. They tend to set WiFi
     * as the default and active network. This method requests the mobile data network to be
     * available, and when available it bind the network to the process to be used later.
     *
     * Whether Cellular Network is Active Network OR (Available and Bound to the process)
     * isCellularActiveNetwork() || (isCellularAvailable() && isCellularBoundToProcess())
     * OR NOT
     * We are requesting cellular data network. If it it no disabled by the user or by network,
     * it should be available. A further optimisation can be done perhaps, with helper check methods.
     *
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun forceCellular(capabilities: IntArray, transportTypes: IntArray, onCompletion: (isSuccess: Boolean) -> Unit) {

        tracer.addDebug(Log.DEBUG, TAG, "------ Forcing Cellular ------")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!cellularInfo.isDataEnabled) {
                Log.d(TAG, "Mobile Data is NOT enabled, we can not force cellular!")
                Thread {
                    tracer.addDebug(
                        Log.DEBUG,
                        TAG,
                        "Calling completion -- Is Main thread? ${isMainThread()}"
                    )
                    onCompletion(false)
                }.start()
                return
            } else {
                tracer.addDebug(Log.DEBUG, TAG, "-> Mobile Data is Enabled!")
            }
        }

        if (cellularNetworkCallBack == null) {
            cellularNetworkCallBack = object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    tracer.addDebug(Log.DEBUG, TAG, "Cellular OnAvailable:")
                    networkInfo(network)
                    try {
                        // Binds the current process to network.  All Sockets created in the future
                        // (and not explicitly bound via a bound SocketFactory from {@link Network#getSocketFactory() Network.getSocketFactory()})
                        // will be bound to network.
                        tracer.addDebug(Log.DEBUG, TAG, "  Binding to process:")
                        bind(network)
                        tracer.addDebug(Log.DEBUG, TAG, "  Binding finished. Is Main thread? ${isMainThread()}")
                        cancelTimeout()
                        onCompletion(true) // Network request needs to be done in this lambda
                    } catch (e: IllegalStateException) {
                        tracer.addDebug(Log.ERROR, TAG, "ConnectivityManager.NetworkCallback.onAvailable: $e")
                        cancelTimeout()
                        onCompletion(false)
                    } finally {
                        // Release the request when done.
                        unregisterCellularNetworkListener()
                        bind(null)
                    }
                }

                override fun onLost(network: Network) {
                    tracer.addDebug(Log.DEBUG, TAG, "Cellular OnLost:")
                    networkInfo(network)
                    super.onLost(network)
                }

                override fun onUnavailable() {
                    tracer.addDebug(Log.DEBUG, TAG, "Cellular onUnavailable")
                    // When this method gets called due to timeout, the callback will automatically be unregistered
                    // So no need to call unregisterCellularNetworkListener()
                    // But we should null it
                    cellularNetworkCallBack = null
                    onCompletion(false)
                    super.onUnavailable()
                }
            }

            tracer.addDebug(Log.DEBUG, TAG, "Creating a network builder on Main thread? ${isMainThread()}")

            val request = NetworkRequest.Builder()
            // Just in case as per Documentation
            request.removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            request.removeTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)

            for (capability in capabilities) {
                request.addCapability(capability)
            }
            for (transportType in transportTypes) {
                request.addTransportType(transportType)
            }

            tracer.addDebug(Log.DEBUG, TAG, "Cellular requested")

            requestNetwork(request.build(), onCompletion)

            tracer.addDebug(Log.DEBUG, TAG, "Forcing Cellular - Requesting to registered...")
        } else {
            // Perhaps there is already one registered, and in progress or waiting to be timed out
            tracer.addDebug(Log.DEBUG, TAG, "There is already a Listener registered.")
        }
    }

    /**
     * Return the Country Code of the Carrier + MCC + MNC
     * in uppercase
     */
    private fun getOperator(): String? {
        if (cellularInfo.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            val op: String = cellularInfo.simOperator
            tracer.addDebug(Log.DEBUG, TAG, "-> getOperator $op")
            return op
        } else
            tracer.addDebug(Log.DEBUG, TAG, "-> getOperator not PHONE_TYPE_GSM!")
        return null
    }

    private fun bind(network: Network?) {
        connectivityManager.bindProcessToNetwork(network)
    }

    private fun requestNetwork(request: NetworkRequest, onCompletion: (isSuccess: Boolean) -> Unit) {
        // The network request will live, until unregisterNetworkCallback is called or app exit.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { // API Level 26
            timeoutTask = Timer("Setting Up", true).schedule(TIME_OUT) {
                tracer.addDebug(Log.DEBUG, TAG, "Timeout...")
                Thread { onCompletion(false) }.start()
            }
            connectivityManager.requestNetwork(
                request,
                cellularNetworkCallBack as ConnectivityManager.NetworkCallback
            )
        } else {
            connectivityManager.requestNetwork(
                request,
                cellularNetworkCallBack as ConnectivityManager.NetworkCallback,
                TIME_OUT.toInt()
            )
        }
    }

    private fun cancelTimeout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            tracer.addDebug(Log.DEBUG, TAG, "Cancelling timeout")
            timeoutTask?.cancel()
        }
    }

    private fun unregisterCellularNetworkListener() {
        Log.d(TAG, "UnregisteringCellularNetworkListener")
        cellularNetworkCallBack?.let {
            tracer.addDebug(Log.DEBUG, TAG, "CallBack available, unregistering it.")
            connectivityManager.unregisterNetworkCallback(cellularNetworkCallBack as ConnectivityManager.NetworkCallback)
            cellularNetworkCallBack = null
        }
    }

    private fun isMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    private fun checkNetworks() {
        tracer.addDebug(Log.DEBUG, TAG, "----- Check Network ------")
        tracer.addDebug(Log.DEBUG, TAG, "Is Default Network Active? " + connectivityManager.isDefaultNetworkActive.toString())
        boundNetwork()
        activeNetworkInfo()
        availableNetworks()
    }

    private fun isCellular(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network)
        caps?.let {
            if (it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            ) {
                return true
            }
        }
        return false
    }

    private fun isCellularAvailable(): Boolean {
        val networks = connectivityManager.allNetworks
        var available = false
        for (network in networks) {
            available = isCellular(network)
            if (available) break
        }
        return available
    }

    private fun isCellularBoundToProcess(): Boolean {
        connectivityManager.boundNetworkForProcess?.let {
            return isCellular(it)
        }
        return false
    }

    companion object {
        private const val TAG = "CellularNetworkManager"
        private const val TIME_OUT: Long = 5000
    }

    private fun boundNetwork() { // API 23
        tracer.addDebug(Log.DEBUG, TAG, "----- Bound network ----")
        connectivityManager.boundNetworkForProcess?.let { networkInfo(it) }
    }

    private fun activeNetworkInfo() { // API 23
        tracer.addDebug(Log.DEBUG, TAG, "---- Active network ----")
        connectivityManager.activeNetwork?.let { networkInfo(it) }
    }

    @RequiresApi(api = Build.VERSION_CODES.R) // 30
    private fun networkType(capability: NetworkCapabilities) {
        when (capability.networkSpecifier) {
            is TelephonyNetworkSpecifier -> Log.d(TAG, "Cellular network")
            is WifiNetworkSpecifier -> Log.d(TAG, "Wifi network")
            is WifiAwareNetworkSpecifier -> Log.d(TAG, "Wifi Aware network")
        }
    }

    private fun availableNetworks() {
        tracer.addDebug(Log.DEBUG, TAG, "----------Available Networks----------")

        val networks = connectivityManager.allNetworks
        for (network in networks) {

            networkInfo(network)

            val caps = connectivityManager.getNetworkCapabilities(network)
            caps?.let {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // IF >= API Level 30
                    networkType(it)
                    tracer.addDebug(Log.DEBUG, TAG, "Signal Strength : " + it.signalStrength)

                    if (it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        tracer.addDebug(Log.DEBUG, TAG, "Cap: Internet Capability")
                    }
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        tracer.addDebug(Log.DEBUG, TAG, "Cap: Cellular")
                    }
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
                        tracer.addDebug(Log.DEBUG, TAG, "Cap: Wifi Aware")
                    }
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        tracer.addDebug(Log.DEBUG, TAG, "Cap: Wifi")
                    }
                    if (it.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                        tracer.addDebug(Log.DEBUG, TAG, "Cap: Bluetooth")
                    }
                }
            }
        }
    }

    private fun networkInfo(network: Network) {
        tracer.addDebug(Log.DEBUG, TAG, "Name:" + linkName(network))
        linkAddresses(network)
    }

    private fun linkName(network: Network): String {
        val networkLinkProperties = connectivityManager.getLinkProperties(network)
        return networkLinkProperties?.interfaceName ?: "None"
    }

    private fun linkAddresses(network: Network) {
        val activeNetworkLinkProperties = connectivityManager.getLinkProperties(network)

        activeNetworkLinkProperties?.linkAddresses?.let {
            for (address in it) {
                Log.d(TAG, "Address: $address")
            }
        }
    }
}
