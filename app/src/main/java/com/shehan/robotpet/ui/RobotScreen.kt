package com.shehan.robotpet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    initialVoiceLanguage: String,
    onTouch: () -> Unit,
    onPet: () -> Unit,
    onListen: () -> Unit,
    onTestVoice: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTestingChanged: (Boolean) -> Unit,
    onSimEspChanged: (Boolean) -> Unit,
    onSaveSettings: (String, Boolean, String) -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        RobotFace(
            emotion = ui.emotion,
            gazeX = ui.gazeX,
            gazeY = ui.gazeY,
            onTouch = onTouch,
            onPet = onPet
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                ui.status,
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                when {
                    ui.simEsp -> "SIM ESP • virtual safety clear"
                    ui.robotConnected && ui.safeToMove -> "ESP32 connected • safety clear"
                    ui.robotConnected -> "ESP32 connected • movement locked"
                    else -> "Autonomous phone mode • ESP32 offline"
                },
                color = if (ui.safeToMove) {
                    Color(0xFF9AF5A6)
                } else {
                    Color.White.copy(alpha = 0.55f)
                },
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TEST",
                color = if (ui.testing) Color(0xFF9AF5A6) else Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelSmall
            )
            Switch(
                checked = ui.testing,
                onCheckedChange = onTestingChanged
            )
        }

        if (ui.testing) {
            TestPanel(
                ui = ui,
                onSimEspChanged = onSimEspChanged,
                onTestVoice = onTestVoice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 78.dp)
            )
        } else if (ui.lastHeard.isNotBlank()) {
            Text(
                "Heard: ${ui.lastHeard}",
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 78.dp, start = 24.dp, end = 24.dp)
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onListen) {
                Text(if (ui.voice.listening) "Listening…" else "Talk")
            }

            Button(
                onClick = {
                    if (ui.robotConnected && !ui.simEsp) onDisconnect() else onConnect()
                },
                enabled = !ui.simEsp
            ) {
                Text(if (ui.robotConnected && !ui.simEsp) "Disconnect" else "Connect")
            }

            Button(onClick = { showSettings = true }) {
                Text("Settings")
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initialUrl = initialUrl,
            initialFollow = initialFollow,
            initialVoiceLanguage = initialVoiceLanguage,
            onDismiss = { showSettings = false }
        ) { url, follow, language ->
            onSaveSettings(url, follow, language)
            showSettings = false
        }
    }
}

@Composable
private fun TestPanel(
    ui: PetUiState,
    onSimEspChanged: (Boolean) -> Unit,
    onTestVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.82f),
        tonalElevation = 2.dp
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SIM ESP",
                    color = if (ui.simEsp) Color(0xFF9AF5A6) else Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
                Switch(
                    checked = ui.simEsp,
                    onCheckedChange = onSimEspChanged
                )
                Spacer(Modifier.width(10.dp))
                Button(onClick = onTestVoice) {
                    Text("Voice test")
                }
            }

            Text(
                "GESTURE ${ui.lastGesture.name}  ${(ui.gestureConfidence * 100).toInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )

            Text(
                "OBJECT ${ui.objectLabel.ifBlank { "--" }}  ${(ui.objectConfidence * 100).toInt()}%   SMILE ${(ui.smileProbability * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall
            )

            Text(
                "VOICE ${if (ui.voice.usingOnDevice) "ON-DEVICE" else "SYSTEM"} • ${ui.voice.languageTag} • RMS ${"%.1f".format(ui.voice.rmsDb)}",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall
            )

            if (ui.voice.partialText.isNotBlank()) {
                Text(
                    "HEARING: ${ui.voice.partialText}",
                    color = Color(0xFF9AF5A6),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (ui.voice.error.isNotBlank()) {
                Text(
                    "VOICE ERROR: ${ui.voice.error}",
                    color = Color(0xFFFFC66D),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                "PHONE ${ui.phoneEvent.name} • BAT ${ui.phoneBattery}%${if (ui.phoneCharging) " ⚡" else ""}",
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.labelSmall
            )

            Text(
                "DECISION ${ui.decisionCommand.name}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )

            Text(
                "ESP TX ${ui.espActualCommand?.name ?: "--"}  ${ui.espDurationMs} ms • ${ui.espState}",
                color = if (ui.espQueued) Color(0xFF9AF5A6) else Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelMedium
            )

            if (ui.eventLog.isNotEmpty()) {
                Text(
                    ui.eventLog.take(4).joinToString("\n"),
                    color = Color.White.copy(alpha = 0.50f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    initialUrl: String,
    initialFollow: Boolean,
    initialVoiceLanguage: String,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, String) -> Unit
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var follow by rememberSaveable(initialFollow) { mutableStateOf(initialFollow) }
    var voiceLanguage by rememberSaveable(initialVoiceLanguage) {
        mutableStateOf(initialVoiceLanguage)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Robot settings", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("ESP32 WebSocket URL") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = voiceLanguage,
                    onValueChange = { voiceLanguage = it },
                    label = { Text("Voice language (example: en-US)") },
                    singleLine = true
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = follow,
                        onCheckedChange = { follow = it }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Autonomous face follow")
                }

                Text(
                    "Real movement remains safety-gated by ESP32 telemetry.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSave(url, follow, voiceLanguage) }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
