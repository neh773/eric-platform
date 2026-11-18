package org.oip.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

class ConfigEventsTest {
    @TempDir lateinit var tmp: Path
    private val registry = SchemaRegistry().apply {
        register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer")), "required" to listOf("x")))
    }
    private lateinit var store: FileConfigStore
    private val events = LinkedBlockingQueue<ConfigEvent>()
    private val id = ResourceId("m", Scope.GATEWAY, "Thing", "a")
    private val file get() = tmp.resolve("config/m/gateway/Thing/a.yaml")
    private fun open() = FileConfigStore(tmp.resolve("config"), registry).also { store = it; it.subscribe { e -> events.add(e) } }
    private fun next(timeoutMs: Long = 2000) = events.poll(timeoutMs, TimeUnit.MILLISECONDS)
    private fun doc(x: Any, version: Long = 1) = "apiVersion: oip/v1\nkind: Thing\nmetadata:\n  name: a\n  module: m\n  version: $version\nspec:\n  x: $x\n"
    @AfterEach fun close() { if (::store.isInitialized) store.close() }

    @Test fun apiWritesEmitEvents() {
        open()
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 1)))
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m", version = 1), mapOf("x" to 2)))
        store.delete(id)
        assertEquals(listOf(ChangeType.CREATED, ChangeType.UPDATED, ChangeType.DELETED), listOf(next(), next(), next()).map { it.type })
        assertNull(events.poll())
    }

    @Test fun externalEditAppliedWithEventWithin2s() {
        open(); store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 1))); next()
        store.startWatching(intervalMs = 200)
        val t0 = System.nanoTime()
        file.writeText(doc(42))
        val e = next(2000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull(e, "no event within 2 s"); assertTrue(ms < 2000)
        assertEquals(ChangeType.UPDATED, e!!.type); assertEquals(ChangeSource.FILE, e.source); assertEquals(id, e.id)
        assertEquals(42, store.get(id)!!.spec["x"]); assertEquals(2L, store.get(id)!!.metadata.version)
        assertEquals("file", store.get(id)!!.metadata.modifiedBy)
        assertNull(next(600), "store's own normalising rewrite must not produce another event")
    }

    @Test fun externalCreateAndDelete() {
        open(); store.startWatching(intervalMs = 100)
        file.createParentDirectories().writeText(doc(5, version = 0))
        assertEquals(ChangeType.CREATED, next()!!.type); assertEquals(5, store.get(id)!!.spec["x"])
        file.deleteExisting()
        assertEquals(ChangeType.DELETED, next()!!.type); assertNull(store.get(id))
    }

    @Test fun invalidEditReportedStoreUnchanged() {
        open(); val v1 = store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 1))); next()
        store.startWatching(intervalMs = 100)
        file.writeText(doc("\"not-an-int\""))
        val e = next()!!
        assertEquals(ChangeType.INVALID, e.type); assertEquals(ChangeSource.FILE, e.source); assertNotNull(e.error)
        assertEquals(v1, store.get(id))
        file.writeText("apiVersion: oip/v1\nkind: Thing\nmetadata: {name: a, module: m\n") // malformed YAML
        assertEquals(ChangeType.INVALID, next()!!.type)
        assertEquals(v1, store.get(id))
        // an API write still works against the last valid version and repairs the file
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m", version = 1), mapOf("x" to 3)))
        assertEquals(ChangeType.UPDATED, next()!!.type)
        assertEquals(3, ResourceCodec.decode(file.readText()).spec["x"])
    }

    @Test fun multiFieldInvalidEditNeverPartiallyApplied() {
        registry.register("m", "Pair", mapOf("type" to "object", "properties" to mapOf("a" to mapOf("type" to "integer"), "b" to mapOf("type" to "integer"))))
        open()
        val v1 = store.put(Scope.GATEWAY, Resource("Pair", Metadata("p", "m"), mapOf("a" to 1, "b" to 1))); next()
        tmp.resolve("config/m/gateway/Pair/p.yaml").writeText("apiVersion: oip/v1\nkind: Pair\nmetadata: {name: p, module: m, version: 1}\nspec: {a: 2, b: x}\n")
        store.rescan()
        assertEquals(ChangeType.INVALID, next()!!.type)
        assertEquals(v1.spec, store.get(ResourceId("m", Scope.GATEWAY, "Pair", "p"))!!.spec)
    }

    @Test fun unsubscribeStopsDelivery() {
        open(); val got = mutableListOf<ConfigEvent>()
        val sub = store.subscribe { got += it }; sub.close()
        store.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to 1)))
        assertTrue(got.isEmpty())
    }
}
