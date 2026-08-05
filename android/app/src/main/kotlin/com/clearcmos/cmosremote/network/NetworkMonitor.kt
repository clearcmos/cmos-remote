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
 * Watches whether the device is on a network that could plausibly reach a LAN
 * server: WiFi or Ethernet, but not cellular.
 *
 * This is only a cheap first gate. Whether the server is actually there, and
 * actually the right server, is decided by the authenticated health check in
 * [ApiClient] plus response verification in [HmacInterceptor]. Ethernet counts
 * because tablets with a dock, and emulators, report TRANSPORT_ETHERNET; gating
 * on WiFi alone left those permanently "Disconnected".
 */
class NetworkMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Emits true while a local-network-capable transport is connected. */
    val isOnLocalNetwork: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(checkOnLocalNetwork())
            }

            override fun onLost(network: Network) {
                // Another transport may still be up, so re-check rather than
                // assuming this loss means offline.
                trySend(checkOnLocalNetwork())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(checkOnLocalNetwork())
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        trySend(checkOnLocalNetwork())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /** True when the active network is WiFi or Ethernet. */
    fun checkOnLocalNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return LOCAL_TRANSPORTS.any { capabilities.hasTransport(it) }
    }

    companion object {
        private val LOCAL_TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
        )

        @Volatile
        private var INSTANCE: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkMonitor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
