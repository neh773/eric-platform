package org.oip.report.render

import org.oip.reporting.ReportDefinition
import org.oip.reporting.ReportResult
import org.oip.reporting.ReportResultSink
import org.oip.reporting.ScheduleAction
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.print.DocFlavor
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc
import javax.print.attribute.HashPrintRequestAttributeSet
import javax.print.attribute.standard.Copies

/**
 * Sends a report PDF through a shared SMTP email profile (GAT-030). The gateway supplies the implementation, so the
 * profile's host, login and OAuth2 token are looked up in one place for reports, alarm notification and scripts.
 * [from] is empty when the schedule names none; the profile's sender applies then.
 */
fun interface ReportMailer {
    fun send(profile: String, from: String, to: String, subject: String, filename: String, pdf: ByteArray)
}

/**
 * Scheduled report delivery. With no actions the PDF is saved, as before. `email` submits one PDF over SMTP,
 * `print` writes a spool job and sends it to an OS printer of that name when one is installed, and `ftp` stores
 * the PDF with one binary STOR. Passwords are `secret://` references resolved by [readSecret] at send time.
 */
class ReportDelivery(
    private val archive: PdfArchiveSink,
    private val printRoot: Path,
    private val readSecret: (String) -> String = { throw IllegalArgumentException("no secret store") },
    private val timeoutMillis: Int = 5_000,
    /** Delivers an email action that names an SMTP profile. Without one such an action fails. */
    private val mailer: ReportMailer? = null,
) : ReportResultSink {
    override fun deliver(definition: ReportDefinition, result: ReportResult) {
        val pdf = render(definition, result)
        val actions = definition.schedule?.actions.orEmpty().ifEmpty { listOf(ScheduleAction.Save) }
        for (action in actions) {
            when (action) {
                ScheduleAction.Save -> archive.store(definition.name, result.generatedAt, result.run.runId, pdf)
                is ScheduleAction.Email -> email(action, definition, pdf)
                is ScheduleAction.Print -> print(action, definition.name, result.generatedAt, result.run.runId, pdf)
                is ScheduleAction.Ftp -> ftp(action, pdf)
            }
        }
    }

    private fun email(action: ScheduleAction.Email, definition: ReportDefinition, pdf: ByteArray) {
        if (action.smtpProfile.isNotEmpty()) {
            val sender = mailer ?: throw IllegalStateException("no email profiles are available to send report mail")
            sender.send(
                action.smtpProfile,
                action.from,
                action.to,
                action.subject.ifBlank { definition.title.ifBlank { definition.name } },
                "${definition.name}.pdf",
                pdf,
            )
            return
        }
        val password = if (action.passwordRef.isEmpty()) "" else readSecret(action.passwordRef)
        SmtpSubmit.send(
            MailJob(
                host = action.host,
                port = action.port,
                username = action.username,
                password = password,
                from = action.from,
                to = action.to,
                subject = action.subject.ifBlank { definition.title.ifBlank { definition.name } },
                filename = "${definition.name}.pdf",
                pdf = pdf,
                timeoutMillis = timeoutMillis,
            ),
        )
    }

    private fun print(action: ScheduleAction.Print, report: String, generatedAt: Long, runId: String, pdf: ByteArray) {
        val dir = printRoot.resolve(report)
        Files.createDirectories(dir)
        val base = "$generatedAt-$runId"
        Files.write(dir.resolve("$base.pdf"), pdf)
        Files.writeString(dir.resolve("$base.job"), "printer=${action.printer}\ncopies=${action.copies}\n")
        runCatching {
            val match = PrintServiceLookup.lookupPrintServices(null, null)
                .firstOrNull { it.name.equals(action.printer, ignoreCase = true) } ?: return
            val flavor = DocFlavor.BYTE_ARRAY.PDF
            if (!match.isDocFlavorSupported(flavor)) return
            val attributes = HashPrintRequestAttributeSet()
            attributes.add(Copies(action.copies))
            match.createPrintJob().print(SimpleDoc(pdf, flavor, null), attributes)
        }
    }

    private fun ftp(action: ScheduleAction.Ftp, pdf: ByteArray) {
        val password = if (action.passwordRef.isEmpty()) "" else readSecret(action.passwordRef)
        FtpSubmit.store(
            FtpJob(action.host, action.port, action.username, password, action.path, pdf, timeoutMillis),
        )
    }
}

