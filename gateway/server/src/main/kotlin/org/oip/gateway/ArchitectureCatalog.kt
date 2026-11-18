package org.oip.gateway

import org.oip.mqtt.MqttDriverModule
import org.oip.tags.StandardTagProvider

/**
 * Reference architectures evaluated against this gateway's live settings.
 *
 * A row matches only when the checks against profile, bind address, redundancy, edge sync,
 * peers, remote tag providers, zones, service policies, and MQTT connections say so.
 * The subsystem table is what redundancy actually stores. It is not a list of required databases.
 */
class ArchitectureCatalog(private val gateway: Gateway) {
    fun document(): Map<String, Any?> {
        val settings = gateway.settings
        val network = gateway.network.statusDocument()
        val outgoing = rows(network, "outgoing")
        val incoming = rows(network, "incoming")
        val policies = rows(network, "policies")
        val remoteProviders = rows(network, "providers")
        val localProviders = gateway.tags.providers().filterIsInstance<StandardTagProvider>().map { it.name }
        val zones = gateway.security.zones.list()
        val mqtt = gateway.host.instance(MqttDriverModule.MODULE_ID) as? MqttDriverModule
        val brokers = mqtt?.connectionNames() ?: emptyList()
        val edgeNodes = mqtt?.edgeNodeNames() ?: emptyList()
        val sync = settings.edgeSync
        val spoke = sync.remoteGateway.isNotEmpty() &&
            (sync.history.enabled || sync.audit.enabled || sync.alarmJournal.enabled)
        val loopback = loopback(settings.host)
        val mode = settings.redundancy.mode.name.lowercase()
        val standby = settings.redundancy.standbyActivityLevel.name.lowercase()
        val historyMode = settings.redundancy.historyMode.name.lowercase()
        val paired = mode == "master" || mode == "backup"
        val customZone = zones.any { !it.isDefault }
        val wideOpen = policies.any { it["peer"] == "*" && it["access"] == "allow" }
        val node = linkedMapOf(
            "profile" to settings.profile,
            "bindHost" to settings.host,
            "loopbackOnly" to loopback,
            "publicAddress" to (settings.publicAddress ?: ""),
            "https" to settings.httpsEnabled,
            "redundancyMode" to mode,
            "standby" to standby,
            "historyMode" to historyMode,
            "outgoing" to outgoing.size,
            "incoming" to incoming.size,
            "remoteProviders" to remoteProviders.size,
            "localProviders" to localProviders.size,
            "zones" to zones.size,
            "mqttConnections" to brokers.size,
            "edgeNodes" to edgeNodes.size,
            "edgeSpoke" to spoke,
        )
        val single = listOf(
            check("independent", "Redundancy is independent", mode == "independent", "Mode is $mode."),
            check("no-outgoing", "No outgoing gateway connection", outgoing.isEmpty(), "${outgoing.size} outgoing."),
            check("no-incoming", "No incoming gateway connection", incoming.isEmpty(), "${incoming.size} incoming."),
            check("no-remote-tags", "No remote tag provider", remoteProviders.isEmpty(), "${remoteProviders.size} remote providers."),
            check("no-edge-sync", "Edge sync streams are off", !spoke, if (spoke) "A sync stream is enabled." else "No sync stream is enabled."),
        )
        val enterprise = listOf(
            check(
                "peer",
                "At least one gateway peer",
                outgoing.isNotEmpty() || incoming.isNotEmpty(),
                "${outgoing.size} outgoing, ${incoming.size} incoming.",
            ),
        )
        val panel = listOf(
            check("edge-profile", "Profile is edge", settings.profile == "edge", "Profile is ${settings.profile}."),
            check("loopback", "The HTTP bind is loopback", loopback, "Bind is ${settings.host}."),
            check("local-only", "Edge sync streams are off", !spoke, if (spoke) "A sync stream is enabled." else "No sync stream is enabled."),
        )
        val iiot = listOf(
            check("edge-profile", "Profile is edge", settings.profile == "edge", "Profile is ${settings.profile}."),
            check(
                "broker-or-node",
                "An MQTT connection or a Sparkplug edge node is configured",
                brokers.isNotEmpty() || edgeNodes.isNotEmpty(),
                "${brokers.size} MQTT connections, ${edgeNodes.size} edge nodes.",
            ),
        )
        val edgeSync = listOf(
            check(
                "spoke-or-hub",
                "This gateway is a sync spoke or a hub",
                spoke || settings.profile == "hub",
                when {
                    spoke -> "Sync target ${sync.remoteGateway}."
                    settings.profile == "hub" -> "Hub profile."
                    else -> "No sync stream and the profile is ${settings.profile}."
                },
            ),
        )
        val cloud = listOf(
            check("reachable", "The HTTP bind is not loopback-only", !loopback, "Bind is ${settings.host}."),
            check(
                "public-name",
                "HTTPS is on or a public address is set",
                settings.httpsEnabled || !settings.publicAddress.isNullOrBlank(),
                "HTTPS ${settings.httpsEnabled}, public address ${settings.publicAddress ?: "unset"}.",
            ),
        )
        val scaleOut = listOf(
            check("front-end", "A remote tag provider makes this a front end", remoteProviders.isNotEmpty(), "${remoteProviders.size} remote providers."),
            check(
                "back-end",
                "An incoming peer and a local tag provider make this a back end",
                incoming.isNotEmpty() && localProviders.isNotEmpty(),
                "${incoming.size} incoming, ${localProviders.size} local providers.",
            ),
        )
        val segmentation = listOf(
            check("custom-zone", "A zone besides Default exists", customZone, "${zones.size} zones."),
            check("explicit-policy", "A service policy is set", policies.isNotEmpty(), "${policies.size} policies."),
            check("not-wide-open", "No policy allows every service for every peer", !wideOpen, if (wideOpen) "A wildcard allow is set." else "No wildcard allow."),
        )
        val independent = listOf(check("independent", "Redundancy mode is independent", mode == "independent", "Mode is $mode."))
        val warm = listOf(
            check("paired", "This node is a master or a backup", paired, "Mode is $mode."),
            check("warm", "Standby is warm", standby == "warm", "Standby is $standby."),
        )
        val cold = listOf(
            check("paired", "This node is a master or a backup", paired, "Mode is $mode."),
            check("cold", "Standby is cold", standby == "cold", "Standby is $standby."),
        )
        return linkedMapOf(
            "node" to node,
            "architectures" to listOf(
                architecture("single-server", "GAN-006", "Single server", "One gateway holds tags, alarms, and the web interface, with no peer and no edge sync.", single, single.all { it.met }),
                architecture("enterprise", "GAN-007", "Enterprise", "More than one gateway, joined by an approved gateway-network connection.", enterprise, enterprise.all { it.met }),
                architecture("edge-panel", "GAN-008", "Edge panel", "An edge-profile gateway bound to loopback, with sync streams off.", panel, panel.all { it.met }),
                architecture("edge-iiot", "GAN-008", "Edge IIoT", "An edge-profile gateway with an MQTT connection or a Sparkplug edge node.", iiot, iiot.all { it.met }),
                architecture("edge-sync", "GAN-008", "Edge sync", "A spoke forwarding history, audit, or the alarm journal, or a hub profile that receives them.", edgeSync, edgeSync.all { it.met }),
                architecture("cloud", "GAN-011", "Cloud hosted", "The same gateway binary, bound beyond loopback, with HTTPS or a public address.", cloud, cloud.all { it.met }),
                architecture("scale-out", "GAN-012", "Scale-out", "A front end reads remote tag providers. A back end owns local tags and accepts incoming peers. One gateway may be either or both.", scaleOut, scaleOut.any { it.met }),
                architecture("segmentation", "GAN-013", "Segmentation", "Clients fall into a zone besides Default, and gateway-network access is an explicit policy that is not a wildcard allow.", segmentation, segmentation.all { it.met }),
                architecture("independent", "RED-004", "Independent", "One node. No master and no backup.", independent, independent.all { it.met }),
                architecture("warm-pair", "RED-004", "Warm master and backup", "The backup subscribes while standby and does not write devices or log history. Config and runtime still sync.", warm, warm.all { it.met }),
                architecture("cold-pair", "RED-004", "Cold master and backup", "The backup connects but does not subscribe, write devices, or log history until it is active.", cold, cold.all { it.met }),
            ),
            "subsystems" to subsystems(historyMode, mode),
        )
    }

