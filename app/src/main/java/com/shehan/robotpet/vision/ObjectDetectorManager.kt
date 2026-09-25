package com.shehan.robotpet.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.shehan.robotpet.brain.DetectedObject
import kotlin.math.sqrt

data class ObjectFrame(
    val detections: List<DetectedObject> = emptyList(),
    val count: Int = detections.size,
    val label: String? = detections.firstOrNull()?.label,
    val confidence: Float = detections.firstOrNull()?.confidence ?: 0f,
    val centerX: Float = detections.firstOrNull()?.centerX ?: 0.5f,
    val centerY: Float = detections.firstOrNull()?.centerY ?: 0.5f,
    val areaRatio: Float = detections.firstOrNull()?.areaRatio ?: 0f
)

private data class Track(val id: Int, val label: String, val x: Float, val y: Float, val seenMs: Long)

/** Low-rate perception only. Event/habituation decisions are made above this layer. */
class ObjectDetectorManager(context: Context) {
    private val detector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(6)
            .setScoreThreshold(0.44f)
            .build()
    )

    private var lastInferenceMs = 0L
    private var cached = ObjectFrame()
    private var nextTrackId = 1
    private var tracks = mutableListOf<Track>()

    fun analyze(bitmap: Bitmap, nowMs: Long = System.currentTimeMillis()): ObjectFrame {
        if (nowMs - lastInferenceMs < 430L) return cached
        lastInferenceMs = nowMs
        val width = bitmap.width.toFloat().coerceAtLeast(1f)
        val height = bitmap.height.toFloat().coerceAtLeast(1f)
        val result = detector.detect(BitmapImageBuilder(bitmap).build())

        val raw = result.detections().mapNotNull { detection ->
            val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val label = category.categoryName().trim().ifBlank { return@mapNotNull null }
            val box = detection.boundingBox()
            DetectedObject(
                label = label,
                confidence = category.score(),
                centerX = (box.centerX() / width).coerceIn(0f, 1f),
                centerY = (box.centerY() / height).coerceIn(0f, 1f),
                areaRatio = ((box.width() * box.height()) / (width * height)).coerceIn(0f, 1f)
            )
        }.sortedByDescending { it.confidence }

        val alive = tracks.filter { nowMs - it.seenMs < 2000L }.toMutableList()
        val assigned = raw.map { item ->
            val best = alive
                .filter { it.label.equals(item.label, true) }
                .minByOrNull { distance(it.x, it.y, item.centerX, item.centerY) }
                ?.takeIf { distance(it.x, it.y, item.centerX, item.centerY) < 0.18f }
            val id = best?.id ?: nextTrackId++
            alive.removeAll { it.id == id }
            alive += Track(id, item.label, item.centerX, item.centerY, nowMs)
            item.copy(trackId = id)
        }
        tracks = alive
        cached = ObjectFrame(assigned)
        return cached
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun close() = detector.close()
}
