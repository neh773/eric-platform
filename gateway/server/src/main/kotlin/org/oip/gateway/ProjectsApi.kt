package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.config.ConfigBatches
import org.oip.config.DesignerAction
import org.oip.config.GATEWAY_MODULE
import org.oip.config.Metadata
import org.oip.config.ProjectService
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.security.AuditActions
import org.oip.security.AuditContext
import org.oip.security.Kinds
import org.oip.security.Permissions
import org.oip.security.SECURITY_MODULE

/**
 * Project CRUD (PRO-004) and settings over REST. [GatewayAuth] gives every `/api/v1/projects` route gateway.read
 * for GET and gateway.write for the rest.
 *
 * - `GET /api/v1/projects`: `projects` (names, as before) and `items` (each project's settings, resource count,
 *   children, and inheritance chain).
 * - `POST /api/v1/projects`: create `{name, title?, description?, parent?, enabled?, inheritable?, runnable?}`.
 * - `GET /api/v1/projects/{name}`: one project's settings.
 * - `PATCH /api/v1/projects/{name}`: change some settings (enable/disable, title, description, parent, flags).
 *   `If-Match` is the settings version the caller read.
 * - `POST /api/v1/projects/{name}/rename` and `/copy`: `{name: <new name>}` (copy also takes `title`).
 * - `DELETE /api/v1/projects/{name}`: removes every resource of the project, refused with 409 while another project
 *   inherits from it.
 *
 * Each request writes one audit record (`project created`, `project updated`, `project renamed`, `project copied`,
 * `project deleted`), however many resources it touches. A resource the caller may not write through the config API
 * (users, roles, API keys, security settings without the security permissions) stops a copy, rename, or delete
 * before anything changes, as it stops an import.
 *
 */
class ProjectsApi(private val gw: Gateway, private val guard: SecurityResourceGuard) {
    private val projects get() = gw.projectService

    fun register(app: Javalin) {
        app.get(BASE) { ctx ->
            val items = projects.list()
            json(ctx, 200, mapOf("projects" to items.map { it.name }, "items" to items.map { it.toMap() }))
        }
        app.post(BASE) { ctx ->
            val body = body(ctx)
            val name = body["name"] as? String ?: throw Problems.unprocessable("name is required")
            projectName(name)
            val changes = body - "name"
            val settings = settings(changes)
            val info = batch(name, AuditActions.PROJECT_CREATED, "") { projects.create(name, settings, actor()) }
            ctx.header("Location", "$BASE/$name")
            json(ctx, 201, info.toMap())
        }
        app.get("$BASE/{name}") { ctx ->
            val name = projectName(ctx.pathParam("name"))
            json(ctx, 200, (projects.info(name) ?: throw Problems.notFound("project '$name' not found")).toMap())
        }
        app.patch("$BASE/{name}") { ctx ->
            val name = projectName(ctx.pathParam("name"))
            val changes = body(ctx)
            if (changes.isEmpty()) throw Problems.unprocessable("no settings to change")
            val expected = ctx.header("If-Match")?.trim()?.removePrefix("W/")?.trim('"')?.let {
                it.toLongOrNull() ?: throw Problems.badRequest("If-Match must be a settings version")
            }
            // SEC-020: a settings change is a project save (protectedResources also needs the protect roles).
            gw.designerGuard.checkSettingsPatch(ctx, name, changes.keys)
            val detail = "keys=" + changes.keys.sorted().joinToString(",")
            val info = batch(name, AuditActions.PROJECT_UPDATED, detail) { projects.update(name, changes, expected, actor()) }
            json(ctx, 200, info.toMap())
        }
        app.delete("$BASE/{name}") { ctx ->
            val name = projectName(ctx.pathParam("name"))
            if (projects.exists(name)) gw.designerGuard.require(ctx, name, DesignerAction.DELETE)
            checkWrites(ctx, name)
            batch(name, AuditActions.PROJECT_DELETED, "") { projects.delete(name) }
            ctx.status(204)
        }
        app.post("$BASE/{name}/rename") { ctx ->
            val from = projectName(ctx.pathParam("name"))
            val to = newName(body(ctx))
            projects.sourceResources(from, to)
            // SEC-020: a rename removes the project under its old name.
            gw.designerGuard.require(ctx, from, DesignerAction.DELETE)
            checkWrites(ctx, from)
            val info = batch(from, AuditActions.PROJECT_RENAMED, "to=$to") {
                val renamed = projects.rename(from, to, actor())
                // The launch default follows the project it names.
                val launch = gw.platformSettings.get()
                if (launch.defaultProject == from) gw.platformSettings.update(launch.copy(defaultProject = to), actor())
                renamed
            }
            json(ctx, 200, info.toMap())
        }
        app.post("$BASE/{name}/copy") { ctx ->
            val from = projectName(ctx.pathParam("name"))
            val body = body(ctx)
            val to = newName(body)
            val title = when (val raw = body["title"]) {
                null -> null
                is String -> raw
                else -> throw Problems.unprocessable("title must be a string")
            }
            projects.sourceResources(from, to)
            checkWrites(ctx, from)
            val info = batch(to, AuditActions.PROJECT_COPIED, "from=$from") { projects.copy(from, to, actor(), title) }
            ctx.header("Location", "$BASE/$to")
            json(ctx, 201, info.toMap())
        }
    }

