package com.shehan.robotpet

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.shehan.robotpet.brain.BrainDecision
import com.shehan.robotpet.brain.Emotion
import com.shehan.robotpet.brain.MotionCommand
import com.shehan.robotpet.brain.PetBrain
import com.shehan.robotpet.brain.RobotTelemetry
import com.shehan.robotpet.brain.VisionObservation
import com.shehan.robotpet.robot.RobotLink
import com.shehan.robotpet.settings.RobotPrefs
import com.shehan.robotpet.vision.VisionManager
import com.shehan.robotpet.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PetUiState(
    val emotion: Emotion = Emotion.IDLE,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val status: String = "Starting",
    val listening: Boolean = false,
    val robotConnected: Boolean = false,
    val safeToMove: Boolean = false,
    val obstacleCm: Float? = null,
    val lastHeard: String = ""
)

class RobotPetEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val brain = PetBrain()
    private val link = RobotLink()
    private val prefs = RobotPrefs(context)
    private var telemetry = RobotTelemetry()
    private var vision: VisionManager? = null

    private val _ui = MutableStateFlow(PetUiState())
    val ui: StateFlow<PetUiState> = _ui

    private val voice = VoiceManager(
        context = context,
        onText = { text ->
            _ui.value = _ui.value.copy(lastHeard = text)
            dispatch(brain.onSpeech(text, telemetry))
        },
        onListeningChanged = { listening ->
            _ui.value = _ui.value.copy(listening = listening, emotion = if (listening) Emotion.LISTENING else _ui.value.emotion)
        }
    )

    init {
        brain.setFollowEnabled(prefs.followEnabled)
        scope.launch {
            link.telemetry.collectLatest { t ->
                telemetry = t
                _ui.value = _ui.value.copy(robotConnected = t.connected, safeToMove = t.safeToMove, obstacleCm = t.obstacleCm)
            }
        }
        scope.launch {
            while (isActive) {
                delay(2500)
                dispatch(brain.idleTick(telemetry), speak = false)
            }
        }
    }

    fun startVision(owner: LifecycleOwner) {
        if (vision != null) return
        vision = VisionManager(context) { observation -> scope.launch { onVision(observation) } }
        vision?.start(owner)
    }

    fun connectRobot() = link.connect(prefs.robotUrl)
    fun disconnectRobot() = link.disconnect()
    fun listen() = voice.listen()
    fun touch() = dispatch(brain.onTouch())

    fun updateSettings(url: String, follow: Boolean) {
        prefs.robotUrl = url
        prefs.followEnabled = follow
        brain.setFollowEnabled(follow)
        link.disconnect()
        if (url.isNotBlank()) link.connect(url)
    }

    fun currentRobotUrl(): String = prefs.robotUrl
    fun currentFollowEnabled(): Boolean = prefs.followEnabled

    private fun onVision(v: VisionObservation) {
        dispatch(brain.onVision(v, telemetry), speak = false)
    }

    private fun dispatch(d: BrainDecision, speak: Boolean = true) {
        _ui.value = _ui.value.copy(emotion = d.emotion, gazeX = d.gazeX, gazeY = d.gazeY, status = d.status)
        if (d.motion != MotionCommand.STOP || telemetry.connected) link.send(d.motion, d.motionDurationMs)
        if (speak && !d.speech.isNullOrBlank()) voice.speak(d.speech)
    }

    fun shutdown() {
        vision?.shutdown()
        voice.shutdown()
        link.shutdown()
        scope.cancel()
    }
}
