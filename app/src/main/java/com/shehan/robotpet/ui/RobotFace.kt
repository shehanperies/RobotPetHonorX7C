package com.shehan.robotpet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
    modifier: Modifier = Modifier
) {
    val blink = remember { Animatable(1f) }
    var drift by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2800, 6500))
            blink.animateTo(0.05f, tween(75))
            blink.animateTo(1f, tween(120))
            drift = Random.nextFloat() * 0.1f - 0.05f
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onTouch() }
            }
    ) {
        val w = size.width
        val h = size.height
        val eyeW = w * 0.23f
        val baseEyeH = h * 0.13f

        val emotionScale = when (emotion) {
            Emotion.HAPPY -> 0.58f
            Emotion.SLEEPY -> 0.22f
            Emotion.LISTENING -> 1.08f
            Emotion.STARTLED -> 1.18f
            Emotion.SAD -> 0.66f
            Emotion.ANGRY -> 0.62f
            Emotion.PLAYFUL -> 0.82f
            else -> 1f
        }

        val eyeH = baseEyeH * blink.value * emotionScale
        val xShift = (gazeX + drift).coerceIn(-1f, 1f) * w * 0.055f
        val yShift = gazeY.coerceIn(-1f, 1f) * h * 0.025f
        val centerY = h * 0.46f + yShift
        val leftCenter = Offset(w * 0.34f + xShift, centerY)
        val rightCenter = Offset(w * 0.66f + xShift, centerY)

        val glow = when (emotion) {
            Emotion.ANGRY -> Color(0xFFFF675C)
            Emotion.SAD -> Color(0xFF79A8FF)
            Emotion.PLAYFUL -> Color(0xFFC38BFF)
            Emotion.STARTLED -> Color(0xFFFFD86A)
            Emotion.HAPPY -> Color(0xFF79F5D0)
            else -> Color(0xFF7DE7FF)
        }

        fun drawEye(center: Offset, rotation: Float) {
            rotate(rotation, center) {
                drawRoundRect(
                    color = glow.copy(alpha = 0.12f),
                    topLeft = Offset(
                        center.x - eyeW * 0.56f,
                        center.y - eyeH * 0.62f
                    ),
                    size = Size(
                        eyeW * 1.12f,
                        eyeH * 1.24f
                    ),
                    cornerRadius = CornerRadius(eyeH * 0.48f)
                )

                drawRoundRect(
                    color = glow,
                    topLeft = Offset(
                        center.x - eyeW / 2,
                        center.y - eyeH / 2
                    ),
                    size = Size(
                        eyeW,
                        eyeH.coerceAtLeast(5f)
                    ),
                    cornerRadius = CornerRadius(eyeH * 0.45f)
                )
            }
        }

        val tilt = when (emotion) {
            Emotion.CURIOUS -> 4f
            Emotion.STARTLED -> -5f
            Emotion.SAD -> 9f
            Emotion.ANGRY -> -11f
            Emotion.PLAYFUL -> -3f
            else -> 0f
        }

        drawEye(leftCenter, tilt)
        drawEye(rightCenter, -tilt)
    }
}
