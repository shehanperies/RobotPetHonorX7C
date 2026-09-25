package com.shehan.robotpet.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBrainTest {
    @Test
    fun stopCommandAlwaysStopsAndInterrupts() {
        val brain = PetBrain()
        val d = brain.onSpeech("please stop", RobotTelemetry(connected = true, safeToMove = true))
        assertEquals(MotionCommand.STOP, d.motion)
        assertTrue(d.interruptMotion)
    }

    @Test
    fun unsafeControllerBlocksForward() {
        val d = PetBrain().onSpeech("go forward", RobotTelemetry(connected = true, safeToMove = false))
        assertEquals(MotionCommand.STOP, d.motion)
        assertTrue(d.interruptMotion)
    }

    @Test
    fun openPalmStops() {
        val d = PetBrain().onVision(
            VisionObservation(handGesture = HandGesture.OPEN_PALM, handConfidence = 0.95f, timestampMs = 10_000L),
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.STOP, d.motion)
        assertTrue(d.interruptMotion)
    }

    @Test
    fun pointUpEmitsRealForkUpCommandWhenSafe() {
        val d = PetBrain().onVision(
            VisionObservation(handGesture = HandGesture.POINT_UP, timestampMs = 40_000L),
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.FORK_UP, d.motion)
    }

    @Test
    fun tickleProducesFastForkBounce() {
        val d = PetBrain().onTickle()
        assertEquals(Emotion.PLAYFUL, d.emotion)
        assertTrue(d.sequence.size >= 4)
        assertEquals(MotionCommand.FORK_UP, d.sequence.first().command)
        assertEquals(MotionCommand.FORK_DOWN, d.sequence[1].command)
    }

    @Test
    fun kissProducesLoveAndForkReaction() {
        val d = PetBrain().onVision(
            VisionObservation(faceVisible = true, kissDetected = true, kissConfidence = 0.9f, timestampMs = 20_000L),
            RobotTelemetry()
        )
        assertEquals(Emotion.LOVE, d.emotion)
        assertTrue(d.sequence.isNotEmpty())
    }

    @Test
    fun moveObjectRequiresDistanceSafety() {
        val brain = PetBrain()
        brain.onVision(
            VisionObservation(
                objects = listOf(DetectedObject(1, "cup", 0.9f, 0.5f, 0.5f, 0.08f)),
                objectLabel = "cup",
                objectConfidence = 0.9f,
                timestampMs = 10_000L
            ),
            RobotTelemetry()
        )
        val blocked = brain.onSpeech("move that", RobotTelemetry(connected = true, safeToMove = true))
        assertTrue(blocked.sequence.isEmpty())
        assertTrue(blocked.status.contains("blocked", ignoreCase = true))

        val allowed = brain.onSpeech(
            "move that",
            RobotTelemetry(connected = true, safeToMove = true, centerCm = 18f)
        )
        assertFalse(allowed.sequence.isEmpty())
    }

    @Test
    fun seeingFarFaceDoesNotAutoDriveWithoutFollowCommand() {
        val d = PetBrain().onVision(
            VisionObservation(faceVisible = true, faceCenterX = 0.1f, faceAreaRatio = 0.01f, timestampMs = 70_000L),
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.STOP, d.motion)
    }
}
