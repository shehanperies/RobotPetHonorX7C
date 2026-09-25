package com.shehan.robotpet.brain

import kotlin.math.abs
import kotlin.random.Random

class PetBrain {
    private var sleeping = false
    private var followEnabled = false
    private var lastFaceSeenMs = 0L
    private var lastInteractionMs = System.currentTimeMillis()

    fun setFollowEnabled(enabled: Boolean) { followEnabled = enabled }

    fun onTouch(): BrainDecision {
        sleeping = false
        lastInteractionMs = System.currentTimeMillis()
        return BrainDecision(Emotion.HAPPY, speech = randomOf("Hey!", "Hehe!", "Hi there!"), status = "Petted")
    }

    fun onSpeech(text: String, telemetry: RobotTelemetry): BrainDecision {
        val q = text.trim().lowercase()
        sleeping = false
        lastInteractionMs = System.currentTimeMillis()
        return when {
            q.contains("stop") || q.contains("nawath") -> BrainDecision(Emotion.LISTENING, speech = "Stopping.", motion = MotionCommand.STOP, status = "Stopped")
            q.contains("sleep") -> { sleeping = true; BrainDecision(Emotion.SLEEPY, speech = "Okay. Nap time.", status = "Sleeping") }
            q.contains("wake") -> BrainDecision(Emotion.HAPPY, speech = "I'm awake!", status = "Awake")
            q.contains("don't follow") || q.contains("do not follow") -> { followEnabled = false; BrainDecision(Emotion.IDLE, speech = "Okay, staying here.", motion = MotionCommand.STOP, status = "Follow disabled") }
            q.contains("follow") -> { followEnabled = true; BrainDecision(Emotion.HAPPY, speech = "I'll follow when it is safe.", status = "Follow enabled") }
            q.contains("forward") -> safeMotion(MotionCommand.FORWARD, telemetry, "Forward")
            q.contains("back") -> safeMotion(MotionCommand.BACKWARD, telemetry, "Backward")
            q.contains("left") -> safeMotion(MotionCommand.LEFT, telemetry, "Left")
            q.contains("right") -> safeMotion(MotionCommand.RIGHT, telemetry, "Right")
            q.contains("fork") && q.contains("up") -> safeMotion(MotionCommand.FORK_UP, telemetry, "Fork up")
            q.contains("fork") && q.contains("down") -> safeMotion(MotionCommand.FORK_DOWN, telemetry, "Fork down")
            q.contains("hello") || q.contains("hi ") || q == "hi" -> BrainDecision(Emotion.HAPPY, speech = randomOf("Hello!", "Hi! I can see and hear you."), status = "Greeting")
            q.contains("who are you") || q.contains("your name") -> BrainDecision(Emotion.CURIOUS, speech = "I'm your little robot pet.", status = "Chatting")
            else -> BrainDecision(Emotion.CURIOUS, speech = "I heard: $text", status = "Listening")
        }
    }

    fun onVision(v: VisionObservation, telemetry: RobotTelemetry): BrainDecision {
        if (sleeping) return BrainDecision(Emotion.SLEEPY, status = "Sleeping")
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
                return BrainDecision(Emotion.HAPPY, gazeX = gaze, motion = turn, motionDurationMs = if (turn == MotionCommand.STOP) 0 else 300, status = "Following face")
            }
            return BrainDecision(Emotion.CURIOUS, gazeX = gaze, status = "Watching you")
        }
        if (v.objectCount > 0) {
            val gaze = ((v.objectCenterX - 0.5f) * 2f).coerceIn(-1f, 1f)
            return BrainDecision(Emotion.CURIOUS, gazeX = gaze, status = v.objectLabel?.let { "Curious about $it" } ?: "Curious")
        }
        return idleTick(telemetry, v.timestampMs)
    }

    fun idleTick(telemetry: RobotTelemetry, nowMs: Long = System.currentTimeMillis()): BrainDecision {
        if (sleeping) return BrainDecision(Emotion.SLEEPY, status = "Sleeping")
        if (!telemetry.safeToMove && telemetry.connected) return BrainDecision(Emotion.STARTLED, motion = MotionCommand.STOP, status = "Obstacle / safety stop")
        val quietFor = nowMs - maxOf(lastInteractionMs, lastFaceSeenMs)
        if (quietFor > 90_000L) {
            sleeping = true
            return BrainDecision(Emotion.SLEEPY, speech = "I'm getting sleepy.", status = "Auto sleep")
        }
        val gaze = if (Random.nextInt(5) == 0) Random.nextFloat() * 1.4f - 0.7f else 0f
        return BrainDecision(if (abs(gaze) > 0.1f) Emotion.CURIOUS else Emotion.IDLE, gazeX = gaze, status = if (telemetry.connected) "Ready" else "Face-only mode")
    }

    private fun safeMotion(command: MotionCommand, telemetry: RobotTelemetry, name: String): BrainDecision {
        return if (telemetry.connected && telemetry.safeToMove) {
            BrainDecision(Emotion.HAPPY, speech = "$name.", motion = command, motionDurationMs = 500, status = name)
        } else {
            BrainDecision(Emotion.STARTLED, speech = "I won't move until my safety controller says it's clear.", motion = MotionCommand.STOP, status = "Movement blocked")
        }
    }

    private fun randomOf(vararg choices: String): String = choices[Random.nextInt(choices.size)]
}
