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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shehan.robotpet.PetUiState
import com.shehan.robotpet.voice.VoiceChoice
import java.util.Locale

@Composable
fun RobotScreen(
    ui: PetUiState,
    initialUrl: String,
    initialFollow: Boolean,
    initialVoiceLanguage: String,
    initialVoiceName: String,
    initialVoicePreset: String,
    initialGeminiEnabled: Boolean,
    initialGeminiModel: String,
    initialRemoteEnabled: Boolean,
    initialRemotePort: Int,
    onTouch: () -> Unit,
    onPet: () -> Unit,
    onListen: () -> Unit,
    onTestVoice: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTestingChanged: (Boolean) -> Unit,
    onSimEspChanged: (Boolean) -> Unit,
    onTestGemini: (String) -> Unit,
    onRefreshGemini: () -> Unit,
    onClearGeminiKey: () -> Unit,
    onSaveSettings: (
        String,
        Boolean,
        String,
        String,
        String,
        Boolean,
        String,
        String,
        Boolean,
        Int
    ) -> Unit
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
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
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

            if (ui.gemini.enabled || ui.remote.running) {
                Text(
                    buildString {
                        if (ui.gemini.enabled) {
                            append(
                                when {
                                    ui.gemini.busy -> "Gemini thinking"
                                    ui.gemini.keyConfigured -> "Gemini ready"
                                    else -> "Gemini needs key"
                                }
                            )
                        }
                        if (ui.gemini.enabled && ui.remote.running) append(" • ")
                        if (ui.remote.running) {
                            append(if (ui.remote.manualMode) "Remote MANUAL" else "Remote ready")
                        }
                    },
                    color = Color.White.copy(alpha = 0.48f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TEST",
                color = if (ui.testing) {
                    Color(0xFF9AF5A6)
                } else {
                    Color.White.copy(alpha = 0.45f)
                },
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
            ui = ui,
            initialUrl = initialUrl,
            initialFollow = initialFollow,
            initialVoiceLanguage = initialVoiceLanguage,
            initialVoiceName = initialVoiceName,
            initialVoicePreset = initialVoicePreset,
            initialGeminiEnabled = initialGeminiEnabled,
            initialGeminiModel = initialGeminiModel,
            initialRemoteEnabled = initialRemoteEnabled,
            initialRemotePort = initialRemotePort,
            onDismiss = { showSettings = false },
            onTestGemini = onTestGemini,
            onRefreshGemini = onRefreshGemini,
            onClearGeminiKey = onClearGeminiKey
        ) {
                url,
                follow,
                language,
                voiceName,
                preset,
                geminiEnabled,
                geminiModel,
                apiKey,
                remoteEnabled,
                remotePort ->
            onSaveSettings(
                url,
                follow,
                language,
                voiceName,
                preset,
                geminiEnabled,
                geminiModel,
                apiKey,
                remoteEnabled,
                remotePort
            )
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
        color = Color.Black.copy(alpha = 0.84f),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SIM ESP",
                    color = if (ui.simEsp) {
                        Color(0xFF9AF5A6)
                    } else {
                        Color.White.copy(alpha = 0.6f)
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                Switch(
                    checked = ui.simEsp,
                    onCheckedChange = onSimEspChanged
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onTestVoice,
                    enabled = !ui.voice.listening
                ) {
                    Text("Voice test")
                }
            }

            Text(
                "MODE ${ui.mode.name} • DECISION ${ui.decisionCommand.name}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )

            Text(
                "MIND mood=${ui.mind.mood} social=${ui.mind.socialNeed} bored=${ui.mind.boredom} curious=${ui.mind.curiosity} energy=${ui.mind.energy}",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall
            )

            Text(
                "HAND raw=${ui.rawHandLabel.ifBlank { "--" }} → ${ui.lastGesture.name} ${(ui.gestureConfidence * 100).toInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )

            Text(
                "OBJECT ${ui.objectLabel.ifBlank { "--" }} ${(ui.objectConfidence * 100).toInt()}% • SMILE ${(ui.smileProbability.coerceAtLeast(0f) * 100).toInt()}%",
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

            if (ui.voice.finalText.isNotBlank()) {
                Text(
                    "HEARD: ${ui.voice.finalText}",
                    color = Color.White.copy(alpha = 0.78f),
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
                "AI ${if (ui.gemini.enabled) "ON" else "OFF"} • ${if (ui.gemini.keyConfigured) "KEY OK" else "NO KEY"} • ${ui.gemini.selectedModel}${if (ui.gemini.busy) " • BUSY" else ""}",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall
            )

            if (ui.gemini.lastError.isNotBlank()) {
                Text(
                    "AI ERROR: ${ui.gemini.lastError}",
                    color = Color(0xFFFFC66D),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (ui.remote.running) {
                Text(
                    "REMOTE ${if (ui.remote.manualMode) "MANUAL" else "AUTO"} • PIN ${ui.remote.pin}",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                "PHONE ${ui.phoneEvent.name} • BAT ${ui.phoneBattery}%${if (ui.phoneCharging) " ⚡" else ""}",
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.labelSmall
            )

            Text(
                "ESP TX ${ui.espActualCommand?.name ?: "--"} ${ui.espDurationMs}ms • ${ui.espState}",
                color = if (ui.espQueued) {
                    Color(0xFF9AF5A6)
                } else {
                    Color.White.copy(alpha = 0.65f)
                },
                style = MaterialTheme.typography.labelMedium
            )

            if (ui.eventLog.isNotEmpty()) {
                Text(
                    ui.eventLog.take(3).joinToString("\n"),
                    color = Color.White.copy(alpha = 0.50f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    ui: PetUiState,
    initialUrl: String,
    initialFollow: Boolean,
    initialVoiceLanguage: String,
    initialVoiceName: String,
    initialVoicePreset: String,
    initialGeminiEnabled: Boolean,
    initialGeminiModel: String,
    initialRemoteEnabled: Boolean,
    initialRemotePort: Int,
    onDismiss: () -> Unit,
    onTestGemini: (String) -> Unit,
    onRefreshGemini: () -> Unit,
    onClearGeminiKey: () -> Unit,
    onSave: (
        String,
        Boolean,
        String,
        String,
        String,
        Boolean,
        String,
        String,
        Boolean,
        Int
    ) -> Unit
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var follow by rememberSaveable(initialFollow) { mutableStateOf(initialFollow) }
    var voiceLanguage by rememberSaveable(initialVoiceLanguage) {
        mutableStateOf(initialVoiceLanguage)
    }
    var voiceName by rememberSaveable(initialVoiceName) {
        mutableStateOf(initialVoiceName)
    }
    var voicePreset by rememberSaveable(initialVoicePreset) {
        mutableStateOf(initialVoicePreset)
    }

    var geminiEnabled by rememberSaveable(initialGeminiEnabled) {
        mutableStateOf(initialGeminiEnabled)
    }
    var geminiModel by rememberSaveable(initialGeminiModel) {
        mutableStateOf(initialGeminiModel.ifBlank { "AUTO" })
    }
    var apiKey by rememberSaveable { mutableStateOf("") }

    var remoteEnabled by rememberSaveable(initialRemoteEnabled) {
        mutableStateOf(initialRemoteEnabled)
    }
    var remotePortText by rememberSaveable(initialRemotePort) {
        mutableStateOf(initialRemotePort.toString())
    }

    val languages = (ui.voice.availableLanguages + voiceLanguage)
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    val voicesForLanguage = ui.voice.availableVoices.filter {
        val selected = Locale.forLanguageTag(voiceLanguage)
        val item = Locale.forLanguageTag(it.languageTag)
        it.languageTag == voiceLanguage || item.language == selected.language
    }

    val modelChoices = (
        listOf("AUTO") +
            ui.gemini.availableModels +
            geminiModel
        )
        .filter { it.isNotBlank() }
        .distinct()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(
                Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Robot settings", style = MaterialTheme.typography.titleLarge)

                Spacer(Modifier.height(8.dp))
                Text("ESP32", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("ESP32 WebSocket URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = follow,
                        onCheckedChange = { follow = it }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Allow Follow / Come Here")
                }

                Text(
                    "Follow is session-based, not permanently active. All real movement remains ESP32 safety-gated.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))
                Text("Voice", style = MaterialTheme.typography.titleMedium)

                Text("Speech recognition language", style = MaterialTheme.typography.labelMedium)
                ChoiceMenu(
                    label = languageDisplayName(voiceLanguage),
                    items = languages,
                    itemLabel = { languageDisplayName(it) },
                    onChoose = {
                        voiceLanguage = it
                        voiceName = ""
                    }
                )

                Text("Installed TTS voice", style = MaterialTheme.typography.labelMedium)
                VoiceChoiceMenu(
                    selectedVoiceName = voiceName,
                    voices = voicesForLanguage,
                    onChoose = { voiceName = it.name }
                )

                Text("Voice style", style = MaterialTheme.typography.labelMedium)
                ChoiceMenu(
                    label = voicePreset,
                    items = listOf("Robot", "Normal", "Cute", "Deep", "Tiny Bot", "Calm"),
                    itemLabel = { it },
                    onChoose = { voicePreset = it }
                )

                Text(
                    "Installed voices come from this phone's TTS engine. Robot/Cute/Deep/Tiny/Calm change pitch and speed.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))
                Text("Optional Gemini brain", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = geminiEnabled,
                        onCheckedChange = { geminiEnabled = it }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Use Gemini for high-level personality/chat")
                }

                Text(
                    if (ui.gemini.keyConfigured) {
                        "API key is securely saved on this phone."
                    } else {
                        "No API key saved. Local Living Brain still works normally."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API key • blank keeps saved key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Gemini model", style = MaterialTheme.typography.labelMedium)
                ChoiceMenu(
                    label = geminiModel,
                    items = modelChoices,
                    itemLabel = { it },
                    onChoose = { geminiModel = it }
                )

                OutlinedTextField(
                    value = geminiModel,
                    onValueChange = { geminiModel = it },
                    label = { Text("Model • AUTO or custom model name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onTestGemini(apiKey) },
                        enabled = !ui.gemini.busy
                    ) {
                        Text(if (ui.gemini.busy) "Testing…" else "Test / Load models")
                    }

                    Button(
                        onClick = onRefreshGemini,
                        enabled = ui.gemini.keyConfigured && !ui.gemini.busy
                    ) {
                        Text("Refresh")
                    }
                }

                if (ui.gemini.keyConfigured) {
                    Button(
                        onClick = {
                            apiKey = ""
                            onClearGeminiKey()
                        }
                    ) {
                        Text("Remove saved Gemini key")
                    }
                }

                if (ui.gemini.lastError.isNotBlank()) {
                    Text(
                        "Gemini: ${ui.gemini.lastError}",
                        color = Color(0xFFFFA96E),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (ui.gemini.lastReply.isNotBlank()) {
                    Text(
                        ui.gemini.lastReply,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    "Gemini can choose only safe high-level actions such as play, greet, search or follow. It never gets direct motor speed control.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))
                Text("Remote control", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = remoteEnabled,
                        onCheckedChange = { remoteEnabled = it }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Enable same-Wi-Fi phone remote")
                }

                OutlinedTextField(
                    value = remotePortText,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() } && value.length <= 5) {
                            remotePortText = value
                        }
                    },
                    label = { Text("Remote port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                if (ui.remote.running) {
                    Text(
                        "Remote URL:\n${ui.remote.url}\nPIN: ${ui.remote.pin}",
                        color = Color(0xFF9AF5A6),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "After Save, open the shown URL on another phone connected to the same Wi-Fi/hotspot.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            onSave(
                                url,
                                follow,
                                voiceLanguage,
                                voiceName,
                                voicePreset,
                                geminiEnabled,
                                geminiModel.ifBlank { "AUTO" },
                                apiKey,
                                remoteEnabled,
                                remotePortText.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
                            )
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceMenu(
    label: String,
    items: List<String>,
    itemLabel: (String) -> String,
    onChoose: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Box {
        Button(onClick = { expanded = true }) {
            Text(label.ifBlank { "Select" }, maxLines = 1)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onChoose(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun VoiceChoiceMenu(
    selectedVoiceName: String,
    voices: List<VoiceChoice>,
    onChoose: (VoiceChoice) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.name == selectedVoiceName }
    val label = selected?.label
        ?: if (voices.isEmpty()) "System default" else "Auto / system default"

    Box {
        Button(
            onClick = { expanded = true },
            enabled = voices.isNotEmpty()
        ) {
            Text(label, maxLines = 1)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.label) },
                    onClick = {
                        onChoose(voice)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun languageDisplayName(tag: String): String {
    if (tag.isBlank()) return "Select language"
    val locale = Locale.forLanguageTag(tag)
    val display = locale.getDisplayName(locale)
    return if (display.isBlank()) tag else "$display • $tag"
}
