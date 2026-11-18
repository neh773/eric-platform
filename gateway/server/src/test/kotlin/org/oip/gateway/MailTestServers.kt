package org.oip.gateway

import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** One message a [CapturingSmtp] accepted. */
class CapturedMail(val auth: String, val login: List<String>, val from: String, val to: String, val data: String)

/** A plain SMTP server on loopback that accepts AUTH LOGIN and AUTH XOAUTH2 and records each message. */
class CapturingSmtp : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val mails = CopyOnWriteArrayList<CapturedMail>()
    @Volatile private var closed = false
    private val thread = Thread {
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            Thread { runCatching { serve(socket) } }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true; start() }

    private fun serve(socket: java.net.Socket) = socket.use {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val out = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        fun say(line: String) { out.write(line + "\r\n"); out.flush() }
        say("220 test smtp")
        var auth = "none"
        val login = ArrayList<String>()
        var from = ""
        var to = ""
        while (true) {
            val line = reader.readLine() ?: return
            when {
                line.startsWith("EHLO") -> { say("250-localhost"); say("250 AUTH LOGIN XOAUTH2") }
                line == "AUTH LOGIN" -> {
                    say("334 VXNlcm5hbWU6")
                    login += decode(reader.readLine() ?: return)
                    say("334 UGFzc3dvcmQ6")
                    login += decode(reader.readLine() ?: return)
                    auth = "login"
                    say("235 ok")
                }
                line.startsWith("AUTH XOAUTH2 ") -> {
                    login += decode(line.removePrefix("AUTH XOAUTH2 "))
                    auth = "xoauth2"
                    say("235 ok")
                }
                line.startsWith("MAIL FROM:") -> { from = line.substringAfter('<').substringBefore('>'); say("250 ok") }
                line.startsWith("RCPT TO:") -> { to = line.substringAfter('<').substringBefore('>'); say("250 ok") }
                line == "DATA" -> {
                    say("354 go")
                    val data = StringBuilder()
                    while (true) {
                        val row = reader.readLine() ?: return
                        if (row == ".") break
                        data.append(row).append('\n')
                    }
                    mails += CapturedMail(auth, login.toList(), from, to, data.toString())
                    say("250 queued")
                }
                line == "QUIT" -> { say("221 bye"); return }
                else -> say("250 ok")
            }
        }
    }

    private fun decode(text: String) = String(Base64.getDecoder().decode(text.trim()), StandardCharsets.UTF_8)

    fun await(count: Int = 1, timeoutMs: Long = 10_000): List<CapturedMail> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (mails.size < count && System.nanoTime() < deadline) Thread.sleep(25)
        return mails.toList()
    }

    override fun close() {
        closed = true
        server.close()
        thread.interrupt()
    }
}

/** An OAuth2 token endpoint on loopback. Every request body is recorded. */
class TokenServer(private val token: String = "access-token-xyz", private val expiresIn: Int = 3600, private val status: Int = 200) : AutoCloseable {
    private val http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val requests = CopyOnWriteArrayList<Map<String, String>>()
    val url: String get() = "http://127.0.0.1:${http.address.port}/token"

    init {
        http.createContext("/token") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            requests += body.split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split('=', limit = 2)
                java.net.URLDecoder.decode(k, StandardCharsets.UTF_8) to java.net.URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
            val reply = if (status == 200) """{"access_token":"$token","token_type":"Bearer","expires_in":$expiresIn}""" else """{"error":"invalid_client"}"""
            val bytes = reply.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
    }

    override fun close() = http.stop(0)
}
