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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

/** csv, simulator, splitter and embedded provider resources and the store-and-forward engine resource (FEATURES HIS-006, HIS-007, HIS-009, HIS-024). */
class HistoryProviderTypesBindingTest {
    @TempDir lateinit var tmp: Path

    private val clock = FakeClock(Instant.parse("2026-06-01T00:00:00Z"))

    private fun provider(name: String, spec: Map<String, Any?>) = Resource(HISTORY_PROVIDER_KIND, Metadata(name, HISTORIAN_MODULE), spec)
    private fun engine(name: String, spec: Map<String, Any?>) = Resource(STORE_AND_FORWARD_KIND, Metadata(name, HISTORIAN_MODULE), spec)

    private val noConnections = object : JdbcConnectionSource {
        override fun borrow(): Connection = throw java.sql.SQLException("not used", "08001")
        override fun release(connection: Connection, broken: Boolean) {}
    }

    private class Fixture(val store: FileConfigStore, val router: HistoryRouter, val local: EmbeddedHistorian, val binding: HistoryProviderBinding, val engines: MutableList<StoreAndForwardSettings>)

    private fun withBinding(body: Fixture.() -> Unit) {
        val registry = SchemaRegistry()
        val csvRoot = Files.createDirectories(tmp.resolve("history-csv"))
        val engines = ArrayList<StoreAndForwardSettings>()
        FileConfigStore(tmp.resolve("config"), registry, clock).use { store ->
            EmbeddedHistorian(tmp.resolve("history.sqlite"), clock = clock).use { local ->
                val router = HistoryRouter(local, ForwardBuffer(tmp.resolve("fwd.sqlite")), clock)
                HistoryProviderBinding(
                    router, store, registry,
                    local = local, csvRoot = csvRoot, archiveRoot = tmp.resolve("history-archive"), clock = clock,
                    onEngine = { engines += it },
                ) { noConnections }.use { binding ->
                    binding.attach()
                    Fixture(store, router, local, binding, engines).body()
                }
                router.close()
            }
        }
    }

    @Test
    fun csvSimulatorAndSplitterResourcesRegisterProviders() = withBinding {
        Files.createDirectories(tmp.resolve("history-csv/plant"))
        Files.writeString(tmp.resolve("history-csv/plant/line1.csv"), "t,[default]A\n2026-06-01T00:00:00Z,1.5\n2026-06-01T00:00:01Z,2.5\n")
        store.put(Scope.GATEWAY, provider("line1", mapOf("type" to "csv", "file" to "plant/line1.csv", "relativeTimestamps" to false)))
        val csv = router.provider("line1") as CsvHistoryProvider
        assertEquals(tmp.resolve("history-csv/plant/line1.csv").toAbsolutePath().normalize(), csv.file)
        assertEquals(listOf(1.5, 2.5), router.reader("line1")!!.query(HistoryQuery("[default]A", 0, Long.MAX_VALUE)).map { it.value })

        store.put(Scope.GATEWAY, provider("sim", mapOf("type" to "simulator")))
        assertTrue(router.provider("sim") is SimulatorHistoryProvider)

        store.put(Scope.GATEWAY, provider("pg", mapOf("connection" to "plant-db")))
        store.put(Scope.GATEWAY, provider("both", mapOf("type" to "splitter", "first" to "pg", "second" to "local",
            "limitFirstQuery" to mapOf("length" to 2, "units" to "weeks"))))
        val splitter = router.provider("both") as SplitterHistoryProvider
        assertEquals("pg" to "local", splitter.first to splitter.second)
        assertEquals(14L * 86_400_000L, splitter.limitFirstMillis)

        store.delete(provider("both", emptyMap()).id())
        assertNull(router.provider("both"))
        assertTrue(binding.problems().isEmpty(), binding.problems().toString())
    }

    @Test
    fun storeAndForwardBlocksSetEachProvidersPolicyEvenWhileDisabled() = withBinding {
        store.put(Scope.GATEWAY, provider("pg", mapOf("connection" to "plant-db", "enabled" to false,
            "storeAndForward" to mapOf("forwardRateMillis" to 2_000, "batchSize" to 500, "retryMillis" to 9_000, "maxPending" to 1_000, "whenFull" to "evictOldestData"))))
        assertNull(router.provider("pg"))
        assertEquals(ForwardPolicy(2_000, 500, 9_000, 1_000, WhenFull.EVICT_OLDEST_DATA), router.policyFor("pg"))
        store.delete(provider("pg", emptyMap()).id())
        assertEquals(HistoryRouter.DEFAULT_BATCH, router.policyFor("pg").batchSize, "deleting the provider drops its policy")
    }

