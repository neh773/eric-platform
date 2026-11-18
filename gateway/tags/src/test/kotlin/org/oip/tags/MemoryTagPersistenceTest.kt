package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Memory-tag persistence (TAG-016): - */
class MemoryTagPersistenceTest {
    @TempDir lateinit var dir: Path

    private val db: Path get() = dir.resolve("db").resolve(MemoryTagPersistence.FILE_NAME)

    /** One gateway lifetime: config store, engine, persistence opened before the binding loads tags. */
    private inner class Gateway(flushMillis: Long = 60_000) : AutoCloseable {
        val registry = SchemaRegistry()
        val store = FileConfigStore(dir.resolve("config"), registry)
        val engine = TagEngine()
        val persistence = MemoryTagPersistence.open(engine, db, flushMillis)
        val binding = TagConfigBinding(engine, store, registry).also { it.attach() }

        fun tag(name: String, vararg spec: Pair<String, Any?>) {
            val id = ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, name)
            val version = store.get(id)?.metadata?.version ?: 0L
            store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata(name, TAGS_MODULE, version = version), mapOf(*spec)))
        }

        fun udt(name: String, vararg spec: Pair<String, Any?>) =
            store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata(name, TAGS_MODULE), mapOf(*spec)))

        /** Clean shutdown. */
        override fun close() {
            binding.close()
            persistence.close()
            store.close()
        }
    }

    private fun rows(): Map<String, String?> = DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT path, value FROM memory_tag").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
            }
        }
    }

    private fun num(v: Any?): Double = (v as Number).toDouble()

    @Test
    fun aWriteSurvivesARestartWithValueQualityAndTimestamp() {
        val written: QualifiedValue
        Gateway().use { gw ->
            gw.tag("speed", "path" to "Line/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.engine.write("Line/Speed", 42.5)
            written = gw.engine.read("Line/Speed")
        }
        Gateway().use { gw ->
            val restored = gw.engine.read("Line/Speed")
            assertEquals(42.5, num(restored.value))
            assertEquals(QualityCode.GOOD, restored.quality.code)
            assertEquals(written.timestamp, restored.timestamp, "timestamp restored to the microsecond")
        }
    }

    @Test
    fun writesAreBatchedOffTheWritePath() {
        Gateway().use { gw ->
            gw.tag("speed", "path" to "Line/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            repeat(100) { gw.engine.write("Line/Speed", it.toDouble()) }
            assertTrue(rows().isEmpty(), "a write does not touch the database")
            assertEquals(1, gw.persistence.flush(), "100 changes to one tag coalesce to one row")
            assertEquals("99.0", rows().values.single())
        }
    }

    @Test
    fun manyWritesThenCleanShutdownRestoreTheLastValueOfEachTag() {
        Gateway().use { gw ->
            repeat(200) { i -> gw.tag("t$i", "path" to "Bulk/T$i", "type" to "memory", "dataType" to "Int32", "value" to 0) }
            val samples = (0 until 200).map { gw.engine.openSample("Bulk/T$it") }
            for (round in 1..50) for ((i, s) in samples.withIndex()) s.set(round * 1000 + i)
        }
        Gateway().use { gw ->
            for (i in 0 until 200) assertEquals(50_000 + i, (gw.engine.read("Bulk/T$i").value as Number).toInt(), "Bulk/T$i")
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun aCrashLosesAtMostTheFlushWindowAndNeverCorruptsTheDb() {
        val child = ProcessBuilder(javaBinary(), "-cp", childClasspath(), MemoryCrashChild::class.java.name, db.toString())
            .redirectErrorStream(true)
            .start()
        var durable = 0L
        try {
            val out = child.inputStream.bufferedReader()
            while (durable < 2000) {
                val line = out.readLine() ?: fail("child exited early")
                if (line.startsWith("durable ")) durable = line.removePrefix("durable ").toLong()
            }
        } finally {
            child.destroyForcibly() // SIGKILL: no shutdown hooks, no close(), possibly mid-transaction
            child.waitFor(30, TimeUnit.SECONDS)
        }

        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA integrity_check").use { rs -> assertTrue(rs.next()); assertEquals("ok", rs.getString(1)) }
            }
        }
        val restart = TagEngine()
        MemoryTagPersistence.open(restart, db).use {
            repeat(MemoryCrashChild.TAGS) { restart.configure(TagConfig(path = "Crash/T$it", type = TagType.MEMORY, dataType = DataType.INT64)) }
            for (i in 0 until MemoryCrashChild.TAGS) {
                val v = (restart.read("Crash/T$i").value as Number).toLong()
                assertTrue(v >= durable, "Crash/T$i restored $v, but $durable was confirmed flushed before the kill")
            }
        }
    }

    @Test
    fun deletingATagDeletesItsRowAndARecreatedTagStartsFromItsSeed() {
        Gateway().use { gw ->
            gw.tag("speed", "path" to "Line/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.engine.write("Line/Speed", 9.0)
            gw.persistence.flush()
            assertEquals(setOf("[default]Line/Speed"), rows().keys)

            gw.store.delete(ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, "speed"))
            gw.persistence.flush()
            assertTrue(rows().isEmpty())
            gw.tag("speed", "path" to "Line/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)
        }
        Gateway().use { gw -> assertEquals(1.0, num(gw.engine.read("Line/Speed").value)) }
    }

    @Test
    fun movingATagCarriesItsValueToTheNewPath() {
        Gateway().use { gw ->
            gw.tag("speed", "path" to "Line/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.engine.write("Line/Speed", 42.0)
            gw.tag("speed", "path" to "Line2/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)

            assertEquals(QualityCode.BAD_NOT_FOUND, gw.engine.read("Line/Speed").quality.code, "the old path is gone, not left behind")
            assertEquals(42.0, num(gw.engine.read("Line2/Speed").value))
            gw.persistence.flush()
            assertEquals(setOf("[default]Line2/Speed"), rows().keys)
        }
        Gateway().use { gw -> assertEquals(42.0, num(gw.engine.read("Line2/Speed").value)) }
    }

    @Test
    fun arraysStringsDatesAndDocumentsRoundTrip() {
        val stamp = Instant.parse("2026-10-27T12:34:56.789Z")
        Gateway().use { gw ->
            gw.tag("arr", "path" to "Arr", "type" to "memory", "dataType" to "Double", "value" to listOf(0.0, 0.0, 0.0))
            gw.tag("name", "path" to "Name", "type" to "memory", "dataType" to "String", "value" to "")
            gw.tag("when", "path" to "When", "type" to "memory", "dataType" to "DateTime")
            gw.tag("doc", "path" to "Doc", "type" to "memory", "dataType" to "Document")
            assertEquals(QualityCode.GOOD, gw.engine.write("Arr[1]", 2.5).quality.code)
            gw.engine.write("Name", "line \"7\" — ünïcode")
            gw.engine.write("When", stamp)
            gw.engine.write("Doc", mapOf("batch" to "B-12", "steps" to listOf(1, 2, 3)))
        }
        Gateway().use { gw ->
            assertEquals(listOf(0.0, 2.5, 0.0), (gw.engine.read("Arr").value as List<*>).map { num(it) })
            assertEquals("line \"7\" — ünïcode", gw.engine.read("Name").value)
            assertEquals(stamp, gw.engine.read("When").value)
            assertEquals(mapOf("batch" to "B-12", "steps" to listOf(1, 2, 3)), gw.engine.read("Doc").value)
        }
    }

    @Test
    fun aDataTypeOrSeedChangeDiscardsTheStaleValue() {
        Gateway().use { gw ->
            gw.tag("a", "path" to "A", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.tag("b", "path" to "B", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.engine.write("A", 5.5)
            gw.engine.write("B", 6.5)
        }
        // Edit the config while the gateway is down.
        FileConfigStore(dir.resolve("config"), SchemaRegistry().also { registerTagSchemas(it) }).use { store ->
            fun edit(name: String, vararg spec: Pair<String, Any?>) {
                val current = store.get(ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, name))!!
                store.put(Scope.GATEWAY, current.copy(spec = current.spec + mapOf(*spec)))
            }
            edit("a", "dataType" to "Int32")
            edit("b", "value" to 2.0)
        }
        Gateway().use { gw ->
            assertEquals(1, (gw.engine.read("A").value as Number).toInt(), "data type changed: seed wins")
            assertEquals(2.0, num(gw.engine.read("B").value), "seed changed: new seed wins")
            gw.persistence.flush()
            assertTrue(rows().isEmpty(), "stale rows are deleted")
        }
    }

    @Test
    fun udtMemberValuesPersist() {
        Gateway().use { gw ->
            gw.udt("motor", "path" to "Types/Motor", "members" to listOf(
                mapOf("name" to "Setpoint", "type" to "memory", "dataType" to "Double", "value" to 0.0),
            ))
            gw.tag("m1", "path" to "Line/M1", "type" to "udt", "typeId" to "Types/Motor")
            gw.engine.write("Line/M1/Setpoint", 77.0)
        }
        Gateway().use { gw -> assertEquals(77.0, num(gw.engine.read("Line/M1/Setpoint").value)) }
    }

    @Test
    fun rowsForTagsDeletedWhileTheGatewayWasDownArePruned() {
        Gateway().use { gw ->
            gw.tag("a", "path" to "A", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.tag("b", "path" to "B", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.engine.write("A", 3.0)
            gw.engine.write("B", 4.0)
        }
        // Delete B's config file while "offline": open the store without an engine.
        FileConfigStore(dir.resolve("config"), SchemaRegistry().also { registerTagSchemas(it) }).use {
            it.delete(ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, "b"))
        }
        Gateway().use { gw ->
            assertEquals(1, gw.persistence.pruneUnclaimed())
            gw.persistence.flush()
            assertEquals(setOf("[default]A"), rows().keys)
        }
    }

    private fun javaBinary(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** The test JVM's classpath plus the jars these classes came from (Gradle's worker may hide some). */
    private fun childClasspath(): String {
        val anchors = listOf(
            MemoryCrashChild::class.java, TagEngine::class.java, org.oip.config.Canon::class.java,
            org.oip.security.AuditLog::class.java, org.sqlite.JDBC::class.java, Unit::class.java,
            com.fasterxml.jackson.databind.ObjectMapper::class.java, com.fasterxml.jackson.core.JsonFactory::class.java,
            com.fasterxml.jackson.annotation.JsonInclude::class.java,
        ).map { File(it.protectionDomain.codeSource.location.toURI()).path }
        return (System.getProperty("java.class.path").split(File.pathSeparator) + anchors)
            .filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)
    }
}

