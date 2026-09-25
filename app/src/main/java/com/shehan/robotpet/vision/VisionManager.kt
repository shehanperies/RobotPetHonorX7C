package com.shehan.robotpet.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.shehan.robotpet.brain.HandGesture
import com.shehan.robotpet.brain.VisionObservation
import java.util.concurrent.Executors
import kotlin.math.abs

class VisionManager(
    private val context: Context,
    private val onObservation: (VisionObservation) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var usingFrontCamera = true

    private val gestureManager = GestureManager(context)
    private val objectManager = ObjectDetectorManager(context)

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(0.12f)
            .build()
    )

    fun start(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            provider = future.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { analyze(it) }
            provider?.unbindAll()

            val front = runCatching {
                usingFrontCamera = true
                provider?.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
            }

            if (front.isFailure) {
                usingFrontCamera = false
                runCatching {
                    provider?.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis
                    )
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride.coerceAtLeast(4)
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * proxy.width
        val paddedWidth = proxy.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(
            paddedWidth,
            proxy.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)

        val source = Bitmap.createBitmap(
            padded,
            0,
            0,
            proxy.width,
            proxy.height
        )

        val rotation = Matrix().apply {
            postRotate(proxy.imageInfo.rotationDegrees.toFloat())
        }

        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            rotation,
            true
        )

        val bitmap = if (usingFrontCamera) {
            val mirror = Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(
                rotated,
                0,
                0,
                rotated.width,
                rotated.height,
                mirror,
                true
            )
        } else {
            rotated
        }

        val now = System.currentTimeMillis()
        val width = bitmap.width.toFloat().coerceAtLeast(1f)
        val height = bitmap.height.toFloat().coerceAtLeast(1f)

        val gesture = runCatching {
            gestureManager.analyze(bitmap)
        }.getOrNull()

        val obj = runCatching {
            objectManager.analyze(bitmap, now)
        }.getOrDefault(ObjectFrame())

        val input = InputImage.fromBitmap(bitmap, 0)

        faceDetector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull {
                    it.boundingBox.width() * it.boundingBox.height()
                }

                var faceVisible = false
                var faceX = 0.5f
                var faceY = 0.5f
                var faceArea = 0f
                var smile = -1f
                var leftEye = -1f
                var rightEye = -1f
                var eulerX = 0f
                var eulerY = 0f
                var eulerZ = 0f

                if (face != null) {
                    faceVisible = true
                    faceX = (face.boundingBox.centerX() / width).coerceIn(0f, 1f)
                    faceY = (face.boundingBox.centerY() / height).coerceIn(0f, 1f)
                    faceArea = (
                        (face.boundingBox.width() * face.boundingBox.height()) /
                            (width * height)
                        ).coerceIn(0f, 1f)

                    smile = face.smilingProbability ?: -1f
                    leftEye = face.leftEyeOpenProbability ?: -1f
                    rightEye = face.rightEyeOpenProbability ?: -1f
                    eulerX = face.headEulerAngleX
                    eulerY = face.headEulerAngleY
                    eulerZ = face.headEulerAngleZ
                }

                var handGesture = gesture?.gesture ?: HandGesture.NONE
                val handX = gesture?.centerX ?: 0.5f
                val handY = gesture?.centerY ?: 0.5f

                // Pointing near the mouth area is treated as "shh".
                if (
                    handGesture == HandGesture.POINT_UP &&
                    faceVisible &&
                    abs(handX - faceX) < 0.18f &&
                    handY > faceY - 0.05f &&
                    abs(handY - faceY) < 0.30f
                ) {
                    handGesture = HandGesture.SHH
                }

                onObservation(
                    VisionObservation(
                        faceVisible = faceVisible,
                        faceCenterX = faceX,
                        faceCenterY = faceY,
                        faceAreaRatio = faceArea,
                        smileProbability = smile,
                        leftEyeOpenProbability = leftEye,
                        rightEyeOpenProbability = rightEye,
                        headEulerX = eulerX,
                        headEulerY = eulerY,
                        headEulerZ = eulerZ,

                        objectCount = obj.count,
                        objectCenterX = obj.centerX,
                        objectCenterY = obj.centerY,
                        objectAreaRatio = obj.areaRatio,
                        objectLabel = obj.label,
                        objectConfidence = obj.confidence,

                        handGesture = handGesture,
                        handConfidence = gesture?.confidence ?: 0f,
                        handCenterX = handX,
                        handCenterY = handY,
                        timestampMs = now
                    )
                )
            }
            .addOnCompleteListener {
                proxy.close()
            }
    }

    fun stop() {
        provider?.unbindAll()
    }

    fun shutdown() {
        stop()
        faceDetector.close()
        gestureManager.close()
        objectManager.close()
        executor.shutdown()
    }
}
