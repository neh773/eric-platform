package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.host.DependencyException
import org.oip.host.ModuleHost
import org.oip.host.UpgradeException
import org.oip.sdk.ManifestException
import org.oip.sdk.ModuleManifest
import org.oip.security.AuditActions
import org.oip.security.AuditContext
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.security.SecurityService
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * The gateway side of built-in modules (see [BuiltInModules]): which ones the operator removed, and how to
 * install one again. Calls arrive with the module host locked.
 */
interface BuiltInModuleControl {
    fun isBuiltIn(id: String): Boolean
    /** Built-in modules that are not installed because the operator removed them. */
    fun available(): List<ModuleManifest>
    /** Record that [id] was uninstalled, so a restart does not install it again. */
    fun removed(id: String)
    /** Install and start [id] again. */
    fun install(id: String): ModuleManifest
}

/**
 * `/api/v1/modules`: list, install (POST zip), upgrade (PUT zip), uninstall (DELETE). Host access is serialized.
 * `GET` also lists the built-in modules the operator removed (`available`), and
 * `POST /api/v1/modules/{id}/install` installs one of those again (review-1 F5).
 * A successful install, upgrade, or uninstall appends one audit row. The public reference records those
 * gateway module actions: -
 */
class ModulesApi(
    private val host: ModuleHost,
    private val security: SecurityService,
    private val clock: Clock,
    private val builtIns: BuiltInModuleControl? = null,
) {
    fun register(app: Javalin) {
        app.get("/api/v1/modules") { ctx ->
            json(ctx, 200, locked {
                mapOf("items" to host.installed().map { info(it) }, "available" to (builtIns?.available() ?: emptyList()).map { available(it) })
            })
        }
        app.get("/api/v1/modules/{id}") { ctx ->
            json(ctx, 200, locked { info(host.manifest(id(ctx))?.takeIf { host.state(it.id) != null } ?: throw Problems.notFound("module '${id(ctx)}' not installed")) })
        }
        app.post("/api/v1/modules") { ctx ->
            val m = locked { mapErrors {
                val installed = host.installPackage(ctx.bodyAsBytes())
                audit(AuditActions.MODULE_INSTALLED, installed.id, "version=${installed.version}")
                host.start(installed.id)
                installed
            } }
            ctx.header("Location", "/api/v1/modules/${m.id}")
            json(ctx, 201, locked { info(m) })
        }
        app.put("/api/v1/modules/{id}") { ctx ->
            val bytes = ctx.bodyAsBytes()
            val m = locked { mapErrors {
                val pkgId = org.oip.host.ModulePackage.read(bytes).manifest.id
                if (pkgId != id(ctx)) throw Problems.unprocessable("package id '$pkgId' does not match path '${id(ctx)}'")
                val from = host.manifest(id(ctx))?.version?.toString()
                val upgraded = host.upgradePackage(bytes)
                audit(AuditActions.MODULE_UPGRADED, upgraded.id, "from=$from;version=${upgraded.version}")
                upgraded
            } }
            json(ctx, 200, locked { info(m) })
        }
        app.delete("/api/v1/modules/{id}") { ctx ->
            locked { mapErrors {
                if (host.state(id(ctx)) == null) throw Problems.notFound("module '${id(ctx)}' not installed")
                val version = host.manifest(id(ctx))?.version?.toString()
                val moduleId = id(ctx)
                host.uninstall(moduleId, removeFiles = true)
                if (builtIns?.isBuiltIn(moduleId) == true) builtIns.removed(moduleId)
                audit(AuditActions.MODULE_UNINSTALLED, moduleId, if (version == null) "" else "version=$version")
            } }
            ctx.status(204)
        }
        app.post("/api/v1/modules/{id}/install") { ctx ->
            val m = locked { mapErrors {
                val moduleId = id(ctx)
                val control = builtIns?.takeIf { it.isBuiltIn(moduleId) } ?: throw Problems.notFound("'$moduleId' is not a built-in module")
                if (host.state(moduleId) != null) throw Problems.conflict("module '$moduleId' is already installed")
                val installed = control.install(moduleId)
                audit(AuditActions.MODULE_INSTALLED, installed.id, "version=${installed.version};builtIn=true")
                installed
            } }
            ctx.header("Location", "/api/v1/modules/${m.id}")
            json(ctx, 201, locked { info(m) })
        }
    }

    /** One success row. A failed install, upgrade, or uninstall does not get here. Audit errors do not roll back the module change. */
    private fun audit(action: String, moduleId: String, detail: String) {
        try {
            if (!security.auditProfile.get().enabled) return
            val origin = AuditContext.current()
            security.audit.append(
                AuditDraft(
                    timestampMillis = clock.millis(),
                    actor = origin?.actor ?: "system",
                    action = action,
                    status = AuditStatus.SUCCESS,
                    actorHost = origin?.host ?: "",
                    target = moduleId,
                    detail = detail,
                    context = origin?.context ?: "",
                )
            )
        } catch (e: Exception) {
            LoggerFactory.getLogger(ModulesApi::class.java).warn("audit record for {} {} was not written: {}", action, moduleId, e.message)
        }
    }

    private fun id(ctx: Context) = ctx.pathParam("id")
    private fun <T> locked(f: () -> T): T = synchronized(host) { f() }
    private fun <T> mapErrors(f: () -> T): T = try { f() } catch (e: Throwable) { throw when (e) {
        is ProblemException -> e
        is ManifestException, is ClassNotFoundException, is NoClassDefFoundError, is ClassCastException -> Problems.unprocessable(e.message ?: e.javaClass.name)
        is UpgradeException, is DependencyException -> Problems.conflict(e.message!!)
        else -> Problems.of(e)
    } }

    private fun info(m: ModuleManifest) = linkedMapOf("id" to m.id, "version" to m.version.toString(), "name" to m.name,
        "state" to host.state(m.id)?.name, "permissions" to m.permissions,
        "dependencies" to m.dependencies.map { mapOf("id" to it.id, "version" to it.minVersion.toString()) },
        "error" to host.lastError(m.id))

    private fun available(m: ModuleManifest) = linkedMapOf("id" to m.id, "version" to m.version.toString(), "name" to m.name, "builtIn" to true)

    private fun json(ctx: Context, status: Int, body: Any) { ctx.status(status).contentType("application/json").result(Json.write(body)) }
}
