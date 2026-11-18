package org.oip.backup

import org.oip.security.AuditBase
import org.oip.security.AuditRecord
import org.oip.security.AuditStatus
import org.oip.security.SecretsKeyFile
import org.oip.security.SqliteAuditSink
import org.oip.security.SqliteSecretBlobStore
import org.oip.security.StoredBlob
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Base64
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Canonical snapshots of the security files that sit beside the runtime database.
 *
 * [org.oip.security.RuntimeStores.sqlite] keeps the audit log in `db/audit.sqlite` (WAL, synchronous=FULL),
 * encrypted secret blobs in `db/secrets.sqlite` (WAL), and the MAC'd audit head in `db/audit.anchor`
 * (not a row in the audit database). The gateway secrets key is [SecretsKeyFile.path]
 * (`secrets/gateway.key`, directory mode 0700, file mode 0600). Archives store those bytes under the
 * entry name [SECRETS_KEY] (`secrets.key`). It is not part of the config tree. `OIP_SECRETS_KEY` in
 * the environment has no file to archive. `OIP_SECRETS_KEY_FILE` names some other file, which this
 * snapshot does not follow. A stray `secrets.key` in the data directory is not the live key.
 *
 * WAL rewrites page headers, so the SQLite file bytes are not a stable identity. The dumps below are
 * parsed and rebound, the same way [RuntimeDatabase] treats `db/runtime.sql`. The anchor and the key
 * are copied as raw bytes. Restore writes a fresh database and drops any `-wal`/`-shm` sidecar so an
 * old journal cannot replay onto the restored file.
 */
internal object SecuritySnapshot {
    const val AUDIT_SQL = "db/audit.sql"
    const val AUDIT_ANCHOR = "db/audit.anchor"
    const val SECRETS_SQL = "db/secrets.sql"
    const val SECRETS_KEY = "secrets.key"
    const val AUDIT_HEADER = "OIP_AUDIT_V1"
    /** A dump of a log that retention has pruned: the header is followed by a `base` line (SEC-001). */
    const val AUDIT_HEADER_V2 = "OIP_AUDIT_V2"
    const val SECRETS_HEADER = "OIP_SECRETS_V1"

    fun auditDb(dataDir: Path): Path = dataDir.resolve("db/audit.sqlite")
    fun secretsDb(dataDir: Path): Path = dataDir.resolve("db/secrets.sqlite")
    fun anchorFile(dataDir: Path): Path = dataDir.resolve(AUDIT_ANCHOR)
    fun keyFile(dataDir: Path): Path = SecretsKeyFile.path(dataDir)

    /** Canonical audit dump, or null when the database file is absent. */
    fun readAudit(path: Path): String? = if (!path.exists()) null else formatAudit(loadAudit(path))

    /** Canonical secret-blob dump, or null when the database file is absent. */
    fun readSecrets(path: Path): String? = if (!path.exists()) null else formatSecrets(loadSecrets(path))

    fun writeAudit(path: Path, dump: String) {
        val parsed = parseAudit(dump)
        replaceSqlite(path) {
            SqliteAuditSink(path).use { sink ->
                // The base goes in first: it is what lets the kept rows start above seq 1 and still verify.
                parsed.base?.let { sink.prune(it) }
                parsed.records.forEach { sink.append(it) }
            }
        }
    }

    fun writeSecrets(path: Path, dump: String) {
        val rows = parseSecrets(dump)
        replaceSqlite(path) {
            SqliteSecretBlobStore(path).use { store ->
                for (row in rows) {
                    store.upsert(row.name, row.description, row.ciphertext, row.createdMillis)
                    if (row.updatedMillis != row.createdMillis) {
                        store.upsert(row.name, row.description, row.ciphertext, row.updatedMillis)
                    }
                }
            }
        }
    }

    /** Remove the security files and their SQLite sidecars. Missing files are ignored. */
    fun deleteAll(dataDir: Path) {
        for (file in listOf(
            auditDb(dataDir),
            secretsDb(dataDir),
            anchorFile(dataDir),
            keyFile(dataDir),
            SecretsKeyFile.legacyPath(dataDir),
        )) {
            file.deleteIfExists()
            sidecar(file, "-wal").deleteIfExists()
            sidecar(file, "-shm").deleteIfExists()
        }
    }

    /** Rows and the chain base of an audit dump. [base] is null for a log that was never pruned. */
    data class AuditDump(val base: AuditBase?, val records: List<AuditRecord>)

    fun formatAudit(dump: AuditDump): String = formatAudit(dump.records, dump.base)

    /** V1 (no base) when [base] is null, so a never-pruned log keeps the dump bytes and content hash it had. */
    fun formatAudit(records: List<AuditRecord>, base: AuditBase? = null): String = buildString {
        val ordered = records.sortedBy { it.seq }
        if (base == null) {
            append(AUDIT_HEADER).append('\n')
        } else {
            append(AUDIT_HEADER_V2).append('\n')
            append("base\t").append(base.seq).append('\t').append(escape(base.hash)).append('\t')
                .append(base.prunedAtMillis).append('\t').append(escape(base.mac)).append('\n')
        }
        append(ordered.size).append('\n')
        for (r in ordered) {
            append(r.seq).append('\t').append(r.timestampMillis)
            for (field in listOf(r.actor, r.actorHost, r.action, r.target, r.detail, r.status.name, r.origin, r.context, r.prevHash, r.hash)) {
                append('\t').append(escape(field))
            }
            append('\n')
        }
    }

