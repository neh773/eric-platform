package org.oip.reporting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Expression parameters (FEATURES REP-034). */
class ParameterExpressionTest {
    private val now = Instant.parse("2026-10-28T06:30:15Z").toEpochMilli() // a Wednesday
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun expr(name: String, type: ParameterType, text: String) =
        ParameterDef(name, type, null, null, false, false, expression = text)

    private fun value(type: ParameterType, text: String, vararg earlier: ParameterDef): Any? =
        Parameters.resolve(earlier.toList() + expr("x", type, text), emptyMap(), now).last().value

    @Test
    fun relativeTimesAndRanges() {
        assertEquals(now - 86_400_000L, value(ParameterType.TIMESTAMP, "now - 24h"))
        assertEquals(at("2026-10-28T00:00:00Z"), value(ParameterType.TIMESTAMP, "startOfDay(now)"))
        assertEquals(at("2026-10-26T00:00:00Z"), value(ParameterType.TIMESTAMP, "startOfWeek(now)"))
        assertEquals(at("2026-10-01T00:00:00Z"), value(ParameterType.TIMESTAMP, "startOfMonth(now)"))
        assertEquals(at("2026-10-28T06:00:00Z"), value(ParameterType.TIMESTAMP, "startOfHour(now)"))
        // Berlin is UTC+1 on 2026-10-28 (summer time ended on the 25th): its midnight is 23:00Z the day before.
        assertEquals(at("2026-10-27T23:00:00Z"), value(ParameterType.TIMESTAMP, "startOfDay(now, 'Europe/Berlin')"))
        val yesterday = mapOf("from" to at("2026-10-27T00:00:00Z"), "to" to at("2026-10-28T00:00:00Z"))
        assertEquals(yesterday, value(ParameterType.TIME_RANGE, "range(startOfDay(now) - 1d, startOfDay(now))"))
    }

    @Test
    fun laterParametersReadEarlierOnes() {
        val day = expr("day", ParameterType.TIMESTAMP, "startOfDay(now) - 1d")
        val window = expr("window", ParameterType.TIME_RANGE, "range(day, day + 24h)")
        val resolved = Parameters.resolve(listOf(day, window), emptyMap(), now)
        assertEquals(at("2026-10-27T00:00:00Z"), resolved[0].value)
        assertEquals(mapOf("from" to at("2026-10-27T00:00:00Z"), "to" to at("2026-10-28T00:00:00Z")), resolved[1].value)
        val from = expr("from", ParameterType.TIMESTAMP, "window.from + 1h")
        assertEquals(at("2026-10-27T01:00:00Z"), Parameters.resolve(listOf(day, window, from), emptyMap(), now)[2].value)
    }

    @Test
    fun suppliedValueBeatsTheExpressionAndNumbersAndStringsWork() {
        val d = expr("n", ParameterType.NUMBER, "max(2, 3) * 4 + round(0.5)")
        assertEquals(13L, Parameters.resolve(listOf(d), emptyMap(), now).single().value)
        assertEquals(7L, Parameters.resolve(listOf(d), mapOf("n" to 7), now).single().value)
        assertEquals("Line L1", value(ParameterType.STRING, "concat('Line ', 'L', 1)"))
        assertEquals("AB", value(ParameterType.STRING, "upper('a') + 'B'"))
    }

    @Test
    fun badExpressionsAreRefused() {
        for (bad in listOf("now -", "foo(1)", "startOfDay()", "1 / 0", "'a' * 2", "now.from", "(1", "1 2", "startOfDay(now, 'Mars/Base')")) {
            assertThrows(ReportParameterException::class.java, { value(ParameterType.NUMBER, bad) }, bad)
        }
        assertThrows(ReportParameterException::class.java) { value(ParameterType.TIMESTAMP, "'yesterday'") }
    }

    @Test
    fun definitionsCheckExpressionsAtSave() {
        fun spec(vararg params: Map<String, Any?>) = mapOf(
            "parameters" to params.toList(),
            "dataSources" to listOf(mapOf("name" to "d", "type" to "namedQuery", "query" to "q")),
        )
        val ok = ReportDefinitions.parse("r", 1, spec(mapOf("name" to "t", "type" to "timestamp", "expression" to "startOfDay(now)")))
        assertEquals("startOfDay(now)", ok.parameters.single().expression)
        fun errors(vararg params: Map<String, Any?>) = ReportDefinitions.semanticErrors(spec(*params)).joinToString()
        assertTrue(errors(mapOf("name" to "t", "type" to "timestamp", "expression" to "now +")).contains("expression"), "syntax")
        assertTrue(errors(mapOf("name" to "t", "type" to "timestamp", "expression" to "'x'")).contains("expression"), "type")
        assertTrue(errors(mapOf("name" to "t", "type" to "timestamp", "expression" to "now", "default" to "now")).contains("either default or expression"))
        assertTrue(errors(mapOf("name" to "t", "type" to "timestamp", "expression" to "later")).contains("not declared before"))
        assertTrue(errors(mapOf("name" to "now", "type" to "timestamp")).contains("reserved"))
        assertEquals("", errors(mapOf("name" to "a", "type" to "timestamp", "expression" to "now"), mapOf("name" to "b", "type" to "timestamp", "expression" to "a - 1h")))
    }
}
