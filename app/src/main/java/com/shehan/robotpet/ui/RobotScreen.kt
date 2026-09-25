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
    initialRobotName: String,
    initialOwnerName: String,
    initialCharacterInstructions: String,
    initialCharacterNeverDo: String,
    initialGeminiEnabled: Boolean,
    initialGeminiModel: String,
    initialRemoteEnabled: Boolean,
    initialRemotePort: Int,
    onTouch: () -> Unit,
    onPet: () -> Unit,
    onListen: () -> Unit,
    onTestVoice: () -> Unit,
    onPreviewVoice: (String, String, String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTestingChanged: (Boolean) -> Unit,
    onSimEspChanged: (Boolean) -> Unit,
    onTestGemini: (String) -> Unit,
    onRefreshGemini: () -> Unit,
    onClearGeminiKey: () -> Unit,
    onSaveSettings: (
        String, Boolean, String, String, String,
        String, String, String, String,
        Boolean, String, String, Boolean, Int
    ) -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RobotFace(
            emotion = ui.emotion,
            gazeX = ui.gazeX,
            gazeY = ui.gazeY,
            onTouch = onTouch,
            onPet = onPet
        )

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                ui.status,
                color = Color.White.copy(alpha = 0.90f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                when {
                    ui.simEsp -> "SIM ESP • V6 executive"
                    ui.robotConnected && ui.safeToMove -> "ESP32 connected • safety clear"
                    ui.robotConnected -> "ESP32 connected • movement locked"
                    else -> "Autonomous phone mode • ESP32 offline"
                },
                color = if (ui.safeToMove) Color(0xFF9AF5A6) else Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall
            )
            if (ui.gemini.enabled || ui.remote.running) {
                Text(
                    buildString {
                        if (ui.gemini.enabled) append(
                            when {
                                ui.gemini.busy -> "Gemini thinking"
                                ui.gemini.cooldownUntilMs > System.currentTimeMillis() -> "Gemini cooling down"
                                ui.gemini.keyConfigured -> "Gemini ready"
                                else -> "Gemini needs key"
                            }
                        )
                        if (ui.gemini.enabled && ui.remote.running) append(" • ")
                        if (ui.remote.running) append(if (ui.remote.manualMode) "Remote MANUAL" else "Remote ready")
                    },
                    color = Color.White.copy(alpha = 0.48f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TEST", color = if (ui.testing) Color(0xFF9AF5A6) else Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall)
            Switch(checked = ui.testing, onCheckedChange = onTestingChanged)
        }

        if (ui.testing) {
            TestPanel(
                ui = ui,
                onSimEspChanged = onSimEspChanged,
                onTestVoice = onTestVoice,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 78.dp)
            )
        } else if (ui.lastHeard.isNotBlank()) {
            Text(
                "Heard: ${ui.lastHeard}",
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 78.dp, start = 24.dp, end = 24.dp)
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onListen, enabled = !ui.voice.listening) {
                Text(if (ui.voice.listening) ui.voice.phase else "Talk")
            }
            Button(
                onClick = { if (ui.robotConnected && !ui.simEsp) onDisconnect() else onConnect() },
                enabled = !ui.simEsp
            ) { Text(if (ui.robotConnected && !ui.simEsp) "Disconnect" else "Connect") }
            Button(onClick = { showSettings = true }) { Text("Settings") }
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
            initialRobotName = initialRobotName,
            initialOwnerName = initialOwnerName,
            initialCharacterInstructions = initialCharacterInstructions,
            initialCharacterNeverDo = initialCharacterNeverDo,
            initialGeminiEnabled = initialGeminiEnabled,
            initialGeminiModel = initialGeminiModel,
            initialRemoteEnabled = initialRemoteEnabled,
            initialRemotePort = initialRemotePort,
            onDismiss = { showSettings = false },
            onPreviewVoice = onPreviewVoice,
            onTestGemini = onTestGemini,
            onRefreshGemini = onRefreshGemini,
            onClearGeminiKey = onClearGeminiKey
        ) { url, follow, language, voiceName, preset, robotName, ownerName, character, neverDo, geminiEnabled, geminiModel, apiKey, remoteEnabled, remotePort ->
            onSaveSettings(
                url, follow, language, voiceName, preset,
                robotName, ownerName, character, neverDo,
                geminiEnabled, geminiModel, apiKey, remoteEnabled, remotePort
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
                Text("SIM ESP", color = if (ui.simEsp) Color(0xFF9AF5A6) else Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                Switch(checked = ui.simEsp, onCheckedChange = onSimEspChanged)
                Spacer(Modifier.width(10.dp))
                Button(onClick = onTestVoice, enabled = !ui.voice.listening) { Text("Voice test") }
            }

            Text("MODE ${ui.mode.name} • BEHAVIOR ${ui.activeBehavior}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text("LOCKS ${ui.executiveLocks}", color = Color(0xFF9AF5A6), style = MaterialTheme.typography.labelSmall)
            Text(
                "MIND mood=${ui.mind.mood} social=${ui.mind.socialNeed} bored=${ui.mind.boredom} curious=${ui.mind.curiosity} affection=${ui.mind.affection}",
                color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall
            )
            Text(
                "HAND ${ui.gestureStage} • raw=${ui.rawHandLabel.ifBlank { "--" }} • candidate=${ui.gestureCandidate.name} • ${(ui.gestureConfidence * 100).toInt()}%",
                color = Color.White, style = MaterialTheme.typography.labelSmall
            )
            Text(
                "FACE smile=${(ui.smileProbability.coerceAtLeast(0f) * 100).toInt()}% • kiss=${(ui.kissConfidence * 100).toInt()}%${if (ui.kissDetected) " CONFIRMED" else ""}${if (ui.blownKissDetected) " • BLOWN KISS" else ""}",
                color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall
            )
            Text(
                "OBJECTS ${ui.objectSummary.ifBlank { "--" }}",
                color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall
            )
            Text(
                "VOICE ${ui.voice.phase} • ${if (ui.voice.usingOnDevice) "ON-DEVICE" else "SYSTEM"} • ${ui.voice.languageTag} • RMS ${"%.1f".format(ui.voice.rmsDb)}",
                color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall
            )
            if (ui.voice.partialText.isNotBlank()) Text("HEARING: ${ui.voice.partialText}", color = Color(0xFF9AF5A6), style = MaterialTheme.typography.labelSmall)
            if (ui.voice.finalText.isNotBlank()) Text("HEARD: ${ui.voice.finalText}", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall)
            if (ui.voice.error.isNotBlank()) Text("VOICE ERROR: ${ui.voice.error}", color = Color(0xFFFFC66D), style = MaterialTheme.typography.labelSmall)
            Text("TTS ACTIVE ${ui.voice.activeVoiceName.ifBlank { "--" }}", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)

            Text(
                "AI ${if (ui.gemini.enabled) "ON" else "OFF"} • ${if (ui.gemini.keyConfigured) "KEY OK" else "NO KEY"} • ${ui.gemini.selectedModel}${if (ui.gemini.busy) " • BUSY" else ""}",
                color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall
            )
            if (ui.gemini.lastError.isNotBlank()) Text("AI ERROR: ${ui.gemini.lastError}", color = Color(0xFFFFC66D), style = MaterialTheme.typography.labelSmall)
            if (ui.remote.running) Text("REMOTE ${if (ui.remote.manualMode) "MANUAL" else "AUTO"} • PIN ${ui.remote.pin}", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
            Text("PHONE ${ui.phoneEvent.name} • BAT ${ui.phoneBattery}%${if (ui.phoneCharging) " ⚡" else ""}", color = Color.White.copy(alpha = 0.70f), style = MaterialTheme.typography.labelSmall)
            Text(
                "ESP TX#${ui.espTxSeq} ${ui.espActualCommand?.name ?: "--"} ${ui.espDurationMs}ms • ACK#${ui.espAckSeq} ${ui.espAckAccepted ?: "?"} • ${ui.espState}",
                color = if (ui.espQueued) Color(0xFF9AF5A6) else Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelMedium
            )
            if (ui.espAckReason.isNotBlank()) Text("ESP REASON ${ui.espAckReason}", color = Color(0xFFFFC66D), style = MaterialTheme.typography.labelSmall)
            if (ui.eventLog.isNotEmpty()) Text(ui.eventLog.take(3).joinToString("\n"), color = Color.White.copy(alpha = 0.50f), style = MaterialTheme.typography.labelSmall)
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
    initialRobotName: String,
    initialOwnerName: String,
    initialCharacterInstructions: String,
    initialCharacterNeverDo: String,
    initialGeminiEnabled: Boolean,
    initialGeminiModel: String,
    initialRemoteEnabled: Boolean,
    initialRemotePort: Int,
    onDismiss: () -> Unit,
    onPreviewVoice: (String, String, String) -> Unit,
    onTestGemini: (String) -> Unit,
    onRefreshGemini: () -> Unit,
    onClearGeminiKey: () -> Unit,
    onSave: (
        String, Boolean, String, String, String,
        String, String, String, String,
        Boolean, String, String, Boolean, Int
    ) -> Unit
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var follow by rememberSaveable(initialFollow) { mutableStateOf(initialFollow) }
    var voiceLanguage by rememberSaveable(initialVoiceLanguage) { mutableStateOf(initialVoiceLanguage) }
    var voiceName by rememberSaveable(initialVoiceName) { mutableStateOf(initialVoiceName) }
    var voicePreset by rememberSaveable(initialVoicePreset) { mutableStateOf(initialVoicePreset) }
    var robotName by rememberSaveable(initialRobotName) { mutableStateOf(initialRobotName) }
    var ownerName by rememberSaveable(initialOwnerName) { mutableStateOf(initialOwnerName) }
    var character by rememberSaveable(initialCharacterInstructions) { mutableStateOf(initialCharacterInstructions) }
    var neverDo by rememberSaveable(initialCharacterNeverDo) { mutableStateOf(initialCharacterNeverDo) }
    var geminiEnabled by rememberSaveable(initialGeminiEnabled) { mutableStateOf(initialGeminiEnabled) }
    var geminiModel by rememberSaveable(initialGeminiModel) { mutableStateOf(initialGeminiModel.ifBlank { "AUTO" }) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var remoteEnabled by rememberSaveable(initialRemoteEnabled) { mutableStateOf(initialRemoteEnabled) }
    var remotePortText by rememberSaveable(initialRemotePort) { mutableStateOf(initialRemotePort.toString()) }

    val languages = (ui.voice.availableLanguages + voiceLanguage).filter { it.isNotBlank() }.distinct().sorted()
    val voicesForLanguage = ui.voice.availableVoices.filter {
        val selected = Locale.forLanguageTag(voiceLanguage)
        val item = Locale.forLanguageTag(it.languageTag)
        it.languageTag == voiceLanguage || item.language == selected.language
    }
    val modelChoices = (listOf("AUTO") + ui.gemini.availableModels + geminiModel).filter { it.isNotBlank() }.distinct()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("Robot V6 settings", style = MaterialTheme.typography.titleLarge)

                Spacer(Modifier.height(8.dp))
                Text("ESP32", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("ESP32 WebSocket URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = follow, onCheckedChange = { follow = it })
                    Spacer(Modifier.width(10.dp)); Text("Allow Follow / Come Here")
                }
                Text("Follow is session-based. ESP32 safety can always override movement.", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(12.dp))
                Text("Character", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = robotName, onValueChange = { robotName = it }, label = { Text("Robot name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ownerName, onValueChange = { ownerName = it }, label = { Text("Owner name • optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = character, onValueChange = { character = it }, label = { Text("Personality / character instructions") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = neverDo, onValueChange = { neverDo = it }, label = { Text("Never do / boundaries") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Text("These become Gemini system instructions. Local safety still has final control.", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(12.dp))
                Text("Voice", style = MaterialTheme.typography.titleMedium)
                Text("Speech recognition language", style = MaterialTheme.typography.labelMedium)
                ChoiceMenu(
                    label = recognitionLanguageLabel(voiceLanguage, ui),
                    items = languages,
                    itemLabel = { recognitionLanguageLabel(it, ui) },
                    onChoose = { voiceLanguage = it; voiceName = "" }
                )
                Text("Only recognizer-supported languages are listed; TTS languages are kept separate.", style = MaterialTheme.typography.bodySmall)

                Text("Actual installed TTS voice", style = MaterialTheme.typography.labelMedium)
                VoiceChoiceMenu(
                    selectedVoiceName = voiceName,
                    voices = voicesForLanguage,
                    onChoose = {
                        voiceName = it.name
                        onPreviewVoice(voiceLanguage, it.name, voicePreset)
                    }
                )
                Text("Tap a voice to hear an immediate preview before Save.", style = MaterialTheme.typography.bodySmall)

                Text("Voice effect", style = MaterialTheme.typography.labelMedium)
                ChoiceMenu(
                    label = voicePreset,
                    items = listOf("Normal", "Robot", "Cute", "Deep", "Tiny Bot", "Calm"),
                    itemLabel = { it },
                    onChoose = {
                        voicePreset = it
                        if (voiceName.isNotBlank()) onPreviewVoice(voiceLanguage, voiceName, it)
                    }
                )
                Text("Effects change pitch/speed; the TTS voice above is the real voice identity.", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(12.dp))
                Text("Optional Gemini brain", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = geminiEnabled, onCheckedChange = { geminiEnabled = it })
                    Spacer(Modifier.width(10.dp)); Text("Use Gemini for conversation / rare planning")
                }
                Text(
                    if (ui.gemini.keyConfigured) "API key securely saved on this phone." else "No API key saved. Local Living Brain still works.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("Gemini API key • blank keeps saved key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                Text("Gemini model", style = MaterialTheme.typography.labelMedium)
                ChoiceMenu(label = geminiModel, items = modelChoices, itemLabel = { it }, onChoose = { geminiModel = it })
                OutlinedTextField(value = geminiModel, onValueChange = { geminiModel = it }, label = { Text("AUTO or custom model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onTestGemini(apiKey) }, enabled = !ui.gemini.busy) { Text(if (ui.gemini.busy) "Testing…" else "Test / Load") }
                    Button(onClick = onRefreshGemini, enabled = ui.gemini.keyConfigured && !ui.gemini.busy) { Text("Refresh") }
                }
                if (ui.gemini.keyConfigured) Button(onClick = { apiKey = ""; onClearGeminiKey() }) { Text("Remove Gemini key") }
                if (ui.gemini.lastError.isNotBlank()) Text("Gemini: ${ui.gemini.lastError}", color = Color(0xFFFFA96E), style = MaterialTheme.typography.bodySmall)
                Text("V6 does not continuously poll Gemini. 429 responses trigger a cooldown circuit breaker.", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(12.dp))
                Text("Remote control", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = remoteEnabled, onCheckedChange = { remoteEnabled = it })
                    Spacer(Modifier.width(10.dp)); Text("Enable same-Wi-Fi phone remote")
                }
                OutlinedTextField(
                    value = remotePortText,
                    onValueChange = { value -> if (value.all { it.isDigit() } && value.length <= 5) remotePortText = value },
                    label = { Text("Remote port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )
                if (ui.remote.running) Text("Remote URL:\n${ui.remote.url}\nPIN: ${ui.remote.pin}", color = Color(0xFF9AF5A6), style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        onSave(
                            url, follow, voiceLanguage, voiceName, voicePreset,
                            robotName, ownerName, character, neverDo,
                            geminiEnabled, geminiModel.ifBlank { "AUTO" }, apiKey,
                            remoteEnabled, remotePortText.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
                        )
                    }) { Text("Save") }
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
        Button(onClick = { expanded = true }) { Text(label.ifBlank { "Select" }, maxLines = 1) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(itemLabel(item)) }, onClick = { onChoose(item); expanded = false })
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
    val label = selected?.label ?: if (voices.isEmpty()) "No matching installed voice" else "Auto / system default"
    Box {
        Button(onClick = { expanded = true }, enabled = voices.isNotEmpty()) { Text(label, maxLines = 1) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            voices.forEach { voice -> DropdownMenuItem(text = { Text(voice.label) }, onClick = { onChoose(voice); expanded = false }) }
        }
    }
}

private fun recognitionLanguageLabel(tag: String, ui: PetUiState): String {
    if (tag.isBlank()) return "Select language"
    val locale = Locale.forLanguageTag(tag)
    val display = locale.getDisplayName(locale).ifBlank { tag }
    val support = when {
        tag in ui.voice.installedOnDeviceLanguages -> "offline installed"
        tag in ui.voice.onlineLanguages -> "online"
        else -> "current / unverified"
    }
    return "$display • $tag • $support"
}
