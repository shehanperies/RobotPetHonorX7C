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
import com.shehan.robotpet.brain.BehaviorExecutive
import com.shehan.robotpet.brain.BehaviorResource
import com.shehan.robotpet.brain.BrainDecision
import com.shehan.robotpet.brain.DecisionSource
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
import com.shehan.robotpet.voice.WakeWordManager
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
    val status: String = "Starting Welly V7",
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

class RobotPetEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val brain = PetBrain()
    private val executive = BehaviorExecutive()
    private val link = RobotLink()
    private val prefs = RobotPrefs(context)
    private val secrets = SecureSecretStore(context)

    private var realTelemetry = RobotTelemetry()
    private var vision: VisionManager? = null
    private var latestVision = VisionObservation()
    private var wakeWord: WakeWordManager? = null

    private var lastCommand = MotionCommand.STOP
    private var lastCommandMs = 0L
    private var lastDriveTxMs = 0L
    private var lastForkTxMs = 0L
    private var sequenceJob: Job? = null
    private var sequenceKey = ""
    private var motionStopJob: Job? = null
    private var motionKey = ""

    @Volatile private var remoteManual = false
    private var remoteLastCommandMs = 0L
    private var remoteLastMotion = MotionCommand.STOP
    private var lastAutonomousAiMs = System.currentTimeMillis()
    private val touchTimes = ArrayDeque<Long>()

    // V7 event fusion. Vision still updates the world continuously, but behavior is sampled
    // and only meaningful/stable events enter the one-at-a-time executive.
    private var lastBrainVisionSampleMs = 0L
    private var previousFaceVisible = false
    private var stableObjectKey = ""
    private var stableObjectSinceMs = 0L
    private var lastObjectEventKey = ""
    private var lastObjectEventMs = 0L
    private var lastVisionSubmitKey = ""
    private var lastVisionSubmitMs = 0L

    private val _ui = MutableStateFlow(PetUiState())
    val ui: StateFlow<PetUiState> = _ui

    private var remoteServer: RemoteControlServer = createRemoteServer(prefs.remotePort)

    private val gemini = GeminiBrainManager(
        prefs = prefs,
        secrets = secrets,
        onStatus = { status -> _ui.value = _ui.value.copy(gemini = status) },
        onDirective = { directive ->
            scope.launch {
                if (!remoteManual && !_ui.value.voice.listening) {
                    submit(brain.onAiDirective(directive, effectiveTelemetry()), DecisionSource.AI)
                }
            }
        },
        onPlanFailure = { reason ->
            scope.launch {
                addLog("GEMINI FAIL: $reason")
                if (_ui.value.lastHeard.isNotBlank() && !_ui.value.voice.listening) {
                    submit(brain.onSpeech(_ui.value.lastHeard, effectiveTelemetry()), DecisionSource.DIRECT_COMMAND)
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
            scope.launch {
                _ui.value = _ui.value.copy(lastHeard = text)
                addLog("VOICE: $text")
                val q = text.trim().lowercase()
                when {
                    q.contains("your name") || q.contains("who are you") -> submit(
                        BrainDecision(
                            emotion = Emotion.HAPPY,
                            mode = PetMode.CONVERSATION,
                            speech = "I'm Welly.",
                            status = "Welly",
                            behaviorKey = "identity-welly",
                            minimumHoldMs = 1600L
                        ),
                        DecisionSource.DIRECT_COMMAND
                    )
                    brain.isDirectVoiceCommand(text) ->
                        submit(brain.onSpeech(text, effectiveTelemetry()), DecisionSource.DIRECT_COMMAND)
                    _ui.value.gemini.enabled && _ui.value.gemini.keyConfigured -> {
                        _ui.value = _ui.value.copy(
                            mode = PetMode.THINKING,
                            emotion = Emotion.THINKING,
                            status = "Welly is thinking…"
                        )
                        gemini.plan(
                            context = geminiContext(),
                            reason = "user spoke to Welly",
                            userText = text,
                            bypassRateLimit = true
                        )
                    }
                    else -> submit(brain.onSpeech(text, effectiveTelemetry()), DecisionSource.DIRECT_COMMAND)
                }
            }
        },
        onFailure = { reason ->
            scope.launch {
                executive.setListening(false)
                executive.setSpeaking(false)
                _ui.value = _ui.value.copy(mode = PetMode.ENGAGED, status = "Voice • $reason")
                addLog("VOICE ERROR: $reason")
                wakeWord?.resume(900L)
            }
        },
        onDebug = { debug ->
            scope.launch {
                val wasListening = _ui.value.voice.listening
                if (debug.listening && !wasListening) {
                    executive.setListening(true)
                    executive.setSpeaking(false)
                    wakeWord?.pause()
                    cancelMotionAndStop("MIC LOCK")
                } else if (!debug.listening && wasListening) {
                    executive.setListening(false)
                }

                executive.setSpeaking(debug.phase == "SPEAKING")
                val busyVoice = debug.listening || debug.phase in setOf(
                    "STARTING_MIC", "LISTENING", "HEARING", "PROCESSING", "SPEAKING", "RETRYING", "FALLBACK"
                )
                if (busyVoice) wakeWord?.pause() else if (!remoteManual) wakeWord?.resume(850L)

                _ui.value = _ui.value.copy(
                    voice = debug,
                    emotion = when {
                        debug.listening -> Emotion.LISTENING
                        debug.phase == "SPEAKING" -> Emotion.SPEAKING
                        else -> _ui.value.emotion
                    },
                    mode = if (debug.listening) PetMode.LISTENING else _ui.value.mode,
                    status = when (debug.phase) {
                        "STARTING_MIC" -> "Welly • starting microphone…"
                        "LISTENING" -> "Welly • listening"
                        "HEARING" -> "Welly • I can hear you"
                        "PROCESSING" -> "Welly • processing…"
                        "SPEAKING" -> "Welly • speaking…"
                        else -> if (debug.listening) "Welly • listening…" else _ui.value.status
                    },
                    activeBehavior = executive.activePrimaryKey(),
                    executiveLocks = executive.activeLocks()
                )
            }
        }
    )

    init {
        brain.setFollowEnabled(prefs.followEnabled)
        brain.restoreMind(
            prefs.mood, prefs.annoyance, prefs.socialNeed, prefs.boredom,
            prefs.curiosity, prefs.energy, prefs.affection, prefs.confidence
        )
        voice.applySettings(prefs.voiceLanguage, prefs.voiceName, prefs.voicePreset)
        phoneSensors.start()

        wakeWord = WakeWordManager(
            context = context,
            languageProvider = { prefs.voiceLanguage },
            onWake = { scope.launch { onWakeWordDetected() } },
            onState = { state -> if (_ui.value.testing) addLog(state) }
        ).also { manager -> if (prefs.wakeWordEnabled) manager.start() }

        if (prefs.remoteEnabled) runCatching { remoteServer.startRemote() }
            .onFailure { addLog("REMOTE START FAIL: ${it.message}") }

        scope.launch {
            link.telemetry.collectLatest { t ->
                val becameUnsafe = realTelemetry.safeToMove && !t.safeToMove
                realTelemetry = t
                if (!_ui.value.simEsp) {
                    _ui.value = _ui.value.copy(
                        robotConnected = t.connected,
                        safeToMove = t.safeToMove,
                        obstacleCm = t.centerCm ?: t.obstacleCm
                    )
                }
                if (becameUnsafe) {
                    submit(
                        BrainDecision(
                            Emotion.STARTLED, PetMode.EMERGENCY,
                            motion = MotionCommand.STOP,
                            interruptMotion = true,
                            status = "SAFETY STOP",
                            behaviorKey = "safety-stop",
                            minimumHoldMs = 1000L
                        ),
                        DecisionSource.SAFETY
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
                            d.ackAccepted == false -> "ESP REJECTED"
                            d.ackSeq == d.txSeq && d.ackSeq != 0L -> "ESP ACK"
                            d.queuedToWebSocket -> "WS QUEUED • awaiting ACK"
                            realTelemetry.connected -> "SEND FAILED"
                            else -> "NO ESP LINK"
                        },
                        espTxSeq = d.txSeq,
                        espAckSeq = d.ackSeq,
                        espAckAccepted = d.ackAccepted,
                        espAckReason = d.ackReason
                    )
                }
            }
        }

        // Only this loop starts idle/spontaneous behavior. Camera frames may update the world,
        // but they no longer start random idle actions.
        scope.launch {
            while (isActive) {
                delay(2200L)
                if (!_ui.value.voice.listening && !remoteManual) {
                    val d = brain.idleTick(effectiveTelemetry())
                    if (isActionful(d)) {
                        submit(d, DecisionSource.IDLE)
                    } else if (!executive.hasActivePrimary()) {
                        applyAmbient(d)
                    }
                }
                _ui.value = _ui.value.copy(
                    mind = brain.mindSnapshot(),
                    activeBehavior = executive.activePrimaryKey(),
                    executiveLocks = executive.activeLocks()
                )
            }
        }

        scope.launch {
            while (isActive) {
                updateBattery()
                persistMind()
                delay(15_000L)
            }
        }

        scope.launch {
            while (isActive) {
                delay(5 * 60_000L)
                val now = System.currentTimeMillis()
                if (
                    _ui.value.gemini.enabled && _ui.value.gemini.keyConfigured &&
                    !_ui.value.gemini.busy && !_ui.value.voice.listening && !remoteManual &&
                    !executive.hasActivePrimary(now) &&
                    now - lastAutonomousAiMs > 10 * 60_000L && brain.shouldRequestAi(now)
                ) {
                    lastAutonomousAiMs = now
                    gemini.plan(geminiContext(), reason = "rare long-idle high-level planning")
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(200L)
                if (remoteManual && remoteLastMotion != MotionCommand.STOP && System.currentTimeMillis() - remoteLastCommandMs > 900L) {
                    sendCommand(MotionCommand.STOP, 0L, force = true)
                    remoteLastMotion = MotionCommand.STOP
                    addLog("REMOTE WATCHDOG: STOP")
                }
            }
        }
    }

    private suspend fun onWakeWordDetected() {
        if (remoteManual || _ui.value.voice.listening) return
        cancelMotionAndStop("WAKE WORD WELLY")
        _ui.value = _ui.value.copy(
            emotion = Emotion.LISTENING,
            mode = PetMode.LISTENING,
            status = "Welly heard you • listening…"
        )
        delay(220L)
        if (!remoteManual && !_ui.value.voice.listening) voice.listen(prefs.voiceLanguage)
    }

    fun startVision(owner: LifecycleOwner) {
        if (vision != null) return
        vision = VisionManager(
            context = context,
            onObservation = { observation -> scope.launch { onVision(observation) } },
            onFrame = { bitmap -> remoteServer.updateFrame(bitmap) }
        )
        vision?.start(owner)
    }

    fun connectRobot() { if (!_ui.value.simEsp) link.connect(prefs.robotUrl) }
    fun disconnectRobot() { cancelMotionAndStop("DISCONNECT"); link.disconnect() }

    fun listen() {
        if (_ui.value.voice.listening) return
        wakeWord?.pause()
        voice.listen(prefs.voiceLanguage)
    }

    fun testVoice() = voice.testVoice()
    fun previewVoice(language: String, voiceName: String, preset: String) = voice.previewVoice(language, voiceName, preset)

    fun touch() {
        if (_ui.value.voice.listening) return
        val now = System.currentTimeMillis()
        while (touchTimes.isNotEmpty() && now - touchTimes.first() > 900L) touchTimes.removeFirst()
        touchTimes.addLast(now)
        if (touchTimes.size >= 3) {
            touchTimes.clear()
            submit(brain.onTickle(), DecisionSource.GESTURE)
        } else submit(brain.onTouch(), DecisionSource.TOUCH)
    }

    fun pet() { if (!_ui.value.voice.listening) submit(brain.onPetting(), DecisionSource.TOUCH) }
    fun setTesting(enabled: Boolean) { _ui.value = _ui.value.copy(testing = enabled) }

    fun setSimEsp(enabled: Boolean) {
        cancelMotionAndStop("SIM CHANGE")
        if (enabled) link.disconnect()
        _ui.value = _ui.value.copy(
            simEsp = enabled,
            robotConnected = if (enabled) true else realTelemetry.connected,
            safeToMove = if (enabled) true else realTelemetry.safeToMove,
            obstacleCm = if (enabled) 20f else (realTelemetry.centerCm ?: realTelemetry.obstacleCm),
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
        robotName: String,
        ownerName: String,
        characterInstructions: String,
        characterNeverDo: String,
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
        prefs.robotName = robotName.ifBlank { "Welly" }
        prefs.ownerName = ownerName
        prefs.characterInstructions = characterInstructions
        prefs.characterNeverDo = characterNeverDo
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
        if (remoteEnabled) runCatching { remoteServer.startRemote() }
            .onFailure { addLog("REMOTE START FAIL: ${it.message}") }
        else remoteServer.stopRemote()

        if (!_ui.value.simEsp) {
            link.disconnect()
            if (url.isNotBlank()) link.connect(url)
        }
        if (!remoteManual && !_ui.value.voice.listening) wakeWord?.resume(600L)
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
        val now = v.timestampMs
        val summary = v.objects.take(4).joinToString(" • ") { "${it.label} ${(it.confidence * 100).toInt()}%" }

        // Diagnostics and passive eye tracking are separate from behavior selection.
        val passiveGaze = if (v.faceVisible) ((v.faceCenterX - 0.5f) * 2f).coerceIn(-1f, 1f) else _ui.value.gazeX
        _ui.value = _ui.value.copy(
            gazeX = if (!_ui.value.voice.listening) passiveGaze else _ui.value.gazeX,
            lastGesture = v.handGesture,
            gestureCandidate = v.handCandidate,
            gestureStage = v.gestureStage,
            rawHandLabel = v.rawHandLabel,
            gestureConfidence = v.handConfidence,
            objectLabel = v.objectLabel.orEmpty(),
            objectConfidence = v.objectConfidence,
            objectSummary = summary,
            smileProbability = v.smileProbability,
            kissConfidence = v.kissConfidence,
            kissDetected = v.kissDetected,
            blownKissDetected = v.blownKissDetected,
            mind = brain.mindSnapshot()
        )

        val objectEvent = updateObjectStability(v, now)
        val faceChanged = v.faceVisible != previousFaceVisible
        previousFaceVisible = v.faceVisible

        if (_ui.value.voice.listening || remoteManual) return

        val immediateEvent = v.handGesture != HandGesture.NONE || v.kissDetected || v.blownKissDetected
        if (!immediateEvent && now - lastBrainVisionSampleMs < 240L) return
        lastBrainVisionSampleMs = now

        // PetBrain receives samples so it can maintain face-loss/follow/search state. The result
        // is NOT automatically executed. Only fused meaningful events reach BehaviorExecutive.
        val d = brain.onVision(v, effectiveTelemetry())
        val source = when {
            immediateEvent -> DecisionSource.GESTURE
            d.mode == PetMode.FOLLOWING -> DecisionSource.FOLLOW
            d.mode == PetMode.SEARCHING -> DecisionSource.SEARCH
            else -> DecisionSource.VISION
        }

        val meaningful = when {
            immediateEvent -> true
            source == DecisionSource.FOLLOW || source == DecisionSource.SEARCH -> true
            d.interruptMotion || d.motion != MotionCommand.STOP || d.sequence.isNotEmpty() || !d.speech.isNullOrBlank() ->
                d.mode != PetMode.IDLE || faceChanged || objectEvent
            objectEvent && d.mode in setOf(PetMode.ENGAGED, PetMode.OBJECT_PLAY) -> true
            else -> false
        }

        if (!meaningful) return

        // Final event-level duplicate gate. This sits above detector cooldowns and below the
        // executive, so the same camera event cannot repeatedly restart a behavior.
        val key = d.behaviorKey.ifBlank { source.name }
        val duplicate = key == lastVisionSubmitKey && now - lastVisionSubmitMs < 1100L
        if (duplicate && source !in setOf(DecisionSource.FOLLOW, DecisionSource.SEARCH, DecisionSource.SAFETY)) return
        lastVisionSubmitKey = key
        lastVisionSubmitMs = now
        submit(d, source)
    }

    private fun updateObjectStability(v: VisionObservation, now: Long): Boolean {
        val primary = v.objects.firstOrNull()
        if (primary == null || primary.confidence < 0.44f) {
            stableObjectKey = ""
            stableObjectSinceMs = 0L
            return false
        }
        val key = "${primary.label.lowercase()}#${primary.trackId}"
        if (key != stableObjectKey) {
            stableObjectKey = key
            stableObjectSinceMs = now
            return false
        }
        if (now - stableObjectSinceMs < 900L) return false
        if (key == lastObjectEventKey && now - lastObjectEventMs < 12_000L) return false
        lastObjectEventKey = key
        lastObjectEventMs = now
        return true
    }

    private fun onPhone(v: PhoneObservation) {
        _ui.value = _ui.value.copy(phoneEvent = v.event)
        if (!_ui.value.voice.listening && !remoteManual) {
            brain.onPhone(v)?.let { submit(it, DecisionSource.PHONE) }
        }
    }

    private fun isActionful(d: BrainDecision): Boolean =
        d.interruptMotion || d.motion != MotionCommand.STOP || d.sequence.isNotEmpty() || !d.speech.isNullOrBlank() ||
            d.mode in setOf(PetMode.FOLLOWING, PetMode.SEARCHING, PetMode.CONVERSATION, PetMode.OBJECT_PLAY, PetMode.EMERGENCY)

    private fun applyAmbient(d: BrainDecision) {
        if (_ui.value.voice.listening || _ui.value.voice.phase == "SPEAKING") return
        _ui.value = _ui.value.copy(
            emotion = d.emotion,
            gazeX = d.gazeX,
            gazeY = d.gazeY,
            mind = brain.mindSnapshot()
        )
    }

    private fun submit(d: BrainDecision, source: DecisionSource) {
        val result = executive.submit(d, source)
        val allowed = result.allowed
        val primaryAccepted = BehaviorResource.PRIMARY in allowed
        val faceAccepted = BehaviorResource.FACE in allowed
        val physicalAccepted = BehaviorResource.DRIVE in allowed || BehaviorResource.FORK in allowed

        if (
            BehaviorResource.PRIMARY in result.preempted ||
            BehaviorResource.DRIVE in result.preempted ||
            BehaviorResource.FORK in result.preempted
        ) {
            cancelJobsOnly()
            sendCommand(MotionCommand.STOP, 0L, force = true)
        }

        _ui.value = _ui.value.copy(
            emotion = if (primaryAccepted) result.decision.emotion else _ui.value.emotion,
            mode = if (primaryAccepted) result.decision.mode else _ui.value.mode,
            gazeX = if (faceAccepted) result.decision.gazeX else _ui.value.gazeX,
            gazeY = if (faceAccepted) result.decision.gazeY else _ui.value.gazeY,
            status = if (primaryAccepted) result.decision.status else _ui.value.status,
            activeBehavior = executive.activePrimaryKey(),
            executiveLocks = executive.activeLocks(),
            decisionCommand = if (physicalAccepted || result.decision.interruptMotion) result.decision.motion else _ui.value.decisionCommand,
            mind = brain.mindSnapshot()
        )

        if (result.decision.interruptMotion) {
            cancelMotionAndStop("${source.name}:INTERRUPT")
            if (source != DecisionSource.SAFETY && result.decision.sequence.isNotEmpty()) {
                executeSequence(result.decision.sequence, result.decision.behaviorKey)
            }
        } else if (result.decision.sequence.isNotEmpty()) {
            executeSequence(result.decision.sequence, result.decision.behaviorKey)
        } else if (result.decision.motion != MotionCommand.STOP) {
            executeMotion(result.decision.motion, result.decision.motionDurationMs, result.decision.behaviorKey)
        }

        if (!result.decision.speech.isNullOrBlank() && BehaviorResource.SPEECH in allowed && !_ui.value.voice.listening) {
            voice.speak(result.decision.speech)
        }

        if (result.denied.isNotEmpty() && _ui.value.testing) {
            addLog("EXEC denied ${result.denied.joinToString { it.name }} • ${d.behaviorKey}")
        }
    }

    private fun executeMotion(command: MotionCommand, durationMs: Long, behaviorKey: String) {
        if (motionStopJob?.isActive == true && motionKey == behaviorKey) return
        motionStopJob?.cancel()
        motionKey = behaviorKey
        sendCommand(command, durationMs)
        if (durationMs > 0L) {
            motionStopJob = scope.launch {
                delay(durationMs)
                sendCommand(MotionCommand.STOP, 0L, force = true)
                motionKey = ""
            }
        }
    }

    private fun executeSequence(steps: List<com.shehan.robotpet.brain.MotionStep>, behaviorKey: String) {
        if (sequenceJob?.isActive == true && sequenceKey == behaviorKey) return
        if (sequenceJob?.isActive == true) return // only executive preemption may cancel another behavior
        motionStopJob?.cancel()
        sequenceKey = behaviorKey
        sequenceJob = scope.launch {
            try {
                for (step in steps) {
                    if (!isActive || remoteManual || _ui.value.voice.listening) break
                    sendCommand(step.command, step.durationMs, force = true)
                    delay(step.durationMs)
                    sendCommand(MotionCommand.STOP, 0L, force = true)
                    delay(step.delayAfterMs)
                }
            } finally {
                sequenceKey = ""
            }
        }
    }

    private fun cancelJobsOnly() {
        sequenceJob?.cancel(); sequenceJob = null; sequenceKey = ""
        motionStopJob?.cancel(); motionStopJob = null; motionKey = ""
    }

    private fun cancelMotionAndStop(reason: String) {
        cancelJobsOnly()
        sendCommand(MotionCommand.STOP, 0L, force = true)
        if (_ui.value.testing) addLog("STOP • $reason")
    }

    private fun sendCommand(command: MotionCommand, durationMs: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val drive = command in setOf(MotionCommand.FORWARD, MotionCommand.BACKWARD, MotionCommand.LEFT, MotionCommand.RIGHT)
        val fork = command in setOf(MotionCommand.FORK_UP, MotionCommand.FORK_DOWN)
        if (!force) {
            if (command == lastCommand && now - lastCommandMs < 420L) return
            if (drive && now - lastDriveTxMs < 560L) return
            if (fork && now - lastForkTxMs < 360L) return
        }
        lastCommand = command
        lastCommandMs = now
        if (drive) lastDriveTxMs = now
        if (fork) lastForkTxMs = now

        if (_ui.value.simEsp) {
            val nextSeq = _ui.value.espTxSeq + 1L
            _ui.value = _ui.value.copy(
                espRequestedCommand = command,
                espActualCommand = command,
                espDurationMs = durationMs,
                espQueued = true,
                espSafetyBlocked = false,
                espState = "SIM EXECUTED",
                espTxSeq = nextSeq,
                espAckSeq = nextSeq,
                espAckAccepted = true
            )
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
        if (percent >= 0 && !_ui.value.voice.listening && !remoteManual) {
            brain.onBattery(percent, charging)?.let { submit(it, DecisionSource.BATTERY) }
        }
    }

    private fun persistMind() {
        val m = brain.mindSnapshot()
        prefs.mood = m.mood
        prefs.annoyance = m.annoyance
        prefs.socialNeed = m.socialNeed
        prefs.boredom = m.boredom
        prefs.curiosity = m.curiosity
        prefs.energy = m.energy
        prefs.affection = m.affection
        prefs.confidence = m.confidence
    }

    private fun effectiveTelemetry(): RobotTelemetry = if (_ui.value.simEsp) {
        RobotTelemetry(
            connected = true, safeToMove = true,
            obstacleCm = 20f, centerCm = 20f, leftCm = 50f, rightCm = 50f,
            batteryPercent = 100, lastSeenMs = System.currentTimeMillis()
        )
    } else realTelemetry

    private fun geminiContext(): GeminiContext {
        val u = _ui.value
        return GeminiContext(
            mind = brain.mindSnapshot(),
            faceVisible = latestVision.faceVisible,
            objectLabel = latestVision.objectLabel,
            objectConfidence = latestVision.objectConfidence,
            objectsSummary = u.objectSummary,
            phoneBattery = u.phoneBattery,
            charging = u.phoneCharging,
            robotConnected = effectiveTelemetry().connected,
            robotSafe = effectiveTelemetry().safeToMove,
            status = u.status
        )
    }

    private fun createRemoteServer(port: Int): RemoteControlServer = RemoteControlServer(
        port = port,
        statusProvider = {
            val s = _ui.value
            JSONObject()
                .put("emotion", s.emotion.name)
                .put("mode", s.mode.name)
                .put("status", s.status)
                .put("behavior", s.activeBehavior)
                .put("robotConnected", s.robotConnected)
                .put("safeToMove", s.safeToMove)
                .put("obstacleCm", s.obstacleCm)
                .put("battery", s.phoneBattery)
                .put("objects", s.objectSummary)
                .put("gesture", s.lastGesture.name)
        },
        onCommand = { command, duration ->
            scope.launch {
                if (!remoteManual) return@launch
                remoteLastCommandMs = System.currentTimeMillis()
                remoteLastMotion = command
                cancelJobsOnly()
                sendCommand(command, duration, force = true)
                _ui.value = _ui.value.copy(mode = PetMode.REMOTE, status = "Remote • ${command.name}")
            }
        },
        onManualModeChanged = { manual ->
            scope.launch {
                remoteManual = manual
                executive.setRemoteManual(manual)
                cancelMotionAndStop("REMOTE MODE CHANGE")
                remoteLastMotion = MotionCommand.STOP
                if (manual) wakeWord?.pause() else wakeWord?.resume(700L)
                _ui.value = _ui.value.copy(
                    mode = if (manual) PetMode.REMOTE else PetMode.IDLE,
                    status = if (manual) "Remote manual control" else "Welly autonomous",
                    activeBehavior = executive.activePrimaryKey(),
                    executiveLocks = executive.activeLocks()
                )
            }
        },
        onInfoChanged = { info -> _ui.value = _ui.value.copy(remote = info) }
    )

    private fun addLog(line: String) {
        val stamp = (System.currentTimeMillis() / 1000L) % 100000
        _ui.value = _ui.value.copy(eventLog = (listOf("$stamp • $line") + _ui.value.eventLog).take(10))
    }

    fun shutdown() {
        persistMind()
        cancelJobsOnly()
        wakeWord?.shutdown(); wakeWord = null
        phoneSensors.stop()
        vision?.shutdown()
        voice.shutdown()
        remoteServer.shutdown()
        gemini.shutdown()
        link.shutdown()
        executive.reset()
        scope.cancel()
    }
}
