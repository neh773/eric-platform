package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import java.nio.file.Path
import java.sql.DriverManager

/** Raw time-range query, type round trip, and the ADR 0008 file layout (WAL). */
class SqliteHistoryStoreTest {
    @TempDir lateinit var tmp: Path

    private fun sample(path: String, t: Long, v: Any?, q: Quality = Quality.GOOD) = HistorySample(path, t, v, q)

    @Test
    fun rawQueryReturnsSamplesInTheInclusiveRangeOldestFirstUpToTheLimit() {
        SqliteHistoryStore(tmp.resolve("db/history.sqlite")).use { store ->
            store.appendAll((10 downTo 1).map { sample("[default]A", it * 100L, it.toDouble()) })
            store.appendAll(listOf(sample("[default]B", 300, 99.0)))
            val hit = store.query(HistoryQuery("[default]A", 300, 600))
            assertEquals(listOf(300L, 400L, 500L, 600L), hit.map { it.timestampMillis })
            assertEquals(listOf(3.0, 4.0, 5.0, 6.0), hit.map { it.value })
            assertEquals(listOf(300L, 400L), store.query(HistoryQuery("[default]A", 300, 600, limit = 2)).map { it.timestampMillis })
            assertEquals(4, store.query(HistoryQuery("[DEFAULT]a", 300, 600)).size, "tag paths compare case-insensitively")
            assertEquals(0, store.query(HistoryQuery("[default]A", 1001, 2000)).size)
        }
    }

    @Test
    fun valuesKeepTheirTypeAndQualityRoundTrips() {
        SqliteHistoryStore(tmp.resolve("h.sqlite")).use { store ->
            val values = listOf(true, 7, 9_000_000_000L, 1.5, "text", null)
            store.appendAll(values.mapIndexed { i, v -> sample("[default]T", i.toLong(), v) })
            store.appendAll(listOf(sample("[default]T", 99, 1.0, Quality.BAD_NOT_CONNECTED.withDiagnostic("link down"))))
            val rows = store.query(HistoryQuery("[default]T", 0, 100))
            assertEquals(values, rows.take(values.size).map { it.value })
            assertEquals(Quality.BAD_NOT_CONNECTED.withDiagnostic("link down"), rows.last().quality)
        }
    }

    @Test
    fun latestAllReturnsTheLastStoredRowPerTag() {
        SqliteHistoryStore(tmp.resolve("h.sqlite")).use { store ->
            store.appendAll(listOf(sample("[default]A", 1, 1.0), sample("[default]B", 1, 5.0), sample("[default]A", 2, 2.0)))
            val latest = store.latestAll()
            assertEquals(2.0, latest["[default]A"]?.value)
            assertEquals(5.0, latest["[default]B"]?.value)
        }
    }

    @Test
    fun usesWalAndSurvivesReopen() {
        val file = tmp.resolve("db/history.sqlite")
        SqliteHistoryStore(file).use { it.appendAll(listOf(sample("[default]A", 1, 1.0))) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { st -> st.executeQuery("PRAGMA journal_mode").use { rs -> rs.next(); assertEquals("wal", rs.getString(1)) } }
        }
        SqliteHistoryStore(file).use { assertEquals(1, it.query(HistoryQuery("[default]A", 0, 10)).size) }
    }

    @Test
    fun invalidQueriesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { HistoryQuery("[default]A", 10, 9) }
        assertThrows(IllegalArgumentException::class.java) { HistoryQuery("[default]A", 0, 1, limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { HistoryQuery("[default]A", 0, 1, limit = HistoryQuery.MAX_LIMIT + 1) }
    }

    @Test
    fun latestAllReadsTheLatestTableKeptInEachAppendTransaction() {
        val db = tmp.resolve("db/history.sqlite")
        SqliteHistoryStore(db).use { store ->
            store.appendAll(listOf(sample("[default]A", 100, 1.0), sample("[default]B", 100, 5.0), sample("[default]A", 200, 2.0)))
            store.appendAll(listOf(sample("[default]a", 300, 3.0, Quality.BAD_NOT_CONNECTED)))
        }
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM history_latest").use { rs -> rs.next(); assertEquals(2, rs.getInt(1)) }
                // Prove startup reads history_latest, not history_sample: empty the samples table.
                st.execute("DELETE FROM history_sample")
            }
        }
        SqliteHistoryStore(db).use { store ->
            val latest = store.latestAll().mapKeys { it.key.lowercase() }
            assertEquals(setOf("[default]a", "[default]b"), latest.keys)
            assertEquals(3.0, latest.getValue("[default]a").value)
            assertEquals(Quality.BAD_NOT_CONNECTED, latest.getValue("[default]a").quality)
            assertEquals(300L, latest.getValue("[default]a").timestampMillis)
            assertEquals(5.0, latest.getValue("[default]b").value)
        }
    }

    @Test
    fun anExistingDatabaseWithoutTheLatestTableGetsItBuiltOnce() {
        val db = tmp.resolve("old.sqlite")
        SqliteHistoryStore(db).use { store ->
            store.appendAll(listOf(sample("[default]A", 100, 1.0), sample("[default]A", 200, 2.0), sample("[default]B", 50, 7)))
        }
        // Simulate a file written before history_latest existed.
        DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { it.execute("DROP TABLE history_latest") } }
        SqliteHistoryStore(db).use { store ->
            val latest = store.latestAll()
            assertEquals(2.0, latest.getValue("[default]A").value)
            assertEquals(7, latest.getValue("[default]B").value)
        }
        // Built once: a later open does not rebuild from samples (rows deleted here would otherwise vanish).
        DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { it.execute("DELETE FROM history_sample") } }
        SqliteHistoryStore(db).use { store -> assertEquals(2, store.latestAll().size) }
    }
}
