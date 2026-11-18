package org.oip.gateway

import org.oip.alarm.notify.Holiday
import org.oip.alarm.notify.NOTIFY_MODULE
import org.oip.alarm.notify.NotifyCatalog
import org.oip.alarm.notify.NotifyCodec
import org.oip.alarm.notify.NotifyKinds
import org.oip.alarm.notify.Schedules
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.opcua.CONNECTION_KIND
import org.oip.opcua.OpcUaClientModule
import org.oip.security.CallerScope
import org.oip.security.CreateUser
import org.oip.security.DefaultRoles
import org.oip.security.PasswordRules
import org.oip.security.UpdateUser
import org.oip.security.UserAccount
import java.time.Instant
import java.time.ZoneOffset

/**
 * `system.opcua`, `system.user` and `system.security` functions that need live gateway services (SCR-021,
 * SCR-023, SCR-027). [org.oip.scripting.ScriptHost.libraryCall] has already decided who may call each one;
 * this runs them. Nothing here returns a password, a password hash, or another credential.
 */
internal object ScriptLibraryCalls {
    private const val SCRIPT_ACTOR = "script"

    /**
     * Password hashing (Argon2id) allocates tens of megabytes. It runs on these threads, not the script's, so it
     * is not charged to the script's allocation limit and only two run at once.
     */
    private val hashing = java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "oip-script-hash").apply { isDaemon = true }
    }

    private fun <T> onHashThread(work: () -> T): T = try {
        hashing.submit<T> { work() }.get(30, java.util.concurrent.TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    } catch (e: java.util.concurrent.TimeoutException) {
        throw IllegalStateException("password check timed out")
    }

    fun call(gw: Gateway, function: String, args: List<Any?>, actor: String): Any? = when (function) {
        "opcua.getConnectionInfo" -> connectionInfo(gw, text(args, 0, "name"))
        "opcua.addConnection" -> addConnection(gw, args)
        "opcua.removeConnection" -> removeConnection(gw, text(args, 0, "name"))
        "opcua.setConnectionEnabled" -> setConnectionEnabled(gw, text(args, 0, "name"), args.getOrNull(1) as? Boolean ?: throw IllegalArgumentException("enabled must be a boolean"))
        "opcua.callMethod" -> callMethod(gw, text(args, 0, "connection"), text(args, 1, "objectId"), text(args, 2, "method"), args.getOrNull(3))
        "user.getUser" -> accountView(gw.security.users.get(text(args, 0, "username")) ?: throw IllegalArgumentException("user not found"))
        "user.addUser" -> addUser(gw, args)
        "user.editUser" -> editUser(gw, args)
        "user.removeUser" -> removeUser(gw, text(args, 0, "username"))
        "user.getSchedules" -> scheduleSpecs(gw).map { (name, spec) -> scheduleView(name, spec) }
        "user.getSchedule" -> text(args, 0, "name").let { name ->
            scheduleView(name, scheduleSpecs(gw)[name] ?: throw IllegalArgumentException("schedule not found"))
        }
        "user.isScheduleActive" -> isScheduleActive(gw, text(args, 0, "name"), (args.getOrNull(1) as? Number)?.toLong())
        "user.getHolidays" -> holidays(gw).map { holiday ->
            mapOf("name" to holiday.name, "date" to holiday.date.toString(), "repeatAnnually" to holiday.repeatAnnually)
        }
        "security.validateUser" -> validateUser(gw, text(args, 0, "username"), args.getOrNull(1) as? String ?: throw IllegalArgumentException("password must be a string"), actor)
        "security.getUserRoles" -> gw.security.users.get(text(args, 0, "username"))?.roles?.sorted() ?: emptyList<String>()
        "util.translate" -> Translations.translate(
            gw.projectService, text(args, 0, "term"),
            (args.getOrNull(1) as? String)?.takeIf { it.isNotBlank() }?.also { text(args, 1, "locale") },
            (args.getOrNull(2) as? String)?.takeIf { it.isNotBlank() }?.also { text(args, 2, "project") },
        )
        "sfc.startChart" -> gw.charts.startChart(text(args, 0, "chart"), stringMap(args.getOrNull(1), "parameters"))
        "sfc.cancelChart" -> text(args, 0, "chart id").let { gw.charts.cancel(it); null }
        "sfc.pauseChart" -> text(args, 0, "chart id").let { gw.charts.pause(it); null }
        "sfc.resumeChart" -> text(args, 0, "chart id").let { gw.charts.resume(it); null }
        "sfc.setVariable" -> {
            gw.charts.setVariable(text(args, 0, "chart id"), text(args, 1, "variable"), args.getOrNull(2))
            null
        }
        "sfc.getRunningCharts" -> {
            val only = (args.getOrNull(0) as? String)?.takeIf { it.isNotBlank() }
            gw.charts.instances().filter { (only == null || it["chart"] == only) && it["stateCode"] !in SFC_TERMINAL_CODES }
        }
        else -> if (ScriptDriverCalls.handles(function)) ScriptDriverCalls.call(gw, function, args) else ScriptGatewayCalls.call(gw, function, args)
    }

    /** Chart state codes of finished instances: derived from the engine's terminal states. */
    private val SFC_TERMINAL_CODES = org.oip.sfc.ChartState.values().filter { it.terminal }.map { it.code }.toSet()

    private fun stringMap(value: Any?, what: String): Map<String, Any?> {
        if (value == null) return emptyMap()
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("$what must be an object")
        return map.entries.associate { (it.key as? String ?: throw IllegalArgumentException("$what names must be strings")) to it.value }
    }

    private fun text(args: List<Any?>, index: Int, what: String): String {
        val value = args.getOrNull(index) as? String
        if (value.isNullOrBlank() || value.length > 1_000 || value.any { it.code < 0x20 }) throw IllegalArgumentException("$what is invalid")
        return value
    }

    // --- system.security ---

    /**
     * True only for an enabled user whose password matches. A wrong password, a missing user and a disabled
     * user all answer false, and a missing user costs the same hashing work (see UserDirectory.check).
     */
    private fun validateUser(gw: Gateway, username: String, password: String, actor: String): Boolean {
        if (password.codePointCount(0, password.length) > PasswordRules.MAX_CODE_POINTS) return false
        val check = onHashThread { gw.security.users.check(username, password.toCharArray(), actor, "script") }
        return check.exists && check.enabled == true && check.passwordValid == true
    }

    // --- system.user: users ---

    private fun accountView(user: UserAccount): Map<String, Any?> = linkedMapOf(
        "username" to user.username,
        "displayName" to user.displayName,
        "enabled" to user.enabled,
        "roles" to user.roles.sorted(),
        "passwordExpired" to user.passwordExpired,
    )

    private fun addUser(gw: Gateway, args: List<Any?>): Any? {
        val username = text(args, 0, "username")
        val password = args.getOrNull(1) as? String ?: throw IllegalArgumentException("password must be a string")
        val roles = stringSet(args.getOrNull(2)) ?: emptySet()
        val created = onHashThread {
            gw.security.users.create(
                CreateUser(
                    username = username,
                    password = password.toCharArray(),
                    roles = roles,
                    displayName = args.getOrNull(3) as? String ?: "",
                    enabled = args.getOrNull(4) as? Boolean ?: true,
                ),
                actor = SCRIPT_ACTOR,
                caller = CallerScope.GATEWAY,
            )
        }
        return accountView(created)
    }

    private fun editUser(gw: Gateway, args: List<Any?>): Any? {
        val username = text(args, 0, "username")
        val roles = stringSet(args.getOrNull(1))
        val enabled = args.getOrNull(3) as? Boolean
        val current = gw.security.users.get(username) ?: throw IllegalArgumentException("user not found")
        val losesAdmin = DefaultRoles.ADMINISTRATOR in current.roles && current.enabled &&
            ((roles != null && DefaultRoles.ADMINISTRATOR !in roles) || enabled == false)
        if (losesAdmin) requireAnotherAdministrator(gw, username, "change")
        val updated = gw.security.users.update(
            UpdateUser(username, displayName = args.getOrNull(2) as? String, enabled = enabled, roles = roles),
            actor = SCRIPT_ACTOR,
            caller = CallerScope.GATEWAY,
        )
        return accountView(updated)
    }

    private fun removeUser(gw: Gateway, username: String): Any? {
        val current = gw.security.users.get(username) ?: throw IllegalArgumentException("user not found")
        if (DefaultRoles.ADMINISTRATOR in current.roles && current.enabled) requireAnotherAdministrator(gw, username, "remove")
        gw.security.users.delete(username, actor = SCRIPT_ACTOR, caller = CallerScope.GATEWAY)
        return null
    }

    /** The gateway must keep one enabled administrator; a script cannot lock everyone out. */
    private fun requireAnotherAdministrator(gw: Gateway, username: String, verb: String) {
        val others = gw.security.users.list().count { it.username != username && it.enabled && DefaultRoles.ADMINISTRATOR in it.roles }
        if (others == 0) throw IllegalArgumentException("cannot $verb the last enabled administrator")
    }

    private fun stringSet(value: Any?): Set<String>? {
        if (value == null) return null
        val list = value as? List<*> ?: throw IllegalArgumentException("roles must be a list")
        return list.map { it as? String ?: throw IllegalArgumentException("role names must be strings") }.toSet()
    }

    // --- system.user: schedules and holidays ---

    private fun scheduleSpecs(gw: Gateway): Map<String, Map<String, Any?>> =
        gw.store.list(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.SCHEDULE).associate { it.id.name to it.resource.spec }.toSortedMap()

    private fun scheduleView(name: String, spec: Map<String, Any?>): Map<String, Any?> {
        val view = linkedMapOf<String, Any?>("name" to name, "type" to (spec["type"] ?: "standard"))
        for ((key, value) in spec) if (key != "type") view[key] = value
        return view
    }

    private fun holidays(gw: Gateway): List<Holiday> = gw.store.list(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.HOLIDAY)
        .mapNotNull { stored -> runCatching { NotifyCodec.holiday(stored.id.name, stored.resource.spec) }.getOrNull() }
        .sortedBy { it.name }

    private fun isScheduleActive(gw: Gateway, name: String, timestamp: Long?): Boolean {
        val specs = scheduleSpecs(gw)
        if (name != Schedules.ALWAYS && name !in specs) throw IllegalArgumentException("schedule not found")
        val schedules = specs.mapNotNull { (n, spec) -> runCatching { n to NotifyCodec.schedule(n, spec) }.getOrNull() }.toMap()
        val catalog = NotifyCatalog(schedules = schedules, holidays = holidays(gw))
        val at = if (timestamp == null) gw.clock.instant() else Instant.ofEpochMilli(timestamp)
        return Schedules.active(name, catalog, at, ZoneOffset.UTC)
    }

    // --- system.opcua ---

    private fun opcuaId(name: String) = ResourceId(OpcUaClientModule.MODULE_ID, Scope.GATEWAY, CONNECTION_KIND, name)

    private fun storedConnection(gw: Gateway, name: String) =
        gw.store.get(opcuaId(name)) ?: throw IllegalArgumentException("connection not found")

    private fun connectionInfo(gw: Gateway, name: String): Map<String, Any?> {
        val spec = storedConnection(gw, name).spec
        val live = (gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule)?.connection(name)
        val info = linkedMapOf<String, Any?>(
            "name" to name,
            "enabled" to (spec["enabled"] as? Boolean ?: true),
            "state" to (live?.connectionStatus()?.state?.name ?: "NOT_RUNNING"),
        )
        // Only the settings that describe where and how it connects; no certificates paths or credentials.
        for (key in listOf("endpointUrl", "securityPolicy", "securityMode", "requestTimeoutMs", "sessionTimeoutMs")) {
            spec[key]?.let { info[key] = it }
        }
        return info
    }

    private fun addConnection(gw: Gateway, args: List<Any?>): Any? {
        val name = text(args, 0, "name")
        if (gw.store.get(opcuaId(name)) != null) throw IllegalArgumentException("connection already exists")
        val spec = linkedMapOf<String, Any?>(
            "endpointUrl" to text(args, 1, "endpointUrl"),
            "enabled" to (args.getOrNull(2) as? Boolean ?: true),
            "securityPolicy" to (args.getOrNull(3) as? String ?: "NONE"),
            "securityMode" to (args.getOrNull(4) as? String ?: "NONE"),
        )
        gw.store.put(Scope.GATEWAY, Resource(CONNECTION_KIND, Metadata(name, OpcUaClientModule.MODULE_ID), spec), SCRIPT_ACTOR)
        return connectionInfo(gw, name)
    }

    private fun removeConnection(gw: Gateway, name: String): Any? {
        storedConnection(gw, name)
        gw.store.delete(opcuaId(name))
        return null
    }

    private fun setConnectionEnabled(gw: Gateway, name: String, enabled: Boolean): Any? {
        val stored = storedConnection(gw, name)
        gw.store.put(
            Scope.GATEWAY,
            Resource(CONNECTION_KIND, stored.metadata, stored.spec + ("enabled" to enabled)),
            SCRIPT_ACTOR,
        )
        return connectionInfo(gw, name)
    }

    private val NODE_ID = Regex("^(ns=\\d+;)?[isgb]=.+$")

    /**
     * Call a method for real: [method] is a node id, or the display name of a method found by browsing [objectId]
     * (an empty or `Objects` object id is the Objects folder). [inputs] is a list of values.
     */
    private fun callMethod(gw: Gateway, connection: String, objectId: String, method: String, inputs: Any?): Map<String, Any?> {
        val conn = (gw.host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule)?.connection(connection)
            ?: throw IllegalArgumentException("connection not found")
        val values = (inputs as? List<*> ?: emptyList<Any?>()).toList()
        if (values.size > 32) throw IllegalArgumentException("too many inputs")
        val browseId = if (objectId.equals("Objects", true)) "" else objectId
        val target = if (NODE_ID.matches(method)) method else {
            conn.browse(browseId).find { node ->
                node.nodeClass.equals("Method", ignoreCase = true) && node.displayName == method
            }?.nodeId ?: throw IllegalArgumentException("no method $method")
        }
        val owner = if (browseId.isEmpty()) "i=85" else browseId
        val result = conn.callMethod(owner, target, values)
        return linkedMapOf(
            "nodeId" to target,
            "invoked" to true,
            "good" to result.good,
            "statusCode" to "0x" + java.lang.Long.toHexString(result.statusCode).padStart(8, '0'),
            "outputs" to result.outputs,
            "inputResults" to result.inputResults.map { "0x" + java.lang.Long.toHexString(it).padStart(8, '0') },
        )
    }
}
