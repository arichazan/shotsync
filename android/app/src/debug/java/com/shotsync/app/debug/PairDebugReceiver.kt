package com.shotsync.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shotsync.app.ShotSyncApp
import com.shotsync.app.pairing.QrPayload
import com.shotsync.app.util.AppLogger
import kotlinx.coroutines.launch

/**
 * Debug-only broadcast receiver (see src/debug/AndroidManifest.xml) that
 * exercises the real pairing path from an adb broadcast:
 *
 *   adb shell am broadcast -a com.shotsync.app.DEBUG_PAIR \
 *       -e pairing_json '{"version":1,...}'
 */
class PairDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.shotsync.app.DEBUG_PAIR") return
        val json = intent.getStringExtra("pairing_json") ?: run {
            AppLogger.w("PairDebug", "no pairing_json extra")
            return
        }
        val payload = QrPayload.parse(json) ?: run {
            AppLogger.w("PairDebug", "invalid payload")
            return
        }
        val pending = goAsync()
        val app = ShotSyncApp.from(context)
        app.appScope.launch {
            try {
                val result = app.engine.pairWithQr(
                    secret = payload.pairingSecret,
                    macDeviceId = payload.deviceId,
                    macName = payload.deviceName,
                    fingerprint = payload.certificateFingerprint,
                    localHost = payload.localHost,
                    port = payload.port,
                    deviceDisplayName = "Automated test device",
                )
                result.fold(
                    onSuccess = { AppLogger.i("PairDebug", "PAIRED OK") },
                    onFailure = { AppLogger.w("PairDebug", "pair failed: ${it.message}") },
                )
            } finally {
                pending.finish()
            }
        }
    }
}
