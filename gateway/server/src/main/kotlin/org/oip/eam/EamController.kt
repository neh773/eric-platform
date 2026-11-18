package org.oip.eam

import io.grpc.BindableService
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import org.oip.config.GATEWAY_MODULE
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.gateway.Json
import org.oip.network.v1.EamCheckIn
import org.oip.network.v1.EamCheckInReply
import org.oip.network.v1.EamChunk
import org.oip.network.v1.EamDownloadRequest
import org.oip.network.v1.EamReportAck
import org.oip.network.v1.EamTaskOrder
import org.oip.network.v1.EamTaskReport
import org.oip.network.v1.EamUploadAck
import org.oip.network.v1.EnterpriseAdminGrpc
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The controller half of enterprise administration. It serves [EnterpriseAdminGrpc] on the gateway-network
 * listener, so every call is already mutual TLS with an approved certificate. On top of that each agent is
 * approved here (EAM-005), and a check-in under a known name from a new certificate waits for recovery (EAM-007).
 *
 * Task runs (EAM-003) are queued per agent and handed out on check-in. A run that fails, or is not reported
 * inside its timeout, goes back to the queue after the retry delay until its attempts are spent. An agent in
 * maintenance (EAM-006) gets no runs and raises no threshold events. Scheduled tasks (EAM-009) fire from
 * [tick] once per matching minute.
 * Doc source: FEATURES EAM rows (EAM-001 to EAM-017).
 */
class EamController(private val host: EamHost, private val dir: Path) {
    private val lock = Any()
    private val runs = LinkedHashMap<String, EamRun>()
    private val live = HashMap<String, MutableMap<String, Any?>>()
    private val events = ArrayDeque<Map<String, Any?>>()
    private val fired = HashMap<String, Long>()
    private val breaches = HashMap<String, MutableSet<String>>()
    private val alarmTags = HashSet<String>()
    private val stateFile = dir.resolve("state.json")
    private val payloadDir = dir.resolve("payloads")
    private val backupDir = dir.resolve("backups")
    private val uploadDir = dir.resolve("uploads")
    private val random = SecureRandom()

    val service: BindableService = Service()

    init {
        dir.createDirectories()
        load()
    }

    // ----- settings and records -----

    fun settings(): EamSettings = host.settings()

    fun agents(): List<EamAgentRecord> = host.store.list(GATEWAY_MODULE, Scope.GATEWAY, EamAgentRecord.KIND)
        .map { EamAgentRecord.fromSpec(it.resource.metadata.name, it.resource.spec) }
        .sortedBy { it.name }

    fun agent(name: String): EamAgentRecord? = host.store.get(agentId(name))?.let { EamAgentRecord.fromSpec(name, it.spec) }

    fun tasks(): List<EamTaskDef> = host.store.list(GATEWAY_MODULE, Scope.GATEWAY, EamTaskDef.KIND)
        .mapNotNull { runCatching { EamTaskDef.fromSpec(it.resource.metadata.name, it.resource.spec) }.getOrNull() }
        .sortedBy { it.id }

    fun task(id: String): EamTaskDef? = host.store.get(taskId(id))?.let { EamTaskDef.fromSpec(id, it.spec) }

    fun putAgent(record: EamAgentRecord, actor: String): EamAgentRecord {
        require(EAM_NAME.matches(record.name)) { "agent name must be a lowercase token" }
        require(EAM_GROUP.matches(record.group)) { "group must be a lowercase token" }
        val version = host.store.get(agentId(record.name))?.metadata?.version ?: 0L
        host.store.put(
            Scope.GATEWAY,
            Resource(EamAgentRecord.KIND, Metadata(record.name, GATEWAY_MODULE, version = version), record.toSpec()),
            actor,
        )
        return record
    }

    fun approve(name: String, actor: String): EamAgentRecord {
        val record = agent(name) ?: throw NoSuchElementException("agent '$name' not found")
        event(name, "approved", "by $actor")
        return putAgent(record.copy(approved = true), actor)
    }

    fun removeAgent(name: String, actor: String) {
        agent(name) ?: throw NoSuchElementException("agent '$name' not found")
        host.store.delete(agentId(name))
        synchronized(lock) {
            live.remove(name)
            runs.values.filter { it.agent == name && it.state == RunState.QUEUED }.forEach {
                it.state = RunState.CANCELLED
                it.message = "agent removed by $actor"
                it.finishedMillis = now()
            }
            save()
        }
        event(name, "removed", "by $actor")
    }

