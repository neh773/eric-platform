package org.oip.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.*

class FileConfigStoreTest {
    @TempDir lateinit var tmp: Path
    private val registry = SchemaRegistry().apply {
        register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
    }
    private fun store(hook: (Path) -> Unit = {}) = FileConfigStore(tmp.resolve("config"), registry, beforeCommit = hook)
    private fun thing(name: String, x: Int, version: Long = 0) = Resource("Thing", Metadata(name, "m", version = version), mapOf("x" to x))
    private val id = ResourceId("m", Scope.GATEWAY, "Thing", "a")

    @Test fun putGetListDelete() {
        val s = store()
        val v1 = s.put(Scope.GATEWAY, thing("a", 1), "alice")
        assertEquals(1L, v1.metadata.version); assertEquals("alice", v1.metadata.modifiedBy); assertNotNull(v1.metadata.modified)
        assertTrue(tmp.resolve("config/m/gateway/Thing/a.yaml").exists())
        s.put(Scope.project("p1"), thing("b", 2))
        assertEquals(v1, s.get(id))
        assertEquals(2, s.list().size); assertEquals(1, s.list(module = "m", scope = Scope.GATEWAY).size)
        assertEquals(listOf("b"), s.list(kind = "Thing", scope = Scope.project("p1")).map { it.id.name })
        val v2 = s.put(Scope.GATEWAY, thing("a", 5, version = 1))
        assertEquals(2L, v2.metadata.version); assertEquals(5, s.get(id)!!.spec["x"])
        s.delete(id, expectedVersion = 2)
        assertNull(s.get(id)); assertFalse(tmp.resolve("config/m/gateway/Thing/a.yaml").exists())
    }

    @Test fun putAllCommitsABatchAndAbortsBeforeWriteOnConflict() {
        val s = store()
        s.put(Scope.GATEWAY, thing("keep", 1))
        val count = 10_000
        val batch = (1..count).map { thing("t$it", it) }
        val started = System.nanoTime()
        val result = s.putAll(Scope.GATEWAY, batch, syncEach = false)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertEquals(count, result.created)
        assertEquals(0, result.updated)
        assertEquals(1, s.get(ResourceId("m", Scope.GATEWAY, "Thing", "t1"))!!.spec["x"])
        assertEquals(count, s.get(ResourceId("m", Scope.GATEWAY, "Thing", "t$count"))!!.spec["x"])
        assertTrue(seconds < 60, "putAll of $count took $seconds s")
        assertThrows(ConflictException::class.java) {
            s.putAll(Scope.GATEWAY, listOf(thing("fresh", 1), thing("keep", 9, version = 0)), syncEach = false)
        }
        assertNull(s.get(ResourceId("m", Scope.GATEWAY, "Thing", "fresh")))
        assertEquals(1, s.get(ResourceId("m", Scope.GATEWAY, "Thing", "keep"))!!.spec["x"])
    }

    @Test fun putAllHookFailureLeavesEveryTargetUntouched() {
        val s = store { if (it.fileName.toString().contains(".t2.yaml.")) throw RuntimeException("boom") }
        s.put(Scope.GATEWAY, thing("keep", 1))
        assertThrows(RuntimeException::class.java) {
            s.putAll(Scope.GATEWAY, listOf(thing("t1", 1), thing("t2", 2), thing("t3", 3)), syncEach = false)
        }
        assertNull(s.get(ResourceId("m", Scope.GATEWAY, "Thing", "t1")))
        assertNull(s.get(ResourceId("m", Scope.GATEWAY, "Thing", "t3")))
        assertEquals(1, s.get(ResourceId("m", Scope.GATEWAY, "Thing", "keep"))!!.spec["x"])
        val names = tmp.resolve("config/m/gateway/Thing").listDirectoryEntries().map { it.fileName.toString() }.filter { !it.startsWith(".") }
        assertEquals(listOf("keep.yaml"), names)
    }

    @Test fun persistsAcrossReopen() {
        store().put(Scope.GATEWAY, thing("a", 7))
        val r = store().get(id)!!
        assertEquals(7, r.spec["x"]); assertEquals(1L, r.metadata.version)
    }

