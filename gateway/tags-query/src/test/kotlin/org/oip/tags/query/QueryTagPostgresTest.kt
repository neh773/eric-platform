package org.oip.tags.query

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.db.DatabaseService
import org.oip.db.PasswordUpdate
import org.oip.tags.DataType
import org.oip.tags.Level
import org.oip.tags.QualityCode
import org.oip.tags.TagCaller
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagGroupDef
import org.oip.tags.TagType
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant

/**
 * Query tag against a real PostgreSQL connection (TAG-030, P2-23).
 */
class QueryTagPostgresTest {
    @TempDir
    lateinit var tmp: Path

    private val suffix = java.lang.Long.toHexString(System.nanoTime() and 0xffffff)
    private val tables = ArrayList<String>()
    private lateinit var db: PlantDb
    private lateinit var engine: TagEngine
    private lateinit var runtime: QueryTagRuntime
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        val url = PostgresServer.require()
        db = PlantDb(tmp)
        db.connect(url)
        engine = TagEngine()
        engine.configureGroup(TagGroupDef("fast", rateMillis = 1_000))
        runtime = QueryTagRuntime(engine, db.db).also { it.attach() }
    }

    @AfterEach
    fun tearDown() {
        if (::runtime.isInitialized) runtime.close()
        if (::db.isInitialized) {
            for (name in tables) runCatching { db.sql("DROP TABLE IF EXISTS $name") }
            db.close()
        }
    }

    @Test
    fun valueFollowsTableUpdatesAtTheGroupRate() {
        val table = table("q_level")
        db.sql("CREATE TABLE $table (speed integer)")
        db.sql("INSERT INTO $table (speed) VALUES (1)")
        engine.configure(query("Line/Speed", "SELECT speed FROM $table"))

        runtime.tick(t0)
        assertGood(1, "Line/Speed")

        db.sql("UPDATE $table SET speed = 2")
        runtime.tick(t0.plusMillis(999))
        assertGood(1, "Line/Speed")

        runtime.tick(t0.plusMillis(1_000))
        assertGood(2, "Line/Speed")
        assertEquals(QualityCode.BAD_NOT_WRITABLE, engine.write("Line/Speed", 9).quality.code)
        assertGood(2, "Line/Speed")

        // A successful query that returns no row is Good and clears the value. A failure, below, does not.
        db.sql("DELETE FROM $table")
        runtime.tick(t0.plusMillis(2_000))
        val cleared = engine.read("Line/Speed")
        assertEquals(QualityCode.GOOD, cleared.quality.code)
        assertNull(cleared.value)
    }

    @Test
    fun tagPathReferencesAreBoundParameters() {
        val table = table("q_name")
        db.sql("CREATE TABLE $table (name text, n integer)")
        db.sql("INSERT INTO $table (name, n) VALUES ('safe', 1)")
        val hostile = "x'; DROP TABLE $table; --"
        db.jdbc { connection ->
            connection.prepareStatement("INSERT INTO $table (name, n) VALUES (?, 2)").use { statement ->
                statement.setString(1, hostile)
                statement.executeUpdate()
            }
        }
        engine.configure(TagConfig(
            path = "Line/Name", type = TagType.MEMORY, dataType = DataType.STRING, value = hostile, valueSet = true,
        ))
        engine.configure(query("Line/Picked", "SELECT n FROM $table WHERE name = {[default]Line/Name}"))

        runtime.tick(t0)
        assertGood(2, "Line/Picked")
        assertEquals(2, count(table), "the bound value must not be executed as SQL")

        engine.configure(query("Line/Picked", "SELECT n FROM $table WHERE name = {[.]Name}"))
        engine.write("Line/Name", "safe")
        runtime.tick(t0.plusMillis(1_000))
        assertGood(1, "Line/Picked")

        engine.configure(query("Line/Literal", "SELECT '{not a tag}'", DataType.STRING))
        runtime.tick(t0.plusMillis(2_000))
        assertEquals("{not a tag}", engine.read("Line/Literal").value)
        assertEquals(QualityCode.GOOD, engine.read("Line/Literal").quality.code)
    }

    @Test
    fun aFailingQueryGoesBadThenRecoversAndKeepsTheLastValue() {
        val table = table("q_fail")
        db.sql("CREATE TABLE $table (speed integer)")
        db.sql("INSERT INTO $table (speed) VALUES (7)")
        engine.configure(query("Line/Speed", "SELECT speed FROM $table"))
        runtime.tick(t0)
        assertGood(7, "Line/Speed")

        db.sql("DROP TABLE $table")
        runtime.tick(t0.plusMillis(1_000))
        val failed = engine.read("Line/Speed")
        assertEquals(QualityCode.BAD_DEVICE_FAILURE, failed.quality.code)
        assertEquals(Level.BAD, failed.quality.level)
        assertEquals(7, (failed.value as Number).toInt())
        assertTrue(!failed.quality.diagnostic.isNullOrBlank())

        db.sql("CREATE TABLE $table (speed integer)")
        db.sql("INSERT INTO $table (speed) VALUES (9)")
        runtime.tick(t0.plusMillis(2_000))
        assertGood(9, "Line/Speed")
    }

    @Test
    fun aMissingConnectionGoesBadThenRecoversAndKeepsTheLastValue() {
        val table = table("q_conn")
        db.sql("CREATE TABLE $table (speed integer)")
        db.sql("INSERT INTO $table (speed) VALUES (4)")
        engine.configure(query("Line/Speed", "SELECT speed FROM $table"))
        runtime.tick(t0)
        assertGood(4, "Line/Speed")

        val disabled = db.save(enabled = false, version = db.version)
        runtime.tick(t0.plusMillis(1_000))
        val down = engine.read("Line/Speed")
        assertEquals(QualityCode.BAD_NOT_CONNECTED, down.quality.code)
        assertEquals(Level.BAD, down.quality.level)
        assertEquals(4, (down.value as Number).toInt())

        db.save(enabled = true, version = disabled)
        db.sql("UPDATE $table SET speed = 5")
        runtime.tick(t0.plusMillis(2_000))
        assertGood(5, "Line/Speed")
    }

    @Test
    fun aReaderWithoutReadPermissionCannotReadTheQueryTag() {
        engine.configure(TagConfig(
            path = "Line/Secret",
            type = TagType.MEMORY,
            dataType = DataType.STRING,
            value = "hidden-recipe",
            valueSet = true,
            readPermissions = setOf("operator"),
        ))
        engine.configure(query(
            "Line/FromQuery",
            "SELECT {[default]Line/Secret}",
            DataType.STRING,
            readPermissions = setOf("operator"),
        ))
        runtime.tick(t0)

        assertEquals("hidden-recipe", engine.read("Line/FromQuery").value)
        assertEquals(QualityCode.GOOD, engine.read("Line/FromQuery").quality.code)
        val operator = TagCaller.User("ada", setOf("operator"), zone = "Floor")
        assertEquals("hidden-recipe", engine.read("Line/FromQuery", operator).value)

        val viewer = TagCaller.User("vic", setOf("viewer"), zone = "Floor")
        val hidden = engine.read("Line/FromQuery", viewer)
        assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, hidden.quality.code)
        assertNull(hidden.value)
        // The source tag is hidden from this reader too. The poll still produced the value, as the gateway.
        assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, engine.read("Line/Secret", viewer).quality.code)
    }

    @Test
    fun datasetReturnPublishesTheWholeResultAndScalarPublishesTheFirstCell() {
        val table = table("q_rows")
        db.sql("CREATE TABLE $table (id integer, label text)")
        db.sql("INSERT INTO $table (id, label) VALUES (1, 'a'), (2, 'b')")
        engine.configure(query("Line/Cell", "SELECT id, label FROM $table ORDER BY id"))
        engine.configure(query(
            "Line/Table",
            "SELECT id, label FROM $table ORDER BY id",
            DataType.DOCUMENT,
            queryDataset = true,
        ))
        runtime.tick(t0)

        assertGood(1, "Line/Cell")
        val doc = engine.read("Line/Table")
        assertEquals(QualityCode.GOOD, doc.quality.code)
        @Suppress("UNCHECKED_CAST")
        val body = doc.value as Map<String, Any?>
        assertEquals(listOf("id", "label"), body["columns"])
        assertEquals(listOf(listOf(1, "a"), listOf(2, "b")), body["rows"])
        assertEquals(false, body["truncated"])
    }

    private fun query(
        path: String,
        sql: String,
        dataType: DataType = DataType.INT32,
        datasource: String = "plant",
        queryDataset: Boolean = false,
        readPermissions: Set<String>? = null,
    ) = TagConfig(
        path = path,
        type = TagType.QUERY,
        dataType = dataType,
        query = sql,
        datasource = datasource,
        queryDataset = queryDataset,
        tagGroup = "fast",
        readPermissions = readPermissions,
    )

    private fun assertGood(expected: Int, path: String) {
        val value = engine.read(path)
        assertEquals(QualityCode.GOOD, value.quality.code, path)
        assertEquals(expected, (value.value as Number).toInt(), path)
    }

    private fun table(prefix: String): String {
        val name = "${prefix}_$suffix"
        tables += name
        return name
    }

    private fun count(table: String): Int = db.jdbc { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
}

