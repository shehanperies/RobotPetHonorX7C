package com.shehan.robotpet

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.LifecycleOwner
import com.shehan.robotpet.brain.BrainDecision
import com.shehan.robotpet.brain.Emotion
import com.shehan.robotpet.brain.HandGesture
import com.shehan.robotpet.brain.MotionCommand
import com.shehan.robotpet.brain.PetBrain
import com.shehan.robotpet.brain.PhoneEvent
import com.shehan.robotpet.brain.PhoneObservation
import com.shehan.robotpet.brain.RobotTelemetry
import com.shehan.robotpet.brain.VisionObservation
import com.shehan.robotpet.robot.RobotLink
import com.shehan.robotpet.sensors.PhoneSensorManager
import com.shehan.robotpet.settings.RobotPrefs
import com.shehan.robotpet.vision.VisionManager
import com.shehan.robotpet.voice.VoiceDebug
import com.shehan.robotpet.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    val robotConnected: Boolean = false,
    val safeToMove: Boolean = false,
    val obstacleCm: Float? = null,

    val lastGesture: HandGesture = HandGesture.NONE,
    val gestureConfidence: Float = 0f,
    val objectLabel: String = "",
    val objectConfidence: Float = 0f,
    val smileProbability: Float = -1f,

    val phoneEvent: PhoneEvent = PhoneEvent.NONE,
    val phoneBattery: Int = -1,
    val phoneCharging: Boolean = false,

    val voice: VoiceDebug = VoiceDebug(),
    val lastHeard: String = "",

    val testing: Boolean = false,
    val simEsp: Boolean = false,
    val decisionCommand: MotionCommand = MotionCommand.STOP,
    val espRequestedCommand: MotionCommand = MotionCommand.STOP,
    val espActualCommand: MotionCommand? = null,
    val espDurationMs: Long = 0L,
    val espQueued: Boolean = false,
    val espSafetyBlocked: Boolean = false,
    val espState: String = "IDLE",

    val eventLog: List<String> = emptyList()
)

