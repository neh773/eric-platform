package org.oip.gateway

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * `<data dir>/logs/startup.log`: one line per startup stage, and the full error when a stage fails, so a gateway that
 * never opens its web port still says why (on Windows: C:\ProgramData\ERIC\gateway\logs\startup.log).
 */
object StartupLog {
    @Volatile private var file: Path? = null
    @Volatile var lastStage: String = "not started"
        private set

    fun open(dataDir: Path) {
        val target = dataDir.resolve("logs").resolve("startup.log")
        runCatching {
            Files.createDirectories(target.parent)
            // Keep the previous start for comparison; one rotation is enough for a support request.
            if (Files.exists(target)) Files.move(target, target.resolveSibling("startup.previous.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        file = target
        write("ERIC gateway ${Gateway.VERSION} starting, data directory ${dataDir.toAbsolutePath()}, java ${System.getProperty("java.version")}, ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    }

    fun path(): Path? = file

    fun stage(name: String) {
        lastStage = name
        write("stage: $name")
    }

    fun write(line: String) {
        val target = file ?: return
        runCatching {
            Files.writeString(target, "${Instant.now()} $line${System.lineSeparator()}", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    fun failure(what: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        write("FAILED $what: ${error.javaClass.name}: ${error.message}\n$trace")
    }

    fun threadDump(reason: String) {
        val dump = StringBuilder("$reason. Threads:\n")
        Thread.getAllStackTraces().forEach { (thread, frames) ->
            dump.append("\"${thread.name}\" ${thread.state}\n")
            frames.take(25).forEach { dump.append("    at $it\n") }
        }
        write(dump.toString())
    }
}

/**
 * Runs an optional part of startup. A failure there (including a missing native library) is logged and recorded in
 * [problems] instead of stopping the gateway, so the web pages still open and show what is wrong.
 */
internal inline fun startOptional(name: String, problems: MutableList<String>, warn: (String) -> Unit, block: () -> Unit) {
    StartupLog.stage(name)
    try {
        block()
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        StartupLog.failure(name, e)
        val text = "$name did not start: ${e.message ?: e.javaClass.simpleName}"
        problems += text
        warn(text)
    }
}
