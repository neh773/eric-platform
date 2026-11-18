package org.oip.security

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Review-1 F14: wall-clock ceilings do not run in the default `:gateway:security:test` suite, where a loaded
 * runner can fail them with no code change. They carry the `benchmark` tag, which the default suite excludes
 * and `securityBenchmarkTest` (part of `check`) runs. Their ceilings are unchanged.
 */
class BenchmarkSplitTest {
    @Test fun timingCeilingsAreTaggedAndExcludedFromTheUnitSuite() {
        for (cls in listOf(SecurityBenchmarkTest::class.java, AuthGuardScaleTest::class.java)) {
            val tags = cls.getAnnotationsByType(Tag::class.java).map { it.value }
            assertTrue("benchmark" in tags, "${cls.simpleName} is not tagged benchmark: $tags")
        }
        val excluded = System.getProperty("oip.test.excludedTags").orEmpty().split(',').map { it.trim() }
        assertTrue("benchmark" in excluded, "the default test task does not exclude the benchmark tag: $excluded")
    }
}
