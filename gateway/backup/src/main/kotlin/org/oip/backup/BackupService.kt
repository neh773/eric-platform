@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.oip.backup

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.config.FileConfigStore
import org.oip.sdk.ManifestParser
import org.oip.security.SecretKeyException
import org.oip.security.SecretKeySource
import org.oip.security.SecretsKeyFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

/**
 * One gateway backup archive: config tree, runtime DB dump, installed-module manifest, and the security
 * files when they are present (secrets key, encrypted secret store, audit log, audit anchor).
 *
 * the reference platform stores that set in a single gateway backup file and a restore does not replace modules
 * (-).
 * This archive is our own zip (`oip-gateway-backup` v1), not a .gwbk (DIFFERENCES #10).
 * The default archive is manifest-only. [create] with `bundleModules` also stores each installed
 * package, and restoring that archive replaces `modules/` with those packages.
 *
 * The audit log and secret store are WAL databases, so the archive holds canonical dumps
 * (`db/audit.sql`, `db/secrets.sql`) rather than the SQLite file bytes. `db/audit.anchor` and the
 * secrets key are copied as raw bytes. The archive entry is `secrets.key`; on disk the file is
 * `secrets/gateway.key` (directory 0700, file 0600). An entry that is absent from the archive leaves
 * the live file alone, matching `gateway.yaml`. [wipe] removes the security files, including
 * `secrets/gateway.key` and a stray `secrets.key`, so a later restore is what brings them back.
 *
 * Restore validates the archive and the module packages before it changes anything, then stages the
 * new tree, swaps it in with atomic renames, and commits the runtime rows. A failure rolls the files back.
 *
 * Certificate material under `https/` and `opcua/` is archived by [CertificateMaterial]: certificates as-is,
 * private keys and key stores sealed under a subkey of the secrets key from [keySource], or of the
 * archived `secrets/gateway.key` when there is no source. With no key at all only the public
 * certificates are archived, never a private key. Restore opens the sealed entries with the archived
 * key, or else the live one. A restore whose archive holds certificate entries replaces each of those
 * directories as a whole; [afterRestore] then lets the gateway reload the listener certificate. A
 * directory whose certificate comes without its private key (a certificate-only archive) is left as it
 * is on disk, because replacing it would delete the live key and the next start would fail.
 */
