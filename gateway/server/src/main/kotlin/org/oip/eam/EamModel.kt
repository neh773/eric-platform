package org.oip.eam

import org.oip.config.GATEWAY_MODULE
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.scripting.Cron

/**
 * Enterprise administration (EAM) configuration. Three config resources in `org.oip.gateway`, gateway scope:
 *
 * - `EamSettings/eam`: this gateway's role (`none`, `controller`, or `agent`), the outgoing gateway-network
 *   connection an agent checks in over (and a second one for a redundant controller), the check-in period,
 *   and the controller's event thresholds.
 * - `EamAgent/<name>`: one agent as the controller knows it: the approved certificate, its group, and maintenance.
 * - `EamTask/<id>`: a task definition: what to run, on which agents or groups, when, and how often to retry.
 *
 * Because these are config resources, they travel in a gateway backup and in redundancy config sync. Runtime state
 * (task runs, the last inventory, events, stored agent backups) lives in `<dataDir>/eam/`.
 * Doc source: FEATURES EAM rows (EAM-001 to EAM-017).
 */
enum class EamRole { NONE, CONTROLLER, AGENT;
    val label: String get() = name.lowercase()

    companion object {
        fun parse(raw: String?): EamRole = when (raw?.trim()?.lowercase()) {
            null, "", "none" -> NONE
            "controller" -> CONTROLLER
            "agent" -> AGENT
            else -> throw IllegalArgumentException("eam role must be none, controller, or agent")
        }
    }
}

/** Controller event thresholds (EAM-017). A null limit is not checked. Percentages are 0..100. */
data class EamThresholds(
    val offlineMillis: Long = DEFAULT_OFFLINE_MILLIS,
    val cpuPercent: Double? = null,
    val memoryPercent: Double? = null,
    val diskPercent: Double? = null,
    val failedTasks: Int? = null,
) {
    fun toSpec(): Map<String, Any?> = linkedMapOf(
        "offlineMillis" to offlineMillis,
        "cpuPercent" to cpuPercent,
        "memoryPercent" to memoryPercent,
        "diskPercent" to diskPercent,
        "failedTasks" to failedTasks,
    )

    companion object {
        const val DEFAULT_OFFLINE_MILLIS = 60_000L

        fun fromSpec(raw: Any?): EamThresholds {
            val m = raw as? Map<*, *> ?: return EamThresholds()
            return EamThresholds(
                offlineMillis = (m["offlineMillis"] as? Number)?.toLong() ?: DEFAULT_OFFLINE_MILLIS,
                cpuPercent = (m["cpuPercent"] as? Number)?.toDouble(),
                memoryPercent = (m["memoryPercent"] as? Number)?.toDouble(),
                diskPercent = (m["diskPercent"] as? Number)?.toDouble(),
                failedTasks = (m["failedTasks"] as? Number)?.toInt(),
            )
        }
    }
}

data class EamSettings(
    val role: EamRole = EamRole.NONE,
    /** Agent: outgoing connection to the controller. */
    val controller: String = "",
    /** Agent: outgoing connection to the redundant controller, tried when [controller] does not answer (EAM-012). */
    val backupController: String = "",
    /** Agent: the group the agent asks for when it enrolls. The controller can change it. */
    val group: String = "",
    val checkInMillis: Long = DEFAULT_CHECK_IN_MILLIS,
    /** Controller: approve a new agent at its first check-in instead of leaving it pending. */
    val autoApprove: Boolean = false,
    /** Controller: stored backups kept per agent. */
    val keepBackups: Int = DEFAULT_KEEP_BACKUPS,
    val thresholds: EamThresholds = EamThresholds(),
) {
    fun toSpec(): Map<String, Any?> = linkedMapOf(
        "role" to role.label,
        "controller" to controller,
        "backupController" to backupController,
        "group" to group,
        "checkInMillis" to checkInMillis,
        "autoApprove" to autoApprove,
        "keepBackups" to keepBackups,
        "thresholds" to thresholds.toSpec(),
    )

    companion object {
        const val KIND = "EamSettings"
        const val NAME = "eam"
        const val DEFAULT_CHECK_IN_MILLIS = 5_000L
        const val DEFAULT_KEEP_BACKUPS = 5
        val ID = ResourceId(GATEWAY_MODULE, Scope.GATEWAY, KIND, NAME)

        fun fromSpec(spec: Map<String, Any?>?): EamSettings {
            if (spec == null) return EamSettings()
            return EamSettings(
                role = EamRole.parse(spec["role"] as? String),
                controller = spec["controller"] as? String ?: "",
                backupController = spec["backupController"] as? String ?: "",
                group = spec["group"] as? String ?: "",
                checkInMillis = (spec["checkInMillis"] as? Number)?.toLong() ?: DEFAULT_CHECK_IN_MILLIS,
                autoApprove = spec["autoApprove"] as? Boolean ?: false,
                keepBackups = (spec["keepBackups"] as? Number)?.toInt() ?: DEFAULT_KEEP_BACKUPS,
                thresholds = EamThresholds.fromSpec(spec["thresholds"]),
            )
        }
    }
}