    @Test fun stalePutConflicts() {
        val s = store(); s.put(Scope.GATEWAY, thing("a", 1)); s.put(Scope.GATEWAY, thing("a", 2, version = 1))
        assertThrows(ConflictException::class.java) { s.put(Scope.GATEWAY, thing("a", 3, version = 1)) }
        assertThrows(ConflictException::class.java) { s.put(Scope.GATEWAY, thing("a", 3, version = 0)) }   // create over existing
        assertThrows(ConflictException::class.java) { s.put(Scope.GATEWAY, thing("new", 3, version = 4)) } // update of missing
        assertThrows(ConflictException::class.java) { s.delete(id, expectedVersion = 1) }
        assertEquals(2, s.get(id)!!.spec["x"])
    }

    @Test fun concurrentPutWithSameStaleVersionExactlyOneWins() {
        val s = store(); s.put(Scope.GATEWAY, thing("a", 0))
        val n = 8; val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(n)
        val results = (1..n).map { i -> pool.submit<Boolean> {
            start.await(); try { s.put(Scope.GATEWAY, thing("a", i, version = 1)); true } catch (e: ConflictException) { false } } }
        start.countDown()
        val ok = results.map { it.get() }; pool.shutdown()
        assertEquals(1, ok.count { it }); assertEquals(n - 1, ok.count { !it })
        assertEquals(2L, s.get(id)!!.metadata.version)
        assertEquals(s.get(id), store().get(id))
    }

    @Test fun crashDuringWriteLeavesOldFileIntact() {
        store().put(Scope.GATEWAY, thing("a", 1))
        val file = tmp.resolve("config/m/gateway/Thing/a.yaml"); val before = file.readText()
        val crashing = store { throw RuntimeException("simulated crash before rename") }
        assertThrows(RuntimeException::class.java) { crashing.put(Scope.GATEWAY, thing("a", 2, version = 1)) }
        assertEquals(before, file.readText())
        assertEquals(1, crashing.get(id)!!.spec["x"])
        assertEquals(listOf("a.yaml"), file.parent.listDirectoryEntries().map { it.name }) // temp file cleaned up
        assertEquals(1, store().get(id)!!.spec["x"])
    }

    @Test fun leftoverTempFileFromHardCrashIgnoredOnLoad() {
        store().put(Scope.GATEWAY, thing("a", 1))
        tmp.resolve("config/m/gateway/Thing/.a.yaml.tmp-dead").writeText("apiVersion: oip/v1\nkind: Thi")
        val s = store(); assertEquals(1, s.get(id)!!.spec["x"]); assertTrue(s.loadErrors.isEmpty()); assertEquals(1, s.list().size)
    }

    @Test fun invalidAndUnregisteredRejectedOnPut() {
        val s = store()
        assertThrows(ValidationException::class.java) { s.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("x" to "str"))) }
        assertThrows(ValidationException::class.java) { s.put(Scope.GATEWAY, Resource("Thing", Metadata("a", "m"), mapOf("y" to 1))) }
        assertThrows(UnknownKindException::class.java) { s.put(Scope.GATEWAY, Resource("Other", Metadata("a", "m"))) }
        assertTrue(s.list().isEmpty())
    }

    @Test fun deleteModuleRemovesAllItsResourcesAndDirOnly() {
        registry.register("n", "Thing", mapOf("type" to "object"))
        val s = store(); s.put(Scope.GATEWAY, thing("a", 1)); s.put(Scope.project("p"), thing("b", 1))
        s.put(Scope.GATEWAY, Resource("Thing", Metadata("c", "n")))
        s.deleteModule("m")
        assertEquals(listOf("n"), s.list().map { it.id.module })
        assertFalse(tmp.resolve("config/m").exists()); assertTrue(tmp.resolve("config/n").exists())
    }

    @Test fun fileWhoseContentDisagreesWithPathIsALoadError() {
        val f = tmp.resolve("config/m/gateway/Thing/zz.yaml").createParentDirectories()
        f.writeText(ResourceCodec.encode(thing("a", 1)))
        val s = store(); assertNull(s.get(id)); assertEquals(1, s.loadErrors.size)
    }
}
