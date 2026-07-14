package com.clearcmos.cmosremote.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Monitors network connectivity and WiFi state.
 */
class NetworkMonitor(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Flow that emits true when connected to WiFi, false otherwise.
     */
    val isWifiConnected: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(checkWifiConnected())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Emit initial state
        trySend(checkWifiConnected())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * Check if currently connected to WiFi.
     */
    fun checkWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        @Volatile
        private var INSTANCE: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
