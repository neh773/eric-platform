package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.backup.ConflictPolicy
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Optional example projects. Nothing here is installed until someone asks. An example is a directory with
 * `example.json`, a project archive `project.zip`, gateway resources in `resources.json`, and an optional
 * `PlantDo.st` soft-PLC program.
 *
 * The directory is `OIP_EXAMPLES`, or `examples` under the working directory when that variable is unset.
 */
class ExamplesApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get(BASE) { ctx ->
            json(ctx, mapOf("examples" to list()))
        }
        app.post("$BASE/{id}/install") { ctx ->
            val id = ctx.pathParam("id")
            val replace = ctx.queryParam("replace") == "true"
            json(ctx, install(id, replace))
        }
    }

    private fun list(): List<Map<String, Any?>> {
        val root = root() ?: return emptyList()
        return root.toFile().listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val marker = dir.resolve("example.json")
            if (!marker.isFile) return@mapNotNull null
            val spec = Json.parse(marker.readText())
            mapOf(
                "id" to (spec["id"] as? String ?: dir.name),
                "title" to (spec["title"] as? String ?: dir.name),
                "description" to (spec["description"] as? String ?: ""),
                "project" to (spec["project"] as? String ?: dir.name),
            )
        }?.sortedBy { it["id"] as String } ?: emptyList()
    }

    private fun install(id: String, replace: Boolean): Map<String, Any?> {
        val root = root() ?: throw Problems.notFound("no examples directory")
        val dir = root.resolve(id)
        val marker = dir.resolve("example.json")
        if (!marker.isRegularFile()) throw Problems.notFound("example '$id' not found")
        val spec = Json.parse(marker.readText())
        val project = spec["project"] as? String ?: throw Problems.unprocessable("example '$id' has no project name")
        if (gw.projectService.exists(project)) {
            if (!replace) throw Problems.conflict("project '$project' already exists")
            gw.projectService.delete(project)
        }
        val zip = dir.resolve("project.zip")
        if (!zip.isRegularFile()) throw Problems.unprocessable("example '$id' has no project.zip")
        val imported = gw.projects.import(project, zip.readBytes(), ConflictPolicy.OVERWRITE)
        val resources = dir.resolve("resources.json")
        var written = 0
        if (resources.isRegularFile()) {
            val body = Json.parse(resources.readText())
            val items = body["resources"] as? List<*> ?: emptyList<Any>()
            for (item in items) {
                val row = item as? Map<*, *> ?: continue
                val module = row["module"] as? String ?: continue
                val scope = Scope.parse(row["scope"] as? String ?: "gateway")
                val kind = row["kind"] as? String ?: continue
                val name = row["name"] as? String ?: continue
                @Suppress("UNCHECKED_CAST")
                val resourceSpec = row["spec"] as? Map<String, Any?> ?: emptyMap()
                val apiVersion = row["apiVersion"] as? String ?: "oip/v1"
                val id = ResourceId(module, scope, kind, name)
                val version = gw.store.get(id)?.metadata?.version ?: 0L
                gw.store.put(scope, Resource(kind, Metadata(name, module, version = version), resourceSpec, apiVersion), "example")
                written++
            }
        }
        val program = dir.resolve("PlantDo.st")
        if (program.isRegularFile()) {
            gw.logic.save("PlantDo", program.readText())
            runCatching { gw.logic.check("PlantDo") }
        }
        return mapOf(
            "id" to id,
            "project" to imported.project,
            "created" to imported.created,
            "resources" to written,
            "publicHmi" to (gw.projectService.info(project)?.settings?.publicHmi == true),
        )
    }

    private fun root(): Path? {
        val named = System.getenv("OIP_EXAMPLES")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        if (named != null && named.isDirectory()) return named
        val cwd = Path("examples").toAbsolutePath().normalize()
        return cwd.takeIf { it.isDirectory() }
    }

    private fun json(ctx: Context, body: Any?) {
        ctx.contentType("application/json").result(Json.write(body))
    }

    companion object {
        const val BASE = "/api/v1/examples"
    }
}
