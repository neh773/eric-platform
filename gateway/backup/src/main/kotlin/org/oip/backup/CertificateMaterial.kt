@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.oip.backup

import org.oip.security.SecretKeyLoader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Web-server (`https/`) and OPC UA (`opcua/`) certificate material inside a gateway backup.
 *
 * The backup page lists certificates among the things a gateway backup carries
 * (-), and the
 * secure-communication page describes the web-server key pair the gateway keeps
 * (-).
 * Only public material is stored as-is (`certs/<path>`). Every other file in those directories — private
 * keys, key stores, store passwords, and anything with a PEM private-key marker — is sealed with AES-256-GCM
 * (`sealed/<path>`). The sealing key is an HKDF-SHA256 subkey of the gateway secrets key, so no file in the
 * archive holds a private key in the clear. The archive path is bound as GCM associated data, so a sealed
 * blob cannot be moved onto another path.
 *
 * The manifest records [keyCheck] so a restore can tell, before it changes anything, whether the key it
 * has is the one the archive was sealed under. `https/incoming/` and `https/rejected/` are transient drop
 * areas and are not archived.
 */
internal object CertificateMaterial {
    val DIRS = listOf("https", "opcua")
    const val PLAIN_PREFIX = "certs/"
    const val SEALED_PREFIX = "sealed/"
    private const val SEAL_INFO = "oip-backup-certificate-keys"
    private const val CHECK_INFO = "oip-backup-key-check"
    private const val VERSION: Byte = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    /** Transient drop areas: not archived, recreated empty by a restore that replaces `https/`. */
    val DROP_DIRS = listOf("https/incoming", "https/rejected")
    private val SKIPPED = DROP_DIRS.map { "$it/" }
    private val PUBLIC_EXTENSIONS = setOf("crt", "cer", "der", "crl", "csr")
    private val PUBLIC_NAMES = setOf("server.source", "tls.json")
    private val random = SecureRandom()

    /** One archived file. [sealed] files are ciphertext in the archive and plaintext on disk. */
    data class Entry(val rel: String, val bytes: ByteArray, val sealed: Boolean)

