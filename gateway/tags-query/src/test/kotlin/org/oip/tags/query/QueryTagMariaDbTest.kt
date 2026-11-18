package org.oip.tags.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.db.DatabaseService
import org.oip.db.PasswordUpdate
import org.oip.tags.DataType
import org.oip.tags.QualityCode
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagGroupDef
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/** A query tag on a MariaDB connection (TAG-030, DAT-019): the value follows the table at the group rate. */
class QueryTagMariaDbTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun valueFollowsTableUpdatesAtTheGroupRateOnMariaDb() {
        val hostPort = MariaDbServer.require()
        val secrets = linkedMapOf<String, String>()
        val store = FileConfigStore(tmp.resolve("config"), SchemaRegistry())
        val db = DatabaseService(
            store, tmp.resolve("data"), Clock.systemUTC(),
            readSecret = { reference -> secrets[reference.removePrefix("secret://")] ?: error("missing secret") },
            writeSecret = { name, plaintext, _ -> secrets[name] = plaintext; "secret://$name" },
        ).also { it.attach() }
        try {
            db.saveConnection(
                "plant",
                mapOf("driver" to "mariadb", "url" to "jdbc:mariadb://$hostPort/oip", "username" to MariaDbServer.USER),
                0,
                PasswordUpdate.Set(MariaDbServer.PASSWORD),
            )
            db.withConnection("plant") { jdbc ->
                jdbc.createStatement().use {
                    it.execute("DROP TABLE IF EXISTS q_speed")
                    it.execute("CREATE TABLE q_speed (speed INT)")
                    it.execute("INSERT INTO q_speed (speed) VALUES (1)")
                }
            }
            val engine = TagEngine()
            engine.configureGroup(TagGroupDef("fast", rateMillis = 1_000))
            engine.configure(TagConfig(
                path = "Line/Speed",
                type = TagType.QUERY,
                dataType = DataType.INT32,
                query = "SELECT speed FROM q_speed WHERE speed >= {Line/Floor}",
                datasource = "plant",
                tagGroup = "fast",
            ))
            engine.configure(TagConfig(path = "Line/Floor", type = TagType.MEMORY, dataType = DataType.INT32, value = 0))
            val runtime = QueryTagRuntime(engine, db).also { it.attach() }
            val t0 = Instant.parse("2026-01-01T00:00:00Z")
            runtime.tick(t0)
            assertEquals(QualityCode.GOOD, engine.read("Line/Speed").quality.code)
            assertEquals(1, (engine.read("Line/Speed").value as Number).toInt())
            db.withConnection("plant") { jdbc -> jdbc.createStatement().use { it.execute("UPDATE q_speed SET speed = 2") } }
            runtime.tick(t0.plusMillis(999))
            assertEquals(1, (engine.read("Line/Speed").value as Number).toInt())
            runtime.tick(t0.plusMillis(1_000))
            assertEquals(2, (engine.read("Line/Speed").value as Number).toInt())
            runtime.close()
            db.withConnection("plant") { jdbc -> jdbc.createStatement().use { it.execute("DROP TABLE q_speed") } }
        } finally {
            db.close()
            store.close()
        }
    }
}
