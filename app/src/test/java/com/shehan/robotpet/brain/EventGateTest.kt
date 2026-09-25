package com.shehan.robotpet.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventGateTest {
    @Test
    fun heldSignalFiresOnlyOnceUntilReleased() {
        val g = OneShotEventGate(confirmMs = 500L, releaseMs = 400L)
        assertFalse(g.update(true, 0L))
        assertFalse(g.update(true, 300L))
        assertTrue(g.update(true, 500L))
        assertFalse(g.update(true, 2000L))
        assertFalse(g.update(true, 8000L))
        assertFalse(g.update(false, 8100L))
        assertFalse(g.update(false, 8400L))
        assertFalse(g.update(false, 8500L))
        assertFalse(g.update(true, 8600L))
        assertTrue(g.update(true, 9100L))
    }
}
