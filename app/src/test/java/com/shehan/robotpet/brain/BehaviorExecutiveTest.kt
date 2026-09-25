package com.shehan.robotpet.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorExecutiveTest {
    @Test
    fun onePrimaryBehaviorBlocksFruitSalad() {
        val e = BehaviorExecutive()
        val first = e.submit(
            BrainDecision(Emotion.HAPPY, speech = "Hi", sequence = listOf(MotionStep(MotionCommand.FORK_UP, 200L)), behaviorKey = "greet", minimumHoldMs = 2200L),
            DecisionSource.GESTURE, 1000L
        )
        assertTrue(BehaviorResource.PRIMARY in first.allowed)
        val second = e.submit(
            BrainDecision(Emotion.CURIOUS, speech = "A bottle!", behaviorKey = "object-bottle", minimumHoldMs = 1800L),
            DecisionSource.VISION, 1200L
        )
        assertTrue(BehaviorResource.PRIMARY in second.denied)
        assertTrue(second.decision.speech == null)
    }

    @Test
    fun ttsNeverReplacesPrimary() {
        val e = BehaviorExecutive()
        e.submit(BrainDecision(Emotion.LOVE, speech = "Mwah", behaviorKey = "kiss", minimumHoldMs = 2500L), DecisionSource.GESTURE, 1000L)
        e.setSpeaking(true, 1100L)
        assertEquals("kiss", e.activePrimaryKey(1100L))
    }

    @Test
    fun lowPriorityInterruptCannotPunchThroughGesture() {
        val e = BehaviorExecutive()
        e.submit(BrainDecision(Emotion.HAPPY, speech = "Hi", behaviorKey = "wave", minimumHoldMs = 3000L), DecisionSource.GESTURE, 1000L)
        val bad = e.submit(
            BrainDecision(Emotion.CURIOUS, motion = MotionCommand.STOP, interruptMotion = true, behaviorKey = "owner-found"),
            DecisionSource.VISION, 1200L
        )
        assertFalse(BehaviorResource.PRIMARY in bad.allowed)
        assertEquals("wave", e.activePrimaryKey(1200L))
    }

    @Test
    fun speakingDropsVisionChatter() {
        val e = BehaviorExecutive()
        e.submit(BrainDecision(Emotion.HAPPY, speech = "Hello", behaviorKey = "touch", minimumHoldMs = 1000L), DecisionSource.TOUCH, 1000L)
        e.setSpeaking(true, 1200L)
        val v = e.submit(BrainDecision(Emotion.CURIOUS, speech = "Bottle", behaviorKey = "object-bottle"), DecisionSource.VISION, 5000L)
        assertFalse(BehaviorResource.PRIMARY in v.allowed)
    }

    @Test
    fun safetyStillPreemptsEverything() {
        val e = BehaviorExecutive()
        e.submit(BrainDecision(Emotion.HAPPY, motion = MotionCommand.FORWARD, motionDurationMs = 2000L, behaviorKey = "follow", minimumHoldMs = 2000L), DecisionSource.FOLLOW, 1000L)
        val stop = e.submit(BrainDecision(Emotion.STARTLED, motion = MotionCommand.STOP, interruptMotion = true, behaviorKey = "safety-stop"), DecisionSource.SAFETY, 1100L)
        assertTrue(BehaviorResource.PRIMARY in stop.allowed)
        assertTrue(BehaviorResource.DRIVE in stop.allowed)
    }
}
