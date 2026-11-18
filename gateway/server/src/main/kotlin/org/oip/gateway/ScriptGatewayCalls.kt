package org.oip.gateway

import org.oip.alarm.notify.NOTIFY_MODULE
import org.oip.alarm.notify.NotifyKinds
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.db.groups.GroupNotFound
import org.oip.scripting.ScriptService
import org.oip.security.SecretReferences
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * `system.project`, `system.net`, `system.secrets`, `system.roster`, `system.eventstream`, `system.groups` and `system.report`
 * (doc source: FEATURES SCR-035, SCR-018, SCR-022, SCR-016, SCR-011, SCR-010, SCR-024). [org.oip.scripting.ScriptHost.libraryCall]
 * has already decided who may call each function; this runs them against the gateway's own services.
 *
 * A secret's value goes in and never comes out: nothing here reads a value back, and no answer, error or log line
 * carries one.
 */
internal object ScriptGatewayCalls {
    private const val SCRIPT_ACTOR = "script"

    fun call(gw: Gateway, function: String, args: List<Any?>): Any? = when (function) {
        "project.getProjectNames" -> gw.projectService.names()
        "project.getProjectName" -> ScriptService.PROJECT
        "project.requestScan" -> {
            gw.store.rescan()
            null
        }
        "net.getHostName" -> hostName()
        "net.getIpAddress" -> ipAddresses().first()
        "net.getIpAddresses" -> ipAddresses()
        "net.getRemoteServers" -> remoteServers(gw, args.getOrNull(0) as? Boolean ?: false)
        "secrets.listSecrets" -> gw.security.secrets.list().map { info ->
            linkedMapOf("name" to info.name, "description" to info.description, "reference" to info.reference,
                "createdAt" to info.createdAt.toEpochMilli(), "updatedAt" to info.updatedAt.toEpochMilli())
        }
        "secrets.secretExists" -> secretName(args).let { name -> gw.security.secrets.list().any { it.name == name } }
        "secrets.writeSecret" -> writeSecret(gw, args)
        "secrets.deleteSecret" -> deleteSecret(gw, secretName(args))
        "roster.getRosterNames" -> gw.store.list(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.ROSTER).map { it.id.name }.sorted()
        "roster.getRoster" -> rosterView(gw, name(args, "roster"))
        "roster.createRoster" -> createRoster(gw, args)
        "roster.setRosterUsers" -> setRosterUsers(gw, name(args, "roster"), users(args.getOrNull(1)))
        "roster.deleteRoster" -> deleteRoster(gw, name(args, "roster"))
        "eventstream.getStreams" -> gw.events.streams().map { it["name"] }
        "eventstream.diagnose" -> diagnose(gw, name(args, "event stream"))
        "eventstream.publish" -> publish(gw, name(args, "event stream"), args.getOrNull(1), args.getOrNull(2))
        "groups.getGroups" -> gw.transactionGroups.list()
        "groups.getGroupStatus" -> group { gw.transactionGroups.status(name(args, "group")) }
        "groups.setGroupEnabled" -> group {
            val group = name(args, "group")
            val enabled = args.getOrNull(1) as? Boolean ?: throw IllegalArgumentException("enabled must be a boolean")
            gw.transactionGroups.setEnabled(group, enabled, SCRIPT_ACTOR)
            gw.transactionGroups.status(group)
        }
        "groups.executeGroup" -> group {
            val outcome = gw.transactionGroups.runOnce(name(args, "group"))
            linkedMapOf("executed" to outcome.executed, "rowsWritten" to outcome.rowsWritten, "tagWrites" to outcome.tagWrites, "warning" to outcome.warning)
        }
        "report.getReportNames" -> gw.reports.definitions().map { it.name }.sorted()
        "report.executeReport" -> executeReport(gw, name(args, "report"), args.getOrNull(1), args.getOrNull(2) as? Boolean ?: true)
        else -> throw IllegalArgumentException("unknown function")
    }

