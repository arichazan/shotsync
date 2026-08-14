package com.shotsync.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shotsync.app.AppViewModel

@Composable
fun AppRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.showMessage(null)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (!settings.setupCompleted) {
            SetupScreen(vm, onDone = { vm.finishSetup() })
        } else {
            HomeScreen(
                vm = vm,
                engineState = engineState,
                onOpenSettings = { showSettings = true },
            )
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    if (showSettings) {
        SettingsScreen(vm = vm, onBack = { showSettings = false })
        BackHandler { showSettings = false }
    }
}

@Composable
fun DiagnosticsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Text(
                text = logs.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = {
                vm.showMessage("Use Export diagnostics to share the full log")
            }) { Text("Info") }
        },
    )
}
