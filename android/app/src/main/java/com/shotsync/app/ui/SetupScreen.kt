package com.shotsync.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shotsync.app.AppViewModel
import com.shotsync.app.data.AppSettings
import com.shotsync.app.pairing.QrPayload
import com.shotsync.app.rememberPermissionLauncher
import com.shotsync.app.rememberQrLauncher
import com.shotsync.app.util.AppLogger

private data class SetupStep(
    val title: String,
    val detail: String,
    val done: Boolean,
)

/** First-run onboarding: permissions, folder selection, pairing, enable. */
@Composable
fun SetupScreen(vm: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    var folders by remember { mutableStateOf<List<String>?>(null) }
    var selectedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var step by remember { mutableStateOf(0) }
    var pairingOpen by remember { mutableStateOf(false) }
    var deviceNameDraft by remember(settings.deviceDisplayName) { mutableStateOf(settings.deviceDisplayName) }

    LaunchedEffect(Unit) {
        if (folders == null) {
            // Folder discovery is done lazily; defaults are always observed.
            val found = runCatching {
                com.shotsync.app.media.MediaScanner(com.shotsync.app.ShotSyncApp.from(context))
                    .discoverScreenshotFolders()
            }.getOrDefault(emptyList())
            folders = found
            selectedFolders = found.toSet()
        }
    }

    // Media access: images first, then videos on Android 13+.
    val videoPermLauncher = rememberPermissionLauncher { _ -> step++ }
    val permLauncher = rememberPermissionLauncher { granted ->
        if (granted) {
            if (Build.VERSION.SDK_INT >= 33 && !vm.hasVideoPermission) {
                videoPermLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                step++
            }
        }
    }
    val notifLauncher = rememberPermissionLauncher { granted ->
        if (granted) step++
    }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { step++ } // advance whether or not the user exempts the app

    val steps = remember(settings, selectedFolders) {
        listOf(
            SetupStep(
                "Access to photos & videos",
                if (vm.hasPartialPhotoAccess)
                    "Partial photo access is not enough — full access is needed to watch your screenshot folders."
                else
                    "Required to detect screenshots and screen recordings.",
                vm.hasFullMediaPermission,
            ),
            SetupStep(
                "Notifications",
                "Used for the monitoring indicator and transfer notices.",
                vm.hasNotificationPermission,
            ),
            SetupStep(
                "Screenshot folders",
                "Folders watched for new screenshots.",
                selectedFolders.isNotEmpty(),
            ),
            SetupStep(
                "Battery optimization",
                "Exclude ShotSync so monitoring is not killed in the background.",
                vm.isBatteryOptimizationExempt,
            ),
            SetupStep(
                "Pair with your Mac",
                "Scan the QR code shown by the ShotSync receiver.",
                settings.paired,
            ),
        )
    }

    // Auto-advance past steps whose precondition is already satisfied
    // (permissions granted earlier, battery exemption already active).
    // Folder selection and pairing always require explicit confirmation.
    val autoAdvance = step == 0 || step == 1 || step == 3
    LaunchedEffect(step, steps[step].done) {
        if (autoAdvance && steps[step].done && step < steps.lastIndex) {
            step++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Welcome to ShotSync", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Screenshots from this phone will appear on your Mac automatically — no cloud in between.",
            style = MaterialTheme.typography.bodyMedium,
        )

        steps.forEachIndexed { index, s ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (s.done) Icons.Default.CheckCircle else Icons.Default.Refresh,
                        contentDescription = null,
                        tint = if (s.done) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold)
                        Text(s.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        when (step) {
            0 -> Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.requestMediaPermission { permLauncher.launch(it) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            vm.hasFullMediaPermission -> "Permissions granted — Continue"
                            vm.hasPartialPhotoAccess -> "Grant full photo access"
                            vm.hasMediaPermission && !vm.hasVideoPermission -> "Grant video access"
                            else -> "Grant photo & video access"
                        }
                    )
                }
                if (vm.hasFullMediaPermission) {
                    TextButton(onClick = { step++ }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue")
                    }
                }
            }

            1 -> Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow notifications") }
                // Notifications are a soft requirement: never hard-block setup.
                TextButton(onClick = { step++ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now")
                }
            }

            2 -> {
                val folderList = folders
                Column(Modifier.fillMaxWidth()) {
                    if (folderList == null) {
                        Text(
                            "Looking for screenshot folders…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (folderList.isEmpty()) {
                        Text(
                            "No screenshot folders found yet — the defaults (Pictures/Screenshots, DCIM/Screenshots) will be watched. Take a screenshot and revisit to extend coverage.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    folderList?.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = folder in selectedFolders,
                                onCheckedChange = { checked ->
                                    selectedFolders =
                                        if (checked) selectedFolders + folder else selectedFolders - folder
                                },
                            )
                            Text(folder, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Default screenshot folders are always watched; extra selections extend coverage.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        vm.updateSettings { it.setExtraFolders(selectedFolders) }
                        step++
                    }, modifier = Modifier.fillMaxWidth()) { Text("Confirm folders") }
                }
            }

            3 -> Button(
                onClick = {
                    if (vm.isBatteryOptimizationExempt) {
                        step++
                    } else {
                        runCatching {
                            batteryLauncher.launch(vm.batteryOptimizationIntent())
                        }.onFailure { step++ }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Exclude from battery optimization") }

            4 -> Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = deviceNameDraft,
                    onValueChange = { deviceNameDraft = it.take(63) },
                    label = { Text("This device name") },
                    supportingText = { Text("Shown to the receiver; no hardware model is shared.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (settings.paired) {
                    Text(
                        "Paired with ${settings.macDeviceName}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Button(
                    onClick = { pairingOpen = true },
                    enabled = deviceNameDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (settings.paired) "Re-pair" else "Scan pairing QR code")
                }
                if (settings.paired) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue")
                    }
                }
            }
        }

        if (step == 4 && !settings.paired) {
            Text(
                "Tip: open the ShotSync menu-bar icon on your Mac and choose \"Show Pairing QR Code\".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (pairingOpen) {
        PairSheet(vm = vm, deviceDisplayName = deviceNameDraft, onClose = { pairingOpen = false })
    }
}

@Composable
fun PairSheet(vm: AppViewModel, deviceDisplayName: String, onClose: () -> Unit) {
    var manual by remember { mutableStateOf(false) }
    var manualJson by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val qrLauncher = rememberQrLauncher { contents ->
        if (contents != null) {
            handlePairJson(vm, contents, deviceDisplayName) { _ -> busy = false }
            busy = true
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Pair with your Mac") },
        text = {
            Column {
                if (!manual) {
                    Text("Scan the QR code from the ShotSync receiver on your Mac.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { qrLauncher.launch(AppViewModel.scanOptions()) }) {
                        Text("Open camera")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { manual = true }) {
                        Text("Paste pairing code manually")
                    }
                } else {
                    OutlinedTextField(
                        value = manualJson,
                        onValueChange = { manualJson = it },
                        label = { Text("Pairing JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                    Button(
                        onClick = {
                            busy = true
                            handlePairJson(vm, manualJson, deviceDisplayName) { busy = false }
                        },
                        enabled = manualJson.isNotBlank() && !busy,
                    ) { Text("Pair") }
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text("Pairing…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onClose, enabled = !busy) {
                Text("Close")
            }
        },
    )
}

private fun handlePairJson(vm: AppViewModel, json: String, deviceDisplayName: String, done: (Boolean) -> Unit) {
    val payload = QrPayload.parse(json)
    if (payload == null) {
        vm.showMessage("Invalid pairing code")
        done(false)
        return
    }
    AppLogger.i("Pairing", "pairing payload accepted for ${payload.deviceName}")
    vm.pair(
        secret = payload.pairingSecret,
        macId = payload.deviceId,
        macName = payload.deviceName,
        fingerprint = payload.certificateFingerprint,
        host = payload.localHost,
        port = payload.port,
        deviceDisplayName = deviceDisplayName,
        onDone = done,
    )
}
