package org.oip.historian

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.tags.Level
import org.oip.tags.Quality
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

/**
 * HIS-008: an existing PostgreSQL table read as a history provider, against a real PostgreSQL. Uses
 * `OIP_HISTORY_PG_URL` when set, otherwise starts the pinned image through Testcontainers. Without Docker it is
 * skipped on a developer machine and fails in CI (`CI=true`).
 */
class DbTableHistoryProviderTest {
    companion object {
        private const val IMAGE = "timescale/timescaledb:2.31.0-pg16-oss@sha256:1bfcf994089b9c97b4ddfa07f2ff9eec430709715e419d9a6957d1a4b4614cd1"
        private var container: GenericContainer<*>? = null
        private var url: String? = System.getenv("OIP_HISTORY_PG_URL")?.takeIf { it.isNotBlank() }

        @JvmStatic @BeforeAll fun start() {
            if (url != null) return
            val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
            if (!docker) {
                check(System.getenv("CI") != "true") { "CI must run DbTableHistoryProviderTest: no Docker and no OIP_HISTORY_PG_URL" }
                return
            }
            val db = GenericContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("timescale/timescaledb"))
                .withEnv("POSTGRES_USER", "oip").withEnv("POSTGRES_PASSWORD", "oip").withEnv("POSTGRES_DB", "oip")
                .withEnv("TIMESCALEDB_TELEMETRY", "off")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2).withStartupTimeout(Duration.ofMinutes(3)))
            db.start()
            container = db
            url = "jdbc:postgresql://${db.host}:${db.getMappedPort(5432)}/oip?user=oip&password=oip"
        }

        @JvmStatic @AfterAll fun stop() { container?.stop(); container = null }
    }

    @TempDir lateinit var tmp: Path

    private fun jdbc() = url!!
    private fun source() = JdbcConnectionSource.driverManager(jdbc())
    private fun sql(vararg statements: String) = DriverManager.getConnection(jdbc()).use { c -> c.createStatement().use { st -> statements.forEach { st.execute(it) } } }
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @BeforeEach fun fixtures() {
        assumeTrue(url != null, "no PostgreSQL available")
        sql(
            "DROP SCHEMA IF EXISTS plant CASCADE", "CREATE SCHEMA plant",
            "CREATE TABLE plant.readings (ts timestamptz NOT NULL, tag text NOT NULL, val double precision, q text)",
            "INSERT INTO plant.readings VALUES ('2026-05-01T00:00:00Z','[default]Line/Flow',1.5,'Good')," +
                "('2026-05-01T00:01:00Z','[default]Line/Flow',2.5,'Bad_NotConnected')," +
                "('2026-05-01T00:02:00Z','[default]Line/Flow',3.5,NULL)," +
                "('2026-05-01T00:00:30Z','Line/Temp',70,'Good')," +
                "('2026-05-01T00:00:00Z','Other/Tag',9,'Good')",
            "CREATE TABLE plant.by_id (t timestamp NOT NULL, tag_id int NOT NULL, v int, flag boolean, q int)",
            "INSERT INTO plant.by_id VALUES ('2026-05-01 00:00:00',7,10,true,192),('2026-05-01 00:00:10',7,11,false,100),('2026-05-01 00:00:20',8,99,true,0)",
            "CREATE TABLE plant.wide (epoch_ms bigint NOT NULL, speed double precision, state text)",
            "INSERT INTO plant.wide VALUES (${ms("2026-05-01T00:00:00Z")}, 5.0, 'run'),(${ms("2026-05-01T00:00:10Z")}, NULL, 'stop'),(${ms("2026-05-01T00:00:20Z")}, 7.0, NULL)",
        )
    }

    private fun narrow() = DbTableHistoryProvider(
        "legacy", source(), "plant.readings", "ts",
        TableLayout.Narrow(tagColumn = "tag", valueColumn = "val", qualityColumn = "q"),
    )

    @Test fun narrowTableAnswersQueriesScansAndLatestBefore() {
        narrow().use { p ->
            val all = p.query(HistoryQuery("[default]Line/Flow", 0, Long.MAX_VALUE))
            assertEquals(listOf(1.5, 2.5, 3.5), all.map { it.value })
            assertEquals(listOf(ms("2026-05-01T00:00:00Z"), ms("2026-05-01T00:01:00Z"), ms("2026-05-01T00:02:00Z")), all.map { it.timestampMillis })
            assertEquals(listOf(Level.GOOD, Level.BAD, Level.GOOD), all.map { it.quality.level })
            assertEquals("[default]Line/Flow", all[0].tagPath)
            // A bare path in the table matches the same tag.
            assertEquals(listOf(70.0), p.query(HistoryQuery("Line/Temp", 0, Long.MAX_VALUE)).map { it.value })
            assertEquals(2, p.query(HistoryQuery("[default]Line/Flow", 0, Long.MAX_VALUE, limit = 2)).size)
            val inner = ArrayList<Double>()
            p.scan("[default]Line/Flow", ms("2026-05-01T00:00:30Z"), ms("2026-05-01T00:01:00Z")) { inner += it.value as Double }
            assertEquals(listOf(2.5), inner)
            assertEquals(2.5, p.latestBefore("[default]Line/Flow", ms("2026-05-01T00:02:00Z"))!!.value)
            assertNull(p.latestBefore("[default]Line/Flow", ms("2026-05-01T00:00:00Z")))
            assertTrue(p.query(HistoryQuery("[default]No/Such", 0, Long.MAX_VALUE)).isEmpty())
        }
    }

    @Test fun tagIdsBooleanAndNumericQualityOnATimestampWithoutZone() {
        val p = DbTableHistoryProvider(
            "ids", source(), "plant.by_id", "t",
            TableLayout.Narrow("tag_id", "v", "q", tagIds = mapOf("Line/Count" to 7, "Line/Other" to 8)),
        )
        p.use {
            val rows = it.query(HistoryQuery("[default]Line/Count", ms("2026-05-01T00:00:00Z"), ms("2026-05-01T00:00:10Z")))
            assertEquals(listOf(10L, 11L), rows.map { r -> r.value })
            assertEquals(listOf(ms("2026-05-01T00:00:00Z"), ms("2026-05-01T00:00:10Z")), rows.map { r -> r.timestampMillis }, "a timestamp without zone is read as UTC")
            assertEquals(listOf(Level.GOOD, Level.UNCERTAIN), rows.map { r -> r.quality.level })
            assertEquals(Level.BAD, it.query(HistoryQuery("Line/Other", 0, Long.MAX_VALUE)).single().quality.level)
            assertEquals(listOf("Line/Count", "Line/Other").map { n -> "[default]$n" }, it.mappedTags())
            assertTrue(it.query(HistoryQuery("Line/Unmapped", 0, Long.MAX_VALUE)).isEmpty())
        }
        val flag = DbTableHistoryProvider("flag", source(), "plant.by_id", "t", TableLayout.Narrow("tag_id", "flag", tagIds = mapOf("Line/Count" to 7)))
        flag.use { assertEquals(listOf(true, false), it.query(HistoryQuery("Line/Count", 0, Long.MAX_VALUE)).map { r -> r.value }) }
    }

    @Test fun wideTableWithEpochTimestampsGivesOneSeriesPerColumnAndSkipsNulls() {
        val p = DbTableHistoryProvider(
            "wide", source(), "plant.wide", "epoch_ms",
            TableLayout.Wide(mapOf("Line/Speed" to "speed", "Line/State" to "state")), TimestampUnit.EPOCH_MILLIS,
        )
        p.use {
            assertEquals(listOf(5.0, 7.0), it.query(HistoryQuery("[default]Line/Speed", 0, Long.MAX_VALUE)).map { r -> r.value })
            assertEquals(listOf("run", "stop"), it.query(HistoryQuery("Line/State", ms("2026-05-01T00:00:00Z"), ms("2026-05-01T00:00:15Z"))).map { r -> r.value })
            assertEquals(5.0, it.latestBefore("Line/Speed", ms("2026-05-01T00:00:20Z"))!!.value)
            assertTrue(it.query(HistoryQuery("Line/Other", 0, Long.MAX_VALUE)).isEmpty())
        }
    }

    @Test fun neverWritesAndRefusesAForwardedWriteAsPermanent() {
        narrow().use { p ->
            val e = assertThrows(HistoryWriteException::class.java) { p.write(emptyList()) }
            assertTrue(e.permanent)
            p.query(HistoryQuery("[default]Line/Flow", 0, Long.MAX_VALUE))
            // The connection a read used is handed back writable only if it was before; the table is untouched.
            DriverManager.getConnection(jdbc()).use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM plant.readings").use { rs -> rs.next(); assertEquals(5, rs.getInt(1)) }
            }
        }
        // The statement runs in a read-only transaction, and the connection is handed back as it was borrowed.
        val seen = ArrayList<String>()
        val real = DriverManager.getConnection(jdbc())
        val spy = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, m, args ->
            try {
                if (m.name == "prepareStatement") seen += "readOnly=${real.isReadOnly} autoCommit=${real.autoCommit}"
                m.invoke(real, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
        } as java.sql.Connection
        val spied = object : JdbcConnectionSource {
            override fun borrow() = spy
            override fun release(connection: java.sql.Connection, broken: Boolean) {}
        }
        DbTableHistoryProvider("spy", spied, "plant.readings", "ts", TableLayout.Narrow("tag", "val")).query(HistoryQuery("Line/Temp", 0, Long.MAX_VALUE))
        assertEquals(listOf("readOnly=true autoCommit=false"), seen)
        assertEquals(false to true, real.isReadOnly to real.autoCommit)
        real.autoCommit = false; real.isReadOnly = true
        assertThrows(java.sql.SQLException::class.java) { real.createStatement().execute("DELETE FROM plant.readings") }
        real.close()
    }

    @Test fun identifiersAreCheckedBeforeAnySql() {
        assertThrows(IllegalArgumentException::class.java) {
            DbTableHistoryProvider("x", source(), "plant.readings; DROP TABLE a", "ts", TableLayout.Narrow("tag", "val"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DbTableHistoryProvider("x", source(), "readings", "ts\" OR 1=1 --", TableLayout.Narrow("tag", "val"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DbTableHistoryProvider("x", source(), "readings", "ts", TableLayout.Wide(mapOf("A/B" to "a b")))
        }
        assertThrows(HistoryReadException::class.java) {
            DbTableHistoryProvider("x", source(), "plant.missing", "ts", TableLayout.Narrow("tag", "val")).query(HistoryQuery("A", 0, 1))
        }
    }

    @Test fun aProviderResourceRegistersAndTheRouterReadsIt() {
        val registry = SchemaRegistry()
        FileConfigStore(tmp.resolve("config"), registry).use { store ->
            EmbeddedHistorian(tmp.resolve("history.sqlite")).use { local ->
                val router = HistoryRouter(local, ForwardBuffer(tmp.resolve("fwd.sqlite")))
                HistoryProviderBinding(router, store, registry, local = local) { source() }.use { binding ->
                    binding.attach()
                    store.put(Scope.GATEWAY, Resource(HISTORY_PROVIDER_KIND, Metadata("legacy", HISTORIAN_MODULE), mapOf(
                        "type" to "table", "connection" to "plant-db", "sourceTable" to "plant.readings", "timestampColumn" to "ts",
                        "tagColumn" to "tag", "valueColumn" to "val", "qualityColumn" to "q",
                    )))
                    assertTrue(binding.problems().isEmpty(), binding.problems().toString())
                    assertTrue(router.provider("legacy") is DbTableHistoryProvider)
                    val rows = router.reader("legacy")!!.query(HistoryQuery("[default]Line/Flow", 0, Long.MAX_VALUE))
                    assertEquals(listOf(1.5, 2.5, 3.5), rows.map { it.value })
                    store.put(Scope.GATEWAY, Resource(HISTORY_PROVIDER_KIND, Metadata("wide", HISTORIAN_MODULE), mapOf(
                        "type" to "table", "connection" to "plant-db", "sourceTable" to "plant.wide", "timestampColumn" to "epoch_ms",
                        "timestampUnit" to "epochMillis", "layout" to "wide", "tagColumns" to mapOf("Line/Speed" to "speed"),
                    )))
                    assertTrue(binding.problems().isEmpty(), binding.problems().toString())
                    assertEquals(listOf(5.0, 7.0), router.reader("wide")!!.query(HistoryQuery("Line/Speed", 0, Long.MAX_VALUE)).map { it.value })
                }
                router.close()
            }
        }
    }

    @Test fun incompleteResourcesAreRefused() {
        val registry = SchemaRegistry()
        FileConfigStore(tmp.resolve("config"), registry).use { store ->
            EmbeddedHistorian(tmp.resolve("history.sqlite")).use { local ->
                val router = HistoryRouter(local, ForwardBuffer(tmp.resolve("fwd.sqlite")))
                HistoryProviderBinding(router, store, registry, local = local) { source() }.use { binding ->
                    binding.attach()
                    val ex = assertThrows(org.oip.config.ValidationException::class.java) {
                        store.put(Scope.GATEWAY, Resource(HISTORY_PROVIDER_KIND, Metadata("bad", HISTORIAN_MODULE), mapOf(
                            "type" to "table", "connection" to "plant-db", "sourceTable" to "plant.readings",
                        )))
                    }
                    assertTrue(ex.message!!.contains("timestampColumn"), ex.message)
                    assertThrows(org.oip.config.ValidationException::class.java) {
                        store.put(Scope.GATEWAY, Resource(HISTORY_PROVIDER_KIND, Metadata("bad2", HISTORIAN_MODULE), mapOf(
                            "type" to "table", "connection" to "plant-db", "sourceTable" to "x; DROP", "timestampColumn" to "ts", "tagColumn" to "t", "valueColumn" to "v",
                        )))
                    }
                }
                router.close()
            }
        }
    }
}
