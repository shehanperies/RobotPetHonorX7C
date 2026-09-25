package com.shehan.robotpet.brain

enum class Emotion { IDLE, HAPPY, CURIOUS, LISTENING, SLEEPY, STARTLED, SAD, ANGRY, PLAYFUL }

enum class MotionCommand { STOP, FORWARD, BACKWARD, LEFT, RIGHT, FORK_UP, FORK_DOWN }

enum class HandGesture {
    NONE,
    WAVE,
    OPEN_PALM,
    THUMBS_UP,
    THUMBS_DOWN,
    POINT_LEFT,
    POINT_RIGHT,
    POINT_UP,
    COME_HERE,
    SHH,
    PEACE,
    FIST,
    HIT_SWING
}

data class VisionObservation(
    val faceVisible: Boolean = false,
    val faceCenterX: Float = 0.5f,
    val faceAreaRatio: Float = 0f,
    val objectCount: Int = 0,
    val objectCenterX: Float = 0.5f,
    val objectLabel: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val faceCenterY: Float = 0.5f,
    val handGesture: HandGesture = HandGesture.NONE,
    val handConfidence: Float = 0f,
    val handCenterX: Float = 0.5f,
    val handCenterY: Float = 0.5f
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
