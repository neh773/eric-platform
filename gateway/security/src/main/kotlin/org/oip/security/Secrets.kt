package org.oip.security

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKey

/**
 * AES-256-GCM secrets. The key comes from the environment or a key file, never from the config tree.
 *
 * `OIP_SECRETS_KEY` is the key itself (base64 or 64 hex characters). `OIP_SECRETS_KEY_FILE` is a path.
 * A missing key file is created when [createFileIfMissing] is true. The file is opened mode 0600 and the key
 * bytes are written only after that mode is set, then linked into place, so the key is never stored in a
 * world-readable file. An existing file is never overwritten.
 * Each value uses a fresh 12-byte IV and binds the secret name as additional authenticated data, so ciphertext
 * cannot be swapped between names.
 *
 * Config fields hold references of the form `secret://<name>` (SPEC: format `secret` is a reference, not the value).
 */
fun interface SecretKeySource {
    fun key(): SecretKey
}

object SecretKeyLoader {
    const val ENV_KEY = "OIP_SECRETS_KEY"
    const val ENV_FILE = "OIP_SECRETS_KEY_FILE"
    private val random = SecureRandom()

    fun fromBytes(raw: ByteArray): SecretKey {
        if (raw.size != 32) throw SecretKeyException("secrets key must be 32 bytes, got ${raw.size}")
        return SecretKeySpec(raw.copyOf(), "AES")
    }

    fun decode(text: String): ByteArray {
        val t = text.trim()
        if (t.matches(Regex("^[0-9a-fA-F]{64}$"))) return Hex.decode(t)
        val decoded = try {
            Base64.getMimeDecoder().decode(t)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(t)
            } catch (_: IllegalArgumentException) {
                throw SecretKeyException("secrets key must be 32 raw bytes, 64 hex characters, or base64")
            }
        }
        if (decoded.size != 32) throw SecretKeyException("secrets key must be 32 bytes, got ${decoded.size}")
        return decoded
    }

    /**
     * Resolve the key from [env]. The inline key wins over the file. When the file is absent and
     * [createFileIfMissing] is set, a new 32-byte key is written as base64.
     */
    fun load(env: Map<String, String> = System.getenv(), createFileIfMissing: Boolean = true): SecretKey {
        env[ENV_KEY]?.let { return fromBytes(decode(it)) }
        val file = env[ENV_FILE] ?: throw SecretKeyException("set $ENV_KEY or $ENV_FILE")
        return loadFile(Path.of(file), createFileIfMissing)
    }

    fun loadFile(path: Path, createFileIfMissing: Boolean = true): SecretKey {
        if (Files.exists(path)) return fromBytes(readFile(path))
        if (!createFileIfMissing) throw SecretKeyException("key file $path does not exist")
        val raw = ByteArray(32).also { random.nextBytes(it) }
        writeNew(path, raw)
        return fromBytes(raw)
    }

    private fun readFile(path: Path): ByteArray {
        val bytes = Files.readAllBytes(path)
        if (bytes.size == 32) return bytes
        return decode(bytes.toString(Charsets.US_ASCII))
    }

    private fun writeNew(path: Path, raw: ByteArray) {
        if (Files.exists(path)) throw SecretKeyException("refusing to overwrite $path")
        val text = (Base64.getEncoder().encodeToString(raw) + "\n").toByteArray(Charsets.US_ASCII)
        try {
            SecureFiles.createExclusive(path, text)
        } catch (_: FileAlreadyExistsException) {
            throw SecretKeyException("refusing to overwrite $path")
        }
    }
}

/**
 * Files that hold key material or the audit head. They are created mode 0600 before any secret bytes
 * are written, and published by a same-directory link or atomic rename. The parent directory is fsynced
 * after that name is published so the directory entry itself survives a crash.
 */
