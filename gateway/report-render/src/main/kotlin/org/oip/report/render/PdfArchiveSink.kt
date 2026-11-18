package org.oip.report.render

import org.oip.reporting.ReportDefinition
import org.oip.reporting.ReportResult
import org.oip.reporting.ReportResultSink
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** One archived scheduled report PDF. */
data class ArchivedReport(val report: String, val file: String, val generatedAt: Long, val size: Long) {
    fun toMap(): Map<String, Any?> = linkedMapOf("report" to report, "file" to file, "generatedAt" to generatedAt, "size" to size)
}

/**
 * Scheduled-run delivery: renders each result the scheduler hands over to PDF and keeps it under
 * `<root>/<report>/<generatedAt>-<runId>.pdf`, newest [keep] per report (older files are deleted on each delivery).
 * Files are written to a temporary name and moved into place, so a reader never sees a partial PDF.
 * Runs on the scheduler's run thread; failures propagate to the scheduler, which logs them.
 */
class PdfArchiveSink(private val root: Path, private val keep: Int = DEFAULT_KEEP) : ReportResultSink {
    init {
        require(keep >= 1) { "keep must be at least 1" }
    }

    override fun deliver(definition: ReportDefinition, result: ReportResult) {
        val pdf = render(definition, result)
        store(definition.name, result.generatedAt, result.run.runId, pdf)
    }

    internal fun store(report: String, generatedAt: Long, runId: String, pdf: ByteArray): Path {
        val dir = reportDir(report) ?: throw IllegalArgumentException("invalid report name '$report'")
        val fileName = "$generatedAt-$runId.pdf"
        require(FILE.matches(fileName)) { "invalid run id '$runId'" }
        synchronized(this) {
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, ".partial-", ".tmp")
            try {
                Files.write(tmp, pdf)
                val target = dir.resolve(fileName)
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                files(report).drop(keep).forEach { Files.deleteIfExists(dir.resolve(it.file)) }
                return target
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    /** Archived PDFs of [report], newest first; empty when there are none or the name is not a valid report name. */
    fun files(report: String): List<ArchivedReport> {
        val dir = reportDir(report)?.takeIf { it.isDirectory() } ?: return emptyList()
        return dir.listDirectoryEntries().asSequence()
            .filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) && FILE.matches(it.name) }
            .map { ArchivedReport(report, it.name, it.name.substringBefore('-').toLong(), Files.size(it)) }
            .sortedWith(compareByDescending<ArchivedReport> { it.generatedAt }.thenByDescending { it.file })
            .toList()
    }

    /** The archived file, or null when the names are invalid or it does not exist. Never resolves outside [root]. */
    fun file(report: String, fileName: String): Path? {
        if (!FILE.matches(fileName)) return null
        return reportDir(report)?.resolve(fileName)?.takeIf { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
    }

    private fun reportDir(report: String): Path? =
        if (REPORT.matches(report) && report != "." && report != "..") root.resolve(report) else null

    companion object {
        const val DEFAULT_KEEP = 50
        private val REPORT = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val FILE = Regex("^[0-9]{1,19}-[0-9A-Za-z-]{1,64}\\.pdf$")
    }
}
