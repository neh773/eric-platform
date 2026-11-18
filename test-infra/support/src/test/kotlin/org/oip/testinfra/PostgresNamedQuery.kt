package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.db.DatabaseService
import org.oip.db.PasswordUpdate
import org.oip.db.QueryForbidden
import org.oip.db.QueryResult
import java.nio.file.Path

/**
 * Named-query binding against the compose Timescale/Postgres service.
 * Uses the stack the smoke test already started; it does not open another container.
 */
object PostgresNamedQuery {
    fun check(dir: Path, jdbcUrl: String) {
        val secrets = linkedMapOf<String, String>()
        val store = FileConfigStore(dir.resolve("config"), SchemaRegistry())
        val db = DatabaseService(
            store,
            dir.resolve("data"),
            readSecret = { reference -> secrets[reference.removePrefix("secret://")] ?: error("missing secret") },
            writeSecret = { name, plaintext, _ ->
                secrets[name] = plaintext
                "secret://$name"
            },
        )
        try {
            db.attach()
            db.saveConnection(
                "pg",
                mapOf("driver" to "postgres", "url" to jdbcUrl, "username" to SimPorts.DB_USER),
                0,
                PasswordUpdate.Set(SimPorts.DB_PASSWORD),
            )
            val status = db.connection("pg")
            assertEquals("Valid", status["status"], status["error"]?.toString())
            assertEquals(true, status["passwordSet"])
            assertFalse(status.containsKey("password"))

            db.saveQuery(
                "make",
                mapOf(
                    "connection" to "pg",
                    "queryType" to "update",
                    "sql" to "CREATE TABLE IF NOT EXISTS oip_named_query (name TEXT PRIMARY KEY, note TEXT NOT NULL)",
                ),
                0,
            )
            db.execute("make", emptyMap(), emptySet())
            db.saveQuery("wipe", mapOf("connection" to "pg", "queryType" to "update", "sql" to "DELETE FROM oip_named_query"), 0)
            db.execute("wipe", emptyMap(), emptySet())
            db.saveQuery(
                "add",
                mapOf(
                    "connection" to "pg",
                    "queryType" to "update",
                    "sql" to "INSERT INTO oip_named_query(name, note) VALUES (:name, :note)",
                    "parameters" to listOf(
                        mapOf("name" to "name", "dataType" to "string"),
                        mapOf("name" to "note", "dataType" to "string"),
                    ),
                ),
                0,
            )
            db.execute("add", mapOf("name" to "ada", "note" to "ok"), emptySet())
            db.saveQuery(
                "find",
                mapOf(
                    "connection" to "pg",
                    "queryType" to "scalar",
                    "sql" to "SELECT note FROM oip_named_query WHERE name = :name",
                    "parameters" to listOf(mapOf("name" to "name", "dataType" to "string")),
                    "roles" to listOf("operator"),
                ),
                0,
            )
            assertThrows<QueryForbidden> { db.execute("find", mapOf("name" to "ada"), setOf("viewer")) }
            val found = db.execute("find", mapOf("name" to "ada"), setOf("operator")) as QueryResult.Scalar
            assertEquals("ok", found.value)
            val injected = db.execute("find", mapOf("name" to "ada' OR '1'='1"), setOf("operator")) as QueryResult.Scalar
            assertNull(injected.value)
            db.saveQuery("count", mapOf("connection" to "pg", "queryType" to "scalar", "sql" to "SELECT COUNT(*) FROM oip_named_query"), 0)
            val left = db.execute("count", emptyMap(), emptySet()) as QueryResult.Scalar
            assertEquals(1, (left.value as Number).toInt())
        } finally {
            db.close()
            store.close()
        }
    }
}
