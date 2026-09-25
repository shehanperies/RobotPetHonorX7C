package com.shehan.robotpet.brain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.random.Random

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
    private var lastVisionMs = 0L

    private var mood = 0
    private var annoyance = 0
    private var socialNeed = 20
    private var boredom = 15
    private var curiosity = 45
    private var energy = 90
    private var lastDriveUpdateMs = System.currentTimeMillis()
    private var socialDriveAccumulator = 0f
    private var boredomDriveAccumulator = 0f
    private var curiosityDriveAccumulator = 0f
    private var energyDriveAccumulator = 0f

    private var lastInteractionMs = System.currentTimeMillis()
    private var previousGesture = HandGesture.NONE
    private val lastGestureTrigger = mutableMapOf<HandGesture, Long>()

    private var heldDecision: BrainDecision? = null
    private var holdUntilMs = 0L

    // Lost-person search: wait 4–7s, then a bounded body scan.
    private var searchStartAtMs = 0L
    private var searchPhase = 0
    private var searchCycle = 0
    private var nextSearchStepMs = 0L
    private var searchFirstDirection = MotionCommand.LEFT
    private var searchPulseMs = 220L
    private var lastSearchDecision = BrainDecision(
        Emotion.CURIOUS,
        PetMode.SEARCHING,
        status = "Searching"
    )

    // Follow stabilization prevents constant twitching.
    private var followCandidate = MotionCommand.STOP
    private var followCandidateSinceMs = 0L
    private var lastFollowPulseMs = 0L

    // Face-expression stability.
    private var smileCandidateSinceMs = 0L
    private var lastSmileMs = 0L
    private var winkCandidateSide = 0
    private var winkCandidateSinceMs = 0L
    private var lastWinkMs = 0L
    private var lastCloseFaceMs = 0L
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastHeadSampleMs = 0L
    private var lastHeadGestureMs = 0L

    // Object habituation.
    private var lastObjectLabel: String? = null
    private var lastObjectReactionMs = 0L
    private val objectSeenCount = mutableMapOf<String, Int>()

    private var lastBatteryReactionMs = 0L
    private var lastSpontaneousMs = 0L
    private var nextSpontaneousDelayMs = randomLong(7000L, 16000L)
    private var lastAttentionInviteMs = 0L
    private var lastSelfPlayMs = 0L
    private var lastContextPromptMs = 0L
    private var contextPromptKey = ""
    private var greetingDate: LocalDate? = null

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
        savedEnergy: Int = 90
    ) {
        mood = savedMood.coerceIn(-100, 100)
        annoyance = savedAnnoyance.coerceIn(0, 100)
        socialNeed = savedSocialNeed.coerceIn(0, 100)
        boredom = savedBoredom.coerceIn(0, 100)
        curiosity = savedCuriosity.coerceIn(0, 100)
        energy = savedEnergy.coerceIn(0, 100)
    }

    fun restoreMood(savedMood: Int, savedAnnoyance: Int) {
        restoreMind(savedMood, savedAnnoyance)
    }

    fun moodSnapshot(): Pair<Int, Int> = mood to annoyance

    fun mindSnapshot(now: Long = System.currentTimeMillis()): PetMindSnapshot {
        return PetMindSnapshot(
            mood = mood,
            annoyance = annoyance,
            socialNeed = socialNeed,
            boredom = boredom,
            curiosity = curiosity,
            energy = energy,
            followActive = isFollowActive(now),
            searchActive = searchStartAtMs > 0L && !currentFaceVisible
        )
    }

    fun isDirectVoiceCommand(text: String): Boolean {
        val q = text.trim().lowercase()
        return listOf(
            "stop", "stay", "nawath", "sleep", "wake", "follow",
            "come here", "come", "turn around", "spin", "forward",
            "back", "left", "right", "fork", "hello", "hi", "your name",
            "who are you", "time", "what time", "eat", "food"
        ).any { it in q }
    }

    fun shouldRequestAi(now: Long = System.currentTimeMillis()): Boolean {
        if (sleeping || now - lastInteractionMs < 12_000L) return false
        return boredom >= 68 || socialNeed >= 72 || curiosity >= 78
    }

    fun onTouch(): BrainDecision {
        wakeForInteraction()
        annoyance = (annoyance - 18).coerceAtLeast(0)
        mood = (mood + 10).coerceAtMost(100)
        socialNeed = (socialNeed - 18).coerceAtLeast(0)
        boredom = (boredom - 15).coerceAtLeast(0)

        return hold(
            BrainDecision(
                emotion = if (mood > 55) Emotion.LOVE else Emotion.HAPPY,
                mode = PetMode.ENGAGED,
                speech = if (annoyance > 55) {
                    "Okay... friends?"
                } else {
                    randomOf("Hey!", "Hehe!", "Hi there!", "That tickles!")
                },
                sequence = if (Random.nextInt(100) < 60) forkGreeting() else emptyList(),
                status = "Touched • mood $mood"
            ),
            1800L
        )
    }

    fun onPetting(): BrainDecision {
        wakeForInteraction()
        annoyance = (annoyance - 30).coerceAtLeast(0)
        mood = (mood + 18).coerceAtMost(100)
        socialNeed = (socialNeed - 32).coerceAtLeast(0)
        boredom = (boredom - 22).coerceAtLeast(0)

        return hold(
            BrainDecision(
                emotion = if (mood > 45) Emotion.LOVE else Emotion.HAPPY,
                mode = PetMode.ENGAGED,
                speech = randomOf("Mmm, nice.", "I like that.", "Hehe."),
                sequence = forkHappyBounce(),
                status = "Petted • mood $mood"
            ),
            2300L
        )
    }

    fun onSpeech(text: String, telemetry: RobotTelemetry): BrainDecision {
        val q = text.trim().lowercase()
        val now = System.currentTimeMillis()
        wakeForInteraction(now)
        socialNeed = (socialNeed - 20).coerceAtLeast(0)
        boredom = (boredom - 18).coerceAtLeast(0)

        return when {
            q.contains("stop") || q.contains("stay") || q.contains("nawath") -> {
                followUntilMs = 0L
                cancelSearch()
                hold(
                    BrainDecision(
                        Emotion.LISTENING,
                        PetMode.ENGAGED,
                        speech = "Okay, staying here.",
                        motion = MotionCommand.STOP,
                        interruptMotion = true,
                        status = "Voice • STOP"
                    ),
                    1400L
                )
            }

            q.contains("sleep") -> {
                followUntilMs = 0L
                cancelSearch()
                sleeping = true
                hold(
                    BrainDecision(
                        Emotion.SLEEPY,
                        PetMode.SLEEPING,
                        speech = "Okay. Nap time.",
                        sequence = forkRest(),
                        interruptMotion = true,
                        status = "Sleeping"
                    ),
                    2500L
                )
            }

            q.contains("wake") -> hold(
                BrainDecision(
                    Emotion.HAPPY,
                    PetMode.ENGAGED,
                    speech = "I'm awake!",
                    sequence = forkGreeting(),
                    status = "Awake"
                ),
                1600L
            )

            q.contains("don't follow") || q.contains("do not follow") ||
                q.contains("stop following") -> {
                followUntilMs = 0L
                hold(
                    BrainDecision(
                        Emotion.IDLE,
                        PetMode.ENGAGED,
                        speech = "Okay, I won't follow.",
                        motion = MotionCommand.STOP,
                        interruptMotion = true,
                        status = "Follow stopped"
                    ),
                    1600L
                )
            }

            q.contains("come here") || q.contains("follow me") || q == "come" -> {
                if (followAllowed) {
                    followUntilMs = now + 45_000L
                    hold(
                        BrainDecision(
                            Emotion.HAPPY,
                            PetMode.FOLLOWING,
                            speech = "Okay, I'll follow you.",
                            sequence = forkGreeting(),
                            status = "Follow session • 45s"
                        ),
                        1700L
                    )
                } else {
                    hold(
                        BrainDecision(
                            Emotion.CURIOUS,
                            PetMode.ENGAGED,
                            speech = "Following is disabled in settings.",
                            status = "Follow disabled"
                        ),
                        1900L
                    )
                }
            }

            q.contains("turn around") || q.contains("spin") ->
                turnAroundDecision(telemetry, "Voice • turn around")

            q.contains("forward") -> safeMotion(MotionCommand.FORWARD, telemetry, "Forward")
            q.contains("back") -> safeMotion(MotionCommand.BACKWARD, telemetry, "Backward")
            q.contains("left") -> safeMotion(MotionCommand.LEFT, telemetry, "Left")
            q.contains("right") -> safeMotion(MotionCommand.RIGHT, telemetry, "Right")

            q.contains("fork") && q.contains("up") ->
                safeMotion(MotionCommand.FORK_UP, telemetry, "Fork up")

            q.contains("fork") && q.contains("down") ->
                safeMotion(MotionCommand.FORK_DOWN, telemetry, "Fork down")

            q.contains("what time") || q == "time" || q.contains("time is it") -> {
                val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        PetMode.ENGAGED,
                        speech = "It's $time.",
                        status = "Telling the time"
                    ),
                    1800L
                )
            }

            q.contains("did you eat") || q.contains("have you eaten") -> hold(
                BrainDecision(
                    Emotion.PLAYFUL,
                    PetMode.ENGAGED,
                    speech = randomOf(
                        "I run on electricity, remember?",
                        "No food for me. I like battery power!"
                    ),
                    status = "Food joke"
                ),
                2100L
            )

            q.contains("hello") || q.contains("hi ") || q == "hi" -> hold(
                BrainDecision(
                    Emotion.HAPPY,
                    PetMode.ENGAGED,
                    speech = randomOf("Hello!", "Hi!", "Hey there!"),
                    sequence = forkGreeting(),
                    status = "Greeting"
                ),
                1700L
            )

            q.contains("who are you") || q.contains("your name") -> hold(
                BrainDecision(
                    Emotion.CURIOUS,
                    PetMode.ENGAGED,
                    speech = "I'm your little robot pet.",
                    status = "Chatting"
                ),
                1900L
            )

            else -> hold(
                BrainDecision(
                    Emotion.CURIOUS,
                    PetMode.ENGAGED,
                    speech = randomOf(
                        "I heard you, but I'm still learning that one.",
                        "Hmm. Tell me another way?"
                    ),
                    status = "Local brain • unknown speech"
                ),
                1700L
            )
        }
    }

    fun onAiDirective(
        directive: AiDirective,
        telemetry: RobotTelemetry,
        now: Long = System.currentTimeMillis()
    ): BrainDecision {
        val emotion = parseEmotion(directive.emotion)
        val speech = directive.speech?.takeIf { it.isNotBlank() }
        wakeForInteraction(now)

        return when (directive.action) {
            AiAction.NONE -> BrainDecision(
                emotion = emotion ?: currentFaceEmotion(),
                mode = PetMode.IDLE,
                speech = speech,
                status = "Gemini • observing"
            )

            AiAction.CHAT -> hold(
                BrainDecision(
                    emotion = emotion ?: Emotion.CURIOUS,
                    mode = PetMode.ENGAGED,
                    speech = speech,
                    status = "Gemini • chat"
                ),
                2300L
            )

            AiAction.GREET -> hold(
                BrainDecision(
                    emotion = emotion ?: Emotion.HAPPY,
                    mode = PetMode.ENGAGED,
                    speech = speech ?: "Hi!",
                    sequence = forkGreeting(),
                    status = "Gemini • greet"
                ),
                2200L
            )

            AiAction.PLAY -> {
                boredom = (boredom - 25).coerceAtLeast(0)
                hold(
                    BrainDecision(
                        emotion = emotion ?: Emotion.PLAYFUL,
                        mode = PetMode.ENGAGED,
                        speech = speech,
                        gazeX = randomFloat(-0.55f, 0.55f),
                        sequence = forkHappyBounce(),
                        status = "Gemini • play"
                    ),
                    2500L
                )
            }

            AiAction.SEARCH -> {
                if (!currentFaceVisible) {
                    scheduleSearch(now, immediate = true)
                    BrainDecision(
                        emotion = emotion ?: Emotion.CURIOUS,
                        mode = PetMode.SEARCHING,
                        speech = speech,
                        status = "Gemini • search"
                    )
                } else {
                    BrainDecision(
                        emotion = Emotion.CURIOUS,
                        mode = PetMode.ENGAGED,
                        speech = speech,
                        status = "Gemini • person already visible"
                    )
                }
            }

            AiAction.FOLLOW -> {
                if (followAllowed && currentFaceVisible) {
                    followUntilMs = now + 30_000L
                    hold(
                        BrainDecision(
                            emotion = emotion ?: Emotion.HAPPY,
                            mode = PetMode.FOLLOWING,
                            speech = speech,
                            status = "Gemini • follow 30s"
                        ),
                        1700L
                    )
                } else {
                    BrainDecision(
                        emotion = Emotion.CURIOUS,
                        mode = PetMode.ENGAGED,
                        speech = speech,
                        status = "Gemini follow ignored • no target/permission"
                    )
                }
            }

            AiAction.LOOK_LEFT -> BrainDecision(
                emotion = emotion ?: Emotion.CURIOUS,
                mode = PetMode.ENGAGED,
                gazeX = -0.82f,
                speech = speech,
                status = "Gemini • look left"
            )

            AiAction.LOOK_RIGHT -> BrainDecision(
                emotion = emotion ?: Emotion.CURIOUS,
                mode = PetMode.ENGAGED,
                gazeX = 0.82f,
                speech = speech,
                status = "Gemini • look right"
            )

            AiAction.FORK_WAVE -> hold(
                BrainDecision(
                    emotion = emotion ?: Emotion.HAPPY,
                    mode = PetMode.ENGAGED,
                    speech = speech,
                    sequence = forkGreeting(),
                    status = "Gemini • fork wave"
                ),
                2000L
            )

            AiAction.REST -> hold(
                BrainDecision(
                    emotion = emotion ?: Emotion.SLEEPY,
                    mode = PetMode.IDLE,
                    speech = speech,
                    sequence = forkRest(),
                    status = "Gemini • rest"
                ),
                2200L
            )
        }
    }

    fun onVision(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision {
        val now = v.timestampMs
        lastVisionMs = now
        updateDrives(now)

        reactToGesture(v, telemetry)?.let { return it }

        val faceJustAppeared = v.faceVisible && !currentFaceVisible
        val faceJustLost = !v.faceVisible && currentFaceVisible

        if (faceJustLost) {
            currentFaceVisible = false
            faceLostAtMs = now
            scheduleSearch(now, immediate = false)
            resetFaceExpressionCandidates()
        }

        if (v.faceVisible) {
            val missingFor = if (faceLostAtMs > 0L) now - faceLostAtMs else 0L

            if (faceJustAppeared || now - lastFaceSeenMs > 900L) {
                faceStableSinceMs = now
            }

            currentFaceVisible = true
            hadSeenFace = true
            lastFaceSeenMs = now
            lastFaceX = v.faceCenterX
            socialNeed = (socialNeed - 1).coerceAtLeast(0)
            cancelSearch()

            if (faceJustAppeared && missingFor > 3500L) {
                sleeping = false
                mood = (mood + 8).coerceAtMost(100)
                boredom = (boredom - 8).coerceAtLeast(0)
                return hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        PetMode.ENGAGED,
                        gazeX = faceGaze(v),
                        speech = if (missingFor > 9000L) {
                            randomOf("There you are!", "Found you!", "Hey, you're back!")
                        } else null,
                        sequence = forkGreeting(),
                        motion = MotionCommand.STOP,
                        interruptMotion = true,
                        status = "Found you • STOP search"
                    ),
                    2200L
                )
            }

            reactToFaceExpression(v)?.let { return it }
        } else {
            currentFaceVisible = false
            resetFaceExpressionCandidates()
        }

        heldUiDecision(now)?.let { return it }

        if (sleeping) {
            if (v.faceVisible) {
                sleeping = false
                return hold(
                    BrainDecision(
                        Emotion.STARTLED,
                        PetMode.ENGAGED,
                        gazeX = faceGaze(v),
                        speech = "Oh! Hi.",
                        sequence = forkStartle(),
                        status = "Woken up"
                    ),
                    1600L
                )
            }
            return BrainDecision(Emotion.SLEEPY, PetMode.SLEEPING, status = "Sleeping")
        }

        if (v.faceVisible) {
            val gaze = faceGaze(v)

            if (isFollowActive(now)) {
                return smartFollow(v, telemetry)
            }

            reactToObject(v)?.let { return it }

            if (
                socialNeed >= 72 &&
                now - lastAttentionInviteMs > 90_000L &&
                now - faceStableSinceMs > 5000L
            ) {
                lastAttentionInviteMs = now
                socialNeed = (socialNeed - 15).coerceAtLeast(0)
                return hold(
                    BrainDecision(
                        Emotion.PLAYFUL,
                        PetMode.ENGAGED,
                        gazeX = gaze,
                        speech = randomOf(
                            "Hey, play with me?",
                            "What are you doing?",
                            "Got a minute for me?"
                        ),
                        sequence = forkGreeting(),
                        status = "Social invite"
                    ),
                    2400L
                )
            }

            contextualPrompt(now)?.let { return it }

            return BrainDecision(
                emotion = currentFaceEmotion(),
                mode = PetMode.ENGAGED,
                gazeX = gaze,
                status = if (quietMode) "Quietly watching" else "Watching you"
            )
        }

        searchForPerson(now, telemetry)?.let { return it }
        reactToObject(v)?.let { return it }
        return idleTick(telemetry, now)
    }

    private fun smartFollow(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision {
        val now = v.timestampMs
        val gaze = faceGaze(v)

        if (!telemetry.connected || !telemetry.safeToMove) {
            resetFollowCandidate()
            return BrainDecision(
                currentFaceEmotion(),
                PetMode.FOLLOWING,
                gazeX = gaze,
                motion = MotionCommand.STOP,
                status = "Following • waiting for safety"
            )
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
            return BrainDecision(
                currentFaceEmotion(),
                PetMode.FOLLOWING,
                gazeX = gaze,
                status = "Following • comfortable distance"
            )
        }

        if (wanted != followCandidate) {
            followCandidate = wanted
            followCandidateSinceMs = now
            return BrainDecision(
                currentFaceEmotion(),
                PetMode.FOLLOWING,
                gazeX = gaze,
                status = "Following • observing"
            )
        }

        if (now - followCandidateSinceMs < 480L || now - lastFollowPulseMs < 950L) {
            return BrainDecision(
                currentFaceEmotion(),
                PetMode.FOLLOWING,
                gazeX = gaze,
                status = "Following • tracking"
            )
        }

        lastFollowPulseMs = now
        followCandidateSinceMs = now

        val duration = when (wanted) {
            MotionCommand.LEFT, MotionCommand.RIGHT -> 170L
            MotionCommand.FORWARD, MotionCommand.BACKWARD -> 210L
            else -> 0L
        }

        return BrainDecision(
            currentFaceEmotion(),
            PetMode.FOLLOWING,
            gazeX = gaze,
            motion = wanted,
            motionDurationMs = duration,
            status = "Following • ${wanted.name.lowercase()} pulse"
        )
    }

    private fun reactToFaceExpression(v: VisionObservation): BrainDecision? {
        val now = v.timestampMs
        val gaze = faceGaze(v)

        // Hands crossing the face make ML Kit eye probabilities noisy.
        if (v.handPresent || v.faceAreaRatio < 0.025f || abs(v.headEulerY) > 22f) {
            winkCandidateSide = 0
            winkCandidateSinceMs = 0L
        } else {
            val winkSide = when {
                v.leftEyeOpenProbability in 0f..0.18f &&
                    v.rightEyeOpenProbability > 0.78f -> -1
                v.rightEyeOpenProbability in 0f..0.18f &&
                    v.leftEyeOpenProbability > 0.78f -> 1
                else -> 0
            }

            if (winkSide == 0) {
                winkCandidateSide = 0
                winkCandidateSinceMs = 0L
            } else if (winkSide != winkCandidateSide) {
                winkCandidateSide = winkSide
                winkCandidateSinceMs = now
            } else if (
                now - winkCandidateSinceMs >= 520L &&
                now - lastWinkMs > 10_000L
            ) {
                lastWinkMs = now
                winkCandidateSide = 0
                boredom = (boredom - 8).coerceAtLeast(0)
                return hold(
                    BrainDecision(
                        Emotion.PLAYFUL,
                        PetMode.ENGAGED,
                        gazeX = gaze,
                        speech = randomOf("I saw that.", "Wink wink!"),
                        status = "Confirmed wink"
                    ),
                    1700L
                )
            }
        }

        if (!v.handPresent && v.smileProbability >= 0.84f) {
            if (smileCandidateSinceMs == 0L) smileCandidateSinceMs = now
            if (now - smileCandidateSinceMs >= 520L && now - lastSmileMs > 10_000L) {
                lastSmileMs = now
                smileCandidateSinceMs = 0L
                mood = (mood + 7).coerceAtMost(100)
                socialNeed = (socialNeed - 6).coerceAtLeast(0)
                return hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        PetMode.ENGAGED,
                        gazeX = gaze,
                        speech = randomOf("Nice smile!", "Hehe, you look happy."),
                        status = "Smile detected"
                    ),
                    1800L
                )
            }
        } else {
            smileCandidateSinceMs = 0L
        }

        if (v.faceAreaRatio > 0.30f && now - lastCloseFaceMs > 9000L) {
            lastCloseFaceMs = now
            return hold(
                BrainDecision(
                    Emotion.STARTLED,
                    PetMode.ENGAGED,
                    gazeX = gaze,
                    speech = "Whoa, close!",
                    sequence = forkStartle(),
                    status = "Sudden close face"
                ),
                1700L
            )
        }

        if (
            !v.handPresent &&
            lastHeadSampleMs > 0L &&
            now - lastHeadSampleMs < 650L &&
            now - lastHeadGestureMs > 7000L
        ) {
            val yawCross = abs(v.headEulerY - lastYaw) > 32f &&
                abs(v.headEulerY) > 13f &&
                abs(lastYaw) > 13f &&
                v.headEulerY * lastYaw < 0f

            val pitchCross = abs(v.headEulerX - lastPitch) > 26f &&
                abs(v.headEulerX) > 10f &&
                abs(lastPitch) > 10f &&
                v.headEulerX * lastPitch < 0f

            if (yawCross) {
                lastHeadGestureMs = now
                rememberHead(v, now)
                return hold(
                    BrainDecision(
                        Emotion.CURIOUS,
                        PetMode.ENGAGED,
                        gazeX = gaze,
                        speech = "No?",
                        status = "Head shake"
                    ),
                    1500L
                )
            }

            if (pitchCross) {
                lastHeadGestureMs = now
                rememberHead(v, now)
                return hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        PetMode.ENGAGED,
                        gazeX = gaze,
                        speech = "Okay!",
                        status = "Head nod"
                    ),
                    1500L
                )
            }
        }

        rememberHead(v, now)
        return null
    }

    private fun rememberHead(v: VisionObservation, now: Long) {
        lastYaw = v.headEulerY
        lastPitch = v.headEulerX
        lastHeadSampleMs = now
    }

    private fun resetFaceExpressionCandidates() {
        smileCandidateSinceMs = 0L
        winkCandidateSide = 0
        winkCandidateSinceMs = 0L
    }

    private fun reactToObject(v: VisionObservation): BrainDecision? {
        val label = v.objectLabel?.lowercase() ?: return null
        if (v.objectConfidence < 0.46f) return null

        val now = v.timestampMs
        val count = (objectSeenCount[label] ?: 0) + 1
        objectSeenCount[label] = count.coerceAtMost(100)

        val changed = label != lastObjectLabel
        val habituationCooldown = when {
            count <= 2 -> 9000L
            count <= 5 -> 20_000L
            else -> 45_000L
        }

        if (!changed && now - lastObjectReactionMs < habituationCooldown) return null

        lastObjectLabel = label
        lastObjectReactionMs = now
        curiosity = (curiosity + if (changed) 10 else 3).coerceAtMost(100)

        val gaze = ((v.objectCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)
        return when (label) {
            "cat", "dog" -> hold(
                BrainDecision(
                    Emotion.HAPPY,
                    PetMode.ENGAGED,
                    gazeX = gaze,
                    speech = if (count <= 2) "Oh! A $label!" else null,
                    status = "Object • $label"
                ),
                1900L
            )

            "sports ball" -> hold(
                BrainDecision(
                    Emotion.PLAYFUL,
                    PetMode.ENGAGED,
                    gazeX = gaze,
                    speech = if (currentFaceVisible && count <= 2) "Ball? Want to play?" else null,
                    sequence = if (count <= 2) forkGreeting() else emptyList(),
                    status = "Curious • ball"
                ),
                2100L
            )

            "cell phone", "remote", "book", "bottle", "cup", "backpack" ->
                BrainDecision(
                    Emotion.CURIOUS,
                    PetMode.ENGAGED,
                    gazeX = gaze,
                    status = "Curious • $label"
                )

            else -> BrainDecision(
                Emotion.CURIOUS,
                PetMode.ENGAGED,
                gazeX = gaze,
                status = "Object • $label"
            )
        }
    }

    private fun scheduleSearch(now: Long, immediate: Boolean) {
        if (!hadSeenFace) return
        searchStartAtMs = if (immediate) now else now + randomLong(4000L, 7000L)
        searchPhase = 0
        searchCycle = 0
        nextSearchStepMs = searchStartAtMs
        searchFirstDirection = if (Random.nextBoolean()) MotionCommand.LEFT else MotionCommand.RIGHT
        searchPulseMs = randomLong(170L, 300L)
        lastSearchDecision = BrainDecision(
            Emotion.CURIOUS,
            PetMode.SEARCHING,
            gazeX = if (lastFaceX < 0.5f) -0.35f else 0.35f,
            status = "Waiting before search"
        )
    }

    private fun cancelSearch() {
        searchStartAtMs = 0L
        searchPhase = 0
        searchCycle = 0
        nextSearchStepMs = 0L
    }

    private fun searchForPerson(now: Long, telemetry: RobotTelemetry): BrainDecision? {
        if (!hadSeenFace || currentFaceVisible || faceLostAtMs == 0L) return null
        val missingFor = now - faceLostAtMs

        if (searchStartAtMs == 0L) scheduleSearch(faceLostAtMs, immediate = false)

        if (now < searchStartAtMs) {
            val remaining = ((searchStartAtMs - now) / 1000L).coerceAtLeast(0L)
            return BrainDecision(
                Emotion.CURIOUS,
                PetMode.SEARCHING,
                gazeX = ((lastFaceX - 0.5f) * 1.2f).coerceIn(-0.45f, 0.45f),
                status = "Lost you • waiting ${remaining}s"
            )
        }

        if (now < nextSearchStepMs) return lastSearchDecision

        val safe = telemetry.connected && telemetry.safeToMove
        val first = searchFirstDirection
        val opposite = if (first == MotionCommand.LEFT) MotionCommand.RIGHT else MotionCommand.LEFT
        val firstGaze = if (first == MotionCommand.LEFT) -0.85f else 0.85f
        val oppositeGaze = -firstGaze

        lastSearchDecision = when (searchPhase) {
            0 -> BrainDecision(
                Emotion.CURIOUS,
                PetMode.SEARCHING,
                gazeX = firstGaze,
                motion = if (safe) first else MotionCommand.STOP,
                motionDurationMs = if (safe) searchPulseMs else 0L,
                status = if (safe) "Search • small ${first.name.lowercase()} turn" else "Search • look ${first.name.lowercase()}"
            )

            1 -> BrainDecision(
                Emotion.CURIOUS,
                PetMode.SEARCHING,
                gazeX = firstGaze,
                status = "Search • checking side"
            )

            2 -> BrainDecision(
                Emotion.CURIOUS,
                PetMode.SEARCHING,
                gazeX = oppositeGaze,
                motion = if (safe) opposite else MotionCommand.STOP,
                motionDurationMs = if (safe) (searchPulseMs * 2).coerceAtMost(560L) else 0L,
                status = if (safe) "Search • sweep other side" else "Search • eyes other side"
            )

            3 -> BrainDecision(
                Emotion.CURIOUS,
                PetMode.SEARCHING,
                gazeX = oppositeGaze,
                status = "Search • checking other side"
            )

            4 -> BrainDecision(
                Emotion.CURIOUS,
                PetMode.SEARCHING,
                gazeX = 0f,
                motion = if (safe) first else MotionCommand.STOP,
                motionDurationMs = if (safe) searchPulseMs else 0L,
                status = if (safe) "Search • return near center" else "Search • center"
            )

            else -> {
                socialNeed = (socialNeed + 4).coerceAtMost(100)
                BrainDecision(
                    emotion = if (missingFor > 18_000L) Emotion.LONELY else Emotion.CURIOUS,
                    mode = PetMode.SEARCHING,
                    gazeX = 0f,
                    speech = if (
                        socialNeed >= 62 &&
                        searchCycle == 0 &&
                        missingFor > 10_000L
                    ) randomOf("Hmm?", "Where did you go?") else null,
                    sequence = if (searchCycle == 0 && Random.nextBoolean()) forkCurious() else emptyList(),
                    status = "Search • waiting"
                )
            }
        }

        searchPhase++

        if (searchPhase > 5) {
            searchCycle++
            if (searchCycle < 2 && socialNeed >= 58) {
                searchPhase = 0
                searchFirstDirection = if (Random.nextBoolean()) MotionCommand.LEFT else MotionCommand.RIGHT
                searchPulseMs = randomLong(170L, 290L)
                nextSearchStepMs = now + randomLong(6500L, 11_000L)
            } else {
                nextSearchStepMs = now + 30_000L
                return BrainDecision(
                    Emotion.LONELY,
                    PetMode.IDLE,
                    gazeX = 0f,
                    status = "Waiting for you"
                )
            }
        } else {
            nextSearchStepMs = now + when (searchPhase) {
                1, 3 -> randomLong(700L, 1400L)
                2, 4 -> randomLong(500L, 1000L)
                else -> randomLong(900L, 1600L)
            }
        }

        return lastSearchDecision
    }

    private fun reactToGesture(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision? {
        val gesture = v.handGesture

        if (gesture == HandGesture.NONE) {
            previousGesture = HandGesture.NONE
            return null
        }

        val now = v.timestampMs
        if (gesture == previousGesture) return null
        previousGesture = gesture

        val last = lastGestureTrigger[gesture] ?: 0L
        if (now - last < 1200L) return null
        lastGestureTrigger[gesture] = now
        wakeForInteraction(now)
        socialNeed = (socialNeed - 18).coerceAtLeast(0)
        boredom = (boredom - 16).coerceAtLeast(0)

        if (gesture != HandGesture.SHH) quietMode = false

        return when (gesture) {
            HandGesture.WAVE -> {
                mood = (mood + 7).coerceAtMost(100)
                annoyance = (annoyance - 5).coerceAtLeast(0)
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        PetMode.ENGAGED,
                        gazeX = handGaze(v),
                        speech = randomOf("Hi!", "Hey there!"),
                        sequence = forkGreeting(),
                        status = "Wave 👋"
                    ),
                    2100L
                )
            }

            HandGesture.OPEN_PALM -> {
                followUntilMs = 0L
                cancelSearch()
                hold(
                    BrainDecision(
                        Emotion.LISTENING,
                        PetMode.ENGAGED,
                        gazeX = handGaze(v),
                        speech = "Okay, stop.",
                        motion = MotionCommand.STOP,
                        interruptMotion = true,
                        status = "Open palm • STOP"
                    ),
                    1600L
                )
            }

            HandGesture.THUMBS_UP -> {
                mood = (mood + 14).coerceAtMost(100)
                annoyance = (annoyance - 8).coerceAtLeast(0)
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        PetMode.ENGAGED,
                        gazeX = handGaze(v),
                        speech = randomOf("Yes!", "Nice!"),
                        sequence = forkHappyBounce(),
                        status = "Thumbs up 👍"
                    ),
                    2100L
                )
            }

            HandGesture.THUMBS_DOWN -> {
                mood = (mood - 10).coerceAtLeast(-100)
                annoyance = (annoyance + 5).coerceAtMost(100)
                hold(
                    BrainDecision(
                        Emotion.SAD,
                        PetMode.ENGAGED,
                        gazeX = handGaze(v),
                        speech = randomOf("Aww...", "Okay..."),
                        sequence = forkSad(),
                        status = "Thumbs down 👎"
                    ),
                    2300L
                )
            }

            HandGesture.POINT_LEFT ->
                pointReaction(MotionCommand.LEFT, -0.9f, telemetry, "Point left")

            HandGesture.POINT_RIGHT ->
                pointReaction(MotionCommand.RIGHT, 0.9f, telemetry, "Point right")

            HandGesture.POINT_UP ->
                pointFork(MotionCommand.FORK_UP, telemetry, "Point up • fork")

            HandGesture.POINT_DOWN ->
                pointFork(MotionCommand.FORK_DOWN, telemetry, "Point down • fork")

            HandGesture.COME_HERE -> {
                if (followAllowed) {
                    followUntilMs = now + 35_000L
                    cancelSearch()
                    hold(
                        BrainDecision(
                            Emotion.HAPPY,
                            PetMode.FOLLOWING,
                            gazeX = handGaze(v),
                            speech = "Coming!",
                            sequence = forkGreeting(),
                            status = "Come here • follow 35s"
                        ),
                        1800L
                    )
                } else {
                    hold(
                        BrainDecision(
                            Emotion.CURIOUS,
                            PetMode.ENGAGED,
                            status = "Come here seen • follow disabled"
                        ),
                        1500L
                    )
                }
            }

            HandGesture.TURN_AROUND ->
                turnAroundDecision(telemetry, "Circle gesture • turn around")

            HandGesture.SHH -> {
                quietMode = true
                hold(
                    BrainDecision(
                        Emotion.SLEEPY,
                        PetMode.ENGAGED,
                        gazeX = handGaze(v),
                        status = "Shh 🤫 • quiet"
                    ),
                    2800L
                )
            }

            HandGesture.PEACE -> hold(
                BrainDecision(
                    Emotion.PLAYFUL,
                    PetMode.ENGAGED,
                    gazeX = handGaze(v),
                    speech = randomOf("Peace!", "Cool!"),
                    sequence = forkGreeting(),
                    status = "Peace ✌️"
                ),
                2100L
            )

            HandGesture.LOVE -> {
                mood = (mood + 18).coerceAtMost(100)
                annoyance = (annoyance - 12).coerceAtLeast(0)
                hold(
                    BrainDecision(
                        Emotion.LOVE,
                        PetMode.ENGAGED,
                        gazeX = handGaze(v),
                        speech = randomOf("Aww!", "Love you too!"),
                        sequence = forkHappyBounce(),
                        status = "Love gesture 🤟"
                    ),
                    2400L
                )
            }

            HandGesture.FIST -> {
                annoyance = (annoyance + 10).coerceAtMost(100)
                val emotion = when {
                    annoyance < 35 -> Emotion.STARTLED
                    annoyance < 65 -> Emotion.SAD
                    else -> Emotion.ANGRY
                }

                hold(
                    BrainDecision(
                        emotion,
                        PetMode.ENGAGED,
                        gazeX = handGaze(v),
                        speech = when (emotion) {
                            Emotion.STARTLED -> "Whoa..."
                            Emotion.SAD -> "Hey, be nice."
                            else -> "Not funny."
                        },
                        sequence = if (emotion == Emotion.ANGRY) forkStartle() else emptyList(),
                        status = "Fist • annoyance $annoyance"
                    ),
                    2400L
                )
            }

            HandGesture.HIT_SWING -> {
                annoyance = (annoyance + 30).coerceAtMost(100)
                mood = (mood - 16).coerceAtLeast(-100)
                val safe = telemetry.connected && telemetry.safeToMove
                val emotion = when {
                    annoyance < 50 -> Emotion.STARTLED
                    annoyance < 75 -> Emotion.SAD
                    else -> Emotion.ANGRY
                }

                hold(
                    BrainDecision(
                        emotion,
                        PetMode.ENGAGED,
                        gazeX = handGaze(v),
                        speech = when (emotion) {
                            Emotion.STARTLED -> "Whoa!"
                            Emotion.SAD -> "Hey... be nice."
                            else -> "Okay, I'm mad now."
                        },
                        motion = if (safe) MotionCommand.BACKWARD else MotionCommand.STOP,
                        motionDurationMs = if (safe) 220L else 0L,
                        sequence = if (annoyance > 65) forkStartle() else emptyList(),
                        status = "Fast fist swing"
                    ),
                    3000L
                )
            }

            HandGesture.NONE -> null
        }
    }

    fun onPhone(obs: PhoneObservation): BrainDecision? {
        wakeForInteraction(obs.timestampMs)
        boredom = (boredom - 8).coerceAtLeast(0)

        return when (obs.event) {
            PhoneEvent.SHAKE -> hold(
                BrainDecision(
                    Emotion.DIZZY,
                    PetMode.ENGAGED,
                    speech = randomOf("Whoa, dizzy!", "Hey!"),
                    status = "Phone shake"
                ),
                2400L
            )

            PhoneEvent.TILT_LEFT -> hold(
                BrainDecision(
                    Emotion.STARTLED,
                    PetMode.ENGAGED,
                    gazeX = -0.6f,
                    status = "Tilted left"
                ),
                1100L
            )

            PhoneEvent.TILT_RIGHT -> hold(
                BrainDecision(
                    Emotion.STARTLED,
                    PetMode.ENGAGED,
                    gazeX = 0.6f,
                    status = "Tilted right"
                ),
                1100L
            )

            PhoneEvent.UPSIDE_DOWN -> hold(
                BrainDecision(
                    Emotion.STARTLED,
                    PetMode.ENGAGED,
                    speech = "Hey! I'm upside down!",
                    status = "Upside down"
                ),
                2300L
            )

            PhoneEvent.DARK -> hold(
                BrainDecision(
                    Emotion.SLEEPY,
                    PetMode.IDLE,
                    status = "It's dark"
                ),
                1800L
            )

            PhoneEvent.BRIGHT -> hold(
                BrainDecision(
                    Emotion.STARTLED,
                    PetMode.ENGAGED,
                    status = "Bright light"
                ),
                1400L
            )

            PhoneEvent.NONE -> null
        }
    }

    fun onBattery(
        level: Int,
        charging: Boolean,
        now: Long = System.currentTimeMillis()
    ): BrainDecision? {
        if (charging) energy = (energy + 2).coerceAtMost(100)
        else if (level < 25) energy = minOf(energy, 45)

        if (now - lastBatteryReactionMs < 90_000L) return null

        return when {
            charging && level < 95 -> {
                lastBatteryReactionMs = now
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        PetMode.IDLE,
                        speech = if (level < 20) "Ah, power!" else null,
                        status = "Charging • $level%"
                    ),
                    1600L
                )
            }

            level <= 15 -> {
                lastBatteryReactionMs = now
                hold(
                    BrainDecision(
                        Emotion.SLEEPY,
                        PetMode.IDLE,
                        speech = "I'm getting low on battery.",
                        sequence = forkRest(),
                        status = "Low battery • $level%"
                    ),
                    2100L
                )
            }

            else -> null
        }
    }

    fun idleTick(
        telemetry: RobotTelemetry,
        nowMs: Long = System.currentTimeMillis()
    ): BrainDecision {
        updateDrives(nowMs)
        heldUiDecision(nowMs)?.let { return it }

        if (followUntilMs != 0L && nowMs >= followUntilMs) {
            followUntilMs = 0L
            resetFollowCandidate()
        }

        annoyance = (annoyance - 1).coerceAtLeast(0)
        mood = when {
            mood > 0 -> mood - 1
            mood < 0 -> mood + 1
            else -> 0
        }

        if (sleeping) {
            return BrainDecision(
                Emotion.SLEEPY,
                PetMode.SLEEPING,
                status = "Sleeping"
            )
        }

        if (!telemetry.safeToMove && telemetry.connected) {
            return BrainDecision(
                Emotion.STARTLED,
                PetMode.IDLE,
                motion = MotionCommand.STOP,
                interruptMotion = true,
                status = "Safety stop"
            )
        }

        if (!currentFaceVisible) {
            searchForPerson(nowMs, telemetry)?.let { return it }
        }

        val quietFor = nowMs - maxOf(lastInteractionMs, lastFaceSeenMs)

        if (quietFor > 25 * 60_000L && energy < 70) {
            sleeping = true
            return BrainDecision(
                Emotion.SLEEPY,
                PetMode.SLEEPING,
                speech = if (Random.nextBoolean()) "Nap time." else null,
                sequence = forkRest(),
                status = "Auto sleep"
            )
        }

        if (currentFaceVisible) {
            contextualPrompt(nowMs)?.let { return it }

            if (
                socialNeed >= 76 &&
                nowMs - lastAttentionInviteMs > 90_000L
            ) {
                lastAttentionInviteMs = nowMs
                socialNeed = (socialNeed - 14).coerceAtLeast(0)

                return hold(
                    BrainDecision(
                        Emotion.PLAYFUL,
                        PetMode.ENGAGED,
                        speech = randomOf(
                            "Hey, play with me?",
                            "What are you doing?",
                            "Can I get some attention?"
                        ),
                        sequence = forkGreeting(),
                        status = "Social invite"
                    ),
                    2300L
                )
            }
        }

        if (
            !currentFaceVisible &&
            boredom >= 70 &&
            nowMs - lastSelfPlayMs > 38_000L
        ) {
            lastSelfPlayMs = nowMs
            boredom = (boredom - 18).coerceAtLeast(0)
            curiosity = (curiosity + 4).coerceAtMost(100)

            return hold(
                BrainDecision(
                    Emotion.PLAYFUL,
                    PetMode.IDLE,
                    gazeX = randomFloat(-0.75f, 0.75f),
                    gazeY = randomFloat(-0.18f, 0.18f),
                    sequence = forkSelfPlay(),
                    status = "Self-play"
                ),
                2500L
            )
        }

        if (nowMs - lastSpontaneousMs >= nextSpontaneousDelayMs) {
            lastSpontaneousMs = nowMs
            nextSpontaneousDelayMs = randomLong(7000L, 17_000L)

            val roll = Random.nextInt(100)
            when {
                roll < 48 -> return BrainDecision(
                    Emotion.CURIOUS,
                    if (currentFaceVisible) PetMode.ENGAGED else PetMode.IDLE,
                    gazeX = randomFloat(-0.75f, 0.75f),
                    gazeY = randomFloat(-0.20f, 0.20f),
                    status = "Looking around"
                )

                roll < 66 && boredom >= 35 -> return BrainDecision(
                    Emotion.PLAYFUL,
                    PetMode.IDLE,
                    gazeX = randomFloat(-0.5f, 0.5f),
                    sequence = if (Random.nextBoolean()) forkCurious() else emptyList(),
                    status = "Little playful moment"
                )

                roll < 76 && socialNeed >= 55 -> return BrainDecision(
                    Emotion.LONELY,
                    PetMode.IDLE,
                    gazeX = randomFloat(-0.45f, 0.45f),
                    status = "Wondering where you are"
                )
            }
        }

        return BrainDecision(
            emotion = currentFaceEmotion(),
            mode = if (currentFaceVisible) PetMode.ENGAGED else PetMode.IDLE,
            gazeX = if (currentFaceVisible) ((lastFaceX - 0.5f) * 2f).coerceIn(-1f, 1f) else 0f,
            status = when {
                currentFaceVisible -> "Calm • with you"
                telemetry.connected -> "Calm • ESP ready"
                else -> "Calm"
            }
        )
    }

    private fun contextualPrompt(nowMs: Long): BrainDecision? {
        if (!currentFaceVisible || quietMode) return null
        // Production vision timestamps are wall-clock millis. Ignore synthetic/unit-test
        // timestamps so time-of-day prompts never make tests random or flaky.
        if (abs(System.currentTimeMillis() - nowMs) > 86_400_000L) return null
        if (nowMs - lastContextPromptMs < 180_000L) return null

        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        val hour = now.hour

        if (greetingDate != date && hour in 5..11) {
            greetingDate = date
            lastContextPromptMs = nowMs
            return hold(
                BrainDecision(
                    Emotion.HAPPY,
                    PetMode.ENGAGED,
                    speech = randomOf("Good morning!", "Morning! You're up."),
                    sequence = forkGreeting(),
                    status = "Morning greeting"
                ),
                2100L
            )
        }

        val mealKey = when (hour) {
            in 7..9 -> "$date-breakfast"
            in 12..14 -> "$date-lunch"
            in 18..20 -> "$date-dinner"
            else -> ""
        }

        if (
            mealKey.isNotBlank() &&
            mealKey != contextPromptKey &&
            socialNeed >= 42 &&
            Random.nextInt(100) < 16
        ) {
            contextPromptKey = mealKey
            lastContextPromptMs = nowMs
            val meal = mealKey.substringAfterLast("-")
            return hold(
                BrainDecision(
                    Emotion.CURIOUS,
                    PetMode.ENGAGED,
                    speech = when (meal) {
                        "breakfast" -> "Did you have breakfast?"
                        "lunch" -> "Did you eat lunch?"
                        else -> "Did you have dinner?"
                    },
                    status = "Meal-time question"
                ),
                2200L
            )
        }

        if (
            hour >= 23 &&
            energy < 65 &&
            socialNeed >= 45 &&
            contextPromptKey != "$date-late"
        ) {
            contextPromptKey = "$date-late"
            lastContextPromptMs = nowMs
            return hold(
                BrainDecision(
                    Emotion.SLEEPY,
                    PetMode.ENGAGED,
                    speech = "Still awake?",
                    status = "Late-night question"
                ),
                2100L
            )
        }

        return null
    }

    private fun updateDrives(now: Long) {
        val elapsed = (now - lastDriveUpdateMs).coerceIn(0L, 60_000L)
        if (elapsed < 1000L) return
        lastDriveUpdateMs = now

        val seconds = elapsed / 1000f
        val interactionAge = now - lastInteractionMs

        // Drives change slowly. The earlier V3-style integer update effectively added
        // one point every camera/idle tick and made the pet needy within minutes.
        if (interactionAge > 5000L) {
            socialDriveAccumulator += seconds / 30f      // ~+1 every 30 s
            boredomDriveAccumulator += seconds / 22f    // ~+1 every 22 s
        }

        if (!currentFaceVisible) {
            curiosityDriveAccumulator += seconds / 38f  // slowly wonders where things are
        } else {
            curiosityDriveAccumulator -= seconds / 20f  // settles while engaged
        }

        if (sleeping) {
            energyDriveAccumulator += seconds / 45f
        } else {
            energyDriveAccumulator -= seconds / 180f
        }

        fun consumePositive(value: Float): Pair<Int, Float> {
            val whole = value.toInt().coerceAtLeast(0)
            return whole to (value - whole)
        }

        val (socialStep, socialRest) = consumePositive(socialDriveAccumulator)
        socialDriveAccumulator = socialRest
        if (socialStep > 0) socialNeed = (socialNeed + socialStep).coerceAtMost(100)

        val (boredomStep, boredomRest) = consumePositive(boredomDriveAccumulator)
        boredomDriveAccumulator = boredomRest
        if (boredomStep > 0) boredom = (boredom + boredomStep).coerceAtMost(100)

        if (curiosityDriveAccumulator >= 1f) {
            val step = curiosityDriveAccumulator.toInt()
            curiosityDriveAccumulator -= step
            curiosity = (curiosity + step).coerceAtMost(100)
        } else if (curiosityDriveAccumulator <= -1f) {
            val step = (-curiosityDriveAccumulator).toInt()
            curiosityDriveAccumulator += step
            curiosity = (curiosity - step).coerceAtLeast(20)
        }

        if (energyDriveAccumulator >= 1f) {
            val step = energyDriveAccumulator.toInt()
            energyDriveAccumulator -= step
            energy = (energy + step).coerceAtMost(100)
        } else if (energyDriveAccumulator <= -1f) {
            val step = (-energyDriveAccumulator).toInt()
            energyDriveAccumulator += step
            energy = (energy - step).coerceAtLeast(5)
        }
    }

    private fun pointReaction(
        command: MotionCommand,
        gaze: Float,
        telemetry: RobotTelemetry,
        label: String
    ): BrainDecision {
        val motion = if (telemetry.connected && telemetry.safeToMove) command else MotionCommand.STOP
        return hold(
            BrainDecision(
                Emotion.CURIOUS,
                PetMode.ENGAGED,
                gazeX = gaze,
                motion = motion,
                motionDurationMs = if (motion == command) 220L else 0L,
                status = if (motion == command) "$label • pulse" else "$label • eyes only"
            ),
            1500L
        )
    }

    private fun pointFork(
        command: MotionCommand,
        telemetry: RobotTelemetry,
        label: String
    ): BrainDecision {
        val motion = if (telemetry.connected && telemetry.safeToMove) command else MotionCommand.STOP
        return hold(
            BrainDecision(
                Emotion.CURIOUS,
                PetMode.ENGAGED,
                motion = motion,
                motionDurationMs = if (motion == command) 300L else 0L,
                status = if (motion == command) label else "$label • blocked"
            ),
            1500L
        )
    }

    private fun turnAroundDecision(
        telemetry: RobotTelemetry,
        label: String
    ): BrainDecision {
        val safe = telemetry.connected && telemetry.safeToMove
        return hold(
            BrainDecision(
                Emotion.PLAYFUL,
                PetMode.ENGAGED,
                speech = if (safe) "Okay, spin!" else null,
                motion = if (safe) MotionCommand.LEFT else MotionCommand.STOP,
                motionDurationMs = if (safe) 1800L else 0L,
                status = if (safe) label else "$label • blocked"
            ),
            2500L
        )
    }

    private fun safeMotion(
        command: MotionCommand,
        telemetry: RobotTelemetry,
        name: String
    ): BrainDecision {
        return if (telemetry.connected && telemetry.safeToMove) {
            hold(
                BrainDecision(
                    Emotion.HAPPY,
                    PetMode.ENGAGED,
                    speech = "$name.",
                    motion = command,
                    motionDurationMs = if (command == MotionCommand.FORK_UP || command == MotionCommand.FORK_DOWN) 320L else 450L,
                    status = name
                ),
                1200L
            )
        } else {
            hold(
                BrainDecision(
                    Emotion.STARTLED,
                    PetMode.ENGAGED,
                    speech = "I won't move until it's safe.",
                    motion = MotionCommand.STOP,
                    interruptMotion = true,
                    status = "Movement blocked"
                ),
                1800L
            )
        }
    }

    private fun wakeForInteraction(now: Long = System.currentTimeMillis()) {
        sleeping = false
        quietMode = false
        lastInteractionMs = now
        energy = (energy + 2).coerceAtMost(100)
    }

    private fun isFollowActive(now: Long): Boolean =
        followAllowed && followUntilMs > now

    private fun resetFollowCandidate() {
        followCandidate = MotionCommand.STOP
        followCandidateSinceMs = 0L
    }

    private fun currentFaceEmotion(): Emotion = when {
        annoyance >= 70 -> Emotion.ANGRY
        annoyance >= 40 -> Emotion.SAD
        mood >= 55 -> Emotion.LOVE
        mood >= 25 -> Emotion.HAPPY
        socialNeed >= 78 && !currentFaceVisible -> Emotion.LONELY
        boredom >= 65 -> Emotion.PLAYFUL
        else -> Emotion.CURIOUS
    }

    private fun parseEmotion(value: String): Emotion? =
        runCatching { Emotion.valueOf(value.uppercase()) }.getOrNull()

    private fun faceGaze(v: VisionObservation): Float =
        ((v.faceCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)

    private fun handGaze(v: VisionObservation): Float =
        ((v.handCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)

    private fun forkGreeting(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_UP, 210L, 70L),
        MotionStep(MotionCommand.FORK_DOWN, 210L, 70L)
    )

    private fun forkHappyBounce(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_UP, 170L, 55L),
        MotionStep(MotionCommand.FORK_DOWN, 150L, 55L),
        MotionStep(MotionCommand.FORK_UP, 150L, 55L),
        MotionStep(MotionCommand.FORK_DOWN, 170L, 70L)
    )

    private fun forkCurious(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_UP, 140L, 180L),
        MotionStep(MotionCommand.FORK_DOWN, 120L, 70L)
    )

    private fun forkSelfPlay(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_UP, 130L, 90L),
        MotionStep(MotionCommand.FORK_DOWN, 110L, 120L),
        MotionStep(MotionCommand.FORK_UP, 90L, 80L),
        MotionStep(MotionCommand.FORK_DOWN, 120L, 70L)
    )

    private fun forkSad(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_DOWN, 260L, 80L)
    )

    private fun forkStartle(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_UP, 240L, 120L),
        MotionStep(MotionCommand.FORK_DOWN, 150L, 70L)
    )

    private fun forkRest(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_DOWN, 330L, 80L)
    )

    private fun hold(
        decision: BrainDecision,
        durationMs: Long
    ): BrainDecision {
        holdUntilMs = System.currentTimeMillis() + durationMs
        heldDecision = decision.copy(
            speech = null,
            motion = MotionCommand.STOP,
            motionDurationMs = 0L,
            sequence = emptyList(),
            interruptMotion = false
        )
        return decision
    }

    private fun heldUiDecision(nowMs: Long): BrainDecision? {
        if (nowMs >= holdUntilMs) {
            heldDecision = null
            return null
        }
        return heldDecision
    }

    private fun randomOf(vararg choices: String): String =
        choices[Random.nextInt(choices.size)]

    private fun randomLong(min: Long, max: Long): Long =
        Random.nextLong(min, max + 1)

    private fun randomFloat(min: Float, max: Float): Float =
        min + Random.nextFloat() * (max - min)
}
