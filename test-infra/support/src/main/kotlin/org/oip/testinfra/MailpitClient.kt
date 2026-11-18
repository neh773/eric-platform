package org.oip.testinfra

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class MailMessage(val id: String, val subject: String, val to: List<String>, val snippet: String)

/** SMTP submission plus the Mailpit HTTP API (https://mailpit.axllent.org/docs/api-v1/). */
class MailpitClient(private val httpBase: String, private val smtpHost: String, private val smtpPort: Int) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val mapper = ObjectMapper()

    fun live(): Boolean {
        val response = send("GET", "/livez")
        return response.statusCode() == 200
    }

    fun deleteAll() {
        val response = send("DELETE", "/api/v1/messages")
        if (response.statusCode() !in 200..299) error("mailpit delete -> ${response.statusCode()}: ${response.body()}")
    }

    fun messages(): List<MailMessage> {
        val response = send("GET", "/api/v1/messages")
        if (response.statusCode() != 200) error("mailpit list -> ${response.statusCode()}: ${response.body()}")
        val root = mapper.readTree(response.body())
        return root.path("messages").map { node ->
            MailMessage(
                id = node.path("ID").asText(),
                subject = node.path("Subject").asText(),
                to = node.path("To").map { it.path("Address").asText() },
                snippet = node.path("Snippet").asText(),
            )
        }
    }

    fun await(subject: String, timeoutMs: Long = 5_000): MailMessage {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var last = emptyList<MailMessage>()
        while (System.nanoTime() < deadline) {
            last = messages()
            val found = last.firstOrNull { it.subject == subject }
            if (found != null) return found
            Thread.sleep(50)
        }
        error("no message with subject $subject in $last")
    }

    fun send(
        from: String,
        to: String,
        subject: String,
        body: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 15_000,
    ) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(smtpHost, smtpPort), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            expect(reader, 220)
            command(writer, reader, "EHLO oip.test", 250)
            command(writer, reader, "MAIL FROM:<$from>", 250)
            command(writer, reader, "RCPT TO:<$to>", 250)
            command(writer, reader, "DATA", 354)
            val payload = buildString {
                append("From: <$from>\r\n")
                append("To: <$to>\r\n")
                append("Subject: $subject\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("\r\n")
                append(dotStuff(body))
                append("\r\n.\r\n")
            }
            writer.write(payload)
            writer.flush()
            expect(reader, 250)
            command(writer, reader, "QUIT", 221)
        }
    }

    private fun send(method: String, path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(httpBase.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}

internal fun dotStuff(body: String): String =
    body.replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("\r\n") { line ->
        if (line.startsWith(".")) ".$line" else line
    }

private fun command(writer: BufferedWriter, reader: BufferedReader, line: String, code: Int) {
    writer.write(line)
    writer.write("\r\n")
    writer.flush()
    expect(reader, code)
}

private fun expect(reader: BufferedReader, code: Int) {
    val lines = mutableListOf<String>()
    while (true) {
        val line = reader.readLine() ?: error("smtp closed after $lines")
        lines += line
        if (line.length >= 4 && line[3] == ' ') {
            val actual = line.substring(0, 3).toInt()
            if (actual != code) error("smtp expected $code but got $lines")
            return
        }
    }
}
