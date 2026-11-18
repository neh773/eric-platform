package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

/** Providers and the per-tag storage provider are config resources (SPEC §4). */
class HistoryProviderBindingTest {
    @TempDir lateinit var tmp: Path

    private val clock = FakeClock(Instant.parse("2026-06-01T00:00:00Z"))

    private fun provider(name: String, spec: Map<String, Any?>) = Resource(HISTORY_PROVIDER_KIND, Metadata(name, HISTORIAN_MODULE), spec)
    private fun settings(name: String, spec: Map<String, Any?>) = Resource(HISTORY_SETTINGS_KIND, Metadata(name, HISTORIAN_MODULE), spec)

    private val noConnections = object : JdbcConnectionSource {
        override fun borrow(): Connection = throw java.sql.SQLException("not used", "08001")
        override fun release(connection: Connection, broken: Boolean) {}
    }

    @Test
    fun providerResourcesRegisterReplaceAndRemoveProvidersAndSettingsRouteTags() {
        val registry = SchemaRegistry()
        val asked = ArrayList<String>()
        FileConfigStore(tmp.resolve("config"), registry, clock).use { store ->
            val router = HistoryRouter(MemoryHistorySink(), ForwardBuffer(tmp.resolve("fwd.sqlite")), clock)
            val engine = TagEngine(clock).also {
                it.configure(TagConfig(path = "Plant/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
            }
            val collector = HistoryCollector(engine, router, clock)
            HistoryProviderBinding(router, store, registry) { name -> asked += name; noConnections }.use { providers ->
                HistoryConfigBinding(collector, store, registry, router).use { settings ->
                    providers.attach()
                    settings.attach()
                    store.put(Scope.GATEWAY, provider("plant-pg", mapOf("connection" to "plant-db", "timescale" to "off", "table" to "hist")))
                    val p = router.provider("plant-pg") as SqlHistoryProvider
                    assertEquals("hist", p.table)
                    assertEquals(TimescaleMode.OFF, p.timescale)
                    assertEquals(listOf("plant-db"), asked, "building a provider does not connect")

                    store.put(Scope.GATEWAY, settings("speed", mapOf("tagPath" to "Plant/Speed", "storageProvider" to "plant-pg")))
                    assertEquals("plant-pg", router.providerFor("[default]Plant/Speed"))
                    collector.flush()
                    assertEquals(1L, router.buffer.pendingCounts()["plant-pg"], "the first sample is buffered for the provider")

                    store.put(Scope.GATEWAY, store.get(provider("plant-pg", emptyMap()).id())!!.let { it.copy(spec = it.spec + ("enabled" to false)) })
                    assertNull(router.provider("plant-pg"))
                    store.put(Scope.GATEWAY, store.get(provider("plant-pg", emptyMap()).id())!!.let { it.copy(spec = it.spec + ("enabled" to true)) })
                    assertTrue(router.provider("plant-pg") is SqlHistoryProvider)
                    store.delete(provider("plant-pg", emptyMap()).id())
                    assertNull(router.provider("plant-pg"))

                    store.put(Scope.GATEWAY, provider("local", mapOf("connection" to "x")))
                    assertNull(router.provider("local"))
                    assertTrue(providers.problems().any { it.contains("built-in") }, providers.problems().toString())

                    assertThrows(ValidationException::class.java) {
                        store.put(Scope.GATEWAY, provider("bad", mapOf("connection" to "x", "table" to "Robert'); DROP TABLE x;--")))
                    }
                    assertThrows(ValidationException::class.java) {
                        store.put(Scope.GATEWAY, settings("bad", mapOf("tagPath" to "A", "storageProvider" to "Not A Name")))
                    }
                }
            }
            collector.close()
            router.close()
        }
    }
}
