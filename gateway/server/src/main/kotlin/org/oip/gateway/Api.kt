@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import org.oip.backup.BackupCipher
import org.oip.backup.ConflictPolicy
import org.oip.network.ServicePolicy
import org.oip.security.AuditActions
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * REST API under `/api/v1`. Config and module routes are [ConfigApi] and [ModulesApi].
 * Users, roles, and API keys are [SecurityApi]. Errors are RFC 9457 problem+json via [Problems].
 */
class Api(private val gw: Gateway) {
    private val securityGuard by lazy { SecurityResourceGuard(gw.security.authorization, gw.designerGuard) }

    fun register(app: Javalin) {
        Problems.install(app)
        app.exception(Exception::class.java) { e, ctx ->
            val problem = Problems.of(e)
            if (problem.status >= 500) gw.logs.log(LogLevel.ERROR, "org.oip.http", "request failed", mapOf("error" to e.javaClass.simpleName))
            Problems.write(ctx, problem)
        }
        app.before { ctx -> ctx.attribute("oip-t0", System.nanoTime()) }
        // Force-HTTPS redirect runs before authentication so a plain-HTTP request is 308, not 401.
        app.before { ctx ->
            val target = gw.https.redirectTarget(ctx.viaScheme(gw), ctx.viaHost(gw), ctx.path(), ctx.req().queryString)
                ?: return@before
            ctx.header("Location", target)
            ctx.header("Cache-Control", "no-store")
            ctx.status(308).result("")
            ctx.skipRemainingHandlers()
        }
        GatewayAuth(gw).register(app)
        app.after { ctx ->
            runCatching {
                val t0 = ctx.attribute<Long>("oip-t0") ?: return@runCatching
                val nanos = System.nanoTime() - t0
                // matchedPath() is overwritten by the AFTER endpoint ("*"); endpointHandlerPath stays the route.
                val route = runCatching { ctx.endpointHandlerPath() }.getOrNull()?.takeIf { it.isNotBlank() } ?: ctx.path()
                gw.diagnostics.latency.record(ctx.method().name, route, nanos)
                gw.logs.log(LogLevel.DEBUG, "org.oip.http", "request", mapOf(
                    "method" to ctx.method().name,
                    "path" to ctx.path(),
                    "status" to ctx.status().code,
                    "micros" to nanos / 1_000,
                ))
            }
        }
        app.get("/api/docs") { ctx -> ctx.contentType("application/json").result(Json.write(OpenApi.document)) }
        ConfigApi(gw.store, securityGuard, gw.projectService, gw.limitGuard).register(app)
        SchemaApi(gw.store.registry).register(app)
        TagApi(gw.tags, gw.security.zones, org.oip.tags.UdtInstancePlanner(gw.tags, gw.store, gw.store.registry), gw.store).register(app)
        TagExchangeApi(gw.tags, gw.store).register(app)
        AlarmApi(gw.alarms, gw.network, gw.security, gw.clock, gw.tags, gw.journalStorage).register(app)
        HistoryApi(gw.historyCollector, gw.historyRouter, gw.security.authorization, gw.tags, gw.security.zones, gw.diskCacheArchives) { action, target, detail ->
            auditAdmin(action, target, detail)
        }.register(app)
        NotificationApi(gw.notifications).register(app)
        ModulesApi(gw.host, gw.security, gw.clock, gw.builtInModules).register(app)
        SecurityApi(gw).register(app)
        IdentityProviderApi(gw).register(app)
        UserSourceApi(gw).register(app)
        gw.idp.register(app)
        DatabaseApi(gw).register(app)
        OpcUaTrustApi(gw).register(app)
        gw.loginLevels.register(app)
        OutboundTrustApi(gw).register(app)
        ModbusMappingApi(gw.store).register(app)
        TagDeviceApi(gw).register(app)
        SfcApi(gw).register(app)
        SecretsApi(gw).register(app)
        LogicApi(gw).register(app)
        RestartApi(gw).register(app)
        PlcResourcesApi(gw).register(app)
        app.get("/api/v1/platform") { ctx -> json(ctx, HostPlatform.body()) }
        EventStreamApi(gw).register(app)
        gw.webdev.register(app)
        ReportApi(gw).register(app)
        DesignerConsole(gw).register(app)
        ConnectionsApi(gw).register(app)
        DesignerSessionApi(gw).register(app)
        DesignerProjectApi(gw).register(app)
        EmailProfileApi(gw).register(app)
        OAuth2ClientApi(gw).register(app)
        httpsRoutes(app)
        gatewayNetworkRoutes(app)
        app.get("/api/v1/health") { ctx ->
            json(ctx, mapOf(
                "status" to "UP",
                "profile" to gw.settings.profile,
                "version" to Gateway.VERSION,
                "modules" to gw.host.installed().size,
                // 0.1.1: a plain-HTTP gateway viewed from its own machine is the normal first run, not a problem;
                // the Network page carries a calm note instead. Other browsers still see the warning.
                "warnings" to listOfNotNull(gw.insecureCookieWarning?.takeUnless { gw.settings.plainHttpOnly() && fromThisMachine(ctx) }),
                "startedAt" to gw.startedAt?.toString(),
            ))
        }
        backupRoutes(app)
        projectRoutes(app)
        logRoutes(app)
        diagnosticsRoutes(app)
        edgeSyncRoutes(app)
        org.oip.eam.EamApi(gw).register(app)
        deploymentModeRoutes(app)
        gw.platformSettings.register(app)
    }

