package com.shotsync.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.shotsync.app.data.AppSettings
import com.shotsync.app.data.Prefs
import com.shotsync.app.data.db.QueueItem
import com.shotsync.app.sync.ConnectionMethod
import com.shotsync.app.sync.EngineState
import com.shotsync.app.sync.MonitorService
import com.shotsync.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.shotsync.app.ui.theme.ShotSyncTheme {
                val vm: AppViewModel = viewModel { AppViewModel(this@MainActivity) }
                com.shotsync.app.ui.AppRoot(vm)
            }
        }
    }
}

class AppViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val prefs: Prefs = (app as ShotSyncApp).prefs
    private val engine = (app as ShotSyncApp).engine

    val settings: StateFlow<AppSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val engineState: StateFlow<EngineState> = engine.state
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        // The monitoring service is not restarted by Android after a
        // process kill or app update. Opening the app is a foreground
        // context, so restart it here when it should be running.
        viewModelScope.launch {
            runCatching {
                if (prefs.current().monitoringEnabled) {
                    MonitorService.start(app)
                    AppLogger.i("Monitor", "restarted on app open")
                }
            }.onFailure { AppLogger.w("Monitor", "restart on open failed: ${it.message}") }
        }
        viewModelScope.launch {
            while (true) {
                _logs.value = AppLogger.tail(100)
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun showMessage(text: String?) {
        _message.value = text
    }

    val hasMediaPermission: Boolean
        get() = ContextCompat.checkSelfPermission(app, mediaPermission()) ==
            PackageManager.PERMISSION_GRANTED

    /** Screen recordings need READ_MEDIA_VIDEO on Android 13+. */
    val hasVideoPermission: Boolean
        get() = Build.VERSION.SDK_INT < 33 || // covered by READ_EXTERNAL_STORAGE below 33
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED

    /** Both image and video access (required for screenshots + recordings). */
    val hasFullMediaPermission: Boolean
        get() = hasMediaPermission && hasVideoPermission

    /** Android 14+ "Select photos" partial access — insufficient for monitoring. */
    val hasPartialPhotoAccess: Boolean
        get() = Build.VERSION.SDK_INT >= 34 && !hasMediaPermission &&
            ContextCompat.checkSelfPermission(
                app, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED

    val hasNotificationPermission: Boolean
        get() = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    val isBatteryOptimizationExempt: Boolean
        get() = runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(app.packageName)
        }.getOrDefault(false)

    fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun requestMediaPermission(launcher: (String) -> Unit) = launcher(mediaPermission())

    fun setMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setMonitoring(enabled)
            if (enabled) {
                MonitorService.start(app)
            } else {
                MonitorService.stop(app)
            }
        }
    }

    fun setSetupCompleted() {
        viewModelScope.launch { prefs.setSetupCompleted(true) }
    }

    /** Finishes onboarding and enables monitoring (PRD setup step 6). */
    fun finishSetup() {
        viewModelScope.launch {
            prefs.setSetupCompleted(true)
            prefs.setMonitoring(true)
            MonitorService.start(app)
            engine.triggerSync("setup-complete")
        }
    }

    fun scanNow() {
        viewModelScope.launch {
            showMessage("Scanning…")
            val found = engine.reconciliationScan()
            showMessage("Scan complete: $found new screenshot(s)")
        }
    }

    fun sendTestImage() {
        viewModelScope.launch {
            showMessage("Sending test image…")
            val result = engine.sendTestImage()
            showMessage(
                result.fold(
                    onSuccess = { "Test image: $it" },
                    onFailure = { "Test image failed: ${it.message}" },
                )
            )
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            engine.retryAllFailed()
            showMessage("Failed items queued for retry")
        }
    }

    fun retryItem(item: QueueItem) {
        viewModelScope.launch { engine.retryFailedItem(item.id) }
    }

    fun deleteItem(item: QueueItem) {
        viewModelScope.launch { engine.deleteItem(item.id) }
    }

    fun unpair() {
        viewModelScope.launch {
            engine.unpair()
            showMessage("Mac unpaired")
        }
    }

    fun pair(secret: String, macId: String, macName: String, fingerprint: String, host: String, port: Int, deviceDisplayName: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            prefs.setDeviceDisplayName(deviceDisplayName)
            val result = engine.pairWithQr(secret, macId, macName, fingerprint, host, port, deviceDisplayName)
            result.fold(
                onSuccess = {
                    showMessage("Paired with $macName")
                    onDone(true)
                },
                onFailure = {
                    showMessage("Pairing failed: ${it.message}")
                    onDone(false)
                },
            )
        }
    }

    fun updateSettings(update: suspend (Prefs) -> Unit) {
        viewModelScope.launch { update(prefs) }
    }

    fun refreshStatus() {
        viewModelScope.launch { engine.refreshConnectionStatus() }
    }

    fun connectionLabel(method: ConnectionMethod?): String = when (method) {
        ConnectionMethod.LOCAL -> "Local network"
        ConnectionMethod.TAILSCALE -> "Tailscale"
        null -> if (engineState.value.paired) "Offline" else "Not paired"
    }

    fun exportLogs(): Intent = AppLogger.exportIntent()

    fun writeLogsTo(uri: Uri) {
        AppLogger.writeExportedTo(uri, app)
    }

    fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${app.packageName}")
        }

    companion object {
        fun scanOptions(): ScanOptions = ScanOptions()
            .setPrompt("Scan the pairing QR code shown on your Mac")
            .setBeepEnabled(true)
            .setOrientationLocked(false)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    }
}

@Composable
fun rememberQrLauncher(onResult: (String?) -> Unit) =
    rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) onResult(result.contents)
    }

@Composable
fun rememberPermissionLauncher(onResult: (Boolean) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onResult(granted)
    }
