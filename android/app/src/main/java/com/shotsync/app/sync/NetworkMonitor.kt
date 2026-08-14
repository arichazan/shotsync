package com.shotsync.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.shotsync.app.util.AppLogger

/**
 * Watches network availability and connectivity changes so uploads can be
 * retried the moment a usable network appears (recovery after Wi-Fi/mobile
 * switches, VPN connect, Mac waking on the LAN).
 */
class NetworkMonitor(context: Context, private val onAvailable: () -> Unit) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var isOnline: Boolean = false
        private set

    @Volatile
    var isWifi: Boolean = false
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isOnline = true
            AppLogger.i("Network", "available")
            onAvailable()
        }

        override fun onLost(network: Network) {
            isOnline = false
            AppLogger.i("Network", "lost")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    fun start() {
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            isOnline = caps != null
            isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) {
            AppLogger.w("Network", "monitor start failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }
}