    fun parseAudit(text: String): AuditDump {
        val all = lines(text)
        if (all.isEmpty() || (all[0] != AUDIT_HEADER && all[0] != AUDIT_HEADER_V2)) throw BackupException("audit dump header missing")
        var base: AuditBase? = null
        val lines = if (all[0] == AUDIT_HEADER_V2) {
            if (all.size < 2) throw BackupException("audit dump base line missing")
            val b = fields(all[1], 5, "audit base")
            if (b[0] != "base") throw BackupException("audit dump base line missing")
            base = AuditBase(
                seq = b[1].toLongOrNull() ?: throw BackupException("audit dump base seq is not an integer"),
                hash = unescape(b[2]),
                prunedAtMillis = b[3].toLongOrNull() ?: throw BackupException("audit dump base time is not an integer"),
                mac = unescape(b[4]),
            )
            listOf(all[0]) + all.drop(2)
        } else all
        if (lines.size < 2) throw BackupException("audit dump header missing")
        val n = lines[1].toIntOrNull() ?: throw BackupException("audit dump count is not an integer")
        val rows = lines.drop(2)
        if (rows.size != n) throw BackupException("audit dump has ${rows.size} rows, header says $n")
        val seen = hashSetOf<Long>()
        return AuditDump(base, rows.map { line ->
            val p = fields(line, 12, "audit")
            val seq = p[0].toLongOrNull() ?: throw BackupException("audit dump seq is not an integer")
            if (!seen.add(seq)) throw BackupException("duplicate audit seq $seq")
            val ts = p[1].toLongOrNull() ?: throw BackupException("audit dump timestamp is not an integer")
            val status = try {
                AuditStatus.valueOf(p[7])
            } catch (_: IllegalArgumentException) {
                throw BackupException("audit dump status is not SUCCESS or FAILURE")
            }
            AuditRecord(
                seq = seq,
                timestampMillis = ts,
                actor = unescape(p[2]),
                actorHost = unescape(p[3]),
                action = unescape(p[4]),
                target = unescape(p[5]),
                detail = unescape(p[6]),
                status = status,
                origin = unescape(p[8]),
                context = unescape(p[9]),
                prevHash = unescape(p[10]),
                hash = unescape(p[11]),
            )
        })
    }

    fun formatSecrets(rows: List<StoredBlob>): String = buildString {
        val ordered = rows.sortedBy { it.name }
        append(SECRETS_HEADER).append('\n')
        append(ordered.size).append('\n')
        for (row in ordered) {
            append(escape(row.name)).append('\t')
            append(escape(row.description)).append('\t')
            append(Base64.getEncoder().encodeToString(row.ciphertext)).append('\t')
            append(row.createdMillis).append('\t')
            append(row.updatedMillis).append('\n')
        }
    }

    fun parseSecrets(text: String): List<StoredBlob> {
        val lines = lines(text)
        if (lines.size < 2 || lines[0] != SECRETS_HEADER) throw BackupException("secrets dump header missing")
        val n = lines[1].toIntOrNull() ?: throw BackupException("secrets dump count is not an integer")
        val rows = lines.drop(2)
        if (rows.size != n) throw BackupException("secrets dump has ${rows.size} rows, header says $n")
        val seen = hashSetOf<String>()
        return rows.map { line ->
            val p = fields(line, 5, "secrets")
            val name = unescape(p[0])
            if (!seen.add(name)) throw BackupException("duplicate secret name in dump")
            val created = p[3].toLongOrNull() ?: throw BackupException("secrets dump created time is not an integer")
            val updated = p[4].toLongOrNull() ?: throw BackupException("secrets dump updated time is not an integer")
            val cipher = try {
                Base64.getDecoder().decode(p[2])
            } catch (_: IllegalArgumentException) {
                throw BackupException("secrets dump ciphertext is not base64")
            }
            StoredBlob(name, unescape(p[1]), cipher, created, updated)
        }
    }

    private fun loadAudit(path: Path): AuditDump = try {
        SqliteAuditSink(path).use { AuditDump(it.base(), it.readAll()) }
    } catch (e: BackupException) {
        throw e
    } catch (e: Exception) {
        throw BackupException("could not read audit database: ${e.message}")
    }

    private fun loadSecrets(path: Path): List<StoredBlob> = try {
        SqliteSecretBlobStore(path).use { it.list() }
    } catch (e: BackupException) {
        throw e
    } catch (e: Exception) {
        throw BackupException("could not read secrets database: ${e.message}")
    }

    private fun replaceSqlite(path: Path, write: () -> Unit) {
        path.parent?.let { Files.createDirectories(it) }
        path.deleteIfExists()
        sidecar(path, "-wal").deleteIfExists()
        sidecar(path, "-shm").deleteIfExists()
        try {
            write()
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("could not write ${path.fileName}: ${e.message}")
        }
        checkpoint(path)
    }

    /** Truncate the WAL so the main file is a complete snapshot, then drop the sidecars. */
    private fun checkpoint(file: Path) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                if (st.execute("PRAGMA wal_checkpoint(TRUNCATE)")) st.resultSet?.close()
            }
        }
        sidecar(file, "-wal").deleteIfExists()
        sidecar(file, "-shm").deleteIfExists()
    }

    private fun sidecar(file: Path, suffix: String): Path = file.resolveSibling("${file.fileName}$suffix")

    private fun lines(text: String): List<String> {
        val raw = text.split('\n')
        return if (raw.lastOrNull().isNullOrEmpty() && raw.size > 1) raw.dropLast(1) else raw
    }

    private fun fields(line: String, count: Int, what: String): List<String> {
        val parts = line.split('\t')
        if (parts.size != count) throw BackupException("$what dump row has ${parts.size} fields, expected $count")
        return parts
    }

    private fun escape(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> throw BackupException("bad escape in security dump")
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
