package com.shotsync.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shotsync_prefs")

/**
 * All user settings and pairing state. The receiver access token is stored
 * encrypted (Android Keystore AES-GCM) and never logged.
 */
data class AppSettings(
    val setupCompleted: Boolean = false,
    val deviceId: String = "",
    val deviceDisplayName: String = "Android device",
    val monitoringEnabled: Boolean = false,
    val paired: Boolean = false,
    val macDeviceId: String = "",
    val macDeviceName: String = "",
    val certificateFingerprint: String = "",
    val localHost: String = "",
    val port: Int = 47831,
    val tailscaleHost: String = "",
    val extraFolders: Set<String> = emptySet(),
    val recordingFolders: Set<String> = emptySet(),
    val destSubfolder: String = "",
    val preferLocal: Boolean = true,
    val wifiOnly: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val syncScreenshots: Boolean = true,
    val syncRecordings: Boolean = true,
    val retentionDays: Int = 30,
    val watermarkSec: Long = 0L,
    val watermarkVideoSec: Long = 0L,
    val scanWindowSec: Long = 172800L, // 48h reconciliation window
    val lastSuccessfulTransferAt: Long = 0L,
)

class Prefs(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    companion object {
        private val KEY_SETUP_DONE = booleanPreferencesKey("setup_done")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_DISPLAY_NAME = stringPreferencesKey("device_display_name")
        private val KEY_MONITORING = booleanPreferencesKey("monitoring")
        private val KEY_PAIRED = booleanPreferencesKey("paired")
        private val KEY_MAC_ID = stringPreferencesKey("mac_id")
        private val KEY_MAC_NAME = stringPreferencesKey("mac_name")
        private val KEY_CERT_FP = stringPreferencesKey("cert_fp")
        private val KEY_LOCAL_HOST = stringPreferencesKey("local_host")
        private val KEY_PORT = intPreferencesKey("port")
        private val KEY_TAILSCALE = stringPreferencesKey("tailscale_host")
        private val KEY_FOLDERS = stringSetPreferencesKey("folders")
        private val KEY_DEST_SUB = stringPreferencesKey("dest_subfolder")
        private val KEY_PREFER_LOCAL = booleanPreferencesKey("prefer_local")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_NOTIFS = booleanPreferencesKey("notifications")
        private val KEY_RETENTION = intPreferencesKey("retention_days")
        private val KEY_TOKEN_ENC = stringPreferencesKey("token_enc")
        private val KEY_WATERMARK = longPreferencesKey("watermark_sec")
        private val KEY_WATERMARK_VIDEO = longPreferencesKey("watermark_video_sec")
        private val KEY_SCAN_WINDOW = longPreferencesKey("scan_window_sec")
        private val KEY_LAST_SUCCESS = longPreferencesKey("last_success_at")
        private val KEY_SYNC_SCREENSHOTS = booleanPreferencesKey("sync_screenshots")
        private val KEY_SYNC_RECORDINGS = booleanPreferencesKey("sync_recordings")
        private val KEY_RECORDING_FOLDERS = stringSetPreferencesKey("recording_folders")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            setupCompleted = p[KEY_SETUP_DONE] ?: false,
            deviceId = p[KEY_DEVICE_ID] ?: "",
            deviceDisplayName = p[KEY_DEVICE_DISPLAY_NAME] ?: "Android device",
            monitoringEnabled = p[KEY_MONITORING] ?: false,
            paired = p[KEY_PAIRED] ?: false,
            macDeviceId = p[KEY_MAC_ID] ?: "",
            macDeviceName = p[KEY_MAC_NAME] ?: "",
            certificateFingerprint = p[KEY_CERT_FP] ?: "",
            localHost = p[KEY_LOCAL_HOST] ?: "",
            port = p[KEY_PORT] ?: 47831,
            tailscaleHost = p[KEY_TAILSCALE] ?: "",
            extraFolders = p[KEY_FOLDERS] ?: emptySet(),
            recordingFolders = p[KEY_RECORDING_FOLDERS] ?: emptySet(),
            destSubfolder = p[KEY_DEST_SUB] ?: "",
            preferLocal = p[KEY_PREFER_LOCAL] ?: true,
            wifiOnly = p[KEY_WIFI_ONLY] ?: false,
            notificationsEnabled = p[KEY_NOTIFS] ?: true,
            syncScreenshots = p[KEY_SYNC_SCREENSHOTS] ?: true,
            syncRecordings = p[KEY_SYNC_RECORDINGS] ?: true,
            retentionDays = p[KEY_RETENTION] ?: 30,
            watermarkSec = p[KEY_WATERMARK] ?: 0L,
            watermarkVideoSec = p[KEY_WATERMARK_VIDEO] ?: 0L,
            scanWindowSec = p[KEY_SCAN_WINDOW] ?: 172800L,
            lastSuccessfulTransferAt = p[KEY_LAST_SUCCESS] ?: 0L,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun ensureDeviceId(): String {
        val existing = current().deviceId
        if (existing.isNotEmpty()) return existing
        val id = UUID.randomUUID().toString()
        dataStore.edit { it[KEY_DEVICE_ID] = id }
        return id
    }

    suspend fun setSetupCompleted(done: Boolean) {
        dataStore.edit { it[KEY_SETUP_DONE] = done }
    }

    suspend fun setMonitoring(enabled: Boolean) {
        dataStore.edit { it[KEY_MONITORING] = enabled }
    }

    suspend fun setDeviceDisplayName(name: String) {
        dataStore.edit { it[KEY_DEVICE_DISPLAY_NAME] = name.trim().ifBlank { "Android device" }.take(63) }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[KEY_WIFI_ONLY] = enabled }
    }

    suspend fun setNotifications(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFS] = enabled }
    }

    suspend fun setRetentionDays(days: Int) {
        dataStore.edit { it[KEY_RETENTION] = days.coerceIn(1, 3650) }
    }

    suspend fun setExtraFolders(folders: Set<String>) {
        dataStore.edit { it[KEY_FOLDERS] = folders }
    }

    suspend fun setRecordingFolders(folders: Set<String>) {
        dataStore.edit { it[KEY_RECORDING_FOLDERS] = folders }
    }

    suspend fun setSyncScreenshots(enabled: Boolean) {
        dataStore.edit { it[KEY_SYNC_SCREENSHOTS] = enabled }
    }

    suspend fun setSyncRecordings(enabled: Boolean) {
        dataStore.edit { it[KEY_SYNC_RECORDINGS] = enabled }
    }

    suspend fun setDestSubfolder(sub: String) {
        val safe = sub.filter {
            it.code >= 0x20 && it.code != 0x7f && it !in "\\/:*?\"<>|"
        }.trim().trim('.').take(80)
        dataStore.edit { it[KEY_DEST_SUB] = safe }
    }

    suspend fun setPreferLocal(prefer: Boolean) {
        dataStore.edit { it[KEY_PREFER_LOCAL] = prefer }
    }

    suspend fun setTailscaleHost(host: String) {
        dataStore.edit { it[KEY_TAILSCALE] = host.trim() }
    }

    suspend fun setPort(port: Int) {
        dataStore.edit { it[KEY_PORT] = port.coerceIn(1, 65535) }
    }

    suspend fun setScanWindowSec(seconds: Long) {
        dataStore.edit { it[KEY_SCAN_WINDOW] = seconds.coerceIn(600, 31_536_000) }
    }

    suspend fun setWatermark(sec: Long) {
        dataStore.edit { it[KEY_WATERMARK] = sec }
    }

    suspend fun setVideoWatermark(sec: Long) {
        dataStore.edit { it[KEY_WATERMARK_VIDEO] = sec }
    }

    suspend fun setLastSuccessfulTransfer(at: Long) {
        dataStore.edit { it[KEY_LAST_SUCCESS] = at }
    }

    suspend fun savePairing(
        macDeviceId: String,
        macDeviceName: String,
        fingerprint: String,
        localHost: String,
        port: Int,
        token: String,
    ) {
        val enc = KeystoreCrypto.encrypt(token)
        dataStore.edit {
            it[KEY_MAC_ID] = macDeviceId
            it[KEY_MAC_NAME] = macDeviceName
            it[KEY_CERT_FP] = fingerprint
            it[KEY_LOCAL_HOST] = localHost
            it[KEY_PORT] = port
            it[KEY_TOKEN_ENC] = enc
            it[KEY_PAIRED] = true
        }
    }

    suspend fun token(): String? {
        val enc = dataStore.data.map { it[KEY_TOKEN_ENC] }.first() ?: return null
        return KeystoreCrypto.decrypt(enc)
    }

    suspend fun unpair() {
        dataStore.edit {
            it.remove(KEY_MAC_ID)
            it.remove(KEY_MAC_NAME)
            it.remove(KEY_CERT_FP)
            it.remove(KEY_LOCAL_HOST)
            it.remove(KEY_TOKEN_ENC)
            it[KEY_PAIRED] = false
            it[KEY_TAILSCALE] = ""
        }
    }
}