    private fun subsystems(historyMode: String, mode: String): List<Map<String, Any?>> = listOf(
        subsystem(
            "config",
            "Configuration",
            "copied",
            "file-store",
            "Tag and project resources copy to the backup. A resource labeled oip-local does not. gateway.yaml, including redundancy settings, stays on the node. This node is $mode.",
        ),
        subsystem("tag-values", "Tag values", "copied", "none", "Memory and OPC values sync at runtime. There is no tag-value database in the pair."),
        subsystem("live-alarms", "Live alarms", "copied", "none", "Live alarm state, including a shelf, syncs with runtime values."),
        subsystem("alarm-journal", "Alarm journal", "active-node", "local-file", "The journal appends only on the active node. The pair does not copy it."),
        subsystem(
            "history",
            "History",
            "buffer",
            "none",
            "Failover history is an in-memory buffer in $historyMode mode. Partial mode on a backup keeps samples taken while that node is active. This is not a SQL historian.",
        ),
        subsystem("audit", "Audit", "node-local", "local-file", "Redundancy does not copy the audit chain. Edge sync can forward audit rows separately."),
        subsystem("security-config", "Security configuration", "copied", "file-store", "Users, roles, and zones copy with other configuration unless the resource is labeled oip-local."),
        subsystem("certificates", "Certificates", "node-local", "none", "HTTPS and gateway-network certificates stay on the node that minted them."),
        subsystem("gateway-network-identity", "Gateway-network identity", "node-local", "none", "The gateway key and trust list under the data directory are not configuration resources."),
    )
}

private data class Check(val id: String, val label: String, val met: Boolean, val detail: String)

private fun check(id: String, label: String, met: Boolean, detail: String) = Check(id, label, met, detail)

private fun Check.toMap(): Map<String, Any?> = linkedMapOf("id" to id, "label" to label, "met" to met, "detail" to detail)

private fun architecture(
    id: String,
    feature: String,
    title: String,
    summary: String,
    checks: List<Check>,
    fits: Boolean,
): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "feature" to feature,
    "title" to title,
    "summary" to summary,
    "fits" to fits,
    "checks" to checks.map { it.toMap() },
)

private fun subsystem(id: String, title: String, redundancy: String, database: String, detail: String): Map<String, Any?> =
    linkedMapOf("id" to id, "title" to title, "redundancy" to redundancy, "database" to database, "detail" to detail)

@Suppress("UNCHECKED_CAST")
private fun rows(document: Map<String, Any?>, key: String): List<Map<String, Any?>> =
    (document[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

private fun loopback(host: String): Boolean {
    val name = host.trim().lowercase()
    return name == "localhost" || name == "127.0.0.1" || name == "::1" || name == "[::1]" || name == "0:0:0:0:0:0:0:1"
}
