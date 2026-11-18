package org.oip.db.groups

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Every group type and update mode against one backend. Run by [TransactionGroupSqliteTest] and
 * [TransactionGroupPostgresTest].
 *
 * Group types: -
 * Update modes: -
 * Block group: -
 * Update or insert: -
 */
abstract class TransactionGroupContract(private val backend: Backend) {
    @TempDir lateinit var tmp: Path
    protected lateinit var g: GroupHarness

    @BeforeEach fun open() {
        assumeTrue(backend.available, "${backend.name} is not reachable")
        g = GroupHarness(tmp, backend)
    }

    @AfterEach fun shut() {
        if (::g.isInitialized) g.close()
    }

    private fun valuesTable(): String {
        val t = g.table("line")
        g.sql("CREATE TABLE \"$t\" (${backend.idColumn}, speed DOUBLE PRECISION, doubled DOUBLE PRECISION, site TEXT, t_stamp TIMESTAMP)")
        return t
    }

    private fun lineItems() = listOf(
        mapOf("name" to "speed", "source" to "tag", "tag" to "Line/Speed", "column" to "speed"),
        mapOf("name" to "doubled", "source" to "expression", "expression" to "{speed} * 2", "column" to "doubled"),
        mapOf("name" to "site", "source" to "static", "value" to "north", "column" to "site"),
    )

    @Test fun historicalInsertsOneRowPerExecution() {
        val t = valuesTable()
        g.tags["Line/Speed"] = 10.0
        g.put("hist", mapOf("connection" to "plant", "table" to t, "groupType" to "historical", "timestampColumn" to "t_stamp", "items" to lineItems()))
        assertEquals(1, g.run("hist").rowsWritten)
        g.tags["Line/Speed"] = 12.5
        g.run("hist")
        val rows = g.rows("SELECT speed, doubled, site, t_stamp FROM \"$t\" ORDER BY id")
        assertEquals(2, rows.size)
        assertEquals(10.0, num(rows[0][0]))
        assertEquals(20.0, num(rows[0][1]))
        assertEquals("north", rows[0][2])
        assertNotNull(rows[0][3])
        assertEquals(25.0, num(rows[1][1]))
        val status = g.groups.status("hist")
        assertEquals(1, status["rowsWritten"])
        assertEquals(2L, status["totalRowsWritten"])
        assertNull(status["error"])
    }

