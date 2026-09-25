package com.shehan.robotpet.brain

enum class Emotion { IDLE, HAPPY, CURIOUS, LISTENING, SLEEPY, STARTLED, SAD }

enum class MotionCommand { STOP, FORWARD, BACKWARD, LEFT, RIGHT, FORK_UP, FORK_DOWN }

data class VisionObservation(
    val faceVisible: Boolean = false,
    val faceCenterX: Float = 0.5f,
    val faceAreaRatio: Float = 0f,
    val objectCount: Int = 0,
    val objectCenterX: Float = 0.5f,
    val objectLabel: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class RobotTelemetry(
    val connected: Boolean = false,
    val safeToMove: Boolean = false,
    val obstacleCm: Float? = null,
    val batteryPercent: Int? = null,
    val lastSeenMs: Long = 0L
)

data class BrainDecision(
    val emotion: Emotion,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val speech: String? = null,
    val motion: MotionCommand = MotionCommand.STOP,
    val motionDurationMs: Long = 0L,
    val status: String = "Idle"
)
