package org.oip.tags

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * Dataset tag type (TAG-014): columns and rows, types kept, a bad write refused, value kept across a restart.
 */
class DatasetTagTest {
    @TempDir lateinit var dir: Path

    private val stamp = Instant.parse("2026-10-27T12:34:56.789Z")

    private val table = mapOf(
        "columns" to listOf(
            mapOf("name" to "id", "type" to "Int32"),
            mapOf("name" to "count", "type" to "Int64"),
            mapOf("name" to "rate", "type" to "Float"),
            mapOf("name" to "flow", "type" to "Double"),
            mapOf("name" to "on", "type" to "Boolean"),
            mapOf("name" to "name", "type" to "String"),
            mapOf("name" to "when", "type" to "DateTime"),
            mapOf("name" to "note", "type" to "String"),
        ),
        "rows" to listOf(
            listOf(7, 3_000_000_000L, 1.5f, 2.25, true, "line", stamp.toString(), null),
            listOf(8, 4L, 0f, 0.0, false, "pump", stamp.toString(), "ok"),
        ),
    )

    @Test
    fun datasetMemoryTagKeepsColumnTypesAndRefusesABadWrite() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Table", type = TagType.MEMORY, dataType = DataType.DATASET))
        assertEquals(Dataset(emptyList(), emptyList()), engine.read("Table").value)

        val written = engine.write("Table", table)
        assertEquals(QualityCode.GOOD, written.quality.code, written.quality.diagnostic)
        val value = engine.read("Table").value as Dataset
        assertEquals(
            listOf("Int32", "Int64", "Float", "Double", "Boolean", "String", "DateTime", "String"),
            value.columns.map { it.type.label() },
        )
        assertEquals(listOf("id", "count", "rate", "flow", "on", "name", "when", "note"), value.columns.map { it.name })
        assertEquals(7, value.rows[0][0])
        assertEquals(3_000_000_000L, value.rows[0][1])
        assertEquals(1.5f, value.rows[0][2])
        assertEquals(2.25, value.rows[0][3])
        assertEquals(true, value.rows[0][4])
        assertEquals("line", value.rows[0][5])
        assertEquals(stamp, value.rows[0][6])
        assertNull(value.rows[0][7])
        assertEquals(false, value.rows[1][4])

        val inferred = engine.write("Table", mapOf(
            "columns" to listOf("n", "label"),
            "rows" to listOf(listOf(3, "a"), listOf(4, "b")),
        ))
        assertEquals(QualityCode.GOOD, inferred.quality.code, inferred.quality.diagnostic)
        val guessed = engine.read("Table").value as Dataset
        assertEquals(listOf(DataType.INT32, DataType.STRING), guessed.columns.map { it.type })
        assertEquals("b", guessed.rows[1][1])

        val json = ObjectMapper().writeValueAsString(guessed)
        assertTrue(json.contains("\"type\":\"Int32\""), json)
        assertTrue(json.contains("\"rows\""), json)
        val again = engine.write("Table", json)
        assertEquals(QualityCode.GOOD, again.quality.code, again.quality.diagnostic)
        assertEquals(guessed, engine.read("Table").value)

