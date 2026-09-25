package com.shehan.robotpet.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBrainTest {
    @Test
    fun stopCommandAlwaysStops() {
        val brain = PetBrain()
        val d = brain.onSpeech(
            "please stop",
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.STOP, d.motion)
    }

    @Test
    fun unsafeControllerBlocksForward() {
        val brain = PetBrain()
        val d = brain.onSpeech(
            "go forward",
            RobotTelemetry(connected = true, safeToMove = false)
        )
        assertEquals(MotionCommand.STOP, d.motion)
        assertEquals(Emotion.STARTLED, d.emotion)
    }

    @Test
    fun openPalmStops() {
        val brain = PetBrain()
        val d = brain.onVision(
            VisionObservation(
                handGesture = HandGesture.OPEN_PALM,
                handConfidence = 0.95f,
                timestampMs = 10_000L
            ),
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.STOP, d.motion)
        assertTrue(d.status.contains("STOP"))
    }

    @Test
    fun thumbsUpIsHappy() {
        val brain = PetBrain()
        val d = brain.onVision(
            VisionObservation(
                handGesture = HandGesture.THUMBS_UP,
                handConfidence = 0.95f,
                timestampMs = 20_000L
            ),
            RobotTelemetry()
        )
        assertEquals(Emotion.HAPPY, d.emotion)
        assertTrue(d.sequence.isNotEmpty())
    }

    @Test
    fun thumbsDownIsSad() {
        val brain = PetBrain()
        val d = brain.onVision(
            VisionObservation(
                handGesture = HandGesture.THUMBS_DOWN,
                handConfidence = 0.95f,
                timestampMs = 30_000L
            ),
            RobotTelemetry()
        )
        assertEquals(Emotion.SAD, d.emotion)
    }

    @Test
    fun pointUpControlsForkWhenSafe() {
        val brain = PetBrain()
        val d = brain.onVision(
            VisionObservation(
                handGesture = HandGesture.POINT_UP,
                timestampMs = 40_000L
            ),
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.FORK_UP, d.motion)
    }

    @Test
    fun turnAroundUsesTurnMotionWhenSafe() {
        val brain = PetBrain()
        val d = brain.onVision(
            VisionObservation(
                handGesture = HandGesture.TURN_AROUND,
                timestampMs = 50_000L
            ),
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.LEFT, d.motion)
        assertTrue(d.motionDurationMs >= 1000L)
    }

    @Test
    fun lostPersonStartsSearch() {
        val brain = PetBrain()
        brain.onVision(
            VisionObservation(
                faceVisible = true,
                faceCenterX = 0.5f,
                timestampMs = 1_000L
            ),
            RobotTelemetry()
        )

        val d = brain.onVision(
            VisionObservation(
                faceVisible = false,
                timestampMs = 6_000L
            ),
            RobotTelemetry()
        )

        assertTrue(d.status.contains("Looking") || d.status.contains("Searching"))
    }
}
