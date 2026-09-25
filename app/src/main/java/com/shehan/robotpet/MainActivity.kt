package com.shehan.robotpet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shehan.robotpet.ui.RobotScreen

class MainActivity : ComponentActivity() {
    private lateinit var engine: RobotPetEngine

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        engine.onMicrophonePermissionChanged(mic)
        if (camera) engine.startVision(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        engine = RobotPetEngine(applicationContext)
        requestNeededPermissions()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by engine.ui.collectAsState()
                RobotScreen(
                    ui = ui,
                    initialUrl = engine.currentRobotUrl(), initialFollow = engine.currentFollowEnabled(),
                    initialVoiceLanguage = engine.currentVoiceLanguage(), initialVoiceName = engine.currentVoiceName(),
                    initialVoicePreset = engine.currentVoicePreset(), initialRobotName = engine.currentRobotName(),
                    initialOwnerName = engine.currentOwnerName(), initialCharacterInstructions = engine.currentCharacterInstructions(),
                    initialCharacterNeverDo = engine.currentCharacterNeverDo(), initialGeminiEnabled = engine.currentGeminiEnabled(),
                    initialGeminiModel = engine.currentGeminiModel(), initialRemoteEnabled = engine.currentRemoteEnabled(),
                    initialRemotePort = engine.currentRemotePort(), onTouch = engine::touch, onPet = engine::pet,
                    onListen = engine::listen, onTestVoice = engine::testVoice, onPreviewVoice = engine::previewVoice,
                    onConnect = engine::connectRobot, onDisconnect = engine::disconnectRobot,
                    onTestingChanged = engine::setTesting, onSimEspChanged = engine::setSimEsp,
                    onTestGemini = engine::testGemini, onRefreshGemini = engine::refreshGeminiModels,
                    onClearGeminiKey = engine::clearGeminiKey, onSaveSettings = engine::updateSettings
                )
            }
        }
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
        }
        if (wanted.isEmpty()) {
            engine.onMicrophonePermissionChanged(true)
            engine.startVision(this)
        } else permissions.launch(wanted.toTypedArray())
    }

    override fun onDestroy() { engine.shutdown(); super.onDestroy() }
}
