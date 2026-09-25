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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.shehan.robotpet.brain.HandGesture
import com.shehan.robotpet.brain.VisionObservation
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class VisionManager(
    private val context: Context,
    private val onObservation: (VisionObservation) -> Unit,
    private val onFrame: ((Bitmap) -> Unit)? = null
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
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(0.12f)
            .build()
    )

    private var kissCandidateSinceMs = 0L
    private var lastKissMs = 0L
    private var handNearMouthMs = 0L
    private var previousHandFaceDistance = 9f
    private var lastBlownKissMs = 0L

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
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }
            if (front.isFailure) {
                usingFrontCamera = false
                runCatching {
                    provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
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

        val padded = Bitmap.createBitmap(paddedWidth, proxy.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val source = Bitmap.createBitmap(padded, 0, 0, proxy.width, proxy.height)
        val rotation = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotation, true)
        val bitmap = if (usingFrontCamera) {
            val mirror = Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(rotated, 0, 0, rotated.width, rotated.height, mirror, true)
        } else rotated

        val now = System.currentTimeMillis()
        runCatching { onFrame?.invoke(bitmap) }
        val width = bitmap.width.toFloat().coerceAtLeast(1f)
        val height = bitmap.height.toFloat().coerceAtLeast(1f)

        val gesture = runCatching { gestureManager.analyze(bitmap) }.getOrNull()
        val objects = runCatching { objectManager.analyze(bitmap, now) }.getOrDefault(ObjectFrame())
        val input = InputImage.fromBitmap(bitmap, 0)

        faceDetector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
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
                var kissConfidence = 0f
                var kissDetected = false
                var blownKiss = false

                if (face != null) {
                    faceVisible = true
                    faceX = (face.boundingBox.centerX() / width).coerceIn(0f, 1f)
                    faceY = (face.boundingBox.centerY() / height).coerceIn(0f, 1f)
                    faceArea = ((face.boundingBox.width() * face.boundingBox.height()) / (width * height)).coerceIn(0f, 1f)
                    smile = face.smilingProbability ?: -1f
                    leftEye = face.leftEyeOpenProbability ?: -1f
                    rightEye = face.rightEyeOpenProbability ?: -1f
                    eulerX = face.headEulerAngleX
                    eulerY = face.headEulerAngleY
                    eulerZ = face.headEulerAngleZ

                    kissConfidence = kissScore(face)
                    val puckerCandidate = kissConfidence >= 0.70f && smile < 0.55f && abs(eulerY) < 24f
                    if (puckerCandidate) {
                        if (kissCandidateSinceMs == 0L) kissCandidateSinceMs = now
                        if (now - kissCandidateSinceMs >= 420L && now - lastKissMs > 3200L) {
                            kissDetected = true
                            lastKissMs = now
                            kissCandidateSinceMs = now
                        }
                    } else {
                        kissCandidateSinceMs = 0L
                    }

                    if (gesture?.handPresent == true) {
                        val handDistance = distance(gesture.centerX, gesture.centerY, faceX, faceY)
                        if (handDistance < 0.22f && (puckerCandidate || now - lastKissMs < 1000L)) {
                            handNearMouthMs = now
                        }
                        if (
                            now - handNearMouthMs < 1700L &&
                            handDistance > 0.30f &&
                            handDistance - previousHandFaceDistance > 0.055f &&
                            now - lastBlownKissMs > 3500L &&
                            (puckerCandidate || now - lastKissMs < 1700L)
                        ) {
                            blownKiss = true
                            lastBlownKissMs = now
                            handNearMouthMs = 0L
                        }
                        previousHandFaceDistance = handDistance
                    } else {
                        previousHandFaceDistance = 9f
                    }
                } else {
                    kissCandidateSinceMs = 0L
                    previousHandFaceDistance = 9f
                }

                var handGesture = gesture?.gesture ?: HandGesture.NONE
                val handX = gesture?.centerX ?: 0.5f
                val handY = gesture?.centerY ?: 0.5f

                // Finger vertically near the lower half of the face -> shh.
                if (
                    handGesture == HandGesture.POINT_UP &&
                    faceVisible &&
                    abs(handX - faceX) < 0.16f &&
                    handY > faceY - 0.02f &&
                    abs(handY - faceY) < 0.26f
                ) handGesture = HandGesture.SHH

                val primary = objects.detections.firstOrNull()
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
                        kissConfidence = kissConfidence,
                        kissDetected = kissDetected,
                        blownKissDetected = blownKiss,
                        objects = objects.detections,
                        objectCount = objects.count,
                        objectCenterX = primary?.centerX ?: 0.5f,
                        objectCenterY = primary?.centerY ?: 0.5f,
                        objectAreaRatio = primary?.areaRatio ?: 0f,
                        objectLabel = primary?.label,
                        objectConfidence = primary?.confidence ?: 0f,
                        handPresent = gesture?.handPresent == true,
                        rawHandLabel = gesture?.rawLabel.orEmpty(),
                        handGesture = handGesture,
                        handCandidate = gesture?.candidate ?: HandGesture.NONE,
                        gestureStage = gesture?.stage ?: "NONE",
                        handConfidence = gesture?.confidence ?: 0f,
                        handCenterX = handX,
                        handCenterY = handY,
                        timestampMs = now
                    )
                )
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun kissScore(face: Face): Float {
        val upper = face.getContour(FaceContour.UPPER_LIP_TOP)?.points.orEmpty()
        val lower = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points.orEmpty()
        val all = upper + lower
        if (all.size < 8 || face.boundingBox.width() <= 0 || face.boundingBox.height() <= 0) return 0f

        val mouthWidth = (all.maxOf { it.x } - all.minOf { it.x }).coerceAtLeast(1f)
        val upperY = upper.map { it.y }.average().toFloat()
        val lowerY = lower.map { it.y }.average().toFloat()
        val mouthGap = abs(lowerY - upperY)
        val widthRatio = mouthWidth / face.boundingBox.width().toFloat()
        val gapRatio = mouthGap / face.boundingBox.height().toFloat()

        // A pucker usually narrows the mouth and keeps the vertical opening compact.
        val narrow = ((0.42f - widthRatio) / 0.18f).coerceIn(0f, 1f)
        val compact = ((0.105f - gapRatio) / 0.080f).coerceIn(0f, 1f)
        return (narrow * 0.72f + compact * 0.28f).coerceIn(0f, 1f)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun stop() { provider?.unbindAll() }

    fun shutdown() {
        stop()
        faceDetector.close()
        gestureManager.close()
        objectManager.close()
        executor.shutdown()
    }
}
