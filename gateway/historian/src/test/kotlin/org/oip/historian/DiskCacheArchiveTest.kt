package org.oip.historian

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.tags.Quality
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * A disk-cache archive is durable before any pending row is removed, and a load keeps (sourceId, seq)
 * so a crash between the rename and the delete cannot store a sample twice.
 */
class DiskCacheArchiveTest {
    @TempDir lateinit var tmp: Path

    private val t0 = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = FakeClock(t0)
    private val tag = "[default]Plant/Flow"

    private fun sample(i: Int) = HistorySample(tag, t0.toEpochMilli() + i, i.toDouble(), Quality.GOOD)

    private fun router(path: Path = tmp.resolve("db/history-forward.sqlite")) =
        HistoryRouter(MemoryHistorySink(), ForwardBuffer(path), clock)

    @Test
    fun aFailureDuringTheWriteLeavesTheRowsAndListsNoArchive() {
        router().use { r ->
            prepare(r, 3)
            val archives = DiskCacheArchives(tmp.resolve("archives"), r.buffer, clock)
            assertThrows(IllegalStateException::class.java) {
                archives.archive("pg", pageSize = 2, hooks = DiskCacheArchiveHooks(duringWrite = { throw IllegalStateException("disk") }))
            }
            assertEquals(3, r.buffer.pendingCount("pg"))
            assertTrue(archives.list().isEmpty())
            assertTrue(Files.list(tmp.resolve("archives")).use { stream -> stream.noneMatch { it.fileName.toString().endsWith(".partial") } })
        }
    }

    @Test
    fun aFailureAfterTheRenameAndBeforeTheDeleteKeepsBothCopies() {
        router().use { r ->
            prepare(r, 3)
            val archives = DiskCacheArchives(tmp.resolve("archives"), r.buffer, clock)
            assertThrows(IllegalStateException::class.java) {
                archives.archive("pg", hooks = DiskCacheArchiveHooks(beforeDelete = { throw IllegalStateException("crash") }))
            }
            assertEquals(3, r.buffer.pendingCount("pg"))
            val listed = archives.list()
            assertEquals(1, listed.size)
            assertEquals(3, listed.single().records)
        }
    }

