package org.oip.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Tag resources share 256 shard files (ADR 0011). These tests cover migration, a crash before the
 * rename, concurrent put/get, and a tag count whose file count stays in the thousands.
 */
class TagShardStoreTest {
    @TempDir lateinit var tmp: Path

    private val registry = SchemaRegistry().apply {
        register("tags", "Tag", mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer"),
                "path" to mapOf("type" to "string"),
                "type" to mapOf("type" to "string"),
            ),
        ))
        register("m", "Thing", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
    }

    private fun root() = tmp.resolve("config")
    private fun store(hook: (Path) -> Unit = {}) = FileConfigStore(root(), registry, beforeCommit = hook)
    private fun tag(name: String, x: Int, version: Long = 0) =
        Resource("Tag", Metadata(name, "tags", version = version), mapOf("x" to x))
    private fun thing(name: String, x: Int, version: Long = 0) =
        Resource("Thing", Metadata(name, "m", version = version), mapOf("x" to x))
    private fun id(name: String) = ResourceId("tags", Scope.GATEWAY, "Tag", name)

    @Test fun shardIndexIsTheLowByteOfFnv1a() {
        assertEquals(44, TagShards.index("a"))
        assertEquals("s2c.yaml", TagShards.fileName(44))
        assertTrue(TagShards.index("plant-speed") in 0 until TagShards.COUNT)
    }

    @Test fun twoTagsInOneShardRoundTripAndAnUpdateKeepsTheSibling() {
        val (left, right) = twoInOneShard()
        val s = store()
        s.put(Scope.GATEWAY, tag(left, 1))
        s.put(Scope.GATEWAY, tag(right, 2))
        s.put(Scope.GATEWAY, thing("keep", 7))
        s.put(Scope.project("plant"), tag("alpha", 4))
        val projectShard = s.file(ResourceId("tags", Scope.project("plant"), "Tag", "alpha"))
        assertTrue(projectShard.toString().replace('\\', '/').contains("tags/project/plant/Tag/_shards/"))
        assertEquals(4, s.get(ResourceId("tags", Scope.project("plant"), "Tag", "alpha"))!!.spec["x"])
        val shard = s.file(id(left))
        assertEquals(shard, s.file(id(right)))
        assertTrue(shard.readText().contains("name: $left"))
        assertTrue(shard.readText().contains("name: $right"))
        assertTrue(root().resolve("m/gateway/Thing/keep.yaml").exists())
        assertFalse(root().resolve("tags/gateway/Tag/$left.yaml").exists())
        s.put(Scope.GATEWAY, tag(left, 3, version = 1))
        assertEquals(3, s.get(id(left))!!.spec["x"])
        assertEquals(2, s.get(id(right))!!.spec["x"])
        s.close()
        val again = store()
        assertEquals(3, again.get(id(left))!!.spec["x"])
        assertEquals(2, again.get(id(right))!!.spec["x"])
        assertEquals(7, again.get(ResourceId("m", Scope.GATEWAY, "Thing", "keep"))!!.spec["x"])
        again.delete(id(left))
        assertNull(again.get(id(left)))
        assertEquals(2, again.get(id(right))!!.spec["x"])
        again.delete(id(right))
        assertFalse(shard.exists())
        again.close()
    }

    @Test fun migratesLegacyOneFilePerTagAndIsIdempotent() {
        val legacyA = root().resolve("tags/gateway/Tag/alpha.yaml")
        val legacyB = root().resolve("tags/gateway/Tag/beta.yaml")
        legacyA.createParentDirectories().writeText(ResourceCodec.encode(tag("alpha", 4, version = 1)))
        legacyB.writeText(ResourceCodec.encode(tag("beta", 5, version = 1)))
        val thingFile = root().resolve("m/gateway/Thing/keep.yaml")
        thingFile.createParentDirectories().writeText(ResourceCodec.encode(thing("keep", 8)))

        val first = store()
        assertEquals(4, first.get(id("alpha"))!!.spec["x"])
        assertEquals(1L, first.get(id("alpha"))!!.metadata.version)
        assertEquals(5, first.get(id("beta"))!!.spec["x"])
        assertEquals(8, first.get(ResourceId("m", Scope.GATEWAY, "Thing", "keep"))!!.spec["x"])
        assertFalse(legacyA.exists())
        assertFalse(legacyB.exists())
        assertTrue(thingFile.exists())
        val alphaShard = first.file(id("alpha"))
        val betaShard = first.file(id("beta"))
        assertTrue(alphaShard.readText().contains("name: alpha"))
        assertTrue(betaShard.readText().contains("name: beta"))
        assertTrue(first.loadErrors.isEmpty(), first.loadErrors.toString())
        val snapshot = shardBytes()
        first.close()

        val second = store()
        assertEquals(snapshot, shardBytes())
        assertEquals(4, second.get(id("alpha"))!!.spec["x"])
        assertEquals(5, second.get(id("beta"))!!.spec["x"])
        assertEquals(1L, second.get(id("alpha"))!!.metadata.version)
        assertEquals(listOf("alpha", "beta"), second.list(module = "tags", kind = "Tag").map { it.id.name }.sorted())
        assertFalse(legacyA.exists())
        second.close()
    }

    @Test fun legacyMigrationIsIdempotentWhenAShardCopyAlreadyExists() {
        val shard = root().resolve("tags/gateway/Tag/_shards/${TagShards.fileName(TagShards.index("alpha"))}")
        val body = ResourceCodec.encode(tag("alpha", 1).let {
            it.copy(metadata = it.metadata.copy(version = 2))
        })
        shard.createParentDirectories().writeText("# oip-tag-shard: fnv1a-256 ${shard.getFileName().toString().removeSuffix(".yaml")}\n$body")
        val legacy = root().resolve("tags/gateway/Tag/alpha.yaml")
        legacy.writeText(ResourceCodec.encode(tag("alpha", 9).let { it.copy(metadata = it.metadata.copy(version = 1)) }))
        val s = store()
        assertEquals(1, s.get(id("alpha"))!!.spec["x"])
        assertEquals(2L, s.get(id("alpha"))!!.metadata.version)
        assertFalse(legacy.exists())
        s.close()
        val again = store()
        assertEquals(1, again.get(id("alpha"))!!.spec["x"])
        assertFalse(legacy.exists())
        again.close()
    }

    @Test fun crashDuringMigrationLeavesLegacyFilesIntact() {
        val legacy = root().resolve("tags/gateway/Tag/alpha.yaml")
        legacy.createParentDirectories().writeText(ResourceCodec.encode(tag("alpha", 4)))
        val before = legacy.readText()
        assertThrows<RuntimeException> {
            FileConfigStore(root(), registry, beforeCommit = { throw RuntimeException("simulated crash before rename") })
        }
        assertEquals(before, legacy.readText())
        assertTrue(shardYamlFiles().isEmpty(), shardYamlFiles().map { it.getFileName().toString() }.toString())
        val s = store()
        assertEquals(4, s.get(id("alpha"))!!.spec["x"])
        assertFalse(legacy.exists())
        assertTrue(shardYamlFiles().isNotEmpty())
        s.close()
    }

    @Test fun crashDuringWriteLeavesThePreviousShardIntact() {
        val s = store()
        s.put(Scope.GATEWAY, tag("alpha", 1))
        val shard = s.file(id("alpha"))
        val before = shard.readText()
        val crashing = store { throw RuntimeException("simulated crash before rename") }
        assertThrows<RuntimeException> { crashing.put(Scope.GATEWAY, tag("alpha", 2, version = 1)) }
        assertEquals(before, shard.readText())
        assertEquals(1, crashing.get(id("alpha"))!!.spec["x"])
        assertTrue(temps().isEmpty(), temps().toString())
        assertEquals(listOf(tagDocument("alpha", 1)), documents(shard))
        crashing.close()
        s.close()
        val reopened = store()
        assertEquals(1, reopened.get(id("alpha"))!!.spec["x"])
        assertEquals(before, shard.readText())
        reopened.close()
    }

    @Test fun crashDuringBatchLeavesEveryShardUntouched() {
        val (left, right) = twoInOneShard()
        val s = store()
        s.put(Scope.GATEWAY, tag(left, 1))
        val before = shardBytes()
        val crashing = store { throw RuntimeException("simulated crash before rename") }
        assertThrows<RuntimeException> {
            crashing.putAll(
                Scope.GATEWAY,
                listOf(tag(left, 8, version = 1), tag(right, 9), tag("other", 3)),
                syncEach = false,
            )
        }
        assertEquals(before, shardBytes())
        assertEquals(1, crashing.get(id(left))!!.spec["x"])
        assertNull(crashing.get(id(right)))
        assertNull(crashing.get(id("other")))
        assertTrue(temps().isEmpty(), temps().toString())
        crashing.close()
        s.close()
        val reopened = store()
        assertEquals(1, reopened.get(id(left))!!.spec["x"])
        assertNull(reopened.get(id(right)))
        reopened.close()
    }

    @Test fun leftoverShardTempFromAHardCrashIsIgnored() {
        val s = store()
        s.put(Scope.GATEWAY, tag("alpha", 1))
        val shard = s.file(id("alpha"))
        shard.resolveSibling(".${shard.getFileName()}.99.tmp").writeText("apiVersion: oip/v1\nkind: Ta")
        s.close()
        val again = store()
        assertEquals(1, again.get(id("alpha"))!!.spec["x"])
        assertTrue(again.loadErrors.isEmpty())
        again.close()
    }

    @Test fun externalEditOfOneDocumentBumpsOnceAndAnInvalidShardDoesNotApply() {
        val (left, right) = twoInOneShard()
        val s = store()
        s.put(Scope.GATEWAY, tag(left, 1))
        s.put(Scope.GATEWAY, tag(right, 2))
        val shard = s.file(id(left))
        val updated = shard.readText().replace("x: 1\n", "x: 9\n")
        assertTrue(updated != shard.readText())
        shard.writeText(updated)
        touch(shard)
        s.rescan()
        assertEquals(9, s.get(id(left))!!.spec["x"])
        assertEquals(2L, s.get(id(left))!!.metadata.version)
        assertEquals("file", s.get(id(left))!!.metadata.modifiedBy)
        assertEquals(2, s.get(id(right))!!.spec["x"])
        assertEquals(1L, s.get(id(right))!!.metadata.version)
        s.rescan()
        assertEquals(2L, s.get(id(left))!!.metadata.version)
        val good = shard.readText()
        shard.writeText("this is not a tag document: [\n")
        touch(shard)
        s.rescan()
        assertEquals(9, s.get(id(left))!!.spec["x"])
        assertEquals(2, s.get(id(right))!!.spec["x"])
        assertEquals("this is not a tag document: [\n", shard.readText())
        assertTrue(s.loadErrors.isNotEmpty())
        shard.writeText(good)
        touch(shard)
        s.rescan()
        assertEquals(9, s.get(id(left))!!.spec["x"])
        s.close()
    }

    @Test fun aBadDocumentDoesNotDropItsSiblingOnStartup() {
        val (left, right) = twoInOneShard()
        val s = store()
        s.put(Scope.GATEWAY, tag(left, 1))
        s.put(Scope.GATEWAY, tag(right, 2))
        val shard = s.file(id(left))
        shard.writeText(shard.readText() + "---\n" + ResourceCodec.encode(tag("zzzz", 1)).replace("x: 1\n", "x: \"bad\"\n"))
        s.close()
        val again = store()
        assertEquals(1, again.get(id(left))!!.spec["x"])
        assertEquals(2, again.get(id(right))!!.spec["x"])
        assertNull(again.get(id("zzzz")))
        assertTrue(again.loadErrors.isNotEmpty(), again.loadErrors.toString())
        again.close()
    }

    @Test fun concurrentPutAndGetDoNotLoseOrTearATag() {
        val s = store()
        s.put(Scope.GATEWAY, thing("keep", 1))
        val threads = 8
        val perThread = 20
        val start = CountDownLatch(1)
        val failures = AtomicReference<Throwable>(null)
        val stop = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(threads + 2)
        fun fail(t: Throwable) { failures.compareAndSet(null, t) }
        val readers = (0 until 2).map {
            pool.submit {
                try {
                    start.await()
                    while (!stop.get()) {
                        val got = s.get(ResourceId("m", Scope.GATEWAY, "Thing", "keep"))
                        if (got?.spec?.get("x") != 1) fail(AssertionError("thing changed: $got"))
                        s.list(module = "m", scope = Scope.GATEWAY, kind = "Thing")
                    }
                } catch (t: Throwable) {
                    fail(t)
                }
            }
        }
        val writers = (0 until threads * perThread).map { n ->
            pool.submit {
                try {
                    start.await()
                    val name = "c%03d".format(n)
                    s.put(Scope.GATEWAY, tag(name, n))
                    val back = s.get(id(name))
                    if (back?.spec?.get("x") != n) fail(AssertionError("torn read of $name: $back"))
                } catch (t: Throwable) {
                    fail(t)
                }
            }
        }
        start.countDown()
        writers.forEach { it.get(60, TimeUnit.SECONDS) }
        stop.set(true)
        readers.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        failures.get()?.let { throw it }
        for (n in 0 until threads * perThread) {
            assertEquals(n, s.get(id("c%03d".format(n)))!!.spec["x"])
        }
        s.close()
        val again = store()
        for (n in 0 until threads * perThread) {
            assertEquals(n, again.get(id("c%03d".format(n)))!!.spec["x"])
        }
        assertEquals(1, again.get(ResourceId("m", Scope.GATEWAY, "Thing", "keep"))!!.spec["x"])
        again.close()
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun twoHundredThousandTagsStayWithinAFewThousandFiles() {
        val count = 200_000
        val s = store()
        val batch = ArrayList<Resource>(count)
        for (i in 0 until count) {
            batch += tag("t%06d".format(i), i)
        }
        val started = System.nanoTime()
        val result = s.putAll(Scope.GATEWAY, batch, actor = "scale", syncEach = false)
        val seconds = (System.nanoTime() - started) / 1e9
        batch.clear()
        assertEquals(count, result.created)
        val census = census(root())
        val filesPerTag = census.files.toDouble() / count
        println(
            "TAG_SHARD_SCALE tags=$count files=${census.files} shardFiles=${census.shards} " +
                "dirs=${census.dirs} filesPerTag=${"%.6f".format(filesPerTag)} " +
                "shardFilesPerTag=${"%.6f".format(census.shards.toDouble() / count)} " +
                "putAllSec=${"%.3f".format(seconds)}",
        )
        assertTrue(census.shards in 1..TagShards.COUNT, "shard files ${census.shards}")
        assertTrue(census.files <= 4_096, "files=${census.files} for $count tags (${"%.6f".format(filesPerTag)} files/tag)")
        assertTrue(census.dirs <= 4_096, "directories=${census.dirs}")
        assertEquals(0, census.looseTagFiles, "legacy one-file-per-tag yaml files=${census.looseTagFiles}")
        assertEquals(0, s.get(id("t000000"))!!.spec["x"])
        assertEquals(count / 2, s.get(id("t%06d".format(count / 2)))!!.spec["x"])
        assertEquals(count - 1, s.get(id("t%06d".format(count - 1)))!!.spec["x"])
        s.put(Scope.GATEWAY, thing("keep", 1))
        assertTrue(root().resolve("m/gateway/Thing/keep.yaml").exists())
        s.close()
        val again = store()
        assertEquals(count - 1, again.get(id("t%06d".format(count - 1)))!!.spec["x"])
        assertEquals(census.shards, census(root()).shards)
        again.close()
    }

    @Test fun laterBatchAppendsTheNewDocumentAndReopenFoldsIt() {
        val (left, right) = twoInOneShard()
        val s = store()
        s.putAll(Scope.GATEWAY, listOf(tag(left, 1)), actor = "scale", syncEach = false)
        val shard = s.file(id(left))
        val before = shard.readText()
        val reads = s.journalReads
        val written = s.tagFileBytesWritten
        s.putAll(Scope.GATEWAY, listOf(tag(right, 2), tag(left, 3, version = 1)), actor = "scale", syncEach = false)
        assertEquals(before, shard.readText(), "the sorted shard was rewritten for a small batch")
        val segment = shard.resolveSibling(TagShards.segmentFileName(TagShards.index(left)))
        assertTrue(segment.exists(), "new documents were not appended")
        assertTrue(segment.readText().contains("name: $right"))
        assertTrue(s.journalReads - reads <= 2, "append re-read the shard from the journal: ${s.journalReads - reads}")
        assertTrue(s.tagFileBytesWritten - written < before.length * 4, "append wrote the whole shard again")
        assertEquals(3, s.get(id(left))!!.spec["x"])
        assertEquals(2, s.get(id(right))!!.spec["x"])
        s.close()
        val again = store()
        assertEquals(3, again.get(id(left))!!.spec["x"])
        assertEquals(2L, again.get(id(left))!!.metadata.version)
        assertEquals(2, again.get(id(right))!!.spec["x"])
        assertFalse(segment.exists(), "segment was not folded")
        assertTrue(shard.readText().contains("name: $right"))
        assertTrue(again.loadErrors.isEmpty(), again.loadErrors.toString())
        val folded = shardBytes()
        again.close()
        val third = store()
        assertEquals(folded, shardBytes(), "folding ran again on a clean tree")
        third.close()
    }

    @Test fun crashMidSegmentFlushKeepsTheTornAppendAndItsSiblings() {
        val names = namesInOneShard(3)
        val (first, second, third) = names
        val s = store()
        s.putAll(Scope.GATEWAY, listOf(tag(first, 1)), syncEach = false)
        s.putAll(Scope.GATEWAY, listOf(tag(second, 2)), syncEach = false)
        val index = TagShards.index(first)
        val segment = s.file(id(first)).resolveSibling(TagShards.segmentFileName(index))
        assertTrue(segment.exists(), "second tag was not appended")
        s.close()

        val oldSize = Files.size(segment)
        val payload = TagShards.encodeSegment(
            index,
            listOf(ResourceCodec.encodeYamlBytes(tag(third, 7))),
            header = false,
        )
        Files.write(segment, payload.copyOf(payload.size / 2), java.nio.file.StandardOpenOption.APPEND)
        val pending = segment.resolveSibling(TagShards.pendingFileName(index))
        pending.writeBytes(TagShards.encodePending(oldSize, payload))

        val again = store()
        assertEquals(1, again.get(id(first))!!.spec["x"])
        assertEquals(2, again.get(id(second))!!.spec["x"])
        assertEquals(7, again.get(id(third))!!.spec["x"])
        assertFalse(pending.exists(), "pending record was left behind")
        assertFalse(segment.exists(), "torn segment was not folded")
        assertTrue(again.file(id(first)).readText().contains("name: $third"))
        assertTrue(again.loadErrors.isEmpty(), again.loadErrors.toString())
        again.close()
    }

    @Test fun tagBatchFileIoDoesNotBlockReads() {
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s = FileConfigStore(root(), registry, beforeCommit = { path ->
            if (!block.get()) return@FileConfigStore
            if (path.parent?.fileName?.toString() != TagShards.DIR) return@FileConfigStore
            entered.countDown()
            if (!release.await(30, TimeUnit.SECONDS)) error("tag batch hook was not released")
        })
        val failed = AtomicReference<Throwable>(null)
        try {
            s.put(Scope.GATEWAY, tag("alpha", 1))
            block.set(true)
            val writer = Thread {
                try {
                    s.putAll(Scope.GATEWAY, (0 until 64).map { tag("t%03d".format(it), it) }, syncEach = false)
                } catch (t: Throwable) {
                    failed.set(t)
                    entered.countDown()
                }
            }
            writer.start()
            assertTrue(entered.await(30, TimeUnit.SECONDS), "tag batch did not reach the commit hook")
            val started = System.nanoTime()
            val got = s.get(id("alpha"))
            val ms = (System.nanoTime() - started) / 1_000_000
            println("TAG_BATCH read during shard io ${ms} ms")
            assertEquals(1, got!!.spec["x"])
            assertTrue(ms < 500, "get waited ${ms} ms on tag batch file io")
            release.countDown()
            writer.join(30_000)
            assertFalse(writer.isAlive)
            failed.get()?.let { throw it }
            assertEquals(0, s.get(id("t000"))!!.spec["x"])
        } finally {
            release.countDown()
            s.close()
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun batchedTagCreateStaysLinear() {
        val smallN = 20_000
        val largeN = 200_000
        val small = measureBatches(smallN)
        val large = measureBatches(largeN)
        val readsPerSmall = small.reads / smallN.toDouble()
        val readsPerLarge = large.reads / largeN.toDouble()
        val bytesPerSmall = small.bytes / smallN.toDouble()
        val bytesPerLarge = large.bytes / largeN.toDouble()
        val readRatio = if (readsPerSmall == 0.0) readsPerLarge else readsPerLarge / readsPerSmall
        val byteRatio = bytesPerLarge / bytesPerSmall
        println(
            "TAG_BATCH_SCALE n=$smallN reads=${small.reads} bytes=${small.bytes} " +
                "readsPerTag=${"%.4f".format(readsPerSmall)} bytesPerTag=${"%.2f".format(bytesPerSmall)}",
        )
        println(
            "TAG_BATCH_SCALE n=$largeN reads=${large.reads} bytes=${large.bytes} " +
                "readsPerTag=${"%.4f".format(readsPerLarge)} bytesPerTag=${"%.2f".format(bytesPerLarge)}",
        )
        println("TAG_BATCH_SCALE readRatio=${"%.3f".format(readRatio)} byteRatio=${"%.3f".format(byteRatio)}")
        assertTrue(bytesPerSmall > 32.0, "20k create wrote ${small.bytes} shard bytes")
        assertTrue(byteRatio <= 2.0, "bytes/tag $bytesPerLarge vs $bytesPerSmall (ratio $byteRatio)")
        val readLimit = if (readsPerSmall == 0.0) 1.0 else readsPerSmall * 2.0
        assertTrue(readsPerLarge <= readLimit, "journal reads/tag $readsPerLarge vs $readsPerSmall")
    }

    private fun measureBatches(count: Int): BatchCost {
        val dir = tmp.resolve("batches-$count")
        val s = FileConfigStore(dir, registry)
        try {
            val batch = 2_000
            var at = 0
            while (at < count) {
                val end = minOf(count, at + batch)
                val resources = ArrayList<Resource>(end - at)
                for (i in at until end) resources += tag("t%06d".format(i), i)
                s.putAll(Scope.GATEWAY, resources, actor = "scale", syncEach = false)
                at = end
            }
            val cost = BatchCost(s.journalReads, s.tagFileBytesWritten)
            assertEquals(0, s.get(ResourceId("tags", Scope.GATEWAY, "Tag", "t000000"))!!.spec["x"])
            assertEquals(count - 1, s.get(ResourceId("tags", Scope.GATEWAY, "Tag", "t%06d".format(count - 1)))!!.spec["x"])
            val during = census(dir)
            assertTrue(during.files <= 4_096, "files during import ${during.files}")
            assertTrue(during.shards <= TagShards.COUNT * 2, "shard yaml files during import ${during.shards}")
            assertEquals(0, during.looseTagFiles)
            s.close()
            val again = FileConfigStore(dir, registry)
            try {
                assertEquals(count - 1, again.get(ResourceId("tags", Scope.GATEWAY, "Tag", "t%06d".format(count - 1)))!!.spec["x"])
                assertEquals(0, again.get(ResourceId("tags", Scope.GATEWAY, "Tag", "t000000"))!!.spec["x"])
                val folded = census(dir)
                assertTrue(folded.shards in 1..TagShards.COUNT, "folded shard files ${folded.shards}")
                assertTrue(folded.files <= 4_096, "files after fold ${folded.files}")
                assertEquals(0, folded.looseTagFiles)
            } finally {
                again.close()
            }
            return cost
        } finally {
            runCatching { s.close() }
        }
    }

    private fun twoInOneShard(): Pair<String, String> {
        val names = namesInOneShard(2)
        return names[0] to names[1]
    }

    private fun namesInOneShard(count: Int): List<String> {
        val found = ArrayList<String>()
        var index = -1
        for (i in 0 until 5_000) {
            val name = "n%04d".format(i)
            val at = TagShards.index(name)
            if (index < 0 || at == index) {
                index = at
                found += name
                if (found.size == count) return found
            }
        }
        error("expected $count names in one shard")
    }

    private data class BatchCost(val reads: Long, val bytes: Long)

    private fun shardYamlFiles(): List<Path> {
        if (!root().exists()) return emptyList()
        return Files.walk(root()).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.parent?.getFileName()?.toString() == TagShards.DIR && it.getFileName().toString().endsWith(".yaml") }
                .sorted()
                .toList()
        }
    }

    private fun shardBytes(): Map<String, String> =
        shardYamlFiles().associate { root().relativize(it).toString().replace('\\', '/') to it.readText() }

    private fun temps(): List<Path> {
        if (!root().exists()) return emptyList()
        return Files.walk(root()).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.getFileName().toString().startsWith(".") && it.getFileName().toString() != ".good-journal" }
                .toList()
        }
    }

    private fun documents(shard: Path): List<String> =
        ResourceCodec.decodeDocuments(shard.readText()).map { it.resource?.metadata?.name + "=" + it.resource?.spec?.get("x") }

    private fun tagDocument(name: String, x: Int) = "$name=$x"

    /** Move the mtime off the mark [FileConfigStore] recorded, even when the edit keeps the same length. */
    private fun touch(path: Path) {
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))
    }

    private data class Census(val files: Int, val dirs: Int, val shards: Int, val looseTagFiles: Int)

    private fun census(root: Path): Census {
        var files = 0
        var dirs = 0
        var shards = 0
        var loose = 0
        Files.walk(root).use { stream ->
            stream.forEach { path ->
                if (path == root) return@forEach
                if (Files.isDirectory(path)) {
                    dirs++
                } else if (Files.isRegularFile(path)) {
                    files++
                    val parent = path.parent?.getFileName()?.toString()
                    if (parent == TagShards.DIR && path.getFileName().toString().endsWith(".yaml")) shards++
                    if (parent == "Tag" && path.getFileName().toString().endsWith(".yaml")) loose++
                }
            }
        }
        return Census(files, dirs, shards, loose)
    }
}
