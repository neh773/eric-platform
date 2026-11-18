package org.oip.eam

import org.oip.backup.ConflictPolicy
import org.oip.config.GATEWAY_MODULE
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.gateway.Gateway
import org.oip.gateway.Json
import org.oip.gateway.LogLevel
import org.oip.host.ModulePackage
import org.oip.security.AuditActions
import org.oip.security.AuditDraft
import org.oip.security.AuditStatus
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagExchange
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readText

/**
 * Enterprise administration on a running gateway: the [EamController] service and the [EamAgentClient] loop,
 * selected by the `EamSettings/eam` resource's role, on one daemon thread. The role can change at runtime
 * (designating a controller, EAM-014); the thread follows the stored resource.
 *
 * First boot (EAM-008): when `<dataDir>/eam-agent.json` exists and this gateway has no role yet, it is applied
 * once and renamed to `eam-agent.json.applied`. Keys: `controllerHost`, `controllerPort`, `controllerFingerprint`
 * (pins the controller's gateway-network certificate, so no manual approval is needed on this side),
 * `connection` (default `controller`), optional `backupControllerHost`, `backupControllerPort`,
 * `backupControllerFingerprint`, and `group`, `checkInMillis`. See docs/ops/enterprise-admin.md.
 * Doc source: FEATURES EAM rows (EAM-001 to EAM-017).
 */
class EamRuntime(private val gw: Gateway, private val dir: Path) : EamHost, AutoCloseable {
    override val store get() = gw.store
    override val clock get() = gw.clock
    override val network get() = gw.network
    val controller = EamController(this, dir.resolve("controller"))
    val agent = EamAgentClient(this)
    private val wake = Object()
    @Volatile private var thread: Thread? = null
    @Volatile private var stop = false
    @Volatile private var lastTick = 0L
    private val startedMillis = System.currentTimeMillis()

