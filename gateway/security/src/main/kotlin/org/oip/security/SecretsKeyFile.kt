package org.oip.security

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import javax.crypto.SecretKey

/**
 * On-disk gateway secrets key: `<dataDir>/secrets/gateway.key`.
 *
 * The directory is mode 0700 and the file is mode 0600. Backup archives store those bytes under the
 * entry name `secrets.key`. [SecretKeyLoader.ENV_KEY] and [SecretKeyLoader.ENV_FILE] override this
 * file. A stray `<dataDir>/secrets.key` is moved here when the real file is absent. An existing key
 * file is never overwritten, and the key bytes are never logged.
 */
object SecretsKeyFile {
    const val DIRECTORY = "secrets"
    const val FILE_NAME = "gateway.key"
    val FILE_MODE: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )
    val DIRECTORY_MODE: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    fun path(dataDir: Path): Path = dataDir.resolve(DIRECTORY).resolve(FILE_NAME)

    /** Mistaken layout that used the backup entry name as a file in the data directory. */
    fun legacyPath(dataDir: Path): Path = dataDir.resolve("secrets.key")

    /**
     * Key for this data directory. An inline [SecretKeyLoader.ENV_KEY] wins over
     * [SecretKeyLoader.ENV_FILE]. When neither is set, [migrate] runs and [path] is loaded or created.
     */
    fun load(
        dataDir: Path,
        env: Map<String, String> = System.getenv(),
        createFileIfMissing: Boolean = true,
    ): SecretKey {
        if (env.containsKey(SecretKeyLoader.ENV_KEY) || env.containsKey(SecretKeyLoader.ENV_FILE)) {
            return SecretKeyLoader.load(env, createFileIfMissing)
        }
        migrate(dataDir)
        val file = path(dataDir)
        prepareDirectory(dataDir)
        val key = SecretKeyLoader.loadFile(file, createFileIfMissing)
        if (Files.isRegularFile(file)) setMode(file, FILE_MODE)
        return key
    }

    /** Move [legacyPath] onto [path] when the real file is absent. Does not overwrite a key. */
    fun migrate(dataDir: Path) {
        val legacy = legacyPath(dataDir)
        if (!Files.isRegularFile(legacy)) return
        val dest = path(dataDir)
        if (Files.exists(dest)) return
        prepareDirectory(dataDir)
        try {
            Files.move(legacy, dest, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            return
        }
        setMode(dest, FILE_MODE)
    }

    /** Directory mode 0700 and, when the key file is present, file mode 0600. */
    fun restrict(dataDir: Path) {
        prepareDirectory(dataDir)
        val file = path(dataDir)
        if (Files.isRegularFile(file)) setMode(file, FILE_MODE)
    }

    private fun prepareDirectory(dataDir: Path) {
        val dir = dataDir.resolve(DIRECTORY)
        Files.createDirectories(dir)
        setMode(dir, DIRECTORY_MODE)
    }

    private fun setMode(path: Path, mode: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, mode)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems have no mode bits.
        }
    }
}