internal object SecureFiles {
    private val OWNER_RW = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    /** Create [path] only if it does not exist. The inode is owner-read/write before [bytes] are written. */
    fun createExclusive(path: Path, bytes: ByteArray) {
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val tmp = parent.resolve(".${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            writePrivate(tmp, bytes)
            try {
                Files.createLink(path, tmp)
            } catch (_: UnsupportedOperationException) {
                if (Files.exists(path)) throw FileAlreadyExistsException(path.toString())
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE)
            }
            // Make the new name durable before dropping the temporary name. A crash after this
            // point can leave the temp name behind; it cannot lose the only name of the inode.
            fsyncDirectory(parent)
        } finally {
            Files.deleteIfExists(tmp)
        }
        fsyncDirectory(parent)
    }

    /** Replace [path]. The temporary inode is mode 0600 before it is renamed over the destination. */
    fun replace(path: Path, bytes: ByteArray) {
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val tmp = parent.resolve(".${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            writePrivate(tmp, bytes)
            Files.move(
                tmp,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            fsyncDirectory(parent)
        } finally {
            Files.deleteIfExists(tmp)
        }
        fsyncDirectory(parent)
    }

    /**
     * Flush the directory entry created by a rename or hard link. Opening a directory and calling
     * [FileChannel.force] works on Linux. Platforms that refuse it are skipped; the file contents
     * were already fsynced.
     */
    internal fun fsyncDirectory(dir: Path) {
        // Linux can fsync a directory through a read channel. Windows refuses that open
        // with AccessDeniedException whose message is the path, not the word "directory".
        if (!Files.isDirectory(dir)) return
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: UnsupportedOperationException) {
        } catch (_: AccessDeniedException) {
        } catch (e: IOException) {
            val message = e.message ?: ""
            if (message.contains("directory", ignoreCase = true)) return
            throw e
        }
    }

    private fun writePrivate(path: Path, bytes: ByteArray) {
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val channel = try {
            FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(OWNER_RW))
        } catch (_: UnsupportedOperationException) {
            FileChannel.open(path, options)
        }
        channel.use { ch ->
            try {
                Files.setPosixFilePermissions(path, OWNER_RW)
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystems have no mode bits. The bytes are still unpublished until rename.
            }
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
    }
}

object SecretReferences {
    const val PREFIX = "secret://"
    private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    fun validName(name: String) = NAME.matches(name)

    fun format(name: String): String {
        require(validName(name)) { "invalid secret name '$name'" }
        return PREFIX + name
    }

    fun parse(reference: String): String {
        if (!reference.startsWith(PREFIX)) throw SecretException("not a secret reference '$reference'")
        val name = reference.removePrefix(PREFIX)
        if (!validName(name)) throw SecretException("invalid secret reference '$reference'")
        return name
    }

    fun isReference(value: String) = value.startsWith(PREFIX) && validName(value.removePrefix(PREFIX))

    /** JSON Schema fragment for a config field that stores a reference. `format` is omitted; networknt rejects unknown formats. */
    fun schemaProperty(description: String): Map<String, Any?> = mapOf(
        "type" to "string",
        "pattern" to "^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
        "description" to description,
    )

    /**
     * Deep-copy [spec], replacing every `secret://` string with the UTF-8 plaintext from [resolve].
     * Non-reference strings are left unchanged. This is what the config store's callers use at read time;
     * the file itself keeps the reference.
     */
    fun resolveSpec(spec: Map<String, Any?>, resolve: (String) -> String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return walk(spec, resolve) as Map<String, Any?>
    }

    fun find(spec: Map<String, Any?>): List<String> {
        val out = mutableListOf<String>()
        fun rec(v: Any?) {
            when (v) {
                is Map<*, *> -> v.values.forEach { rec(it) }
                is List<*> -> v.forEach { rec(it) }
                is String -> if (isReference(v)) out += v
            }
        }
        rec(spec)
        return out
    }

    private fun walk(v: Any?, resolve: (String) -> String): Any? = when (v) {
        is Map<*, *> -> v.entries.associateTo(linkedMapOf()) { it.key.toString() to walk(it.value, resolve) }
        is List<*> -> v.map { walk(it, resolve) }
        is String -> if (isReference(v)) resolve(v) else v
        else -> v
    }
}

data class SecretInfo(val name: String, val description: String, val createdAt: Instant, val updatedAt: Instant, val reference: String)

