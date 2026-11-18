package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.oip.tags.DataType
import org.oip.tags.DeadbandMode
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.time.Instant

/** Per-tag history settings are a config resource, so collection follows config edits and resumes after a restart. */
class HistoryConfigBindingTest {
    @TempDir lateinit var tmp: Path

    private val clock = FakeClock(Instant.parse("2026-06-01T00:00:00Z"))

    private fun settings(name: String, spec: Map<String, Any?>) = Resource(HISTORY_SETTINGS_KIND, Metadata(name, HISTORIAN_MODULE), spec)

    private fun engine() = TagEngine(clock).also {
        it.configure(TagConfig(path = "Plant/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
        it.configure(TagConfig(path = "Plant/Level", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
    }

    @Test
    fun resourcesConfigureAndStopCollection() {
        val registry = SchemaRegistry()
        FileConfigStore(tmp.resolve("config"), registry, clock).use { store ->
            val collector = HistoryCollector(engine(), MemoryHistorySink(), clock)
            HistoryConfigBinding(collector, store, registry).use { binding ->
                binding.attach()
                store.put(Scope.GATEWAY, settings("speed", mapOf("tagPath" to "Plant/Speed", "sampleMode" to "periodic",
                    "sampleRateMillis" to 500, "deadband" to mapOf("mode" to "absolute", "value" to 2.5))))
                assertTrue(collector.isCollecting("[default]Plant/Speed"))
                val s = collector.settings().single()
                assertEquals(HistorySampleMode.PERIODIC, s.sampleMode)
                assertEquals(500L, s.sampleRateMillis)
                assertEquals(DeadbandMode.ABSOLUTE, s.deadband.mode)
                assertEquals(2.5, s.deadband.value)

                // Retargeting the resource moves collection to the new tag.
                store.put(Scope.GATEWAY, store.get(settings("speed", emptyMap()).id())!!.let {
                    it.copy(spec = mapOf("tagPath" to "Plant/Level"))
                })
                assertFalse(collector.isCollecting("Plant/Speed"))
                assertTrue(collector.isCollecting("Plant/Level"))

                store.delete(settings("speed", emptyMap()).id())
                assertFalse(collector.isCollecting("Plant/Level"))
                assertTrue(binding.problems().isEmpty(), binding.problems().toString())
            }
        }
    }

    @Test
    fun theSchemaRejectsBadSettings() {
        val registry = SchemaRegistry()
        FileConfigStore(tmp.resolve("config"), registry, clock).use { store ->
            HistoryConfigBinding(HistoryCollector(engine(), MemoryHistorySink(), clock), store, registry).use { binding ->
                binding.attach()
                for (bad in listOf(
                    mapOf("sampleMode" to "periodic"),
                    mapOf("tagPath" to "Plant/Speed", "sampleMode" to "sometimes"),
                    mapOf("tagPath" to "Plant/Speed", "sampleRateMillis" to 0),
                    mapOf("tagPath" to "Plant/Speed", "deadband" to mapOf("mode" to "absolute", "value" to -1)),
                    mapOf("tagPath" to "Plant/Speed", "unknown" to true),
                )) {
                    assertThrows(ValidationException::class.java, { store.put(Scope.GATEWAY, settings("bad", bad)) }, bad.toString())
                }
            }
        }
    }

    @Test
    fun collectionResumesAfterARestartFromConfigAlone() {
        val db = tmp.resolve("db/history.sqlite")
        run {
            val registry = SchemaRegistry()
            FileConfigStore(tmp.resolve("config"), registry, clock).use { store ->
                SqliteHistoryStore(db).use { sink ->
                    val collector = HistoryCollector(engine(), sink, clock)
                    HistoryConfigBinding(collector, store, registry).use { binding ->
                        binding.attach()
                        store.put(Scope.GATEWAY, settings("speed", mapOf("tagPath" to "[default]Plant/Speed")))
                    }
                    collector.close()
                }
            }
        }
        // Restart: nothing calls configure() in code; the binding reads the stored resource.
        val registry = SchemaRegistry()
        FileConfigStore(tmp.resolve("config"), registry, clock).use { store ->
            SqliteHistoryStore(db).use { sink ->
                val engine = engine()
                val collector = HistoryCollector(engine, sink, clock)
                HistoryConfigBinding(collector, store, registry).use { it.attach() }
                assertTrue(collector.isCollecting("[default]Plant/Speed"))
                clock.advance(java.time.Duration.ofSeconds(5))
                engine.write("[default]Plant/Speed", 42.0)
                collector.close()
                val values = sink.query(HistoryQuery("[default]Plant/Speed", Long.MIN_VALUE, Long.MAX_VALUE)).map { it.value }
                assertEquals(42.0, values.last())
            }
        }
    }
}