    /**
     * `system.report.executeReport` (SCR-024). Runs the stored report as the system caller (the same engine and run
     * history as `POST /api/v1/reports/{name}/run`; only gateway scripts reach here, and they hold every permission
     * that route asks for). With [distribute] the result goes through the gateway's report sink, so the report's
     * own schedule actions apply: the PDF is saved and emailed, printed or sent by FTP as configured.
     * A delivery failure is reported in the answer, not thrown, because the run itself already succeeded.
     */
    private fun executeReport(gw: Gateway, report: String, parameters: Any?, distribute: Boolean): Map<String, Any?> {
        val supplied = when (parameters) {
            null -> emptyMap()
            is Map<*, *> -> parameters.entries.associate { (it.key as? String ?: throw IllegalArgumentException("parameter names must be strings")) to it.value }
            else -> throw IllegalArgumentException("parameters must be an object")
        }
        val result = try {
            gw.reports.run(report, supplied, org.oip.reporting.ReportCaller.SYSTEM, org.oip.reporting.RunTrigger.MANUAL)
        } catch (e: org.oip.reporting.ReportNotFound) {
            throw IllegalArgumentException(e.message ?: "report not found")
        } catch (e: org.oip.reporting.ReportDisabled) {
            throw IllegalStateException(e.message ?: "report is disabled")
        } catch (e: org.oip.reporting.ReportParameterException) {
            throw IllegalArgumentException(e.message ?: "invalid report parameters")
        }
        var delivered = false
        var deliveryError: String? = null
        if (distribute) {
            try {
                gw.reportSink.deliver(gw.reports.definition(report), result)
                delivered = true
            } catch (e: Exception) {
                deliveryError = e.message ?: e.javaClass.simpleName
            }
        }
        val rows = result.datasets.associate { it.name to it.rows.size }
        return linkedMapOf("report" to report, "runId" to result.run.runId, "status" to result.run.status.wire,
            "generatedAt" to result.generatedAt, "rows" to rows, "warnings" to result.run.warnings,
            "delivered" to delivered, "deliveryError" to deliveryError)
    }

    private fun <T> group(work: () -> T): T = try {
        work()
    } catch (e: GroupNotFound) {
        throw IllegalArgumentException(e.message ?: "transaction group not found")
    }

    /** Resource-style names: lowercase letters, digits and '-', 1 to 63 characters. */
    private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    private fun name(args: List<Any?>, what: String, index: Int = 0): String {
        val value = args.getOrNull(index) as? String
        if (value.isNullOrBlank() || value.length > 200 || value.any { it.code < 0x20 }) throw IllegalArgumentException("$what name is invalid")
        return value
    }

    private fun resourceName(args: List<Any?>, what: String): String =
        name(args, what).also { if (!NAME.matches(it)) throw IllegalArgumentException("$what name must be 1 to 63 lowercase letters, digits and '-'") }

    // --- system.net ---

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "localhost"

