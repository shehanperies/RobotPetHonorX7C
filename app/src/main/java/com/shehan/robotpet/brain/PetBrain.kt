package com.shehan.robotpet.brain

import kotlin.math.abs
import kotlin.random.Random

class PetBrain {
    private var sleeping = false
    private var followEnabled = false
    private var quietMode = false

    private var lastFaceSeenMs = 0L
    private var hadSeenFace = false
    private var lastInteractionMs = System.currentTimeMillis()

    private var annoyance = 0
    private var mood = 0

    private var previousGesture = HandGesture.NONE
    private val lastGestureTrigger = mutableMapOf<HandGesture, Long>()

    private var heldDecision: BrainDecision? = null
    private var holdUntilMs = 0L

    private var searchPhase = 0
    private var nextSearchStepMs = 0L
    private var lastSearchDecision = BrainDecision(Emotion.CURIOUS, status = "Searching")

    private var lastSmileMs = 0L
    private var lastWinkMs = 0L
    private var lastHeadGestureMs = 0L
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastHeadSampleMs = 0L

    private var lastObjectLabel: String? = null
    private var lastObjectReactionMs = 0L
    private var lastBatteryReactionMs = 0L

    private var lastSpontaneousMs = 0L

    fun setFollowEnabled(enabled: Boolean) {
        followEnabled = enabled
    }

    fun restoreMood(savedMood: Int, savedAnnoyance: Int) {
        mood = savedMood.coerceIn(-100, 100)
        annoyance = savedAnnoyance.coerceIn(0, 100)
    }

    fun moodSnapshot(): Pair<Int, Int> = mood to annoyance

    fun onTouch(): BrainDecision {
        sleeping = false
        quietMode = false
        lastInteractionMs = System.currentTimeMillis()
        annoyance = (annoyance - 18).coerceAtLeast(0)
        mood = (mood + 10).coerceAtMost(100)

        val line = when {
            annoyance > 55 -> "Okay... friends?"
            mood > 50 -> randomOf("Hehe!", "Hi!", "Again!")
            else -> randomOf("Hey!", "That tickles!", "Hi there!")
        }

        return hold(
            BrainDecision(
                emotion = Emotion.HAPPY,
                speech = line,
                sequence = if (Random.nextInt(3) == 0) forkWave() else emptyList(),
                status = "Poke • mood $mood"
            ),
            1800L
        )
    }

    fun onPetting(): BrainDecision {
        sleeping = false
        quietMode = false
        lastInteractionMs = System.currentTimeMillis()
        annoyance = (annoyance - 30).coerceAtLeast(0)
        mood = (mood + 18).coerceAtMost(100)

        return hold(
            BrainDecision(
                emotion = if (mood > 55) Emotion.LOVE else Emotion.HAPPY,
                speech = randomOf("Mmm, nice.", "I like that.", "Hehe."),
                sequence = forkWave(),
                status = "Petted • mood $mood"
            ),
            2200L
        )
    }

    fun onSpeech(text: String, telemetry: RobotTelemetry): BrainDecision {
        val q = text.trim().lowercase()
        sleeping = false
        quietMode = false
        lastInteractionMs = System.currentTimeMillis()

        return when {
            q.contains("stop") || q.contains("stay") || q.contains("nawath") ->
                hold(
                    BrainDecision(
                        Emotion.LISTENING,
                        speech = "Stopping.",
                        motion = MotionCommand.STOP,
                        status = "Voice • STOP"
                    ),
                    1200L
                )

            q.contains("sleep") -> {
                sleeping = true
                hold(
                    BrainDecision(
                        Emotion.SLEEPY,
                        speech = "Okay. Nap time.",
                        sequence = listOf(MotionStep(MotionCommand.FORK_DOWN, 350)),
                        status = "Voice • sleeping"
                    ),
                    2600L
                )
            }

            q.contains("wake") ->
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        speech = "I'm awake!",
                        sequence = forkWave(),
                        status = "Voice • awake"
                    ),
                    1700L
                )

