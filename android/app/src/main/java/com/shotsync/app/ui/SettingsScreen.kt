package com.shotsync.app.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shotsync.app.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showDiagnostics by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var deviceNameDraft by remember(settings.deviceDisplayName) { mutableStateOf(settings.deviceDisplayName) }
    var tailscaleDraft by remember(settings.tailscaleHost) { mutableStateOf(settings.tailscaleHost) }
    var retentionDraft by remember(settings.retentionDays) { mutableStateOf(settings.retentionDays.toString()) }
    var subfolderDraft by remember(settings.destSubfolder) { mutableStateOf(settings.destSubfolder) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) vm.writeLogsTo(uri) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SettingSwitch("Monitoring", settings.monitoringEnabled) { vm.setMonitoring(it) }
        SettingSwitch("Sync screenshots", settings.syncScreenshots) {
            vm.updateSettings { p -> p.setSyncScreenshots(it) }
        }
        SettingSwitch("Sync screen recordings", settings.syncRecordings) {
            vm.updateSettings { p -> p.setSyncRecordings(it) }
        }
        SettingSwitch("Upload on Wi-Fi only", settings.wifiOnly) { vm.updateSettings { p -> p.setWifiOnly(it) } }
        SettingSwitch("Notifications", settings.notificationsEnabled) { vm.updateSettings { p -> p.setNotifications(it) } }
        SettingSwitch("Prefer local network", settings.preferLocal) { vm.updateSettings { p -> p.setPreferLocal(it) } }

        OutlinedTextField(
            value = deviceNameDraft,
            onValueChange = { deviceNameDraft = it.take(63) },
            label = { Text("This device name") },
            supportingText = { Text("Used the next time this device pairs; hardware model is not shared.") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            vm.updateSettings { it.setDeviceDisplayName(deviceNameDraft) }
            vm.showMessage("Device name saved")
        }) { Text("Save device name") }

        OutlinedTextField(
            value = tailscaleDraft,
            onValueChange = { tailscaleDraft = it },
            label = { Text("Tailscale hostname (fallback)") },
            placeholder = { Text("receiver-alias.tailnet.ts.net") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            vm.updateSettings { it.setTailscaleHost(tailscaleDraft.trim()) }
            vm.showMessage("Tailscale host saved")
        }) { Text("Save Tailscale host") }

        OutlinedTextField(
            value = subfolderDraft,
            onValueChange = { value ->
                subfolderDraft = value.filter { c ->
                    c.code >= 0x20 && c.code != 0x7f && c !in "\\/:*?\"<>|"
                }.take(80)
            },
            label = { Text("Optional destination subfolder") },
            placeholder = { Text("e.g. Work phone") },
            supportingText = { Text("Created inside the receiver folder selected on the Mac.") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            vm.updateSettings { it.setDestSubfolder(subfolderDraft.trim()) }
        }) { Text("Save subfolder") }

        OutlinedTextField(
            value = retentionDraft,
            onValueChange = { retentionDraft = it.filter { c -> c.isDigit() } },
            label = { Text("History retention (days)") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            retentionDraft.toIntOrNull()?.let { days ->
                vm.updateSettings { it.setRetentionDays(days) }
            }
        }) { Text("Save retention") }

        Spacer(Modifier.height(4.dp))
        Text("Paired Mac: ${settings.macDeviceName.ifBlank { "none" }}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Fingerprint: ${settings.certificateFingerprint.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        OutlinedButton(onClick = { showDiagnostics = true }, modifier = Modifier.fillMaxWidth()) {
            Text("View diagnostics")
        }
        OutlinedButton(onClick = { exportLauncher.launch("shotsync-log.txt") }, modifier = Modifier.fillMaxWidth()) {
            Text("Export diagnostic logs")
        }
        if (settings.paired) {
            Button(
                onClick = { confirmUnpair = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unpair Mac", color = MaterialTheme.colorScheme.onError)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "ShotSync 0.1.0 — direct Android-to-Mac transfer, no cloud.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(vm) { showDiagnostics = false }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair Mac?") },
            text = { Text("Screenshots will no longer be sent until you pair again.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.unpair()
                    confirmUnpair = false
                }) { Text("Unpair") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
