package org.oip.tags.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.db.DatabaseService
import org.oip.tags.DataType
import org.oip.tags.QualityCode
import org.oip.tags.TagCaller
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * The runner does not open a thread per tag, and a missing connection is Bad_NotConnected.
 * Read permission is enforced by the engine even when the poll itself failed.
 */
class QueryTagRuntimeTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun missingConnectionIsBadAndOneTickServesEveryTag() {
        LocalDb(tmp).use { local ->
            val engine = TagEngine()
            val n = 200
            repeat(n) { i ->
                engine.configure(TagConfig(
                    path = "q$i",
                    type = TagType.QUERY,
                    dataType = DataType.INT32,
                    query = "SELECT 1",
                    datasource = "missing",
                ))
            }
            val runtime = QueryTagRuntime(engine, local.db)
            val before = tickers()
            runtime.attach()
            assertEquals(before, tickers(), "attach does not start a thread")
            assertEquals(QualityCode.UNCERTAIN_INITIAL_VALUE, engine.read("q0").quality.code)
            assertEquals(QualityCode.BAD_NOT_WRITABLE, engine.write("q0", 5).quality.code)

            val started = System.nanoTime()
            runtime.tick(Instant.EPOCH)
            val millis = (System.nanoTime() - started) / 1_000_000
            println("QUERY-TAG missing-connection tick ${millis}ms for $n tags")
            assertTrue(millis < 2_000, "tick took ${millis}ms")
            repeat(n) { i ->
                val value = engine.read("q$i")
                assertEquals(QualityCode.BAD_NOT_CONNECTED, value.quality.code, "q$i")
                assertNull(value.value, "q$i keeps the unset value")
            }
            // Not due again until the default group's 1000 ms.
            runtime.tick(Instant.EPOCH.plusMillis(999))
            assertEquals(QualityCode.BAD_NOT_CONNECTED, engine.read("q0").quality.code)

            runtime.start()
            assertEquals(1, tickers())
            runtime.close()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (tickers() > 0 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(0, tickers())
        }
    }

    @Test
    fun aReaderWithoutPermissionDoesNotSeeTheFailureEither() {
        LocalDb(tmp).use { local ->
            val engine = TagEngine()
            engine.configure(TagConfig(
                path = "Line/Hidden",
                type = TagType.QUERY,
                dataType = DataType.INT32,
                query = "SELECT 1",
                datasource = "missing",
                readPermissions = setOf("operator"),
            ))
            val runtime = QueryTagRuntime(engine, local.db).also { it.attach() }
            runtime.tick(Instant.EPOCH)
            val denied = engine.read("Line/Hidden", TagCaller.User("vic", setOf("viewer"), zone = "Floor"))
            assertEquals(QualityCode.BAD_USER_ACCESS_DENIED, denied.quality.code)
            assertNull(denied.value)
            assertEquals(QualityCode.BAD_NOT_CONNECTED, engine.read("Line/Hidden").quality.code)
            runtime.close()
        }
    }

    @Test
    fun aWriteStatementIsConfigurationErrorAndDoesNotNeedAConnection() {
        LocalDb(tmp).use { local ->
            val engine = TagEngine()
            engine.configure(TagConfig(
                path = "Line/Nope",
                type = TagType.QUERY,
                dataType = DataType.INT32,
                query = "DELETE FROM line WHERE id = {[default]Line/Id}",
                datasource = "missing",
            ))
            engine.configure(TagConfig(
                path = "Line/Broken",
                type = TagType.QUERY,
                dataType = DataType.INT32,
                query = "SELECT {[/]}",
                datasource = "missing",
            ))
            val runtime = QueryTagRuntime(engine, local.db).also { it.attach() }
            runtime.tick(Instant.EPOCH)
            assertEquals(QualityCode.ERROR_CONFIGURATION, engine.read("Line/Nope").quality.code)
            assertEquals(QualityCode.ERROR_INVALID_PATH, engine.read("Line/Broken").quality.code)
            runtime.close()
        }
    }

    private fun tickers() = Thread.getAllStackTraces().keys.count { it.name == QueryTagRuntime.THREAD && it.isAlive }
}

private class LocalDb(dir: Path) : AutoCloseable {
    private val registry = SchemaRegistry()
    private val store = FileConfigStore(dir.resolve("config"), registry)
    val db: DatabaseService = DatabaseService(
        store,
        dir.resolve("data"),
        Clock.systemUTC(),
        readSecret = { error("no secrets") },
        writeSecret = { _, _, _ -> error("no secrets") },
    ).also { it.attach() }

    override fun close() {
        db.close()
        store.close()
    }
}