    @Test
    fun anArchiveThatDoesNotReadBackKeepsEveryRowAndIsRemoved() {
        router().use { r ->
            prepare(r, 3)
            val dir = tmp.resolve("archives")
            val archives = DiskCacheArchives(dir, r.buffer, clock)
            var pages = 0
            // The second page is the last. Drop its line from the file as a disk that loses a write would.
            val lose = {
                pages++
                if (pages == 2) {
                    val partial = Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(".partial") }.findFirst().get() }
                    val text = Files.readString(partial)
                    val cut = text.trimEnd('\n').lastIndexOf('\n') + 1
                    FileChannel.open(partial, StandardOpenOption.WRITE).use { it.truncate(cut.toLong()) }
                }
            }
            assertThrows(IllegalStateException::class.java) {
                archives.archive("pg", pageSize = 2, hooks = DiskCacheArchiveHooks(duringWrite = lose))
            }
            assertEquals(3, r.buffer.pendingCount("pg"), "no row leaves the cache until the archive reads back whole")
            assertEquals((1..3).map { it.toDouble() }, r.buffer.pendingPage("pg", 0, 10).map { it.sample.value })
            assertTrue(archives.list().isEmpty(), "a short archive is not listed")
        }
    }

    @Test
    fun aRowAddedWhileTheArchiveIsWrittenStaysPending() {
        router().use { r ->
            prepare(r, 3)
            val archives = DiskCacheArchives(tmp.resolve("archives"), r.buffer, clock)
            var added = false
            val info = archives.archive("pg", pageSize = 3, hooks = DiskCacheArchiveHooks(duringWrite = {
                if (!added) {
                    added = true
                    r.appendAll(listOf(sample(4)))
                }
            }))
            assertEquals(3, info.records)
            assertEquals(listOf(4.0), r.buffer.pendingPage("pg", 0, 10).map { it.sample.value }, "only archived rows are deleted")
            assertEquals(3, archives.load(info.name, providerExists = { it == "pg" }))
            assertEquals((1..4).map { it.toDouble() }, r.buffer.pendingPage("pg", 0, 10).map { it.sample.value })
        }
    }

    @Test
    fun aCrashBetweenRenameAndDeleteThenForwardAndLoadStoresEachSampleOnce() {
        val path = tmp.resolve("db/history-forward.sqlite")
        val dir = tmp.resolve("archives")
        val first = router(path)
        val name = try {
            val pg = FakeRemoteProvider("pg").also { it.down = true; first.register(it) }
            first.route(tag, "pg")
            first.appendAll((1..3).map { sample(it) })
            val archives = DiskCacheArchives(dir, first.buffer, clock)
            assertThrows(IllegalStateException::class.java) {
                archives.archive("pg", hooks = DiskCacheArchiveHooks(beforeDelete = { throw IllegalStateException("crash") }))
            }
            assertEquals(3, first.buffer.pendingCount("pg"))
            archives.list().single().name
        } finally {
            first.close()
        }
        router(path).use { restored ->
            val pg = FakeRemoteProvider("pg").also { restored.register(it) }
            assertEquals(3, restored.forward())
            assertEquals(listOf(1.0, 2.0, 3.0), pg.rows().map { it.value })
            val archives = DiskCacheArchives(dir, restored.buffer, clock)
            assertEquals(3, archives.load(name, pageSize = 2, providerExists = { it == "pg" }, noteStored = restored::noteStored))
            assertEquals(3, restored.forward())
            assertEquals(listOf(1.0, 2.0, 3.0), pg.rows().map { it.value }, "the provider already stored this sourceId and seq")
            assertEquals(0, restored.buffer.pendingCount("pg"))
        }
    }

    @Test
    fun aCrashMidLoadResumesWithoutSkippingOrDoubling() {
        router().use { r ->
            prepare(r, 5)
            val archives = DiskCacheArchives(tmp.resolve("archives"), r.buffer, clock)
            val name = archives.archive("pg", pageSize = 2).name
            assertEquals(0, r.buffer.pendingCount("pg"))
            var crash = true
            assertThrows(IllegalStateException::class.java) {
                archives.load(name, pageSize = 2, providerExists = { true }, hooks = DiskCacheLoadHooks(
                    beforeProgress = { if (crash) throw IllegalStateException("crash") },
                ))
            }
            assertEquals(2, r.buffer.pendingCount("pg"))
            crash = false
            archives.load(name, pageSize = 2, providerExists = { true })
            assertEquals(5, r.buffer.pendingCount("pg"))
            assertEquals((1..5).map { it.toDouble() }, r.buffer.pendingPage("pg", 0, 10).map { it.sample.value })
        }
    }

    @Test
    fun loadAndImportRefuseAProviderThatIsNotRegisteredAndWriteNothing() {
        router().use { r ->
            val pg = FakeRemoteProvider("pg").also { r.register(it) }
            r.route(tag, "pg")
            val missing = DiskCacheRecord("missing", sample(1), "src", 4)
            val known = DiskCacheRecord("pg", sample(2), "src", 5)
            assertThrows(UnknownHistoryProvider::class.java) { r.loadDiskCache(listOf(known, missing)) }
            assertEquals(0, r.buffer.pendingCount("pg"))
            assertThrows(UnknownHistoryProvider::class.java) {
                r.importQuarantine(listOf(DiskCacheRecord("pg", sample(3)), DiskCacheRecord("missing", sample(4))))
            }
            assertEquals(0, pg.rows().size)
            assertEquals(0, r.buffer.pendingCount("pg"))
            assertEquals(0, r.buffer.quarantined().size)
        }
    }

    @Test
    fun moreThanOneHundredThousandRowsArchiveAndLoad() {
        val n = 100_001
        router().use { r ->
            prepare(r, 0)
            val batch = ArrayList<HistorySample>(n)
            for (i in 1..n) batch += sample(i)
            r.appendAll(batch)
            val archives = DiskCacheArchives(tmp.resolve("archives"), r.buffer, clock)
            val info = archives.archive("pg")
            assertEquals(n, info.records)
            assertTrue(info.records > 100_000)
            assertEquals(0, r.buffer.pendingCount("pg"))
            assertEquals(n, archives.load(info.name, providerExists = { true }))
            assertEquals(n, r.buffer.pendingCount("pg"))
            assertEquals(1.0, r.buffer.head("pg", 1).single().sample.value)
            assertEquals(n.toDouble(), r.buffer.pendingPage("pg", 0, n).last().sample.value)
        }
    }

    private fun prepare(r: HistoryRouter, count: Int) {
        FakeRemoteProvider("pg").also { it.down = true; r.register(it) }
        r.route(tag, "pg")
        if (count > 0) r.appendAll((1..count).map { sample(it) })
    }
}
