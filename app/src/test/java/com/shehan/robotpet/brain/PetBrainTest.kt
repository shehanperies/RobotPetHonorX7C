package com.shehan.robotpet.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBrainTest {
    @Test
    fun stopCommandAlwaysStopsAndInterrupts() {
        val brain = PetBrain()
        val d = brain.onSpeech(
            "please stop",
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.STOP, d.motion)
        assertTrue(d.interruptMotion)
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
        assertTrue(d.interruptMotion)
    }

    @Test
    fun thumbsUpIsHappyAndUsesForkExpression() {
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
    fun lostPersonWaitsBeforePhysicalSearch() {
        val brain = PetBrain()

        brain.onVision(
            VisionObservation(
                faceVisible = true,
                faceCenterX = 0.5f,
                timestampMs = 1_000L
            ),
            RobotTelemetry(connected = true, safeToMove = true)
        )

        val justLost = brain.onVision(
            VisionObservation(
                faceVisible = false,
                timestampMs = 6_000L
            ),
            RobotTelemetry(connected = true, safeToMove = true)
        )

        assertEquals(MotionCommand.STOP, justLost.motion)
        assertTrue(justLost.status.contains("waiting", ignoreCase = true))

        val afterMaximumWait = brain.onVision(
            VisionObservation(
                faceVisible = false,
                timestampMs = 14_000L
            ),
            RobotTelemetry(connected = true, safeToMove = true)
        )

        assertTrue(
            afterMaximumWait.motion == MotionCommand.LEFT ||
                afterMaximumWait.motion == MotionCommand.RIGHT
        )
    }

    @Test
    fun seeingFarFaceDoesNotAutoDriveWithoutFollowCommand() {
        val brain = PetBrain()
        val d = brain.onVision(
            VisionObservation(
                faceVisible = true,
                faceCenterX = 0.1f,
                faceAreaRatio = 0.01f,
                timestampMs = 70_000L
            ),
            RobotTelemetry(connected = true, safeToMove = true)
        )
        assertEquals(MotionCommand.STOP, d.motion)
        assertEquals(PetMode.ENGAGED, d.mode)
    }

    @Test
    fun aiCannotBypassFollowPermission() {
        val brain = PetBrain()
        brain.setFollowEnabled(false)

        brain.onVision(
            VisionObservation(
                faceVisible = true,
                timestampMs = 100_000L
            ),
            RobotTelemetry()
        )

        val d = brain.onAiDirective(
            AiDirective(action = AiAction.FOLLOW, speech = "Let's go"),
            RobotTelemetry(connected = true, safeToMove = true),
            now = 101_000L
        )

        assertEquals(MotionCommand.STOP, d.motion)
        assertTrue(d.status.contains("ignored"))
    }
}