    /** Refuse the request when any resource of [project] is one the caller may not write through the config API. */
    private fun checkWrites(ctx: Context, project: String) {
        for (key in gw.store.list(scope = Scope.project(project)).map { it.id.module to it.id.kind }.distinct()) {
            guard.checkWrite(ctx, key.first, key.second)
        }
    }

    private fun <T> batch(project: String, action: String, detail: String, block: () -> T): T =
        ConfigBatches.run("project/$project", actor(), action, "project/$project", detail.ifEmpty { null }, block)

    private fun settings(changes: Map<String, Any?>): ProjectService.Settings {
        val unknown = changes.keys - ProjectService.Settings.FIELDS
        if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown project settings: ${unknown.sorted().joinToString()}")
        val defaults = ProjectService.Settings()
        fun text(key: String, fallback: String): String = when (val v = changes[key]) {
            null -> fallback
            is String -> v
            else -> throw Problems.unprocessable("$key must be a string")
        }
        fun flag(key: String, fallback: Boolean): Boolean = when (val v = changes[key]) {
            null -> fallback
            is Boolean -> v
            else -> throw Problems.unprocessable("$key must be a boolean")
        }
        return ProjectService.Settings(
            title = text("title", defaults.title),
            description = text("description", defaults.description),
            parent = text("parent", defaults.parent),
            enabled = flag("enabled", defaults.enabled),
            inheritable = flag("inheritable", defaults.inheritable),
            runnable = flag("runnable", defaults.runnable),
            sessionRoles = when (val v = changes["sessionRoles"]) {
                null -> defaults.sessionRoles
                else -> ProjectService.Settings.roleList(v) ?: throw Problems.unprocessable("sessionRoles must be a list of role names")
            },
            sessionIdentityProvider = text("sessionIdentityProvider", defaults.sessionIdentityProvider),
            sessionLevels = when (val v = changes["sessionLevels"]) {
                null -> defaults.sessionLevels
                else -> ProjectService.Settings.roleList(v) ?: throw Problems.unprocessable("sessionLevels must be a list of security level paths")
            },
            sessionRequireAll = flag("sessionRequireAll", defaults.sessionRequireAll),
            defaultTagProvider = text("defaultTagProvider", defaults.defaultTagProvider),
            defaultDatabase = text("defaultDatabase", defaults.defaultDatabase),
            sessionTheme = text("sessionTheme", defaults.sessionTheme),
            sessionLocale = text("sessionLocale", defaults.sessionLocale),
            touchscreenMode = flag("touchscreenMode", defaults.touchscreenMode),
            publicHmi = flag("publicHmi", defaults.publicHmi),
            designerViewRoles = roles(changes, "designerViewRoles"),
            designerSaveRoles = roles(changes, "designerSaveRoles"),
            designerDeleteRoles = roles(changes, "designerDeleteRoles"),
            designerProtectRoles = roles(changes, "designerProtectRoles"),
            protectedResources = roles(changes, "protectedResources", "a list of module/kind/name resource paths"),
        )
    }

    private fun roles(changes: Map<String, Any?>, key: String, what: String = "a list of role names"): List<String> =
        when (val v = changes[key]) {
            null -> emptyList()
            else -> ProjectService.Settings.roleList(v) ?: throw Problems.unprocessable("$key must be $what")
        }

    private fun newName(body: Map<String, Any?>): String =
        projectName(body["name"] as? String ?: throw Problems.unprocessable("name (the new project name) is required"))

    companion object {
        const val BASE = "/api/v1/projects"

        fun actor(): String = AuditContext.current()?.actor ?: ConfigApi.ACTOR

        fun projectName(name: String): String {
            try {
                Scope.project(name)
            } catch (_: IllegalArgumentException) {
                throw Problems.unprocessable("'$name' is not a project name: lowercase letters, digits, and '-', 1 to 63 characters")
            }
            return name
        }

        fun body(ctx: Context): Map<String, Any?> {
            val parsed = try {
                Json.mapper.readValue(ctx.body().ifBlank { "{}" }, Any::class.java)
            } catch (e: com.fasterxml.jackson.core.JacksonException) {
                throw Problems.badRequest("malformed JSON: ${e.originalMessage}")
            }
            @Suppress("UNCHECKED_CAST")
            return (parsed as? Map<String, Any?>) ?: throw Problems.unprocessable("body must be an object")
        }

        fun json(ctx: Context, status: Int, value: Any?) {
            ctx.status(status).contentType("application/json").result(Json.write(value))
        }
    }
}

