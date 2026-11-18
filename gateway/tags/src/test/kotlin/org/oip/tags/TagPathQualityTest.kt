package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Path syntax: SPEC §5 and -
 * Quality codes: -
 * OPC UA numeric values: https://reference.opcfoundation.org/Core/Part6/docs/A.2
 * (StatusCode.csv in the OPC Foundation UA-Nodeset).
 */
class TagPathTest {
    private val base = TagPath.parse("[default]Plant/Line/Speed")

    @Test
    fun absoluteProviderFolderAndProperty() {
        val p = TagPath.parse("[default]Plant/Line/Speed.Quality")
        assertEquals("default", p.provider)
        assertEquals(listOf("Plant", "Line", "Speed"), p.segments)
        assertEquals("Quality", p.property)
        assertEquals("[default]Plant/Line/Speed.Quality", p.toString())
    }

    @Test
    fun omittedProviderUsesDefaultAndMatchIsCaseInsensitive() {
        val a = TagPath.parse("Plant/Line/Speed", "default")
        val b = TagPath.parse("[DEFAULT]plant/line/speed")
        assertEquals(a.canonicalKey(), b.canonicalKey())
        assertEquals(listOf("Plant", "Line", "Speed"), a.segments)
    }

    @Test
    fun relativeDotAndParent() {
        assertEquals("Plant/Line/Other", TagPath.parse("[.]Other").resolve(base).segments.joinToString("/"))
        assertEquals("Plant/Line/Other", TagPath.parse("./Other", "default").resolve(base).segments.joinToString("/"))
        assertEquals("Plant/Other", TagPath.parse("[.]../Other").resolve(base).segments.joinToString("/"))
        assertEquals("Plant/Other", TagPath.parse("../Other", "default").resolve(base).segments.joinToString("/"))
        assertEquals(listOf("Other"), TagPath.parse("[~]Other").resolve(base).segments)
    }

    @Test
    fun arrayIndexOnTheLeaf() {
        val p = TagPath.parse("[default]Tanks/Level[3]")
        assertEquals(listOf("Tanks", "Level"), p.segments)
        assertEquals(listOf(3), p.indexes)
        val nested = TagPath.parse("Arr[1][2].Value", "default")
        assertEquals(listOf(1, 2), nested.indexes)
        assertEquals("Value", nested.property)
    }

    @Test
    fun rejectsBrokenSyntax() {
        assertThrows<PathException> { TagPath.parse("[default") }
        assertThrows<PathException> { TagPath.parse("tag[-1]") }
        assertThrows<PathException> { TagPath.parse("tag[x]") }
        assertThrows<PathException> { TagPath.parse("a//b") }
    }
}

class QualityTest {
    @Test
    fun levelsAndDiagnostic() {
        assertEquals(Level.GOOD, Quality.GOOD.level)
        assertEquals(Level.UNCERTAIN, Quality.UNCERTAIN_LAST_KNOWN_VALUE.level)
        assertEquals(Level.BAD, Quality.BAD_NOT_CONNECTED.level)
        assertEquals(Level.ERROR, Quality.ERROR_EXPRESSION_EVAL.level)
        val q = Quality.BAD_NOT_CONNECTED.withDiagnostic("socket closed")
        assertEquals("Bad_NotConnected: socket closed", q.toString())
        assertTrue(Quality.BAD.worse(Quality.ERROR).level == Level.ERROR)
        assertTrue(Quality.UNCERTAIN.worse(Quality.GOOD).level == Level.UNCERTAIN)
    }

    @Test
    fun opcUaRoundTripForStandardCodes() {
        val cases = listOf(
            QualityCode.GOOD to 0x00000000,
            QualityCode.GOOD_OVERRIDDEN to 0x00960000,
            QualityCode.GOOD_OVERLOAD to 0x002F0000,
            QualityCode.UNCERTAIN to 0x40000000,
            QualityCode.UNCERTAIN_LAST_KNOWN_VALUE to 0x40900000,
            QualityCode.UNCERTAIN_ENGINEERING_UNITS_EXCEEDED to 0x40940000,
            QualityCode.UNCERTAIN_SUB_NORMAL to 0x40950000,
            QualityCode.BAD to 0x80000000.toInt(),
            QualityCode.BAD_NOT_CONNECTED to 0x808A0000.toInt(),
            QualityCode.BAD_NO_COMMUNICATION to 0x80310000.toInt(),
            QualityCode.BAD_NOT_WRITABLE to 0x803B0000.toInt(),
            QualityCode.BAD_OUT_OF_RANGE to 0x803C0000.toInt(),
            QualityCode.BAD_NOT_FOUND to 0x803E0000.toInt(),
            QualityCode.BAD_CONFIGURATION_ERROR to 0x80890000.toInt(),
            QualityCode.BAD_WAITING_FOR_INITIAL_DATA to 0x80320000.toInt(),
        )
        for ((code, status) in cases) {
            assertEquals(status, Quality(code).opcUaStatus, code.label)
            assertEquals(status, Quality(code).toOpcUa(), code.label)
            assertEquals(code, Quality.fromOpcUa(status).code, code.label)
        }
        assertEquals(QualityCode.BAD_NOT_CONNECTED, Quality.fromOpcUa(0x808A0001.toInt()).code)
        assertEquals(QualityCode.GOOD_OVERRIDDEN, Quality.fromName("Good_LocalOverride")!!.code)
        assertEquals(QualityCode.UNCERTAIN_LAST_KNOWN_VALUE, Quality.fromName("Uncertain_LastUsableValue")!!.code)
        assertEquals(QualityCode.BAD_NOT_WRITABLE, Quality.fromName("Bad_ReadOnly")!!.code)
    }

    @Test
    fun platformCodesHaveNoOneToOneOpcEquivalent() {
        assertNull(Quality.BAD_STALE.opcUaStatus)
        assertEquals(Level.BAD, Quality.BAD_STALE.level)
        assertEquals(0x80000000.toInt(), Quality.BAD_STALE.toOpcUa())
        assertNull(Quality.ERROR_CYCLE_DETECTED.opcUaStatus)
        assertEquals(0x80010000.toInt(), Quality.ERROR_CYCLE_DETECTED.toOpcUa())
        val unknown = Quality.fromOpcUa(0x80ABCDEF.toInt())
        assertEquals(QualityCode.BAD, unknown.code)
        assertTrue(unknown.diagnostic!!.contains("80abcdef"))
    }

    @Test
    fun timestampUsesSourceWhenPresentAndTruncatesToMicroseconds() {
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"), ZoneOffset.UTC)
        val source = Instant.parse("2026-01-01T00:00:01.000001999Z")
        val qv = QualifiedValue.of(1, Quality.GOOD, clock, source)
        assertEquals(1_000, qv.timestamp.nano)
        assertEquals(qv.timestamp, qv.sourceTimestamp)
        val local = QualifiedValue.of(1, Quality.GOOD, clock, null)
        assertEquals(123_456_000, local.timestamp.nano)
        assertNull(local.sourceTimestamp)
        assertEquals(Instant.parse("2026-01-01T00:00:00.123456Z"), local.timestamp)
    }
}
