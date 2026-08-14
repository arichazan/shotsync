package com.shotsync.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.shotsync.app.util.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

data class DiscoveredMac(
    val host: String,
    val port: Int,
    val deviceId: String,
    val deviceName: String,
    val protocolVersion: Int,
)

/**
 * mDNS discovery of _shotsync._tcp services via NsdManager. The Android
 * client prefers locally discovered endpoints over configured Tailscale ones.
 */
class Discovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _macs = MutableStateFlow<List<DiscoveredMac>>(emptyList())
    val macs: StateFlow<List<DiscoveredMac>> = _macs

    @Volatile
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var running = false

    /** Starts a continuous discovery. Safe to call repeatedly. */
    fun start(expectedMacDeviceId: String?) {
        if (running) return
        running = true
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                AppLogger.i("Discovery", "started $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolve immediately; match against the paired Mac ID when known.
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        AppLogger.d("Discovery", "resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val txt = info.attributes
                        val deviceId = txt["device_id"]?.let { String(it) } ?: ""
                        if (expectedMacDeviceId != null && expectedMacDeviceId.isNotBlank() &&
                            deviceId != expectedMacDeviceId
                        ) {
                            return // a different ShotSync Mac on the network
                        }
                        val version = txt["protocol_version"]?.let { String(it).toIntOrNull() } ?: 0
                        if (version != 1) return
                        val mac = DiscoveredMac(
                            host = info.host.hostAddress ?: return,
                            port = info.port,
                            deviceId = deviceId,
                            deviceName = txt["device_name"]?.let { String(it) } ?: info.serviceName,
                            protocolVersion = version,
                        )
                        val current = _macs.value
                        if (current.none { it.host == mac.host && it.port == mac.port }) {
                            _macs.value = current + mac
                            AppLogger.i("Discovery", "found ${mac.deviceName} at ${mac.host}:${mac.port}")
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                _macs.value = _macs.value.filterNot { it.host == host }
                AppLogger.i("Discovery", "lost $host")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AppLogger.i("Discovery", "stopped")
                running = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLogger.w("Discovery", "start failed: $errorCode")
                running = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLogger.w("Discovery", "stop failed: $errorCode")
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices("_shotsync._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            AppLogger.w("Discovery", "discoverServices failed: ${e.message}")
            running = false
        }
    }

    fun stop() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        running = false
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }
}
