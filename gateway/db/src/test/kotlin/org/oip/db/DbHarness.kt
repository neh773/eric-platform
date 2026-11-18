package org.oip.db

import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SettableClock(var millis: Long = 1_700_000_000_000L) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    override fun millis(): Long = millis
}

/** In-memory secret map and a config store. The password text never goes into a resource. */
class DbHarness(dir: Path, clock: Clock = Clock.systemUTC()) : AutoCloseable {
    val secrets = linkedMapOf<String, String>()
    val registry = SchemaRegistry()
    val store = FileConfigStore(dir.resolve("config"), registry, clock)
    val db = DatabaseService(
        store,
        dir.resolve("data"),
        clock,
        readSecret = { reference ->
            val name = reference.removePrefix("secret://")
            secrets[name] ?: error("missing secret")
        },
        writeSecret = { name, plaintext, _ ->
            secrets[name] = plaintext
            "secret://$name"
        },
    ).also { it.attach() }

    fun connection(
        name: String,
        driver: String = "sqlite",
        url: String = "jdbc:sqlite::memory:",
        password: PasswordUpdate = PasswordUpdate.Unchanged,
        fields: Map<String, Any?> = emptyMap(),
        version: Long = 0,
    ) = db.saveConnection(name, mapOf("driver" to driver, "url" to url) + fields, version, password)

    override fun close() {
        db.close()
        store.close()
    }
}
