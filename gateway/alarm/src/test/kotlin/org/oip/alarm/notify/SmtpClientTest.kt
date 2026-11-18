package org.oip.alarm.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Plain SMTP, including AUTH, without writing the password into the debug transcript.
 */
class SmtpClientTest {
    @Test fun authPasswordIsNeverLogged() {
        val secret = "s3cret-mail-1"
        val encoded = Base64.getEncoder().encodeToString(secret.toByteArray(StandardCharsets.UTF_8))
        val body = AtomicReference<String>()
        ServerSocket(0).use { server ->
            val accepted = Thread { serve(server.accept(), body) }
            accepted.start()
            val endpoint = SmtpEndpoint(
                host = "127.0.0.1",
                port = server.localPort,
                useSsl = false,
                startTls = false,
                username = "mailer",
                password = secret.toCharArray(),
                timeoutMillis = 5_000,
                protocols = listOf("TLSv1.2"),
                debug = true,
            )
            val debug = ArrayList<String>()
            try {
                SmtpClient.send(
                    endpoint,
                    OutboundMail(
                        from = "alarms@example.com",
                        to = "ada@example.com",
                        subject = "High",
                        body = "line\n.hidden",
                        testMode = false,
                        eventId = "e",
                        username = "ada",
                        profile = "mail",
                        ackLink = false,
                        emailProfile = "mail",
                        smtpProfile = null,
                    ),
                ) { debug += it }
            } finally {
                endpoint.wipe()
            }
            accepted.join(5_000)
            val transcript = debug.joinToString("\n")
            assertFalse(transcript.contains(secret), transcript)
            assertFalse(transcript.contains(encoded), transcript)
            assertTrue(transcript.contains("AUTH"), transcript)
            assertTrue(transcript.contains("EHLO"), transcript)
            assertFalse(endpoint.toString().contains(secret))
            assertTrue(endpoint.password.all { it == '\u0000' })
            assertTrue(body.get().contains("..hidden"), body.get())
        }
        assertEquals("failed ***", scrubSecret("failed $secret", secret))
        assertEquals("ab", scrubSecret("ab", "ab"))
    }

    private fun serve(socket: Socket, body: AtomicReference<String>) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
            fun say(line: String) {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
            }
            say("220 mail.example.com ready")
            read(reader)
            say("250-mail.example.com")
            say("250 AUTH LOGIN")
            read(reader)
            say("334 VXNlcm5hbWU6")
            read(reader)
            say("334 UGFzc3dvcmQ6")
            read(reader)
            say("235 ok")
            read(reader)
            say("250 ok")
            read(reader)
            say("250 ok")
            read(reader)
            say("354 go")
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line == ".") break
                data.append(line).append('\n')
            }
            body.set(data.toString())
            say("250 queued")
            read(reader)
            say("221 bye")
        }
    }

    private fun read(reader: BufferedReader) {
        val line = reader.readLine() ?: error("client closed")
        check(line.isNotEmpty())
    }
}
