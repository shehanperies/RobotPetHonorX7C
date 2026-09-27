package com.shehan.robotpet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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

private data class EyeMood(
    val leftH: Float = 1f,
    val rightH: Float = 1f,
    val leftRot: Float = 0f,
    val rightRot: Float = 0f,
    val leftY: Float = 0f,
    val rightY: Float = 0f,
    val width: Float = 1f
)

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
    var driftX by remember { mutableFloatStateOf(0f) }
    var driftY by remember { mutableFloatStateOf(0f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2400L, 5600L))
            blink.animateTo(0.06f, tween(55))
            blink.animateTo(1f, tween(105))
            driftX = Random.nextFloat() * 0.09f - 0.045f
            driftY = Random.nextFloat() * 0.05f - 0.025f
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
        val unit = minOf(w, h)

        val mood = when (emotion) {
            Emotion.IDLE -> EyeMood()
            Emotion.HAPPY -> EyeMood(0.62f, 0.62f, -3f, 3f, 0.01f, 0.01f, 1.02f)
            Emotion.CURIOUS -> EyeMood(1.06f, 0.78f, 5f, -2f, -0.02f, 0.015f, 0.98f)
            Emotion.LISTENING -> EyeMood(1.13f, 1.13f, 0f, 0f, -0.015f, -0.015f, 0.96f)
            Emotion.SLEEPY -> EyeMood(0.27f, 0.27f, 0f, 0f, 0.035f, 0.035f, 1.02f)
            Emotion.STARTLED -> EyeMood(1.30f, 1.30f, -2f, 2f, -0.02f, -0.02f, 0.90f)
            Emotion.SAD -> EyeMood(0.67f, 0.67f, 10f, -10f, 0.03f, 0.03f, 0.98f)
            Emotion.ANGRY -> EyeMood(0.58f, 0.58f, -13f, 13f, -0.015f, -0.015f, 1.02f)
            Emotion.PLAYFUL -> EyeMood(0.48f, 0.91f, -7f, 3f, 0.02f, -0.005f, 1.00f)
            Emotion.LOVE -> EyeMood(0.72f, 0.72f, -7f, 7f, -0.005f, -0.005f, 0.96f)
            Emotion.DIZZY -> EyeMood(0.78f, 0.98f, 13f, -13f, 0.015f, -0.015f, 0.92f)
            Emotion.LONELY -> EyeMood(0.54f, 0.54f, 8f, -8f, 0.045f, 0.045f, 0.94f)
        }

        val glow = when (emotion) {
            Emotion.ANGRY -> Color(0xFFFF675C)
            Emotion.SAD, Emotion.LONELY -> Color(0xFF79A8FF)
            Emotion.PLAYFUL, Emotion.DIZZY -> Color(0xFFC38BFF)
            Emotion.STARTLED -> Color(0xFFFFD86A)
            Emotion.HAPPY -> Color(0xFF79F5D0)
            Emotion.LOVE -> Color(0xFFFF8FCB)
            Emotion.LISTENING -> Color(0xFF8EEBFF)
            Emotion.SLEEPY -> Color(0xFF81C8FF)
            else -> Color(0xFF7DE7FF)
        }

        val eyeW = unit * 0.30f * mood.width
        val baseEyeH = unit * 0.165f
        val xShift = (gazeX + driftX).coerceIn(-1f, 1f) * unit * 0.055f
        val yShift = (gazeY + driftY).coerceIn(-1f, 1f) * unit * 0.034f
        val centerY = h * 0.50f + yShift

        fun drawEye(center: Offset, heightScale: Float, rotation: Float) {
            val eyeH = (baseEyeH * heightScale * blink.value).coerceAtLeast(unit * 0.012f)

            rotate(rotation, center) {
                drawRoundRect(
                    color = glow.copy(alpha = 0.11f),
                    topLeft = Offset(center.x - eyeW * 0.57f, center.y - eyeH * 0.72f),
                    size = Size(eyeW * 1.14f, eyeH * 1.44f),
                    cornerRadius = CornerRadius(eyeH * 0.58f)
                )
                drawRoundRect(
                    color = glow,
                    topLeft = Offset(center.x - eyeW / 2f, center.y - eyeH / 2f),
                    size = Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeH * 0.48f)
                )

                if (eyeH > unit * 0.045f && emotion != Emotion.ANGRY) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.18f),
                        topLeft = Offset(center.x - eyeW * 0.28f, center.y - eyeH * 0.28f),
                        size = Size(eyeW * 0.18f, eyeH * 0.18f),
                        cornerRadius = CornerRadius(eyeH * 0.09f)
                    )
                }
            }
        }

        val left = Offset(w * 0.32f + xShift, centerY + mood.leftY * unit)
        val right = Offset(w * 0.68f + xShift, centerY + mood.rightY * unit)

        drawEye(left, mood.leftH, mood.leftRot)
        drawEye(right, mood.rightH, mood.rightRot)
    }
}