/** One agent on the controller (EAM-005, EAM-006, EAM-007). */
data class EamAgentRecord(
    val name: String,
    val fingerprint: String,
    val approved: Boolean = false,
    val group: String = "",
    val maintenance: Boolean = false,
    val description: String = "",
    /** A different certificate that checked in under this name: a replacement gateway waiting for recovery. */
    val pendingFingerprint: String = "",
) {
    fun toSpec(): Map<String, Any?> = linkedMapOf(
        "fingerprint" to fingerprint,
        "approved" to approved,
        "group" to group,
        "maintenance" to maintenance,
        "description" to description,
        "pendingFingerprint" to pendingFingerprint,
    )

    companion object {
        const val KIND = "EamAgent"

        fun fromSpec(name: String, spec: Map<String, Any?>): EamAgentRecord = EamAgentRecord(
            name = name,
            fingerprint = spec["fingerprint"] as? String ?: "",
            approved = spec["approved"] as? Boolean ?: false,
            group = spec["group"] as? String ?: "",
            maintenance = spec["maintenance"] as? Boolean ?: false,
            description = spec["description"] as? String ?: "",
            pendingFingerprint = spec["pendingFingerprint"] as? String ?: "",
        )
    }
}

/** What a task does on each target agent. */
enum class EamTaskKind(val label: String) {
    /** The agent makes an encrypted backup and uploads it to the controller (EAM-004). */
    BACKUP("backup"),
    /** The agent downloads one of its stored backups and restores it (EAM-004, EAM-007). */
    RESTORE("restore"),
    /** The agent installs or upgrades a module package uploaded to the controller (EAM-004). */
    MODULE_INSTALL("module-install"),
    /** The agent imports a project exported from the controller (EAM-013). */
    SEND_PROJECT("send-project"),
    /** The agent imports tag configuration exported from the controller (EAM-015). */
    SEND_TAGS("send-tags"),
    /** The agent verifies and stages a gateway upgrade package for its next start (EAM-011). */
    UPGRADE("upgrade");

    companion object {
        fun parse(raw: String?): EamTaskKind = entries.firstOrNull { it.label == raw?.trim()?.lowercase() }
            ?: throw IllegalArgumentException("unknown task kind '$raw' (expected one of ${entries.joinToString { it.label }})")
    }
}