class RobotPetEngine(
    private val context: Context
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    private val brain = PetBrain()
    private val link = RobotLink()
    private val prefs = RobotPrefs(context)

    private var realTelemetry = RobotTelemetry()
    private var vision: VisionManager? = null

    private var lastCommand = MotionCommand.STOP
    private var lastCommandMs = 0L

    private val _ui = MutableStateFlow(PetUiState())
    val ui: StateFlow<PetUiState> = _ui

    private val phoneSensors = PhoneSensorManager(context) { observation ->
        scope.launch { onPhone(observation) }
    }

    private val voice = VoiceManager(
        context = context,
        onText = { text ->
            _ui.value = _ui.value.copy(lastHeard = text)
            addLog("VOICE: $text")
            dispatch(brain.onSpeech(text, effectiveTelemetry()))
        },
        onDebug = { debug ->
            _ui.value = _ui.value.copy(voice = debug)
        }
    )

    init {
        brain.setFollowEnabled(prefs.followEnabled)
        brain.restoreMood(prefs.mood, prefs.annoyance)
        phoneSensors.start()

        scope.launch {
            link.telemetry.collectLatest { t ->
                realTelemetry = t
                if (!_ui.value.simEsp) {
                    _ui.value = _ui.value.copy(
                        robotConnected = t.connected,
                        safeToMove = t.safeToMove,
                        obstacleCm = t.obstacleCm
                    )
                }
            }
        }

        scope.launch {
            link.debug.collectLatest { d ->
                if (!_ui.value.simEsp) {
                    _ui.value = _ui.value.copy(
                        espRequestedCommand = d.requested,
                        espActualCommand = d.actual,
                        espDurationMs = d.durationMs,
                        espQueued = d.queuedToWebSocket,
                        espSafetyBlocked = d.blockedBySafety,
                        espState = when {
                            d.blockedBySafety -> "SAFETY BLOCK → STOP"
                            d.queuedToWebSocket -> "WS QUEUED"
                            realTelemetry.connected -> "SEND FAILED"
                            else -> "NO ESP LINK"
                        }
                    )
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(1800L)
                dispatch(
                    brain.idleTick(effectiveTelemetry()),
                    speak = true
                )
            }
        }

        scope.launch {
            while (isActive) {
                updateBattery()
                val snapshot = brain.moodSnapshot()
                prefs.mood = snapshot.first
                prefs.annoyance = snapshot.second
                delay(15000L)
            }
        }
    }

    fun startVision(owner: LifecycleOwner) {
        if (vision != null) return

        vision = VisionManager(context) { observation ->
            scope.launch { onVision(observation) }
        }
        vision?.start(owner)
    }

    fun connectRobot() {
        if (_ui.value.simEsp) return
        link.connect(prefs.robotUrl)
    }

    fun disconnectRobot() {
        link.disconnect()
    }

    fun listen() {
        voice.listen(prefs.voiceLanguage)
    }

    fun testVoice() {
        voice.testVoice()
    }

    fun touch() {
        dispatch(brain.onTouch())
    }

    fun pet() {
        dispatch(brain.onPetting())
    }

    fun setTesting(enabled: Boolean) {
        _ui.value = _ui.value.copy(testing = enabled)
    }

    fun setSimEsp(enabled: Boolean) {
        if (enabled) link.disconnect()

        _ui.value = _ui.value.copy(
            simEsp = enabled,
            robotConnected = if (enabled) true else realTelemetry.connected,
            safeToMove = if (enabled) true else realTelemetry.safeToMove,
            obstacleCm = if (enabled) 999f else realTelemetry.obstacleCm,
            espState = if (enabled) "SIM READY" else "REAL ESP"
        )

        addLog(if (enabled) "SIM ESP ON" else "SIM ESP OFF")
    }

    fun updateSettings(
        url: String,
        follow: Boolean,
        voiceLanguage: String
    ) {
        prefs.robotUrl = url
        prefs.followEnabled = follow
        prefs.voiceLanguage = voiceLanguage
        brain.setFollowEnabled(follow)

        if (!_ui.value.simEsp) {
            link.disconnect()
            if (url.isNotBlank()) link.connect(url)
        }
    }

    fun currentRobotUrl(): String = prefs.robotUrl
    fun currentFollowEnabled(): Boolean = prefs.followEnabled
    fun currentVoiceLanguage(): String = prefs.voiceLanguage

    private fun onVision(v: VisionObservation) {
        _ui.value = _ui.value.copy(
            lastGesture = v.handGesture,
            gestureConfidence = v.handConfidence,
            objectLabel = v.objectLabel.orEmpty(),
            objectConfidence = v.objectConfidence,
            smileProbability = v.smileProbability
        )

        dispatch(
            brain.onVision(v, effectiveTelemetry()),
            speak = true
        )
    }

    private fun onPhone(v: PhoneObservation) {
        _ui.value = _ui.value.copy(phoneEvent = v.event)
        addLog("PHONE: ${v.event}")
        brain.onPhone(v)?.let { dispatch(it) }
    }

    private fun updateBattery() {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        val percent = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else -1

        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        _ui.value = _ui.value.copy(
            phoneBattery = percent,
            phoneCharging = charging
        )

        if (percent >= 0) {
            brain.onBattery(percent, charging)?.let { dispatch(it) }
        }
    }

    private fun effectiveTelemetry(): RobotTelemetry {
        return if (_ui.value.simEsp) {
            RobotTelemetry(
                connected = true,
                safeToMove = true,
                obstacleCm = 999f,
                batteryPercent = 100,
                lastSeenMs = System.currentTimeMillis()
            )
        } else {
            realTelemetry
        }
    }

    private fun dispatch(
        d: BrainDecision,
        speak: Boolean = true
    ) {
        _ui.value = _ui.value.copy(
            emotion = d.emotion,
            gazeX = d.gazeX,
            gazeY = d.gazeY,
            status = d.status,
            decisionCommand = d.motion
        )

        if (d.motion != MotionCommand.STOP || effectiveTelemetry().connected) {
            sendCommand(d.motion, d.motionDurationMs)
        }

        if (d.sequence.isNotEmpty()) {
            scope.launch {
                for (step in d.sequence) {
                    sendCommand(step.command, step.durationMs, force = true)
                    delay(step.durationMs + step.delayAfterMs)
                }
                sendCommand(MotionCommand.STOP, 0L, force = true)
            }
        }

        if (speak && !d.speech.isNullOrBlank()) {
            voice.speak(d.speech)
        }
    }

    private fun sendCommand(
        command: MotionCommand,
        durationMs: Long,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()

        if (!force &&
            command == lastCommand &&
            now - lastCommandMs < 230L
        ) {
            return
        }

        lastCommand = command
        lastCommandMs = now

        if (_ui.value.simEsp) {
            _ui.value = _ui.value.copy(
                espRequestedCommand = command,
                espActualCommand = command,
                espDurationMs = durationMs,
                espQueued = true,
                espSafetyBlocked = false,
                espState = "SIM EXECUTED"
            )
            addLog("SIM ESP: ${command.name} ${durationMs}ms")
        } else {
            link.send(command, durationMs)
        }
    }

    private fun addLog(line: String) {
        val stamp = (System.currentTimeMillis() / 1000L) % 100000
        val updated = (listOf("$stamp • $line") + _ui.value.eventLog).take(7)
        _ui.value = _ui.value.copy(eventLog = updated)
    }

    fun shutdown() {
        val snapshot = brain.moodSnapshot()
        prefs.mood = snapshot.first
        prefs.annoyance = snapshot.second
        phoneSensors.stop()
        vision?.shutdown()
        voice.shutdown()
        link.shutdown()
        scope.cancel()
    }
}
