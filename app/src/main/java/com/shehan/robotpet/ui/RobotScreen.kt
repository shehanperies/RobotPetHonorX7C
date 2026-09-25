package com.shehan.robotpet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shehan.robotpet.PetUiState

@Composable
fun RobotScreen(
    ui: PetUiState,
    initialUrl: String,
    initialFollow: Boolean,
    onTouch: () -> Unit,
    onListen: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSaveSettings: (String, Boolean) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RobotFace(ui.emotion, ui.gazeX, ui.gazeY, onTouch)

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 24.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(ui.status, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    ui.robotConnected && ui.safeToMove -> "ESP32 connected • safety clear"
                    ui.robotConnected -> "ESP32 connected • movement locked"
                    else -> "Face-only mode • ESP32 offline"
                },
                color = if (ui.safeToMove) Color(0xFF9AF5A6) else Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onListen) { Text(if (ui.listening) "Listening…" else "Talk") }
            Button(onClick = { if (ui.robotConnected) onDisconnect() else onConnect() }) { Text(if (ui.robotConnected) "Disconnect" else "Connect") }
            Button(onClick = { showSettings = true }) { Text("Settings") }
        }

        if (ui.lastHeard.isNotBlank()) {
            Text(
                "Heard: ${ui.lastHeard}",
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 82.dp, start = 24.dp, end = 24.dp)
            )
        }
    }

    if (showSettings) {
        SettingsDialog(initialUrl, initialFollow, onDismiss = { showSettings = false }) { url, follow ->
            onSaveSettings(url, follow)
            showSettings = false
        }
    }
}

@Composable
private fun SettingsDialog(initialUrl: String, initialFollow: Boolean, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var follow by remember(initialFollow) { mutableStateOf(initialFollow) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Robot settings", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("ESP32 WebSocket URL") }, singleLine = true)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = follow, onCheckedChange = { follow = it })
                    Spacer(Modifier.width(10.dp))
                    Text("Autonomous face follow")
                }
                Spacer(Modifier.height(18.dp))
                Text("Movement stays blocked until the ESP32 reports safeToMove=true.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSave(url, follow) }) { Text("Save + connect") }
                }
            }
        }
    }
}
