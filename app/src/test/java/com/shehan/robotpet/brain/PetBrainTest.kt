package com.shehan.robotpet.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBrainTest {
    private val brain = PetBrain()

    @Test fun stopCommandAlwaysStops() {
        val d = brain.onSpeech("please stop", RobotTelemetry(connected = true, safeToMove = true))
        assertEquals(MotionCommand.STOP, d.motion)
    }

    @Test fun unsafeControllerBlocksForward() {
        val d = brain.onSpeech("go forward", RobotTelemetry(connected = true, safeToMove = false))
        assertEquals(MotionCommand.STOP, d.motion)
        assertEquals(Emotion.STARTLED, d.emotion)
    }

    @Test fun faceMovesGazeWithoutRobotConnection() {
        val d = brain.onVision(VisionObservation(faceVisible = true, faceCenterX = 0.85f), RobotTelemetry())
        assertTrue(d.gazeX > 0.4f)
        assertEquals(MotionCommand.STOP, d.motion)
    }
}
