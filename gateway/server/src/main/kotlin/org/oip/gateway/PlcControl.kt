package org.oip.gateway

import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

/**
 * The scan engine's control socket. Commands are one JSON line.
 * A missing socket leaves the editor on its in-process stand-in so tests
 * can deploy without a running engine. When the socket is set, deploy, start,
 * stop, force, and watch go to the engine. The engine keeps outputs at the
 * safe value while it is stopped, swapping, or faulted.
 */
interface PlcControl {
    fun connected(): Boolean = false
    fun deploy(program: String, entry: String, instance: String, hwmap: String?): String? = null
    fun start(): String? = null
    fun stop(): String? = null
    fun force(name: String, value: Any?, output: Boolean): String? = null
    fun unforce(name: String): String? = null
    fun status(): Map<String, Any?>? = null
    fun watch(): Map<String, Any?>? = null

    companion object {
        fun disconnected(): PlcControl = None

        fun fromEnvironment(): PlcControl {
            val path = System.getenv("ERIC_PLC_SOCKET")?.takeIf { it.isNotEmpty() } ?: return None
            return UnixPlcControl(path)
        }
    }
}

private object None : PlcControl

class UnixPlcControl(private val path: String) : PlcControl {
    override fun connected(): Boolean = path.isNotEmpty()

    override fun deploy(program: String, entry: String, instance: String, hwmap: String?): String? =
        command(mapOf("op" to "deploy", "program" to program, "entry" to entry, "instance" to instance, "hwmap" to hwmap.orEmpty()))

    override fun start(): String? = command(mapOf("op" to "start"))

    override fun stop(): String? = command(mapOf("op" to "stop"))

    override fun force(name: String, value: Any?, output: Boolean): String? =
        command(mapOf("op" to "force", "name" to name, "value" to value, "output" to output))

    override fun unforce(name: String): String? = command(mapOf("op" to "unforce", "name" to name))

    override fun status(): Map<String, Any?>? = read(mapOf("op" to "status"))

    override fun watch(): Map<String, Any?>? = read(mapOf("op" to "watch"))

    private fun command(body: Map<String, Any?>): String? = try {
        val reply = exchange(body)
        if (reply["ok"] == true) null else reply["error"]?.toString() ?: "the scan engine rejected the command"
    } catch (ex: Exception) {
        ex.message ?: "the scan engine is not reachable"
    }

    private fun read(body: Map<String, Any?>): Map<String, Any?>? = try {
        exchange(body).takeIf { it["ok"] == true }
    } catch (_: Exception) {
        null
    }

    private fun exchange(body: Map<String, Any?>): Map<String, Any?> {
        if (path.startsWith("\\\\")) return exchangePipe(body)
        val channel = SocketChannel.open(UnixDomainSocketAddress.of(path))
        channel.use {
            val payload = (Json.write(body) + "\n").toByteArray(StandardCharsets.UTF_8)
            val outgoing = ByteBuffer.wrap(payload)
            while (outgoing.hasRemaining()) channel.write(outgoing)
            channel.configureBlocking(false)
            val incoming = ByteBuffer.allocate(1 shl 16)
            val text = StringBuilder()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline && !text.contains('\n')) {
                incoming.clear()
                val n = channel.read(incoming)
                if (n > 0) {
                    incoming.flip()
                    text.append(StandardCharsets.UTF_8.decode(incoming))
                } else if (n < 0) {
                    break
                } else {
                    Thread.sleep(5)
                }
            }
            val line = text.toString()
            val end = line.indexOf('\n')
            if (end < 0) throw IllegalStateException("the scan engine did not answer")
            @Suppress("UNCHECKED_CAST")
            return Json.mapper.readValue(line.substring(0, end), Map::class.java) as Map<String, Any?>
        }
    }

    /** A Windows named pipe in byte mode. `RandomAccessFile` opens `\\.\pipe\...`. */
    private fun exchangePipe(body: Map<String, Any?>): Map<String, Any?> {
        java.io.RandomAccessFile(path, "rw").use { pipe ->
            pipe.write((Json.write(body) + "\n").toByteArray(StandardCharsets.UTF_8))
            val text = StringBuilder()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline && !text.contains('\n')) {
                val n = pipe.read()
                if (n < 0) break
                text.append(n.toChar())
            }
            val line = text.toString()
            val end = line.indexOf('\n')
            if (end < 0) throw IllegalStateException("the scan engine did not answer")
            @Suppress("UNCHECKED_CAST")
            return Json.mapper.readValue(line.substring(0, end), Map::class.java) as Map<String, Any?>
        }
    }
}
