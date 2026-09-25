package com.shehan.robotpet.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

data class ObjectFrame(
    val count: Int = 0,
    val label: String? = null,
    val confidence: Float = 0f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val areaRatio: Float = 0f
)

class ObjectDetectorManager(context: Context) {
    private val detector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("efficientdet_lite0.tflite")
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(5)
            .setScoreThreshold(0.42f)
            .build()
    )

    private var lastInferenceMs = 0L
    private var cached = ObjectFrame()

    fun analyze(bitmap: Bitmap, nowMs: Long = System.currentTimeMillis()): ObjectFrame {
        // Object recognition is intentionally slower than face/hand tracking.
        if (nowMs - lastInferenceMs < 450L) return cached
        lastInferenceMs = nowMs

        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        val detections = result.detections()
        val best = detections
            .mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                Triple(detection, category, detection.boundingBox().width() * detection.boundingBox().height())
            }
            .maxByOrNull { it.third }

        cached = if (best == null) {
            ObjectFrame()
        } else {
            val detection = best.first
            val category = best.second
            val box = detection.boundingBox()
            val width = bitmap.width.toFloat().coerceAtLeast(1f)
            val height = bitmap.height.toFloat().coerceAtLeast(1f)
            ObjectFrame(
                count = detections.size,
                label = category.categoryName(),
                confidence = category.score(),
                centerX = (box.centerX() / width).coerceIn(0f, 1f),
                centerY = (box.centerY() / height).coerceIn(0f, 1f),
                areaRatio = ((box.width() * box.height()) / (width * height)).coerceIn(0f, 1f)
            )
        }
        return cached
    }

    fun close() = detector.close()
}
