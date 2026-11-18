package org.oip.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * DAT-015: the alarm journal stores its rows on a named database connection, borrowed from that connection's pool,
 * and a missing or broken connection is a reported fault with the rows held, not a silent loss.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AlarmJournalConnectionTest {
    @TempDir lateinit var tmp: Path

    private val level = "prov:default:/tag:tank/level:/alm:High"
    private val pressure = "prov:default:/tag:line/pressure:/alm:Low"

    /** A pool stand-in over a SQLite file. [available] false makes [borrow] throw as a missing connection does. */
    private class FileSource(val file: Path) : JournalConnectionSource {
        val available = AtomicBoolean(true)
        val borrowed = AtomicInteger()
        val open = AtomicInteger()

        override fun borrow(): Connection {
            if (!available.get()) throw IllegalStateException("database connection 'plant' not found")
            borrowed.incrementAndGet()
            open.incrementAndGet()
            return DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        }

        override fun release(connection: Connection, broken: Boolean) {
            open.decrementAndGet()
            connection.close()
        }
    }

    private fun entry(source: String, type: Int, time: Long, data: Map<String, Any?> = emptyMap()) = JournalEntry(
        eventId = "ev-$source-$time",
        source = source,
        displayPath = "Tank",
        priority = AlarmPriority.HIGH,
        eventType = type,
        eventFlags = 0,
        eventTimeMillis = time,
        shelved = false,
        data = data,
    )

    private fun count(file: Path, table: String): Long =
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { rs ->
                    rs.next()
                    if (rs.getLong(1) == 0L) return 0
                }
                st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) }
            }
        }

    private fun until(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!check()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test fun rowsGoToTheNamedConnectionAndLiveStateStaysInTheJournalFile() {
        val own = tmp.resolve("db/alarm-journal.sqlite")
        val plant = FileSource(tmp.resolve("plant.db"))
        AlarmJournal(own).use { journal ->
            journal.useConnection("plant", plant)
            assertEquals("plant", journal.connection)
            journal.append(entry(level, AlarmEventType.ACTIVE, 1_000, mapOf("ackUser" to "op", "value" to 150.5, "count" to 3)))
            journal.append(entry(pressure, AlarmEventType.ACTIVE, 2_000))
            journal.saveState(level, "{\"active\":true}")
            assertTrue(journal.flush())

            // The rows are on the connection, borrowed and returned; the live state is in the journal's own file.
            assertEquals(2, count(plant.file, "alarm_events"))
            assertEquals(3, count(plant.file, "alarm_event_data"))
            assertEquals(0, count(own, "alarm_events"))
            assertEquals(mapOf(level to "{\"active\":true}"), journal.loadStates())
            assertTrue(plant.borrowed.get() > 0)
            assertEquals(0, plant.open.get(), "every borrowed connection is returned")

            // Reads come from the connection, with the same filters as the built-in file.
            val page = journal.query(JournalQuery(source = "*tank*", newestFirst = false))
            assertEquals(listOf(level), page.records.map { it.source })
            assertEquals("op", page.records.single().actor)
            assertEquals(150.5, page.records.single().data["value"])
            val admitted = journal.query(JournalQuery(sourceAllowed = { it == pressure }))
            assertEquals(listOf(pressure), admitted.records.map { it.source })
            assertEquals(2, journal.maxId())
            assertEquals(1, journal.purgeOlderThan(java.time.Instant.ofEpochMilli(1_500)))
            assertEquals(1, count(plant.file, "alarm_events"))

            val stats = journal.stats()
            assertEquals(AlarmJournal.STORAGE_CONNECTION, stats["storage"])
            assertEquals("plant", stats["connection"])
            assertEquals(AlarmJournal.STATUS_VALID, stats["connectionStatus"])
            assertEquals("sqlite", stats["database"])
            assertNull(stats["connectionError"])

            // Back to the built-in file: new rows go there again.
            journal.useConnection(null, null)
            journal.append(entry(level, AlarmEventType.CLEAR, 3_000))
            assertTrue(journal.flush())
            assertEquals(1, count(own, "alarm_events"))
            assertEquals(AlarmJournal.STORAGE_BUILT_IN, journal.stats()["storage"])
        }
    }

    @Test fun aMissingConnectionIsAFaultWithTheRowsHeldUntilItAppears() {
        val plant = FileSource(tmp.resolve("late.db"))
        plant.available.set(false)
        AlarmJournal(tmp.resolve("db/alarm-journal.sqlite")).use { journal ->
            journal.useConnection("plant", plant)
            // Reported before any alarm is written: the writer checks the connection when it is named.
            until("the connection to be reported faulted") { journal.stats()["connectionStatus"] == AlarmJournal.STATUS_FAULTED }
            assertTrue((journal.stats()["connectionError"] as String).contains("not found"), journal.stats().toString())

            assertTrue(journal.append(entry(level, AlarmEventType.ACTIVE, 1_000)))
            journal.saveState(level, "{\"active\":true}")
            until("the batch to be held for retry") { journal.stats()["retrying"] == true }
            assertTrue((journal.stats()["queued"] as Int) >= 1, journal.stats().toString())
            assertTrue((journal.stats()["lastWriteError"] as String).contains("not found"))
            assertEquals(0L, journal.lostRows)
            val read = assertThrows(JournalUnavailable::class.java) { journal.query(JournalQuery()) }
            assertTrue(read.message!!.contains("'plant'"), read.message)
            // The state update queued after the row waits with it, so it never runs ahead of the row.
            assertEquals(emptyMap<String, String>(), journal.loadStates())

            plant.available.set(true)
            until("the held row to be written") { journal.stats()["retrying"] == false && count(plant.file, "alarm_events") == 1L }
            assertEquals(AlarmJournal.STATUS_VALID, journal.stats()["connectionStatus"])
            assertEquals(mapOf(level to "{\"active\":true}"), journal.loadStates())
            assertEquals(0L, journal.lostRows)
            assertEquals(listOf(level), journal.query(JournalQuery()).records.map { it.source })
        }
    }

    @Test fun anUnsupportedDatabaseIsAFaultThatNamesIt() {
        val other = object : JournalConnectionSource {
            override fun borrow(): Connection {
                val real = DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("other.db").toAbsolutePath()}")
                val meta = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.DatabaseMetaData::class.java)) { _, m, args ->
                    if (m.name == "getDatabaseProductName") "Exotic SQL" else m.invoke(real.metaData, *(args ?: emptyArray()))
                } as java.sql.DatabaseMetaData
                return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, args ->
                    if (m.name == "getMetaData") meta else m.invoke(real, *(args ?: emptyArray()))
                } as Connection
            }

            override fun release(connection: Connection, broken: Boolean) = connection.close()
        }
        AlarmJournal(tmp.resolve("db/alarm-journal.sqlite")).use { journal ->
            journal.useConnection("exotic", other)
            until("the connection to be reported faulted") { journal.stats()["connectionStatus"] == AlarmJournal.STATUS_FAULTED }
            val error = journal.stats()["connectionError"] as String
            assertTrue(error.contains("SQLite and PostgreSQL") && error.contains("Exotic SQL"), error)
        }
    }

    @Test fun postgresPatternsAreLikeClausesThatKeepWildcardsLiteral() {
        val (sql, values) = AlarmJournal.globClause("source", "*tank*,a_b%c", JournalDialect.POSTGRESQL)!!
        assertEquals("(source LIKE ? ESCAPE '\\' OR source LIKE ? ESCAPE '\\')", sql)
        assertEquals(listOf("%tank%", "a\\_b\\%c"), values)
    }
}
