package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import java.nio.file.Path
import java.time.Instant

/** [SqlHistoryProvider] against a SQLite file: schema, writes, and queries must not use PostgreSQL-only SQL. */
class SqlHistoryProviderSqliteTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun storesSamplesOnASqliteFileAndReturnsThemFromAQuery() {
        val url = "jdbc:sqlite:${tmp.resolve("history.sqlite").toAbsolutePath()}"
        val t0 = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
        val tag = "[default]Line/Count"
        SqlHistoryProvider("sql", JdbcConnectionSource.driverManager(url)).use { p ->
            val records = listOf(
                HistorySample(tag, t0 + 1_000, 1, Quality.GOOD),
                HistorySample(tag, t0 + 2_000, 2.5, Quality.GOOD),
                HistorySample(tag, t0 + 3_000, true, Quality.GOOD),
                HistorySample(tag, t0 + 4_000, "text", Quality.GOOD),
                HistorySample(tag, t0 + 5_000, null, Quality.GOOD),
            ).mapIndexed { i, s -> ForwardRecord("src", i + 1L, s) }
            p.write(records)
            p.write(records.take(3))
            assertFalse(p.hypertable)
            val rows = p.query(HistoryQuery("[DEFAULT]line/count", 0, Long.MAX_VALUE))
            assertEquals(listOf<Any?>(1, 2.5, true, "text", null), rows.map { it.value })
            assertEquals(listOf(t0 + 1_000, t0 + 2_000, t0 + 3_000, t0 + 4_000, t0 + 5_000), rows.map { it.timestampMillis })
            assertEquals(listOf(2.5, true), p.query(HistoryQuery(tag, t0 + 2_000, t0 + 3_000)).map { it.value })
            assertEquals(1, p.latestBefore(tag, t0 + 2_000)!!.value)
        }
    }
}