    /** The install directory the launcher exports; null when the gateway was not started by the launcher. */
    @Volatile var upgrade = GatewayUpgrade(System.getenv("OIP_INSTALL_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }, gw.dataDir)
    /** Test seams: how the process exits for a restart, and how long after the task result. */
    @Volatile var restartHook: (Int) -> Unit = { kotlin.system.exitProcess(it) }
    @Volatile var restartDelayMillis = 3_000L

    /** Test seam: replaces the CPU, memory, and disk percentages an agent reports. */
    @Volatile var metricsOverride: (() -> Map<String, Any?>)? = null

    override fun settings(): EamSettings = EamSettings.fromSpec(gw.store.get(EamSettings.ID)?.spec)

    fun updateSettings(next: EamSettings, actor: String): EamSettings {
        val version = gw.store.get(EamSettings.ID)?.metadata?.version ?: 0L
        gw.store.put(Scope.GATEWAY, Resource(EamSettings.KIND, Metadata(EamSettings.NAME, GATEWAY_MODULE, version = version), next.toSpec()), actor)
        poke()
        return settings()
    }

    fun start() {
        if (thread != null) return
        runCatching { checkUpgradeOnBoot() }
        runCatching { applyFirstBoot() }.onFailure {
            gw.logs.log(LogLevel.WARN, "org.oip.eam", "first-boot agent file not applied", mapOf("error" to (it.message ?: it.javaClass.simpleName)))
        }
        stop = false
        thread = Thread({ loop() }, "eam").apply {
            isDaemon = true
            start()
        }
    }

    /** A pending upgrade: the gateway has reached this point on the new version, so it is healthy; a missed deadline rolls back. */
    private fun checkUpgradeOnBoot() {
        when (val boot = upgrade.onBoot()) {
            GatewayUpgrade.Boot.Nothing -> Unit
            is GatewayUpgrade.Boot.RolledBack -> {
                log("", "upgrade", "rolled back: ${boot.reason}")
                restartHook(GatewayUpgrade.EXIT_RESTART_FOR_UPGRADE)
            }
            is GatewayUpgrade.Boot.Pending -> {
                upgrade.markHealthy()
                log("", "upgrade", "applied, now on ${boot.to}")
            }
        }
    }

    fun poke() = synchronized(wake) { wake.notifyAll() }

    fun onLink(name: String, up: Boolean) {
        val s = settings()
        if (up && s.role == EamRole.AGENT && (name == s.controller || name == s.backupController)) poke()
    }

    private fun loop() {
        while (!stop) {
            var delay = 1_000L
            try {
                val s = settings()
                when (s.role) {
                    EamRole.CONTROLLER -> {
                        if (serving()) controller.tick()
                        lastTick = clock.millis()
                    }
                    EamRole.AGENT -> {
                        agent.checkInOnce()
                        delay = agent.nextCheckInMillis.coerceIn(200, 3_600_000)
                    }
                    EamRole.NONE -> Unit
                }
                gw.tags.publishSystem("Gateway/EAM/Role", s.role.label, DataType.STRING)
            } catch (e: Exception) {
                gw.logs.log(LogLevel.WARN, "org.oip.eam", "enterprise administration pass failed", mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            }
            synchronized(wake) {
                if (!stop) runCatching { wake.wait(delay) }
            }
        }
    }

    override fun close() {
        stop = true
        poke()
        thread?.let { runCatching { it.join(2_000) } }
        thread = null
    }

    /** `GET /api/v1/eam` and `system.eam`'s status: this gateway's role, and the agent or controller summary. */
    fun statusDocument(): Map<String, Any?> {
        val s = settings()
        return linkedMapOf(
            "role" to s.role.label,
            "gatewayName" to gatewayName(),
            "settings" to s.toSpec(),
            "agent" to if (s.role == EamRole.AGENT) agent.statusDocument() else null,
            "controller" to if (s.role == EamRole.CONTROLLER) linkedMapOf(
                "serving" to serving(),
                "agents" to controller.agents().size,
                "online" to controller.agentViews().count { it["online"] == true },
                "pendingCertificates" to pendingCertificates(),
                "listenPort" to network.port().takeIf { it > 0 },
                "fingerprint" to runCatching { network.fingerprint() }.getOrNull(),
            ) else null,
        )
    }

    /** Incoming gateway-network certificates still waiting for approval: agents that cannot check in yet. */
    @Suppress("UNCHECKED_CAST")
    fun pendingCertificates(): List<Map<String, Any?>> =
        (network.statusDocument()["incoming"] as? List<Map<String, Any?>> ?: emptyList())
            .filter { it["state"] == "pending" }
            .map { linkedMapOf("fingerprint" to it["fingerprint"], "subject" to it["subject"], "gatewayName" to it["gatewayName"]) }

    // ----- first boot -----

    private fun applyFirstBoot() {
        val file = gw.dataDir.resolve(SEED_FILE)
        if (!file.exists()) return
        if (settings().role != EamRole.NONE) return
        val seed = Json.parse(file.readText())
        val connection = seed["connection"] as? String ?: "controller"
        fun link(prefix: String, name: String): String {
            val host = seed["${prefix}Host"] as? String ?: return ""
            val port = (seed["${prefix}Port"] as? Number)?.toInt() ?: 8060
            (seed["${prefix}Fingerprint"] as? String)?.takeIf { it.isNotBlank() }?.let { network.pinOutgoing(it) }
            if (!network.hasOutgoing(name)) network.addOutgoing(name, host, port, description = "enterprise administration controller")
            return name
        }
        val primary = link("controller", connection)
        require(primary.isNotEmpty()) { "$SEED_FILE needs controllerHost" }
        val backup = link("backupController", "$connection-backup")
        updateSettings(EamSettings(
            role = EamRole.AGENT,
            controller = primary,
            backupController = backup,
            group = seed["group"] as? String ?: "",
            checkInMillis = (seed["checkInMillis"] as? Number)?.toLong() ?: EamSettings.DEFAULT_CHECK_IN_MILLIS,
        ), "eam-first-boot")
        file.moveTo(gw.dataDir.resolve("$SEED_FILE.applied"), overwrite = true)
        gw.logs.log(LogLevel.INFO, "org.oip.eam", "first-boot agent file applied", mapOf("controller" to primary))
    }

    // ----- EamHost -----

    override fun gatewayName(): String = gw.settings.network.gatewayName

    override fun version(): String = Gateway.VERSION

    override fun serving(): Boolean = settings().role == EamRole.CONTROLLER && gw.redundancy.isActive()

    override fun backupPassphrase(): String {
        synchronized(this) {
            runCatching { return gw.security.secrets.getText(PASSPHRASE_SECRET) }
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val text = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            gw.security.secrets.putText(PASSPHRASE_SECRET, text, "enterprise administration agent backups")
            return text
        }
    }

    override fun inventory(): Map<String, Any?> {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val cpu = (os as? com.sun.management.OperatingSystemMXBean)?.cpuLoad?.takeIf { it >= 0 }?.times(100)
        val runtime = Runtime.getRuntime()
        val memory = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / runtime.maxMemory() * 100
        val store = runCatching { Files.getFileStore(gw.dataDir) }.getOrNull()
        val disk = store?.let { (1.0 - it.usableSpace.toDouble() / it.totalSpace) * 100 }
        val metrics = linkedMapOf<String, Any?>("cpuPercent" to cpu, "memoryPercent" to memory, "diskPercent" to disk)
        metricsOverride?.invoke()?.let { metrics.putAll(it) }
        return linkedMapOf<String, Any?>(
            "profile" to gw.settings.profile,
            "systemName" to gw.platformSettings.effectiveSystemName(),
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            "java" to System.getProperty("java.version"),
            "modules" to gw.host.installed().map { linkedMapOf("id" to it.id, "version" to it.version.toString()) },
            "projects" to gw.projects.listProjects(),
            "tagCount" to gw.tags.copyTagConfigs().size,
            "redundancy" to gw.settings.redundancy.mode.name.lowercase(),
            "uptimeMillis" to (gw.startedAt?.let { clock.millis() - it.toEpochMilli() } ?: (System.currentTimeMillis() - startedMillis)),
            "activeAlarms" to runCatching { gw.alarms.query().size }.getOrDefault(0),
            "upgrade" to stagedUpgrade(),
            "upgradeResult" to upgrade.result(),
            "installedVersion" to (upgrade.currentVersion() ?: Gateway.VERSION),
        ) + metrics
    }

    override fun exportProject(name: String): ByteArray = gw.projects.export(name)

    override fun exportTags(provider: String, folder: String?): String = TagExchange.export(gw.store, provider, folder)

    override fun createBackup(passphrase: CharArray, bundleModules: Boolean): ByteArray = gw.backups.createEncrypted(passphrase, bundleModules)

    /**
     * Restore, then announce every config change to the running subsystems (tags, scripts, alarms, ...), so the agent
     * runs the restored configuration without a restart.
     */
    override fun restoreBackup(archive: ByteArray, passphrase: CharArray) {
        val before = gw.store.list().associate { it.id to it.resource }
        gw.backups.restoreEncrypted(archive, passphrase)
        gw.store.announceReplaced(before)
        audit(AuditActions.BACKUP_RESTORED, "eam", "bytes=${archive.size}")
    }

    override fun installModule(bytes: ByteArray): Map<String, Any?> = synchronized(gw.host) {
        val id = ModulePackage.read(bytes).manifest.id
        if (gw.host.state(id) != null) {
            val from = gw.host.manifest(id)?.version?.toString()
            val upgraded = gw.host.upgradePackage(bytes)
            audit(AuditActions.MODULE_UPGRADED, upgraded.id, "from=$from;version=${upgraded.version};origin=eam")
            linkedMapOf("module" to upgraded.id, "version" to upgraded.version.toString(), "action" to "upgraded")
        } else {
            val installed = gw.host.installPackage(bytes)
            audit(AuditActions.MODULE_INSTALLED, installed.id, "version=${installed.version};origin=eam")
            gw.host.start(installed.id)
            linkedMapOf("module" to installed.id, "version" to installed.version.toString(), "action" to "installed")
        }
    }

    override fun importProject(name: String, archive: ByteArray, policy: String): Map<String, Any?> {
        val result = gw.projects.import(name, archive, ConflictPolicy.parse(policy))
        return linkedMapOf("project" to result.project, "created" to result.created, "overwritten" to result.overwritten, "skipped" to result.skipped)
    }

    override fun importTags(json: String, policy: String): Map<String, Any?> {
        val mode = when (policy.lowercase()) {
            "merge" -> TagExchange.CollisionPolicy.MERGE
            "ignore" -> TagExchange.CollisionPolicy.IGNORE
            else -> TagExchange.CollisionPolicy.OVERWRITE
        }
        val report = TagExchange.import(gw.store, json, mode, actor = "eam")
        if (report.failed.isNotEmpty()) throw IllegalStateException("${report.failed.size} tags failed to import")
        return linkedMapOf("created" to report.created.size, "updated" to report.updated.size, "skipped" to report.skipped.size)
    }

    /**
     * EAM-011, as far as this process can go: the package is checked against the controller's checksum and kept under
     * `<dataDir>/upgrade/` with a manifest naming its version. Swapping the installed gateway happens outside the
     * running process (the service manager or image), on the next start (DIFFERENCES 503).
     */
    override fun stageUpgrade(bytes: ByteArray, sha256: String, params: Map<String, Any?>): Map<String, Any?> {
        val staged = stageOnly(bytes, sha256, params)
        if (params["apply"] != true) return staged
        val marker = upgrade.apply((params["deadlineMillis"] as? Number)?.toLong() ?: GatewayUpgrade.DEFAULT_DEADLINE_MILLIS)
        // Leave time for the task result to reach the controller before the process goes down.
        Thread({ Thread.sleep(restartDelayMillis); restartHook(GatewayUpgrade.EXIT_RESTART_FOR_UPGRADE) }, "eam-upgrade-restart").apply { isDaemon = true; start() }
        return staged + linkedMapOf("applied" to true, "restarting" to true, "to" to marker["to"], "deadlineMillis" to marker["deadlineMillis"])
    }

    private fun stageOnly(bytes: ByteArray, sha256: String, params: Map<String, Any?>): Map<String, Any?> {
        if (EamController.sha256(bytes) != sha256) throw IllegalStateException("upgrade package checksum does not match")
        val version = params["version"] as? String ?: throw IllegalArgumentException("upgrade needs params.version")
        require(GatewayUpgrade.validVersion(version)) { "version is invalid" }
        val folder = Files.createDirectories(gw.dataDir.resolve("upgrade"))
        Files.write(folder.resolve("staged.bin"), bytes)
        val manifest = linkedMapOf("version" to version, "sha256" to sha256, "bytes" to bytes.size, "stagedMillis" to clock.millis(), "from" to Gateway.VERSION)
        Files.writeString(folder.resolve("staged.json"), Json.write(manifest))
        return linkedMapOf("staged" to true) + manifest
    }

    private fun stagedUpgrade(): Map<String, Any?>? {
        val file = gw.dataDir.resolve("upgrade").resolve("staged.json")
        if (!file.exists()) return null
        return runCatching { Json.parse(file.readText()) }.getOrNull()
    }

    private val published = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Publish only a changed value, so the engine lock and the alarm reference tags see a write only on change. */
    override fun publish(path: String, value: Any?, type: DataType) {
        val key = value ?: NULL_VALUE
        if (published.put(path, key) == key) return
        gw.tags.publishSystem(path, value, type)
    }

    override fun configureAlarmTag(config: TagConfig) = gw.tags.configureSystem(config, origin = "eam", originKind = "EamThreshold")

    override fun log(agent: String, kind: String, detail: String) {
        gw.logs.log(LogLevel.INFO, "org.oip.eam", "enterprise administration event", mapOf("agent" to agent, "kind" to kind, "detail" to detail))
    }

    private fun audit(action: String, target: String, detail: String) {
        runCatching {
            if (!gw.security.auditProfile.get().enabled) return
            gw.security.audit.append(AuditDraft(
                timestampMillis = clock.millis(),
                actor = "eam:${agent.controllerName.ifEmpty { "controller" }}",
                action = action,
                status = AuditStatus.SUCCESS,
                actorHost = agent.activeConnection,
                target = target,
                detail = detail.take(500),
                origin = "gateway-network",
                context = "eam",
            ))
        }
    }

    // ----- system.eam (SCR-012) -----

    /**
     * `system.eam.*` on the controller: `getGroups`, `queryAgentStatus`, `queryAgentHistory`, `runTask`.
     * Arguments and results are JSON. Doc source: FEATURES SCR-012.
     */
    fun script(function: String, argsJson: String): String {
        val args = Json.parse(argsJson.ifBlank { "{}" })
        fun names(key: String): Set<String>? = (args[key] as? List<*>)?.map { it.toString() }?.toSet()?.takeIf { it.isNotEmpty() }
        if (settings().role != EamRole.CONTROLLER) throw IllegalStateException("system.eam needs this gateway to be an enterprise administration controller")
        val result: Any? = when (function) {
            "getGroups" -> controller.groups()
            "queryAgentStatus" -> {
                val groups = names("groupIds")
                val agents = names("agentIds")
                val connected = args["isConnected"] as? Boolean
                val rows = controller.agentViews().filter { v ->
                    (groups == null || v["group"] in groups) && (agents == null || v["name"] in agents) &&
                        (connected == null || v["online"] == connected)
                }.map { v ->
                    listOf(v["name"], v["group"], v["status"], v["online"], v["lastCheckInMillis"], v["version"], v["queued"], v["failed"])
                }
                linkedMapOf("columns" to listOf("AgentName", "AgentGroup", "Status", "IsConnected", "LastCommunication", "Version", "QueuedTasks", "FailedTasks"), "rows" to rows)
            }
            "queryAgentHistory" -> {
                val groups = names("groupIds")
                val agents = names("agentIds") ?: groups?.let { g -> controller.agents().filter { it.group in g }.map { it.name }.toSet() }
                val rows = controller.eventsBetween(agents, (args["startDate"] as? Number)?.toLong(), (args["endDate"] as? Number)?.toLong(),
                    (args["limit"] as? Number)?.toInt() ?: 100).map { e -> listOf(e["timeMillis"], e["agent"], e["kind"], e["detail"]) }
                linkedMapOf("columns" to listOf("EventTime", "AgentName", "EventType", "Detail"), "rows" to rows)
            }
            "runTask" -> {
                val id = args["taskName"] as? String ?: throw IllegalArgumentException("runTask needs a task name")
                val def = controller.task(id) ?: throw NoSuchElementException("task '$id' not found")
                controller.fire(def).map { it.id }
            }
            else -> throw IllegalArgumentException("system.eam.$function does not exist")
        }
        return Json.write(mapOf("value" to result))
    }

    companion object {
        const val SEED_FILE = "eam-agent.json"
        const val PASSPHRASE_SECRET = "eam-backup-passphrase"
        private val NULL_VALUE = Any()
    }
}
