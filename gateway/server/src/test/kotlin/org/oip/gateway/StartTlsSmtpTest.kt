package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.alarm.notify.OutboundMail
import org.oip.alarm.notify.SmtpClient
import org.oip.alarm.notify.SmtpEndpoint
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * STARTTLS upgrades the SMTP session before AUTH. The debug transcript names the handshake and not the secret.
 */
class StartTlsSmtpTest {
    @Test fun startTlsThenAuthDoesNotLogThePassword() {
        val secret = "s3cret-mail-1"
        val encoded = Base64.getEncoder().encodeToString(secret.toByteArray(StandardCharsets.UTF_8))
        val material = TlsCertificates.generate(listOf("localhost", "127.0.0.1"))
        val serverContext = SSLContext.getInstance("TLS")
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(material.toKeyStore(STORE_PASSWORD), STORE_PASSWORD)
        serverContext.init(keys.keyManagers, null, null)
        val clientContext = SSLContext.getInstance("TLS")
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setCertificateEntry("gateway", material.chain.first())
        trust.init(store)
        clientContext.init(null, trust.trustManagers, null)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = Thread { serve(server.accept(), serverContext) }
            accepted.start()
            val endpoint = SmtpEndpoint(
                host = "localhost",
                port = server.localPort,
                useSsl = false,
                startTls = true,
                username = "mailer",
                password = secret.toCharArray(),
                timeoutMillis = 5_000,
                protocols = listOf("TLSv1.2"),
                debug = true,
                sslContext = clientContext,
            )
            val debug = ArrayList<String>()
            try {
                SmtpClient.send(
                    endpoint,
                    OutboundMail(
                        from = "alarms@example.com", to = "ada@example.com", subject = "High", body = "hello",
                        testMode = false, eventId = "e", username = "ada", profile = "mail", ackLink = false,
                        emailProfile = "mail", smtpProfile = "plant",
                    ),
                ) { debug += it }
            } finally {
                endpoint.wipe()
            }
            accepted.join(5_000)
            val transcript = debug.joinToString("\n")
            assertTrue(transcript.contains("STARTTLS"), transcript)
            assertTrue(transcript.contains("TLS handshake"), transcript)
            assertFalse(transcript.contains(secret), transcript)
            assertFalse(transcript.contains(encoded), transcript)
        }
    }

    private fun serve(socket: Socket, context: SSLContext) {
        socket.use { client ->
            var current = client
            fun reader() = BufferedReader(InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))
            fun writer() = BufferedWriter(OutputStreamWriter(current.getOutputStream(), StandardCharsets.UTF_8))
            var reader = reader()
            var writer = writer()
            fun say(line: String) {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
            }
            say("220 ready")
            reader.readLine()
            say("250 STARTTLS")
            val command = reader.readLine()
            check(command == "STARTTLS")
            say("220 go")
            val secure = context.socketFactory.createSocket(current, current.inetAddress.hostAddress, current.port, true) as SSLSocket
            secure.useClientMode = false
            secure.startHandshake()
            current = secure
            reader = reader()
            writer = writer()
            reader.readLine()
            say("250 AUTH LOGIN")
            reader.readLine()
            say("334 VXNlcm5hbWU6")
            reader.readLine()
            say("334 UGFzc3dvcmQ6")
            reader.readLine()
            say("235 ok")
            reader.readLine()
            say("250 ok")
            reader.readLine()
            say("250 ok")
            reader.readLine()
            say("354 go")
            while (true) {
                val line = reader.readLine() ?: break
                if (line == ".") break
            }
            say("250 queued")
            reader.readLine()
            say("221 bye")
        }
    }

    companion object {
        private val STORE_PASSWORD = "changeit".toCharArray()
    }
}