internal data class MailJob(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val from: String,
    val to: String,
    val subject: String,
    val filename: String,
    val pdf: ByteArray,
    val timeoutMillis: Int,
)

internal data class FtpJob(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val path: String,
    val pdf: ByteArray,
    val timeoutMillis: Int,
)

internal object SmtpSubmit {
    fun send(job: MailJob) {
        for ((field, address) in listOf("from" to job.from, "to" to job.to)) {
            if (address.any { it.code < 0x20 || it.code == 0x7F }) throw IllegalArgumentException("$field address contains a control character")
        }
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(job.host, job.port), job.timeoutMillis)
            socket.soTimeout = job.timeoutMillis
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            fun read(): String {
                val line = reader.readLine() ?: throw IllegalStateException("smtp connection closed")
                return line
            }
            fun expect(code: String) {
                var line = read()
                while (line.length >= 4 && line[3] == '-') line = read()
                if (!line.startsWith(code)) throw IllegalStateException("smtp refused the message")
            }
            fun cmd(line: String, code: String) {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
                expect(code)
            }
            expect("220")
            cmd("EHLO oip", "250")
            if (job.username.isNotEmpty()) {
                cmd("AUTH LOGIN", "334")
                cmd(Base64.getEncoder().encodeToString(job.username.toByteArray(StandardCharsets.UTF_8)), "334")
                cmd(Base64.getEncoder().encodeToString(job.password.toByteArray(StandardCharsets.UTF_8)), "235")
            }
            cmd("MAIL FROM:<${job.from}>", "250")
            cmd("RCPT TO:<${job.to}>", "250")
            cmd("DATA", "354")
            writer.write(message(job))
            writer.write("\r\n.\r\n")
            writer.flush()
            expect("250")
            cmd("QUIT", "221")
        }
    }

    private fun message(job: MailJob): String {
        val boundary = "oip-${job.filename.hashCode().toUInt().toString(16)}"
        val encoded = Base64.getMimeEncoder().encodeToString(job.pdf)
        val subject = job.subject.replace("\r", " ").replace("\n", " ")
        return buildString {
            append("From: ").append(job.from).append("\r\n")
            append("To: ").append(job.to).append("\r\n")
            append("Subject: ").append(subject).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: multipart/mixed; boundary=").append(boundary).append("\r\n")
            append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/pdf\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; filename=\"").append(job.filename).append("\"\r\n")
            append("\r\n")
            append(encoded).append("\r\n")
            append("--").append(boundary).append("--")
        }
    }
}

internal object FtpSubmit {
    fun store(job: FtpJob) {
        Socket().use { control ->
            control.tcpNoDelay = true
            control.connect(InetSocketAddress(job.host, job.port), job.timeoutMillis)
            control.soTimeout = job.timeoutMillis
            val reader = BufferedReader(InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8))
            fun read(): String = reader.readLine() ?: throw IllegalStateException("ftp connection closed")
            fun expect(code: String): String {
                val line = read()
                if (!line.startsWith(code)) throw IllegalStateException("ftp refused the transfer")
                return line
            }
            fun cmd(line: String, code: String): String {
                writer.write(line)
                writer.write("\r\n")
                writer.flush()
                return expect(code)
            }
            expect("220")
            cmd("USER ${job.username}", "331")
            cmd("PASS ${job.password}", "230")
            cmd("TYPE I", "200")
            val passive = cmd("PASV", "227")
            val (host, port) = passiveAddress(passive)
            Socket().use { data ->
                data.tcpNoDelay = true
                data.connect(InetSocketAddress(host, port), job.timeoutMillis)
                data.soTimeout = job.timeoutMillis
                cmd("STOR ${job.path}", "150")
                data.getOutputStream().use { it.write(job.pdf) }
            }
            expect("226")
            cmd("QUIT", "221")
        }
    }

    private fun passiveAddress(line: String): Pair<String, Int> {
        val open = line.indexOf('(')
        val close = line.indexOf(')', open + 1)
        if (open < 0 || close < 0) throw IllegalStateException("ftp passive address was not understood")
        val parts = line.substring(open + 1, close).split(',')
        if (parts.size != 6) throw IllegalStateException("ftp passive address was not understood")
        val numbers = parts.map { it.trim().toInt() }
        val host = numbers.take(4).joinToString(".")
        val port = numbers[4] * 256 + numbers[5]
        return host to port
    }
}