interface SecretStore {
    fun put(name: String, plaintext: ByteArray, description: String = ""): String
    fun putText(name: String, plaintext: String, description: String = ""): String =
        put(name, plaintext.toByteArray(Charsets.UTF_8), description)
    fun get(name: String): ByteArray
    fun getText(name: String): String = get(name).toString(Charsets.UTF_8)
    fun getReference(reference: String): ByteArray = get(SecretReferences.parse(reference))
    fun delete(name: String)
    fun list(): List<SecretInfo>
}

/** Ciphertext persistence. The encrypted store is the only writer of these bytes. */
interface SecretBlobStore : AutoCloseable {
    /** Drop the file handle so a restore can rename the database. No-op for in-memory stores. */
    fun release() {}
    /** Open the backing file again after a restore renamed it. No-op for in-memory stores. */
    fun reopen() {}
    fun upsert(name: String, description: String, ciphertext: ByteArray, nowMillis: Long)
    fun read(name: String): StoredBlob?
    fun delete(name: String): Boolean
    fun list(): List<StoredBlob>
    override fun close() {}
}

data class StoredBlob(
    val name: String,
    val description: String,
    val ciphertext: ByteArray,
    val createdMillis: Long,
    val updatedMillis: Long,
)

class MemorySecretBlobStore : SecretBlobStore {
    private val rows = linkedMapOf<String, StoredBlob>()
    override fun upsert(name: String, description: String, ciphertext: ByteArray, nowMillis: Long) {
        val prev = rows[name]
        rows[name] = StoredBlob(name, description, ciphertext.copyOf(), prev?.createdMillis ?: nowMillis, nowMillis)
    }
    override fun read(name: String): StoredBlob? = rows[name]
    override fun delete(name: String) = rows.remove(name) != null
    override fun list(): List<StoredBlob> = rows.values.map { it.copy(ciphertext = it.ciphertext.copyOf()) }
}

class SqliteSecretBlobStore(val path: Path) : SecretBlobStore {
    private var connection: Connection

