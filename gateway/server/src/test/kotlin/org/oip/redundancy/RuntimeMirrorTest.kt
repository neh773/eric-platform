package org.oip.redundancy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmJournal
import org.oip.gateway.Json
import org.oip.tags.DataType
import org.oip.tags.QualityCode
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Clock
import org.junit.jupiter.api.Assertions.assertEquals

class RuntimeMirrorTest {
    @TempDir
    lateinit var tmp: Path

    /**
     * A configured device tag can itself report Bad_NotFound (its node went away on the device). That is
     * not a tag missing on this node: the mirror must keep applying its values, and report as missed only
     * rows for tags that are not configured here.
     */
    @Test
    fun aConfiguredTagReportingBadNotFoundStillTakesTheNextValue() {
        val tags = TagEngine()
        tags.configure(TagConfig(path = "Plc/Speed", type = TagType.OPC, dataType = DataType.DOUBLE))
        AlarmEngine(Clock.systemUTC(), AlarmJournal(tmp.resolve("alarms.sqlite"))).use { alarms ->
            val mirror = RuntimeMirror(tags, alarms)
            fun row(path: String, value: Any?, quality: QualityCode) =
                TagSnap("default", path, Json.write(value), quality.label, 1L, "opc")

            assertEquals(emptyList<TagSnap>(), mirror.apply(listOf(row("Plc/Speed", null, QualityCode.BAD_NOT_FOUND)), emptyList()))
            assertEquals(QualityCode.BAD_NOT_FOUND, tags.read("[default]Plc/Speed").quality.code)

            assertEquals(emptyList<TagSnap>(), mirror.apply(listOf(row("Plc/Speed", 42.0, QualityCode.GOOD)), emptyList()))
            assertEquals(QualityCode.GOOD, tags.read("[default]Plc/Speed").quality.code)
            assertEquals(42.0, (tags.read("[default]Plc/Speed").value as Number).toDouble())

            val missing = row("Plc/NotHere", 1.0, QualityCode.GOOD)
            assertEquals(listOf(missing), mirror.apply(listOf(missing), emptyList()))
        }
    }
}
