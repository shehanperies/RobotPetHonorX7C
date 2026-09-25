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
    onTouch: () -> Unit,
    onListen: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSaveSettings: (String, Boolean) -> Unit
) {
    var showSettings by rememberSaveable {
        mutableStateOf(false)
    }

    var testing by rememberSaveable {
        mutableStateOf(false)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        RobotFace(
            ui.emotion,
            ui.gazeX,
            ui.gazeY,
            onTouch
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(
                    top = 18.dp,
                    start = 12.dp,
                    end = 12.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                ui.status,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                when {
                    ui.robotConnected && ui.safeToMove ->
                        "ESP32 connected • safety clear"

                    ui.robotConnected ->
                        "ESP32 connected • movement locked"

                    else ->
                        "Face + gesture mode • ESP32 offline"
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
                .padding(
                    top = 10.dp,
                    end = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TEST",
                color = if (testing) {
                    Color(0xFF9AF5A6)
                } else {
                    Color.White.copy(alpha = 0.5f)
                },
                style = MaterialTheme.typography.labelSmall
            )

            Switch(
                checked = testing,
                onCheckedChange = {
                    testing = it
                }
            )
        }

        if (testing) {
            TestPanel(
                ui = ui,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 88.dp
                    )
            )
        } else if (ui.lastHeard.isNotBlank()) {
            Text(
                "Heard: ${ui.lastHeard}",
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = 82.dp,
                        start = 24.dp,
                        end = 24.dp
                    )
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onListen
            ) {
                Text(
                    if (ui.listening) {
                        "Listening…"
                    } else {
                        "Talk"
                    }
                )
            }

            Button(
                onClick = {
                    if (ui.robotConnected) {
                        onDisconnect()
                    } else {
                        onConnect()
                    }
                }
            ) {
                Text(
                    if (ui.robotConnected) {
                        "Disconnect"
                    } else {
                        "Connect"
                    }
                )
            }

            Button(
                onClick = {
                    showSettings = true
                }
            ) {
                Text("Settings")
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initialUrl = initialUrl,
            initialFollow = initialFollow,
            onDismiss = {
                showSettings = false
            }
        ) { url, follow ->
            onSaveSettings(
                url,
                follow
            )
            showSettings = false
        }
    }
}

@Composable
private fun TestPanel(
    ui: PetUiState,
    modifier: Modifier = Modifier
) {
    val txState = when {
        !ui.robotConnected ->
            "NO ESP LINK"

        ui.espSafetyBlocked ->
            "SAFETY BLOCK → STOP"

        ui.espQueued ->
            "WS QUEUED ✓"

        else ->
            "SEND FAILED / WAITING"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.76f),
        tonalElevation = 2.dp
    ) {
        Column(
            Modifier.padding(
                horizontal = 14.dp,
                vertical = 8.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "GESTURE: ${ui.lastGesture.name}",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelMedium
            )

            Text(
                "DECISION: ${ui.decisionCommand.name}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )

            Text(
                "ESP TX: ${ui.espActualCommand?.name ?: "--"}  ${ui.espDurationMs} ms",
                color = if (ui.espQueued) {
                    Color(0xFF9AF5A6)
                } else {
                    Color.White.copy(alpha = 0.72f)
                },
                style = MaterialTheme.typography.labelMedium
            )

            Text(
                txState,
                color = when {
                    ui.espSafetyBlocked ->
                        Color(0xFFFFC66D)

                    ui.espQueued ->
                        Color(0xFF9AF5A6)

                    else ->
                        Color.White.copy(alpha = 0.55f)
                },
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    initialUrl: String,
    initialFollow: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var url by rememberSaveable(initialUrl) {
        mutableStateOf(initialUrl)
    }

    var follow by rememberSaveable(initialFollow) {
        mutableStateOf(initialFollow)
    }

    Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                Modifier.padding(20.dp)
            ) {
                Text(
                    "Robot settings",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(
                    Modifier.width(1.dp)
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                    },
                    label = {
                        Text("ESP32 WebSocket URL")
                    },
                    singleLine = true
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = follow,
                        onCheckedChange = {
                            follow = it
                        }
                    )

                    Spacer(
                        Modifier.width(10.dp)
                    )

                    Text(
                        "Autonomous face follow"
                    )
                }

                Text(
                    "Movement stays blocked until the ESP32 reports safeToMove=true.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            onSave(
                                url,
                                follow
                            )
                        }
                    ) {
                        Text("Save + connect")
                    }
                }
            }
        }
    }
}
