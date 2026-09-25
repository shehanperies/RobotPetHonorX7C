package com.shehan.robotpet.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.shehan.robotpet.brain.HandGesture
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class GestureFrame(
    val gesture: HandGesture,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val rawLabel: String,
    val handPresent: Boolean = true
)

private data class PathPoint(val x: Float, val y: Float, val t: Long)

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
            .setMinHandDetectionConfidence(0.50f)
            .setMinHandPresenceConfidence(0.50f)
            .setMinTrackingConfidence(0.50f)
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
    private var waveMinY = 1f
    private var waveMaxY = 0f
    private var waveXDirection = 0
    private var waveYDirection = 0
    private var waveXReversals = 0
    private var waveYReversals = 0

    private var beckonStage = 0
    private var beckonStartMs = 0L
    private var curlState = 0
    private var curlTransitions = 0
    private var curlStartMs = 0L

    private val circlePath = ArrayDeque<PathPoint>()

    private var staticCandidate = HandGesture.NONE
    private var staticCandidateCount = 0

    fun analyze(bitmap: Bitmap): GestureFrame? {
        val now = System.currentTimeMillis()
        if (now - lastInferenceMs < 105L) return lastFrame
        lastInferenceMs = now

        val result = recognizer.recognize(BitmapImageBuilder(bitmap).build())
        val landmarks = result.landmarks().firstOrNull()

        if (landmarks == null || landmarks.size < 21) {
            resetDynamicIfOld(now)
            lastFrame = null
            staticCandidate = HandGesture.NONE
            staticCandidateCount = 0
            return null
        }

        val top = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
        val rawName = top?.categoryName().orEmpty().ifBlank { "None" }
        val confidence = top?.score() ?: 0f

        val wrist = landmarks[0]
        val centerX = wrist.x()
        val centerY = wrist.y()

        val dynamic = detectDynamic(rawName, landmarks, centerX, centerY, now)
        val staticGesture = classifyStatic(rawName, landmarks, confidence)
        val stableStatic = stabilizeStatic(staticGesture)

        val frame = GestureFrame(
            gesture = dynamic ?: stableStatic,
            confidence = confidence,
            centerX = centerX,
            centerY = centerY,
            rawLabel = rawName,
            handPresent = true
        )

        lastFrame = frame
        return frame
    }

    private fun classifyStatic(
        name: String,
        landmarks: List<NormalizedLandmark>,
        confidence: Float
    ): HandGesture {
        val direct = when (name) {
            "Open_Palm" -> HandGesture.OPEN_PALM
            "Thumb_Up" -> HandGesture.THUMBS_UP
            "Thumb_Down" -> HandGesture.THUMBS_DOWN
            "Victory" -> HandGesture.PEACE
            "ILoveYou" -> HandGesture.LOVE
            "Closed_Fist" -> HandGesture.FIST
            else -> HandGesture.NONE
        }

        if (direct != HandGesture.NONE && confidence >= 0.48f) return direct

        // Directional pointing is derived from landmarks so a sideways finger does not
        // depend on the model continuing to call it "Pointing_Up".
        if (name == "Pointing_Up" || isIndexOnlyExtended(landmarks)) {
            val mcp = landmarks[5]
            val tip = landmarks[8]
            val dx = tip.x() - mcp.x()
            val dy = tip.y() - mcp.y()

            return when {
                abs(dx) > abs(dy) * 0.68f && dx < -0.06f -> HandGesture.POINT_LEFT
                abs(dx) > abs(dy) * 0.68f && dx > 0.06f -> HandGesture.POINT_RIGHT
                dy > 0.075f -> HandGesture.POINT_DOWN
                else -> HandGesture.POINT_UP
            }
        }

        return HandGesture.NONE
    }

    private fun stabilizeStatic(value: HandGesture): HandGesture {
        if (value == HandGesture.NONE) {
            staticCandidate = HandGesture.NONE
            staticCandidateCount = 0
            return HandGesture.NONE
        }

        if (value == staticCandidate) {
            staticCandidateCount++
        } else {
            staticCandidate = value
            staticCandidateCount = 1
        }

        // Two matching samples (~200 ms) removes most one-frame false triggers.
        return if (staticCandidateCount >= 2) value else HandGesture.NONE
    }

    private fun detectDynamic(
        name: String,
        landmarks: List<NormalizedLandmark>,
        x: Float,
        y: Float,
        now: Long
    ): HandGesture? {
        val dt = now - lastMotionMs
        val dx = x - lastX
        val dy = y - lastY
        val distance = sqrt(dx * dx + dy * dy)
        val speed = if (dt in 20L..400L) distance * 1000f / dt else 0f

        var event: HandGesture? = null

        // A fast closed-fist trajectory is treated as a hit/swing, not a normal fist.
        if (name == "Closed_Fist" && distance > 0.10f && speed > 1.55f) {
            event = HandGesture.HIT_SWING
            resetWaveBeckon()
        }

        val palmLike = name == "Open_Palm" || name == "Closed_Fist" || name == "None"
        if (palmLike) {
            if (waveStartMs == 0L || now - waveStartMs > 1700L) {
                waveStartMs = now
                waveMinX = x
                waveMaxX = x
                waveMinY = y
                waveMaxY = y
                waveXDirection = 0
                waveYDirection = 0
                waveXReversals = 0
                waveYReversals = 0
            }

            waveMinX = minOf(waveMinX, x)
            waveMaxX = maxOf(waveMaxX, x)
            waveMinY = minOf(waveMinY, y)
            waveMaxY = maxOf(waveMaxY, y)

            if (abs(dx) > 0.025f) {
                val direction = if (dx > 0f) 1 else -1
                if (waveXDirection != 0 && direction != waveXDirection) waveXReversals++
                waveXDirection = direction
            }

            if (abs(dy) > 0.025f) {
                val direction = if (dy > 0f) 1 else -1
                if (waveYDirection != 0 && direction != waveYDirection) waveYReversals++
                waveYDirection = direction
            }

            val xRange = waveMaxX - waveMinX
            val yRange = waveMaxY - waveMinY

            // Horizontal oscillation -> wave. Vertical-dominant oscillation -> beckon/come here.
            if (event == null && waveXReversals >= 2 && xRange > 0.16f && xRange > yRange * 1.25f) {
                event = HandGesture.WAVE
                resetWaveBeckon()
            } else if (
                event == null &&
                waveYReversals >= 2 &&
                yRange > 0.14f &&
                yRange > xRange * 1.20f
            ) {
                event = HandGesture.COME_HERE
                resetWaveBeckon()
            }
        } else {
            resetWaveBeckon()
        }

        // The old open->fist->open sequence is kept as a second way to say "come here".
        if (now - beckonStartMs > 1900L) beckonStage = 0
        when {
            name == "Open_Palm" && beckonStage == 0 -> {
                beckonStage = 1
                beckonStartMs = now
            }
            name == "Closed_Fist" && beckonStage == 1 -> beckonStage = 2
            name == "Open_Palm" && beckonStage == 2 && now - beckonStartMs <= 1900L -> {
                if (event == null) event = HandGesture.COME_HERE
                beckonStage = 0
            }
        }

        // Curling the fingers toward the palm and releasing twice is a natural beckon.
        // This catches "come here" even when MediaPipe alternates between Open_Palm/None.
        val curl = fingerCurlState(landmarks)
        if (curl != 0) {
            if (curlStartMs == 0L || now - curlStartMs > 2200L) {
                curlStartMs = now
                curlState = curl
                curlTransitions = 0
            } else if (curl != curlState) {
                curlState = curl
                curlTransitions++
            }

            if (event == null && curlTransitions >= 3) {
                event = HandGesture.COME_HERE
                curlStartMs = 0L
                curlTransitions = 0
            }
        }

        // Circle detection follows the index fingertip. It is deliberately more permissive
        // than the static point classifier because people draw circles with slightly bent fingers.
        if (name == "Pointing_Up" || indexLooksExtended(landmarks)) {
            val tip = landmarks[8]
            circlePath.addLast(PathPoint(tip.x(), tip.y(), now))
            while (circlePath.isNotEmpty() && now - circlePath.first().t > 2800L) {
                circlePath.removeFirst()
            }

            if (event == null && looksLikeCircle(circlePath)) {
                event = HandGesture.TURN_AROUND
                circlePath.clear()
            }
        } else if (circlePath.isNotEmpty() && now - circlePath.last().t > 650L) {
            circlePath.clear()
        }

        lastX = x
        lastY = y
        lastMotionMs = now
        return event
    }

    private fun isIndexOnlyExtended(l: List<NormalizedLandmark>): Boolean {
        val wrist = l[0]
        fun d(a: Int, b: NormalizedLandmark = wrist): Float {
            val dx = l[a].x() - b.x()
            val dy = l[a].y() - b.y()
            return sqrt(dx * dx + dy * dy)
        }

        val indexExtended = d(8) > d(6) * 1.15f
        val middleExtended = d(12) > d(10) * 1.12f
        val ringExtended = d(16) > d(14) * 1.12f
        val pinkyExtended = d(20) > d(18) * 1.12f
        val otherExtended = listOf(middleExtended, ringExtended, pinkyExtended).count { it }

        return indexExtended && otherExtended <= 1
    }

    private fun indexLooksExtended(l: List<NormalizedLandmark>): Boolean {
        val wrist = l[0]
        fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
            val dx = a.x() - b.x()
            val dy = a.y() - b.y()
            return sqrt(dx * dx + dy * dy)
        }
        return distance(l[8], wrist) > distance(l[6], wrist) * 1.07f
    }

    private fun fingerCurlState(l: List<NormalizedLandmark>): Int {
        val wrist = l[0]
        fun d(a: Int, b: NormalizedLandmark = wrist): Float {
            val dx = l[a].x() - b.x()
            val dy = l[a].y() - b.y()
            return sqrt(dx * dx + dy * dy)
        }

        val ratios = listOf(
            d(8) / d(5).coerceAtLeast(0.01f),
            d(12) / d(9).coerceAtLeast(0.01f),
            d(16) / d(13).coerceAtLeast(0.01f),
            d(20) / d(17).coerceAtLeast(0.01f)
        )
        val avg = ratios.average().toFloat()

        return when {
            avg > 1.48f -> 1   // fingers extended
            avg < 1.22f -> -1  // fingers curled toward palm
            else -> 0
        }
    }

    private fun looksLikeCircle(path: ArrayDeque<PathPoint>): Boolean {
        if (path.size < 9) return false
        val points = path.toList()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        if (maxX - minX < 0.13f || maxY - minY < 0.13f) return false

        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        var signedAngle = 0f
        var previous = atan2(points.first().y - cy, points.first().x - cx)

        for (p in points.drop(1)) {
            val angle = atan2(p.y - cy, p.x - cx)
            var delta = angle - previous
            while (delta > Math.PI) delta -= (Math.PI * 2).toFloat()
            while (delta < -Math.PI) delta += (Math.PI * 2).toFloat()
            signedAngle += delta
            previous = angle
        }

        val start = points.first()
        val end = points.last()
        val close = sqrt(
            (start.x - end.x) * (start.x - end.x) +
                (start.y - end.y) * (start.y - end.y)
        ) < 0.26f

        return close && abs(signedAngle) > 4.35f
    }

    private fun resetDynamicIfOld(now: Long) {
        if (now - lastMotionMs > 900L) {
            resetWaveBeckon()
            beckonStage = 0
            curlStartMs = 0L
            curlTransitions = 0
            circlePath.clear()
        }
    }

    private fun resetWaveBeckon() {
        waveStartMs = 0L
        waveMinX = 1f
        waveMaxX = 0f
        waveMinY = 1f
        waveMaxY = 0f
        waveXDirection = 0
        waveYDirection = 0
        waveXReversals = 0
        waveYReversals = 0
    }

    fun close() = recognizer.close()
}
