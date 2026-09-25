package com.shehan.robotpet.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.shehan.robotpet.brain.HandGesture
import kotlin.math.abs
import kotlin.math.sqrt

data class GestureFrame(
    val gesture: HandGesture,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float
)

class GestureManager(context: Context) {
    private val recognizer = GestureRecognizer.createFromOptions(
        context,
        GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("gesture_recognizer.task")
                    .build()
            )
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .build()
    )

    private var lastInferenceMs = 0L
    private var lastFrame: GestureFrame? = null
    private var lastX = 0.5f
    private var lastY = 0.5f
    private var lastMotionMs = 0L

    private var waveStartMs = 0L
    private var waveMinX = 1f
    private var waveMaxX = 0f
    private var waveDirection = 0
    private var waveReversals = 0

    private var beckonStage = 0
    private var beckonStartMs = 0L

    fun analyze(bitmap: Bitmap): GestureFrame? {
        val now = System.currentTimeMillis()
        if (now - lastInferenceMs < 120L) return lastFrame
        lastInferenceMs = now

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = recognizer.recognize(mpImage)
        val landmarks = result.landmarks().firstOrNull()
        val top = result.gestures().firstOrNull()?.maxByOrNull { it.score() }

        if (landmarks == null || landmarks.size < 9 || top == null || top.score() < 0.45f) {
            resetDynamicIfOld(now)
            lastFrame = null
            return null
        }

        val wrist = landmarks[0]
        val centerX = wrist.x()
        val centerY = wrist.y()
        val name = top.categoryName()
        val confidence = top.score()

        val dynamic = detectDynamic(name, centerX, centerY, now)
        val staticGesture = classifyStatic(name, landmarks)
        val frame = GestureFrame(dynamic ?: staticGesture, confidence, centerX, centerY)
        lastFrame = frame
        return frame
    }

    private fun classifyStatic(name: String, landmarks: List<NormalizedLandmark>): HandGesture {
        return when (name) {
            "Open_Palm" -> HandGesture.OPEN_PALM
            "Thumb_Up" -> HandGesture.THUMBS_UP
            "Thumb_Down" -> HandGesture.THUMBS_DOWN
            "Victory" -> HandGesture.PEACE
            "Closed_Fist" -> HandGesture.FIST
            "Pointing_Up" -> {
                val mcp = landmarks[5]
                val tip = landmarks[8]
                val dx = tip.x() - mcp.x()
                val dy = tip.y() - mcp.y()
                when {
                    abs(dx) > abs(dy) * 0.75f && dx < 0f -> HandGesture.POINT_LEFT
                    abs(dx) > abs(dy) * 0.75f && dx > 0f -> HandGesture.POINT_RIGHT
                    else -> HandGesture.POINT_UP
                }
            }
            else -> HandGesture.NONE
        }
    }

    private fun detectDynamic(name: String, x: Float, y: Float, now: Long): HandGesture? {
        val dt = now - lastMotionMs
        val dx = x - lastX
        val dy = y - lastY
        val distance = sqrt(dx * dx + dy * dy)
        val speed = if (dt in 20L..400L) distance * 1000f / dt else 0f

        var event: HandGesture? = null

        if (name == "Closed_Fist" && distance > 0.10f && speed > 1.6f) {
            event = HandGesture.HIT_SWING
            resetWave()
        }

        if (name == "Open_Palm") {
            if (waveStartMs == 0L || now - waveStartMs > 1400L) {
                waveStartMs = now
                waveMinX = x
                waveMaxX = x
                waveDirection = 0
                waveReversals = 0
            }
            waveMinX = minOf(waveMinX, x)
            waveMaxX = maxOf(waveMaxX, x)
            if (abs(dx) > 0.035f) {
                val direction = if (dx > 0f) 1 else -1
                if (waveDirection != 0 && direction != waveDirection) waveReversals++
                waveDirection = direction
            }
            if (waveReversals >= 2 && waveMaxX - waveMinX > 0.18f) {
                event = HandGesture.WAVE
                resetWave()
            }
        } else if (name != "Closed_Fist") {
            resetWave()
        }

        if (now - beckonStartMs > 1600L) beckonStage = 0
        when {
            name == "Open_Palm" && beckonStage == 0 -> {
                beckonStage = 1
                beckonStartMs = now
            }
            name == "Closed_Fist" && beckonStage == 1 -> beckonStage = 2
            name == "Open_Palm" && beckonStage == 2 && now - beckonStartMs <= 1600L -> {
                if (event == null) event = HandGesture.COME_HERE
                beckonStage = 0
            }
        }

        lastX = x
        lastY = y
        lastMotionMs = now
        return event
    }

    private fun resetDynamicIfOld(now: Long) {
        if (now - lastMotionMs > 800L) {
            resetWave()
            beckonStage = 0
        }
    }

    private fun resetWave() {
        waveStartMs = 0L
        waveMinX = 1f
        waveMaxX = 0f
        waveDirection = 0
        waveReversals = 0
    }

    fun close() = recognizer.close()
}