    /**
     * Bind the replacement certificate that checked in under [name] and queue a restore of the agent's latest stored
     * backup to it (EAM-007). Without a stored backup the agent is still bound, and no run is queued.
     */
    fun recover(name: String, actor: String, backup: String? = null): Map<String, Any?> {
        val record = agent(name) ?: throw NoSuchElementException("agent '$name' not found")
        if (record.pendingFingerprint.isEmpty()) throw IllegalStateException("agent '$name' has no replacement waiting for recovery")
        putAgent(record.copy(fingerprint = record.pendingFingerprint, pendingFingerprint = "", approved = true), actor)
        val file = backup ?: backups(name).lastOrNull()?.get("name") as? String
        event(name, "recovered", "by $actor; backup=${file ?: "none"}")
        if (file == null) return linkedMapOf("agent" to name, "run" to null)
        val run = synchronized(lock) {
            val created = newRun("recover", name, EamTaskKind.RESTORE.label, maxAttempts = 3, retry = EamTaskDef.DEFAULT_RETRY_DELAY,
                timeout = EamTaskDef.DEFAULT_TIMEOUT, params = mapOf("backup" to file))
            created.payload = backupPayloadId(name, file)
            save()
            created
        }
        return linkedMapOf("agent" to name, "run" to run.view())
    }

    /** Store a task definition. Without a schedule it runs at once when [runNow] is true. */
    fun putTask(def: EamTaskDef, actor: String, runNow: Boolean = def.schedule.isBlank()): List<EamRun> {
        require(EAM_NAME.matches(def.id)) { "task id must be a lowercase token" }
        if (def.payload.isNotEmpty()) require(payloadFile(def.payload).exists()) { "payload '${def.payload}' was not uploaded" }
        val version = host.store.get(taskId(def.id))?.metadata?.version ?: 0L
        host.store.put(Scope.GATEWAY, Resource(EamTaskDef.KIND, Metadata(def.id, GATEWAY_MODULE, version = version), def.toSpec()), actor)
        return if (runNow) fire(def) else emptyList()
    }

    fun removeTask(id: String) {
        task(id) ?: throw NoSuchElementException("task '$id' not found")
        host.store.delete(taskId(id))
    }

    /** Queue one run of [def] for every approved target agent. */
    fun fire(def: EamTaskDef): List<EamRun> {
        val targets = targets(def)
        val created = ArrayList<EamRun>()
        for (agentName in targets) {
            val prepared = runCatching { preparePayload(def, agentName) }
            synchronized(lock) {
                val run = newRun(def.id, agentName, def.kind.label, def.maxAttempts, def.retryDelayMillis, def.timeoutMillis, def.params)
                prepared.onSuccess { run.payload = it }.onFailure {
                    run.state = RunState.FAILED
                    run.message = it.message ?: it.javaClass.simpleName
                    run.finishedMillis = now()
                }
                created += run
            }
        }
        synchronized(lock) { save() }
        if (targets.isEmpty()) event("", "task-no-targets", def.id)
        return created
    }

    fun targets(def: EamTaskDef): List<String> {
        val all = agents().filter { it.approved }
        val named = def.agents.toSet()
        val groups = def.groups.toSet()
        return all.filter { it.name in named || (it.group.isNotEmpty() && it.group in groups) }.map { it.name }
    }

    fun runs(agent: String? = null, task: String? = null, limit: Int = 200): List<Map<String, Any?>> = synchronized(lock) {
        runs.values.reversed().filter { (agent == null || it.agent == agent) && (task == null || it.task == task) }
            .take(limit.coerceIn(1, 2000)).map { it.view() }
    }

    fun run(id: String): EamRun? = synchronized(lock) { runs[id]?.copy() }

    fun retry(id: String): Map<String, Any?> = synchronized(lock) {
        val run = runs[id] ?: throw NoSuchElementException("run '$id' not found")
        check(run.state == RunState.FAILED || run.state == RunState.CANCELLED) { "only a failed or cancelled run can be retried" }
        if (run.payload.isNotEmpty() && resolvePayload(run.payload) == null) {
            val def = task(run.task) ?: throw IllegalStateException("task '${run.task}' no longer exists, so its payload cannot be made again")
            run.payload = preparePayload(def, run.agent)
        }
        run.state = RunState.QUEUED
        run.attempts = 0
        run.dueMillis = now()
        run.finishedMillis = 0
        run.message = "retried"
        save()
        run.view()
    }

    fun cancel(id: String): Map<String, Any?> = synchronized(lock) {
        val run = runs[id] ?: throw NoSuchElementException("run '$id' not found")
        check(run.state == RunState.QUEUED || run.state == RunState.DISPATCHED) { "run '$id' is already finished" }
        run.state = RunState.CANCELLED
        run.finishedMillis = now()
        run.message = "cancelled"
        release(run)
        save()
        run.view()
    }

    fun events(agent: String? = null, limit: Int = 200): List<Map<String, Any?>> = synchronized(lock) {
        events.reversed().filter { agent == null || it["agent"] == agent }.take(limit.coerceIn(1, 1000))
    }

