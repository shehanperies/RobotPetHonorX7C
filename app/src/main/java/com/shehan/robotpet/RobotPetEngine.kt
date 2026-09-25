package com.shehan.robotpet

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import com.shehan.robotpet.ai.GeminiBrainManager
import com.shehan.robotpet.ai.GeminiContext
import com.shehan.robotpet.ai.GeminiStatus
import com.shehan.robotpet.ai.SecureSecretStore
import com.shehan.robotpet.brain.BehaviorExecutive
import com.shehan.robotpet.brain.BehaviorResource
import com.shehan.robotpet.brain.BrainDecision
import com.shehan.robotpet.brain.DecisionSource
import com.shehan.robotpet.brain.Emotion
import com.shehan.robotpet.brain.HandGesture
import com.shehan.robotpet.brain.MotionCommand
import com.shehan.robotpet.brain.OneShotEventGate
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
import com.shehan.robotpet.voice.WakeWordManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.sqrt

data class PetUiState(
    val emotion: Emotion = Emotion.IDLE,
    val mode: PetMode = PetMode.IDLE,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val status: String = "Starting Welly V8",
    val robotConnected: Boolean = false,
    val safeToMove: Boolean = false,
    val obstacleCm: Float? = null,
    val lastGesture: HandGesture = HandGesture.NONE,
    val gestureCandidate: HandGesture = HandGesture.NONE,
    val gestureStage: String = "NONE",
    val rawHandLabel: String = "",
    val gestureConfidence: Float = 0f,
    val objectLabel: String = "",
    val objectConfidence: Float = 0f,
    val objectSummary: String = "",
    val smileProbability: Float = -1f,
    val kissConfidence: Float = 0f,
    val kissDetected: Boolean = false,
    val blownKissDetected: Boolean = false,
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
    val activeBehavior: String = "idle",
    val executiveLocks: String = "none",
    val decisionCommand: MotionCommand = MotionCommand.STOP,
    val espRequestedCommand: MotionCommand = MotionCommand.STOP,
    val espActualCommand: MotionCommand? = null,
    val espDurationMs: Long = 0L,
    val espQueued: Boolean = false,
    val espSafetyBlocked: Boolean = false,
    val espState: String = "IDLE",
    val espTxSeq: Long = 0L,
    val espAckSeq: Long = 0L,
    val espAckAccepted: Boolean? = null,
    val espAckReason: String = "",
    val eventLog: List<String> = emptyList()
)

private data class BehaviorRequest(
    val decision: BrainDecision,
    val source: DecisionSource,
    val createdMono: Long,
    val ttlMs: Long
)

/**
 * V8 runtime: detectors only create observations/events; one serialized queue may start
 * foreground behaviors. Passive eye tracking never owns the robot.
 */
class RobotPetEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val brain = PetBrain()
    private val executive = BehaviorExecutive()
    private val link = RobotLink()
    private val prefs = RobotPrefs(context)
    private val secrets = SecureSecretStore(context)
    private val behaviorQueue = Channel<BehaviorRequest>(capacity = 64)

    private var realTelemetry = RobotTelemetry()
    private var vision: VisionManager? = null
    private var latestVision = VisionObservation()
    private var wakeWord: WakeWordManager? = null

    private var lastCommand = MotionCommand.STOP
    private var lastCommandMono = 0L
    private var lastDriveTxMono = 0L
    private var lastForkTxMono = 0L
    private var sequenceJob: Job? = null
    private var sequenceKey = ""
    private var motionStopJob: Job? = null
    private var motionKey = ""
    private var selfMotionMaskUntil = 0L

    @Volatile private var remoteManual = false
    private var remoteLastCommandMono = 0L
    private var remoteLastMotion = MotionCommand.STOP
    private var lastAutonomousAiWall = System.currentTimeMillis()
    private var waitingForUserAi = false
    private val touchTimes = ArrayDeque<Long>()

    private val smileGate = OneShotEventGate(420L, 850L, 3000L)
    private val winkGate = OneShotEventGate(360L, 750L, 3000L)
    private var pendingSmile: BrainDecision? = null
    private var pendingWink: BrainDecision? = null
    private var pendingCloseFace: BrainDecision? = null

    private var stableObjectLabel = ""
    private var stableObjectX = 0.5f
    private var stableObjectY = 0.5f
    private var stableObjectSinceMono = 0L
    private val objectHabituation = mutableMapOf<String, Long>()
    private var pendingObjectDecision: BrainDecision? = null
    private var pendingObjectLabel = ""
    private var pendingObjectMono = 0L

    private var lastIdleActionMono = 0L
    private var lastPassiveFaceVisible = false

    private val _ui = MutableStateFlow(PetUiState())
    val ui: StateFlow<PetUiState> = _ui

    private var remoteServer: RemoteControlServer = createRemoteServer(prefs.remotePort)

    private val gemini = GeminiBrainManager(
        prefs = prefs,
        secrets = secrets,
        onStatus = { status -> _ui.value = _ui.value.copy(gemini = status) },
        onDirective = { directive ->
            scope.launch {
                if (remoteManual || _ui.value.voice.listening) return@launch
                val src = if (waitingForUserAi) DecisionSource.DIRECT_COMMAND else DecisionSource.AI
                waitingForUserAi = false
                enqueue(brain.onAiDirective(directive, effectiveTelemetry()), src)
            }
        },
        onPlanFailure = { reason ->
            scope.launch {
                addLog("GEMINI FAIL: $reason")
                if (waitingForUserAi) {
                    waitingForUserAi = false
                    val heard = _ui.value.lastHeard
                    if (heard.isNotBlank()) enqueue(brain.onSpeech(heard, effectiveTelemetry()), DecisionSource.DIRECT_COMMAND)
                    else voice.finishProcessing()
                }
            }
        }
    )

    private val phoneSensors = PhoneSensorManager(context) { observation -> scope.launch { onPhone(observation) } }

    private val voice = VoiceManager(
        context = context,
        onText = { text -> scope.launch { onVoiceText(text) } },
        onFailure = { reason ->
            scope.launch {
                executive.setListening(false, SystemClock.elapsedRealtime())
                executive.setSpeaking(false, SystemClock.elapsedRealtime())
                _ui.value = _ui.value.copy(mode = PetMode.ENGAGED, status = "Voice • $reason")
                addLog("VOICE ERROR: $reason")
                if (!remoteManual) wakeWord?.resume(1600L)
            }
        },
        onDebug = { debug -> scope.launch { onVoiceDebug(debug) } }
    )

    init {
        if (prefs.mindVersion < 8) prefs.resetMindForV8()
        brain.setFollowEnabled(prefs.followEnabled)
        brain.restoreMind(prefs.mood, prefs.annoyance, prefs.socialNeed, prefs.boredom, prefs.curiosity, prefs.energy, prefs.affection, prefs.confidence)
        voice.applySettings(prefs.voiceLanguage, prefs.voiceName, prefs.voicePreset)
        phoneSensors.start()

        wakeWord = WakeWordManager(
            context = context,
            languageProvider = { prefs.voiceLanguage },
            onWake = { scope.launch { onWakeWordDetected() } },
            onState = { state -> if (_ui.value.testing) addLog(state) }
        ).also { if (prefs.wakeWordEnabled) it.start() }

        if (prefs.remoteEnabled) runCatching { remoteServer.startRemote() }.onFailure { addLog("REMOTE START FAIL: ${it.message}") }

        // The only consumer allowed to start normal foreground behaviors.
        scope.launch {
            for (request in behaviorQueue) {
                val age = SystemClock.elapsedRealtime() - request.createdMono
                if (age <= request.ttlMs || request.source in setOf(DecisionSource.SAFETY, DecisionSource.REMOTE)) {
                    runDecision(request.decision, request.source)
                } else if (_ui.value.testing) addLog("DROP stale ${request.decision.behaviorKey} ${age}ms")
            }
        }

        scope.launch {
            link.telemetry.collectLatest { t ->
                val becameUnsafe = realTelemetry.safeToMove && !t.safeToMove
                realTelemetry = t
                if (!_ui.value.simEsp) {
                    _ui.value = _ui.value.copy(robotConnected = t.connected, safeToMove = t.safeToMove, obstacleCm = t.centerCm ?: t.obstacleCm)
                }
                if (becameUnsafe) enqueue(
                    BrainDecision(Emotion.STARTLED, PetMode.EMERGENCY, motion = MotionCommand.STOP, interruptMotion = true, status = "SAFETY STOP", behaviorKey = "safety-stop", minimumHoldMs = 1200L),
                    DecisionSource.SAFETY
                )
            }
        }

        scope.launch {
            link.debug.collectLatest { d ->
                if (!_ui.value.simEsp) {
                    _ui.value = _ui.value.copy(
                        espRequestedCommand = d.requested, espActualCommand = d.actual, espDurationMs = d.durationMs,
                        espQueued = d.queuedToWebSocket, espSafetyBlocked = d.blockedBySafety,
                        espState = when {
                            d.blockedBySafety -> "SAFETY BLOCK → STOP"
                            d.ackAccepted == false -> "ESP REJECTED • ${d.ackReason}"
                            d.ackSeq == d.txSeq && d.ackSeq != 0L -> "ESP ACK"
                            d.queuedToWebSocket -> "WS QUEUED • awaiting ACK"
                            realTelemetry.connected -> "SEND FAILED"
                            else -> "NO ESP LINK"
                        },
                        espTxSeq = d.txSeq, espAckSeq = d.ackSeq, espAckAccepted = d.ackAccepted, espAckReason = d.ackReason
                    )
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(2500L)
                if (!_ui.value.voice.listening && _ui.value.voice.phase != "SPEAKING" && !remoteManual) {
                    val d = brain.idleTick(effectiveTelemetry())
                    val now = SystemClock.elapsedRealtime()
                    if (isActionful(d) && now - lastIdleActionMono >= 45_000L) {
                        lastIdleActionMono = now
                        enqueue(d, DecisionSource.IDLE)
                    } else if (!executive.hasActivePrimary(now)) applyAmbient(d)
                }
                _ui.value = _ui.value.copy(mind = brain.mindSnapshot(), activeBehavior = executive.activePrimaryKey(SystemClock.elapsedRealtime()), executiveLocks = executive.activeLocks(SystemClock.elapsedRealtime()))
            }
        }

        scope.launch {
            while (isActive) { updateBattery(); persistMind(); delay(15_000L) }
        }

        scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                val wall = System.currentTimeMillis()
                val mono = SystemClock.elapsedRealtime()
                if (_ui.value.gemini.enabled && _ui.value.gemini.keyConfigured && !_ui.value.gemini.busy && !_ui.value.voice.listening && !remoteManual &&
                    !executive.hasActivePrimary(mono) && wall - lastAutonomousAiWall > 10 * 60_000L && brain.shouldRequestAi(wall)) {
                    lastAutonomousAiWall = wall
                    waitingForUserAi = false
                    gemini.plan(geminiContext(), reason = "rare long-idle high-level planning")
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(200L)
                if (remoteManual && remoteLastMotion != MotionCommand.STOP && SystemClock.elapsedRealtime() - remoteLastCommandMono > 900L) {
                    sendCommand(MotionCommand.STOP, 0L, true)
                    remoteLastMotion = MotionCommand.STOP
                    addLog("REMOTE WATCHDOG: STOP")
                }
            }
        }
    }

    fun onMicrophonePermissionChanged(granted: Boolean) { wakeWord?.onPermissionChanged(granted) }

    private suspend fun onWakeWordDetected() {
        if (remoteManual || _ui.value.voice.listening || _ui.value.voice.phase == "SPEAKING") return
        cancelMotionAndStop("WAKE WORD WELLY")
        _ui.value = _ui.value.copy(emotion = Emotion.LISTENING, mode = PetMode.LISTENING, status = "Welly heard you • listening…")
        delay(250L)
        if (!remoteManual && !_ui.value.voice.listening) voice.listen(prefs.voiceLanguage)
    }

    fun startVision(owner: LifecycleOwner) {
        if (vision != null) return
        vision = VisionManager(context, onObservation = { o -> scope.launch { onVision(o) } }, onFrame = { bitmap -> remoteServer.updateFrame(bitmap) })
        vision?.start(owner)
    }

    fun connectRobot() { if (!_ui.value.simEsp) link.connect(prefs.robotUrl) }
    fun disconnectRobot() { cancelMotionAndStop("DISCONNECT"); link.disconnect() }

    fun listen() {
        if (_ui.value.voice.listening) return
        wakeWord?.pause(); cancelMotionAndStop("MANUAL TALK"); voice.listen(prefs.voiceLanguage)
    }
    fun testVoice() = voice.testVoice()
    fun previewVoice(language: String, voiceName: String, preset: String) = voice.previewVoice(language, voiceName, preset)

    fun touch() {
        if (_ui.value.voice.listening) return
        val now = SystemClock.elapsedRealtime()
        while (touchTimes.isNotEmpty() && now - touchTimes.first() > 900L) touchTimes.removeFirst()
        touchTimes.addLast(now)
        if (touchTimes.size >= 3) { touchTimes.clear(); enqueue(brain.onTickle(), DecisionSource.GESTURE) }
        else enqueue(brain.onTouch(), DecisionSource.TOUCH)
    }

    fun pet() { if (!_ui.value.voice.listening) enqueue(brain.onPetting(), DecisionSource.TOUCH) }
    fun setTesting(enabled: Boolean) { _ui.value = _ui.value.copy(testing = enabled) }

    fun setSimEsp(enabled: Boolean) {
        cancelMotionAndStop("SIM CHANGE")
        if (enabled) link.disconnect()
        _ui.value = _ui.value.copy(
            simEsp = enabled, robotConnected = if (enabled) true else realTelemetry.connected,
            safeToMove = if (enabled) true else realTelemetry.safeToMove,
            obstacleCm = if (enabled) 20f else (realTelemetry.centerCm ?: realTelemetry.obstacleCm),
            espState = if (enabled) "SIM READY" else "REAL ESP"
        )
        addLog(if (enabled) "SIM ESP ON" else "SIM ESP OFF")
    }

    fun updateSettings(
        url: String, followAllowed: Boolean, voiceLanguage: String, voiceName: String, voicePreset: String,
        robotName: String, ownerName: String, characterInstructions: String, characterNeverDo: String,
        geminiEnabled: Boolean, geminiModel: String, apiKeyInput: String, remoteEnabled: Boolean, remotePort: Int
    ) {
        prefs.robotUrl = url; prefs.followEnabled = followAllowed; prefs.voiceLanguage = voiceLanguage
        prefs.voiceName = voiceName; prefs.voicePreset = voicePreset; prefs.robotName = robotName.ifBlank { "Welly" }
        prefs.ownerName = ownerName; prefs.characterInstructions = characterInstructions; prefs.characterNeverDo = characterNeverDo
        brain.setFollowEnabled(followAllowed); voice.applySettings(voiceLanguage, voiceName, voicePreset)
        if (apiKeyInput.isNotBlank()) gemini.saveKey(apiKeyInput)
        gemini.selectModel(geminiModel); gemini.setEnabled(geminiEnabled)
        val oldPort = prefs.remotePort
        prefs.remotePort = remotePort; prefs.remoteEnabled = remoteEnabled
        if (oldPort != prefs.remotePort) { remoteServer.shutdown(); remoteServer = createRemoteServer(prefs.remotePort) }
        if (remoteEnabled) runCatching { remoteServer.startRemote() }.onFailure { addLog("REMOTE START FAIL: ${it.message}") } else remoteServer.stopRemote()
        if (!_ui.value.simEsp) { link.disconnect(); if (url.isNotBlank()) link.connect(url) }
        if (!remoteManual && !_ui.value.voice.listening && _ui.value.voice.phase != "SPEAKING") wakeWord?.resume(1200L)
    }

    fun testGemini(keyInput: String) { if (keyInput.isNotBlank()) gemini.saveKey(keyInput); gemini.testConnection() }
    fun refreshGeminiModels() = gemini.refreshModels()
    fun clearGeminiKey() = gemini.clearKey()

    fun currentRobotUrl() = prefs.robotUrl
    fun currentFollowEnabled() = prefs.followEnabled
    fun currentVoiceLanguage() = prefs.voiceLanguage
    fun currentVoiceName() = prefs.voiceName
    fun currentVoicePreset() = prefs.voicePreset
    fun currentRobotName() = prefs.robotName
    fun currentOwnerName() = prefs.ownerName
    fun currentCharacterInstructions() = prefs.characterInstructions
    fun currentCharacterNeverDo() = prefs.characterNeverDo
    fun currentGeminiEnabled() = prefs.geminiEnabled
    fun currentGeminiModel() = prefs.geminiModel
    fun currentRemoteEnabled() = prefs.remoteEnabled
    fun currentRemotePort() = prefs.remotePort

    private fun onVision(v: VisionObservation) {
        latestVision = v
        val mono = SystemClock.elapsedRealtime()
        val summary = v.objects.take(4).joinToString(" • ") { "${it.label} ${(it.confidence * 100).toInt()}%" }
        val passiveGaze = if (v.faceVisible) ((v.faceCenterX - 0.5f) * 2f).coerceIn(-1f, 1f) else _ui.value.gazeX
        _ui.value = _ui.value.copy(
            gazeX = if (!_ui.value.voice.listening) passiveGaze else _ui.value.gazeX,
            lastGesture = v.handGesture, gestureCandidate = v.handCandidate, gestureStage = v.gestureStage,
            rawHandLabel = v.rawHandLabel, gestureConfidence = v.handConfidence,
            objectLabel = v.objectLabel.orEmpty(), objectConfidence = v.objectConfidence, objectSummary = summary,
            smileProbability = v.smileProbability, kissConfidence = v.kissConfidence,
            kissDetected = v.kissDetected, blownKissDetected = v.blownKissDetected, mind = brain.mindSnapshot()
        )

        updateObjectStability(v, mono)
        val smileFired = smileGate.update(v.faceVisible && !v.handPresent && v.smileProbability >= 0.88f, mono)
        val winkActive = v.faceVisible && !v.handPresent && (
            (v.leftEyeOpenProbability in 0f..0.18f && v.rightEyeOpenProbability > 0.76f) ||
            (v.rightEyeOpenProbability in 0f..0.18f && v.leftEyeOpenProbability > 0.76f)
        )
        val winkFired = winkGate.update(winkActive, mono)

        if (_ui.value.voice.listening || _ui.value.voice.phase in setOf("PROCESSING", "SPEAKING") || remoteManual) return

        val d = brain.onVision(v, effectiveTelemetry())

        when (d.behaviorKey) {
            "smile" -> pendingSmile = d
            "wink" -> pendingWink = d
            "close-face" -> pendingCloseFace = d
        }
        if (d.behaviorKey.startsWith("object-")) {
            pendingObjectDecision = d
            pendingObjectLabel = v.objects.firstOrNull { !it.label.equals("person", true) }?.label?.lowercase().orEmpty()
            pendingObjectMono = mono
        }

        if (smileFired) pendingSmile?.let { enqueue(it, DecisionSource.VISION); pendingSmile = null }
        if (winkFired) pendingWink?.let { enqueue(it, DecisionSource.VISION); pendingWink = null }

        val stableObjectEvent = consumeStableObjectEvent(v, mono)
        if (stableObjectEvent) {
            pendingObjectDecision?.takeIf { mono - pendingObjectMono < 3500L }?.let {
                enqueue(it, DecisionSource.VISION)
                pendingObjectDecision = null
            }
        }

        val immediate = v.handGesture != HandGesture.NONE || v.kissDetected || v.blownKissDetected
        val source = when {
            immediate -> DecisionSource.GESTURE
            d.mode == PetMode.FOLLOWING -> DecisionSource.FOLLOW
            d.mode == PetMode.SEARCHING -> DecisionSource.SEARCH
            else -> DecisionSource.VISION
        }

        // Events already handled by release-gated pipelines above.
        if (d.behaviorKey in setOf("smile", "wink") || d.behaviorKey.startsWith("object-")) return

        // Static close-face is not an event. PetBrain may propose it from absolute size, but V8
        // only executes the cached reaction when VisionManager confirms an actual approach transition.
        if (v.closeApproachDetected) {
            pendingCloseFace?.let { enqueue(it, DecisionSource.VISION); pendingCloseFace = null }
            if (d.behaviorKey == "close-face") return
        } else if (d.behaviorKey == "close-face") return

        // Normal idle mode never physically hunts for a missing face. Search is only allowed during
        // an explicitly active follow session.
        if (source == DecisionSource.SEARCH && !brain.mindSnapshot().followActive) {
            if (!executive.hasActivePrimary(mono)) applyAmbient(d.copy(mode = PetMode.IDLE, motion = MotionCommand.STOP, sequence = emptyList(), speech = null, interruptMotion = false, status = "Calm"))
            return
        }

        val meaningful = immediate || source == DecisionSource.FOLLOW || source == DecisionSource.SEARCH ||
            d.interruptMotion || d.motion != MotionCommand.STOP || d.sequence.isNotEmpty() || !d.speech.isNullOrBlank()
        if (meaningful) enqueue(d, source)
        else if (!executive.hasActivePrimary(mono)) applyAmbient(d)

        lastPassiveFaceVisible = v.faceVisible
    }

    private fun updateObjectStability(v: VisionObservation, mono: Long) {
        val target = v.objects.firstOrNull { !it.label.equals("person", true) && it.confidence >= 0.50f }
        if (target == null) {
            stableObjectLabel = ""; stableObjectSinceMono = 0L
            return
        }
        val label = target.label.lowercase()
        val dist = distance(stableObjectX, stableObjectY, target.centerX, target.centerY)
        if (label != stableObjectLabel || dist > 0.16f) {
            stableObjectLabel = label; stableObjectX = target.centerX; stableObjectY = target.centerY; stableObjectSinceMono = mono
        } else {
            stableObjectX = stableObjectX * 0.75f + target.centerX * 0.25f
            stableObjectY = stableObjectY * 0.75f + target.centerY * 0.25f
        }
    }

    private fun consumeStableObjectEvent(v: VisionObservation, mono: Long): Boolean {
        if (stableObjectLabel.isBlank() || mono - stableObjectSinceMono < 1300L) return false
        val target = v.objects.firstOrNull { it.label.equals(stableObjectLabel, true) && !it.label.equals("person", true) } ?: return false
        val cellX = (target.centerX * 3f).toInt().coerceIn(0, 2)
        val cellY = (target.centerY * 3f).toInt().coerceIn(0, 2)
        if (pendingObjectDecision == null || pendingObjectLabel != stableObjectLabel) return false
        val key = "$stableObjectLabel@$cellX,$cellY"
        val last = objectHabituation[key] ?: Long.MIN_VALUE / 4
        if (mono - last < 120_000L) return false
        objectHabituation[key] = mono
        return true
    }

    private fun onPhone(v: PhoneObservation) {
        _ui.value = _ui.value.copy(phoneEvent = v.event)
        val mono = SystemClock.elapsedRealtime()
        if (mono < selfMotionMaskUntil) {
            if (_ui.value.testing) addLog("PHONE suppressed self-motion ${v.event}")
            return
        }
        if (!_ui.value.voice.listening && _ui.value.voice.phase != "SPEAKING" && !remoteManual) brain.onPhone(v)?.let { enqueue(it, DecisionSource.PHONE) }
    }

    private suspend fun onVoiceText(text: String) {
        _ui.value = _ui.value.copy(lastHeard = text)
        addLog("VOICE: $text")
        val q = text.trim().lowercase()
        when {
            q.contains("your name") || q.contains("who are you") -> enqueue(
                BrainDecision(Emotion.HAPPY, PetMode.CONVERSATION, speech = "I'm Welly.", status = "Welly", behaviorKey = "identity-welly", minimumHoldMs = 1800L),
                DecisionSource.DIRECT_COMMAND
            )
            brain.isDirectVoiceCommand(text) -> enqueue(brain.onSpeech(text, effectiveTelemetry()), DecisionSource.DIRECT_COMMAND)
            _ui.value.gemini.enabled && _ui.value.gemini.keyConfigured -> {
                waitingForUserAi = true
                _ui.value = _ui.value.copy(mode = PetMode.THINKING, emotion = Emotion.THINKING, status = "Welly is thinking…")
                gemini.plan(geminiContext(), reason = "user spoke to Welly", userText = text, bypassRateLimit = true)
            }
            else -> enqueue(brain.onSpeech(text, effectiveTelemetry()), DecisionSource.DIRECT_COMMAND)
        }
    }

    private fun onVoiceDebug(debug: VoiceDebug) {
        val mono = SystemClock.elapsedRealtime()
        val wasListening = _ui.value.voice.listening
        if (debug.listening && !wasListening) {
            executive.setListening(true, mono); executive.setSpeaking(false, mono); wakeWord?.pause(); cancelMotionAndStop("MIC LOCK")
        } else if (!debug.listening && wasListening) executive.setListening(false, mono)
        executive.setSpeaking(debug.phase == "SPEAKING", mono)

        val busy = debug.listening || debug.phase in setOf("STARTING_MIC", "LISTENING", "HEARING", "PROCESSING", "SPEAKING", "RETRYING", "FALLBACK")
        if (busy) wakeWord?.pause() else if (!remoteManual) wakeWord?.resume(1600L)

        _ui.value = _ui.value.copy(
            voice = debug,
            emotion = when { debug.listening -> Emotion.LISTENING; debug.phase == "SPEAKING" -> Emotion.SPEAKING; else -> _ui.value.emotion },
            mode = if (debug.listening) PetMode.LISTENING else _ui.value.mode,
            status = when (debug.phase) {
                "STARTING_MIC" -> "Welly • starting microphone…"
                "LISTENING" -> "Welly • listening"
                "HEARING" -> "Welly • I can hear you"
                "PROCESSING" -> "Welly • processing…"
                "SPEAKING" -> "Welly • speaking…"
                else -> if (debug.listening) "Welly • listening…" else _ui.value.status
            },
            activeBehavior = executive.activePrimaryKey(mono), executiveLocks = executive.activeLocks(mono)
        )
    }

    private fun enqueue(d: BrainDecision, source: DecisionSource) {
        val ttl = when (source) {
            DecisionSource.SAFETY, DecisionSource.REMOTE -> 5000L
            DecisionSource.DIRECT_COMMAND, DecisionSource.LISTENING -> 3000L
            DecisionSource.GESTURE, DecisionSource.TOUCH -> 1600L
            DecisionSource.FOLLOW, DecisionSource.SEARCH -> 900L
            else -> 700L
        }
        if (!behaviorQueue.trySend(BehaviorRequest(d, source, SystemClock.elapsedRealtime(), ttl)).isSuccess && _ui.value.testing) addLog("DROP queue full ${d.behaviorKey}")
    }

    private fun runDecision(d: BrainDecision, source: DecisionSource) {
        val mono = SystemClock.elapsedRealtime()
        val result = executive.submit(d, source, mono)
        val allowed = result.allowed
        val primaryAccepted = BehaviorResource.PRIMARY in allowed
        val faceAccepted = BehaviorResource.FACE in allowed
        val physicalAccepted = BehaviorResource.DRIVE in allowed || BehaviorResource.FORK in allowed

        if (result.preempted.any { it in setOf(BehaviorResource.PRIMARY, BehaviorResource.DRIVE, BehaviorResource.FORK) }) {
            cancelJobsOnly(); sendCommand(MotionCommand.STOP, 0L, true)
        }

        _ui.value = _ui.value.copy(
            emotion = if (primaryAccepted) result.decision.emotion else _ui.value.emotion,
            mode = if (primaryAccepted) result.decision.mode else _ui.value.mode,
            gazeX = if (faceAccepted) result.decision.gazeX else _ui.value.gazeX,
            gazeY = if (faceAccepted) result.decision.gazeY else _ui.value.gazeY,
            status = if (primaryAccepted) result.decision.status else _ui.value.status,
            activeBehavior = executive.activePrimaryKey(mono), executiveLocks = executive.activeLocks(mono),
            decisionCommand = if (physicalAccepted || result.decision.interruptMotion) result.decision.motion else _ui.value.decisionCommand,
            mind = brain.mindSnapshot()
        )

        if (result.decision.interruptMotion && physicalAccepted) {
            cancelMotionAndStop("${source.name}:INTERRUPT")
            if (source != DecisionSource.SAFETY && result.decision.sequence.isNotEmpty()) executeSequence(result.decision.sequence, result.decision.behaviorKey)
        } else if (result.decision.sequence.isNotEmpty()) executeSequence(result.decision.sequence, result.decision.behaviorKey)
        else if (result.decision.motion != MotionCommand.STOP) executeMotion(result.decision.motion, result.decision.motionDurationMs, result.decision.behaviorKey)

        var spoke = false
        if (!result.decision.speech.isNullOrBlank() && BehaviorResource.SPEECH in allowed && !_ui.value.voice.listening) {
            wakeWord?.pause()
            spoke = voice.speak(result.decision.speech)
        }
        if (source == DecisionSource.DIRECT_COMMAND && !spoke) voice.finishProcessing()

        if (result.denied.isNotEmpty() && _ui.value.testing) addLog("EXEC denied ${result.denied.joinToString { it.name }} • ${d.behaviorKey}")
    }

    private fun isActionful(d: BrainDecision): Boolean = d.interruptMotion || d.motion != MotionCommand.STOP || d.sequence.isNotEmpty() || !d.speech.isNullOrBlank() || d.mode in setOf(PetMode.FOLLOWING, PetMode.SEARCHING, PetMode.CONVERSATION, PetMode.OBJECT_PLAY, PetMode.EMERGENCY)

    private fun applyAmbient(d: BrainDecision) {
        if (_ui.value.voice.listening || _ui.value.voice.phase == "SPEAKING") return
        _ui.value = _ui.value.copy(emotion = d.emotion, gazeX = d.gazeX, gazeY = d.gazeY, mind = brain.mindSnapshot())
    }

    private fun executeMotion(command: MotionCommand, durationMs: Long, behaviorKey: String) {
        if (motionStopJob?.isActive == true && motionKey == behaviorKey) return
        motionStopJob?.cancel(); motionKey = behaviorKey
        sendCommand(command, durationMs)
        markSelfMotion(durationMs)
        if (durationMs > 0L) {
            motionStopJob = scope.launch {
                delay(durationMs); sendCommand(MotionCommand.STOP, 0L, true); motionKey = ""
            }
        }
    }

    private fun executeSequence(steps: List<com.shehan.robotpet.brain.MotionStep>, behaviorKey: String) {
        if (sequenceJob?.isActive == true) return
        motionStopJob?.cancel(); sequenceKey = behaviorKey
        sequenceJob = scope.launch {
            try {
                for (step in steps) {
                    if (!isActive || remoteManual || _ui.value.voice.listening) break
                    sendCommand(step.command, step.durationMs, true); markSelfMotion(step.durationMs)
                    delay(step.durationMs); sendCommand(MotionCommand.STOP, 0L, true); delay(step.delayAfterMs)
                }
            } finally { sequenceKey = "" }
        }
    }

    private fun markSelfMotion(durationMs: Long) {
        selfMotionMaskUntil = maxOf(selfMotionMaskUntil, SystemClock.elapsedRealtime() + durationMs + 800L)
    }

    private fun cancelJobsOnly() {
        sequenceJob?.cancel(); sequenceJob = null; sequenceKey = ""
        motionStopJob?.cancel(); motionStopJob = null; motionKey = ""
    }

    private fun cancelMotionAndStop(reason: String) {
        cancelJobsOnly(); sendCommand(MotionCommand.STOP, 0L, true)
        if (_ui.value.testing) addLog("STOP • $reason")
    }

    private fun sendCommand(command: MotionCommand, durationMs: Long, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val drive = command in setOf(MotionCommand.FORWARD, MotionCommand.BACKWARD, MotionCommand.LEFT, MotionCommand.RIGHT)
        val fork = command in setOf(MotionCommand.FORK_UP, MotionCommand.FORK_DOWN)
        if (!force) {
            if (command == lastCommand && now - lastCommandMono < 480L) return
            if (drive && now - lastDriveTxMono < 620L) return
            if (fork && now - lastForkTxMono < 420L) return
        }
        lastCommand = command; lastCommandMono = now
        if (drive) lastDriveTxMono = now
        if (fork) lastForkTxMono = now
        if (_ui.value.simEsp) {
            val next = _ui.value.espTxSeq + 1L
            _ui.value = _ui.value.copy(espRequestedCommand = command, espActualCommand = command, espDurationMs = durationMs, espQueued = true, espSafetyBlocked = false, espState = "SIM EXECUTED", espTxSeq = next, espAckSeq = next, espAckAccepted = true)
            if (_ui.value.testing) addLog("SIM ${command.name} ${durationMs}ms")
        } else link.send(command, durationMs)
    }

    private fun updateBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        _ui.value = _ui.value.copy(phoneBattery = percent, phoneCharging = charging)
        if (percent >= 0 && !_ui.value.voice.listening && !remoteManual) brain.onBattery(percent, charging)?.let { enqueue(it, DecisionSource.BATTERY) }
    }

    private fun persistMind() {
        val m = brain.mindSnapshot()
        prefs.mood = m.mood; prefs.annoyance = m.annoyance; prefs.socialNeed = m.socialNeed; prefs.boredom = m.boredom
        prefs.curiosity = m.curiosity; prefs.energy = m.energy; prefs.affection = m.affection; prefs.confidence = m.confidence
    }

    private fun effectiveTelemetry(): RobotTelemetry = if (_ui.value.simEsp) RobotTelemetry(true, true, 20f, 50f, 20f, 50f, 100, System.currentTimeMillis()) else realTelemetry

    private fun geminiContext(): GeminiContext {
        val u = _ui.value
        return GeminiContext(brain.mindSnapshot(), latestVision.faceVisible, latestVision.objectLabel, latestVision.objectConfidence, u.objectSummary, u.phoneBattery, u.phoneCharging, effectiveTelemetry().connected, effectiveTelemetry().safeToMove, u.status)
    }

    private fun createRemoteServer(port: Int): RemoteControlServer = RemoteControlServer(
        port = port,
        statusProvider = {
            val s = _ui.value
            JSONObject().put("emotion", s.emotion.name).put("mode", s.mode.name).put("status", s.status)
                .put("behavior", s.activeBehavior).put("robotConnected", s.robotConnected).put("safeToMove", s.safeToMove)
                .put("obstacleCm", s.obstacleCm).put("battery", s.phoneBattery).put("object", s.objectLabel).put("objects", s.objectSummary).put("gesture", s.lastGesture.name)
        },
        onCommand = { command, duration ->
            scope.launch {
                if (!remoteManual) return@launch
                remoteLastCommandMono = SystemClock.elapsedRealtime(); remoteLastMotion = command
                cancelJobsOnly(); sendCommand(command, duration, true); markSelfMotion(duration)
                _ui.value = _ui.value.copy(mode = PetMode.REMOTE, status = "Remote • ${command.name}")
            }
        },
        onManualModeChanged = { manual ->
            scope.launch {
                remoteManual = manual; executive.setRemoteManual(manual, SystemClock.elapsedRealtime()); cancelMotionAndStop("REMOTE MODE CHANGE")
                remoteLastMotion = MotionCommand.STOP
                if (manual) wakeWord?.pause() else wakeWord?.resume(1400L)
                _ui.value = _ui.value.copy(mode = if (manual) PetMode.REMOTE else PetMode.IDLE, status = if (manual) "Remote manual control" else "Welly autonomous", activeBehavior = executive.activePrimaryKey(SystemClock.elapsedRealtime()), executiveLocks = executive.activeLocks(SystemClock.elapsedRealtime()))
            }
        },
        onInfoChanged = { info -> _ui.value = _ui.value.copy(remote = info) }
    )

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2; return sqrt(dx * dx + dy * dy)
    }

    private fun addLog(line: String) {
        val stamp = (System.currentTimeMillis() / 1000L) % 100000
        _ui.value = _ui.value.copy(eventLog = (listOf("$stamp • $line") + _ui.value.eventLog).take(12))
    }

    fun shutdown() {
        persistMind(); cancelJobsOnly(); behaviorQueue.close(); wakeWord?.shutdown(); wakeWord = null
        phoneSensors.stop(); vision?.shutdown(); voice.shutdown(); remoteServer.shutdown(); gemini.shutdown(); link.shutdown(); executive.reset(); scope.cancel()
    }
}
