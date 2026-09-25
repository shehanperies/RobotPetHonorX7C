package com.shehan.robotpet.vision

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.shehan.robotpet.brain.VisionObservation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class VisionManager(
    private val context: Context,
    private val onObservation: (VisionObservation) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .setMinFaceSize(0.12f)
            .build()
    )

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    fun start(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(executor) { analyze(it) }
            provider?.unbindAll()
            runCatching { provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis) }
                .recoverCatching { provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis) }
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image ?: run { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val width = input.width.toFloat().coerceAtLeast(1f)
        val height = input.height.toFloat().coerceAtLeast(1f)
        var faceVisible = false
        var faceX = 0.5f
        var faceArea = 0f
        var objectCount = 0
        var objectX = 0.5f
        var objectLabel: String? = null
        val pending = AtomicInteger(2)
        fun done() {
            if (pending.decrementAndGet() == 0) {
                onObservation(VisionObservation(faceVisible, faceX, faceArea, objectCount, objectX, objectLabel))
                proxy.close()
            }
        }

        faceDetector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null) {
                    faceVisible = true
                    faceX = (face.boundingBox.centerX() / width).coerceIn(0f, 1f)
                    faceArea = ((face.boundingBox.width() * face.boundingBox.height()) / (width * height)).coerceIn(0f, 1f)
                }
            }
            .addOnCompleteListener { done() }

        objectDetector.process(input)
            .addOnSuccessListener { objects ->
                objectCount = objects.size
                val obj = objects.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (obj != null) {
                    objectX = (obj.boundingBox.centerX() / width).coerceIn(0f, 1f)
                    objectLabel = obj.labels.maxByOrNull { it.confidence }?.text
                }
            }
            .addOnCompleteListener { done() }
    }

    fun stop() { provider?.unbindAll() }

    fun shutdown() {
        stop()
        faceDetector.close()
        objectDetector.close()
        executor.shutdown()
    }
}