    init {
        Files.createDirectories(path.parent)
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.autoCommit = true
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS secret (
                  name TEXT PRIMARY KEY,
                  description TEXT NOT NULL,
                  ciphertext BLOB NOT NULL,
                  created_millis INTEGER NOT NULL,
                  updated_millis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    override fun upsert(name: String, description: String, ciphertext: ByteArray, nowMillis: Long) {
        val prev = read(name)
        connection.prepareStatement(
            """
            INSERT INTO secret(name, description, ciphertext, created_millis, updated_millis) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET description = excluded.description, ciphertext = excluded.ciphertext, updated_millis = excluded.updated_millis
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, name)
            ps.setString(2, description)
            ps.setBytes(3, ciphertext)
            ps.setLong(4, prev?.createdMillis ?: nowMillis)
            ps.setLong(5, nowMillis)
            ps.executeUpdate()
        }
    }

    override fun read(name: String): StoredBlob? {
        connection.prepareStatement("SELECT * FROM secret WHERE name = ?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return StoredBlob(rs.getString("name"), rs.getString("description"), rs.getBytes("ciphertext"),
                    rs.getLong("created_millis"), rs.getLong("updated_millis"))
            }
        }
    }

    override fun delete(name: String): Boolean {
        connection.prepareStatement("DELETE FROM secret WHERE name = ?").use { ps ->
            ps.setString(1, name)
            return ps.executeUpdate() > 0
        }
    }

    override fun list(): List<StoredBlob> {
        connection.prepareStatement("SELECT * FROM secret ORDER BY name").use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<StoredBlob>()
                while (rs.next()) out += StoredBlob(rs.getString("name"), rs.getString("description"), rs.getBytes("ciphertext"),
                    rs.getLong("created_millis"), rs.getLong("updated_millis"))
                return out
            }
        }
    }

    override fun release() {
        if (!connection.isClosed) {
            runCatching { connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
            connection.close()
        }
    }

    override fun reopen() {
        release()
        connection = open()
    }

    private fun open(): Connection {
        Files.createDirectories(path.parent)
        Class.forName("org.sqlite.JDBC")
        val opened = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        opened.autoCommit = true
        opened.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
        }
        return opened
    }

    override fun close() {
        if (!connection.isClosed) connection.close()
    }
}

class EncryptedSecretStore(
    private val blobs: SecretBlobStore,
    private val key: SecretKey,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) : SecretStore {
    private val lock = Any()
    private val random = SecureRandom()

    override fun put(name: String, plaintext: ByteArray, description: String): String = synchronized(lock) {
        if (!SecretReferences.validName(name)) throw SecretException("invalid secret name '$name'")
        if (plaintext.isEmpty()) throw SecretException("secret value must not be empty")
        if (plaintext.size > MAX_BYTES) throw SecretException("secret value exceeds $MAX_BYTES bytes")
        val now = clock.millis()
        val keys = SecretKeyRing.load(blobs, key)
        blobs.upsert(name, description, if (keys == null) encrypt(name, plaintext) else SecretKeyRing.encrypt(keys, name, plaintext), now)
        SecretReferences.format(name)
    }

    override fun get(name: String): ByteArray = synchronized(lock) {
        if (name == SecretKeyRing.ROW) throw NotFoundException("secret", name)
        val blob = blobs.read(name) ?: throw NotFoundException("secret", name)
        try {
            if (blob.ciphertext.isNotEmpty() && blob.ciphertext[0] == SecretKeyRing.VERSION_WRAPPED) {
                val keys = SecretKeyRing.load(blobs, key) ?: throw SecretException("secret '$name' needs a key hierarchy that is missing")
                SecretKeyRing.decrypt(keys, name, blob.ciphertext)
            } else decrypt(name, blob.ciphertext)
        } catch (e: SecretException) {
            throw e
        } catch (e: Exception) {
            throw SecretException("secret '$name' could not be decrypted", e)
        }
    }

    /**
     * Rotate the root key, the key-encryption key, or both, and re-wrap every stored secret (SEC-031). The first
     * rotation of a legacy store also moves its secrets onto per-secret data keys. See [SecretKeyRing].
     */
    fun rotateKeys(rotateRoot: Boolean = true, rotateKek: Boolean = true): SecretKeyRotation = synchronized(lock) {
        val r = SecretKeyRing.rotate(blobs, key, { n, b -> decrypt(n, b) }, rotateRoot, rotateKek, clock.millis())
        SecretKeyRotation(r.secrets, r.migratedFromLegacy, r.rootVersion, r.kekVersion)
    }

    override fun delete(name: String) {
        synchronized(lock) {
            if (name == SecretKeyRing.ROW || !blobs.delete(name)) throw NotFoundException("secret", name)
        }
    }

    override fun list(): List<SecretInfo> = synchronized(lock) {
        blobs.list().filter { it.name != SecretKeyRing.ROW }.map {
            SecretInfo(it.name, it.description, Instant.ofEpochMilli(it.createdMillis), Instant.ofEpochMilli(it.updatedMillis),
                SecretReferences.format(it.name))
        }
    }

    internal fun encrypt(name: String, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext)
        return byteArrayOf(VERSION) + iv + ct
    }

    internal fun decrypt(name: String, blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_LEN + TAG_BITS / 8 || blob[0] != VERSION) throw SecretException("secret '$name' has an unknown ciphertext format")
        val iv = blob.copyOfRange(1, 1 + IV_LEN)
        val ct = blob.copyOfRange(1 + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ct)
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val VERSION: Byte = 1
    }
}

data class RuntimeStores(
    val audit: AuditSink,
    val secrets: SecretBlobStore,
    /** Head checkpoint for [audit]. Not a table inside the audit database. */
    val auditAnchor: AuditAnchorStore = MemoryAuditAnchorStore(),
) : AutoCloseable {
    override fun close() {
        runCatching { audit.close() }
        runCatching { secrets.close() }
    }

    companion object {
        fun sqlite(dataDir: Path): RuntimeStores {
            val db = dataDir.resolve("db")
            return RuntimeStores(
                SqliteAuditSink(db.resolve("audit.sqlite")),
                SqliteSecretBlobStore(db.resolve("secrets.sqlite")),
                FileAuditAnchorStore(db.resolve("audit.anchor")),
            )
        }

        fun memory(): RuntimeStores = RuntimeStores(MemoryAuditSink(), MemorySecretBlobStore(), MemoryAuditAnchorStore())
    }
}
