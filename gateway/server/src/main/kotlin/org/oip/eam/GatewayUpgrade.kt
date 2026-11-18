package org.oip.eam

import org.oip.gateway.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Applying a staged gateway upgrade (EAM-011, DIFFERENCES 503) and undoing it when the new version does not come up.
 *
 * Install layout, owned by the launcher script `packaging/launcher/oip-gateway.sh`:
 * `<install>/current` is a one-line text file naming the active version; `<install>/versions/<version>/` holds each
 * unpacked version, with its start script at `bin/oip-gateway`. The launcher runs whatever `current` names.
 *
 * State on the data directory, under `upgrade/`: `staged.bin` and `staged.json` (written by the stage step),
 * `pending.json` (old version, new version, deadline) while an applied upgrade has not proved itself, and
 * `result.json` (the last outcome, reported to the controller in the agent inventory).
 *
 * Apply unpacks the package, writes the pending marker, flips `current`, and asks the process to exit with
 * [EXIT_RESTART_FOR_UPGRADE] so the service manager starts it again on the new version. On boot with a marker
 * the gateway calls [onBoot]: past the deadline it rolls back; otherwise it calls [markHealthy] once it is up. The
 * launcher does the same rollback in shell when the new version dies before it gets that far.
 */
class GatewayUpgrade(private val installRoot: Path?, dataDir: Path, private val clock: () -> Long = System::currentTimeMillis) {
    private val dir: Path = dataDir.resolve("upgrade")

    sealed interface Boot {
        data object Nothing : Boot
        /** A marker is open and the deadline has not passed: call [markHealthy] when up. */
        data class Pending(val to: String, val deadlineMillis: Long) : Boot
        /** The deadline had passed; the pointer is back on the old version and the process must restart. */
        data class RolledBack(val reason: String) : Boot
    }

    fun currentVersion(): String? = installRoot?.resolve("current")?.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    fun pending(): Map<String, Any?>? = read("pending.json")

    fun result(): Map<String, Any?>? = read("result.json")

    /** Unpack the staged package, mark it pending, switch the pointer. The caller then restarts the process. */
    fun apply(deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS): Map<String, Any?> {
        val root = installRoot ?: throw IllegalStateException("this gateway is not started by the launcher (no install directory), so it cannot apply an upgrade")
        val manifest = read("staged.json") ?: throw IllegalStateException("no staged upgrade")
        if (pending() != null) throw IllegalStateException("an applied upgrade has not finished")
        val to = manifest["version"] as? String ?: throw IllegalStateException("staged upgrade has no version")
        // The version names a directory that is replaced below, so it must stay a single plain path segment.
        require(validVersion(to)) { "staged version '$to' is not a valid version name" }
        val from = currentVersion() ?: throw IllegalStateException("install directory has no current version")
        require(from != to) { "version $to is already installed" }
        val bytes = Files.readAllBytes(dir.resolve("staged.bin"))
        if (EamController.sha256(bytes) != manifest["sha256"]) throw IllegalStateException("staged package no longer matches its checksum")
        val target = root.resolve("versions").resolve(to)
        val partial = root.resolve("versions").resolve("$to.partial")
        partial.toFile().deleteRecursively()
        target.toFile().deleteRecursively()
        Files.createDirectories(partial)
        try {
            unzip(bytes, partial)
            if (!partial.resolve("bin/oip-gateway").exists()) throw IllegalArgumentException("package has no bin/oip-gateway")
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            partial.toFile().deleteRecursively()
            throw e
        }
        val marker = linkedMapOf<String, Any?>("from" to from, "to" to to, "appliedMillis" to clock(), "deadlineMillis" to clock() + deadlineMillis)
        write("pending.json", marker)
        Files.deleteIfExists(dir.resolve("result.json"))
        point(root, to)
        return marker
    }

    fun onBoot(): Boot {
        val marker = pending() ?: return Boot.Nothing
        val to = marker["to"] as String
        val deadline = (marker["deadlineMillis"] as Number).toLong()
        if (currentVersion() != to) { // the launcher already rolled back and wrote the result
            Files.deleteIfExists(dir.resolve("pending.json"))
            return Boot.Nothing
        }
        if (clock() > deadline) {
            val reason = "new version did not become healthy before its deadline"
            rollback(reason)
            return Boot.RolledBack(reason)
        }
        return Boot.Pending(to, deadline)
    }

    /** The gateway reached healthy on the new version: clear the marker and keep the outcome. */
    fun markHealthy() {
        val marker = pending() ?: return
        write("result.json", linkedMapOf("state" to "applied", "version" to marker["to"], "from" to marker["from"], "atMillis" to clock()))
        Files.deleteIfExists(dir.resolve("pending.json"))
    }

    /** Point back at the old version and record why. Safe to call when nothing is pending. */
    fun rollback(reason: String) {
        val marker = pending() ?: return
        val root = installRoot ?: return
        point(root, marker["from"] as String)
        write("result.json", linkedMapOf("state" to "rolledBack", "version" to marker["to"], "restoredVersion" to marker["from"], "reason" to reason, "atMillis" to clock()))
        Files.deleteIfExists(dir.resolve("pending.json"))
    }

    private fun point(root: Path, version: String) {
        val temp = root.resolve("current.tmp")
        Files.writeString(temp, version + "\n")
        Files.move(temp, root.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun read(name: String): Map<String, Any?>? {
        val file = dir.resolve(name)
        if (!file.exists()) return null
        return runCatching { Json.parse(file.readText()) }.getOrNull()
    }

    private fun write(name: String, doc: Map<String, Any?>) {
        Files.createDirectories(dir)
        val temp = dir.resolve("$name.tmp")
        Files.writeString(temp, Json.write(doc))
        Files.move(temp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun unzip(bytes: ByteArray, into: Path) {
        val base = into.toAbsolutePath().normalize()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                val out = base.resolve(entry.name).normalize()
                if (!out.startsWith(base)) throw IllegalArgumentException("package entry '${entry.name}' leaves the version directory")
                if (entry.isDirectory) Files.createDirectories(out) else {
                    Files.createDirectories(out.parent)
                    Files.copy(zip, out)
                    if (entry.name.startsWith("bin/")) out.toFile().setExecutable(true)
                }
            }
            if (count == 0) throw IllegalArgumentException("upgrade package is not a zip archive")
        }
    }

    companion object {
        /** The documented exit code that means "restart me: an upgrade was applied or rolled back" (EX_TEMPFAIL). */
        const val EXIT_RESTART_FOR_UPGRADE = 75
        const val DEFAULT_DEADLINE_MILLIS = 120_000L

        private val VERSION_NAME = Regex("""[0-9A-Za-z][0-9A-Za-z.+-]{0,39}""")

        /** A version usable as a directory name under versions/: starts with a letter or digit and has no "..". */
        fun validVersion(version: String): Boolean = VERSION_NAME.matches(version) && ".." !in version
    }
}
