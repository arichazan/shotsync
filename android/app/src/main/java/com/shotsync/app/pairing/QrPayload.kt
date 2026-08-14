package com.shotsync.app.pairing

/**
 * Parses and validates the pairing QR payload (protocol doc section 6.1).
 * The QR carries a one-time pairing secret; it must never be logged.
 */
data class QrPayload(
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    val localHost: String,
    val port: Int,
    val certificateFingerprint: String,
    val pairingSecret: String,
) {
    companion object {
        fun parse(json: String): QrPayload? {
            return runCatching {
                val obj = org.json.JSONObject(json)
                QrPayload(
                    version = obj.optInt("version", -1),
                    deviceId = obj.getString("device_id"),
                    deviceName = obj.optString("device_name", "Mac"),
                    localHost = obj.getString("local_host"),
                    port = obj.optInt("port", 47831),
                    certificateFingerprint = obj.getString("certificate_fingerprint"),
                    pairingSecret = obj.getString("pairing_secret"),
                )
            }.getOrNull()?.takeIf {
                it.version == 1 &&
                    it.deviceId.isNotBlank() &&
                    it.localHost.isNotBlank() &&
                    it.certificateFingerprint.startsWith("sha256-") &&
                    it.pairingSecret.length >= 32
            }
        }
    }
}
