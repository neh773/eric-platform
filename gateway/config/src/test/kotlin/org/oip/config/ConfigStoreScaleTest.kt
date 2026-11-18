package org.oip.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Batch create has to finish inside the bench client's 60s HTTP budget, and a kind-scoped list
 * (the auth/zone path) must not wait on that write or walk every tag.
 */
class ConfigStoreScaleTest {
    private val registry = SchemaRegistry().apply {
        register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
        register("m", "Zone", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun hundredThousandBatchCreateStaysInsideTheHttpBudget() {
        val root = Files.createTempDirectory("oip-batch-scale")
        val store = FileConfigStore(root.resolve("config"), registry)
        try {
            store.put(Scope.GATEWAY, Resource("Zone", Metadata("z", "m"), mapOf("x" to 1)))
            val count = 100_000
            val batch = ArrayList<Resource>(count)
            for (i in 0 until count) {
                batch += Resource("Thing", Metadata("n%06d".format(i), "m"), mapOf("x" to i))
            }
            val started = System.nanoTime()
            val result = store.putAll(Scope.GATEWAY, batch, actor = "scale", syncEach = false)
            val seconds = (System.nanoTime() - started) / 1e9
            println("SCALE putAll $count in ${"%.3f".format(seconds)} s")
            assertEquals(count, result.created)
            assertEquals(count - 1, store.get(ResourceId("m", Scope.GATEWAY, "Thing", "n%06d".format(count - 1)))!!.spec["x"])
            val listMs = kindListMillis(store)
            println("SCALE zone list after $count tags ${"%.4f".format(listMs)} ms")
            assertTrue(seconds < 20.0, "putAll of $count took $seconds s")
            assertTrue(listMs < 2.0, "zone list took $listMs ms after $count tags")
            val yaml = root.resolve("config/m/gateway/Thing/n000000.yaml")
            assertTrue(Files.exists(yaml))
            assertTrue(Files.readString(yaml).contains("kind: Thing"))
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun kindListDoesNotWaitOnBatchFileIo() {
        val root = Files.createTempDirectory("oip-batch-lock")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = FileConfigStore(root.resolve("config"), registry, beforeCommit = fun(path: Path) {
            // Single puts (the zone that the list must see) also run the hook. Only the batch of
            // Thing files is the write the list must not wait on.
            if ("/Thing/" !in path.toString().replace('\\', '/')) return
            entered.countDown()
            if (!release.await(30, TimeUnit.SECONDS)) error("batch hook was not released")
        })
        val failed = AtomicReference<Throwable>()
        try {
            store.put(Scope.GATEWAY, Resource("Zone", Metadata("z", "m"), mapOf("x" to 1)))
            val batch = (0 until 64).map { Resource("Thing", Metadata("n%06d".format(it), "m"), mapOf("x" to it)) }
            val writer = Thread {
                try {
                    store.putAll(Scope.GATEWAY, batch, syncEach = false)
                } catch (t: Throwable) {
                    failed.set(t)
                    entered.countDown()
                }
            }
            writer.start()
            assertTrue(entered.await(30, TimeUnit.SECONDS), "batch did not reach the commit hook")
            val started = System.nanoTime()
            val zones = store.list(module = "m", scope = Scope.GATEWAY, kind = "Zone")
            val ms = (System.nanoTime() - started) / 1_000_000
            println("SCALE zone list during batch io ${ms} ms")
            assertEquals(listOf("z"), zones.map { it.id.name })
            assertTrue(ms < 500, "kind list waited ${ms} ms on batch file io")
            release.countDown()
            writer.join(30_000)
            assertTrue(!writer.isAlive)
            failed.get()?.let { throw it }
            assertEquals(64, store.list(module = "m", scope = Scope.GATEWAY, kind = "Thing").size)
            assertNull(store.get(ResourceId("m", Scope.GATEWAY, "Thing", "missing")))
        } finally {
            release.countDown()
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun kindListMillis(store: FileConfigStore): Double {
        val n = 50
        repeat(5) { store.list(module = "m", scope = Scope.GATEWAY, kind = "Zone") }
        val started = System.nanoTime()
        repeat(n) {
            val items = store.list(module = "m", scope = Scope.GATEWAY, kind = "Zone")
            check(items.size == 1)
        }
        return (System.nanoTime() - started) / 1_000_000.0 / n
    }
}
