package org.oip.tags

import java.nio.file.Path

/**
 * Child process for [MemoryTagPersistenceTest.aCrashLosesAtMostTheFlushWindowAndNeverCorruptsTheDb].
 * Writes an increasing counter to memory tags as fast as it can, with the background flusher running.
 * Every 200 writes it flushes on its own thread and prints `durable <n>` once that commit returns, so
 * the parent knows a lower bound on what must survive. The parent kills it with SIGKILL mid-stream.
 */
object MemoryCrashChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val engine = TagEngine()
        repeat(TAGS) { engine.configure(TagConfig(path = "Crash/T$it", type = TagType.MEMORY, dataType = DataType.INT64)) }
        val persistence = MemoryTagPersistence.open(engine, Path.of(args[0]), flushMillis = 20)
        val samples = (0 until TAGS).map { engine.openSample("Crash/T$it") }
        println("ready")
        System.out.flush()
        var n = 0L
        while (true) {
            n++
            for (s in samples) s.set(n)
            if (n % 200 == 0L) {
                persistence.flush()
                println("durable $n")
                System.out.flush()
            }
        }
    }

    const val TAGS = 50
}
