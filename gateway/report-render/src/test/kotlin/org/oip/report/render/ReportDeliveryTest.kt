package org.oip.report.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.reporting.ReportDefinition
import org.oip.reporting.ReportRef
import org.oip.reporting.ReportResult
import org.oip.reporting.RunInfo
import org.oip.reporting.RunStatus
import org.oip.reporting.RunTrigger
import org.oip.reporting.ScheduleAction
import org.oip.reporting.ScheduleDef
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.readBytes

class ReportDeliveryTest {
    @TempDir lateinit var tmp: Path

    @Test fun saveEmailPrintAndFtpEachReceiveThePdf() {
        val mail = AtomicReference<ByteArray>()
        val ftp = AtomicReference<ByteArray>()
        smtp { mail.set(it) }.use { smtp ->
            ftp { ftp.set(it) }.use { ftpServer ->
                val actions = listOf(
                    ScheduleAction.Save,
                    ScheduleAction.Email("ops@plant.example", "reports@plant.example", "Daily", "127.0.0.1", smtp.port, "mailer", "secret://mail"),
                    ScheduleAction.Print("line-printer", 2),
                    ScheduleAction.Ftp("127.0.0.1", ftpServer.port, "ftpuser", "secret://ftp", "daily.pdf"),
                )
                val delivery = ReportDelivery(
                    PdfArchiveSink(tmp.resolve("archive")),
                    tmp.resolve("print"),
                    readSecret = { ref ->
                        when (ref) {
                            "secret://mail" -> "mail-secret"
                            "secret://ftp" -> "ftp-secret"
                            else -> error(ref)
                        }
                    },
                )
                delivery.deliver(definition(actions), result())
            }
        }
        val archived = Files.list(tmp.resolve("archive").resolve("daily")).use { it.toList() }
        assertEquals(1, archived.size)
        assertTrue(archived.single().readBytes().size > 100)
        assertTrue(mail.get().size > 100, "email attachment")
        assertTrue(String(mail.get(), StandardCharsets.ISO_8859_1).startsWith("%PDF"), "email attachment is a PDF")
        assertTrue(ftp.get().size > 100, "ftp payload")
        assertTrue(String(ftp.get(), StandardCharsets.ISO_8859_1).startsWith("%PDF"), "ftp payload is a PDF")
        val job = Files.list(tmp.resolve("print").resolve("daily")).use { it.toList() }.first { it.fileName.toString().endsWith(".job") }
        val text = Files.readString(job)
        assertTrue(text.contains("printer=line-printer"), text)
        assertTrue(text.contains("copies=2"), text)
    }

    private fun definition(actions: List<ScheduleAction>) = ReportDefinition(
        name = "daily",
        version = 1,
        title = "Daily",
        description = "",
        enabled = true,
        parameters = emptyList(),
        dataSources = emptyList(),
        schedule = ScheduleDef(true, "0 6 * * *", "UTC", emptyMap(), actions),
        layout = null,
    )

    private fun result() = ReportResult(
        report = ReportRef("reporting/gateway/Report/daily", "daily", "Daily", 1),
        parameters = emptyList(),
        generatedAt = 1_761_544_800_000L,
        datasets = emptyList(),
        run = RunInfo("run1", RunTrigger.SCHEDULE, "system", 1_761_544_800_000L, 10, RunStatus.SUCCESS, emptyList()),
        layout = null,
    )

    private fun smtp(onPdf: (ByteArray) -> Unit): Server {
        val server = ServerSocket(0)
        val thread = Thread {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
                fun line() = reader.readLine() ?: ""
                fun say(text: String) {
                    writer.write(text)
                    writer.write("\r\n")
                    writer.flush()
                }
                say("220 ready")
                line()
                say("250 hello")
                line()
                say("334 user")
                line()
                say("334 pass")
                line()
                say("235 ok")
                line()
                say("250 from")
                line()
                say("250 to")
                line()
                say("354 send")
                val body = StringBuilder()
                while (true) {
                    val next = line()
                    if (next == ".") break
                    body.append(next).append('\n')
                }
                val encoded = body.lines().dropWhile { !it.startsWith("JVBER") && !it.contains("JVBERi") }.joinToString("").substringAfter("\n")
                val pdf = runCatching { Base64.getMimeDecoder().decode(body.lines().filter { it.length > 40 && !it.startsWith("--") && !it.contains(":") }.joinToString("")) }.getOrNull()
                if (pdf != null && pdf.isNotEmpty()) onPdf(pdf)
                say("250 queued")
                line()
                say("221 bye")
            }
        }
        thread.isDaemon = true
        thread.start()
        return Server(server, thread)
    }

    private fun ftp(onPdf: (ByteArray) -> Unit): Server {
        val server = ServerSocket(0)
        val thread = Thread {
            server.accept().use { control ->
                val reader = BufferedReader(InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8))
                fun line() = reader.readLine() ?: ""
                fun say(text: String) {
                    writer.write(text)
                    writer.write("\r\n")
                    writer.flush()
                }
                say("220 ready")
                line()
                say("331 password")
                line()
                say("230 in")
                line()
                say("200 type")
                ServerSocket(0).use { passive ->
                    val port = passive.localPort
                    say("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
                    val data = passive.accept()
                    line()
                    say("150 go")
                    data.use { socket ->
                        onPdf(socket.getInputStream().readBytes())
                    }
                    say("226 done")
                    line()
                    say("221 bye")
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        return Server(server, thread)
    }

    private class Server(val socket: ServerSocket, val thread: Thread) : AutoCloseable {
        val port: Int get() = socket.localPort
        override fun close() {
            runCatching { socket.close() }
            thread.join(2_000)
        }
    }
}