/**
 * Whether the admin has finished the home page's first-run steps (GAT-023): `org.oip.gateway/gateway/HomeSetup/home`,
 * `{complete: boolean}`. Written through `PUT /api/v1/home/setup`, so it is one config-change audit record.
 */
object HomeSetup {
    const val KIND = "HomeSetup"
    const val NAME = "home"
    val ID = ResourceId(GATEWAY_MODULE, Scope.GATEWAY, KIND, NAME)

    fun register(registry: SchemaRegistry) = registry.register(
        GATEWAY_MODULE, KIND,
        mapOf("type" to "object", "properties" to mapOf("complete" to mapOf("type" to "boolean", "default" to false))),
    )
}

/**
 * The gateway home page's data (GAT-023): `GET /api/v1/home` for any signed-in caller, filtered by what the caller
 * may do.
 *
 * - `links`: the admin pages and the HMI launcher with gateway.read, the designer with gateway.write.
 * - `projects`: launchable projects (enabled and runnable), with gateway.read, each with its HMI link and, with
 *   gateway.write, its designer link. A disabled, non-runnable, or inheritance-only project is left out.
 * - `setup`: the first-run steps, only for a caller with gateway.write, until one marks setup complete with
 *   `PUT /api/v1/home/setup {complete: true}` (gateway.write). Each step's `done` is read from the gateway's state.
 *
 */
class HomeApi(private val gw: Gateway) {
    fun register(app: Javalin) {
        app.get(PATH) { ctx -> ProjectsApi.json(ctx, 200, document(ctx)) }
        app.put(SETUP_PATH) { ctx ->
            val body = ProjectsApi.body(ctx)
            val unknown = body.keys - setOf("complete")
            if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown setup fields: ${unknown.sorted().joinToString()}")
            val complete = body["complete"] as? Boolean ?: throw Problems.unprocessable("complete must be a boolean")
            val version = gw.store.get(HomeSetup.ID)?.metadata?.version ?: 0L
            gw.store.put(
                Scope.GATEWAY,
                Resource(HomeSetup.KIND, Metadata(HomeSetup.NAME, GATEWAY_MODULE, version = version), mapOf("complete" to complete)),
                ProjectsApi.actor(),
            )
            ProjectsApi.json(ctx, 200, document(ctx))
        }
    }

    private fun document(ctx: Context): Map<String, Any?> {
        val (subject, connection) = GatewayAuth.subject(ctx)
        fun may(permission: String) = gw.security.authorization.authorize(subject, permission, connection).allowed
        val read = may(Permissions.GATEWAY_READ)
        val write = may(Permissions.GATEWAY_WRITE)
        val links = buildList {
            if (read) add(link("admin", "Gateway admin", "/admin"))
            if (write) add(link("designer", "Designer", "/studio"))
            if (read) add(link("hmi", "HMI sessions", "/hmi/"))
        }
        val infos = if (read) gw.projectService.list() else emptyList()
        val projects = infos.filter { it.settings.launchable }.map { info ->
            linkedMapOf(
                "name" to info.name,
                "title" to info.settings.title.ifBlank { info.name },
                "description" to info.settings.description,
                "hmi" to "/hmi/${info.name}/",
                "designer" to if (write) "/studio?project=${info.name}" else null,
            )
        }
        val setup = if (!write) null else {
            val complete = gw.store.get(HomeSetup.ID)?.spec?.get("complete") == true
            val users = gw.store.list(SECURITY_MODULE, Scope.GATEWAY, Kinds.USER).size
            linkedMapOf(
                "complete" to complete,
                "steps" to listOf(
                    step("system-name", "Name this gateway", gw.platformSettings.get().systemName.isNotBlank(), "/admin/gateway-settings"),
                    step("users", "Add a user account besides the first administrator", users > 1, "/admin/users"),
                    step("plc-resources", "Choose the PLC and ERIC resource split", gw.plcResources.configured(), "/admin/plc-resources"),
                    step("project", "Create a project", infos.isNotEmpty(), "/admin/projects"),
                ),
            )
        }
        return linkedMapOf(
            "systemName" to gw.platformSettings.effectiveSystemName(),
            "user" to subject.principal,
            "links" to links,
            "projects" to projects,
            "projectsHidden" to !read,
            "setup" to setup,
        )
    }

    private fun link(id: String, label: String, href: String) = linkedMapOf("id" to id, "label" to label, "href" to href)

    private fun step(id: String, label: String, done: Boolean, href: String) =
        linkedMapOf("id" to id, "label" to label, "done" to done, "href" to href)

    companion object {
        const val PATH = "/api/v1/home"
        const val SETUP_PATH = "/api/v1/home/setup"
    }
}
