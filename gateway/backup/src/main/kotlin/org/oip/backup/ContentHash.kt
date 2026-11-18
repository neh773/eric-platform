package org.oip.backup

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Content hashes for backup identity.
 *
 * Config: SHA-256 over the sorted relative paths and raw file bytes under the config root (hidden files skipped).
 * A restore that writes those bytes back is byte-identical, which is what SPEC §4 requires of the config tree.
 *
 * Runtime DB: SHA-256 of [RuntimeDatabase.canonicalDump], not of the SQLite file. WAL mode rewrites page headers,
 * so the file bytes are not a stable identity; the row dump is.
 *
 * Security: SHA-256 over the audit dump, the raw audit anchor, the secret-blob dump, and the raw secrets
 * key. A missing file is a distinct slot from an empty dump, so "no audit database" does not hash the
 * same as "audit database with zero rows".
 */
object ContentHash {
    fun config(root: Path): String = sha256(canonicalConfig(root))

    fun runtime(dump: String): String = sha256(dump.toByteArray(Charsets.UTF_8))

    fun security(auditDump: String?, anchor: ByteArray?, secretsDump: String?, key: ByteArray?): String {
        val out = java.io.ByteArrayOutputStream()
        fun part(name: String, bytes: ByteArray?) {
            out.write(name.toByteArray(Charsets.UTF_8))
            out.write(0)
            if (bytes == null) out.write("absent".toByteArray(Charsets.UTF_8)) else out.write(bytes)
            out.write(0)
        }
        part("audit", auditDump?.toByteArray(Charsets.UTF_8))
        part("anchor", anchor)
        part("secrets", secretsDump?.toByteArray(Charsets.UTF_8))
        part("key", key)
        return sha256(out.toByteArray())
    }

    fun canonicalConfig(root: Path): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in files(root)) {
            val rel = root.relativize(p).toString().replace('\\', '/')
            out.write(rel.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.write(Files.readAllBytes(p))
            out.write(0)
        }
        return out.toByteArray()
    }

    fun files(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && !it.name.startsWith(".") }
                .sorted(compareBy { root.relativize(it).toString().replace('\\', '/') })
                .toList()
        }
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
