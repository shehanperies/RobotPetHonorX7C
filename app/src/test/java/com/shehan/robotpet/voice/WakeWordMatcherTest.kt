package com.shehan.robotpet.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {
    @Test fun acceptsWelly() = assertTrue(WakeWordMatcher.matches("Welly"))
    @Test fun acceptsHeyWelly() = assertTrue(WakeWordMatcher.matches("Hey Welly, are you there?"))
    @Test fun acceptsCommonWellieSpelling() = assertTrue(WakeWordMatcher.matches("wellie"))
    @Test fun rejectsReally() = assertFalse(WakeWordMatcher.matches("really nice"))
    @Test fun rejectsWell() = assertFalse(WakeWordMatcher.matches("well okay"))
}