private class PlantDb(dir: Path) : AutoCloseable {
    private val registry = SchemaRegistry()
    private val store = FileConfigStore(dir.resolve("config"), registry)
    private val secrets = linkedMapOf<String, String>()
    val db: DatabaseService = DatabaseService(
        store,
        dir.resolve("data"),
        Clock.systemUTC(),
        readSecret = { reference -> secrets[reference.removePrefix("secret://")] ?: error("missing secret") },
        writeSecret = { name, plaintext, _ ->
            secrets[name] = plaintext
            "secret://$name"
        },
    ).also { it.attach() }

    private lateinit var url: String
    var version: Long = 0

    fun connect(jdbcUrl: String) {
        url = jdbcUrl
        version = save(enabled = true, version = 0)
    }

    fun save(enabled: Boolean, version: Long): Long {
        val saved = db.saveConnection(
            "plant",
            mapOf(
                "driver" to "postgres",
                "url" to url,
                "username" to PostgresServer.USER,
                "enabled" to enabled,
            ),
            version,
            if (version == 0L) PasswordUpdate.Set(PostgresServer.PASSWORD) else PasswordUpdate.Unchanged,
        )
        this.version = saved.metadata.version
        return saved.metadata.version
    }

    fun sql(statement: String) {
        jdbc { connection -> connection.createStatement().use { it.execute(statement) } }
    }

    fun <T> jdbc(block: (Connection) -> T): T = db.withConnection("plant") { block(it) }

    override fun close() {
        db.close()
        store.close()
    }
}
