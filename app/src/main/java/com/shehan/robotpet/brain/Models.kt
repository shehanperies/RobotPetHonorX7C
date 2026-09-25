package com.shehan.robotpet.brain

enum class Emotion {
    IDLE, HAPPY, CURIOUS, LISTENING, SLEEPY, STARTLED, SAD,
    ANGRY, PLAYFUL, LOVE, DIZZY, LONELY
}

enum class MotionCommand {
    STOP, FORWARD, BACKWARD, LEFT, RIGHT, FORK_UP, FORK_DOWN
}

enum class HandGesture {
    NONE,
    WAVE,
    OPEN_PALM,
    THUMBS_UP,
    THUMBS_DOWN,
    POINT_LEFT,
    POINT_RIGHT,
    POINT_UP,
    POINT_DOWN,
    COME_HERE,
    TURN_AROUND,
    SHH,
    PEACE,
    LOVE,
    FIST,
    HIT_SWING
}

enum class PhoneEvent {
    NONE, SHAKE, TILT_LEFT, TILT_RIGHT, UPSIDE_DOWN, DARK, BRIGHT
}

enum class PetMode {
    IDLE, ENGAGED, FOLLOWING, SEARCHING, LISTENING, SLEEPING, REMOTE, THINKING
}

enum class AiAction {
    NONE, CHAT, GREET, PLAY, SEARCH, FOLLOW, LOOK_LEFT, LOOK_RIGHT, FORK_WAVE, REST
}

data class AiDirective(
    val action: AiAction = AiAction.NONE,
    val speech: String? = null,
    val emotion: String = "",
    val reason: String = ""
)

data class PetMindSnapshot(
    val mood: Int = 0,
    val annoyance: Int = 0,
    val socialNeed: Int = 0,
    val boredom: Int = 0,
    val curiosity: Int = 0,
    val energy: Int = 100,
    val followActive: Boolean = false,
    val searchActive: Boolean = false
)

data class MotionStep(
    val command: MotionCommand,
    val durationMs: Long,
    val delayAfterMs: Long = 80L
)

data class VisionObservation(
    val faceVisible: Boolean = false,
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val faceAreaRatio: Float = 0f,
    val smileProbability: Float = -1f,
    val leftEyeOpenProbability: Float = -1f,
    val rightEyeOpenProbability: Float = -1f,
    val headEulerX: Float = 0f,
    val headEulerY: Float = 0f,
    val headEulerZ: Float = 0f,

    val objectCount: Int = 0,
    val objectCenterX: Float = 0.5f,
    val objectCenterY: Float = 0.5f,
    val objectAreaRatio: Float = 0f,
    val objectLabel: String? = null,
    val objectConfidence: Float = 0f,

    val handPresent: Boolean = false,
    val rawHandLabel: String = "",
    val handGesture: HandGesture = HandGesture.NONE,
    val handConfidence: Float = 0f,
    val handCenterX: Float = 0.5f,
    val handCenterY: Float = 0.5f,

    val timestampMs: Long = System.currentTimeMillis()
)

data class PhoneObservation(
    val event: PhoneEvent = PhoneEvent.NONE,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val gForce: Float = 1f,
    val lux: Float? = null,
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
    val mode: PetMode = PetMode.IDLE,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val speech: String? = null,
    val motion: MotionCommand = MotionCommand.STOP,
    val motionDurationMs: Long = 0L,
    val sequence: List<MotionStep> = emptyList(),
    val interruptMotion: Boolean = false,
    val status: String = "Idle"
)