            q.contains("don't follow") || q.contains("do not follow") || q.contains("stay here") -> {
                followEnabled = false
                hold(
                    BrainDecision(
                        Emotion.IDLE,
                        speech = "Okay, staying here.",
                        motion = MotionCommand.STOP,
                        status = "Follow OFF"
                    ),
                    1600L
                )
            }

            q.contains("come here") || q.contains("follow me") || q == "come" -> {
                followEnabled = true
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        speech = "Coming with you.",
                        status = "Follow ON"
                    ),
                    1600L
                )
            }

            q.contains("turn around") || q.contains("spin") ->
                turnAroundDecision(telemetry, "Voice • turn around")

            q.contains("forward") -> safeMotion(MotionCommand.FORWARD, telemetry, "Forward")
            q.contains("back") -> safeMotion(MotionCommand.BACKWARD, telemetry, "Backward")
            q.contains("left") -> safeMotion(MotionCommand.LEFT, telemetry, "Left")
            q.contains("right") -> safeMotion(MotionCommand.RIGHT, telemetry, "Right")
            q.contains("fork") && q.contains("up") -> safeMotion(MotionCommand.FORK_UP, telemetry, "Fork up")
            q.contains("fork") && q.contains("down") -> safeMotion(MotionCommand.FORK_DOWN, telemetry, "Fork down")

            q.contains("hello") || q.contains("hi ") || q == "hi" ->
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        speech = randomOf("Hello!", "Hi!", "Hey there!"),
                        sequence = forkWave(),
                        status = "Greeting"
                    ),
                    1700L
                )

            q.contains("who are you") || q.contains("your name") ->
                hold(
                    BrainDecision(
                        Emotion.CURIOUS,
                        speech = "I'm your little robot pet.",
                        status = "Chatting"
                    ),
                    1900L
                )

            else ->
                hold(
                    BrainDecision(
                        Emotion.CURIOUS,
                        speech = "I heard: $text",
                        status = "Voice heard"
                    ),
                    1500L
                )
        }
    }

    fun onVision(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision {
        reactToGesture(v, telemetry)?.let { return it }

        if (v.faceVisible) {
            val wasLost = hadSeenFace && lastFaceSeenMs > 0L &&
                v.timestampMs - lastFaceSeenMs > 4500L

            hadSeenFace = true
            lastFaceSeenMs = v.timestampMs
            searchPhase = 0
            nextSearchStepMs = 0L

            if (wasLost) {
                sleeping = false
                mood = (mood + 10).coerceAtMost(100)
                return hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        gazeX = faceGaze(v),
                        speech = randomOf("There you are!", "Found you!", "Hey, you're back!"),
                        sequence = forkWave(),
                        status = "Person found"
                    ),
                    2600L
                )
            }

            reactToFaceExpression(v)?.let { return it }
        }

        heldUiDecision(v.timestampMs)?.let { return it }

        if (sleeping) {
            if (v.faceVisible) {
                sleeping = false
                return hold(
                    BrainDecision(
                        Emotion.STARTLED,
                        gazeX = faceGaze(v),
                        speech = "Oh! Hi.",
                        status = "Woken by face"
                    ),
                    1600L
                )
            }
            return BrainDecision(Emotion.SLEEPY, status = "Sleeping")
        }

        if (v.faceVisible) {
            val gaze = faceGaze(v)

            if (followEnabled && telemetry.connected && telemetry.safeToMove) {
                val motion = when {
                    gaze < -0.35f -> MotionCommand.LEFT
                    gaze > 0.35f -> MotionCommand.RIGHT
                    v.faceAreaRatio < 0.035f -> MotionCommand.FORWARD
                    v.faceAreaRatio > 0.22f -> MotionCommand.BACKWARD
                    else -> MotionCommand.STOP
                }

                return BrainDecision(
                    emotion = currentFaceEmotion(),
                    gazeX = gaze,
                    motion = motion,
                    motionDurationMs = if (motion == MotionCommand.STOP) 0 else 280,
                    status = "Following person"
                )
            }

            return BrainDecision(
                emotion = currentFaceEmotion(),
                gazeX = gaze,
                status = if (quietMode) "Quietly watching" else "Watching you"
            )
        }

        searchForPerson(v.timestampMs, telemetry)?.let { return it }
        reactToObject(v)?.let { return it }

        return idleTick(telemetry, v.timestampMs)
    }

    private fun reactToFaceExpression(v: VisionObservation): BrainDecision? {
        val now = v.timestampMs
        val gaze = faceGaze(v)

        if (v.smileProbability >= 0.78f && now - lastSmileMs > 7000L) {
            lastSmileMs = now
            mood = (mood + 8).coerceAtMost(100)
            annoyance = (annoyance - 5).coerceAtLeast(0)
            return hold(
                BrainDecision(
                    Emotion.HAPPY,
                    gazeX = gaze,
                    speech = randomOf("Nice smile!", "Hehe, you look happy."),
                    status = "Smile detected"
                ),
                1900L
            )
        }

        val wink = (
            v.leftEyeOpenProbability in 0f..0.28f &&
                v.rightEyeOpenProbability > 0.68f
            ) || (
            v.rightEyeOpenProbability in 0f..0.28f &&
                v.leftEyeOpenProbability > 0.68f
            )

        if (wink && now - lastWinkMs > 7000L) {
            lastWinkMs = now
            return hold(
                BrainDecision(
                    Emotion.PLAYFUL,
                    gazeX = gaze,
                    speech = randomOf("I saw that.", "Wink wink!"),
                    status = "Wink detected"
                ),
                1900L
            )
        }

        if (lastHeadSampleMs > 0L && now - lastHeadSampleMs < 700L &&
            now - lastHeadGestureMs > 5000L
        ) {
            val yawCross = abs(v.headEulerY - lastYaw) > 28f &&
                abs(v.headEulerY) > 12f &&
                abs(lastYaw) > 12f &&
                v.headEulerY * lastYaw < 0f

            val pitchCross = abs(v.headEulerX - lastPitch) > 22f &&
                abs(v.headEulerX) > 9f &&
                abs(lastPitch) > 9f &&
                v.headEulerX * lastPitch < 0f

            if (yawCross) {
                lastHeadGestureMs = now
                lastYaw = v.headEulerY
                lastPitch = v.headEulerX
                lastHeadSampleMs = now
                return hold(
                    BrainDecision(
                        Emotion.CURIOUS,
                        gazeX = gaze,
                        speech = "No?",
                        status = "Head shake"
                    ),
                    1500L
                )
            }

            if (pitchCross) {
                lastHeadGestureMs = now
                lastYaw = v.headEulerY
                lastPitch = v.headEulerX
                lastHeadSampleMs = now
                return hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        gazeX = gaze,
                        speech = "Okay!",
                        status = "Head nod"
                    ),
                    1500L
                )
            }
        }

        lastYaw = v.headEulerY
        lastPitch = v.headEulerX
        lastHeadSampleMs = now
        return null
    }

    private fun reactToObject(v: VisionObservation): BrainDecision? {
        val label = v.objectLabel?.lowercase() ?: return null
        if (v.objectConfidence < 0.42f) return null

        val now = v.timestampMs
        val changed = label != lastObjectLabel
        if (!changed && now - lastObjectReactionMs < 12000L) return null

        lastObjectLabel = label
        lastObjectReactionMs = now

        val gaze = ((v.objectCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)

        return when (label) {
            "cat", "dog" -> hold(
                BrainDecision(
                    Emotion.HAPPY,
                    gazeX = gaze,
                    speech = "Oh! A $label!",
                    status = "Object • $label ${(v.objectConfidence * 100).toInt()}%"
                ),
                2200L
            )

            "cell phone", "remote", "book", "bottle", "cup", "sports ball", "backpack" ->
                hold(
                    BrainDecision(
                        Emotion.CURIOUS,
                        gazeX = gaze,
                        status = "Curious • $label ${(v.objectConfidence * 100).toInt()}%"
                    ),
                    1800L
                )

            "person" -> BrainDecision(
                Emotion.CURIOUS,
                gazeX = gaze,
                status = "Person-shaped object"
            )

            else -> BrainDecision(
                Emotion.CURIOUS,
                gazeX = gaze,
                status = "Object • $label ${(v.objectConfidence * 100).toInt()}%"
            )
        }
    }

    private fun searchForPerson(
        now: Long,
        telemetry: RobotTelemetry
    ): BrainDecision? {
        if (!hadSeenFace || lastFaceSeenMs == 0L) return null
        val missingFor = now - lastFaceSeenMs
        if (missingFor < 3500L) return null

        if (now < nextSearchStepMs) return lastSearchDecision

        val safe = telemetry.connected && telemetry.safeToMove
        lastSearchDecision = when (searchPhase % 7) {
            0 -> BrainDecision(
                Emotion.CURIOUS,
                gazeX = -0.85f,
                status = "Looking for you • left"
            )

            1 -> BrainDecision(
                Emotion.CURIOUS,
                gazeX = 0.85f,
                status = "Looking for you • right"
            )

            2 -> BrainDecision(
                Emotion.CURIOUS,
                gazeX = -0.55f,
                motion = if (safe) MotionCommand.LEFT else MotionCommand.STOP,
                motionDurationMs = if (safe) 380 else 0,
                status = "Searching • turn left"
            )

            3 -> BrainDecision(
                Emotion.CURIOUS,
                gazeX = 0.55f,
                motion = if (safe) MotionCommand.RIGHT else MotionCommand.STOP,
                motionDurationMs = if (safe) 720 else 0,
                status = "Searching • turn right"
            )

            4 -> BrainDecision(
                Emotion.CURIOUS,
                motion = if (safe) MotionCommand.FORWARD else MotionCommand.STOP,
                motionDurationMs = if (safe) 300 else 0,
                status = "Searching • peek forward"
            )

            5 -> hold(
                BrainDecision(
                    Emotion.LONELY,
                    speech = if (missingFor > 12000L) "Where did you go?" else null,
                    status = "I can't see you"
                ),
                1800L
            )

            else -> BrainDecision(
                Emotion.LONELY,
                gazeX = 0f,
                status = "Waiting for you"
            )
        }

        searchPhase++
        nextSearchStepMs = now + 1600L
        return lastSearchDecision
    }

    private fun reactToGesture(
        v: VisionObservation,
        telemetry: RobotTelemetry
    ): BrainDecision? {
        val gesture = v.handGesture

        if (gesture == HandGesture.NONE) {
            previousGesture = HandGesture.NONE
            return null
        }

        val now = v.timestampMs
        if (gesture == previousGesture) return null
        previousGesture = gesture

        val last = lastGestureTrigger[gesture] ?: 0L
        if (now - last < 900L) return null
        lastGestureTrigger[gesture] = now
        lastInteractionMs = now

        if (gesture != HandGesture.SHH) {
            sleeping = false
            quietMode = false
        }

        return when (gesture) {
            HandGesture.WAVE -> {
                mood = (mood + 8).coerceAtMost(100)
                annoyance = (annoyance - 5).coerceAtLeast(0)
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        gazeX = handGaze(v),
                        speech = randomOf("Hi!", "Hey there!", "I see you!"),
                        sequence = forkWave(),
                        status = "Wave 👋"
                    ),
                    2200L
                )
            }

            HandGesture.OPEN_PALM -> hold(
                BrainDecision(
                    Emotion.LISTENING,
                    gazeX = handGaze(v),
                    speech = "Okay, stop.",
                    motion = MotionCommand.STOP,
                    status = "Open palm • STOP"
                ),
                1600L
            )

            HandGesture.THUMBS_UP -> {
                mood = (mood + 14).coerceAtMost(100)
                annoyance = (annoyance - 8).coerceAtLeast(0)
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        gazeX = handGaze(v),
                        speech = randomOf("Yes!", "Nice!", "I like that!"),
                        sequence = forkBounce(),
                        status = "Thumbs up 👍"
                    ),
                    2200L
                )
            }

            HandGesture.THUMBS_DOWN -> {
                mood = (mood - 10).coerceAtLeast(-100)
                annoyance = (annoyance + 5).coerceAtMost(100)
                hold(
                    BrainDecision(
                        Emotion.SAD,
                        gazeX = handGaze(v),
                        speech = randomOf("Aww...", "You don't like it?"),
                        sequence = listOf(MotionStep(MotionCommand.FORK_DOWN, 280)),
                        status = "Thumbs down 👎"
                    ),
                    2500L
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
                followEnabled = true
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        gazeX = handGaze(v),
                        speech = "Okay, I'm coming.",
                        status = "Come here • follow ON"
                    ),
                    2400L
                )
            }

            HandGesture.TURN_AROUND ->
                turnAroundDecision(telemetry, "Circle gesture • turn around")

            HandGesture.SHH -> {
                quietMode = true
                hold(
                    BrainDecision(
                        Emotion.SLEEPY,
                        gazeX = handGaze(v),
                        status = "Shh 🤫 • quiet"
                    ),
                    3200L
                )
            }

            HandGesture.PEACE -> {
                mood = (mood + 10).coerceAtMost(100)
                hold(
                    BrainDecision(
                        Emotion.PLAYFUL,
                        gazeX = handGaze(v),
                        speech = randomOf("Peace!", "Cool!", "Hehe, nice!"),
                        sequence = forkWave(),
                        status = "Peace ✌️"
                    ),
                    2300L
                )
            }

            HandGesture.LOVE -> {
                mood = (mood + 20).coerceAtMost(100)
                annoyance = (annoyance - 12).coerceAtLeast(0)
                hold(
                    BrainDecision(
                        Emotion.LOVE,
                        gazeX = handGaze(v),
                        speech = randomOf("Aww!", "Love you too!", "Hehe!"),
                        sequence = forkBounce(),
                        status = "Love gesture 🤟"
                    ),
                    2600L
                )
            }

            HandGesture.FIST -> {
                annoyance = (annoyance + 14).coerceAtMost(100)
                val emotion = when {
                    annoyance < 35 -> Emotion.STARTLED
                    annoyance < 65 -> Emotion.SAD
                    else -> Emotion.ANGRY
                }

                hold(
                    BrainDecision(
                        emotion,
                        gazeX = handGaze(v),
                        speech = when (emotion) {
                            Emotion.STARTLED -> "Whoa..."
                            Emotion.SAD -> "Hey, be nice."
                            else -> "Not funny."
                        },
                        sequence = if (emotion == Emotion.ANGRY) {
                            listOf(MotionStep(MotionCommand.FORK_UP, 300))
                        } else emptyList(),
                        status = "Fist • annoyance $annoyance"
                    ),
                    2600L
                )
            }

            HandGesture.HIT_SWING -> {
                annoyance = (annoyance + 32).coerceAtMost(100)
                mood = (mood - 18).coerceAtLeast(-100)

                val emotion = when {
                    annoyance < 45 -> Emotion.STARTLED
                    annoyance < 72 -> Emotion.SAD
                    annoyance < 92 -> Emotion.ANGRY
                    else -> Emotion.PLAYFUL
                }

                val safe = telemetry.connected && telemetry.safeToMove

                hold(
                    BrainDecision(
                        emotion = emotion,
                        gazeX = handGaze(v),
                        speech = when (emotion) {
                            Emotion.STARTLED -> "Whoa!"
                            Emotion.SAD -> "Hey... that wasn't nice."
                            Emotion.ANGRY -> "Okay, I'm mad now."
                            else -> "Oh, you want a challenge?"
                        },
                        motion = if (safe) MotionCommand.BACKWARD else MotionCommand.STOP,
                        motionDurationMs = if (safe) 350 else 0,
                        sequence = if (annoyance > 70) {
                            listOf(MotionStep(MotionCommand.FORK_UP, 260))
                        } else emptyList(),
                        status = "Fast fist swing • annoyance $annoyance"
                    ),
                    3300L
                )
            }

            HandGesture.NONE -> null
        }
    }

    fun onPhone(obs: PhoneObservation): BrainDecision? {
        val now = obs.timestampMs
        lastInteractionMs = now

        return when (obs.event) {
            PhoneEvent.SHAKE -> hold(
                BrainDecision(
                    Emotion.DIZZY,
                    speech = randomOf("Whoa, dizzy!", "Hey! I'm spinning in here!"),
                    status = "Phone shake"
                ),
                2600L
            )

            PhoneEvent.TILT_LEFT -> hold(
                BrainDecision(
                    Emotion.STARTLED,
                    gazeX = -0.65f,
                    status = "Tilted left"
                ),
                1300L
            )

            PhoneEvent.TILT_RIGHT -> hold(
                BrainDecision(
                    Emotion.STARTLED,
                    gazeX = 0.65f,
                    status = "Tilted right"
                ),
                1300L
            )

            PhoneEvent.UPSIDE_DOWN -> hold(
                BrainDecision(
                    Emotion.STARTLED,
                    speech = "Hey! I'm upside down!",
                    status = "Upside down"
                ),
                2600L
            )

            PhoneEvent.DARK -> hold(
                BrainDecision(
                    Emotion.SLEEPY,
                    status = "It's dark"
                ),
                2200L
            )

            PhoneEvent.BRIGHT -> hold(
                BrainDecision(
                    Emotion.STARTLED,
                    status = "Bright light"
                ),
                1600L
            )

            PhoneEvent.NONE -> null
        }
    }

    fun onBattery(level: Int, charging: Boolean, now: Long = System.currentTimeMillis()): BrainDecision? {
        if (now - lastBatteryReactionMs < 60000L) return null

        return when {
            charging && level < 95 -> {
                lastBatteryReactionMs = now
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        speech = if (level < 20) "Ah, power!" else null,
                        status = "Charging • $level%"
                    ),
                    1800L
                )
            }

            level <= 15 -> {
                lastBatteryReactionMs = now
                hold(
                    BrainDecision(
                        Emotion.SLEEPY,
                        speech = "I'm getting low on battery.",
                        status = "Low battery • $level%"
                    ),
                    2200L
                )
            }

            else -> null
        }
    }

    fun idleTick(
        telemetry: RobotTelemetry,
        nowMs: Long = System.currentTimeMillis()
    ): BrainDecision {
        heldUiDecision(nowMs)?.let { return it }

        annoyance = (annoyance - 1).coerceAtLeast(0)
        mood = when {
            mood > 0 -> mood - 1
            mood < 0 -> mood + 1
            else -> 0
        }

        if (sleeping) {
            return BrainDecision(Emotion.SLEEPY, status = "Sleeping")
        }

        if (!telemetry.safeToMove && telemetry.connected) {
            return BrainDecision(
                Emotion.STARTLED,
                motion = MotionCommand.STOP,
                status = "Safety stop"
            )
        }

        val quietFor = nowMs - maxOf(lastInteractionMs, lastFaceSeenMs)

        if (quietFor > 120_000L) {
            sleeping = true
            return BrainDecision(
                Emotion.SLEEPY,
                speech = "I'm getting sleepy.",
                sequence = listOf(MotionStep(MotionCommand.FORK_DOWN, 350)),
                status = "Auto sleep"
            )
        }

        if (nowMs - lastSpontaneousMs > 9000L) {
            lastSpontaneousMs = nowMs

            when (Random.nextInt(100)) {
                in 0..14 -> return BrainDecision(
                    Emotion.CURIOUS,
                    gazeX = Random.nextFloat() * 1.4f - 0.7f,
                    status = "Looking around"
                )

                in 15..22 -> return BrainDecision(
                    Emotion.PLAYFUL,
                    sequence = if (telemetry.connected && telemetry.safeToMove) forkWave() else emptyList(),
                    status = "Little stretch"
                )

                in 23..28 -> return BrainDecision(
                    Emotion.LONELY,
                    speech = if (quietFor > 30000L) "Hmm..." else null,
                    status = "A little bored"
                )
            }
        }

        val gaze = if (Random.nextInt(5) == 0) {
            Random.nextFloat() * 1.4f - 0.7f
        } else {
            0f
        }

        return BrainDecision(
            emotion = if (abs(gaze) > 0.1f) Emotion.CURIOUS else currentFaceEmotion(),
            gazeX = gaze,
            status = if (telemetry.connected) "Ready" else "Face + gesture mode"
        )
    }

    private fun pointReaction(
        command: MotionCommand,
        gaze: Float,
        telemetry: RobotTelemetry,
        label: String
    ): BrainDecision {
        val motion = if (telemetry.connected && telemetry.safeToMove) {
            command
        } else MotionCommand.STOP

        return hold(
            BrainDecision(
                Emotion.CURIOUS,
                gazeX = gaze,
                motion = motion,
                motionDurationMs = if (motion == command) 350 else 0,
                status = if (motion == command) "$label • turning" else "$label • looking"
            ),
            1700L
        )
    }

    private fun pointFork(
        command: MotionCommand,
        telemetry: RobotTelemetry,
        label: String
    ): BrainDecision {
        val motion = if (telemetry.connected && telemetry.safeToMove) {
            command
        } else MotionCommand.STOP

        return hold(
            BrainDecision(
                Emotion.CURIOUS,
                motion = motion,
                motionDurationMs = if (motion == command) 320 else 0,
                status = if (motion == command) label else "$label • blocked"
            ),
            1600L
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
                speech = if (safe) "Okay, turning around!" else null,
                motion = if (safe) MotionCommand.LEFT else MotionCommand.STOP,
                motionDurationMs = if (safe) 2100L else 0L,
                status = if (safe) label else "$label • movement blocked"
            ),
            2600L
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
                    speech = "$name.",
                    motion = command,
                    motionDurationMs = 500,
                    status = name
                ),
                1200L
            )
        } else {
            hold(
                BrainDecision(
                    Emotion.STARTLED,
                    speech = "I won't move until it's safe.",
                    motion = MotionCommand.STOP,
                    status = "Movement blocked"
                ),
                1800L
            )
        }
    }

    private fun currentFaceEmotion(): Emotion = when {
        annoyance >= 70 -> Emotion.ANGRY
        annoyance >= 40 -> Emotion.SAD
        mood >= 55 -> Emotion.LOVE
        mood >= 25 -> Emotion.HAPPY
        else -> Emotion.CURIOUS
    }

    private fun faceGaze(v: VisionObservation): Float =
        ((v.faceCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)

    private fun handGaze(v: VisionObservation): Float =
        ((v.handCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)

    private fun forkWave(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_UP, 220),
        MotionStep(MotionCommand.FORK_DOWN, 220)
    )

    private fun forkBounce(): List<MotionStep> = listOf(
        MotionStep(MotionCommand.FORK_UP, 180),
        MotionStep(MotionCommand.FORK_DOWN, 180),
        MotionStep(MotionCommand.FORK_UP, 150),
        MotionStep(MotionCommand.FORK_DOWN, 150)
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
            sequence = emptyList()
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
}