/** A task definition (EAM-003, EAM-009). A task without a schedule runs once, when it is created or run by hand. */
data class EamTaskDef(
    val id: String,
    val kind: EamTaskKind,
    val agents: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val params: Map<String, Any?> = emptyMap(),
    /** Five-field cron, evaluated in UTC on the controller. Blank: not scheduled. */
    val schedule: String = "",
    val maxAttempts: Int = DEFAULT_ATTEMPTS,
    val retryDelayMillis: Long = DEFAULT_RETRY_DELAY,
    val timeoutMillis: Long = DEFAULT_TIMEOUT,
    val enabled: Boolean = true,
    /** Uploaded payload id for module-install and upgrade. */
    val payload: String = "",
    val description: String = "",
) {
    fun toSpec(): Map<String, Any?> = linkedMapOf(
        "kind" to kind.label,
        "agents" to agents,
        "groups" to groups,
        "params" to params,
        "schedule" to schedule,
        "maxAttempts" to maxAttempts,
        "retryDelayMillis" to retryDelayMillis,
        "timeoutMillis" to timeoutMillis,
        "enabled" to enabled,
        "payload" to payload,
        "description" to description,
    )

    fun cron(): Cron? = schedule.takeIf { it.isNotBlank() }?.let { Cron(it) }

    companion object {
        const val KIND = "EamTask"
        const val DEFAULT_ATTEMPTS = 3
        const val DEFAULT_RETRY_DELAY = 5_000L
        const val DEFAULT_TIMEOUT = 300_000L

        @Suppress("UNCHECKED_CAST")
        fun fromSpec(id: String, spec: Map<String, Any?>): EamTaskDef = EamTaskDef(
            id = id,
            kind = EamTaskKind.parse(spec["kind"] as? String),
            agents = (spec["agents"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            groups = (spec["groups"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            params = (spec["params"] as? Map<String, Any?>) ?: emptyMap(),
            schedule = spec["schedule"] as? String ?: "",
            maxAttempts = (spec["maxAttempts"] as? Number)?.toInt() ?: DEFAULT_ATTEMPTS,
            retryDelayMillis = (spec["retryDelayMillis"] as? Number)?.toLong() ?: DEFAULT_RETRY_DELAY,
            timeoutMillis = (spec["timeoutMillis"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT,
            enabled = spec["enabled"] as? Boolean ?: true,
            payload = spec["payload"] as? String ?: "",
            description = spec["description"] as? String ?: "",
        )
    }
}

/** Run states. A failed attempt with attempts left goes back to [QUEUED] after the retry delay. */
object RunState {
    const val QUEUED = "queued"
    const val DISPATCHED = "dispatched"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/** One execution of a task on one agent. */
data class EamRun(
    val id: String,
    val task: String,
    val agent: String,
    val kind: String,
    var state: String = RunState.QUEUED,
    var attempts: Int = 0,
    val maxAttempts: Int = 1,
    val retryDelayMillis: Long = EamTaskDef.DEFAULT_RETRY_DELAY,
    val timeoutMillis: Long = EamTaskDef.DEFAULT_TIMEOUT,
    val params: Map<String, Any?> = emptyMap(),
    var payload: String = "",
    val createdMillis: Long = 0,
    var dueMillis: Long = 0,
    var dispatchedMillis: Long = 0,
    var finishedMillis: Long = 0,
    var message: String = "",
    var result: Map<String, Any?> = emptyMap(),
) {
    fun view(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "task" to task,
        "agent" to agent,
        "kind" to kind,
        "state" to state,
        "attempts" to attempts,
        "maxAttempts" to maxAttempts,
        "createdMillis" to createdMillis,
        "dueMillis" to dueMillis,
        "dispatchedMillis" to dispatchedMillis.takeIf { it > 0 },
        "finishedMillis" to finishedMillis.takeIf { it > 0 },
        "message" to message,
        "result" to result,
    )

    fun store(): Map<String, Any?> = view() + linkedMapOf(
        "retryDelayMillis" to retryDelayMillis,
        "timeoutMillis" to timeoutMillis,
        "params" to params,
        "payload" to payload,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun load(m: Map<String, Any?>): EamRun = EamRun(
            id = m["id"] as String,
            task = m["task"] as? String ?: "",
            agent = m["agent"] as? String ?: "",
            kind = m["kind"] as? String ?: "",
            state = m["state"] as? String ?: RunState.QUEUED,
            attempts = (m["attempts"] as? Number)?.toInt() ?: 0,
            maxAttempts = (m["maxAttempts"] as? Number)?.toInt() ?: 1,
            retryDelayMillis = (m["retryDelayMillis"] as? Number)?.toLong() ?: EamTaskDef.DEFAULT_RETRY_DELAY,
            timeoutMillis = (m["timeoutMillis"] as? Number)?.toLong() ?: EamTaskDef.DEFAULT_TIMEOUT,
            params = m["params"] as? Map<String, Any?> ?: emptyMap(),
            payload = m["payload"] as? String ?: "",
            createdMillis = (m["createdMillis"] as? Number)?.toLong() ?: 0,
            dueMillis = (m["dueMillis"] as? Number)?.toLong() ?: 0,
            dispatchedMillis = (m["dispatchedMillis"] as? Number)?.toLong() ?: 0,
            finishedMillis = (m["finishedMillis"] as? Number)?.toLong() ?: 0,
            message = m["message"] as? String ?: "",
            result = m["result"] as? Map<String, Any?> ?: emptyMap(),
        )
    }
}

internal val EAM_NAME = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")
internal val EAM_GROUP = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?|""")

/** JSON Schemas and checks for the three EAM resource kinds. */
object EamSchemas {
    private val nameList = mapOf("type" to "array", "maxItems" to 1000, "items" to mapOf("type" to "string", "pattern" to EAM_NAME.pattern.let { "^(?:$it)$" }))
    private val groupPattern = "^(?:${EAM_GROUP.pattern})$"

    fun register(registry: SchemaRegistry) {
        registry.register(GATEWAY_MODULE, EamSettings.KIND, mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to mapOf(
                "role" to mapOf("type" to "string", "enum" to listOf("none", "controller", "agent"), "default" to "none"),
                "controller" to mapOf("type" to "string", "maxLength" to 63, "default" to ""),
                "backupController" to mapOf("type" to "string", "maxLength" to 63, "default" to ""),
                "group" to mapOf("type" to "string", "pattern" to groupPattern, "default" to ""),
                "checkInMillis" to mapOf("type" to "integer", "minimum" to 200, "maximum" to 3_600_000, "default" to EamSettings.DEFAULT_CHECK_IN_MILLIS),
                "autoApprove" to mapOf("type" to "boolean", "default" to false),
                "keepBackups" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100, "default" to EamSettings.DEFAULT_KEEP_BACKUPS),
                "thresholds" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "properties" to mapOf(
                        "offlineMillis" to mapOf("type" to "integer", "minimum" to 1_000, "maximum" to 86_400_000),
                        "cpuPercent" to mapOf("type" to listOf("number", "null"), "minimum" to 0, "maximum" to 100),
                        "memoryPercent" to mapOf("type" to listOf("number", "null"), "minimum" to 0, "maximum" to 100),
                        "diskPercent" to mapOf("type" to listOf("number", "null"), "minimum" to 0, "maximum" to 100),
                        "failedTasks" to mapOf("type" to listOf("integer", "null"), "minimum" to 1, "maximum" to 100_000),
                    ),
                ),
            ),
        ))
        registry.registerSemantic(GATEWAY_MODULE, EamSettings.KIND) { spec ->
            val role = spec["role"] as? String ?: "none"
            val controller = spec["controller"] as? String ?: ""
            buildList {
                if (role == "agent" && controller.isBlank()) add("an agent needs controller (an outgoing gateway-network connection name)")
                for (key in listOf("controller", "backupController")) {
                    val value = spec[key] as? String ?: ""
                    if (value.isNotEmpty() && !EAM_NAME.matches(value)) add("$key must be an outgoing connection name")
                }
            }
        }
        registry.register(GATEWAY_MODULE, EamAgentRecord.KIND, mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to mapOf(
                "fingerprint" to mapOf("type" to "string", "pattern" to "^([0-9a-f]{64})?$"),
                "approved" to mapOf("type" to "boolean"),
                "group" to mapOf("type" to "string", "pattern" to groupPattern),
                "maintenance" to mapOf("type" to "boolean"),
                "description" to mapOf("type" to "string", "maxLength" to 500),
                "pendingFingerprint" to mapOf("type" to "string", "pattern" to "^([0-9a-f]{64})?$"),
            ),
        ))
        registry.register(GATEWAY_MODULE, EamTaskDef.KIND, mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("kind"),
            "properties" to mapOf(
                "kind" to mapOf("type" to "string", "enum" to EamTaskKind.entries.map { it.label }),
                "agents" to nameList,
                "groups" to nameList,
                "params" to mapOf("type" to "object"),
                "schedule" to mapOf("type" to "string", "maxLength" to 120),
                "maxAttempts" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 20),
                "retryDelayMillis" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 86_400_000),
                "timeoutMillis" to mapOf("type" to "integer", "minimum" to 1_000, "maximum" to 86_400_000),
                "enabled" to mapOf("type" to "boolean"),
                "payload" to mapOf("type" to "string", "pattern" to "^([0-9a-f]{32})?$"),
                "description" to mapOf("type" to "string", "maxLength" to 500),
            ),
        ))
        registry.registerSemantic(GATEWAY_MODULE, EamTaskDef.KIND) { spec ->
            buildList {
                val schedule = spec["schedule"] as? String ?: ""
                if (schedule.isNotBlank()) {
                    runCatching { Cron(schedule) }.onFailure { add("schedule is not a five-field cron expression: ${it.message}") }
                }
                val kind = spec["kind"] as? String
                val payload = spec["payload"] as? String ?: ""
                if ((kind == EamTaskKind.MODULE_INSTALL.label || kind == EamTaskKind.UPGRADE.label) && payload.isEmpty()) {
                    add("$kind needs payload (an uploaded package id)")
                }
                @Suppress("UNCHECKED_CAST")
                val params = spec["params"] as? Map<String, Any?> ?: emptyMap()
                if (kind == EamTaskKind.SEND_PROJECT.label && (params["project"] as? String).isNullOrBlank()) add("send-project needs params.project")
                val agents = spec["agents"] as? List<*> ?: emptyList<Any>()
                val groups = spec["groups"] as? List<*> ?: emptyList<Any>()
                if (agents.isEmpty() && groups.isEmpty()) add("a task needs at least one agent or group")
            }
        }
    }
}
