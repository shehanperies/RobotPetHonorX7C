package com.shehan.robotpet.brain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.random.Random

/**
 * V6 local living brain. It proposes high-level behaviors only; BehaviorExecutive owns
 * arbitration. No detector is allowed to directly commandeer motors.
 */
class PetBrain {
    private var sleeping = false
    private var quietMode = false
    private var followAllowed = true
    private var followUntilMs = 0L

    private var currentFaceVisible = false
    private var hadSeenFace = false
    private var lastFaceSeenMs = 0L
    private var faceLostAtMs = 0L
    private var faceStableSinceMs = 0L
    private var lastFaceX = 0.5f

    private var mood = 0
    private var annoyance = 0
    private var socialNeed = 20
    private var boredom = 15
    private var curiosity = 45
    private var energy = 90
    private var affection = 50
    private var confidence = 50

    private var lastDriveUpdateMs = System.currentTimeMillis()
    private var lastInteractionMs = System.currentTimeMillis()
    private var socialAcc = 0f
    private var boredomAcc = 0f
    private var curiosityAcc = 0f
    private var energyAcc = 0f

    private val gestureCooldowns = mutableMapOf<HandGesture, Long>()
    private val objectSeenCount = mutableMapOf<String, Int>()
    private val behaviorHistory = mutableListOf<String>()
    private var lastObjectLabel: String? = null
    private var lastObjectReactionMs = 0L
    private var lastVisionObject: DetectedObject? = null

    private var lastKissMs = 0L
    private var lastBlownKissMs = 0L
    private var lastSmileMs = 0L
    private var winkCandidateSide = 0
    private var winkCandidateSinceMs = 0L
    private var lastWinkMs = 0L
    private var lastCloseFaceMs = 0L

    private var searchStartAtMs = 0L
    private var searchPhase = 0
    private var searchCycle = 0
    private var nextSearchStepMs = 0L
    private var searchFirstDirection = MotionCommand.LEFT
    private var searchPulseMs = 220L

    private var followCandidate = MotionCommand.STOP
    private var followCandidateSinceMs = 0L
    private var lastFollowPulseMs = 0L

    private var lastSpontaneousMs = 0L
    private var nextSpontaneousDelayMs = randomLong(9000L, 18000L)
    private var lastAttentionInviteMs = 0L
    private var lastSelfPlayMs = 0L
    private var lastContextPromptMs = 0L
    private var contextPromptKey = ""
    private var greetingDate: LocalDate? = null
    private var lastBatteryReactionMs = 0L

    fun setFollowEnabled(enabled: Boolean) {
        followAllowed = enabled
        if (!enabled) followUntilMs = 0L
    }

    fun restoreMind(
        savedMood: Int,
        savedAnnoyance: Int,
        savedSocialNeed: Int = 20,
        savedBoredom: Int = 15,
        savedCuriosity: Int = 45,
        savedEnergy: Int = 90,
        savedAffection: Int = 50,
        savedConfidence: Int = 50
    ) {
        mood = savedMood.coerceIn(-100, 100)
        annoyance = savedAnnoyance.coerceIn(0, 100)
        socialNeed = savedSocialNeed.coerceIn(0, 100)
        boredom = savedBoredom.coerceIn(0, 100)
        curiosity = savedCuriosity.coerceIn(0, 100)
        energy = savedEnergy.coerceIn(0, 100)
        affection = savedAffection.coerceIn(0, 100)
        confidence = savedConfidence.coerceIn(0, 100)
    }

    fun mindSnapshot(now: Long = System.currentTimeMillis()) = PetMindSnapshot(
        mood = mood,
        annoyance = annoyance,
        socialNeed = socialNeed,
        boredom = boredom,
        curiosity = curiosity,
        energy = energy,
        affection = affection,
        confidence = confidence,
        followActive = isFollowActive(now),
        searchActive = searchStartAtMs > 0L && !currentFaceVisible
    )

    fun isDirectVoiceCommand(text: String): Boolean {
        val q = text.trim().lowercase()
        val phrases = listOf(
            "stop", "stay", "nawath", "නවති", "sleep", "wake",
            "follow", "come here", "come", "enn", "එන්න",
            "turn around", "spin", "karaken", "වටේ",
            "forward", "back", "left", "right",
            "fork up", "fork down", "fork", "uda", "pahala",
            "move that", "push that", "move it",
            "hello", "hi", "your name", "who are you", "what time", "time",
            "did you eat", "have you eaten"
        )
        return phrases.any { it in q }
    }

    /** Rare autonomous AI opportunity only. Engine additionally applies a minutes-long gate. */
    fun shouldRequestAi(now: Long = System.currentTimeMillis()): Boolean {
        if (sleeping || now - lastInteractionMs < 8 * 60_000L) return false
        return boredom >= 88 || socialNeed >= 90 || curiosity >= 92
    }

    fun onTouch(): BrainDecision {
        wakeForInteraction()
        annoyance = (annoyance - 14).coerceAtLeast(0)
        mood = (mood + 7).coerceAtMost(100)
        affection = (affection + 2).coerceAtMost(100)
        socialNeed = (socialNeed - 12).coerceAtLeast(0)
        boredom = (boredom - 8).coerceAtLeast(0)
        return remember(
            BrainDecision(
                emotion = if (affection > 65) Emotion.LOVE else Emotion.HAPPY,
                mode = PetMode.ENGAGED,
                speech = if (recentlyUsed("touch-talk")) null else randomOf("Hey!", "Hi!", "Hehe."),
                status = "Touched",
                behaviorKey = "touch",
                minimumHoldMs = 1000L
            )
        )
    }

    fun onTickle(): BrainDecision {
        wakeForInteraction()
        mood = (mood + 12).coerceAtMost(100)
        boredom = (boredom - 18).coerceAtLeast(0)
        affection = (affection + 3).coerceAtMost(100)
        return remember(
            BrainDecision(
                emotion = Emotion.PLAYFUL,
                mode = PetMode.ENGAGED,
                speech = randomOf("Hehe!", "That tickles!", "Ah! Haha!"),
                sequence = listOf(
                    MotionStep(MotionCommand.FORK_UP, 210L, 70L),
                    MotionStep(MotionCommand.FORK_DOWN, 190L, 80L),
                    MotionStep(MotionCommand.FORK_UP, 150L, 60L),
                    MotionStep(MotionCommand.FORK_DOWN, 170L, 70L)
                ),
                status = "Tickled • fork bounce",
                behaviorKey = "tickle",
                minimumHoldMs = 1700L
            )
        )
    }