    fun eventsBetween(agents: Set<String>?, start: Long?, end: Long?, limit: Int): List<Map<String, Any?>> = synchronized(lock) {
        events.reversed().filter { e ->
            val t = e["timeMillis"] as Long
            (agents == null || e["agent"] in agents) && (start == null || t >= start) && (end == null || t <= end)
        }.take(limit.coerceIn(1, 1000))
    }

    // ----- inventory and health -----

    /** Agent records with their last check-in, health, and inventory (EAM-001, EAM-010). */
    fun agentViews(): List<Map<String, Any?>> {
        val records = agents()
        val settings = settings()
        val at = now()
        return synchronized(lock) {
            records.map { record ->
                val seen = live[record.name]
                val last = (seen?.get("lastCheckInMillis") as? Long) ?: 0L
                val online = last > 0 && at - last <= settings.thresholds.offlineMillis
                linkedMapOf(
                    "name" to record.name,
                    "approved" to record.approved,
                    "group" to record.group,
                    "maintenance" to record.maintenance,
                    "description" to record.description,
                    "fingerprint" to record.fingerprint,
                    "pendingFingerprint" to record.pendingFingerprint.ifEmpty { null },
                    "status" to status(record, online),
                    "online" to online,
                    "lastCheckInMillis" to last.takeIf { it > 0 },
                    "version" to seen?.get("version"),
                    "inventory" to (seen?.get("inventory") ?: emptyMap<String, Any?>()),
                    "queued" to runs.values.count { it.agent == record.name && it.state == RunState.QUEUED },
                    "running" to runs.values.count { it.agent == record.name && it.state == RunState.DISPATCHED },
                    "failed" to runs.values.count { it.agent == record.name && it.state == RunState.FAILED },
                    "thresholds" to (breaches[record.name]?.sorted() ?: emptyList()),
                    "backups" to backups(record.name).size,
                )
            }
        }
    }

    fun groups(): List<String> = agents().map { it.group }.filter { it.isNotEmpty() }.distinct().sorted()

    /** One row per agent for inventory reporting (EAM-010). No license data: this platform has none (DIFFERENCES 5). */
    fun inventory(): List<Map<String, Any?>> = agentViews().map { view ->
        @Suppress("UNCHECKED_CAST")
        val inv = view["inventory"] as Map<String, Any?>
        linkedMapOf(
            "agent" to view["name"],
            "group" to view["group"],
            "status" to view["status"],
            "version" to view["version"],
            "profile" to inv["profile"],
            "os" to inv["os"],
            "java" to inv["java"],
            "modules" to inv["modules"],
            "projects" to inv["projects"],
            "tagCount" to inv["tagCount"],
            "redundancy" to inv["redundancy"],
            "uptimeMillis" to inv["uptimeMillis"],
            "cpuPercent" to inv["cpuPercent"],
            "memoryPercent" to inv["memoryPercent"],
            "diskPercent" to inv["diskPercent"],
            "activeAlarms" to inv["activeAlarms"],
            "lastCheckInMillis" to view["lastCheckInMillis"],
        )
    }

    fun inventoryCsv(): String {
        val rows = inventory()
        val columns = listOf("agent", "group", "status", "version", "profile", "os", "java", "modules", "projects", "tagCount",
            "redundancy", "uptimeMillis", "cpuPercent", "memoryPercent", "diskPercent", "activeAlarms", "lastCheckInMillis")
        val out = StringBuilder(columns.joinToString(",")).append('\n')
        for (row in rows) {
            out.append(columns.joinToString(",") { key -> csv(cell(row[key])) }).append('\n')
        }
        return out.toString()
    }

    private fun cell(value: Any?): String = when (value) {
        null -> ""
        is List<*> -> value.joinToString(";") { item ->
            if (item is Map<*, *>) "${item["id"]}@${item["version"]}" else item.toString()
        }
        else -> value.toString()
    }