    @Test
    fun theLocalResourceConfiguresTheEmbeddedHistorianAndDeletingItRestoresTheDefaults() = withBinding {
        store.put(Scope.GATEWAY, provider(LOCAL_PROVIDER, mapOf("type" to "embedded", "partitionInterval" to "month", "deduplication" to false,
            "maintenance" to mapOf("mode" to "archive", "age" to 2, "ageUnits" to "year"))))
        val s = local.settings
        assertEquals(PartitionInterval.MONTH, s.partitionInterval)
        assertEquals(false, s.deduplication)
        assertEquals(MaintenanceMode.ARCHIVE, s.maintenanceMode)
        assertEquals(2, s.maintenanceAge)
        assertEquals(PartitionInterval.YEAR, s.maintenanceAgeUnits)
        assertEquals(tmp.resolve("history-archive/local").toAbsolutePath().normalize(), s.archiveFolder)
        assertTrue(binding.problems().isEmpty(), binding.problems().toString())

        val stored = store.get(provider(LOCAL_PROVIDER, emptyMap()).id())!!
        store.put(Scope.GATEWAY, stored.copy(spec = mapOf("type" to "embedded", "partitionInterval" to "month", "maintenance" to mapOf("mode" to "prune", "age" to 1, "ageUnits" to "week"))))
        assertTrue(binding.problems().any { it.contains("multiple") }, "an age that is not a whole number of partitions is a problem: ${binding.problems()}")
        assertEquals(MaintenanceMode.ARCHIVE, local.settings.maintenanceMode, "the last good settings stay")

        store.delete(provider(LOCAL_PROVIDER, emptyMap()).id())
        assertEquals(EmbeddedHistorianSettings(), local.settings)

        store.put(Scope.GATEWAY, provider("other", mapOf("type" to "embedded")))
        assertNull(router.provider("other"))
        assertTrue(binding.problems().any { it.contains("only the built-in") }, binding.problems().toString())
    }

    @Test
    fun theEngineResourceReachesTheGatewayAndOtherNamesAreProblems() = withBinding {
        store.put(Scope.GATEWAY, engine(STORE_AND_FORWARD_NAME, mapOf("memoryBufferSize" to 5_000, "timeThresholdMillis" to 30_000, "dataThreshold" to 200, "batchSize" to 100)))
        assertEquals(StoreAndForwardSettings(5_000, 30_000, 200, 100), engines.last())
        store.put(Scope.GATEWAY, engine("other", mapOf("batchSize" to 1)))
        assertEquals(1, engines.size)
        assertTrue(binding.problems().any { it.contains("must be named 'engine'") }, binding.problems().toString())
        store.delete(engine(STORE_AND_FORWARD_NAME, emptyMap()).id())
        assertEquals(StoreAndForwardSettings(), engines.last())
        assertThrows(ValidationException::class.java) { store.put(Scope.GATEWAY, engine(STORE_AND_FORWARD_NAME, mapOf("timeThresholdMillis" to 1))) }
    }

    @Test
    fun invalidProviderResourcesAreRefusedBeforeTheyAreStored() = withBinding {
        fun refused(name: String, spec: Map<String, Any?>, why: String) {
            val e = assertThrows(ValidationException::class.java) { store.put(Scope.GATEWAY, provider(name, spec)) }
            assertTrue(e.message!!.contains(why), "${e.message} should mention $why")
        }
        refused("pg", emptyMap(), "connection is required")
        refused("line1", mapOf("type" to "csv"), "file is required")
        refused("line1", mapOf("type" to "csv", "file" to "../secret.csv"), "file")
        refused("line1", mapOf("type" to "csv", "file" to "plant/../../x.csv"), "file")
        refused("both", mapOf("type" to "splitter", "first" to "pg"), "second is required")
        refused("both", mapOf("type" to "splitter", "first" to "pg", "second" to "pg"), "different")
        refused(LOCAL_PROVIDER, mapOf("type" to "embedded", "maintenance" to mapOf("mode" to "prune")), "maintenance.age")
        refused("pg", mapOf("connection" to "plant-db", "storeAndForward" to mapOf("whenFull" to "dropEverything")), "whenFull")
        assertNull(router.provider("pg"))
    }
}