    /**
     * `/api/v1/deployment-mode` (GAT-033, review-1 F11): read and set the active deployment mode. [GatewayAuth]
     * requires gateway.read to read and gateway.write to set. The mode is the config resource
     * `org.oip.gateway/gateway/DeploymentMode/active`, so a switch is one config-change audit record. It applies
     * to the next effective-config read without a restart (DIFFERENCES 210). `modes` lists the modes that have
     * overlay resources; any valid mode name can be set.
     */
    private fun deploymentModeRoutes(app: Javalin) {
        fun document() = linkedMapOf("mode" to gw.store.activeMode(), "modes" to gw.store.overlayModes())
        app.get("/api/v1/deployment-mode") { ctx -> json(ctx, document()) }
        app.put("/api/v1/deployment-mode") { ctx ->
            val body = objectBody(ctx)
            if (!body.containsKey("mode")) throw Problems.unprocessable("mode is required (a mode name, or null for none)")
            val mode = when (val value = body["mode"]) {
                null -> null
                is String -> value
                else -> throw Problems.unprocessable("mode must be a string or null")
            }
            if (mode != null) {
                try {
                    org.oip.config.Scope.mode(mode)
                } catch (_: IllegalArgumentException) {
                    throw Problems.unprocessable("invalid mode name '$mode': lowercase letters, digits, and '-', 1 to 63 characters")
                }
            }
            if (mode != gw.store.activeMode()) {
                gw.store.setActiveMode(mode, org.oip.security.AuditContext.current()?.actor ?: ConfigApi.ACTOR)
            }
            json(ctx, document())
        }
    }

