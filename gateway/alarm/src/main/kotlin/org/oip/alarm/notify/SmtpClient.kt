package org.oip.alarm.notify

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * SMTP submission with optional implicit TLS or STARTTLS.
 *
 * The password is a secret-store reference on the profile. This client never writes the password,
 * its base64 form, or the AUTH payload to [debug].
 */
class SmtpEndpoint(
    val host: String,
    val port: Int,
    val useSsl: Boolean,
    val startTls: Boolean,
    val username: String,
    val password: CharArray,
    val timeoutMillis: Int,
    val protocols: List<String>,
    val debug: Boolean,
    val sslContext: SSLContext? = null,
    /** True when [password] holds an OAuth2 access token and the login is AUTH XOAUTH2. */
    val xoauth2: Boolean = false,
) {
    fun wipe() {
        password.fill('\u0000')
    }

    override fun toString(): String =
        "SmtpEndpoint(host=$host, port=$port, useSsl=$useSsl, startTls=$startTls, timeoutMillis=$timeoutMillis)"
}

/** One file sent with a mail (a scheduled report's PDF). */
class MailAttachment(val filename: String, val contentType: String, val bytes: ByteArray)

data class OutboundMail(
    val from: String,
    val to: String,
    val subject: String,
    val body: String,
    val testMode: Boolean,
    val eventId: String,
    val username: String,
    val profile: String,
    val ackLink: Boolean,
    val emailProfile: String?,
    val smtpProfile: String?,
    val retries: Int = 0,
    val retryDelayMillis: Long = 0,
    val html: Boolean = false,
    val attachment: MailAttachment? = null,
)

fun interface MailTransport {
    fun send(endpoint: SmtpEndpoint, mail: OutboundMail, debug: (String) -> Unit)
}

class SmtpException(message: String) : RuntimeException(message)

