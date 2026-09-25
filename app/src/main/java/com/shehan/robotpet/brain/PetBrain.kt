package com.shehan.robotpet.brain

import kotlin.math.abs
import kotlin.random.Random

class PetBrain {
    private var sleeping = false
    private var followEnabled = false
    private var quietMode = false
    private var lastFaceSeenMs = 0L
    private var lastInteractionMs = System.currentTimeMillis()
    private var annoyance = 0
    private var mood = 0
    private var previousGesture = HandGesture.NONE
    private val lastGestureTrigger = mutableMapOf<HandGesture, Long>()
    private var heldDecision: BrainDecision? = null
    private var holdUntilMs = 0L

    fun setFollowEnabled(enabled: Boolean) {
        followEnabled = enabled
    }

    fun onTouch(): BrainDecision {
        sleeping = false
        quietMode = false
        lastInteractionMs = System.currentTimeMillis()
        annoyance = (annoyance - 22).coerceAtLeast(0)
        mood = (mood + 12).coerceAtMost(100)
        val line = if (annoyance > 45) {
            "Okay... friends?"
        } else {
            randomOf("Hey!", "Hehe!", "Hi there!", "That tickles!")
        }
        return hold(
            BrainDecision(
                Emotion.HAPPY,
                speech = line,
                status = "Petted • mood $mood"
            ),
            1800L
        )
    }