    fun onPetting(): BrainDecision {
        wakeForInteraction()
        annoyance = (annoyance - 24).coerceAtLeast(0)
        mood = (mood + 14).coerceAtMost(100)
        affection = (affection + 5).coerceAtMost(100)
        socialNeed = (socialNeed - 25).coerceAtLeast(0)
        boredom = (boredom - 16).coerceAtLeast(0)
        return remember(
            BrainDecision(
                emotion = Emotion.LOVE,
                mode = PetMode.ENGAGED,
                speech = if (recentlyUsed("pet-talk")) null else randomOf("Mmm, nice.", "I like that.", "More!"),
                sequence = if (Random.nextBoolean()) forkHappyBounce() else emptyList(),
                status = "Petted • affection $affection",
                behaviorKey = "petting",
                minimumHoldMs = 1800L
            )
        )
    }

    fun onSpeech(text: String, telemetry: RobotTelemetry): BrainDecision {
        val q = text.trim().lowercase()
        val now = System.currentTimeMillis()
        wakeForInteraction(now)
        socialNeed = (socialNeed - 18).coerceAtLeast(0)
        boredom = (boredom - 12).coerceAtLeast(0)

        return when {
            containsAny(q, "don't follow", "do not follow", "stop following") -> {
                followUntilMs = 0L
                cancelSearch()
                decisionStop("Okay, I won't follow.", "follow-stop")
            }
            containsAny(q, "stop", "stay", "nawath", "නවති") -> {
                followUntilMs = 0L
                cancelSearch()
                decisionStop("Okay.", "voice-stop")
            }
            containsAny(q, "sleep") -> {
                followUntilMs = 0L
                cancelSearch()
                sleeping = true
                BrainDecision(
                    Emotion.SLEEPY, PetMode.SLEEPING,
                    speech = "Nap time.",
                    sequence = forkRest(), interruptMotion = true,
                    status = "Sleeping", behaviorKey = "sleep", minimumHoldMs = 2400L
                )
            }
            containsAny(q, "wake") -> {
                sleeping = false
                BrainDecision(
                    Emotion.HAPPY, PetMode.ENGAGED,
                    speech = "I'm awake!", sequence = forkGreeting(),
                    status = "Awake", behaviorKey = "wake", minimumHoldMs = 1700L
                )
            }
            containsAny(q, "come here", "follow me", "come", "enn", "එන්න") -> {
                if (followAllowed) {
                    followUntilMs = now + 45_000L
                    cancelSearch()
                    BrainDecision(
                        Emotion.HAPPY, PetMode.FOLLOWING,
                        speech = "Coming!", sequence = forkGreeting(),
                        status = "Follow session • 45s", behaviorKey = "follow-start", minimumHoldMs = 1700L
                    )
                } else BrainDecision(
                    Emotion.CURIOUS, PetMode.ENGAGED,
                    speech = "Following is disabled.", status = "Follow disabled",
                    behaviorKey = "follow-disabled", minimumHoldMs = 1500L
                )
            }
            containsAny(q, "turn around", "spin", "karaken", "වටේ") -> turnAroundDecision(telemetry, "voice-turn")
            containsAny(q, "fork up", "fork uda", "fork එක උඩ") || ("fork" in q && "up" in q) ->
                safeMotion(MotionCommand.FORK_UP, telemetry, "Fork up", "fork-up")
            containsAny(q, "fork down", "fork pahala", "fork එක පහළ") || ("fork" in q && "down" in q) ->
                safeMotion(MotionCommand.FORK_DOWN, telemetry, "Fork down", "fork-down")
            containsAny(q, "move that", "push that", "move it") -> tryNudgeObject(telemetry)
            "forward" in q -> safeMotion(MotionCommand.FORWARD, telemetry, "Forward", "forward")
            "back" in q -> safeMotion(MotionCommand.BACKWARD, telemetry, "Backward", "backward")
            "left" in q -> safeMotion(MotionCommand.LEFT, telemetry, "Left", "left")
            "right" in q -> safeMotion(MotionCommand.RIGHT, telemetry, "Right", "right")
            containsAny(q, "what time", "time is it") || q == "time" -> {
                val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))
                BrainDecision(
                    Emotion.HAPPY, PetMode.CONVERSATION,
                    speech = "It's $time.", status = "Time", behaviorKey = "time", minimumHoldMs = 1500L
                )
            }
            containsAny(q, "did you eat", "have you eaten") -> BrainDecision(
                Emotion.PLAYFUL, PetMode.CONVERSATION,
                speech = randomOf("I run on electricity!", "Battery power is my lunch."),
                status = "Food joke", behaviorKey = "food-joke", minimumHoldMs = 1700L
            )
            q == "hi" || q == "hello" || q.startsWith("hi ") -> BrainDecision(
                Emotion.HAPPY, PetMode.ENGAGED,
                speech = randomOf("Hi!", "Hey there!"), sequence = forkGreeting(),
                status = "Greeting", behaviorKey = "voice-greet", minimumHoldMs = 1800L
            )
            containsAny(q, "who are you", "your name") -> BrainDecision(
                Emotion.CURIOUS, PetMode.CONVERSATION,
                speech = "I'm your little robot pet.", status = "Chatting",
                behaviorKey = "identity", minimumHoldMs = 1700L
            )
            else -> BrainDecision(
                Emotion.CURIOUS, PetMode.CONVERSATION,
                speech = randomOf("I heard you. Tell me another way?", "Hmm, I'm still learning that one."),
                status = "Local conversation fallback", behaviorKey = "speech-fallback", minimumHoldMs = 1600L
            )
        }
    }

    fun onAiDirective(directive: AiDirective, telemetry: RobotTelemetry, now: Long = System.currentTimeMillis()): BrainDecision {
        val emotion = parseEmotion(directive.emotion) ?: Emotion.CURIOUS
        val speech = directive.speech?.takeIf { it.isNotBlank() }
        wakeForInteraction(now)
        return when (directive.action) {
            AiAction.NONE -> BrainDecision(emotion, PetMode.IDLE, speech = speech, status = "AI • observe", behaviorKey = "ai-none")
            AiAction.CHAT -> BrainDecision(emotion, PetMode.CONVERSATION, speech = speech, status = "AI • chat", behaviorKey = "ai-chat", minimumHoldMs = 1900L)
            AiAction.GREET -> BrainDecision(emotion, PetMode.ENGAGED, speech = speech ?: "Hi!", sequence = forkGreeting(), status = "AI • greet", behaviorKey = "ai-greet", minimumHoldMs = 2100L)
            AiAction.PLAY -> BrainDecision(emotion, PetMode.ENGAGED, speech = speech, sequence = forkHappyBounce(), gazeX = randomFloat(-0.5f, 0.5f), status = "AI • play", behaviorKey = "ai-play", minimumHoldMs = 2300L)
            AiAction.SEARCH -> {
                if (!currentFaceVisible) scheduleSearch(now, immediate = true)
                BrainDecision(Emotion.CURIOUS, PetMode.SEARCHING, speech = speech, status = "AI • search", behaviorKey = "ai-search", minimumHoldMs = 1200L)
            }
            AiAction.FOLLOW -> {
                if (followAllowed && currentFaceVisible) {
                    followUntilMs = now + 30_000L
                    BrainDecision(Emotion.HAPPY, PetMode.FOLLOWING, speech = speech, status = "AI • follow", behaviorKey = "ai-follow", minimumHoldMs = 1400L)
                } else BrainDecision(Emotion.CURIOUS, PetMode.ENGAGED, speech = speech, status = "AI follow ignored", behaviorKey = "ai-follow-ignore")
            }
            AiAction.LOOK_LEFT -> BrainDecision(emotion, PetMode.ENGAGED, gazeX = -0.85f, speech = speech, status = "AI • look left", behaviorKey = "ai-look-left")
            AiAction.LOOK_RIGHT -> BrainDecision(emotion, PetMode.ENGAGED, gazeX = 0.85f, speech = speech, status = "AI • look right", behaviorKey = "ai-look-right")
            AiAction.FORK_WAVE -> BrainDecision(emotion, PetMode.ENGAGED, speech = speech, sequence = forkGreeting(), status = "AI • fork wave", behaviorKey = "ai-fork", minimumHoldMs = 1900L)
            AiAction.REST -> BrainDecision(Emotion.SLEEPY, PetMode.IDLE, speech = speech, sequence = forkRest(), status = "AI • rest", behaviorKey = "ai-rest", minimumHoldMs = 1900L)
            AiAction.INSPECT_OBJECT -> BrainDecision(Emotion.CURIOUS, PetMode.OBJECT_PLAY, gazeX = objectGaze(), speech = speech, status = "AI • inspect object", behaviorKey = "ai-inspect", minimumHoldMs = 1800L)
        }
    }

    fun onVision(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision {
        val now = v.timestampMs
        updateDrives(now)
        lastVisionObject = v.objects.firstOrNull { !it.label.equals("person", true) } ?: v.objects.firstOrNull()

        if (v.handGesture != HandGesture.NONE) reactToGesture(v, telemetry)?.let { return it }

        if (v.blownKissDetected && now - lastBlownKissMs > 3000L) {
            lastBlownKissMs = now
            return loveReaction(v, blown = true)
        }
        if (v.kissDetected && now - lastKissMs > 2600L) {
            lastKissMs = now
            return loveReaction(v, blown = false)
        }

        val faceJustAppeared = v.faceVisible && !currentFaceVisible
        val faceJustLost = !v.faceVisible && currentFaceVisible
        if (faceJustLost) {
            currentFaceVisible = false
            faceLostAtMs = now
            scheduleSearch(now, immediate = false)
        }

        if (v.faceVisible) {
            val missingFor = if (faceLostAtMs > 0L) now - faceLostAtMs else 0L
            if (faceJustAppeared) faceStableSinceMs = now
            currentFaceVisible = true
            hadSeenFace = true
            lastFaceSeenMs = now
            lastFaceX = v.faceCenterX
            socialNeed = (socialNeed - 1).coerceAtLeast(0)
            cancelSearch()

            if (faceJustAppeared && missingFor > 3500L) {
                sleeping = false
                mood = (mood + 6).coerceAtMost(100)
                return BrainDecision(
                    Emotion.HAPPY, PetMode.ENGAGED,
                    gazeX = faceGaze(v),
                    speech = if (missingFor > 9000L) randomOf("There you are!", "Found you!") else null,
                    sequence = if (missingFor > 7000L) forkGreeting() else emptyList(),
                    motion = MotionCommand.STOP, interruptMotion = true,
                    status = "Owner found • stop search", behaviorKey = "owner-found", minimumHoldMs = 1900L
                )
            }

            reactToFaceExpression(v)?.let { return it }
        } else {
            currentFaceVisible = false
        }

        if (sleeping) {
            return BrainDecision(Emotion.SLEEPY, PetMode.SLEEPING, status = "Sleeping", behaviorKey = "sleeping")
        }

        if (v.faceVisible && isFollowActive(now)) return smartFollow(v, telemetry)
        if (!v.faceVisible) searchForPerson(now, telemetry)?.let { return it }

        reactToObject(v)?.let { return it }

        if (v.faceVisible) {
            contextualPrompt(now)?.let { return it }
            if (socialNeed >= 78 && now - lastAttentionInviteMs > 120_000L && now - faceStableSinceMs > 5000L) {
                lastAttentionInviteMs = now
                socialNeed = (socialNeed - 14).coerceAtLeast(0)
                return BrainDecision(
                    Emotion.PLAYFUL, PetMode.ENGAGED, gazeX = faceGaze(v),
                    speech = randomOf("Hey, play with me?", "What are you doing?"),
                    sequence = if (Random.nextInt(100) < 45) forkGreeting() else emptyList(),
                    status = "Social invite", behaviorKey = "social-invite", minimumHoldMs = 2100L
                )
            }
            return BrainDecision(
                currentEmotion(), PetMode.ENGAGED,
                gazeX = faceGaze(v), status = if (quietMode) "Quietly watching" else "Watching you",
                behaviorKey = "face-track"
            )
        }

        return idleTick(telemetry, now)
    }

    fun onPhone(obs: PhoneObservation): BrainDecision? {
        wakeForInteraction(obs.timestampMs)
        boredom = (boredom - 5).coerceAtLeast(0)
        return when (obs.event) {
            PhoneEvent.SHAKE -> BrainDecision(Emotion.DIZZY, PetMode.ENGAGED, speech = randomOf("Whoa, dizzy!", "Hey!"), status = "Phone shake", behaviorKey = "phone-shake", minimumHoldMs = 1800L)
            PhoneEvent.TILT_LEFT -> BrainDecision(Emotion.STARTLED, PetMode.ENGAGED, gazeX = -0.55f, status = "Tilted left", behaviorKey = "tilt-left", minimumHoldMs = 900L)
            PhoneEvent.TILT_RIGHT -> BrainDecision(Emotion.STARTLED, PetMode.ENGAGED, gazeX = 0.55f, status = "Tilted right", behaviorKey = "tilt-right", minimumHoldMs = 900L)
            PhoneEvent.UPSIDE_DOWN -> BrainDecision(Emotion.STARTLED, PetMode.ENGAGED, speech = "Hey! I'm upside down!", status = "Upside down", behaviorKey = "upside-down", minimumHoldMs = 1800L)
            PhoneEvent.DARK -> BrainDecision(Emotion.SLEEPY, PetMode.IDLE, status = "It's dark", behaviorKey = "dark", minimumHoldMs = 1200L)
            PhoneEvent.BRIGHT -> BrainDecision(Emotion.STARTLED, PetMode.ENGAGED, status = "Bright light", behaviorKey = "bright", minimumHoldMs = 1000L)
            PhoneEvent.NONE -> null
        }
    }

    fun onBattery(level: Int, charging: Boolean, now: Long = System.currentTimeMillis()): BrainDecision? {
        if (charging) energy = (energy + 2).coerceAtMost(100) else if (level < 25) energy = minOf(energy, 45)
        if (now - lastBatteryReactionMs < 120_000L) return null
        return when {
            charging && level < 95 -> {
                lastBatteryReactionMs = now
                BrainDecision(Emotion.HAPPY, PetMode.IDLE, speech = if (level < 20) "Ah, power!" else null, status = "Charging • $level%", behaviorKey = "charging")
            }
            level in 0..15 -> {
                lastBatteryReactionMs = now
                BrainDecision(Emotion.SLEEPY, PetMode.IDLE, speech = "I'm getting low on battery.", sequence = forkRest(), status = "Low battery • $level%", behaviorKey = "low-battery", minimumHoldMs = 1900L)
            }
            else -> null
        }
    }

    fun idleTick(telemetry: RobotTelemetry, nowMs: Long = System.currentTimeMillis()): BrainDecision {
        updateDrives(nowMs)
        if (followUntilMs != 0L && nowMs >= followUntilMs) {
            followUntilMs = 0L
            resetFollowCandidate()
        }
        annoyance = (annoyance - 1).coerceAtLeast(0)
        mood += when { mood > 0 -> -1; mood < 0 -> 1; else -> 0 }

        if (sleeping) return BrainDecision(Emotion.SLEEPY, PetMode.SLEEPING, status = "Sleeping", behaviorKey = "sleeping")
        if (telemetry.connected && !telemetry.safeToMove) {
            return BrainDecision(Emotion.STARTLED, PetMode.EMERGENCY, motion = MotionCommand.STOP, interruptMotion = true, status = "Safety stop", behaviorKey = "safety-stop", minimumHoldMs = 900L)
        }
        if (!currentFaceVisible) searchForPerson(nowMs, telemetry)?.let { return it }

        val quietFor = nowMs - maxOf(lastInteractionMs, lastFaceSeenMs)
        if (quietFor > 25 * 60_000L && energy < 68) {
            sleeping = true
            return BrainDecision(Emotion.SLEEPY, PetMode.SLEEPING, speech = if (Random.nextBoolean()) "Nap time." else null, sequence = forkRest(), status = "Auto sleep", behaviorKey = "auto-sleep", minimumHoldMs = 2200L)
        }

        if (!currentFaceVisible && boredom >= 70 && nowMs - lastSelfPlayMs > 45_000L) {
            lastSelfPlayMs = nowMs
            boredom = (boredom - 16).coerceAtLeast(0)
            return BrainDecision(
                Emotion.PLAYFUL, PetMode.IDLE,
                gazeX = randomFloat(-0.7f, 0.7f), gazeY = randomFloat(-0.15f, 0.15f),
                sequence = if (Random.nextBoolean()) forkSelfPlay() else emptyList(),
                status = "Self-play", behaviorKey = "self-play", minimumHoldMs = 2200L
            )
        }

        if (nowMs - lastSpontaneousMs >= nextSpontaneousDelayMs) {
            lastSpontaneousMs = nowMs
            nextSpontaneousDelayMs = randomLong(9000L, 20_000L)
            val roll = Random.nextInt(100)
            if (roll < 58) return BrainDecision(Emotion.CURIOUS, if (currentFaceVisible) PetMode.ENGAGED else PetMode.IDLE, gazeX = randomFloat(-0.7f, 0.7f), gazeY = randomFloat(-0.18f, 0.18f), status = "Looking around", behaviorKey = "micro-look")
            if (roll < 76 && boredom >= 38) return BrainDecision(Emotion.PLAYFUL, PetMode.IDLE, gazeX = randomFloat(-0.45f, 0.45f), sequence = if (Random.nextInt(100) < 35) forkCurious() else emptyList(), status = "Little playful moment", behaviorKey = "micro-play", minimumHoldMs = 1500L)
        }

        return BrainDecision(
            currentEmotion(), if (currentFaceVisible) PetMode.ENGAGED else PetMode.IDLE,
            gazeX = if (currentFaceVisible) ((lastFaceX - 0.5f) * 2f).coerceIn(-1f, 1f) else 0f,
            status = if (currentFaceVisible) "Calm • with you" else "Calm",
            behaviorKey = "idle"
        )
    }

    private fun loveReaction(v: VisionObservation, blown: Boolean): BrainDecision {
        wakeForInteraction(v.timestampMs)
        mood = (mood + 12).coerceAtMost(100)
        affection = (affection + if (blown) 5 else 3).coerceAtMost(100)
        socialNeed = (socialNeed - 14).coerceAtLeast(0)
        return BrainDecision(
            Emotion.LOVE, PetMode.ENGAGED,
            gazeX = faceGaze(v),
            speech = if (blown) randomOf("Mwah!", "Aww, love you too!") else randomOf("Mwah!", "Aww!"),
            sequence = listOf(
                MotionStep(MotionCommand.FORK_UP, 180L, 75L),
                MotionStep(MotionCommand.FORK_DOWN, 170L, 70L)
            ),
            status = if (blown) "Blown kiss confirmed" else "Kiss confirmed",
            behaviorKey = if (blown) "blown-kiss" else "kiss",
            minimumHoldMs = 1900L
        )
    }

    private fun reactToGesture(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision? {
        val g = v.handGesture
        if (g == HandGesture.NONE) return null
        val now = v.timestampMs
        val last = gestureCooldowns[g] ?: 0L
        if (now - last < if (g == HandGesture.OPEN_PALM) 700L else 1300L) return null
        gestureCooldowns[g] = now
        wakeForInteraction(now)
        socialNeed = (socialNeed - 12).coerceAtLeast(0)
        boredom = (boredom - 10).coerceAtLeast(0)
        if (g != HandGesture.SHH) quietMode = false

        return when (g) {
            HandGesture.OPEN_PALM -> {
                followUntilMs = 0L
                cancelSearch()
                decisionStop("Okay, stop.", "gesture-stop").copy(gazeX = handGaze(v), status = "Open palm • STOP")
            }
            HandGesture.WAVE -> BrainDecision(Emotion.HAPPY, PetMode.ENGAGED, gazeX = handGaze(v), speech = randomOf("Hi!", "Hey!"), sequence = forkGreeting(), status = "Wave confirmed", behaviorKey = "wave", minimumHoldMs = 1900L)
            HandGesture.THUMBS_UP -> BrainDecision(Emotion.HAPPY, PetMode.ENGAGED, gazeX = handGaze(v), speech = randomOf("Yes!", "Nice!"), sequence = forkHappyBounce(), status = "Thumbs up", behaviorKey = "thumbs-up", minimumHoldMs = 1900L)
            HandGesture.THUMBS_DOWN -> BrainDecision(Emotion.SAD, PetMode.ENGAGED, gazeX = handGaze(v), speech = "Aww...", sequence = forkSad(), status = "Thumbs down", behaviorKey = "thumbs-down", minimumHoldMs = 2000L)
            HandGesture.POINT_LEFT -> pointReaction(MotionCommand.LEFT, -0.9f, telemetry, "Point left")
            HandGesture.POINT_RIGHT -> pointReaction(MotionCommand.RIGHT, 0.9f, telemetry, "Point right")
            HandGesture.POINT_UP -> pointFork(MotionCommand.FORK_UP, telemetry, "Point up • FORK_UP")
            HandGesture.POINT_DOWN -> pointFork(MotionCommand.FORK_DOWN, telemetry, "Point down • FORK_DOWN")
            HandGesture.COME_HERE -> {
                if (followAllowed) {
                    followUntilMs = now + 35_000L
                    cancelSearch()
                    BrainDecision(Emotion.HAPPY, PetMode.FOLLOWING, gazeX = handGaze(v), speech = "Coming!", sequence = forkGreeting(), status = "Come here • follow 35s", behaviorKey = "come-here", minimumHoldMs = 1800L)
                } else BrainDecision(Emotion.CURIOUS, PetMode.ENGAGED, gazeX = handGaze(v), status = "Come here seen • follow disabled", behaviorKey = "come-disabled")
            }
            HandGesture.TURN_AROUND -> turnAroundDecision(telemetry, "circle-turn")
            HandGesture.SHH -> {
                quietMode = true
                BrainDecision(Emotion.SLEEPY, PetMode.ENGAGED, gazeX = handGaze(v), status = "Shh • quiet", behaviorKey = "shh", minimumHoldMs = 2400L)
            }
            HandGesture.PEACE -> BrainDecision(Emotion.PLAYFUL, PetMode.ENGAGED, gazeX = handGaze(v), speech = "Peace!", sequence = forkGreeting(), status = "Peace", behaviorKey = "peace", minimumHoldMs = 1800L)
            HandGesture.LOVE -> BrainDecision(Emotion.LOVE, PetMode.ENGAGED, gazeX = handGaze(v), speech = "Love you too!", sequence = forkHappyBounce(), status = "Love gesture", behaviorKey = "love-hand", minimumHoldMs = 2100L)
            HandGesture.FIST -> {
                annoyance = (annoyance + 8).coerceAtMost(100)
                BrainDecision(if (annoyance > 60) Emotion.ANGRY else Emotion.STARTLED, PetMode.ENGAGED, gazeX = handGaze(v), speech = if (annoyance > 60) "Be nice." else "Whoa...", status = "Fist", behaviorKey = "fist", minimumHoldMs = 1800L)
            }
            HandGesture.HIT_SWING -> {
                annoyance = (annoyance + 28).coerceAtMost(100)
                confidence = (confidence - 8).coerceAtLeast(0)
                val safe = telemetry.connected && telemetry.safeToMove
                BrainDecision(
                    if (annoyance > 70) Emotion.ANGRY else Emotion.STARTLED,
                    PetMode.ENGAGED, gazeX = handGaze(v), speech = if (annoyance > 70) "Not funny." else "Whoa!",
                    motion = if (safe) MotionCommand.BACKWARD else MotionCommand.STOP,
                    motionDurationMs = if (safe) 220L else 0L,
                    sequence = forkStartle(), status = "Fast fist swing • defensive back-off",
                    behaviorKey = "hit-swing", minimumHoldMs = 2600L
                )
            }
            HandGesture.NONE -> null
        }
    }

    private fun smartFollow(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision {
        val now = v.timestampMs
        val gaze = faceGaze(v)
        if (!telemetry.connected || !telemetry.safeToMove) {
            resetFollowCandidate()
            return BrainDecision(currentEmotion(), PetMode.FOLLOWING, gazeX = gaze, status = "Following • waiting for safety", behaviorKey = "follow-wait")
        }

        val wanted = when {
            gaze < -0.46f -> MotionCommand.LEFT
            gaze > 0.46f -> MotionCommand.RIGHT
            v.faceAreaRatio < 0.050f -> MotionCommand.FORWARD
            v.faceAreaRatio > 0.235f -> MotionCommand.BACKWARD
            else -> MotionCommand.STOP
        }
        if (wanted == MotionCommand.STOP) {
            resetFollowCandidate()
            return BrainDecision(currentEmotion(), PetMode.FOLLOWING, gazeX = gaze, status = "Following • comfortable distance", behaviorKey = "follow-hold")
        }
        if (wanted != followCandidate) {
            followCandidate = wanted
            followCandidateSinceMs = now
            return BrainDecision(currentEmotion(), PetMode.FOLLOWING, gazeX = gaze, status = "Following • confirming", behaviorKey = "follow-confirm")
        }
        if (now - followCandidateSinceMs < 500L || now - lastFollowPulseMs < 950L) {
            return BrainDecision(currentEmotion(), PetMode.FOLLOWING, gazeX = gaze, status = "Following • tracking", behaviorKey = "follow-track")
        }
        lastFollowPulseMs = now
        followCandidateSinceMs = now
        val duration = if (wanted in setOf(MotionCommand.LEFT, MotionCommand.RIGHT)) 170L else 210L
        return BrainDecision(currentEmotion(), PetMode.FOLLOWING, gazeX = gaze, motion = wanted, motionDurationMs = duration, status = "Following • ${wanted.name.lowercase()} pulse", behaviorKey = "follow-pulse")
    }

    private fun reactToFaceExpression(v: VisionObservation): BrainDecision? {
        val now = v.timestampMs
        val gaze = faceGaze(v)
        if (!v.handPresent && v.smileProbability >= 0.88f && now - lastSmileMs > 11_000L) {
            lastSmileMs = now
            mood = (mood + 5).coerceAtMost(100)
            return BrainDecision(Emotion.HAPPY, PetMode.ENGAGED, gazeX = gaze, speech = if (Random.nextBoolean()) "Nice smile!" else null, status = "Smile confirmed", behaviorKey = "smile", minimumHoldMs = 1500L)
        }

        if (!v.handPresent && v.faceAreaRatio > 0.31f && now - lastCloseFaceMs > 9000L) {
            lastCloseFaceMs = now
            return BrainDecision(Emotion.STARTLED, PetMode.ENGAGED, gazeX = gaze, speech = "Whoa, close!", sequence = forkStartle(), status = "Close face", behaviorKey = "close-face", minimumHoldMs = 1600L)
        }

        if (!v.handPresent && abs(v.headEulerY) < 22f) {
            val winkSide = when {
                v.leftEyeOpenProbability in 0f..0.16f && v.rightEyeOpenProbability > 0.80f -> -1
                v.rightEyeOpenProbability in 0f..0.16f && v.leftEyeOpenProbability > 0.80f -> 1
                else -> 0
            }
            if (winkSide == 0) {
                winkCandidateSide = 0
                winkCandidateSinceMs = 0L
            } else if (winkSide != winkCandidateSide) {
                winkCandidateSide = winkSide
                winkCandidateSinceMs = now
            } else if (now - winkCandidateSinceMs > 480L && now - lastWinkMs > 12_000L) {
                lastWinkMs = now
                winkCandidateSide = 0
                return BrainDecision(Emotion.PLAYFUL, PetMode.ENGAGED, gazeX = gaze, speech = "Wink wink!", status = "Wink confirmed", behaviorKey = "wink", minimumHoldMs = 1500L)
            }
        }
        return null
    }

    private fun reactToObject(v: VisionObservation): BrainDecision? {
        val target = v.objects.firstOrNull { !it.label.equals("person", true) } ?: return null
        if (target.confidence < 0.42f) return null
        val now = v.timestampMs
        val label = target.label.lowercase()
        val count = (objectSeenCount[label] ?: 0) + 1
        objectSeenCount[label] = count.coerceAtMost(100)
        val changed = label != lastObjectLabel
        val cooldown = when { count <= 2 -> 10_000L; count <= 5 -> 25_000L; else -> 55_000L }
        if (!changed && now - lastObjectReactionMs < cooldown) return null
        lastObjectLabel = label
        lastObjectReactionMs = now
        curiosity = (curiosity + if (changed) 7 else 2).coerceAtMost(100)
        val gaze = ((target.centerX - 0.5f) * 2f).coerceIn(-1f, 1f)

        return when (label) {
            "cat", "dog" -> BrainDecision(Emotion.HAPPY, PetMode.ENGAGED, gazeX = gaze, speech = if (count <= 2) "Oh! A $label!" else null, status = "Object • $label", behaviorKey = "object-$label", minimumHoldMs = 1600L)
            "sports ball" -> BrainDecision(Emotion.PLAYFUL, PetMode.OBJECT_PLAY, gazeX = gaze, speech = if (currentFaceVisible && count <= 2) "Ball?" else null, sequence = if (count <= 2) forkCurious() else emptyList(), status = "Toy curiosity • ball", behaviorKey = "object-ball", minimumHoldMs = 1700L)
            else -> BrainDecision(Emotion.CURIOUS, PetMode.ENGAGED, gazeX = gaze, status = "Object • $label ${"%.0f".format(target.confidence * 100)}%", behaviorKey = "object-$label", minimumHoldMs = 900L)
        }
    }

    private fun tryNudgeObject(telemetry: RobotTelemetry): BrainDecision {
        val target = lastVisionObject
        if (target == null) return BrainDecision(Emotion.CURIOUS, PetMode.ENGAGED, speech = "I can't see what to move.", status = "Move object • no target", behaviorKey = "nudge-no-target", minimumHoldMs = 1600L)
        val cm = telemetry.centerCm ?: telemetry.obstacleCm
        val safeDistance = cm != null && cm in 9f..30f
        if (!telemetry.connected || !telemetry.safeToMove || !safeDistance) {
            return BrainDecision(
                Emotion.CURIOUS, PetMode.OBJECT_PLAY,
                speech = "I need a safe distance reading before I push it.",
                status = "Object nudge blocked • distance safety required",
                behaviorKey = "nudge-blocked", minimumHoldMs = 1900L
            )
        }
        return BrainDecision(
            Emotion.CURIOUS, PetMode.OBJECT_PLAY,
            gazeX = ((target.centerX - 0.5f) * 2f).coerceIn(-1f, 1f),
            speech = "I'll give it a tiny push.",
            sequence = listOf(
                MotionStep(MotionCommand.FORK_DOWN, 180L, 120L),
                MotionStep(MotionCommand.FORWARD, 180L, 100L),
                MotionStep(MotionCommand.BACKWARD, 160L, 90L)
            ),
            status = "Object nudge • ${target.label}", behaviorKey = "object-nudge", minimumHoldMs = 1900L
        )
    }

    private fun searchForPerson(now: Long, telemetry: RobotTelemetry): BrainDecision? {
        if (!hadSeenFace || currentFaceVisible || faceLostAtMs == 0L) return null
        if (searchStartAtMs == 0L) scheduleSearch(faceLostAtMs, immediate = false)
        if (now < searchStartAtMs) {
            return BrainDecision(Emotion.CURIOUS, PetMode.SEARCHING, gazeX = ((lastFaceX - 0.5f) * 1.2f).coerceIn(-0.45f, 0.45f), status = "Lost you • filtering dropout", behaviorKey = "search-wait")
        }
        if (now < nextSearchStepMs) return BrainDecision(Emotion.CURIOUS, PetMode.SEARCHING, status = "Search • observing", behaviorKey = "search-observe")

        val safe = telemetry.connected && telemetry.safeToMove
        val first = searchFirstDirection
        val opposite = if (first == MotionCommand.LEFT) MotionCommand.RIGHT else MotionCommand.LEFT
        val decision = when (searchPhase) {
            0 -> BrainDecision(Emotion.CURIOUS, PetMode.SEARCHING, gazeX = if (first == MotionCommand.LEFT) -0.85f else 0.85f, motion = if (safe) first else MotionCommand.STOP, motionDurationMs = if (safe) searchPulseMs else 0L, status = "Search • first side", behaviorKey = "search-turn-1")
            1 -> BrainDecision(Emotion.CURIOUS, PetMode.SEARCHING, gazeX = if (opposite == MotionCommand.LEFT) -0.85f else 0.85f, motion = if (safe) opposite else MotionCommand.STOP, motionDurationMs = if (safe) (searchPulseMs * 2).coerceAtMost(520L) else 0L, status = "Search • other side", behaviorKey = "search-turn-2")
            2 -> BrainDecision(Emotion.CURIOUS, PetMode.SEARCHING, gazeX = 0f, motion = if (safe) first else MotionCommand.STOP, motionDurationMs = if (safe) searchPulseMs else 0L, status = "Search • return center", behaviorKey = "search-center")
            else -> BrainDecision(if (now - faceLostAtMs > 18_000L) Emotion.LONELY else Emotion.CURIOUS, PetMode.IDLE, speech = if (searchCycle == 0 && socialNeed > 62) randomOf("Hmm?", "Where did you go?") else null, sequence = if (searchCycle == 0 && Random.nextBoolean()) forkCurious() else emptyList(), status = "Search • waiting", behaviorKey = "search-wait-long", minimumHoldMs = 1600L)
        }
        searchPhase++
        if (searchPhase > 3) {
            searchCycle++
            if (searchCycle < 2 && socialNeed >= 58) {
                searchPhase = 0
                searchFirstDirection = if (Random.nextBoolean()) MotionCommand.LEFT else MotionCommand.RIGHT
                searchPulseMs = randomLong(170L, 290L)
                nextSearchStepMs = now + randomLong(6500L, 10_000L)
            } else nextSearchStepMs = now + 30_000L
        } else nextSearchStepMs = now + randomLong(750L, 1350L)
        return decision
    }

    private fun scheduleSearch(now: Long, immediate: Boolean) {
        if (!hadSeenFace) return
        searchStartAtMs = if (immediate) now else now + randomLong(4000L, 7000L)
        searchPhase = 0
        searchCycle = 0
        nextSearchStepMs = searchStartAtMs
        searchFirstDirection = if (lastFaceX < 0.45f) MotionCommand.LEFT else if (lastFaceX > 0.55f) MotionCommand.RIGHT else if (Random.nextBoolean()) MotionCommand.LEFT else MotionCommand.RIGHT
        searchPulseMs = randomLong(170L, 280L)
    }

    private fun cancelSearch() {
        searchStartAtMs = 0L
        searchPhase = 0
        searchCycle = 0
        nextSearchStepMs = 0L
    }

    private fun contextualPrompt(nowMs: Long): BrainDecision? {
        if (!currentFaceVisible || quietMode) return null
        if (abs(System.currentTimeMillis() - nowMs) > 86_400_000L) return null
        if (nowMs - lastContextPromptMs < 240_000L) return null
        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        val hour = now.hour
        if (greetingDate != date && hour in 5..11) {
            greetingDate = date
            lastContextPromptMs = nowMs
            return BrainDecision(Emotion.HAPPY, PetMode.ENGAGED, speech = randomOf("Good morning!", "Morning!"), sequence = forkGreeting(), status = "Morning greeting", behaviorKey = "morning-greeting", minimumHoldMs = 1900L)
        }
        val mealKey = when (hour) { in 7..9 -> "$date-breakfast"; in 12..14 -> "$date-lunch"; in 18..20 -> "$date-dinner"; else -> "" }
        if (mealKey.isNotBlank() && mealKey != contextPromptKey && socialNeed >= 46 && Random.nextInt(100) < 12) {
            contextPromptKey = mealKey
            lastContextPromptMs = nowMs
            val meal = mealKey.substringAfterLast("-")
            return BrainDecision(Emotion.CURIOUS, PetMode.CONVERSATION, speech = "Did you have $meal?", status = "Meal-time question", behaviorKey = "meal-$meal", minimumHoldMs = 1900L)
        }
        if (hour >= 23 && energy < 65 && contextPromptKey != "$date-late") {
            contextPromptKey = "$date-late"
            lastContextPromptMs = nowMs
            return BrainDecision(Emotion.SLEEPY, PetMode.CONVERSATION, speech = "Still awake?", status = "Late-night question", behaviorKey = "late-night", minimumHoldMs = 1800L)
        }
        return null
    }

    private fun updateDrives(now: Long) {
        val elapsed = (now - lastDriveUpdateMs).coerceIn(0L, 60_000L)
        if (elapsed < 1000L) return
        lastDriveUpdateMs = now
        val seconds = elapsed / 1000f
        val interactionAge = now - lastInteractionMs
        if (interactionAge > 8000L) {
            socialAcc += seconds / 35f
            boredomAcc += seconds / 28f
        }
        curiosityAcc += if (!currentFaceVisible) seconds / 45f else -seconds / 25f
        energyAcc += if (sleeping) seconds / 45f else -seconds / 210f
        if (socialAcc >= 1f) { val step = socialAcc.toInt(); socialNeed = (socialNeed + step).coerceAtMost(100); socialAcc -= step }
        if (boredomAcc >= 1f) { val step = boredomAcc.toInt(); boredom = (boredom + step).coerceAtMost(100); boredomAcc -= step }
        if (curiosityAcc >= 1f) { val step = curiosityAcc.toInt(); curiosity = (curiosity + step).coerceAtMost(100); curiosityAcc -= step }
        else if (curiosityAcc <= -1f) { val step = (-curiosityAcc).toInt(); curiosity = (curiosity - step).coerceAtLeast(20); curiosityAcc += step }
        if (energyAcc >= 1f) { val step = energyAcc.toInt(); energy = (energy + step).coerceAtMost(100); energyAcc -= step }
        else if (energyAcc <= -1f) { val step = (-energyAcc).toInt(); energy = (energy - step).coerceAtLeast(5); energyAcc += step }
    }

    private fun pointReaction(command: MotionCommand, gaze: Float, telemetry: RobotTelemetry, label: String): BrainDecision {
        val move = if (telemetry.connected && telemetry.safeToMove) command else MotionCommand.STOP
        return BrainDecision(Emotion.CURIOUS, PetMode.ENGAGED, gazeX = gaze, motion = move, motionDurationMs = if (move == command) 220L else 0L, status = if (move == command) "$label • pulse" else "$label • eyes only", behaviorKey = label.lowercase().replace(' ', '-'), minimumHoldMs = 1200L)
    }

    private fun pointFork(command: MotionCommand, telemetry: RobotTelemetry, label: String): BrainDecision {
        val move = if (telemetry.connected && telemetry.safeToMove) command else MotionCommand.STOP
        return BrainDecision(Emotion.CURIOUS, PetMode.ENGAGED, motion = move, motionDurationMs = if (move == command) 320L else 0L, status = if (move == command) label else "$label • blocked", behaviorKey = label.lowercase().replace(' ', '-'), minimumHoldMs = 1200L)
    }

    private fun turnAroundDecision(telemetry: RobotTelemetry, key: String): BrainDecision {
        val safe = telemetry.connected && telemetry.safeToMove
        return BrainDecision(Emotion.PLAYFUL, PetMode.ENGAGED, speech = if (safe) "Okay!" else null, motion = if (safe) MotionCommand.LEFT else MotionCommand.STOP, motionDurationMs = if (safe) 1500L else 0L, status = if (safe) "Turn around" else "Turn blocked", behaviorKey = key, minimumHoldMs = 2200L)
    }

    private fun safeMotion(command: MotionCommand, telemetry: RobotTelemetry, name: String, key: String): BrainDecision {
        return if (telemetry.connected && telemetry.safeToMove) {
            BrainDecision(Emotion.HAPPY, PetMode.ENGAGED, speech = if (command in setOf(MotionCommand.FORK_UP, MotionCommand.FORK_DOWN)) null else name, motion = command, motionDurationMs = if (command in setOf(MotionCommand.FORK_UP, MotionCommand.FORK_DOWN)) 340L else 430L, status = name, behaviorKey = key, minimumHoldMs = 1100L)
        } else BrainDecision(Emotion.STARTLED, PetMode.ENGAGED, speech = "I won't move until it's safe.", motion = MotionCommand.STOP, interruptMotion = true, status = "Movement blocked", behaviorKey = "$key-blocked", minimumHoldMs = 1500L)
    }

    private fun decisionStop(speech: String?, key: String) = BrainDecision(
        Emotion.LISTENING, PetMode.ENGAGED, speech = speech,
        motion = MotionCommand.STOP, interruptMotion = true,
        status = "STOP", behaviorKey = key, minimumHoldMs = 1200L
    )

    private fun wakeForInteraction(now: Long = System.currentTimeMillis()) {
        sleeping = false
        quietMode = false
        lastInteractionMs = now
        energy = (energy + 2).coerceAtMost(100)
    }

    private fun isFollowActive(now: Long) = followAllowed && followUntilMs > now
    private fun resetFollowCandidate() { followCandidate = MotionCommand.STOP; followCandidateSinceMs = 0L }
    private fun currentEmotion() = when {
        annoyance >= 72 -> Emotion.ANGRY
        annoyance >= 45 -> Emotion.SAD
        mood >= 55 || affection >= 78 -> Emotion.LOVE
        mood >= 24 -> Emotion.HAPPY
        socialNeed >= 80 && !currentFaceVisible -> Emotion.LONELY
        boredom >= 68 -> Emotion.PLAYFUL
        else -> Emotion.CURIOUS
    }

    private fun parseEmotion(value: String): Emotion? = runCatching { Emotion.valueOf(value.uppercase()) }.getOrNull()
    private fun faceGaze(v: VisionObservation) = ((v.faceCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)
    private fun handGaze(v: VisionObservation) = ((v.handCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)
    private fun objectGaze() = ((lastVisionObject?.centerX ?: 0.5f) - 0.5f).times(2f).coerceIn(-1f, 1f)

    private fun forkGreeting() = listOf(MotionStep(MotionCommand.FORK_UP, 210L, 70L), MotionStep(MotionCommand.FORK_DOWN, 210L, 70L))
    private fun forkHappyBounce() = listOf(MotionStep(MotionCommand.FORK_UP, 170L, 55L), MotionStep(MotionCommand.FORK_DOWN, 150L, 55L), MotionStep(MotionCommand.FORK_UP, 150L, 55L), MotionStep(MotionCommand.FORK_DOWN, 170L, 70L))
    private fun forkCurious() = listOf(MotionStep(MotionCommand.FORK_UP, 140L, 180L), MotionStep(MotionCommand.FORK_DOWN, 120L, 70L))
    private fun forkSelfPlay() = listOf(MotionStep(MotionCommand.FORK_UP, 130L, 90L), MotionStep(MotionCommand.FORK_DOWN, 110L, 120L), MotionStep(MotionCommand.FORK_UP, 90L, 80L), MotionStep(MotionCommand.FORK_DOWN, 120L, 70L))
    private fun forkSad() = listOf(MotionStep(MotionCommand.FORK_DOWN, 260L, 80L))
    private fun forkStartle() = listOf(MotionStep(MotionCommand.FORK_UP, 240L, 120L), MotionStep(MotionCommand.FORK_DOWN, 150L, 70L))
    private fun forkRest() = listOf(MotionStep(MotionCommand.FORK_DOWN, 330L, 80L))

    private fun remember(d: BrainDecision): BrainDecision {
        behaviorHistory.add(d.behaviorKey)
        while (behaviorHistory.size > 12) behaviorHistory.removeAt(0)
        return d
    }
    private fun recentlyUsed(key: String) = behaviorHistory.takeLast(5).contains(key).also { behaviorHistory.add(key); while (behaviorHistory.size > 12) behaviorHistory.removeAt(0) }
    private fun containsAny(text: String, vararg values: String) = values.any { it in text }
    private fun randomOf(vararg choices: String) = choices[Random.nextInt(choices.size)]
    private fun randomLong(min: Long, max: Long) = Random.nextLong(min, max + 1)
    private fun randomFloat(min: Float, max: Float) = min + Random.nextFloat() * (max - min)
}
