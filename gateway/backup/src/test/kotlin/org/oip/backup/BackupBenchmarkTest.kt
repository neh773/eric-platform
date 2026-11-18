package org.oip.backup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.nio.file.Path

/** Times a backup of a modest config tree and runtime table. The ceiling is loose; the printed line is the measurement. */
class BackupBenchmarkTest {
    @TempDir lateinit var tmp: Path

    @Test fun backupCreateStaysUnderCeiling() {
        val registry = SchemaRegistry().apply {
            register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
        }
        val store = FileConfigStore(tmp.resolve("config"), registry)
        val db = RuntimeDatabase(tmp.resolve("db/runtime.db"))
        val resources = 100
        val rows = 1000
        repeat(resources) { i ->
            store.put(Scope.GATEWAY, Resource("Thing", Metadata("n$i", "m"), mapOf("x" to i)))
        }
        val pairs = (0 until rows).map { "k${it.toString().padStart(5, '0')}" to "value-$it" }
        db.replaceAll(pairs)
        val svc = BackupService(tmp, store, db, "bench")
        repeat(3) { svc.create() }
        val t0 = System.nanoTime()
        val archive = svc.create()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("BENCH backup.create ms=$ms resources=$resources rows=$rows bytes=${archive.size}")
        assertTrue(ms < 5_000, "backup.create took ${ms}ms")
        assertTrue(archive.size > 1000)
        db.close(); store.close()
    }
}
