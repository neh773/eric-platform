package org.oip.backup

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Size-capped zip read/write. Entry names are relative and cannot escape the archive root. */
object ZipIo {
    const val MAX_ENTRY = 64 * 1024 * 1024
    const val MAX_TOTAL = 256 * 1024 * 1024

    fun write(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                require(safeName(name) == name) { "refusing to write unsafe zip entry '$name'" }
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Read a zip into (name -> bytes), rejecting path traversal and oversized entries. */
    fun read(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = safeName(entry.name)
                    if (name in out) throw BackupException("duplicate zip entry '$name'")
                    val data = readLimited(zip, MAX_ENTRY)
                    total += data.size
                    if (total > MAX_TOTAL) throw BackupException("zip exceeds $MAX_TOTAL uncompressed bytes")
                    out[name] = data
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: ZipException) {
            throw BackupException("not a zip archive: ${e.message}")
        }
        return out
    }

    fun safeName(raw: String): String {
        val name = raw.replace('\\', '/')
        if (name.isBlank() || name.startsWith("/") || name.contains(":")) throw BackupException("illegal zip entry '$raw'")
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) throw BackupException("illegal zip entry '$raw'")
        return name
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw BackupException("zip entry exceeds $max bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

class BackupException(message: String) : RuntimeException(message)
