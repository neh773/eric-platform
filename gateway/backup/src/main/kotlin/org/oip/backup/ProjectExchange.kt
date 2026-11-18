package org.oip.backup

import com.fasterxml.jackson.databind.ObjectMapper
import org.oip.config.FileConfigStore
import org.oip.config.Format
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceCodec
import org.oip.config.ResourceFormatException
import org.oip.config.ResourceId
import org.oip.config.Scope
import java.nio.file.Files

/**
 * Project export/import.
 *
 * the reference platform exports a project to a file and, on import, asks what to do when a resource already exists
 * (-).
 * The archive is a zip of our resource YAML (DIFFERENCES #10), not an the reference platform project file.
 * [ConflictPolicy.SKIP] leaves an existing resource untouched; [ConflictPolicy.OVERWRITE] replaces its spec.
 * Import validates every entry before writing, so a bad resource does not apply part of the archive.
 * The target project name is the one requested by the caller; the name stored in the archive is not required to match.
 * Resource versions are assigned by the config store (create = 1, overwrite = stored + 1), not copied from the file.
 * [export] leaves out resources [include] rejects. [import] calls [check] for every entry before it writes anything,
 * so a refused entry applies nothing.
 */
class ProjectExchange(private val store: FileConfigStore) {
    private val mapper = ObjectMapper()

    fun listProjects(): List<String> = store.list()
        .mapNotNull { if (it.id.scope.type == "project") it.id.scope.qualifier else null }
        .distinct()
        .sorted()

    fun export(name: String, include: (ResourceId) -> Boolean = { true }): ByteArray {
        val scope = Scope.project(name)
        val all = store.list(scope = scope)
        if (all.isEmpty()) throw NoSuchElementException("project '$name' not found")
        val resources = all.filter { include(it.id) }.sortedBy { it.id.toString() }
        val files = resources.map { stored ->
            val path = store.file(stored.id)
            val logical = store.root.resolve(stored.id.relativePath)
            // Tag documents live in a shared shard (ADR 0011). The zip entry stays one resource.
            val bytes = if (Files.exists(path) && path.normalize() == logical.normalize()) {
                Files.readAllBytes(path)
            } else {
                ResourceCodec.encode(stored.resource, Format.YAML).toByteArray()
            }
            "files/${stored.id.relativePath}" to bytes
        }
        val manifest = linkedMapOf(
            "format" to FORMAT,
            "formatVersion" to FORMAT_VERSION,
            "project" to name,
            "resources" to resources.map { it.id.toString() },
        )
        return ZipIo.write(listOf("oip-project.json" to mapper.writeValueAsBytes(manifest)) + files)
    }

    fun import(target: String, archive: ByteArray, policy: ConflictPolicy, check: (ResourceId) -> Unit = {}): ImportResult {
        val scope = Scope.project(target)
        val entries = ZipIo.read(archive)
        val manifestBytes = entries["oip-project.json"] ?: throw BackupException("archive has no oip-project.json")
        @Suppress("UNCHECKED_CAST")
        val manifest = mapper.readValue(manifestBytes, Map::class.java) as Map<String, Any?>
        if (manifest["format"] != FORMAT || (manifest["formatVersion"] as? Number)?.toInt() != FORMAT_VERSION)
            throw BackupException("unsupported project archive")
        val files = entries.filterKeys { it.startsWith("files/") }
        if (files.size != entries.size - 1) throw BackupException("project archive contains unexpected entries")

        data class Pending(val id: ResourceId, val resource: Resource)
        val pending = mutableListOf<Pending>()
        for ((name, bytes) in files) {
            val rel = name.removePrefix("files/")
            val parts = rel.split('/')
            // module / project / <sourceName> / kind / name.yaml
            if (parts.size != 5 || parts[1] != "project" || !parts[4].endsWith(".yaml"))
                throw BackupException("not a project resource path: $rel")
            val module = parts[0]
            val kind = parts[3]
            val resourceName = parts[4].removeSuffix(".yaml")
            val id = try {
                ResourceId(module, scope, kind, resourceName)
            } catch (e: IllegalArgumentException) {
                throw BackupException(e.message ?: "bad resource path")
            }
            val decoded = try {
                ResourceCodec.decode(bytes.toString(Charsets.UTF_8), Format.YAML)
            } catch (e: ResourceFormatException) {
                throw BackupException(e.message ?: "bad resource")
            }
            if (decoded.kind != kind || decoded.metadata.module != module || decoded.metadata.name != resourceName)
                throw BackupException("resource content does not match $rel")
            val candidate = Resource(kind, Metadata(resourceName, module, decoded.metadata.labels), decoded.spec)
            store.registry.check(candidate, scope)
            if (pending.any { it.id == id }) throw BackupException("duplicate resource $id")
            check(id)
            pending += Pending(id, candidate)
        }

        // Store guards (a project's parent must exist and be inheritable) see the whole archive before any write.
        store.checkWrites(pending.map { it.id to it.resource })

        var created = 0
        var overwritten = 0
        var skipped = 0
        for (item in pending) {
            val current = store.get(item.id)
            if (current != null && policy == ConflictPolicy.SKIP) {
                skipped++
                continue
            }
            val version = current?.metadata?.version ?: 0L
            store.put(scope, item.resource.copy(metadata = item.resource.metadata.copy(version = version)), ACTOR)
            if (current == null) created++ else overwritten++
        }
        return ImportResult(target, policy.name.lowercase(), created, overwritten, skipped)
    }

    companion object {
        const val FORMAT = "oip-project"
        const val FORMAT_VERSION = 1
        const val ACTOR = "project-import"
    }
}

enum class ConflictPolicy {
    SKIP, OVERWRITE;

    companion object {
        fun parse(raw: String): ConflictPolicy = when (raw.lowercase()) {
            "skip" -> SKIP
            "overwrite" -> OVERWRITE
            else -> throw IllegalArgumentException("conflict policy must be skip or overwrite")
        }
    }
}

data class ImportResult(val project: String, val policy: String, val created: Int, val overwritten: Int, val skipped: Int)
