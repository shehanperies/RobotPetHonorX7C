package com.shehan.robotpet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.shehan.robotpet.brain.Emotion
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun RobotFace(
    emotion: Emotion,
    gazeX: Float,
    gazeY: Float,
    onTouch: () -> Unit,
    onPet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val blink = remember { Animatable(1f) }
    var drift by remember { mutableFloatStateOf(0f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }

    val emotionalHeightTarget = when (emotion) {
        Emotion.SLEEPY -> 0.58f
        Emotion.HAPPY, Emotion.LOVE -> 0.90f
        Emotion.SAD, Emotion.LONELY -> 0.84f
        Emotion.ANGRY -> 0.82f
        Emotion.PLAYFUL -> 0.96f
        Emotion.LISTENING -> 1.06f
        Emotion.THINKING -> 0.98f
        Emotion.SPEAKING -> 1.03f
        Emotion.STARTLED -> 1.14f
        Emotion.DIZZY -> 0.94f
        else -> 1f
    }
    val emotionalHeight by animateFloatAsState(
        targetValue = emotionalHeightTarget,
        animationSpec = tween(220),
        label = "eye-height"
    )
    val spacingTarget = when (emotion) {
        Emotion.STARTLED -> 1.03f
        Emotion.LOVE -> 0.98f
        Emotion.ANGRY -> 1.01f
        else -> 1f
    }
    val spacing by animateFloatAsState(spacingTarget, tween(220), label = "eye-spacing")

    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2800, 6500))
            blink.animateTo(0.06f, tween(65))
            blink.animateTo(1f, tween(115))
            drift = Random.nextFloat() * 0.07f - 0.035f
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(onTouch) { detectTapGestures { onTouch() } }
            .pointerInput(onPet) {
                detectDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onDrag = { change, amount ->
                        dragDistance += amount.getDistance()
                        change.consume()
                    },
                    onDragEnd = {
                        if (dragDistance > 120f) onPet()
                        dragDistance = 0f
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val short = minOf(w, h)
        val eyeW = short * 0.285f
        val baseEyeH = short * 0.165f
        val eyeH = (baseEyeH * emotionalHeight * blink.value).coerceAtLeast(short * 0.012f)
        val xShift = (gazeX + drift).coerceIn(-1f, 1f) * short * 0.052f
        val yShift = gazeY.coerceIn(-1f, 1f) * short * 0.032f
        val centerY = h * 0.50f + yShift
        val leftCenter = Offset(w * (0.32f / spacing) + xShift, centerY)
        val rightCenter = Offset(w * (1f - 0.32f / spacing) + xShift, centerY)

        val glow = when (emotion) {
            Emotion.ANGRY -> Color(0xFFFF675C)
            Emotion.SAD, Emotion.LONELY -> Color(0xFF79A8FF)
            Emotion.PLAYFUL, Emotion.DIZZY -> Color(0xFFC38BFF)
            Emotion.STARTLED -> Color(0xFFFFD86A)
            Emotion.HAPPY -> Color(0xFF79F5D0)
            Emotion.LOVE -> Color(0xFFFF8FCB)
            Emotion.LISTENING -> Color(0xFF8EEBFF)
            Emotion.THINKING -> Color(0xFF8FB5FF)
            Emotion.SPEAKING -> Color(0xFF91FFD6)
            else -> Color(0xFF7DE7FF)
        }

        val tilt = when (emotion) {
            Emotion.CURIOUS, Emotion.THINKING -> 4f
            Emotion.STARTLED -> -2f
            Emotion.SAD, Emotion.LONELY -> 7f
            Emotion.ANGRY -> -9f
            Emotion.PLAYFUL -> -3f
            Emotion.DIZZY -> 10f
            Emotion.LOVE -> -2f
            else -> 0f
        }

        fun drawEye(center: Offset, rotation: Float) {
            val rounding = when (emotion) {
                Emotion.ANGRY -> eyeH * 0.34f
                Emotion.HAPPY, Emotion.LOVE -> eyeH * 0.50f
                else -> eyeH * 0.44f
            }
            rotate(rotation, center) {
                drawRoundRect(
                    color = glow.copy(alpha = 0.12f),
                    topLeft = Offset(center.x - eyeW * 0.56f, center.y - eyeH * 0.63f),
                    size = Size(eyeW * 1.12f, eyeH * 1.26f),
                    cornerRadius = CornerRadius(rounding * 1.05f)
                )
                drawRoundRect(
                    color = glow,
                    topLeft = Offset(center.x - eyeW / 2f, center.y - eyeH / 2f),
                    size = Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(rounding)
                )
                // Small inner highlight gives depth without turning the eyes into flat capsules.
                if (blink.value > 0.45f && emotion !in setOf(Emotion.SLEEPY, Emotion.ANGRY)) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.14f),
                        topLeft = Offset(center.x - eyeW * 0.28f, center.y - eyeH * 0.25f),
                        size = Size(eyeW * 0.18f, eyeH * 0.18f),
                        cornerRadius = CornerRadius(eyeH * 0.09f)
                    )
                }
            }
        }

        drawEye(leftCenter, tilt)
        drawEye(rightCenter, -tilt)
    }
}