    /** Site-local and global addresses of the up, non-loopback interfaces, IPv4 first; loopback when there are none. */
    private fun ipAddresses(): List<String> {
        val found = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
                .sortedBy { if (it is Inet4Address) 0 else 1 }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .distinct()
        }.getOrDefault(emptyList())
        return found.ifEmpty { listOf("127.0.0.1") }
    }

    private fun remoteServers(gw: Gateway, runningOnly: Boolean): List<Map<String, Any?>> {
        val outgoing = gw.network.statusDocument()["outgoing"] as? List<*> ?: emptyList<Any?>()
        return outgoing.mapNotNull { it as? Map<*, *> }
            .map { view ->
                linkedMapOf<String, Any?>(
                    "name" to view["name"], "host" to view["host"], "port" to view["port"],
                    "enabled" to view["enabled"], "status" to view["status"], "gatewayName" to view["gatewayName"],
                )
            }
            .filter { !runningOnly || it["status"] == "running" }
            .sortedBy { it["name"].toString() }
    }

    // --- system.secrets ---

    private fun secretName(args: List<Any?>): String = resourceName(args, "secret")

    private fun writeSecret(gw: Gateway, args: List<Any?>): Any? {
        val secret = secretName(args)
        val value = args.getOrNull(1) as? String ?: throw IllegalArgumentException("value must be a string")
        if (value.isEmpty() || value.length > 16_384) throw IllegalArgumentException("value must be 1 to 16384 characters")
        val description = (args.getOrNull(2) as? String ?: "").also { if (it.length > 500) throw IllegalArgumentException("description is too long") }
        val reference = org.oip.security.AuditContext.call(SCRIPT_ACTOR, context = "script") { gw.security.secrets.putText(secret, value, description) }
        return linkedMapOf("name" to secret, "reference" to reference)
    }

    private fun deleteSecret(gw: Gateway, secret: String): Any? {
        if (gw.security.secrets.list().none { it.name == secret }) throw IllegalArgumentException("secret not found")
        org.oip.security.AuditContext.call(SCRIPT_ACTOR, context = "script") { gw.security.secrets.delete(secret) }
        return null
    }

    // --- system.roster ---

    private fun rosterId(name: String) = ResourceId(NOTIFY_MODULE, Scope.GATEWAY, NotifyKinds.ROSTER, name)

    private fun users(value: Any?): List<String> {
        val list = value as? List<*> ?: throw IllegalArgumentException("users must be a list")
        if (list.size > 200) throw IllegalArgumentException("too many users")
        return list.map { it as? String ?: throw IllegalArgumentException("user names must be strings") }.distinct()
    }

    private fun rosterView(gw: Gateway, name: String): Map<String, Any?> {
        val spec = gw.store.get(rosterId(name))?.spec ?: throw IllegalArgumentException("roster not found")
        return linkedMapOf("name" to name, "description" to (spec["description"] as? String ?: ""), "users" to ((spec["users"] as? List<*>) ?: emptyList<Any?>()))
    }

    private fun writeRoster(gw: Gateway, name: String, description: String, users: List<String>) {
        val unknown = users.filter { gw.security.users.get(it) == null }
        if (unknown.isNotEmpty()) throw IllegalArgumentException("unknown user: ${unknown.first()}")
        val spec = linkedMapOf<String, Any?>("users" to users)
        if (description.isNotEmpty()) spec["description"] = description
        val version = gw.store.get(rosterId(name))?.metadata?.version ?: 0L
        gw.store.put(Scope.GATEWAY, Resource(NotifyKinds.ROSTER, Metadata(name, NOTIFY_MODULE, version = version), spec), SCRIPT_ACTOR)
    }

    private fun createRoster(gw: Gateway, args: List<Any?>): Any? {
        val roster = resourceName(args, "roster")
        if (gw.store.get(rosterId(roster)) != null) throw IllegalArgumentException("roster already exists")
        writeRoster(gw, roster, args.getOrNull(2) as? String ?: "", users(args.getOrNull(1) ?: emptyList<Any?>()))
        return rosterView(gw, roster)
    }

    private fun setRosterUsers(gw: Gateway, roster: String, users: List<String>): Any? {
        val current = rosterView(gw, roster)
        writeRoster(gw, roster, current["description"] as String, users)
        return rosterView(gw, roster)
    }

    private fun deleteRoster(gw: Gateway, roster: String): Any? {
        if (gw.store.get(rosterId(roster)) == null) throw IllegalArgumentException("roster not found")
        gw.store.delete(rosterId(roster))
        return null
    }

    // --- system.eventstream ---

    private fun diagnose(gw: Gateway, stream: String): Map<String, Any?> = try {
        gw.events.stream(stream)
    } catch (e: NoSuchElementException) {
        throw IllegalArgumentException("event stream not found")
    }

    /** Hands one event to a listener-source stream and answers whether its buffer took it (false: back-pressure or disabled). */
    private fun publish(gw: Gateway, stream: String, data: Any?, metadata: Any?): Map<String, Any?> {
        val extra = (metadata as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
        if (extra.size > 32) throw IllegalArgumentException("too many metadata entries")
        val view = diagnose(gw, stream)
        if (view["valid"] != true) throw IllegalArgumentException("event stream is not valid")
        val accepted = try {
            gw.events.publishListener(stream, data, linkedMapOf<String, Any?>("source" to "script") + extra)
        } catch (e: org.oip.events.NodeNotActive) {
            throw IllegalStateException("this node is not active")
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("event stream is not an event listener source")
        }
        if (accepted) gw.events.pump()
        return linkedMapOf("accepted" to accepted)
    }
}