    private fun csv(text: String): String {
        val safe = if (text.isNotEmpty() && text[0] in "=+-@") "'$text" else text
        return if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun status(record: EamAgentRecord, online: Boolean): String = when {
        record.pendingFingerprint.isNotEmpty() -> "replacement-pending"
        !record.approved -> "pending"
        record.maintenance -> "maintenance"
        online -> "online"
        else -> "offline"
    }

    // ----- stored agent backups (EAM-004, EAM-007) -----

    fun backups(agent: String): List<Map<String, Any?>> {
        val folder = backupDir.resolve(agent)
        if (!EAM_NAME.matches(agent) || !folder.exists()) return emptyList()
        return folder.listDirectoryEntries("*.oipbk").sortedBy { it.name }.map {
            linkedMapOf("name" to it.name, "bytes" to Files.size(it))
        }
    }

    // ----- payloads -----

    /** Store an uploaded package (module or upgrade). Returns its id, size, and SHA-256. */
    fun storePayload(bytes: ByteArray, label: String): Map<String, Any?> {
        require(bytes.isNotEmpty()) { "payload is empty" }
        require(bytes.size <= MAX_PAYLOAD) { "payload is larger than ${MAX_PAYLOAD / (1024 * 1024)} MB" }
        val id = newId()
        payloadDir.createDirectories()
        Files.write(payloadFile(id), bytes)
        Files.writeString(payloadDir.resolve("$id.json"), Json.write(mapOf("label" to label.take(200), "sha256" to sha256(bytes), "bytes" to bytes.size)))
        return linkedMapOf("id" to id, "label" to label.take(200), "bytes" to bytes.size, "sha256" to sha256(bytes))
    }

    fun payloads(): List<Map<String, Any?>> {
        if (!payloadDir.exists()) return emptyList()
        return payloadDir.listDirectoryEntries("*.json").sortedBy { it.name }.mapNotNull { meta ->
            runCatching { mapOf("id" to meta.name.removeSuffix(".json")) + Json.parse(meta.readText()) }.getOrNull()
        }
    }

    private fun preparePayload(def: EamTaskDef, agentName: String): String = when (def.kind) {
        EamTaskKind.BACKUP -> ""
        EamTaskKind.RESTORE -> {
            val file = def.params["backup"] as? String ?: backups(agentName).lastOrNull()?.get("name") as? String
                ?: throw IllegalStateException("no stored backup for agent '$agentName'")
            backupPayloadId(agentName, file)
        }
        EamTaskKind.MODULE_INSTALL, EamTaskKind.UPGRADE -> {
            require(def.payload.isNotEmpty() && payloadFile(def.payload).exists()) { "payload '${def.payload}' was not uploaded" }
            def.payload
        }
        EamTaskKind.SEND_PROJECT -> {
            val project = def.params["project"] as? String ?: throw IllegalArgumentException("send-project needs params.project")
            storePayload(host.exportProject(project), "project $project")["id"] as String
        }
        EamTaskKind.SEND_TAGS -> {
            val provider = def.params["provider"] as? String ?: "default"
            val folder = def.params["folder"] as? String
            storePayload(host.exportTags(provider, folder).toByteArray(), "tags $provider")["id"] as String
        }
    }

    private fun backupPayloadId(agent: String, file: String): String {
        require(BACKUP_FILE.matches(file)) { "backup name is invalid" }
        require(backupDir.resolve(agent).resolve(file).exists()) { "backup '$file' is not stored for agent '$agent'" }
        return "backup/$agent/$file"
    }

    private fun resolvePayload(id: String): Path? {
        if (id.startsWith("backup/")) {
            val parts = id.split('/')
            if (parts.size != 3 || !EAM_NAME.matches(parts[1]) || !BACKUP_FILE.matches(parts[2])) return null
            return backupDir.resolve(parts[1]).resolve(parts[2]).takeIf { it.exists() }
        }
        if (!PAYLOAD_ID.matches(id)) return null
        return payloadFile(id).takeIf { it.exists() }
    }

    private fun payloadFile(id: String): Path {
        require(PAYLOAD_ID.matches(id)) { "payload id is invalid" }
        return payloadDir.resolve("$id.bin")
    }

    // ----- check-in, report, transfer -----

    internal fun checkIn(fingerprint: String, peerName: String, request: EamCheckIn): EamCheckInReply {
        val name = request.gatewayName.lowercase()
        if (!EAM_NAME.matches(name)) throw Status.INVALID_ARGUMENT.withDescription("gateway name is not an agent name").asRuntimeException()
        if (!name.equals(peerName, ignoreCase = true)) {
            throw Status.PERMISSION_DENIED.withDescription("gateway name does not match the connection").asRuntimeException()
        }
        val settings = settings()
        val inventory = runCatching { Json.parse(request.inventoryJson) }.getOrDefault(emptyMap())
        var record = agent(name)
        val replyState: String
        if (record == null) {
            val group = (inventory["requestedGroup"] as? String)?.takeIf { EAM_GROUP.matches(it) } ?: ""
            record = putAgent(EamAgentRecord(name, fingerprint, approved = settings.autoApprove, group = group), "eam")
            event(name, "enrolled", if (settings.autoApprove) "approved automatically" else "waiting for approval")
        }
        if (record.fingerprint.isEmpty()) record = putAgent(record.copy(fingerprint = fingerprint), "eam")
        if (!record.fingerprint.equals(fingerprint, ignoreCase = true)) {
            if (record.pendingFingerprint != fingerprint) {
                putAgent(record.copy(pendingFingerprint = fingerprint), "eam")
                event(name, "replacement", "a different certificate checked in; recover the agent to bind it")
            }
            return EamCheckInReply.newBuilder().setState("replaced").setCheckInMillis(settings.checkInMillis)
                .setControllerName(host.gatewayName()).build()
        }
        val orders = ArrayList<EamTaskOrder>()
        synchronized(lock) {
            live[name] = linkedMapOf(
                "lastCheckInMillis" to now(),
                "version" to request.version,
                "inventory" to inventory,
            )
            replyState = when {
                !record.approved -> "pending"
                record.maintenance -> "maintenance"
                else -> "approved"
            }
            if (replyState == "approved") {
                val running = request.runningList.toSet()
                val due = runs.values.filter {
                    it.agent == name && it.state == RunState.QUEUED && it.dueMillis <= now() && it.id !in running
                }.take(MAX_ORDERS)
                for (run in due) {
                    run.state = RunState.DISPATCHED
                    run.attempts += 1
                    run.dispatchedMillis = now()
                    orders += order(run)
                }
            }
            save()
        }
        return EamCheckInReply.newBuilder()
            .setState(replyState)
            .addAllTasks(orders)
            .setCheckInMillis(settings.checkInMillis)
            .setControllerName(host.gatewayName())
            .build()
    }

    private fun order(run: EamRun): EamTaskOrder {
        val builder = EamTaskOrder.newBuilder()
            .setRunId(run.id)
            .setKind(run.kind)
            .setParamsJson(Json.write(run.params))
        if (run.payload.isNotEmpty()) {
            val file = resolvePayload(run.payload)
            if (file != null) {
                builder.setPayloadId(run.payload).setPayloadSize(Files.size(file)).setPayloadSha256(sha256(Files.readAllBytes(file)))
            }
        }
        if (run.kind == EamTaskKind.BACKUP.label || run.kind == EamTaskKind.RESTORE.label) builder.setSecret(host.backupPassphrase())
        return builder.build()
    }

    internal fun report(name: String, request: EamTaskReport): EamReportAck {
        val outcome: Pair<EamRun, Boolean>
        synchronized(lock) {
            val run = runs[request.runId]
            if (run == null || !run.agent.equals(name, ignoreCase = true)) {
                throw Status.NOT_FOUND.withDescription("run not found").asRuntimeException()
            }
            if (run.state != RunState.DISPATCHED) return EamReportAck.newBuilder().setAccepted(false).build()
            val result = runCatching { Json.parse(request.resultJson.ifBlank { "{}" }) }.getOrDefault(emptyMap()).toMutableMap()
            var success = request.success
            var message = request.message.take(1000)
            if (success && run.kind == EamTaskKind.BACKUP.label) {
                val stored = runCatching { keepBackup(run.agent, request.uploadId) }
                stored.onSuccess { result["backup"] = it }.onFailure {
                    success = false
                    message = "backup upload was not stored: ${it.message}"
                }
            }
            run.result = result
            run.message = message
            if (success) {
                run.state = RunState.SUCCEEDED
                run.finishedMillis = now()
                release(run)
            } else {
                failAttempt(run, message)
            }
            save()
            outcome = run to success
        }
        if (!outcome.second && outcome.first.state == RunState.FAILED) event(name, "task-failed", "${outcome.first.task}: ${outcome.first.message}")
        return EamReportAck.newBuilder().setAccepted(true).build()
    }

    private fun failAttempt(run: EamRun, message: String) {
        run.message = message
        if (run.attempts < run.maxAttempts) {
            run.state = RunState.QUEUED
            run.dueMillis = now() + run.retryDelayMillis
        } else {
            run.state = RunState.FAILED
            run.finishedMillis = now()
            release(run)
        }
    }

    /** A project or tag export made for one run is deleted when that run is finished. Uploaded packages stay. */
    private fun release(run: EamRun) {
        if (run.kind != EamTaskKind.SEND_PROJECT.label && run.kind != EamTaskKind.SEND_TAGS.label) return
        if (!PAYLOAD_ID.matches(run.payload)) return
        runCatching { Files.deleteIfExists(payloadFile(run.payload)) }
        runCatching { Files.deleteIfExists(payloadDir.resolve("${run.payload}.json")) }
    }

    private fun keepBackup(agent: String, uploadId: String): String {
        require(PAYLOAD_ID.matches(uploadId)) { "upload id is invalid" }
        val upload = uploadDir.resolve("$uploadId.part")
        require(upload.exists()) { "upload '$uploadId' not found" }
        val folder = backupDir.resolve(agent).createDirectories()
        val name = BACKUP_STAMP.format(Instant.ofEpochMilli(now())) + ".oipbk"
        Files.move(upload, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        val keep = settings().keepBackups.coerceAtLeast(1)
        val all = folder.listDirectoryEntries("*.oipbk").sortedBy { it.name }
        all.dropLast(keep).forEach { runCatching { Files.delete(it) } }
        return name
    }

    // ----- periodic work -----

    /** Fire due schedules, time out runs, evaluate thresholds, and publish status tags. Called once a second. */
    fun tick() {
        val at = now()
        val minute = at / 60_000
        for (def in tasks()) {
            if (!def.enabled) continue
            val cron = runCatching { def.cron() }.getOrNull() ?: continue
            val due = cron.matches(Instant.ofEpochMilli(at), ZoneOffset.UTC)
            val key = if (cron.fields == 6) at / 1_000 else minute
            val fire = synchronized(lock) { due && fired[def.id] != key && (fired.put(def.id, key) ?: Long.MIN_VALUE) != key }
            if (fire) {
                event("", "task-scheduled", def.id)
                fire(def)
            }
        }
        val failed = ArrayList<EamRun>()
        synchronized(lock) {
            var changed = false
            for (run in runs.values) {
                if (run.state == RunState.DISPATCHED && at - run.dispatchedMillis > run.timeoutMillis) {
                    failAttempt(run, "timed out after ${run.timeoutMillis} ms")
                    if (run.state == RunState.FAILED) failed += run
                    changed = true
                }
            }
            if (changed) save()
        }
        failed.forEach { event(it.agent, "task-failed", "${it.task}: ${it.message}") }
        thresholds()
        publishTags()
    }

    /** EAM-017: compare each approved agent's last check-in against the thresholds and raise or clear events. */
    private fun thresholds() {
        val limits = settings().thresholds
        for (view in agentViews()) {
            val name = view["name"] as String
            if (view["approved"] != true || view["maintenance"] == true || view["pendingFingerprint"] != null) {
                synchronized(lock) { breaches.remove(name) }
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val inv = view["inventory"] as Map<String, Any?>
            val now = HashSet<String>()
            val last = view["lastCheckInMillis"] as Long?
            if (last == null || now() - last > limits.offlineMillis) now += METRIC_OFFLINE
            if (view["online"] == true) {
                fun over(metric: String, value: Any?, limit: Double?) {
                    val v = (value as? Number)?.toDouble() ?: return
                    if (limit != null && v > limit) now += metric
                }
                over(METRIC_CPU, inv["cpuPercent"], limits.cpuPercent)
                over(METRIC_MEMORY, inv["memoryPercent"], limits.memoryPercent)
                over(METRIC_DISK, inv["diskPercent"], limits.diskPercent)
            }
            val failedCount = (view["failed"] as Int)
            if (limits.failedTasks != null && failedCount >= limits.failedTasks) now += METRIC_FAILED
            val (raised, cleared) = synchronized(lock) {
                val before = breaches.getOrPut(name) { HashSet() }
                val r = now - before
                val c = before - now
                before.clear()
                before.addAll(now)
                r to c
            }
            raised.forEach { event(name, "threshold-raised", it) }
            cleared.forEach { event(name, "threshold-cleared", it) }
        }
    }

    /**
     * EAM-016: agent status in the `[system]` provider under `Gateway/EAM/Agents/<agent>/`, and one alarm per
     * threshold under `Gateway/EAM/Alarms/<agent>/<metric>` (a reference tag with an equality alarm on the
     * published threshold flag), so the alarm table and notification pipelines see agent events (EAM-017).
     */
    private fun publishTags() {
        val views = agentViews()
        host.publish("Gateway/EAM/AgentCount", views.size, DataType.INT32)
        host.publish("Gateway/EAM/OnlineCount", views.count { it["online"] == true }, DataType.INT32)
        for (view in views) {
            val name = view["name"] as String
            val base = "Gateway/EAM/Agents/$name"
            @Suppress("UNCHECKED_CAST")
            val inv = view["inventory"] as Map<String, Any?>
            host.publish("$base/Status", view["status"], DataType.STRING)
            host.publish("$base/Online", view["online"], DataType.BOOLEAN)
            host.publish("$base/Group", view["group"], DataType.STRING)
            host.publish("$base/Version", view["version"] ?: "", DataType.STRING)
            host.publish("$base/LastCheckIn", (view["lastCheckInMillis"] as Long?)?.let { Instant.ofEpochMilli(it) }, DataType.DATETIME)
            host.publish("$base/CpuPercent", (inv["cpuPercent"] as? Number)?.toDouble(), DataType.DOUBLE)
            host.publish("$base/MemoryPercent", (inv["memoryPercent"] as? Number)?.toDouble(), DataType.DOUBLE)
            host.publish("$base/DiskPercent", (inv["diskPercent"] as? Number)?.toDouble(), DataType.DOUBLE)
            host.publish("$base/QueuedTasks", view["queued"], DataType.INT32)
            host.publish("$base/FailedTasks", view["failed"], DataType.INT32)
            @Suppress("UNCHECKED_CAST")
            val active = (view["thresholds"] as List<String>).toSet()
            for (metric in METRICS) {
                host.publish("$base/Thresholds/$metric", metric in active, DataType.BOOLEAN)
                val alarmPath = "Gateway/EAM/Alarms/$name/$metric"
                if (view["approved"] == true && alarmTags.add(alarmPath)) {
                    runCatching {
                        host.configureAlarmTag(TagConfig.fromSpec(linkedMapOf(
                            "provider" to "system",
                            "path" to alarmPath,
                            "type" to "reference",
                            "dataType" to "Boolean",
                            "sourcePath" to "[system]$base/Thresholds/$metric",
                            "alarms" to listOf(linkedMapOf(
                                "name" to "Agent $metric",
                                "mode" to "Equal",
                                "setpointA" to 1,
                                "priority" to if (metric == METRIC_OFFLINE) "High" else "Medium",
                            )),
                        )))
                    }.onFailure { alarmTags.remove(alarmPath) }
                }
            }
        }
    }

    // ----- internals -----

    private fun newRun(task: String, agent: String, kind: String, maxAttempts: Int, retry: Long, timeout: Long, params: Map<String, Any?>): EamRun {
        val run = EamRun(
            id = newId(), task = task, agent = agent, kind = kind, maxAttempts = maxAttempts,
            retryDelayMillis = retry, timeoutMillis = timeout, params = params, createdMillis = now(), dueMillis = now(),
        )
        runs[run.id] = run
        while (runs.size > MAX_RUNS) {
            val oldest = runs.values.firstOrNull { it.state != RunState.QUEUED && it.state != RunState.DISPATCHED } ?: break
            runs.remove(oldest.id)
        }
        return run
    }

    fun event(agent: String, kind: String, detail: String) {
        synchronized(lock) {
            events.addLast(linkedMapOf("timeMillis" to now(), "agent" to agent, "kind" to kind, "detail" to detail.take(500)))
            while (events.size > MAX_EVENTS) events.removeFirst()
            save()
        }
        host.log(agent, kind, detail)
    }

    private fun now(): Long = host.clock.millis()

    private fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private fun save() {
        val body = linkedMapOf(
            "runs" to runs.values.map { it.store() },
            "live" to live,
            "events" to events.toList(),
        )
        val temp = dir.resolve("state.json.tmp")
        Files.writeString(temp, Json.write(body))
        Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Suppress("UNCHECKED_CAST")
    private fun load() {
        if (!stateFile.exists()) return
        val body = runCatching { Json.parse(stateFile.readText()) }.getOrNull() ?: return
        (body["runs"] as? List<*>)?.forEach { item -> runCatching { EamRun.load(item as Map<String, Any?>) }.onSuccess { runs[it.id] = it } }
        (body["live"] as? Map<String, Any?>)?.forEach { (k, v) ->
            val m = (v as? Map<String, Any?>)?.toMutableMap() ?: return@forEach
            m["lastCheckInMillis"] = (m["lastCheckInMillis"] as? Number)?.toLong()
            live[k] = m
        }
        (body["events"] as? List<*>)?.forEach { item ->
            val m = (item as? Map<String, Any?>)?.toMutableMap() ?: return@forEach
            m["timeMillis"] = (m["timeMillis"] as? Number)?.toLong() ?: 0L
            events.addLast(m)
        }
    }

    private inner class Service : EnterpriseAdminGrpc.EnterpriseAdminImplBase() {
        override fun checkIn(request: EamCheckIn, responseObserver: StreamObserver<EamCheckInReply>) = unary(responseObserver) {
            val (fingerprint, name) = admit()
            checkIn(fingerprint, name, request)
        }

        override fun report(request: EamTaskReport, responseObserver: StreamObserver<EamReportAck>) = unary(responseObserver) {
            val (_, name) = admit()
            report(name.lowercase(), request)
        }

        override fun upload(responseObserver: StreamObserver<EamUploadAck>): StreamObserver<EamChunk> {
            val name = try {
                admit().second.lowercase()
            } catch (e: StatusRuntimeException) {
                responseObserver.onError(e)
                return object : StreamObserver<EamChunk> {
                    override fun onNext(value: EamChunk) {}
                    override fun onError(t: Throwable) {}
                    override fun onCompleted() {}
                }
            }
            return Upload(name, responseObserver)
        }

        override fun download(request: EamDownloadRequest, responseObserver: StreamObserver<EamChunk>) {
            try {
                val (_, name) = admit()
                val run = synchronized(lock) { runs[request.runId] }
                if (run == null || !run.agent.equals(name, true) || run.state != RunState.DISPATCHED || run.payload != request.payloadId) {
                    throw Status.NOT_FOUND.withDescription("payload not found for this run").asRuntimeException()
                }
                val file = resolvePayload(run.payload) ?: throw Status.NOT_FOUND.withDescription("payload missing").asRuntimeException()
                Files.newInputStream(file).use { input ->
                    val buffer = ByteArray(CHUNK)
                    var offset = 0L
                    while (true) {
                        val read = input.readNBytes(buffer, 0, buffer.size)
                        val last = read < buffer.size
                        responseObserver.onNext(EamChunk.newBuilder().setPayloadId(run.payload).setOffset(offset)
                            .setData(com.google.protobuf.ByteString.copyFrom(buffer, 0, read)).setLast(last).build())
                        offset += read
                        if (last) break
                    }
                }
                responseObserver.onCompleted()
            } catch (e: StatusRuntimeException) {
                responseObserver.onError(e)
            } catch (e: Exception) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.javaClass.simpleName).asRuntimeException())
            }
        }

        private fun admit(): Pair<String, String> {
            if (!host.serving()) throw Status.FAILED_PRECONDITION.withDescription("not an active controller").asRuntimeException()
            return host.network.requirePeer(SERVICE_EAM)
        }

        private fun <T> unary(observer: StreamObserver<T>, body: () -> T) {
            try {
                observer.onNext(body())
                observer.onCompleted()
            } catch (e: StatusRuntimeException) {
                observer.onError(e)
            } catch (e: IllegalArgumentException) {
                observer.onError(Status.INVALID_ARGUMENT.withDescription(e.message).asRuntimeException())
            } catch (e: Exception) {
                observer.onError(Status.INTERNAL.withDescription(e.javaClass.simpleName).asRuntimeException())
            }
        }
    }

    private inner class Upload(private val agent: String, private val observer: StreamObserver<EamUploadAck>) : StreamObserver<EamChunk> {
        private val id = newId()
        private val file = uploadDir.createDirectories().resolve("$id.part")
        private val out = Files.newOutputStream(file)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var size = 0L
        private var failed = false

        override fun onNext(value: EamChunk) {
            if (failed) return
            if (size == 0L) {
                val run = synchronized(lock) { runs[value.runId] }
                if (run == null || !run.agent.equals(agent, true) || run.state != RunState.DISPATCHED) {
                    fail(Status.NOT_FOUND.withDescription("run not found"))
                    return
                }
            }
            size += value.data.size()
            if (size > MAX_PAYLOAD) {
                fail(Status.RESOURCE_EXHAUSTED.withDescription("upload is too large"))
                return
            }
            value.data.writeTo(out)
            digest.update(value.data.asReadOnlyByteBuffer())
        }

        override fun onError(t: Throwable) {
            failed = true
            runCatching { out.close() }
            runCatching { Files.deleteIfExists(file) }
        }

        override fun onCompleted() {
            if (failed) return
            out.close()
            observer.onNext(EamUploadAck.newBuilder().setUploadId(id).setSize(size).setSha256(HexFormat.of().formatHex(digest.digest())).build())
            observer.onCompleted()
        }

        private fun fail(status: Status) {
            failed = true
            runCatching { out.close() }
            runCatching { Files.deleteIfExists(file) }
            observer.onError(status.asRuntimeException())
        }
    }

    companion object {
        const val SERVICE_EAM = "eam"
        const val METRIC_OFFLINE = "Offline"
        const val METRIC_CPU = "Cpu"
        const val METRIC_MEMORY = "Memory"
        const val METRIC_DISK = "Disk"
        const val METRIC_FAILED = "FailedTasks"
        val METRICS = listOf(METRIC_OFFLINE, METRIC_CPU, METRIC_MEMORY, METRIC_DISK, METRIC_FAILED)
        const val CHUNK = 512 * 1024
        const val MAX_PAYLOAD = 512L * 1024 * 1024
        const val MAX_ORDERS = 4
        const val MAX_RUNS = 2_000
        const val MAX_EVENTS = 1_000
        val PAYLOAD_ID = Regex("[0-9a-f]{32}")
        val BACKUP_FILE = Regex("""\d{8}-\d{6}-\d{3}\.oipbk""")
        private val BACKUP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun agentId(name: String) = ResourceId(GATEWAY_MODULE, Scope.GATEWAY, EamAgentRecord.KIND, name)
        fun taskId(id: String) = ResourceId(GATEWAY_MODULE, Scope.GATEWAY, EamTaskDef.KIND, id)
    }
}