        val bad = listOf(
            mapOf("columns" to listOf(mapOf("name" to "id", "type" to "Int32")), "rows" to listOf(listOf("nope"))),
            mapOf("columns" to listOf(mapOf("name" to "id", "type" to "Int32")), "rows" to listOf(listOf(1, 2))),
            mapOf("columns" to listOf(mapOf("name" to "id", "type" to "Color")), "rows" to listOf(listOf(1))),
            mapOf("columns" to listOf(mapOf("name" to "id", "type" to "Document")), "rows" to listOf(listOf(mapOf("a" to 1)))),
            mapOf(
                "columns" to listOf(mapOf("name" to "id", "type" to "Int32"), mapOf("name" to "id", "type" to "Int32")),
                "rows" to listOf(listOf(1, 2)),
            ),
            mapOf("columns" to listOf(mapOf("name" to "id", "type" to "Int32")), "rows" to listOf(listOf(1.5))),
            mapOf("columns" to listOf(mapOf("name" to " ", "type" to "Int32")), "rows" to emptyList<Any>()),
            mapOf("columns" to listOf(mapOf("name" to "id", "type" to "Int32")), "rows" to listOf(listOf(listOf(1)))),
            5,
            "not-json",
        )
        for (attempt in bad) {
            val refused = engine.write("Table", attempt)
            assertEquals(QualityCode.ERROR_TYPE_CONVERSION, refused.quality.code, "${refused.quality.diagnostic} for $attempt")
            assertEquals(guessed, engine.read("Table").value, "refused write must leave the dataset in place")
        }
    }

    @Test
    fun oneColumnPastTheCeilingIsRefused() {
        val engine = TagEngine()
        engine.configure(TagConfig(
            path = "Table", type = TagType.MEMORY, dataType = DataType.DATASET,
            value = mapOf("columns" to listOf(mapOf("name" to "id", "type" to "Int32")), "rows" to listOf(listOf(1))),
            valueSet = true,
        ))
        val wide = Dataset.MAX_COLUMNS + 1
        val columns = List(wide) { mapOf("name" to "c$it", "type" to "Int32") }
        val refused = engine.write("Table", mapOf("columns" to columns, "rows" to listOf(List(wide) { 0 })))
        assertEquals(QualityCode.ERROR_TYPE_CONVERSION, refused.quality.code, refused.quality.diagnostic)
        assertTrue(refused.quality.diagnostic!!.contains(Dataset.MAX_COLUMNS.toString()), refused.quality.diagnostic)
        assertEquals(1, ((engine.read("Table").value as Dataset).rows.single()[0]))

        val atLimit = List(Dataset.MAX_COLUMNS) { mapOf("name" to "c$it", "type" to "Int32") }
        val accepted = engine.write("Table", mapOf("columns" to atLimit, "rows" to listOf(List(Dataset.MAX_COLUMNS) { 0 })))
        assertEquals(QualityCode.GOOD, accepted.quality.code, accepted.quality.diagnostic)

        val tooLong = mapOf(
            "columns" to emptyList<Any>(),
            "rows" to List(Dataset.MAX_ROWS + 1) { emptyList<Any>() },
        )
        val rows = engine.write("Table", tooLong)
        assertEquals(QualityCode.ERROR_TYPE_CONVERSION, rows.quality.code, rows.quality.diagnostic)
        assertEquals(Dataset.MAX_COLUMNS, (engine.read("Table").value as Dataset).columns.size)

        val longName = "n".repeat(129)
        val name = engine.write("Table", mapOf(
            "columns" to listOf(mapOf("name" to longName, "type" to "Int32")),
            "rows" to listOf(listOf(1)),
        ))
        assertEquals(QualityCode.ERROR_TYPE_CONVERSION, name.quality.code, name.quality.diagnostic)
        assertEquals(Dataset.MAX_COLUMNS, (engine.read("Table").value as Dataset).columns.size)
    }

    @Test
    fun datasetValueSurvivesARestart() {
        val db = dir.resolve("db").resolve(MemoryTagPersistence.FILE_NAME)
        val seed = mapOf(
            "columns" to listOf(mapOf("name" to "id", "type" to "Int32"), mapOf("name" to "when", "type" to "DateTime")),
            "rows" to listOf(listOf(1, stamp.toString())),
        )
        val first = TagEngine()
        MemoryTagPersistence.open(first, db, 60_000).use { persistence ->
            first.configure(TagConfig(path = "Table", type = TagType.MEMORY, dataType = DataType.DATASET, value = seed, valueSet = true))
            assertEquals(QualityCode.GOOD, first.write("Table", table).quality.code)
            persistence.flush()
        }
        val second = TagEngine()
        MemoryTagPersistence.open(second, db, 60_000).use {
            second.configure(TagConfig(path = "Table", type = TagType.MEMORY, dataType = DataType.DATASET, value = seed, valueSet = true))
            val value = second.read("Table").value as Dataset
            assertEquals("Int64", value.columns[1].type.label())
            assertEquals(3_000_000_000L, value.rows[0][1])
            assertEquals(stamp, value.rows[0][6])
            assertEquals(false, value.rows[1][4])
            assertNull(value.rows[0][7])
        }
    }
}
