package com.shehan.robotpet.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
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
import com.shehan.robotpet.brain.OneShotEventGate
import com.shehan.robotpet.brain.VisionObservation
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

private data class FaceAreaSample(val t: Long, val area: Float)

/**
 * V8 perception layer. Raw measurements are continuous, but semantic events are one-shot.
 * In particular, kiss/close-face events must be released before they can ever fire again.
 */
class VisionManager(
    private val context: Context,
    private val onObservation: (VisionObservation) -> Unit,
    private val onFrame: ((Bitmap) -> Unit)? = null
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var usingFrontCamera = true
    private var lastAnalyzeMono = 0L

    private val gestureManager = GestureManager(context)
    private val objectManager = ObjectDetectorManager(context)

    // Contours are required for the adaptive pucker signal. Tracking is intentionally disabled:
    // with contour mode ML Kit only returns the prominent face and tracking adds no value.
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build()
    )

    private val kissGate = OneShotEventGate(confirmMs = 700L, releaseMs = 700L, minimumGapMs = 1800L)
    private var puckerBaseline = 0f
    private var baselineSamples = 0
    private var faceSeenSinceMono = 0L
    private var lastFaceSeenMono = 0L

    private var handNearMouthMono = 0L
    private var previousHandFaceDistance = 9f
    private var blownLatched = false
    private var blownReleaseSince = 0L

    private val areaHistory = ArrayDeque<FaceAreaSample>()
    private var closeLatched = false
    private var closeReleaseSince = 0L

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
                runCatching { provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        val monoNow = SystemClock.elapsedRealtime()
        // Face/gesture perception at ~9 Hz is enough for a pet reaction engine and avoids
        // processing a backlog of nearly identical frames.
        if (monoNow - lastAnalyzeMono < 105L) {
            proxy.close()
            return
        }
        lastAnalyzeMono = monoNow

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

        val wallNow = System.currentTimeMillis()
        runCatching { onFrame?.invoke(bitmap) }
        val width = bitmap.width.toFloat().coerceAtLeast(1f)
        val height = bitmap.height.toFloat().coerceAtLeast(1f)

        val gesture = runCatching { gestureManager.analyze(bitmap) }.getOrNull()
        val objects = runCatching { objectManager.analyze(bitmap, wallNow) }.getOrDefault(ObjectFrame())
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
                var adaptiveKissConfidence = 0f
                var kissDetected = false
                var blownKiss = false
                var closeApproach = false

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

                    if (faceSeenSinceMono == 0L || monoNow - lastFaceSeenMono > 1200L) {
                        faceSeenSinceMono = monoNow
                        baselineSamples = 0
                        puckerBaseline = 0f
                        areaHistory.clear()
                    }
                    lastFaceSeenMono = monoNow

                    val rawPucker = puckerScore(face)
                    if (baselineSamples == 0) {
                        puckerBaseline = rawPucker
                        baselineSamples = 1
                    }
                    val faceStable = monoNow - faceSeenSinceMono >= 1400L
                    val baselineReady = faceStable && baselineSamples >= 9
                    val threshold = maxOf(0.80f, puckerBaseline + 0.17f).coerceAtMost(0.98f)
                    val frontal = abs(eulerY) < 23f && abs(eulerX) < 20f
                    val puckerActive = baselineReady && frontal && smile < 0.52f && rawPucker >= threshold

                    // Learn the person's normal mouth shape only while clearly not trying to pucker.
                    if (!puckerActive && kissGate.state != com.shehan.robotpet.brain.EventGateState.WAIT_RELEASE && frontal) {
                        val alpha = if (baselineSamples < 12) 0.20f else 0.035f
                        puckerBaseline = if (baselineSamples == 0) rawPucker else puckerBaseline * (1f - alpha) + rawPucker * alpha
                        baselineSamples = (baselineSamples + 1).coerceAtMost(1000)
                    }

                    adaptiveKissConfidence = if (!baselineReady) 0f else {
                        ((rawPucker - puckerBaseline) / 0.24f).coerceIn(0f, 1f)
                    }
                    kissDetected = kissGate.update(puckerActive, monoNow)

                    updateCloseApproach(faceArea, eulerY, monoNow).also { closeApproach = it }

                    if (gesture?.handPresent == true) {
                        val handDistance = distance(gesture.centerX, gesture.centerY, faceX, faceY)
                        if (handDistance < 0.22f && (puckerActive || kissDetected || adaptiveKissConfidence > 0.70f)) {
                            handNearMouthMono = monoNow
                        }
                        val outward = handDistance > 0.31f && handDistance - previousHandFaceDistance > 0.060f
                        if (!blownLatched && monoNow - handNearMouthMono < 1700L && outward &&
                            (puckerActive || adaptiveKissConfidence > 0.60f || monoNow - handNearMouthMono < 900L)) {
                            blownKiss = true
                            blownLatched = true
                            blownReleaseSince = 0L
                            handNearMouthMono = 0L
                        }
                        previousHandFaceDistance = handDistance
                        if (blownLatched) blownReleaseSince = 0L
                    } else {
                        previousHandFaceDistance = 9f
                        if (blownLatched) {
                            if (blownReleaseSince == 0L) blownReleaseSince = monoNow
                            if (monoNow - blownReleaseSince >= 650L) {
                                blownLatched = false
                                blownReleaseSince = 0L
                            }
                        }
                    }
                } else {
                    kissGate.update(false, monoNow)
                    previousHandFaceDistance = 9f
                    if (closeLatched) {
                        if (closeReleaseSince == 0L) closeReleaseSince = monoNow
                        if (monoNow - closeReleaseSince >= 900L) {
                            closeLatched = false
                            closeReleaseSince = 0L
                        }
                    }
                    if (monoNow - lastFaceSeenMono > 2500L) {
                        faceSeenSinceMono = 0L
                        baselineSamples = 0
                        puckerBaseline = 0f
                        areaHistory.clear()
                    }
                }

                var handGesture = gesture?.gesture ?: HandGesture.NONE
                val handX = gesture?.centerX ?: 0.5f
                val handY = gesture?.centerY ?: 0.5f
                if (
                    handGesture == HandGesture.POINT_UP && faceVisible &&
                    abs(handX - faceX) < 0.16f && handY > faceY - 0.02f && abs(handY - faceY) < 0.26f
                ) handGesture = HandGesture.SHH

                val primary = objects.detections.firstOrNull()
                onObservation(
                    VisionObservation(
                        faceVisible = faceVisible,
                        faceCenterX = faceX,
                        faceCenterY = faceY,
                        faceAreaRatio = faceArea,
                        faceStable = faceVisible && monoNow - faceSeenSinceMono >= 900L,
                        closeApproachDetected = closeApproach,
                        smileProbability = smile,
                        leftEyeOpenProbability = leftEye,
                        rightEyeOpenProbability = rightEye,
                        headEulerX = eulerX,
                        headEulerY = eulerY,
                        headEulerZ = eulerZ,
                        kissConfidence = adaptiveKissConfidence,
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
                        timestampMs = wallNow
                    )
                )
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun updateCloseApproach(area: Float, eulerY: Float, now: Long): Boolean {
        while (areaHistory.isNotEmpty() && now - areaHistory.first().t > 1400L) areaHistory.removeFirst()
        val old = areaHistory.firstOrNull { now - it.t >= 520L }
        areaHistory.addLast(FaceAreaSample(now, area))

        if (closeLatched) {
            if (area < 0.15f) {
                if (closeReleaseSince == 0L) closeReleaseSince = now
                if (now - closeReleaseSince >= 900L) {
                    closeLatched = false
                    closeReleaseSince = 0L
                }
            } else closeReleaseSince = 0L
            return false
        }

        if (old == null || old.area < 0.012f) return false
        val ratio = area / old.area
        val approached = area > 0.20f && ratio >= 1.42f && abs(eulerY) < 28f
        if (approached) {
            closeLatched = true
            closeReleaseSince = 0L
            return true
        }
        return false
    }

    private fun puckerScore(face: Face): Float {
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