    /**
     * Files under [DIRS], sorted, with the transient drop areas and temporary files left out. Symbolic links
     * are not followed (a link, or a linked top-level directory, is skipped), so a link cannot pull a file
     * from outside the data directory into the archive.
     */
    fun collect(dataDir: Path): List<Entry> {
        val out = ArrayList<Entry>()
        for (dir in DIRS) {
            val root = dataDir.resolve(dir)
            if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) continue
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }.sorted().forEach { file ->
                    val rel = dataDir.relativize(file).toString().replace('\\', '/')
                    if (SKIPPED.any { rel.startsWith(it) }) return@forEach
                    val name = file.fileName.toString()
                    if (name.startsWith(".") || name.contains(".tmp-")) return@forEach
                    val bytes = Files.readAllBytes(file)
                    out += Entry(rel, bytes, mustSeal(rel, bytes))
                }
            }
        }
        return out
    }

    /**
     * Anything that is not plainly a certificate is sealed. A file with a public-looking name still gets
     * sealed when it carries a PEM private-key marker.
     */
    fun mustSeal(rel: String, bytes: ByteArray): Boolean {
        val name = rel.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val public = name in PUBLIC_NAMES || ext in PUBLIC_EXTENSIONS
        return !public || containsPrivateMarker(bytes)
    }

    fun containsPrivateMarker(bytes: ByteArray): Boolean =
        String(bytes, Charsets.ISO_8859_1).contains("PRIVATE KEY")

    /** Zip entries for [entries]. A sealed entry with no [key] is refused rather than written in the clear. */
    fun toArchive(entries: List<Entry>, key: SecretKey?): List<Pair<String, ByteArray>> = entries.map { e ->
        if (e.sealed) {
            val k = key ?: throw BackupException("no secrets key to seal ${e.rel}")
            SEALED_PREFIX + e.rel to seal(k, SEALED_PREFIX + e.rel, e.bytes)
        } else {
            PLAIN_PREFIX + e.rel to e.bytes
        }
    }

    /** True for a zip entry name this class owns. */
    fun owns(name: String): Boolean = name.startsWith(PLAIN_PREFIX) || name.startsWith(SEALED_PREFIX)

    /**
     * Decode the certificate entries of an archive. [candidates] are tried in order and the first one whose
     * [keyCheck] matches [expectedCheck] opens the sealed entries. Nothing on disk changes here.
     */
    fun fromArchive(
        entries: Map<String, ByteArray>,
        expectedCheck: String?,
        candidates: List<() -> SecretKey?>,
    ): List<Entry> {
        val names = entries.keys.filter { owns(it) }
        if (names.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        val sealedNames = names.filter { it.startsWith(SEALED_PREFIX) }
        val key = if (sealedNames.isEmpty()) null else {
            if (expectedCheck == null) throw BackupException("archive has sealed certificate keys but no key check")
            candidates.asSequence()
                .mapNotNull { runCatching { it() }.getOrNull() }
                .firstOrNull { MessageDigest.isEqual(keyCheck(it).toByteArray(), expectedCheck.toByteArray()) }
                ?: throw BackupException(
                    "restore refused: the certificate keys in this archive were sealed under a different secrets key",
                )
        }
        for (name in names.sorted()) {
            val sealed = name.startsWith(SEALED_PREFIX)
            val rel = name.removePrefix(if (sealed) SEALED_PREFIX else PLAIN_PREFIX)
            checkRel(rel)
            val bytes = entries.getValue(name)
            if (sealed) {
                out += Entry(rel, open(key!!, name, bytes), true)
            } else {
                if (mustSeal(rel, bytes)) throw BackupException("archive stores certificate key material in the clear: $rel")
                out += Entry(rel, bytes, false)
            }
        }
        return out
    }

    /**
     * Public files that are useless, and break the next start, without their private half. The web
     * listener and the OPC UA server both refuse a `server.crt` with no `server.key`, and the OPC UA key
     * store cannot be opened without `store.pass`.
     */
    private val KEY_PAIRS = mapOf(
        "https/server.crt" to "https/server.key",
        "https/pending.csr" to "https/pending.key",
        "opcua/server.crt" to "opcua/server.key",
        "opcua/server.pfx" to "opcua/store.pass",
    )

    /**
     * The [DIRS] whose entries hold a certificate (or key store) without its private half, for example an
     * archive made while no secrets key was available, which holds only `certs/`. Restoring such a
     * directory would delete the live key, so restore keeps the live directory instead.
     */
    fun incompleteDirs(entries: List<Entry>): List<String> {
        val present = entries.map { it.rel }.toSet()
        return DIRS.filter { dir ->
            KEY_PAIRS.any { (public, private) -> public.startsWith("$dir/") && public in present && private !in present }
        }
    }

    /** Which of [DIRS] an entry set covers. Restore replaces exactly those directories. */
    fun dirsOf(entries: List<Entry>): List<String> = DIRS.filter { d -> entries.any { it.rel.startsWith("$d/") } }

    /** Short public fingerprint of the sealing key. It reveals nothing about the key itself. */
    fun keyCheck(key: SecretKey): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(subkey(key, CHECK_INFO), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(CHECK_INFO.toByteArray(Charsets.UTF_8))).take(32)
    }

    /** The secrets key file bytes (archive entry `secrets.key`, on disk `secrets/gateway.key`): 32 raw bytes, hex, or base64. */
    fun keyFromFileBytes(bytes: ByteArray): SecretKey =
        SecretKeyLoader.fromBytes(if (bytes.size == 32) bytes else SecretKeyLoader.decode(bytes.toString(Charsets.US_ASCII)))

    fun seal(key: SecretKey, aad: String, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey(key, SEAL_INFO), "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plaintext)
    }

    fun open(key: SecretKey, aad: String, blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_LEN + TAG_BITS / 8 || blob[0] != VERSION) {
            throw BackupException("sealed certificate entry has an unknown format")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(subkey(key, SEAL_INFO), "AES"),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(1, 1 + IV_LEN)),
            )
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            cipher.doFinal(blob.copyOfRange(1 + IV_LEN, blob.size))
        } catch (_: Exception) {
            throw BackupException("sealed certificate entry could not be opened")
        }
    }

    private fun checkRel(rel: String) {
        ZipIo.safeName(rel)
        if (DIRS.none { rel.startsWith("$it/") }) throw BackupException("certificate entry outside https/ or opcua/")
        if (SKIPPED.any { rel.startsWith(it) }) throw BackupException("certificate entry in a transient directory")
    }

    /** RFC 5869 HKDF-SHA256 with an all-zero salt and one 32-byte output block. */
    private fun subkey(key: SecretKey, info: String): ByteArray {
        val ikm = key.encoded ?: throw BackupException("secrets key is not extractable")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray(Charsets.UTF_8))
        mac.update(1)
        return mac.doFinal()
    }
}