object SmtpClient : MailTransport {
    override fun send(endpoint: SmtpEndpoint, mail: OutboundMail, debug: (String) -> Unit) {
        if (mail.testMode) {
            if (endpoint.debug) debug("test mode; message not sent")
            return
        }
        val note: (String) -> Unit = { line -> if (endpoint.debug) debug(line) }
        // Review-3 F9: an address with CR, LF or another control character would inject SMTP commands or headers.
        for ((field, address) in listOf("from" to mail.from, "to" to mail.to)) {
            if (address.any { it.code < 0x20 || it.code == 0x7F }) throw SmtpException("$field address contains a control character")
        }
        Socket().use { plain ->
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(endpoint.host, endpoint.port), endpoint.timeoutMillis)
            plain.soTimeout = endpoint.timeoutMillis
            var socket: Socket = plain
            if (endpoint.useSsl) socket = upgrade(plain, endpoint, note)
            val session = Session(socket, note)
            session.expect(220)
            session.command("EHLO oip.gateway", 250)
            if (!endpoint.useSsl && endpoint.startTls) {
                session.command("STARTTLS", 220)
                socket = upgrade(plain, endpoint, note)
                session.replace(socket)
                session.command("EHLO oip.gateway", 250)
            }
            if (endpoint.xoauth2) {
                note("AUTH XOAUTH2")
                val initial = "user=${endpoint.username}\u0001auth=Bearer ${String(endpoint.password)}\u0001\u0001"
                session.commandRaw("AUTH XOAUTH2 " + Base64.getEncoder().encodeToString(initial.toByteArray(StandardCharsets.UTF_8)), 235)
            } else if (endpoint.username.isNotEmpty()) {
                note("AUTH LOGIN")
                session.commandRaw("AUTH LOGIN", 334)
                session.commandRaw(Base64.getEncoder().encodeToString(endpoint.username.toByteArray(StandardCharsets.UTF_8)), 334)
                session.commandRaw(Base64.getEncoder().encodeToString(String(endpoint.password).toByteArray(StandardCharsets.UTF_8)), 235)
            }
            session.command("MAIL FROM:<${mail.from}>", 250)
            session.command("RCPT TO:<${mail.to}>", 250)
            session.command("DATA", 354)
            session.write(messageBytes(mail))
            session.expect(250)
            session.command("QUIT", 221)
        }
    }

    private fun upgrade(socket: Socket, endpoint: SmtpEndpoint, note: (String) -> Unit): SSLSocket {
        note("TLS handshake")
        // SEC-022: without a context of its own, the gateway's outbound trust store.
        val context = endpoint.sslContext ?: org.oip.config.OutboundTls.context("mail")
        val factory = context.socketFactory
        val secure = factory.createSocket(socket, endpoint.host, endpoint.port, true) as SSLSocket
        val allowed = endpoint.protocols.filter { it in secure.supportedProtocols }
        if (allowed.isEmpty()) throw SmtpException("none of the configured SSL protocols are supported")
        secure.enabledProtocols = allowed.toTypedArray()
        val params = secure.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        secure.sslParameters = params
        secure.soTimeout = endpoint.timeoutMillis
        secure.startHandshake()
        return secure
    }

    private fun messageBytes(mail: OutboundMail): String {
        val attachment = mail.attachment
        if (attachment != null) return multipart(mail, attachment)
        val type = if (mail.html || mail.body.trimStart().startsWith("<html", true)) "text/html" else "text/plain"
        return buildString {
            append("From: <${mail.from}>\r\n")
            append("To: <${mail.to}>\r\n")
            append("Subject: ${mail.subject.replace("\r", " ").replace("\n", " ")}\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: $type; charset=utf-8\r\n")
            append("\r\n")
            append(dotStuff(mail.body))
            append("\r\n.\r\n")
        }
    }

    private fun multipart(mail: OutboundMail, file: MailAttachment): String {
        val boundary = "oip-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val type = if (mail.html) "text/html" else "text/plain"
        val name = file.filename.filter { it.code in 0x20..0x7E && it != '"' && it != '\\' }.ifEmpty { "attachment" }
        return buildString {
            append("From: <${mail.from}>\r\n")
            append("To: <${mail.to}>\r\n")
            append("Subject: ${mail.subject.replace("\r", " ").replace("\n", " ")}\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: multipart/mixed; boundary=$boundary\r\n")
            append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: $type; charset=utf-8\r\n")
            append("\r\n")
            append(dotStuff(mail.body))
            append("\r\n--$boundary\r\n")
            append("Content-Type: ${file.contentType}\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; filename=\"$name\"\r\n")
            append("\r\n")
            append(Base64.getMimeEncoder().encodeToString(file.bytes))
            append("\r\n--$boundary--")
            append("\r\n.\r\n")
        }
    }

    private class Session(private var socket: Socket, private val note: (String) -> Unit) {
        private var reader = readerOf(socket)
        private var writer = writerOf(socket)

        fun replace(next: Socket) {
            socket = next
            reader = readerOf(next)
            writer = writerOf(next)
        }

        fun command(line: String, code: Int) {
            note(line.substringBefore(' '))
            commandRaw(line, code)
        }

        fun commandRaw(line: String, code: Int) {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
            expect(code)
        }

        fun write(payload: String) {
            writer.write(payload)
            writer.flush()
        }

        fun expect(code: Int) {
            val lines = ArrayList<String>()
            while (true) {
                val line = reader.readLine() ?: throw SmtpException("smtp connection closed")
                lines += line
                if (line.length >= 4 && line[3] == ' ') {
                    val actual = line.substring(0, 3).toIntOrNull() ?: throw SmtpException("smtp reply was not a status code")
                    if (actual != code) throw SmtpException("smtp expected $code but got $actual")
                    return
                }
                if (lines.size > 40) throw SmtpException("smtp reply was too long")
            }
        }
    }
}

internal fun dotStuff(body: String): String =
    body.replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("\r\n") { line ->
        if (line.startsWith(".")) ".$line" else line
    }

private fun readerOf(socket: Socket) = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))

private fun writerOf(socket: Socket) = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))

/** Replace a resolved secret if it ever appears in a log line. Short values are left alone so they do not erase ordinary words. */
fun scrubSecret(text: String, secret: String): String {
    if (secret.length < 4) return text
    return text.replace(secret, "***")
}