    /**
     * `/api/v1/https*`: listener status, certificate upload, CSR, CA reply, TLS policy, and saved
     * web-server settings. [GatewayAuth] requires `gateway.read` for reads and `gateway.write` for every
     * other verb, and audits a denied write. Each mutating route here audits its outcome. No route returns a private key.
     */
    private fun httpsRoutes(app: Javalin) {
        app.get("/api/v1/https") { ctx ->
            json(ctx, gw.https.status())
        }
        app.put("/api/v1/https/certificate") { ctx ->
            val length = ctx.contentLength()
            if (length > TlsCertificates.MAX_BYTES) throw Problems.unprocessable("certificate upload is larger than 256 KiB")
            val bodyText = ctx.body()
            if (bodyText.length > TlsCertificates.MAX_BYTES) throw Problems.unprocessable("certificate upload is larger than 256 KiB")
            val body = try {
                Json.parse(bodyText)
            } catch (_: Exception) {
                throw Problems.badRequest("body must be a JSON object")
            }
            try {
                json(ctx, gw.https.replaceUpload(body))
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message ?: "certificate was rejected")
            } catch (e: IllegalStateException) {
                throw Problems.unprocessable(e.message ?: "certificate was rejected")
            }
        }
        app.get("/api/v1/https/csr") { ctx ->
            val pending = gw.https.pendingCsr() ?: throw Problems.notFound("no certificate request is pending")
            if (ctx.queryParam("download") == "true") {
                ctx.contentType("application/pkcs10")
                ctx.header("Content-Disposition", "attachment; filename=\"gateway.csr\"")
                ctx.result(pending["csrPem"] as String)
            } else {
                json(ctx, pending)
            }
        }
        app.post("/api/v1/https/csr") { ctx ->
            httpsWrite(ctx) {
                val view = gw.https.createCsr(boundedBody(ctx))
                "subject=${view["subject"]};publicKeySha256=${view["publicKeySha256"]}" to view
            }
        }
        app.delete("/api/v1/https/csr") { ctx ->
            httpsWrite(ctx) {
                if (!gw.https.discardCsr()) throw NoSuchElementException("no certificate request is pending")
                "discarded" to gw.https.status()
            }
        }
        app.post("/api/v1/https/csr/install") { ctx ->
            httpsWrite(ctx) {
                val status = gw.https.installSignedChain(boundedBody(ctx))
                @Suppress("UNCHECKED_CAST")
                val fingerprint = (status["certificate"] as? Map<String, Any?>)?.get("sha256Fingerprint")
                "fingerprint=$fingerprint" to status
            }
        }
        app.put("/api/v1/https/tls") { ctx ->
            httpsWrite(ctx) {
                val status = gw.https.updateTls(boundedBody(ctx))
                "protocols=${status["configuredProtocols"]};cipherSuites=${status["configuredCipherSuites"] ?: "default"}" to status
            }
        }
        app.put("/api/v1/https/settings") { ctx ->
            httpsWrite(ctx) {
                val status = gw.https.updateSettings(boundedBody(ctx))
                @Suppress("UNCHECKED_CAST")
                val saved = (status["savedSettings"] as? Map<String, Any?>).orEmpty()
                    .filterKeys { it in HttpsEndpoint.SETTINGS_KEYS }
                saved.entries.joinToString(";") { "${it.key}=${it.value}" } to status
            }
        }
    }

    /**
     * Run one mutating HTTPS route and audit the outcome. The route table in [GatewayAuth] has already
     * required `gateway.write` and audited a denial as the resolved caller. [work] returns the audit detail
     * and the response body. Validation failures become 422 with a message that never contains key material.
     */
    private fun httpsWrite(ctx: Context, work: () -> Pair<String, Any?>) {
        val (action, target) = HttpsEndpoint.writeRoute(ctx.req().method, ctx.path())
            ?: error("${ctx.req().method} ${ctx.path()} is not a mutating HTTPS route")
        val (detail, body) = try {
            work()
        } catch (e: ProblemException) {
            auditHttps(action, target, "rejected: ${safeDetail(e.message)}", AuditStatus.FAILURE)
            throw e
        } catch (e: NoSuchElementException) {
            auditHttps(action, target, "rejected: ${safeDetail(e.message)}", AuditStatus.FAILURE)
            throw Problems.notFound(safeDetail(e.message))
        } catch (e: IllegalArgumentException) {
            auditHttps(action, target, "rejected: ${safeDetail(e.message)}", AuditStatus.FAILURE)
            throw Problems.unprocessable(safeDetail(e.message))
        } catch (e: IllegalStateException) {
            auditHttps(action, target, "rejected: ${safeDetail(e.message)}", AuditStatus.FAILURE)
            throw Problems.unprocessable(safeDetail(e.message))
        }
        auditHttps(action, target, detail, AuditStatus.SUCCESS)
        json(ctx, body)
    }

    private fun boundedBody(ctx: Context): Map<String, Any?> {
        if (ctx.contentLength() > TlsCertificates.MAX_BYTES) throw Problems.unprocessable("request is larger than 256 KiB")
        val text = ctx.body()
        if (text.length > TlsCertificates.MAX_BYTES) throw Problems.unprocessable("request is larger than 256 KiB")
        return try {
            Json.parse(text)
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
    }

    private fun auditHttps(action: String, target: String, detail: String, status: AuditStatus) {
        runCatching {
            if (!gw.security.auditProfile.get().enabled) return@runCatching
            gw.security.audit.append(
                AuditDraft(
                    timestampMillis = gw.clock.millis(),
                    actor = org.oip.security.AuditContext.current()?.actor ?: "api",
                    action = action,
                    status = status,
                    actorHost = org.oip.security.AuditContext.current()?.host ?: "",
                    target = target,
                    detail = detail.take(500),
                    context = "gateway-https",
                ),
            )
        }
    }

    /**
     * One success row for a backup, restore, or log-level change (review-1 F4), with the request's actor and host,
     * while the audit profile is enabled. An audit failure is logged and does not undo the action.
     */
    private fun auditAdmin(action: String, target: String, detail: String) {
        try {
            if (!gw.security.auditProfile.get().enabled) return
            val origin = org.oip.security.AuditContext.current()
            gw.security.audit.append(
                AuditDraft(
                    timestampMillis = gw.clock.millis(),
                    actor = origin?.actor ?: "api",
                    action = action,
                    status = AuditStatus.SUCCESS,
                    actorHost = origin?.host ?: "",
                    target = target,
                    detail = detail.take(500),
                    context = origin?.context ?: "",
                ),
            )
        } catch (e: Exception) {
            gw.logs.log(LogLevel.WARN, "org.oip.security", "audit record not written", mapOf("action" to action, "error" to e.javaClass.simpleName))
        }
    }

    /** Messages from the HTTPS code never carry keys, but a PEM marker in one is still replaced. */
    private fun safeDetail(message: String?): String {
        val text = message ?: "request was rejected"
        return if (text.contains("PRIVATE") || text.contains("-----") || text.length > 300) "request was rejected" else text
    }

    private fun gatewayNetworkRoutes(app: Javalin) {
        app.get("/api/v1/gateway-network") { ctx -> json(ctx, gw.network.statusDocument()) }
        app.post("/api/v1/gateway-network/outgoing") { ctx ->
            val body = objectBody(ctx)
            gw.network.addOutgoing(
                name = text(body, "name"),
                host = text(body, "host"),
                port = intField(body, "port"),
                enabled = body["enabled"] as? Boolean ?: true,
                description = body["description"] as? String ?: "",
            )
            json(ctx, gw.network.statusDocument())
        }
        app.post("/api/v1/gateway-network/outgoing/{name}/approve") { ctx ->
            gw.network.approveOutgoing(ctx.pathParam("name"))
            json(ctx, gw.network.statusDocument())
        }
        app.post("/api/v1/gateway-network/outgoing/{name}/deny") { ctx ->
            gw.network.denyOutgoing(ctx.pathParam("name"))
            json(ctx, gw.network.statusDocument())
        }
        app.post("/api/v1/gateway-network/incoming/{fingerprint}/approve") { ctx ->
            json(ctx, gw.network.approveIncoming(ctx.pathParam("fingerprint")))
        }
        app.post("/api/v1/gateway-network/incoming/{fingerprint}/deny") { ctx ->
            json(ctx, gw.network.denyIncoming(ctx.pathParam("fingerprint")))
        }
        app.put("/api/v1/gateway-network/policies") { ctx ->
            val body = objectBody(ctx)
            val roles = (body["roles"] as? List<*>)?.map { it as? String ?: throw Problems.unprocessable("roles must be strings") }
                ?: emptyList()
            gw.network.putPolicy(ServicePolicy(text(body, "peer"), text(body, "service"), text(body, "access"), roles))
            json(ctx, gw.network.statusDocument())
        }
        app.delete("/api/v1/gateway-network/policies") { ctx ->
            val peer = ctx.queryParam("peer") ?: throw Problems.badRequest("peer is required")
            val service = ctx.queryParam("service") ?: throw Problems.badRequest("service is required")
            gw.network.removePolicy(peer, service)
            json(ctx, gw.network.statusDocument())
        }
        app.post("/api/v1/gateway-network/providers") { ctx ->
            val body = objectBody(ctx)
            gw.network.addRemoteProvider(
                name = text(body, "name"),
                connection = text(body, "connection"),
                provider = body["provider"] as? String ?: "default",
                enabled = body["enabled"] as? Boolean ?: true,
            )
            json(ctx, gw.network.statusDocument())
        }
        app.post("/api/v1/gateway-network/certificate/regenerate") { ctx ->
            json(ctx, gw.network.regenerateCertificate())
        }
        app.post("/api/v1/gateway-network/connections/{name}/tasks") { ctx ->
            val body = objectBody(ctx)
            json(ctx, gw.network.submitTask(ctx.pathParam("name"), text(body, "name"), body["argument"] as? String ?: ""))
        }
        app.get("/api/v1/gateway-network/connections/{name}/tasks/{id}") { ctx ->
            json(ctx, gw.network.taskStatus(ctx.pathParam("name"), ctx.pathParam("id")))
        }
        app.post("/api/v1/gateway-network/connections/{name}/tasks/{id}/cancel") { ctx ->
            json(ctx, gw.network.cancelTask(ctx.pathParam("name"), ctx.pathParam("id")))
        }
    }

    private fun objectBody(ctx: Context): Map<String, Any?> {
        val body = try {
            Json.parse(ctx.body())
        } catch (_: Exception) {
            throw Problems.badRequest("body must be a JSON object")
        }
        return body
    }

    private fun text(body: Map<String, Any?>, key: String): String =
        body[key] as? String ?: throw Problems.unprocessable("$key is required")

    private fun intField(body: Map<String, Any?>, key: String): Int =
        (body[key] as? Number)?.toInt() ?: throw Problems.unprocessable("$key must be a number")

    /**
     * Every backup the gateway creates, stores, or returns is sealed with [BackupCipher] under a passphrase the
     * caller supplies, so a copy of `<dataDir>/backups/` or a download never carries `secrets.key` in clear
     * (review-1 F3). The gateway does not keep the passphrase. A `.zip` left in `backups/` by an older version
     * is plaintext: it is listed with `encrypted: false`, can be restored, and is never served for download.
     */
    private fun fromThisMachine(ctx: io.javalin.http.Context): Boolean {
        if (ctx.header("X-Forwarded-For") != null || ctx.header("Forwarded") != null) return false
        return runCatching { java.net.InetAddress.getByName(ctx.req().remoteAddr).isLoopbackAddress }.getOrDefault(false)
    }

    private fun backupRoutes(app: Javalin) {
        app.post("/api/v1/backups") { ctx ->
            val bundle = when (ctx.queryParam("bundleModules")) {
                null, "false" -> false
                "true" -> true
                else -> throw IllegalArgumentException("bundleModules must be true or false")
            }
            val pass = passphrase(objectBody(ctx))
            val bytes = try {
                gw.backups.createEncrypted(pass, bundle)
            } finally {
                pass.fill('\u0000')
            }
            val name = storeBackup(bytes)
            auditAdmin(AuditActions.BACKUP_CREATED, name, "bundleModules=$bundle;bytes=${bytes.size};encrypted=true")
            ctx.contentType(BACKUP_MEDIA_TYPE)
            ctx.header("Content-Disposition", "attachment; filename=\"$name\"")
            ctx.header("X-Backup-Name", name)
            ctx.result(bytes)
        }
        app.get("/api/v1/backups") { ctx ->
            val dir = gw.dataDir.resolve("backups")
            val items = if (!dir.exists()) emptyList() else dir.listDirectoryEntries()
                .filter { BACKUP_NAME.matches(it.name) }
                .sortedBy { it.name }
                .map { mapOf("name" to it.name, "bytes" to Files.size(it), "encrypted" to it.name.endsWith(SEALED_SUFFIX)) }
            json(ctx, mapOf("backups" to items))
        }
        app.post("/api/v1/backups/restore") { ctx ->
            val archive: ByteArray
            if (ctx.contentType()?.substringBefore(';')?.trim().equals("application/json", ignoreCase = true)) {
                val body = objectBody(ctx)
                val encoded = body["archive"] as? String ?: throw Problems.unprocessable("archive is required (base64)")
                archive = try {
                    java.util.Base64.getMimeDecoder().decode(encoded)
                } catch (_: IllegalArgumentException) {
                    throw Problems.unprocessable("archive must be base64")
                }
                restoreArchive(archive, body)
            } else {
                archive = ctx.bodyAsBytes()
                restoreArchive(archive, null)
            }
            // Written after the restore: the archive's audit log replaces the live one.
            auditAdmin(AuditActions.BACKUP_RESTORED, "upload", "bytes=${archive.size};encrypted=${BackupCipher.isEncrypted(archive)}")
            json(ctx, mapOf("restored" to true))
        }
        app.get("/api/v1/backups/{name}") { ctx ->
            val file = backupFile(ctx.pathParam("name"))
            if (!file.name.endsWith(SEALED_SUFFIX)) {
                throw Problems.conflict("${file.name} was stored without encryption by an older version and is not served; " +
                    "restore it, create a new encrypted backup, then delete the old file")
            }
            ctx.contentType(BACKUP_MEDIA_TYPE)
            ctx.header("Content-Disposition", "attachment; filename=\"${file.name}\"")
            ctx.result(Files.readAllBytes(file))
        }
        app.post("/api/v1/backups/{name}/restore") { ctx ->
            val file = backupFile(ctx.pathParam("name"))
            val archive = Files.readAllBytes(file)
            restoreArchive(archive, if (ctx.bodyAsBytes().isEmpty()) null else objectBody(ctx))
            auditAdmin(AuditActions.BACKUP_RESTORED, file.name, "bytes=${archive.size};encrypted=${BackupCipher.isEncrypted(archive)}")
            json(ctx, mapOf("restored" to true))
        }
    }

    /** A sealed archive needs `passphrase` in [body]; a wrong one fails before anything changes. */
    /**
     * Restore, then announce every config change to the running subsystems (tags, devices, alarms, scripts), so
     * restored tags have values without a gateway restart. Same as the EAM and first-boot restores.
     */
    private fun restoreArchive(archive: ByteArray, body: Map<String, Any?>?) {
        val before = gw.store.list().associate { it.id to it.resource }
        restoreArchiveFiles(archive, body)
        gw.store.announceReplaced(before)
    }

    private fun restoreArchiveFiles(archive: ByteArray, body: Map<String, Any?>?) {
        if (!BackupCipher.isEncrypted(archive)) {
            gw.backups.restore(archive)
            return
        }
        if (body == null || body["passphrase"] == null) {
            throw Problems.unprocessable("this backup is encrypted: passphrase is required")
        }
        val pass = passphrase(body, forNewBackup = false)
        try {
            gw.backups.restoreEncrypted(archive, pass)
        } finally {
            pass.fill('\u0000')
        }
    }

    private fun passphrase(body: Map<String, Any?>, forNewBackup: Boolean = true): CharArray {
        val text = body["passphrase"] as? String ?: throw Problems.unprocessable("passphrase is required")
        if (text.isEmpty()) throw Problems.unprocessable("passphrase must not be empty")
        val chars = text.toCharArray()
        if (forNewBackup) {
            try {
                BackupCipher.requireNewPassphrase(chars)
            } catch (e: IllegalArgumentException) {
                chars.fill('\u0000')
                throw Problems.unprocessable(e.message!!)
            }
        }
        return chars
    }

    private fun projectRoutes(app: Javalin) {
        // List, create, settings, rename, copy, delete (PRO-004).
        ProjectsApi(gw, securityGuard).register(app)
        ExamplesApi(gw).register(app)
        HomeApi(gw).register(app)
        app.get("/api/v1/projects/{name}/export") { ctx ->
            val name = ctx.pathParam("name")
            // Users, roles, and API keys never leave the gateway in a project archive (review-1 F1).
            val bytes = gw.projects.export(name) { !SecurityResourceGuard.isCredential(it.module, it.kind) }
            ctx.contentType("application/zip")
            ctx.header("Content-Disposition", "attachment; filename=\"project-$name.zip\"")
            ctx.result(bytes)
        }
        app.post("/api/v1/projects/{name}/import") { ctx ->
            val policy = ConflictPolicy.parse(ctx.queryParam("policy") ?: throw IllegalArgumentException("policy is required (skip or overwrite)"))
            val name = ctx.pathParam("name")
            // SEC-003: one audit record for the whole import, however many resources it writes.
            val result = org.oip.config.ConfigBatches.run(
                "project/$name", ProjectsApi.actor(), org.oip.security.AuditActions.PROJECT_IMPORTED, "project/$name", "policy=${ctx.queryParam("policy")}",
            ) {
                gw.projects.import(name, ctx.bodyAsBytes(), policy) { id ->
                    securityGuard.checkWrite(ctx, id.module, id.kind)
                    // SEC-020: an import saves the project, so its Designer permissions, protection and locks apply.
                    gw.designerGuard.checkImport(ctx, id)
                }
            }
            json(ctx, mapOf("project" to result.project, "policy" to result.policy, "created" to result.created,
                "overwritten" to result.overwritten, "skipped" to result.skipped))
        }
    }

    private fun logRoutes(app: Javalin) {
        app.get("/api/v1/logs") { ctx ->
            val min = ctx.queryParam("minLevel")?.let { LogLevel.parse(it) } ?: LogLevel.TRACE
            val limit = ctx.queryParam("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("limit must be an integer") } ?: 200
            if (limit !in 1..2_000) throw IllegalArgumentException("limit must be 1..2000")
            val records = gw.logs.query(min, ctx.queryParam("logger"), ctx.queryParam("q"), limit).map {
                linkedMapOf("ts" to it.ts, "level" to it.level.name, "logger" to it.logger, "message" to it.message, "fields" to it.fields)
            }
            json(ctx, mapOf("records" to records))
        }
        app.get("/api/v1/logs/download") { ctx ->
            ctx.contentType("application/x-ndjson").result(gw.logs.download())
        }
        app.get("/api/v1/logs/levels") { ctx ->
            json(ctx, mapOf("default" to gw.logs.defaultLevel.name, "levels" to gw.logs.configuredLevels()))
        }
        app.put("/api/v1/logs/levels") { ctx ->
            val body = try { Json.parse(ctx.body()) } catch (e: Exception) { throw IllegalArgumentException("body must be a JSON object") }
            val logger = body["logger"] as? String ?: throw IllegalArgumentException("logger is required")
            val level = LogLevel.parse(body["level"] as? String ?: throw IllegalArgumentException("level is required"))
            gw.logs.setLevel(logger, level)
            auditAdmin(AuditActions.LOG_LEVEL_CHANGED, logger, "level=${level.name}")
            json(ctx, mapOf("logger" to logger, "level" to level.name))
        }
    }

    private fun diagnosticsRoutes(app: Javalin) {
        app.get("/api/v1/diagnostics/threads") { ctx -> json(ctx, gw.diagnostics.threads()) }
        app.get("/api/v1/diagnostics/threads/dump") { ctx -> ctx.contentType("text/plain; charset=utf-8").result(gw.diagnostics.threadDump()) }
        app.get("/api/v1/diagnostics/memory") { ctx -> json(ctx, gw.diagnostics.memory()) }
        app.get("/api/v1/diagnostics/modules") { ctx -> json(ctx, gw.diagnostics.modules()) }
        app.get("/api/v1/diagnostics/config") { ctx -> json(ctx, gw.diagnostics.config()) }
        app.get("/api/v1/diagnostics/latency") { ctx -> json(ctx, gw.diagnostics.latency.snapshot()) }
        app.get("/api/v1/diagnostics/performance") { ctx -> json(ctx, gw.diagnostics.performance()) }
        app.get("/api/v1/diagnostics/metrics") { ctx -> json(ctx, gw.diagnostics.metricsSnapshot()) }
        app.get("/api/v1/diagnostics/scripts") { ctx -> json(ctx, mapOf("scripts" to gw.scripts.runningScripts())) }
        app.get("/api/v1/diagnostics/tasks") { ctx -> json(ctx, mapOf("tasks" to gw.diagnostics.tasks.snapshot())) }
        // OPC UA client connection status. Monitored items are those the server created.
        app.get("/api/v1/diagnostics/opcua/connections") { ctx -> json(ctx, gw.opcUaConnectionsDocument()) }
        app.get("/api/v1/diagnostics/environment") { ctx -> json(ctx, gw.environmentDocument()) }
        app.get("/api/v1/architectures") { ctx -> json(ctx, ArchitectureCatalog(gw).document()) }
        app.get("/api/v1/redundancy") { ctx -> json(ctx, gw.redundancy.statusDocument()) }
        app.put("/api/v1/redundancy") { ctx ->
            try {
                json(ctx, gw.saveRedundancy(objectBody(ctx)))
            } catch (e: IllegalArgumentException) {
                throw Problems.unprocessable(e.message ?: "redundancy settings were rejected")
            }
        }
        app.post("/api/v1/redundancy/failover") { ctx ->
            gw.redundancy.requestFailover()
            json(ctx, gw.redundancy.statusDocument())
        }
        app.post("/api/v1/redundancy/assume") { ctx ->
            gw.redundancy.assumeControl()
            json(ctx, gw.redundancy.statusDocument())
        }
        app.post("/api/v1/redundancy/resync") { ctx ->
            gw.redundancy.forceResync()
            json(ctx, gw.redundancy.statusDocument())
        }
    }

    private fun edgeSyncRoutes(app: Javalin) {
        app.get("/api/v1/edge-sync") { ctx -> json(ctx, gw.edgeSync.statusDocument()) }
        app.get("/api/v1/edge-sync/history") { ctx ->
            json(ctx, gw.edgeSync.historyDocument(ctx.queryParam("provider"), ctx.queryParam("source"), queryLimit(ctx)))
        }
        app.get("/api/v1/edge-sync/audit") { ctx ->
            json(ctx, gw.edgeSync.auditDocument(ctx.queryParam("profile"), ctx.queryParam("source"), queryLimit(ctx)))
        }
        app.get("/api/v1/edge-sync/alarms") { ctx ->
            json(ctx, gw.edgeSync.alarmDocument(ctx.queryParam("journal"), ctx.queryParam("source"), queryLimit(ctx)))
        }
        app.post("/api/v1/edge-sync/sync") { ctx -> json(ctx, gw.edgeSync.syncOnce()) }
    }

    private fun queryLimit(ctx: Context): Int {
        val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 100
        if (limit !in 1..1000) throw IllegalArgumentException("limit must be 1..1000")
        return limit
    }

    private fun storeBackup(bytes: ByteArray): String {
        val dir = gw.dataDir.resolve("backups").createDirectories()
        val stamp = STAMP.format(Instant.now())
        var name = "$stamp$SEALED_SUFFIX"
        var i = 0
        while (dir.resolve(name).exists()) name = "$stamp-${i++}$SEALED_SUFFIX"
        Files.write(dir.resolve(name), bytes)
        return name
    }

    private fun backupFile(name: String): java.nio.file.Path {
        if (!BACKUP_NAME.matches(name)) throw IllegalArgumentException("bad backup name")
        val dir = gw.dataDir.resolve("backups").normalize()
        val file = dir.resolve(name).normalize()
        if (file.parent != dir || !Files.exists(file)) throw NoSuchElementException("no backup $name")
        return file
    }

    private fun json(ctx: Context, value: Any?) {
        ctx.contentType("application/json").result(Json.write(value))
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        private val BACKUP_NAME = Regex("""\d{8}-\d{6}-\d{3}(?:-\d+)?\.(?:oipbk|zip)""")
        /** Sealed backups. `.zip` is a plaintext archive stored by an older version. */
        private const val SEALED_SUFFIX = ".oipbk"
        private const val BACKUP_MEDIA_TYPE = "application/octet-stream"
    }
}
