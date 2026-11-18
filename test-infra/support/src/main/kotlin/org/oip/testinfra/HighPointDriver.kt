package org.oip.testinfra

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Driver entry for the high-point simulator (PRF-012).
 *
 * A target of 127.0.0.1, localhost, or any other loopback address is refused
 * before a socket is opened. Functional tests of the compose stack do not use
 * this helper; they may still dial the loopback address of that stack.
 */
object HighPointDriver {
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
                if (resolved.isNotEmpty() && resolved.any { it.isLoopbackAddress }) {
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
