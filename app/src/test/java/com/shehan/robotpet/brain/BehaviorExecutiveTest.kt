package com.shehan.robotpet.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorExecutiveTest {
    @Test
    fun idleCannotStealForkFromGesture() {
        val executive = BehaviorExecutive()
        val gesture = BrainDecision(
            emotion = Emotion.HAPPY,
            sequence = listOf(MotionStep(MotionCommand.FORK_UP, 200L)),
            behaviorKey = "wave",
            minimumHoldMs = 2000L
        )
        val first = executive.submit(gesture, DecisionSource.GESTURE, now = 1000L)
        assertTrue(BehaviorResource.FORK in first.allowed)

        val idle = BrainDecision(
            emotion = Emotion.PLAYFUL,
            sequence = listOf(MotionStep(MotionCommand.FORK_DOWN, 200L)),
            behaviorKey = "idle-play"
        )
        val second = executive.submit(idle, DecisionSource.IDLE, now = 1300L)
        assertFalse(BehaviorResource.FORK in second.allowed)
        assertTrue(BehaviorResource.FORK in second.denied)
    }

    @Test
    fun safetyPreemptsEverything() {
        val executive = BehaviorExecutive()
        executive.submit(
            BrainDecision(
                Emotion.HAPPY,
                motion = MotionCommand.FORWARD,
                motionDurationMs = 2000L,
                behaviorKey = "follow",
                minimumHoldMs = 2000L
            ),
            DecisionSource.FOLLOW,
            now = 1000L
        )
        val stop = executive.submit(
            BrainDecision(
                Emotion.STARTLED,
                motion = MotionCommand.STOP,
                interruptMotion = true,
                behaviorKey = "safety"
            ),
            DecisionSource.SAFETY,
            now = 1100L
        )
        assertTrue(BehaviorResource.DRIVE in stop.allowed)
        assertTrue(BehaviorResource.DRIVE in stop.preempted)
    }

    @Test
    fun listeningBlocksGestureFork() {
        val executive = BehaviorExecutive()
        executive.setListening(true, now = 1000L)
        val result = executive.submit(
            BrainDecision(
                Emotion.PLAYFUL,
                sequence = listOf(MotionStep(MotionCommand.FORK_UP, 150L)),
                behaviorKey = "tickle"
            ),
            DecisionSource.GESTURE,
            now = 1100L
        )
        assertFalse(BehaviorResource.FORK in result.allowed)
    }
}