    fun onSpeech(text: String, telemetry: RobotTelemetry): BrainDecision {
        val q = text.trim().lowercase()
        sleeping = false
        quietMode = false
        lastInteractionMs = System.currentTimeMillis()
        return when {
            q.contains("stop") || q.contains("nawath") -> hold(
                BrainDecision(
                    Emotion.LISTENING,
                    speech = "Stopping.",
                    motion = MotionCommand.STOP,
                    status = "Stopped"
                ),
                1200L
            )
            q.contains("sleep") -> {
                sleeping = true
                hold(
                    BrainDecision(
                        Emotion.SLEEPY,
                        speech = "Okay. Nap time.",
                        status = "Sleeping"
                    ),
                    2500L
                )
            }
            q.contains("wake") -> hold(
                BrainDecision(
                    Emotion.HAPPY,
                    speech = "I'm awake!",
                    status = "Awake"
                ),
                1500L
            )
            q.contains("don't follow") || q.contains("do not follow") -> {
                followEnabled = false
                hold(
                    BrainDecision(
                        Emotion.IDLE,
                        speech = "Okay, staying here.",
                        motion = MotionCommand.STOP,
                        status = "Follow disabled"
                    ),
                    1500L
                )
            }
            q.contains("follow") -> {
                followEnabled = true
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        speech = "I'll follow when it is safe.",
                        status = "Follow enabled"
                    ),
                    1500L
                )
            }
            q.contains("forward") -> safeMotion(MotionCommand.FORWARD, telemetry, "Forward")
            q.contains("back") -> safeMotion(MotionCommand.BACKWARD, telemetry, "Backward")
            q.contains("left") -> safeMotion(MotionCommand.LEFT, telemetry, "Left")
            q.contains("right") -> safeMotion(MotionCommand.RIGHT, telemetry, "Right")
            q.contains("fork") && q.contains("up") -> safeMotion(MotionCommand.FORK_UP, telemetry, "Fork up")
            q.contains("fork") && q.contains("down") -> safeMotion(MotionCommand.FORK_DOWN, telemetry, "Fork down")
            q.contains("hello") || q.contains("hi ") || q == "hi" -> hold(
                BrainDecision(
                    Emotion.HAPPY,
                    speech = randomOf("Hello!", "Hi! I can see and hear you."),
                    status = "Greeting"
                ),
                1600L
            )
            q.contains("who are you") || q.contains("your name") -> hold(
                BrainDecision(
                    Emotion.CURIOUS,
                    speech = "I'm your little robot pet.",
                    status = "Chatting"
                ),
                1800L
            )
            else -> hold(
                BrainDecision(
                    Emotion.CURIOUS,
                    speech = "I heard: $text",
                    status = "Listening"
                ),
                1400L
            )
        }
    }

    fun onVision(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision {
        val gestureDecision = reactToGesture(v, telemetry)
        if (gestureDecision != null) return gestureDecision

        heldUiDecision(v.timestampMs)?.let { return it }

        if (sleeping) {
            return BrainDecision(Emotion.SLEEPY, status = "Sleeping")
        }

        if (v.faceVisible) {
            lastFaceSeenMs = v.timestampMs
            val gaze = ((v.faceCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)

            if (followEnabled && telemetry.connected && telemetry.safeToMove) {
                val turn = when {
                    gaze < -0.35f -> MotionCommand.LEFT
                    gaze > 0.35f -> MotionCommand.RIGHT
                    v.faceAreaRatio < 0.035f -> MotionCommand.FORWARD
                    v.faceAreaRatio > 0.22f -> MotionCommand.BACKWARD
                    else -> MotionCommand.STOP
                }
                return BrainDecision(
                    currentFaceEmotion(),
                    gazeX = gaze,
                    motion = turn,
                    motionDurationMs = if (turn == MotionCommand.STOP) 0 else 300,
                    status = "Following face"
                )
            }

            return BrainDecision(
                currentFaceEmotion(),
                gazeX = gaze,
                status = if (quietMode) "Quietly watching you" else "Watching you"
            )
        }

        if (v.objectCount > 0) {
            val gaze = ((v.objectCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)
            return BrainDecision(
                Emotion.CURIOUS,
                gazeX = gaze,
                status = v.objectLabel?.let { "Curious about $it" } ?: "Curious"
            )
        }

        return idleTick(telemetry, v.timestampMs)
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
                        status = "Wave detected 👋"
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
                1700L
            )

            HandGesture.THUMBS_UP -> {
                mood = (mood + 14).coerceAtMost(100)
                annoyance = (annoyance - 8).coerceAtLeast(0)
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        gazeX = handGaze(v),
                        speech = randomOf("Yes!", "Nice!", "I like that!"),
                        status = "Thumbs up 👍"
                    ),
                    2300L
                )
            }

            HandGesture.THUMBS_DOWN -> {
                mood = (mood - 10).coerceAtLeast(-100)
                hold(
                    BrainDecision(
                        Emotion.SAD,
                        gazeX = handGaze(v),
                        speech = randomOf("Aww...", "You don't like it?"),
                        status = "Thumbs down 👎"
                    ),
                    2600L
                )
            }

            HandGesture.POINT_LEFT -> pointReaction(
                MotionCommand.LEFT,
                -0.9f,
                telemetry,
                "Point left"
            )

            HandGesture.POINT_RIGHT -> pointReaction(
                MotionCommand.RIGHT,
                0.9f,
                telemetry,
                "Point right"
            )

            HandGesture.POINT_UP -> hold(
                BrainDecision(
                    Emotion.CURIOUS,
                    gazeX = handGaze(v),
                    gazeY = -0.55f,
                    status = "Pointing up"
                ),
                1400L
            )

            HandGesture.COME_HERE -> {
                followEnabled = true
                hold(
                    BrainDecision(
                        Emotion.HAPPY,
                        gazeX = handGaze(v),
                        speech = "Okay, I'm coming with you.",
                        status = "Come here • follow ON"
                    ),
                    2600L
                )
            }

            HandGesture.SHH -> {
                quietMode = true
                hold(
                    BrainDecision(
                        Emotion.SLEEPY,
                        gazeX = handGaze(v),
                        status = "Shh 🤫 • quiet mode"
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
                        status = "Peace sign ✌️"
                    ),
                    2400L
                )
            }

            HandGesture.FIST -> {
                annoyance = (annoyance + 14).coerceAtMost(100)
                val emotion = when {
                    annoyance < 35 -> Emotion.STARTLED
                    annoyance < 65 -> Emotion.SAD
                    else -> Emotion.ANGRY
                }
                val speech = when (emotion) {
                    Emotion.STARTLED -> "Whoa..."
                    Emotion.SAD -> "Hey, be nice."
                    else -> "Not funny."
                }
                hold(
                    BrainDecision(
                        emotion,
                        gazeX = handGaze(v),
                        speech = speech,
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

                val speech = when (emotion) {
                    Emotion.STARTLED -> "Whoa!"
                    Emotion.SAD -> "Hey... that wasn't nice."
                    Emotion.ANGRY -> "Okay, I'm mad now."
                    else -> "Oh, you want a challenge?"
                }

                val motion = if (telemetry.connected && telemetry.safeToMove) {
                    MotionCommand.BACKWARD
                } else {
                    MotionCommand.STOP
                }

                hold(
                    BrainDecision(
                        emotion,
                        gazeX = handGaze(v),
                        speech = speech,
                        motion = motion,
                        motionDurationMs = if (motion == MotionCommand.BACKWARD) 350 else 0,
                        status = "Fast fist swing • annoyance $annoyance"
                    ),
                    3300L
                )
            }

            HandGesture.NONE -> null
        }
    }

    private fun pointReaction(
        command: MotionCommand,
        gaze: Float,
        telemetry: RobotTelemetry,
        label: String
    ): BrainDecision {
        val motion = if (telemetry.connected && telemetry.safeToMove) {
            command
        } else {
            MotionCommand.STOP
        }

        return hold(
            BrainDecision(
                Emotion.CURIOUS,
                gazeX = gaze,
                speech = if (motion == command) label else null,
                motion = motion,
                motionDurationMs = if (motion == command) 350 else 0,
                status = if (motion == command) {
                    "$label • turning"
                } else {
                    "$label • looking"
                }
            ),
            1800L
        )
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
                status = "Obstacle / safety stop"
            )
        }

        val quietFor = nowMs - maxOf(lastInteractionMs, lastFaceSeenMs)

        if (quietFor > 90_000L) {
            sleeping = true
            return BrainDecision(
                Emotion.SLEEPY,
                speech = "I'm getting sleepy.",
                status = "Auto sleep"
            )
        }

        val gaze = if (Random.nextInt(5) == 0) {
            Random.nextFloat() * 1.4f - 0.7f
        } else {
            0f
        }

        return BrainDecision(
            if (abs(gaze) > 0.1f) Emotion.CURIOUS else currentFaceEmotion(),
            gazeX = gaze,
            status = if (telemetry.connected) {
                "Ready"
            } else {
                "Face + gesture mode"
            }
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
                    speech = "I won't move until my safety controller says it's clear.",
                    motion = MotionCommand.STOP,
                    status = "Movement blocked"
                ),
                1800L
            )
        }
    }

    private fun currentFaceEmotion(): Emotion {
        return when {
            annoyance >= 70 -> Emotion.ANGRY
            annoyance >= 40 -> Emotion.SAD
            mood >= 35 -> Emotion.HAPPY
            else -> Emotion.CURIOUS
        }
    }

    private fun handGaze(v: VisionObservation): Float {
        return ((v.handCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)
    }

    private fun hold(
        decision: BrainDecision,
        durationMs: Long
    ): BrainDecision {
        holdUntilMs = System.currentTimeMillis() + durationMs
        heldDecision = decision.copy(
            speech = null,
            motion = MotionCommand.STOP,
            motionDurationMs = 0L
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

    private fun randomOf(vararg choices: String): String {
        return choices[Random.nextInt(choices.size)]
    }
}
