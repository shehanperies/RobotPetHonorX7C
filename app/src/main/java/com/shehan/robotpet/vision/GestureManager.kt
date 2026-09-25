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
    val candidate: HandGesture,
    val stage: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val rawLabel: String,
    val handPresent: Boolean = true
)

private data class PathPoint(val x: Float, val y: Float, val t: Long)
private data class HandSample(
    val t: Long,
    val wristX: Float,
    val wristY: Float,
    val indexX: Float,
    val indexY: Float,
    val raw: String,
    val curl: Float
)

/**
 * V6 gesture engine. MediaPipe handles hand detection and canned poses; dynamic gestures
 * are confirmed from a 1-2 second landmark history before they are emitted once.
 */
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

    private val history = ArrayDeque<HandSample>()
    private val circlePath = ArrayDeque<PathPoint>()
    private val lastEmitted = mutableMapOf<HandGesture, Long>()

    private var lastInferenceMs = 0L
    private var lastFrame: GestureFrame? = null
    private var staticCandidate = HandGesture.NONE
    private var staticSinceMs = 0L

    fun analyze(bitmap: Bitmap): GestureFrame? {
        val now = System.currentTimeMillis()
        if (now - lastInferenceMs < 90L) return lastFrame
        lastInferenceMs = now

        val result = recognizer.recognize(BitmapImageBuilder(bitmap).build())
        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null || landmarks.size < 21) {
            trim(now)
            staticCandidate = HandGesture.NONE
            staticSinceMs = 0L
            lastFrame = null
            return null
        }

        val top = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
        val raw = top?.categoryName().orEmpty().ifBlank { "None" }
        val modelConfidence = top?.score() ?: 0f
        val wrist = landmarks[0]
        val index = landmarks[8]
        val curl = averageFingerExtension(landmarks)

        history.addLast(
            HandSample(
                t = now,
                wristX = wrist.x(),
                wristY = wrist.y(),
                indexX = index.x(),
                indexY = index.y(),
                raw = raw,
                curl = curl
            )
        )
        trim(now)

        val dynamic = detectDynamic(landmarks, raw, now)
        val staticPose = classifyStatic(raw, landmarks, modelConfidence)
        val stableStatic = confirmStatic(staticPose, now)
        val candidate = dynamic ?: stableStatic

        val confirmed = if (
            candidate != HandGesture.NONE &&
            canEmit(candidate, now)
        ) {
            lastEmitted[candidate] = now
            candidate
        } else HandGesture.NONE

        val stage = when {
            confirmed != HandGesture.NONE -> "CONFIRMED:${confirmed.name}"
            dynamic != null -> "CANDIDATE:${dynamic.name}"
            staticPose != HandGesture.NONE -> "HOLD:${staticPose.name}"
            else -> "TRACKING"
        }

        val confidence = when {
            confirmed != HandGesture.NONE -> maxOf(modelConfidence, dynamicConfidence(confirmed))
            candidate != HandGesture.NONE -> maxOf(modelConfidence, 0.55f)
            else -> modelConfidence
        }

        return GestureFrame(
            gesture = confirmed,
            candidate = candidate,
            stage = stage,
            confidence = confidence.coerceIn(0f, 1f),
            centerX = wrist.x(),
            centerY = wrist.y(),
            rawLabel = raw,
            handPresent = true
        ).also { lastFrame = it }
    }

    private fun classifyStatic(
        name: String,
        l: List<NormalizedLandmark>,
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
        if (direct != HandGesture.NONE && confidence >= 0.46f) return direct

        if (name == "Pointing_Up" || isIndexOnlyExtended(l)) {
            val mcp = l[5]
            val tip = l[8]
            val dx = tip.x() - mcp.x()
            val dy = tip.y() - mcp.y()
            return when {
                abs(dx) > abs(dy) * 0.68f && dx < -0.055f -> HandGesture.POINT_LEFT
                abs(dx) > abs(dy) * 0.68f && dx > 0.055f -> HandGesture.POINT_RIGHT
                dy > 0.070f -> HandGesture.POINT_DOWN
                else -> HandGesture.POINT_UP
            }
        }
        return HandGesture.NONE
    }

    private fun confirmStatic(candidate: HandGesture, now: Long): HandGesture {
        if (candidate == HandGesture.NONE) {
            staticCandidate = HandGesture.NONE
            staticSinceMs = 0L
            return HandGesture.NONE
        }

        if (candidate != staticCandidate) {
            staticCandidate = candidate
            staticSinceMs = now
            return HandGesture.NONE
        }

        val required = when (candidate) {
            HandGesture.OPEN_PALM -> 320L // STOP must be deliberate but quick
            HandGesture.POINT_UP, HandGesture.POINT_DOWN,
            HandGesture.POINT_LEFT, HandGesture.POINT_RIGHT -> 260L
            else -> 300L
        }
        return if (now - staticSinceMs >= required) candidate else HandGesture.NONE
    }

    private fun detectDynamic(
        l: List<NormalizedLandmark>,
        raw: String,
        now: Long
    ): HandGesture? {
        if (history.size < 4) return null
        val samples = history.toList()
        val recent = samples.filter { now - it.t <= 1500L }
        if (recent.size < 4) return null

        val first = recent.first()
        val last = recent.last()
        val dt = (last.t - first.t).coerceAtLeast(1L)
        val travel = distance(first.wristX, first.wristY, last.wristX, last.wristY)
        val speed = travel * 1000f / dt

        // Fast closed fist -> defensive hit/swing event. It never commands an attack.
        if (raw == "Closed_Fist" && speed > 0.95f && travel > 0.11f) {
            return HandGesture.HIT_SWING
        }

        val xRange = (recent.maxOf { it.wristX } - recent.minOf { it.wristX })
        val yRange = (recent.maxOf { it.wristY } - recent.minOf { it.wristY })
        val xReversals = reversals(recent.map { it.wristX }, threshold = 0.020f)
        val yReversals = reversals(recent.map { it.wristY }, threshold = 0.020f)
        val palmRatio = recent.count { it.raw == "Open_Palm" }.toFloat() / recent.size

        // WAVE: open hand, mostly horizontal left-right oscillation.
        if (
            palmRatio >= 0.45f &&
            xReversals >= 2 &&
            xRange > 0.14f &&
            xRange > yRange * 1.20f
        ) return HandGesture.WAVE

        // COME HERE: repeated finger curl is primary. Wrist vertical oscillation is only
        // supporting evidence, preventing normal waving from becoming a beckon.
        val curlTransitions = curlTransitions(recent)
        val curlAmplitude = (recent.maxOf { it.curl } - recent.minOf { it.curl })
        if (
            curlTransitions >= 2 &&
            curlAmplitude > 0.24f &&
            (yReversals >= 1 || yRange > 0.08f)
        ) return HandGesture.COME_HERE

        // Secondary natural beckon: open -> fist/curled -> open within 1.5 s.
        val sequence = recent.map { it.raw }
        val openBefore = sequence.indexOfFirst { it == "Open_Palm" }
        val fist = sequence.indexOfFirst { it == "Closed_Fist" }
        val openAfter = if (fist >= 0) sequence.drop(fist + 1).indexOfFirst { it == "Open_Palm" } else -1
        if (openBefore >= 0 && fist > openBefore && openAfter >= 0) return HandGesture.COME_HERE

        // TURN AROUND: draw a circle with the index tip. Both directions accepted.
        if (raw == "Pointing_Up" || isIndexOnlyExtended(l)) {
            circlePath.addLast(PathPoint(l[8].x(), l[8].y(), now))
            while (circlePath.isNotEmpty() && now - circlePath.first().t > 2200L) circlePath.removeFirst()
            if (looksLikeCircle(circlePath)) {
                circlePath.clear()
                return HandGesture.TURN_AROUND
            }
        } else if (circlePath.isNotEmpty() && now - circlePath.last().t > 550L) {
            circlePath.clear()
        }

        return null
    }

    private fun canEmit(g: HandGesture, now: Long): Boolean {
        val last = lastEmitted[g] ?: 0L
        val cooldown = when (g) {
            HandGesture.OPEN_PALM -> 900L
            HandGesture.HIT_SWING -> 1800L
            HandGesture.COME_HERE, HandGesture.TURN_AROUND, HandGesture.WAVE -> 2200L
            else -> 1400L
        }
        return now - last >= cooldown
    }

    private fun dynamicConfidence(g: HandGesture): Float = when (g) {
        HandGesture.COME_HERE, HandGesture.TURN_AROUND, HandGesture.WAVE -> 0.78f
        HandGesture.HIT_SWING -> 0.82f
        else -> 0.65f
    }

    private fun trim(now: Long) {
        while (history.isNotEmpty() && now - history.first().t > 2200L) history.removeFirst()
        while (circlePath.isNotEmpty() && now - circlePath.first().t > 2200L) circlePath.removeFirst()
    }

    private fun reversals(values: List<Float>, threshold: Float): Int {
        var direction = 0
        var count = 0
        for (i in 1 until values.size) {
            val delta = values[i] - values[i - 1]
            if (abs(delta) < threshold) continue
            val d = if (delta > 0f) 1 else -1
            if (direction != 0 && d != direction) count++
            direction = d
        }
        return count
    }

    private fun curlTransitions(samples: List<HandSample>): Int {
        var previous = 0
        var transitions = 0
        for (s in samples) {
            val state = when {
                s.curl > 1.48f -> 1
                s.curl < 1.24f -> -1
                else -> 0
            }
            if (state == 0) continue
            if (previous != 0 && state != previous) transitions++
            previous = state
        }
        return transitions
    }

    private fun averageFingerExtension(l: List<NormalizedLandmark>): Float {
        val wrist = l[0]
        fun d(index: Int, to: NormalizedLandmark = wrist): Float =
            distance(l[index].x(), l[index].y(), to.x(), to.y())
        return listOf(
            d(8) / d(5).coerceAtLeast(0.01f),
            d(12) / d(9).coerceAtLeast(0.01f),
            d(16) / d(13).coerceAtLeast(0.01f),
            d(20) / d(17).coerceAtLeast(0.01f)
        ).average().toFloat()
    }

    private fun isIndexOnlyExtended(l: List<NormalizedLandmark>): Boolean {
        val wrist = l[0]
        fun d(i: Int): Float = distance(l[i].x(), l[i].y(), wrist.x(), wrist.y())
        val indexExtended = d(8) > d(6) * 1.12f
        val middleExtended = d(12) > d(10) * 1.10f
        val ringExtended = d(16) > d(14) * 1.10f
        val pinkyExtended = d(20) > d(18) * 1.10f
        return indexExtended && listOf(middleExtended, ringExtended, pinkyExtended).count { it } <= 1
    }

    private fun looksLikeCircle(path: ArrayDeque<PathPoint>): Boolean {
        if (path.size < 10) return false
        val p = path.toList()
        val width = p.maxOf { it.x } - p.minOf { it.x }
        val height = p.maxOf { it.y } - p.minOf { it.y }
        if (width < 0.11f || height < 0.11f) return false
        val roundness = minOf(width, height) / maxOf(width, height)
        if (roundness < 0.50f) return false

        val cx = p.map { it.x }.average().toFloat()
        val cy = p.map { it.y }.average().toFloat()
        var angleSum = 0f
        var previous = atan2(p.first().y - cy, p.first().x - cx)
        for (point in p.drop(1)) {
            val angle = atan2(point.y - cy, point.x - cx)
            var delta = angle - previous
            while (delta > Math.PI) delta -= (Math.PI * 2).toFloat()
            while (delta < -Math.PI) delta += (Math.PI * 2).toFloat()
            angleSum += delta
            previous = angle
        }
        val closed = distance(p.first().x, p.first().y, p.last().x, p.last().y) < maxOf(width, height) * 0.85f
        return closed && abs(angleSum) > 4.7f
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun close() = recognizer.close()
}