class BackupService(
    private val dataDir: Path,
    private val store: FileConfigStore,
    private val runtime: RuntimeDatabase,
    private val gatewayVersion: String,
    private val keySource: SecretKeySource? = null,
) {
    private val mapper = ObjectMapper()
    private val registryFile: Path get() = dataDir.resolve("installed-modules.tsv")
    private val modulesDir: Path get() = dataDir.resolve("modules")
    private val gatewayYaml: Path get() = dataDir.resolve("gateway.yaml")
    private val auditDb: Path get() = SecuritySnapshot.auditDb(dataDir)
    private val secretsDb: Path get() = SecuritySnapshot.secretsDb(dataDir)
    private val auditAnchor: Path get() = SecuritySnapshot.anchorFile(dataDir)
    private val secretsKey: Path get() = SecuritySnapshot.keyFile(dataDir)

    /**
     * Test seam. Runs after the staged files have been swapped into place and before the runtime DB
     * transaction commits. Throwing here rolls the files back and leaves the database unchanged.
     */
    var beforeRuntimeCommit: () -> Unit = {}

    /**
     * Close live audit and secret database handles before those files are renamed.
     * Windows refuses the rename while a connection still has the file open.
     */
    /** Close directory watchers, such as the OPC UA trust list, before those directories are renamed. */
    var releaseReplacedDirs: () -> Unit = {}

    var releaseSecurityFiles: () -> Unit = {}

    /** Open those databases again after the rename, including when the restore rolls back. */
    var reopenSecurityFiles: () -> Unit = {}

    /** Runs after a restore committed. The gateway reloads the HTTPS certificate from disk here. */
    var afterRestore: () -> Unit = {}

    fun create(bundleModules: Boolean = false): ByteArray {
        val configRoot = store.root
        val configEntries = ContentHash.files(configRoot).map { p ->
            val rel = "config/" + configRoot.relativize(p).toString().replace('\\', '/')
            rel to Files.readAllBytes(p)
        }
        val dump = runtime.canonicalDump()
        val modules = moduleManifests()
        val auditDump = SecuritySnapshot.readAudit(auditDb)
        val anchorBytes = if (auditAnchor.exists()) auditAnchor.readBytes() else null
        val secretsDump = SecuritySnapshot.readSecrets(secretsDb)
        val keyBytes = if (secretsKey.exists()) secretsKey.readBytes() else null
        // The live key (the gateway passes the same source as its secret store, which honours
        // OIP_SECRETS_KEY); without a source, the archived key file. No key at all means public certs only.
        val sealKey = try {
            keySource?.key()
        } catch (_: SecretKeyException) {
            null
        } ?: keyBytes?.let { CertificateMaterial.keyFromFileBytes(it) }
        val certs = CertificateMaterial.collect(dataDir).filter { !it.sealed || sealKey != null }
        val manifest = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "formatVersion" to FORMAT_VERSION,
            "gatewayVersion" to gatewayVersion,
            "created" to Instant.now().toString(),
            "bundleModules" to bundleModules,
            "modules" to modules,
            "configHash" to ContentHash.config(configRoot),
            "runtimeHash" to ContentHash.runtime(dump),
            "securityHash" to ContentHash.security(auditDump, anchorBytes, secretsDump, keyBytes),
            "certificateKeyCheck" to sealKey?.let { CertificateMaterial.keyCheck(it) },
        )
        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries += "oip-backup.json" to mapper.writeValueAsBytes(manifest)
        entries += configEntries
        entries += "db/runtime.sql" to dump.toByteArray(Charsets.UTF_8)
        entries += "installed-modules.tsv" to if (registryFile.exists()) registryFile.readBytes() else ByteArray(0)
        if (gatewayYaml.exists()) entries += "gateway.yaml" to gatewayYaml.readBytes()
        if (auditDump != null) entries += SecuritySnapshot.AUDIT_SQL to auditDump.toByteArray(Charsets.UTF_8)
        if (anchorBytes != null) entries += SecuritySnapshot.AUDIT_ANCHOR to anchorBytes
        if (secretsDump != null) entries += SecuritySnapshot.SECRETS_SQL to secretsDump.toByteArray(Charsets.UTF_8)
        if (keyBytes != null) entries += SecuritySnapshot.SECRETS_KEY to keyBytes
        if (certs.isNotEmpty()) entries += CertificateMaterial.toArchive(certs, sealKey)
        entries += logicEntries()
        if (bundleModules) entries += bundledPackages(modules.mapNotNull { it["id"] as? String })
        return ZipIo.write(entries)
    }

    /**
     * [create], then seal the whole archive with [BackupCipher] under [passphrase]. The result holds no
     * plaintext entry, so `secrets.key` never leaves the gateway in clear (review-1 F3). REST and the
     * admin UI only ever store or return this form.
     */
    fun createEncrypted(
        passphrase: CharArray,
        bundleModules: Boolean = false,
        kdf: BackupCipher.Kdf = BackupCipher.PRODUCTION,
    ): ByteArray {
        BackupCipher.requireNewPassphrase(passphrase)
        val plain = create(bundleModules)
        try {
            return BackupCipher.encrypt(plain, passphrase, kdf)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Open an envelope from [createEncrypted] (or `oipctl backup --encrypt`) and [restore] it. A wrong
     * passphrase or a tampered file fails the GCM check before anything on disk changes.
     */
    fun restoreEncrypted(envelope: ByteArray, passphrase: CharArray) {
        val plain = BackupCipher.decrypt(envelope, passphrase)
        try {
            restore(plain)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Delete config files, runtime rows, the security files (audit database, secret store,
     * audit anchor, and `secrets/gateway.key`, plus a stray `secrets.key`), and the `https/` and `opcua/`
     * certificate directories. Module packages, logs, and `gateway.yaml` stay.
     * The config index is rebuilt under the store lock so it matches the empty tree.
     */
    fun wipe() {
        store.replaceTree { deleteChildren(store.root) }
        runtime.clear()
        registryFile.deleteIfExists()
        releaseSecurityFiles()
        releaseReplacedDirs()
        try {
            SecuritySnapshot.deleteAll(dataDir)
            for (dir in CertificateMaterial.DIRS) deleteAnything(dataDir.resolve(dir))
        } finally {
            reopenSecurityFiles()
        }
    }

    /**
     * Replace config, runtime rows, the module registry, `gateway.yaml`, and any security files the
     * archive contains. A security entry that is missing from the archive does not delete the live file.
     */
    fun restore(archive: ByteArray) {
        val plan = plan(archive)
        val stage = Files.createTempDirectory(dataDir, ".restore-stage-")
        val saved = mutableListOf<Saved>()
        try {
            val stagedConfig = stage.resolve("config").also { it.createDirectories() }
            for ((rel, bytes) in plan.configFiles) place(stagedConfig, rel, bytes)
            plan.gatewayYaml?.let { stage.resolve("gateway.yaml").writeBytes(it) }
            if (plan.registry != null) stage.resolve("installed-modules.tsv").writeBytes(plan.registry)
            if (plan.logicFiles.isNotEmpty()) {
                val stagedLogic = stage.resolve("logic").also { it.createDirectories() }
                for ((rel, bytes) in plan.logicFiles) place(stagedLogic, rel, bytes)
                stagedLogic.resolve("programs").createDirectories()
            }
            if (plan.bundled) {
                val stagedModules = stage.resolve("modules").also { it.createDirectories() }
                for ((rel, bytes) in plan.moduleFiles) place(stagedModules, rel, bytes)
            }
            if (plan.auditDump != null) SecuritySnapshot.writeAudit(stage.resolve("audit.sqlite"), plan.auditDump)
            if (plan.secretsDump != null) SecuritySnapshot.writeSecrets(stage.resolve("secrets.sqlite"), plan.secretsDump)
            plan.auditAnchor?.let { stagePrivate(stage.resolve("audit.anchor"), it) }
            plan.secretsKey?.let { stagePrivate(stage.resolve("secrets.key"), it) }
            val certDirs = CertificateMaterial.dirsOf(plan.certificates)
            for (dir in certDirs) stagePrivateDir(stage.resolve("certs").resolve(dir))
            for (e in plan.certificates) {
                val target = stage.resolve("certs").resolve(e.rel).normalize()
                if (!target.startsWith(stage.resolve("certs"))) throw BackupException("path escapes the stage: ${e.rel}")
                try {
                    target.parent?.let { stagePrivateDir(it) }
                    if (e.sealed) stagePrivate(target, e.bytes) else target.writeBytes(e.bytes)
                } catch (_: java.nio.file.FileSystemException) {
                    // Two entries that collide as file and directory (a.crt and a.crt/b.crt). Nothing live
                    // has changed yet; this is still the staging step.
                    throw BackupException("archive has conflicting certificate entries at ${e.rel}")
                }
            }
            // The drop areas are not archived. A restored https/ gets them back empty, so a certificate dropped
            // right after the restore is picked up without waiting for the next start.
            if ("https" in certDirs) {
                for (drop in CertificateMaterial.DROP_DIRS) stagePrivateDir(stage.resolve("certs").resolve(drop))
            }
            val touchesSecurity = plan.auditDump != null || plan.secretsDump != null || plan.auditAnchor != null || plan.secretsKey != null
            if (touchesSecurity) releaseSecurityFiles()
            if (certDirs.isNotEmpty()) releaseReplacedDirs()
            store.replaceTree {
                try {
                    swapIn(store.root, stagedConfig, saved)
                    if (plan.gatewayYaml != null) swapIn(gatewayYaml, stage.resolve("gateway.yaml"), saved)
                    swapIn(registryFile, if (plan.registry != null) stage.resolve("installed-modules.tsv") else null, saved)
                    if (plan.bundled) swapIn(modulesDir, stage.resolve("modules"), saved)
                    if (plan.logicFiles.isNotEmpty()) {
                        logicDir.createDirectories()
                        swapIn(logicDir.resolve("programs"), stage.resolve("logic/programs"), saved)
                        val mapping = stage.resolve("logic/mapping.json")
                        swapIn(logicDir.resolve("mapping.json"), if (mapping.exists()) mapping else null, saved)
                    }
                    if (plan.auditDump != null) swapDatabase(auditDb, stage.resolve("audit.sqlite"), saved)
                    if (plan.secretsDump != null) swapDatabase(secretsDb, stage.resolve("secrets.sqlite"), saved)
                    if (plan.auditAnchor != null) swapIn(auditAnchor, stage.resolve("audit.anchor"), saved)
                    if (plan.secretsKey != null) {
                        // Archive entry `secrets.key` lands on secrets/gateway.key. Mode travels with the inode.
                        swapIn(secretsKey, stage.resolve("secrets.key"), saved)
                        SecretsKeyFile.restrict(dataDir)
                    }
                    for (dir in certDirs) swapIn(dataDir.resolve(dir), stage.resolve("certs").resolve(dir), saved)
                    beforeRuntimeCommit()
                    runtime.restoreDump(plan.dump)
                    if (touchesSecurity) reopenSecurityFiles()
                } catch (t: Throwable) {
                    try {
                        rollback(saved)
                    } catch (r: Throwable) {
                        t.addSuppressed(r)
                    }
                    saved.clear()
                    if (touchesSecurity) runCatching { reopenSecurityFiles() }
                    throw t
                }
            }
            discard(saved)
        } finally {
            if (stage.exists()) deleteAnything(stage)
        }
        // The restore is committed at this point. A failed reload must not report the restore as failed;
        // the hook logs its own errors and the next start loads the restored files anyway.
        runCatching { afterRestore() }
    }

    private data class Plan(
        val configFiles: List<Pair<String, ByteArray>>,
        val dump: String,
        val gatewayYaml: ByteArray?,
        val registry: ByteArray?,
        val bundled: Boolean,
        val moduleFiles: List<Pair<String, ByteArray>>,
        val auditDump: String?,
        val auditAnchor: ByteArray?,
        val secretsDump: String?,
        val secretsKey: ByteArray?,
        val certificates: List<CertificateMaterial.Entry>,
        val logicFiles: List<Pair<String, ByteArray>> = emptyList(),
    )

    private fun plan(archive: ByteArray): Plan {
        val entries = ZipIo.read(archive)
        val manifestBytes = entries["oip-backup.json"] ?: throw BackupException("archive has no oip-backup.json")
        @Suppress("UNCHECKED_CAST")
        val manifest = mapper.readValue(manifestBytes, Map::class.java) as Map<String, Any?>
        if (manifest["format"] != FORMAT || (manifest["formatVersion"] as? Number)?.toInt() != FORMAT_VERSION)
            throw BackupException("unsupported backup format")
        val allowed = entries.keys.all {
            it == "oip-backup.json" || it == "db/runtime.sql" || it == "installed-modules.tsv" || it == "gateway.yaml" ||
                it == SecuritySnapshot.AUDIT_SQL || it == SecuritySnapshot.AUDIT_ANCHOR ||
                it == SecuritySnapshot.SECRETS_SQL || it == SecuritySnapshot.SECRETS_KEY ||
                it.startsWith("config/") || it.startsWith("modules/") || CertificateMaterial.owns(it) ||
                it == "logic/mapping.json" || (it.startsWith("logic/programs/") && it.endsWith(".st"))
        }
        if (!allowed) throw BackupException("archive contains unexpected entries")
        val dumpText = entries["db/runtime.sql"]?.toString(Charsets.UTF_8) ?: throw BackupException("archive has no runtime dump")
        val rows = RuntimeDatabase.parseDump(dumpText)
        val configFiles = entries.filterKeys { it.startsWith("config/") }.map { (name, bytes) ->
            val rel = name.removePrefix("config/")
            if (rel.isBlank()) throw BackupException("config path escapes the store")
            rel to bytes
        }
        val moduleFiles = entries.filterKeys { it.startsWith("modules/") }.map { (name, bytes) ->
            name.removePrefix("modules/") to bytes
        }
        val bundled = moduleFiles.isNotEmpty()
        val registry = entries["installed-modules.tsv"]
        val auditDump = entries[SecuritySnapshot.AUDIT_SQL]?.let { bytes ->
            SecuritySnapshot.formatAudit(SecuritySnapshot.parseAudit(bytes.toString(Charsets.UTF_8)))
        }
        val secretsDump = entries[SecuritySnapshot.SECRETS_SQL]?.let { bytes ->
            SecuritySnapshot.formatSecrets(SecuritySnapshot.parseSecrets(bytes.toString(Charsets.UTF_8)))
        }
        val archiveKey = entries[SecuritySnapshot.SECRETS_KEY]
        val archived = CertificateMaterial.fromArchive(
            entries,
            manifest["certificateKeyCheck"] as? String,
            listOf(
                { archiveKey?.let { CertificateMaterial.keyFromFileBytes(it) } },
                { keySource?.key() },
            ),
        )
        // A directory whose certificate arrives without its key (a certificate-only archive) is not
        // restored: replacing it would delete the live key and the next start would refuse the listener.
        val incomplete = CertificateMaterial.incompleteDirs(archived)
        val certificates = archived.filter { e -> incomplete.none { e.rel.startsWith("$it/") } }
        val ids = registryIds(registry)
        val versions = manifestVersions(manifest)
        if (bundled) {
            requirePackages(ids, versions) { id -> moduleFiles.firstOrNull { it.first == "$id/module.yaml" }?.second }
        } else {
            requirePackages(ids, versions) { id ->
                val yaml = modulesDir.resolve(id).resolve("module.yaml").normalize()
                if (!yaml.startsWith(modulesDir.normalize()) || !yaml.exists()) null else yaml.readBytes()
            }
        }
        return Plan(
            configFiles, RuntimeDatabase.formatDump(rows), entries["gateway.yaml"],
            registry?.takeIf { it.isNotEmpty() }, bundled, moduleFiles,
            auditDump, entries[SecuritySnapshot.AUDIT_ANCHOR], secretsDump, entries[SecuritySnapshot.SECRETS_KEY],
            certificates,
            entries.filterKeys { it.startsWith("logic/") }.map { (name, bytes) ->
                val rel = name.removePrefix("logic/")
                if (rel.split('/').any { it == ".." || it.isEmpty() }) throw BackupException("logic path escapes the data directory")
                rel to bytes
            },
        )
    }

    private fun registryIds(tsv: ByteArray?): List<String> {
        if (tsv == null || tsv.isEmpty()) return emptyList()
        val ids = mutableListOf<String>()
        for (line in tsv.toString(Charsets.UTF_8).lineSequence()) {
            if (line.isBlank()) continue
            val id = line.substringBefore('\t').trim()
            if (!MODULE_ID.matches(id)) throw BackupException("installed-modules.tsv has an illegal module id '$id'")
            if (id in ids) throw BackupException("installed-modules.tsv lists '$id' more than once")
            ids += id
        }
        return ids
    }

    private fun manifestVersions(manifest: Map<String, Any?>): Map<String, String?> {
        val raw = manifest["modules"] as? List<*> ?: return emptyMap()
        val out = linkedMapOf<String, String?>()
        for (item in raw) {
            val m = item as? Map<*, *> ?: continue
            val id = m["id"]?.toString() ?: continue
            out[id] = m["version"]?.toString()
        }
        return out
    }

    /** Fail with every missing or mismatched module named, before any live file is changed. */
    private fun requirePackages(ids: List<String>, versions: Map<String, String?>, yamlOf: (String) -> ByteArray?) {
        val problems = mutableListOf<String>()
        for (id in ids) {
            val expected = versions[id]
            val bytes = yamlOf(id)
            if (bytes == null) {
                problems += "$id (expected ${expected ?: "recorded"}, package not present)"
                continue
            }
            val parsed = runCatching { ManifestParser.parse(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            if (parsed == null || parsed.id != id) {
                problems += "$id (package manifest is unreadable or its id does not match)"
                continue
            }
            if (expected != null && parsed.version.toString() != expected) {
                problems += "$id (expected $expected, found ${parsed.version})"
            }
        }
        if (problems.isNotEmpty())
            throw BackupException("restore refused: module packages missing or version mismatch: ${problems.joinToString("; ")}")
    }

    private val logicDir: Path get() = dataDir.resolve("logic")

    /** PLC programs (the .st files in `logic/programs`) and the tag map (`logic/mapping.json`); build output is left out. */
    private fun logicEntries(): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        val programs = logicDir.resolve("programs")
        if (programs.isDirectory()) {
            for (file in programs.listDirectoryEntries("*.st").filter { it.isRegularFile() }.sortedBy { it.name })
                out += "logic/programs/${file.name}" to file.readBytes()
        }
        val mapping = logicDir.resolve("mapping.json")
        if (mapping.isRegularFile()) out += "logic/mapping.json" to mapping.readBytes()
        return out
    }

    private fun bundledPackages(ids: List<String>): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        for (id in ids) {
            val dir = modulesDir.resolve(id)
            val yaml = dir.resolve("module.yaml")
            if (!dir.isDirectory() || !yaml.exists()) throw BackupException("cannot bundle module '$id': package not present")
            Files.walk(dir).use { stream ->
                stream.filter { it.isRegularFile() }.forEach { file ->
                    val rel = dir.relativize(file).toString().replace('\\', '/')
                    if (rel.split('/').any { it.startsWith(".") }) return@forEach
                    out += "modules/$id/$rel" to file.readBytes()
                }
            }
        }
        return out
    }

    private fun moduleManifests(): List<Map<String, Any?>> {
        val states = readRegistry()
        val onDisk = mutableMapOf<String, org.oip.sdk.ModuleManifest>()
        if (modulesDir.isDirectory()) {
            for (dir in modulesDir.listDirectoryEntries().filter { it.isDirectory() && it.resolve("module.yaml").exists() }.sortedBy { it.name }) {
                val m = runCatching { ManifestParser.parse(dir.resolve("module.yaml").readText()) }.getOrNull() ?: continue
                onDisk[m.id] = m
            }
        }
        return states.keys.sorted().map { id ->
            val m = onDisk[id]
            val (state, error) = states.getValue(id)
            linkedMapOf(
                "id" to id,
                "version" to m?.version?.toString(),
                "name" to (m?.name ?: id),
                "state" to state,
                "error" to error,
            )
        }
    }

    private fun readRegistry(): Map<String, Pair<String, String?>> {
        if (!registryFile.exists()) return emptyMap()
        return registryFile.readText().lineSequence().filter { it.isNotBlank() }.associate { line ->
            val p = line.split('\t', limit = 3)
            p[0] to (p.getOrElse(1) { "" } to p.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }
    }

    private data class Saved(val live: Path, val previous: Path?)

    /**
     * Replace a SQLite file and drop its WAL sidecars. The staged file must already be checkpointed.
     * The previous main file and sidecars are parked so [rollback] can put them back.
     */
    private fun swapDatabase(live: Path, staged: Path, saved: MutableList<Saved>) {
        swapIn(live, staged, saved)
        swapIn(live.resolveSibling("${live.fileName}-wal"), null, saved)
        swapIn(live.resolveSibling("${live.fileName}-shm"), null, saved)
    }

    /** Write [bytes] at mode 0600. The mode travels with the inode when [swapIn] renames the file. */
    private fun stagePrivate(path: Path, bytes: ByteArray) {
        path.parent?.createDirectories()
        path.writeBytes(bytes)
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems have no mode bits.
        }
    }

    /** Owner-only directory (0700) for staged certificate material. */
    private fun stagePrivateDir(path: Path) {
        path.createDirectories()
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems have no mode bits.
        }
    }

    /** Park [live] (if it exists) and move [staged] onto that path. [staged] null removes [live]. */
    private fun swapIn(live: Path, staged: Path?, saved: MutableList<Saved>) {
        val previous = if (live.exists()) {
            val parked = dataDir.resolve(".restore-prev-${UUID.randomUUID()}-${live.name}")
            Files.move(live, parked, StandardCopyOption.ATOMIC_MOVE)
            parked
        } else null
        saved += Saved(live, previous)
        if (staged != null && staged.exists()) {
            live.parent?.createDirectories()
            Files.move(staged, live, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun rollback(saved: List<Saved>) {
        for (s in saved.asReversed()) {
            if (s.live.exists()) {
                val junk = dataDir.resolve(".restore-failed-${UUID.randomUUID()}")
                Files.move(s.live, junk, StandardCopyOption.ATOMIC_MOVE)
                deleteAnything(junk)
            }
            if (s.previous != null && s.previous.exists()) Files.move(s.previous, s.live, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun discard(saved: List<Saved>) {
        for (s in saved) {
            val prev = s.previous ?: continue
            if (prev.exists()) deleteAnything(prev)
        }
    }

    private fun place(root: Path, rel: String, bytes: ByteArray) {
        val target = root.resolve(rel).normalize()
        if (!target.startsWith(root.normalize())) throw BackupException("path escapes the store: $rel")
        target.parent?.createDirectories()
        target.writeBytes(bytes)
    }

    private fun deleteChildren(dir: Path) {
        if (!dir.exists()) { dir.createDirectories(); return }
        dir.listDirectoryEntries().forEach { child -> deleteAnything(child) }
    }

    private fun deleteAnything(path: Path) {
        if (!path.exists()) return
        if (path.isDirectory()) deleteRecursively(path) else path.deleteExisting()
    }

    private fun deleteRecursively(dir: Path) {
        dir.listDirectoryEntries().forEach { child ->
            if (child.isDirectory()) deleteRecursively(child) else child.deleteExisting()
        }
        dir.deleteExisting()
    }

    companion object {
        const val FORMAT = "oip-gateway-backup"
        const val FORMAT_VERSION = 1
        private val MODULE_ID = Regex("""^[a-z][a-z0-9]*([.-][a-z0-9]+)*$""")
    }
}
