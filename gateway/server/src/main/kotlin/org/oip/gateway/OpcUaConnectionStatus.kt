package org.oip.gateway

import org.oip.config.Scope
import org.oip.opcua.CONNECTION_KIND
import org.oip.opcua.OpcUaClientModule

/**
 * OPC UA client connection status for `GET /api/v1/diagnostics/opcua/connections`.
 *
 * Each connection reports server state, the session, and each subscription's rate and item count.
 * Doc source: FEATURES DEV-016. Folder and count differences: DIFFERENCES 311-316.
 *
 * [org.oip.opcua.OpcUaConnectionDiagnostics.monitoredItemCount] counts only items the server
 * created. A monitored item the server rejected is not included.
 */
internal fun Gateway.opcUaConnectionsDocument(): Map<String, Any?> {
    val module = host.instance(OpcUaClientModule.MODULE_ID) as? OpcUaClientModule
    val rows = ArrayList<Map<String, Any?>>()
    val now = clock.instant().toEpochMilli()
    for (stored in store.list(OpcUaClientModule.MODULE_ID, Scope.GATEWAY, CONNECTION_KIND)) {
        val name = stored.id.name
        val spec = stored.resource.spec
        val enabled = spec["enabled"] as? Boolean ?: true
        val connection = if (enabled) module?.connection(name) else null
        // One cached ServerStatus read for the page. Not on the subscription callback.
        connection?.refreshServerState()
        val diag = connection?.diagnostics()
        val state = when {
            !enabled -> "Disabled"
            diag == null -> "Not Started"
            else -> titleEnum(diag.state)
        }
        val subscriptions = diag?.subscriptions?.map { sub ->
            linkedMapOf<String, Any?>(
                "name" to sub.name,
                "samplingIntervalMs" to sub.samplingIntervalMs,
                "requestedPublishingIntervalMs" to sub.requestedPublishingIntervalMs,
                "revisedPublishingIntervalMs" to sub.revisedPublishingIntervalMs,
                "monitoredItemCount" to sub.monitoredItemCount,
            )
        } ?: emptyList()
        val uptime = if (diag?.connected == true) maxOf(0L, now - diag.since.toEpochMilli()) else null
        rows += linkedMapOf(
            "name" to name,
            "enabled" to enabled,
            "description" to (spec["description"] as? String ?: ""),
            "endpointUrl" to (spec["endpointUrl"] as? String ?: ""),
            "state" to state,
            "connected" to (diag?.connected == true),
            "serverState" to diag?.serverState,
            "sessionId" to diag?.sessionId,
            "sessionName" to diag?.sessionName,
            "uptimeMs" to uptime,
            "subscriptionCount" to (diag?.subscriptionCount ?: 0),
            "monitoredItemCount" to (diag?.monitoredItemCount ?: 0),
            "samplingIntervalMs" to diag?.samplingIntervalMs,
            "publishIntervalMs" to diag?.publishIntervalMs,
            "requestCount" to (diag?.requestCount ?: 0L),
            "errorCount" to (diag?.errorCount ?: 0L),
            "timeoutCount" to (diag?.timeoutCount ?: 0L),
            "averageResponseTimeMs" to diag?.averageResponseTimeMs,
            "lastError" to diag?.lastError,
            "subscriptions" to subscriptions,
        )
    }
    return mapOf("connections" to rows)
}

private fun titleEnum(enumName: String): String =
    enumName.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
