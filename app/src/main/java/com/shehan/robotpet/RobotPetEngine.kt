package com.shehan.robotpet

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.LifecycleOwner
import com.shehan.robotpet.ai.GeminiBrainManager
import com.shehan.robotpet.ai.GeminiContext
import com.shehan.robotpet.ai.GeminiStatus
import com.shehan.robotpet.ai.SecureSecretStore
import com.shehan.robotpet.brain.AiDirective
import com.shehan.robotpet.brain.BrainDecision
import com.shehan.robotpet.brain.Emotion
import com.shehan.robotpet.brain.HandGesture
import com.shehan.robotpet.brain.MotionCommand
import com.shehan.robotpet.brain.PetBrain
import com.shehan.robotpet.brain.PetMindSnapshot
import com.shehan.robotpet.brain.PetMode
import com.shehan.robotpet.brain.PhoneEvent
import com.shehan.robotpet.brain.PhoneObservation
import com.shehan.robotpet.brain.RobotTelemetry
import com.shehan.robotpet.brain.VisionObservation
import com.shehan.robotpet.remote.RemoteControlServer
import com.shehan.robotpet.remote.RemoteServerInfo
import com.shehan.robotpet.robot.RobotLink
import com.shehan.robotpet.sensors.PhoneSensorManager
import com.shehan.robotpet.settings.RobotPrefs
import com.shehan.robotpet.vision.VisionManager
import com.shehan.robotpet.voice.VoiceDebug
import com.shehan.robotpet.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PetUiState(
    val emotion: Emotion = Emotion.IDLE,
    val mode: PetMode = PetMode.IDLE,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val status: String = "Starting",

    val robotConnected: Boolean = false,
    val safeToMove: Boolean = false,
    val obstacleCm: Float? = null,

    val lastGesture: HandGesture = HandGesture.NONE,
    val rawHandLabel: String = "",
    val gestureConfidence: Float = 0f,
    val objectLabel: String = "",
    val objectConfidence: Float = 0f,
    val smileProbability: Float = -1f,

    val phoneEvent: PhoneEvent = PhoneEvent.NONE,
    val phoneBattery: Int = -1,
    val phoneCharging: Boolean = false,

    val voice: VoiceDebug = VoiceDebug(),
    val lastHeard: String = "",

    val mind: PetMindSnapshot = PetMindSnapshot(),
    val gemini: GeminiStatus = GeminiStatus(),
    val remote: RemoteServerInfo = RemoteServerInfo(),

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val brain = PetBrain()
    private val link = RobotLink()
    private val prefs = RobotPrefs(context)
    private val secrets = SecureSecretStore(context)

    private var realTelemetry = RobotTelemetry()
    private var vision: VisionManager? = null
    private var latestVision = VisionObservation()

    private var lastCommand = MotionCommand.STOP
    private var lastCommandMs = 0L
    private var lastDriveTxMs = 0L
    private var lastForkTxMs = 0L
    private var sequenceJob: Job? = null

    @Volatile
    private var remoteManual = false

    private var remoteLastCommandMs = 0L
    private var remoteLastMotion = MotionCommand.STOP

    private val _ui = MutableStateFlow(PetUiState())
    val ui: StateFlow<PetUiState> = _ui

    private var remoteServer: RemoteControlServer = createRemoteServer(prefs.remotePort)

    private val gemini = GeminiBrainManager(
        prefs = prefs,
        secrets = secrets,
        onStatus = { status ->
            _ui.value = _ui.value.copy(gemini = status)
        },
        onDirective = { directive ->
            scope.launch {
                if (!remoteManual && !_ui.value.voice.listening) {
                    dispatch(brain.onAiDirective(directive, effectiveTelemetry()))
                }
            }
        },
        onPlanFailure = { reason ->
            scope.launch {
                addLog("GEMINI FAIL: $reason")
                if (_ui.value.lastHeard.isNotBlank() && !_ui.value.voice.listening) {
                    dispatch(
                        brain.onSpeech(
                            _ui.value.lastHeard,
                            effectiveTelemetry()
                        )
                    )
                }
            }
        }
    )

    private val phoneSensors = PhoneSensorManager(context) { observation ->
        scope.launch { onPhone(observation) }
    }

    private val voice = VoiceManager(
        context = context,
        onText = { text ->
            _ui.value = _ui.value.copy(lastHeard = text)
            addLog("VOICE: $text")

            if (brain.isDirectVoiceCommand(text)) {
                dispatch(brain.onSpeech(text, effectiveTelemetry()))
            } else if (_ui.value.gemini.enabled && _ui.value.gemini.keyConfigured) {
                _ui.value = _ui.value.copy(
                    mode = PetMode.THINKING,
                    emotion = Emotion.CURIOUS,
                    status = "Gemini thinking…"
                )
                gemini.plan(
                    context = geminiContext(),
                    reason = "user spoke",
                    userText = text,
                    bypassRateLimit = true
                )
            } else {
                dispatch(brain.onSpeech(text, effectiveTelemetry()))
            }
        },
        onFailure = { reason ->
            _ui.value = _ui.value.copy(
                mode = PetMode.ENGAGED,
                status = "Voice • $reason"
            )
            addLog("VOICE ERROR: $reason")
        },
        onDebug = { debug ->
            _ui.value = _ui.value.copy(
                voice = debug,
                emotion = if (debug.listening) Emotion.LISTENING else _ui.value.emotion,
                mode = if (debug.listening) PetMode.LISTENING else _ui.value.mode,
                status = if (debug.listening) "Listening…" else _ui.value.status
            )
        }
    )

    init {
        brain.setFollowEnabled(prefs.followEnabled)
        brain.restoreMind(
            savedMood = prefs.mood,
            savedAnnoyance = prefs.annoyance,
            savedSocialNeed = prefs.socialNeed,
            savedBoredom = prefs.boredom,
            savedCuriosity = prefs.curiosity,
            savedEnergy = prefs.energy
        )

        voice.applySettings(
            prefs.voiceLanguage,
            prefs.voiceName,
            prefs.voicePreset
        )

        phoneSensors.start()

        if (prefs.remoteEnabled) {
            runCatching { remoteServer.startRemote() }
                .onFailure { addLog("REMOTE START FAIL: ${it.message}") }
        }

        scope.launch {
            link.telemetry.collectLatest { t ->
                val becameUnsafe = realTelemetry.safeToMove && !t.safeToMove
                realTelemetry = t

                if (!_ui.value.simEsp) {
                    _ui.value = _ui.value.copy(
                        robotConnected = t.connected,
                        safeToMove = t.safeToMove,
                        obstacleCm = t.obstacleCm
                    )
                }

                if (becameUnsafe) {
                    sequenceJob?.cancel()
                    sequenceJob = null
                    sendCommand(MotionCommand.STOP, 0L, force = true)
                    addLog("SAFETY: STOP")
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

        // Local brain heartbeat. This keeps life going without Gemini.
        scope.launch {
            while (isActive) {
                delay(2200L)

                if (!_ui.value.voice.listening) {
                    dispatch(
                        brain.idleTick(effectiveTelemetry()),
                        speak = true
                    )
                }

                _ui.value = _ui.value.copy(mind = brain.mindSnapshot())
            }
        }

        // Persist personality state and battery context.
        scope.launch {
            while (isActive) {
                updateBattery()
                persistMind()
                delay(15000L)
            }
        }

        // Optional Gemini planning. It is deliberately sparse: local brain is primary.
        scope.launch {
            while (isActive) {
                delay(12000L)

                if (
                    _ui.value.gemini.enabled &&
                    _ui.value.gemini.keyConfigured &&
                    !_ui.value.gemini.busy &&
                    !_ui.value.voice.listening &&
                    !remoteManual &&
                    brain.shouldRequestAi()
                ) {
                    gemini.plan(
                        context = geminiContext(),
                        reason = "local drives reached an autonomous planning opportunity"
                    )
                }
            }
        }

        // Remote dead-man watchdog: no repeated remote drive command -> STOP.
        scope.launch {
            while (isActive) {
                delay(200L)
                if (
                    remoteManual &&
                    remoteLastMotion != MotionCommand.STOP &&
                    System.currentTimeMillis() - remoteLastCommandMs > 900L
                ) {
                    sendCommand(MotionCommand.STOP, 0L, force = true)
                    remoteLastMotion = MotionCommand.STOP
                    addLog("REMOTE WATCHDOG: STOP")
                }
            }
        }
    }

    fun startVision(owner: LifecycleOwner) {
        if (vision != null) return

        vision = VisionManager(
            context = context,
            onObservation = { observation ->
                scope.launch { onVision(observation) }
            },
            onFrame = { bitmap ->
                remoteServer.updateFrame(bitmap)
            }
        )
        vision?.start(owner)
    }

    fun connectRobot() {
        if (_ui.value.simEsp) return
        link.connect(prefs.robotUrl)
    }

    fun disconnectRobot() {
        sequenceJob?.cancel()
        sequenceJob = null
        link.disconnect()
    }

    fun listen() {
        voice.listen(prefs.voiceLanguage)
    }

    fun testVoice() {
        voice.testVoice()
    }

    fun touch() {
        if (!_ui.value.voice.listening) dispatch(brain.onTouch())
    }

    fun pet() {
        if (!_ui.value.voice.listening) dispatch(brain.onPetting())
    }

    fun setTesting(enabled: Boolean) {
        _ui.value = _ui.value.copy(testing = enabled)
    }

    fun setSimEsp(enabled: Boolean) {
        sequenceJob?.cancel()
        sequenceJob = null

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
        followAllowed: Boolean,
        voiceLanguage: String,
        voiceName: String,
        voicePreset: String,
        geminiEnabled: Boolean,
        geminiModel: String,
        apiKeyInput: String,
        remoteEnabled: Boolean,
        remotePort: Int
    ) {
        prefs.robotUrl = url
        prefs.followEnabled = followAllowed
        prefs.voiceLanguage = voiceLanguage
        prefs.voiceName = voiceName
        prefs.voicePreset = voicePreset

        brain.setFollowEnabled(followAllowed)
        voice.applySettings(voiceLanguage, voiceName, voicePreset)

        if (apiKeyInput.isNotBlank()) gemini.saveKey(apiKeyInput)
        gemini.selectModel(geminiModel)
        gemini.setEnabled(geminiEnabled)

        val oldPort = prefs.remotePort
        prefs.remotePort = remotePort
        prefs.remoteEnabled = remoteEnabled

        if (oldPort != prefs.remotePort) {
            remoteServer.shutdown()
            remoteServer = createRemoteServer(prefs.remotePort)
        }

        if (remoteEnabled) {
            runCatching { remoteServer.startRemote() }
                .onFailure { addLog("REMOTE START FAIL: ${it.message}") }
        } else {
            remoteServer.stopRemote()
        }

        if (!_ui.value.simEsp) {
            link.disconnect()
            if (url.isNotBlank()) link.connect(url)
        }
    }

    fun testGemini(keyInput: String) {
        if (keyInput.isNotBlank()) gemini.saveKey(keyInput)
        gemini.testConnection()
    }

    fun refreshGeminiModels() {
        gemini.refreshModels()
    }

    fun clearGeminiKey() {
        gemini.clearKey()
    }

    fun currentRobotUrl(): String = prefs.robotUrl
    fun currentFollowEnabled(): Boolean = prefs.followEnabled
    fun currentVoiceLanguage(): String = prefs.voiceLanguage
    fun currentVoiceName(): String = prefs.voiceName
    fun currentVoicePreset(): String = prefs.voicePreset
    fun currentGeminiEnabled(): Boolean = prefs.geminiEnabled
    fun currentGeminiModel(): String = prefs.geminiModel
    fun currentRemoteEnabled(): Boolean = prefs.remoteEnabled
    fun currentRemotePort(): Int = prefs.remotePort

    private fun onVision(v: VisionObservation) {
        latestVision = v

        _ui.value = _ui.value.copy(
            lastGesture = v.handGesture,
            rawHandLabel = v.rawHandLabel,
            gestureConfidence = v.handConfidence,
            objectLabel = v.objectLabel.orEmpty(),
            objectConfidence = v.objectConfidence,
            smileProbability = v.smileProbability,
            mind = brain.mindSnapshot()
        )

        // Microphone owns the interaction while listening. Vision diagnostics continue,
        // but speech recognition cannot be killed by autonomous TTS.
        if (_ui.value.voice.listening) return

        dispatch(brain.onVision(v, effectiveTelemetry()), speak = true)
    }

    private fun onPhone(v: PhoneObservation) {
        _ui.value = _ui.value.copy(phoneEvent = v.event)
        addLog("PHONE: ${v.event}")

        if (!_ui.value.voice.listening) {
            brain.onPhone(v)?.let { dispatch(it) }
        }
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

        if (percent >= 0 && !_ui.value.voice.listening) {
            brain.onBattery(percent, charging)?.let { dispatch(it) }
        }
    }

    private fun persistMind() {
        val mind = brain.mindSnapshot()
        prefs.mood = mind.mood
        prefs.annoyance = mind.annoyance
        prefs.socialNeed = mind.socialNeed
        prefs.boredom = mind.boredom
        prefs.curiosity = mind.curiosity
        prefs.energy = mind.energy
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
        val mode = if (remoteManual) PetMode.REMOTE else d.mode

        _ui.value = _ui.value.copy(
            emotion = d.emotion,
            mode = mode,
            gazeX = d.gazeX,
            gazeY = d.gazeY,
            status = if (remoteManual) "Remote manual • ${d.status}" else d.status,
            decisionCommand = d.motion,
            mind = brain.mindSnapshot()
        )

        // Remote manual is above autonomous/voice/gesture motion. Expressions and speech can still run.
        if (!remoteManual) {
            if (d.interruptMotion) {
                sequenceJob?.cancel()
                sequenceJob = null
                sendCommand(MotionCommand.STOP, 0L, force = true)
            } else if (d.motion != MotionCommand.STOP) {
                sendCommand(d.motion, d.motionDurationMs)
            } else if (sequenceJob?.isActive != true && lastCommand != MotionCommand.STOP) {
                sendCommand(MotionCommand.STOP, 0L)
            }

            if (d.sequence.isNotEmpty()) {
                sequenceJob?.cancel()
                sequenceJob = scope.launch {
                    for (step in d.sequence) {
                        if (!isActive || remoteManual) break
                        sendCommand(step.command, step.durationMs, force = true)
                        delay(step.durationMs + step.delayAfterMs)
                    }

                    if (!remoteManual) {
                        sendCommand(MotionCommand.STOP, 0L, force = true)
                    }
                }
            }
        }

        if (
            speak &&
            !d.speech.isNullOrBlank() &&
            !_ui.value.voice.listening
        ) {
            voice.speak(d.speech)
        }
    }

    private fun sendCommand(
        command: MotionCommand,
        durationMs: Long,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()

        val drive = command in setOf(
            MotionCommand.FORWARD,
            MotionCommand.BACKWARD,
            MotionCommand.LEFT,
            MotionCommand.RIGHT
        )
        val fork = command == MotionCommand.FORK_UP ||
            command == MotionCommand.FORK_DOWN

        if (!force) {
            if (command == lastCommand && now - lastCommandMs < 480L) return
            if (drive && now - lastDriveTxMs < 620L) return
            if (fork && now - lastForkTxMs < 480L) return
        }

        lastCommand = command
        lastCommandMs = now
        if (drive) lastDriveTxMs = now
        if (fork) lastForkTxMs = now

        if (_ui.value.simEsp) {
            _ui.value = _ui.value.copy(
                espRequestedCommand = command,
                espActualCommand = command,
                espDurationMs = durationMs,
                espQueued = true,
                espSafetyBlocked = false,
                espState = "SIM WOULD EXECUTE"
            )
            addLog("SIM ESP: ${command.name} ${durationMs}ms")
        } else {
            link.send(command, durationMs)
        }
    }

    private fun createRemoteServer(port: Int): RemoteControlServer {
        return RemoteControlServer(
            port = port,
            statusProvider = {
                val s = _ui.value
                JSONObject()
                    .put("emotion", s.emotion.name)
                    .put("mode", s.mode.name)
                    .put("status", s.status)
                    .put("robotConnected", s.robotConnected)
                    .put("safeToMove", s.safeToMove)
                    .put("obstacleCm", s.obstacleCm)
                    .put("battery", s.phoneBattery)
                    .put("object", s.objectLabel)
                    .put("gesture", s.lastGesture.name)
            },
            onCommand = { command, duration ->
                scope.launch {
                    if (!remoteManual) return@launch

                    remoteLastCommandMs = System.currentTimeMillis()
                    remoteLastMotion = command
                    sequenceJob?.cancel()
                    sequenceJob = null

                    sendCommand(
                        command = command,
                        durationMs = duration,
                        force = true
                    )

                    _ui.value = _ui.value.copy(
                        mode = PetMode.REMOTE,
                        status = "Remote • ${command.name}"
                    )
                }
            },
            onManualModeChanged = { manual ->
                scope.launch {
                    remoteManual = manual
                    sequenceJob?.cancel()
                    sequenceJob = null
                    sendCommand(MotionCommand.STOP, 0L, force = true)
                    remoteLastMotion = MotionCommand.STOP
                    _ui.value = _ui.value.copy(
                        mode = if (manual) PetMode.REMOTE else PetMode.IDLE,
                        status = if (manual) "Remote manual control" else "Autonomous resumed"
                    )
                    addLog(if (manual) "REMOTE MANUAL ON" else "REMOTE AUTO")
                }
            },
            onInfoChanged = { info ->
                _ui.value = _ui.value.copy(remote = info)
            }
        )
    }

    private fun geminiContext(): GeminiContext {
        val u = _ui.value
        return GeminiContext(
            mind = brain.mindSnapshot(),
            faceVisible = latestVision.faceVisible,
            objectLabel = latestVision.objectLabel,
            objectConfidence = latestVision.objectConfidence,
            phoneBattery = u.phoneBattery,
            charging = u.phoneCharging,
            robotConnected = effectiveTelemetry().connected,
            robotSafe = effectiveTelemetry().safeToMove,
            status = u.status
        )
    }

    private fun addLog(line: String) {
        val stamp = (System.currentTimeMillis() / 1000L) % 100000
        val updated = (listOf("$stamp • $line") + _ui.value.eventLog).take(9)
        _ui.value = _ui.value.copy(eventLog = updated)
    }

    fun shutdown() {
        persistMind()
        sequenceJob?.cancel()
        phoneSensors.stop()
        vision?.shutdown()
        voice.shutdown()
        remoteServer.shutdown()
        gemini.shutdown()
        link.shutdown()
        scope.cancel()
    }
}
