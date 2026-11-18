package org.oip.perfsim

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Peer check for the high-point simulator (PRF-012).
 *
 * 127.0.0.1, localhost, and any other loopback address are refused before a
 * socket is opened. Functional tests of the compose stack do not use this
 * helper. The sizing guide's tag floors are measured against a networked
 * simulator: -
 */
object PerfSimPeer {
    fun require(host: String): String {
        val name = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (name.isEmpty() || name == "localhost" || name.endsWith(".localhost") ||
            name == "::1" || name == "0.0.0.0" || name == "::" || name.startsWith("127.")
        ) {
            throw IllegalArgumentException("high-point driver target is loopback; PRF-012 requires a non-loopback address")
        }
        val literal = parseLiteral(name)
        if (literal != null && literal.isLoopbackAddress) {
            throw IllegalArgumentException("high-point driver target is loopback; PRF-012 requires a non-loopback address")
        }
        if (literal == null) {
            try {
                val resolved = InetAddress.getAllByName(name)
                if (resolved.isEmpty() || resolved.any { it.isLoopbackAddress }) {
                    throw IllegalArgumentException("high-point driver target is loopback; PRF-012 requires a non-loopback address")
                }
            } catch (ex: UnknownHostException) {
                throw IllegalArgumentException("high-point driver target is not a usable address", ex)
            }
        }
        return host.trim()
    }

    fun connect(host: String, port: Int, timeoutMs: Int = 1_000): Socket {
        val target = require(host)
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target, port), timeoutMs)
            return socket
        } catch (ex: Exception) {
            socket.close()
            throw ex
        }
    }

    fun assertRefusesLoopback() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 300
            val refused = assertThrows(IllegalArgumentException::class.java) {
                connect("127.0.0.1", server.localPort)
            }
            assertTrue(refused.message!!.contains("loopback"), refused.message)
            assertThrows(SocketTimeoutException::class.java) { server.accept() }
        }
        for (host in listOf("localhost", "::1", "127.0.0.2", "0.0.0.0", "::ffff:127.0.0.1")) {
            val refused = assertThrows(IllegalArgumentException::class.java) { require(host) }
            assertTrue(refused.message!!.contains("loopback"), "$host ${refused.message}")
        }
    }

    private fun parseLiteral(name: String): InetAddress? {
        val mapped = if (name.startsWith("::ffff:")) name.removePrefix("::ffff:") else name
        val parts = mapped.split('.')
        if (parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 }) {
            val bytes = parts.map { it.toInt().toByte() }.toByteArray()
            return InetAddress.getByAddress(bytes)
        }
        if (name.contains(':')) {
            return try {
                InetAddress.getByName(name)
            } catch (_: UnknownHostException) {
                null
            }
        }
        return null
    }
}
