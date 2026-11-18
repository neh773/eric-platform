package org.oip.gateway

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Exclusive ownership of a data directory, plus the pid and URL files `oipctl` uses to stop
 * and find a gateway started on this machine.
 *
 * The lock is `gateway.lock`. It is held from [org.oip.gateway.Gateway.start] until close, and
 * released by the operating system if the process dies. [tryAcquire] fails when this JVM or
 * another process already holds it, which is how offline `reset-admin` refuses a live gateway.
 */
class GatewayControl(private val dataDir: Path) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var published = false

    fun tryAcquire(): Boolean {
        Files.createDirectories(dataDir)
        val ch = FileChannel.open(lockFile(dataDir), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            val held = ch.tryLock()
            if (held == null) {
                ch.close()
                return false
            }
            channel = ch
            lock = held
            return true
        } catch (_: OverlappingFileLockException) {
            ch.close()
            return false
        }
    }

    /** Publish the pid and the client URL. The URL file appears only after both writes finish. */
    fun publish(url: String) {
        check(lock != null) { "gateway lock is not held" }
        atomic(PID_FILE, ProcessHandle.current().pid().toString() + "\n")
        atomic(URL_FILE, url.trim() + "\n")
        published = true
    }

    fun release() {
        if (published) {
            val pid = readPid(dataDir)
            if (pid == ProcessHandle.current().pid()) clearProcessFiles(dataDir, pid)
            published = false
        }
        val held = lock
        val ch = channel
        lock = null
        channel = null
        if (held != null) runCatching { held.release() }
        if (ch != null) runCatching { ch.close() }
    }

    private fun atomic(name: String, text: String) {
        val dest = dataDir.resolve(name)
        val tmp = dataDir.resolve("$name.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val PID_FILE = "gateway.pid"
        const val URL_FILE = "gateway.http"

        fun lockFile(dataDir: Path): Path = dataDir.resolve("gateway.lock")

        fun readPid(dataDir: Path): Long? {
            val file = dataDir.resolve(PID_FILE)
            if (!file.exists()) return null
            return file.readText().trim().toLongOrNull()
        }

        fun readUrl(dataDir: Path): String? {
            val file = dataDir.resolve(URL_FILE)
            if (!file.exists()) return null
            return file.readText().trim().ifEmpty { null }
        }

        fun clearProcessFiles(dataDir: Path, pid: Long) {
            if (readPid(dataDir) != pid) return
            Files.deleteIfExists(dataDir.resolve(PID_FILE))
            Files.deleteIfExists(dataDir.resolve(URL_FILE))
        }
    }
}