    @Test fun standardInsertActionAddsARowAndUpdateRewritesTheLastRow() {
        val t = valuesTable()
        g.tags["Line/Speed"] = 1.0
        g.put("ins", mapOf("connection" to "plant", "table" to t, "groupType" to "standard", "tableAction" to "insert", "items" to lineItems()))
        g.run("ins")
        g.run("ins")
        assertEquals(2, g.rows("SELECT id FROM \"$t\"").size)

        g.put("upd", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "standard", "tableAction" to "update",
            "row" to mapOf("mode" to "last"), "items" to lineItems(),
        ))
        g.tags["Line/Speed"] = 7.0
        assertEquals(1, g.run("upd").rowsWritten)
        val rows = g.rows("SELECT speed FROM \"$t\" ORDER BY id")
        assertEquals(listOf(1.0, 7.0), rows.map { num(it[0]) })
    }

    @Test fun standardDbToTagCopiesTheFirstRowIntoTags() {
        val t = valuesTable()
        g.sql("INSERT INTO \"$t\" (speed, site) VALUES (42.5, 'a')")
        g.sql("INSERT INTO \"$t\" (speed, site) VALUES (99, 'b')")
        g.tags["Line/Speed"] = 0.0
        g.put("read", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "standard", "updateMode" to "dbToTag",
            "row" to mapOf("mode" to "first"),
            "items" to listOf(mapOf("name" to "speed", "source" to "tag", "tag" to "Line/Speed", "column" to "speed")),
        ))
        val outcome = g.run("read")
        assertEquals(1, outcome.tagWrites)
        assertEquals(0, outcome.rowsWritten)
        assertEquals(42.5, num(g.tags["Line/Speed"]))
        // Unchanged values are not written again.
        assertEquals(0, g.run("read").tagWrites)
    }

    @Test fun bidirectionalDbWinsWritesTheTagThenFollowsEitherSide() {
        val t = valuesTable()
        g.sql("INSERT INTO \"$t\" (speed) VALUES (5)")
        g.tags["Line/Speed"] = 1.0
        g.put("bi", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "standard", "updateMode" to "bidirectionalDbWins",
            "row" to mapOf("mode" to "first"),
            "items" to listOf(mapOf("name" to "speed", "source" to "tag", "tag" to "Line/Speed", "column" to "speed")),
        ))
        g.run("bi")
        assertEquals(5.0, num(g.tags["Line/Speed"]), "database wins the first comparison")
        assertEquals(5.0, num(g.rows("SELECT speed FROM \"$t\"")[0][0]))

        g.tags["Line/Speed"] = 6.0
        g.run("bi")
        assertEquals(6.0, num(g.rows("SELECT speed FROM \"$t\"")[0][0]), "a tag-only change goes to the database")

        g.sql("UPDATE \"$t\" SET speed = 8")
        g.run("bi")
        assertEquals(8.0, num(g.tags["Line/Speed"]), "a database-only change goes to the tag")

        g.sql("UPDATE \"$t\" SET speed = 11")
        g.tags["Line/Speed"] = 10.0
        g.run("bi")
        assertEquals(11.0, num(g.tags["Line/Speed"]), "both changed: the database wins")
        assertEquals(11.0, num(g.rows("SELECT speed FROM \"$t\"")[0][0]))

        g.tags.bad += "Line/Speed"
        g.tags["Line/Speed"] = 0.0
        g.run("bi")
        assertEquals(11.0, num(g.rows("SELECT speed FROM \"$t\"")[0][0]), "a bad-quality tag is not copied to the database")
        g.tags.bad -= "Line/Speed"
    }

    @Test fun bidirectionalTagWinsWritesTheDatabase() {
        val t = valuesTable()
        g.sql("INSERT INTO \"$t\" (speed) VALUES (5)")
        g.tags["Line/Speed"] = 1.0
        g.put("bi", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "standard", "updateMode" to "bidirectionalTagWins",
            "row" to mapOf("mode" to "first"),
            "items" to listOf(mapOf("name" to "speed", "source" to "tag", "tag" to "Line/Speed", "column" to "speed")),
        ))
        assertEquals(1, g.run("bi").rowsWritten)
        assertEquals(1.0, num(g.rows("SELECT speed FROM \"$t\"")[0][0]))
        assertEquals(1.0, num(g.tags["Line/Speed"]))

        g.sql("UPDATE \"$t\" SET speed = 3")
        g.tags["Line/Speed"] = 4.0
        g.run("bi")
        assertEquals(4.0, num(g.rows("SELECT speed FROM \"$t\"")[0][0]), "both changed: the tag wins")
        g.sql("UPDATE \"$t\" SET speed = 9")
        g.run("bi")
        assertEquals(9.0, num(g.tags["Line/Speed"]), "a database-only change still reaches the tag")
    }

    @Test fun updateOrInsertByKey() {
        val t = g.table("machines")
        g.sql("CREATE TABLE \"$t\" (machine_id INTEGER, count_value INTEGER)")
        g.tags["M/Id"] = 7
        g.tags["M/Count"] = 1
        g.put("upsert", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "standard", "updateMode" to "tagToDb", "tableAction" to "update",
            "row" to mapOf("mode" to "key", "keyColumn" to "machine_id", "keyItem" to "id", "insertIfMissing" to true),
            "items" to listOf(
                mapOf("name" to "id", "source" to "tag", "tag" to "M/Id"),
                mapOf("name" to "count", "source" to "tag", "tag" to "M/Count", "column" to "count_value"),
            ),
        ))
        assertEquals(1, g.run("upsert").rowsWritten)
        g.tags["M/Count"] = 2
        g.run("upsert")
        g.tags["M/Id"] = 8
        g.tags["M/Count"] = 30
        g.run("upsert")
        val rows = g.rows("SELECT machine_id, count_value FROM \"$t\" ORDER BY machine_id")
        assertEquals(listOf(listOf(7L, 2L), listOf(8L, 30L)), rows.map { r -> r.map { (it as Number).toLong() } })
    }

    @Test fun customWhereSelectsTheRow() {
        val t = valuesTable()
        g.sql("INSERT INTO \"$t\" (speed, site) VALUES (1, 'north')")
        g.sql("INSERT INTO \"$t\" (speed, site) VALUES (2, 'south')")
        g.tags["Line/Speed"] = 50.0
        g.put("where", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "standard", "tableAction" to "update",
            "row" to mapOf("mode" to "where", "where" to "site = 'south'"),
            "items" to listOf(mapOf("name" to "speed", "source" to "tag", "tag" to "Line/Speed", "column" to "speed")),
        ))
        g.run("where")
        assertEquals(listOf(1.0, 50.0), g.rows("SELECT speed FROM \"$t\" ORDER BY id").map { num(it[0]) })
    }

    @Test fun blockInsertWritesOneRowPerEntryWithBlockAndRowIds() {
        val t = g.table("temps")
        g.sql("CREATE TABLE \"$t\" (${backend.idColumn}, temp DOUBLE PRECISION, label TEXT, batch TEXT, row_id INTEGER, block_id INTEGER)")
        for (i in 1..3) g.tags["Oven/T$i"] = 100.0 + i
        g.tags["Batch"] = "b-1"
        g.put("block", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "block", "rowIdColumn" to "row_id", "blockIdColumn" to "block_id",
            "items" to listOf(mapOf("name" to "batch", "source" to "tag", "tag" to "Batch", "column" to "batch")),
            "blockItems" to listOf(
                mapOf("name" to "temp", "column" to "temp", "pattern" to "Oven/T{?}", "from" to 1, "to" to 3),
                mapOf("name" to "label", "column" to "label", "values" to listOf("a", "b", "c")),
            ),
        ))
        assertEquals(3, g.run("block").rowsWritten)
        g.tags["Oven/T2"] = 250.0
        g.run("block")
        val rows = g.rows("SELECT temp, label, batch, row_id, block_id FROM \"$t\" ORDER BY id")
        assertEquals(6, rows.size)
        assertEquals(listOf(101.0, 102.0, 103.0, 101.0, 250.0, 103.0), rows.map { num(it[0]) })
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), rows.map { it[1] })
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), rows.map { (it[3] as Number).toLong() })
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L, 2L), rows.map { (it[4] as Number).toLong() })
        assertTrue(rows.all { it[2] == "b-1" })
    }

    @Test fun blockUpdateWritesAndReadsRowsInIndexOrder() {
        val t = g.table("recipe")
        g.sql("CREATE TABLE \"$t\" (${backend.idColumn}, setpoint DOUBLE PRECISION, recipe TEXT)")
        for (i in 1..3) g.sql("INSERT INTO \"$t\" (setpoint, recipe) VALUES ($i, 'r1')")
        g.sql("INSERT INTO \"$t\" (setpoint, recipe) VALUES (99, 'r2')")
        for (i in 1..3) g.tags["Sp/$i"] = 0.0

        g.put("load", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "block", "updateMode" to "dbToTag", "tableAction" to "update",
            "row" to mapOf("mode" to "where", "where" to "recipe = 'r1'"),
            "blockItems" to listOf(mapOf("name" to "sp", "column" to "setpoint", "tags" to listOf("Sp/1", "Sp/2", "Sp/3"))),
        ))
        assertEquals(3, g.run("load").tagWrites)
        assertEquals(listOf(1.0, 2.0, 3.0), (1..3).map { num(g.tags["Sp/$it"]) })

        g.tags["Sp/2"] = 20.0
        g.put("save", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "block", "updateMode" to "tagToDb", "tableAction" to "update",
            "row" to mapOf("mode" to "where", "where" to "recipe = 'r1'"),
            "blockItems" to listOf(mapOf("name" to "sp", "column" to "setpoint", "tags" to listOf("Sp/1", "Sp/2", "Sp/3"))),
        ))
        assertEquals(3, g.run("save").rowsWritten)
        assertEquals(listOf(1.0, 20.0, 3.0, 99.0), g.rows("SELECT setpoint FROM \"$t\" ORDER BY id").map { num(it[0]) })
    }

    @Test fun triggerRunsOncePerRisingEdgeAndResets() {
        val t = valuesTable()
        g.tags["Line/Speed"] = 1.0
        g.tags["Line/Go"] = false
        g.put("edge", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "historical", "items" to lineItems(),
            "execution" to mapOf("trigger" to mapOf("tag" to "Line/Go", "onlyOnChange" to true)),
        ))
        assertFalse(g.run("edge").executed)
        g.tags["Line/Go"] = true
        assertTrue(g.run("edge").executed)
        assertFalse(g.run("edge").executed, "still active: no second run")
        assertFalse(g.run("edge").executed)
        g.tags["Line/Go"] = false
        assertFalse(g.run("edge").executed)
        g.tags["Line/Go"] = true
        assertTrue(g.run("edge").executed, "a new rising edge runs again")
        assertEquals(2, g.rows("SELECT id FROM \"$t\"").size)

        g.put("level", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "historical", "items" to lineItems(),
            "execution" to mapOf("trigger" to mapOf("tag" to "Line/Go", "onlyOnChange" to false)),
        ))
        assertTrue(g.run("level").executed)
        assertTrue(g.run("level").executed, "without onlyOnChange every tick runs while active")
        assertEquals(4, g.rows("SELECT id FROM \"$t\"").size)

        g.tags["Line/Req"] = 1
        g.put("reset", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "historical", "items" to lineItems(),
            "execution" to mapOf("trigger" to mapOf("tag" to "Line/Req", "reset" to true)),
        ))
        assertTrue(g.run("reset").executed)
        assertEquals(0, g.tags["Line/Req"])
        assertFalse(g.run("reset").executed)
        g.tags["Line/Req"] = 1
        assertTrue(g.run("reset").executed)
    }

    @Test fun fallingEdgeChangeAndHandshake() {
        val t = valuesTable()
        g.tags["Line/Speed"] = 1.0
        g.tags["Line/Go"] = true
        g.put("fall", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "historical", "items" to lineItems(),
            "execution" to mapOf("trigger" to mapOf("tag" to "Line/Go", "mode" to "falling", "handshakeTag" to "Line/Ack", "handshakeValue" to 1)),
        ))
        assertFalse(g.run("fall").executed, "already active: no falling edge yet")
        g.tags["Line/Go"] = false
        assertTrue(g.run("fall").executed)
        assertEquals(1, g.tags["Line/Ack"])
        assertFalse(g.run("fall").executed)
        g.tags["Line/Go"] = true
        assertFalse(g.run("fall").executed)
        g.tags["Line/Go"] = false
        assertTrue(g.run("fall").executed)
        assertEquals(2, g.rows("SELECT id FROM \"$t\"").size)

        g.tags["Line/Code"] = 1
        g.put("chg", mapOf(
            "connection" to "plant", "table" to t, "groupType" to "historical", "items" to lineItems(),
            "execution" to mapOf("trigger" to mapOf("tag" to "Line/Code", "mode" to "change")),
        ))
        assertFalse(g.run("chg").executed, "the first sample is the baseline")
        assertFalse(g.run("chg").executed)
        g.tags["Line/Code"] = 2
        assertTrue(g.run("chg").executed)
        assertFalse(g.run("chg").executed)
        assertEquals(3, g.rows("SELECT id FROM \"$t\"").size)
    }

    @Test fun aFailingDatabaseMarksTheGroupInErrorUntilItRecovers() {
        val t = valuesTable()
        g.tags["Line/Speed"] = 1.0
        g.put("hist", mapOf("connection" to "plant", "table" to t, "groupType" to "historical", "items" to lineItems()))
        g.sql("DROP TABLE \"$t\"")
        g.run("hist")
        val failed = g.groups.status("hist")
        assertEquals("Error", failed["state"])
        assertNotNull(failed["error"])
        assertEquals(1L, failed["errors"])
        assertEquals(0, failed["rowsWritten"])

        g.sql("CREATE TABLE \"$t\" (${backend.idColumn}, speed DOUBLE PRECISION, doubled DOUBLE PRECISION, site TEXT, t_stamp TIMESTAMP)")
        g.run("hist")
        val ok = g.groups.status("hist")
        assertNull(ok["error"])
        assertEquals(1, ok["rowsWritten"])
    }
}
