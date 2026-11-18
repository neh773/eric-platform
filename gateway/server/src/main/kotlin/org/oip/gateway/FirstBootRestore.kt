package org.oip.gateway

import org.oip.backup.BackupCipher
import org.oip.backup.BackupService
import org.oip.config.FileConfigStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Automatic restore of a backup on first boot, for container recipes. Doc source: FEATURES GAT-009.
 *
 * When `OIP_RESTORE_FROM` names a backup file (plain or encrypted, as made by the backup API) and this data directory
 * has never been restored and holds no configuration yet, the gateway restores that file before its modules start.
 * An encrypted file needs `OIP_RESTORE_PASSPHRASE` or `OIP_RESTORE_PASSPHRASE_FILE`; the passphrase is never logged.
 * Afterwards `restored-from-backup.json` in the data directory records the file name and time, so a restart, or a
 * later change of the variable, never restores again over live data. A missing or unreadable file stops the start
 * with a clear message instead of booting an empty gateway that looks restored.
 */
class FirstBootRestore(
    private val dataDir: Path,
    private val store: FileConfigStore,
    private val backups: BackupService,
    private val env: Map<String, String>,
    private val userNames: () -> List<String>,
    private val log: (String, Map<String, Any?>) -> Unit,
) {
    sealed interface Outcome {
        data object NotRequested : Outcome
        data class Skipped(val reason: String) : Outcome
        data class Restored(val file: String) : Outcome
    }

    private val marker: Path get() = dataDir.resolve(MARKER)

    fun run(): Outcome {
        val from = env["OIP_RESTORE_FROM"]?.trim()?.takeIf { it.isNotEmpty() } ?: return Outcome.NotRequested
        if (marker.exists()) return Outcome.Skipped("already restored")
        if (!fresh()) {
            log("first-boot restore skipped: the data directory already holds configuration", mapOf("file" to Path.of(from).fileName.toString()))
            return Outcome.Skipped("data directory is not empty")
        }
        val file = Path.of(from)
        check(Files.isRegularFile(file)) { "OIP_RESTORE_FROM does not name a readable file: ${file.fileName}" }
        val bytes = file.readBytes()
        // The services attached when the gateway was built and hold the empty configuration; after the restore they
        // are told what changed, as a live restore does, so the restored connections and tags run on this first boot.
        val before = store.list().associate { it.id to it.resource }
        if (BackupCipher.isEncrypted(bytes)) {
            val pass = passphrase() ?: throw IllegalStateException(
                "the backup is encrypted: set OIP_RESTORE_PASSPHRASE or OIP_RESTORE_PASSPHRASE_FILE",
            )
            try {
                backups.restoreEncrypted(bytes, pass)
            } finally {
                pass.fill('\u0000')
            }
        } else {
            backups.restore(bytes)
        }
        store.announceReplaced(before)
        val name = file.fileName.toString()
        marker.writeText("""{"file":${Json.write(name)},"restoredAt":"${Instant.now()}"}""" + "\n")
        log("first-boot restore completed", mapOf("file" to name))
        return Outcome.Restored(name)
    }

    /**
     * Fresh means no configuration of any module, the built-in security and module defaults untouched (version 1), and no
     * users except the `admin` that OIP_ADMIN_PASSWORD seeds on this same start.
     */
    private fun fresh(): Boolean {
        val edited = store.list().any {
            val seeded = it.id.module == SECURITY_MODULE || (it.id.kind == "ModuleConfig" && it.id.name == "default")
            !seeded || it.resource.metadata.version > 1
        }
        val seeded = if (env["OIP_ADMIN_PASSWORD"].isNullOrEmpty()) emptyList() else listOf("admin")
        return !edited && userNames().all { it in seeded }
    }

    private fun passphrase(): CharArray? {
        env["OIP_RESTORE_PASSPHRASE"]?.takeIf { it.isNotEmpty() }?.let { return it.toCharArray() }
        val path = env["OIP_RESTORE_PASSPHRASE_FILE"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val file = Path.of(path)
        check(file.exists()) { "OIP_RESTORE_PASSPHRASE_FILE does not name a readable file" }
        return file.readText().trimEnd('\n', '\r').toCharArray()
    }

    companion object {
        private const val SECURITY_MODULE = "org.oip.security"
        const val MARKER = "restored-from-backup.json"
    }
}
