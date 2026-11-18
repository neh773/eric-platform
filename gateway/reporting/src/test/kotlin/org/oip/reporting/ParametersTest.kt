package org.oip.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ParametersTest {
    private val now = java.time.Instant.parse("2026-10-27T06:00:00Z").toEpochMilli()

    private fun p(name: String, type: ParameterType, default: Any? = null, hasDefault: Boolean = default != null, required: Boolean = false) =
        ParameterDef(name, type, null, default, hasDefault, required)

    @Test
    fun relativeAndAbsoluteRanges() {
        val defs = listOf(p("range", ParameterType.TIME_RANGE, mapOf("last" to "24h")))
        assertEquals(mapOf("from" to now - 86_400_000L, "to" to now), Parameters.resolve(defs, emptyMap(), now).single().value)
        val abs = Parameters.resolve(defs, mapOf("range" to mapOf("from" to "2026-10-27T00:00:00Z", "to" to "now")), now).single().value
        assertEquals(mapOf("from" to now - 21_600_000L, "to" to now), abs)
        assertEquals(mapOf("from" to now - 900_000L, "to" to now), Parameters.timeRange(mapOf("last" to "15m"), now))
        assertEquals(7 * 86_400_000L, Parameters.durationMillis("1w"))
    }

    @Test
    fun typesDefaultsAndErrors() {
        val defs = listOf(
            p("line", ParameterType.STRING, "L1"),
            p("count", ParameterType.NUMBER),
            p("on", ParameterType.BOOLEAN, required = true),
            p("at", ParameterType.TIMESTAMP, "now"),
        )
        val r = Parameters.resolve(defs, mapOf("count" to 5, "on" to true), now)
        assertEquals(listOf("L1", 5L, true, now), r.map { it.value })
        assertNull(Parameters.resolve(defs, mapOf("on" to false), now)[1].value)
        assertThrows(ReportParameterException::class.java) { Parameters.resolve(defs, emptyMap(), now) }
        assertThrows(ReportParameterException::class.java) { Parameters.resolve(defs, mapOf("on" to true, "nope" to 1), now) }
        assertThrows(ReportParameterException::class.java) { Parameters.resolve(defs, mapOf("on" to "yes"), now) }
        assertThrows(ReportParameterException::class.java) { Parameters.resolve(defs, mapOf("on" to true, "count" to "5"), now) }
        assertThrows(ReportParameterException::class.java) { Parameters.resolve(defs, mapOf("on" to true, "at" to "yesterday"), now) }
        val range = listOf(p("r", ParameterType.TIME_RANGE))
        for (bad in listOf(mapOf("last" to "0h"), mapOf("last" to "24x"), mapOf("from" to 10, "to" to 5), mapOf("from" to 1), "24h")) {
            assertThrows(ReportParameterException::class.java, { Parameters.resolve(range, mapOf("r" to bad), now) }, bad.toString())
        }
    }
}
