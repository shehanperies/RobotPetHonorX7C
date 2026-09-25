package com.shehan.robotpet.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BehaviorExecutiveTest {
    @Test
    fun onePrimaryBehaviorBlocksFruitSalad() {
        val e = BehaviorExecutive()
        val first = e.submit(
            BrainDecision(
                emotion = Emotion.HAPPY,
                speech = "Hi",
                sequence = listOf(MotionStep(MotionCommand.FORK_UP, 200L)),
                behaviorKey = "greet",
                minimumHoldMs = 2200L
            ),
            DecisionSource.GESTURE,
            now = 1000L
        )
        assertTrue(BehaviorResource.PRIMARY in first.allowed)

        val second = e.submit(
            BrainDecision(
                emotion = Emotion.CURIOUS,
                speech = "A bottle!",
                behaviorKey = "object-bottle",
                minimumHoldMs = 1800L
            ),
            DecisionSource.VISION,
            now = 1200L
        )
        assertTrue(BehaviorResource.PRIMARY in second.denied)
        assertTrue(second.decision.speech == null)
    }

    @Test
    fun safetyStillPreemptsPrimary() {
        val e = BehaviorExecutive()
        e.submit(
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
        val stop = e.submit(
            BrainDecision(
                Emotion.STARTLED,
                motion = MotionCommand.STOP,
                interruptMotion = true,
                behaviorKey = "safety-stop"
            ),
            DecisionSource.SAFETY,
            now = 1100L
        )
        assertTrue(BehaviorResource.PRIMARY in stop.allowed)
        assertTrue(BehaviorResource.DRIVE in stop.allowed)
    }

    @Test
    fun duplicateGestureDoesNotRestartSequence() {
        val e = BehaviorExecutive()
        val d = BrainDecision(
            Emotion.PLAYFUL,
            sequence = listOf(MotionStep(MotionCommand.FORK_UP, 150L)),
            behaviorKey = "tickle",
            minimumHoldMs = 1800L
        )
        val a = e.submit(d, DecisionSource.GESTURE, now = 1000L)
        val b = e.submit(d, DecisionSource.GESTURE, now = 1300L)
        assertTrue(BehaviorResource.FORK in a.allowed)
        assertFalse(BehaviorResource.FORK in b.allowed)
    }

    @Test
    fun idleCannotReplaceActiveGesture() {
        val e = BehaviorExecutive()
        e.submit(
            BrainDecision(Emotion.HAPPY, speech = "Hey", behaviorKey = "wave", minimumHoldMs = 2000L),
            DecisionSource.GESTURE,
            now = 1000L
        )
        val idle = e.submit(
            BrainDecision(Emotion.CURIOUS, speech = "Hmm", behaviorKey = "idle-chat"),
            DecisionSource.IDLE,
            now = 1300L
        )
        assertFalse(BehaviorResource.PRIMARY in idle.allowed)
        assertEquals("wave", e.activePrimaryKey(1300L))
    }
}
