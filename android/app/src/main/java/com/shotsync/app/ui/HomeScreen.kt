package com.shotsync.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shotsync.app.AppViewModel
import com.shotsync.app.data.db.QueueItem
import com.shotsync.app.data.db.QueueStatus
import com.shotsync.app.sync.EngineState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Main screen: status, actions and recent transfer history. */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    engineState: EngineState,
    onOpenSettings: () -> Unit,
) {
    val videoPermLauncher = com.shotsync.app.rememberPermissionLauncher { granted ->
        if (granted) {
            vm.showMessage("Video access granted — recordings will sync now")
            vm.scanNow()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ShotSync",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.refreshStatus() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh status")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        if (!vm.hasVideoPermission && engineState.paired) {
            item {
                Card(
                    Modifier.padding(horizontal = 16.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Video access is missing — screen recordings won't sync.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = {
                            videoPermLauncher.launch(android.Manifest.permission.READ_MEDIA_VIDEO)
                        }) { Text("Grant") }
                    }
                }
            }
        }

        item {
            Card(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Monitoring", modifier = Modifier.weight(1f))
                        Switch(
                            checked = engineState.monitoringEnabled,
                            onCheckedChange = { vm.setMonitoring(it) },
                        )
                    }
                    StatusRow("Paired Mac", engineState.macName ?: "None")
                    StatusRow("Connection", vm.connectionLabel(engineState.connection))
                    StatusRow(
                        "Last transfer",
                        engineState.lastSuccessAt?.let { fmtTime(it) } ?: "—",
                    )
                    StatusRow("Queued", "${engineState.queuedCount}")
                    if (engineState.uploading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Uploading…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { vm.sendTestImage() }, modifier = Modifier.weight(1f)) {
                    Text("Send test image")
                }
                OutlinedButton(onClick = { vm.scanNow() }, modifier = Modifier.weight(1f)) {
                    Text("Scan for missed")
                }
            }
        }

        item {
            val failed = engineState.recentTransfers.count { it.status == QueueStatus.FAILED }
            if (failed > 0) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$failed failed item(s)",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { vm.retryFailed() }) { Text("Retry all") }
                }
            }
        }

        item {
            Text(
                "Recent transfers",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (engineState.recentTransfers.isEmpty()) {
            item {
                Text(
                    "No transfers yet. Take a screenshot to see it appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            items(engineState.recentTransfers.take(30)) { item ->
                HistoryRow(item = item, vm = vm)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HistoryRow(item: QueueItem, vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val kindPrefix = if (item.mimeType.startsWith("video/")) "[video] " else ""
            Text(kindPrefix + item.displayName, style = MaterialTheme.typography.bodySmall)
            val detail = when (item.status) {
                QueueStatus.COMPLETED -> "✓ " + item.totalLatencyMs()?.let { "${it} ms" }.orEmpty()
                QueueStatus.FAILED -> "✗ ${item.lastError ?: "failed"}"
                QueueStatus.RETRY_WAIT -> "retrying…"
                QueueStatus.UPLOADING -> "uploading…"
                QueueStatus.READY -> "queued"
                QueueStatus.DISCOVERED -> "new"
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (item.status == QueueStatus.FAILED) {
            IconButton(onClick = { vm.retryItem(item) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry")
            }
        }
        IconButton(onClick = { vm.deleteItem(item) }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}

private fun fmtTime(epochMs: Long): String =
    SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
