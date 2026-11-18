package org.oip.tags

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagPathPatternTest {
    @Test fun starStaysInOneSegmentAndDoubleStarCrossesSegments() {
        assertTrue(TagPathPattern.matches("[default]Line/*", "[default]Line/Pump"))
        assertFalse(TagPathPattern.matches("[default]Line/*", "[default]Line/Pump/Speed"))
        assertTrue(TagPathPattern.matches("[default]Line/**", "[default]Line/Pump/Speed"))
        assertTrue(TagPathPattern.matches("[default]P?mp", "[default]Pump"))
        assertFalse(TagPathPattern.matches("[default]P?mp", "[default]Pmp"))
        assertFalse(TagPathPattern.matches("[default]Line/*", "[default]Other/Pump"))
        assertFalse(TagPathPattern.isPattern("[default]Line/Pump"))
        assertTrue(TagPathPattern.isPattern("[default]Line/*"))
    }
}
