package org.oip.mqtt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** `EdgeNode` resources start, restart, and stop edge nodes without a gateway restart. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SparkplugEdgeModuleTest {
    @TempDir
    lateinit var tmp: Path

    @Test fun edgeNodeResourceLifecycle() {
        val id = MqttDriverModule.MODULE_ID
        val modules = tmp.resolve("modules")
        modules.resolve(id).createDirectories().resolve("module.yaml").writeText(javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n"))
        val config = tmp.resolve("config")
        val store = FileConfigStore(config, SchemaRegistry())
        val host = ModuleHost(modules, config, store = store)
        RoutingBroker().use { broker ->
            host.installAll()
            val module = host.instance(id) as MqttDriverModule
            val tags = FakeFeed().apply { set("[default]A", 1, dataType = "Int32") }
            module.tagFeed = tags
            host.start(id)
            try {
                assertThrows(ValidationException::class.java) {
                    store.put(Scope.GATEWAY, Resource(EDGE_NODE_KIND, Metadata("bad", id), mapOf("host" to "h", "groupId" to "a/b", "edgeNodeId" to "E")))
                }
                assertThrows(ValidationException::class.java) {
                    store.put(Scope.GATEWAY, Resource(EDGE_NODE_KIND, Metadata("bad", id), mapOf("host" to "h", "groupId" to "G", "edgeNodeId" to "E", "password" to "plain")))
                }
                val spec = mapOf(
                    "host" to "127.0.0.1",
                    "port" to broker.port,
                    "groupId" to "MG",
                    "edgeNodeId" to "ME",
                    "reconnectInitialMs" to 50,
                    "nodeMetrics" to listOf(mapOf("name" to "A", "tag" to "[default]A")),
                )
                store.put(Scope.GATEWAY, Resource(EDGE_NODE_KIND, Metadata("line", id), spec))
                awaitTrue("first birth") { broker.published("spBv1.0/MG/NBIRTH/ME").isNotEmpty() }
                val first = SparkplugCodec.decode(broker.published("spBv1.0/MG/NBIRTH/ME").last().payload)
                assertEquals(0L, first.metrics.first { it.name == "bdSeq" }.value)
                assertEquals(listOf("bdSeq", "Node Control/Rebirth", "A"), first.metrics.map { it.name })

                module.setEdgeActive(false)
                awaitTrue("an inactive node ends the session") { broker.published("spBv1.0/MG/NDEATH/ME").size == 1 && module.edgeNode("line")?.connected() == false }
                module.setEdgeActive(true)
                awaitTrue("active again") { broker.published("spBv1.0/MG/NBIRTH/ME").size == 2 }

                val stored = store.get(org.oip.config.ResourceId(id, Scope.GATEWAY, EDGE_NODE_KIND, "line"))!!
                store.put(
                    Scope.GATEWAY,
                    Resource(EDGE_NODE_KIND, Metadata("line", id, version = stored.metadata.version), spec + ("nodeMetrics" to listOf(mapOf("name" to "B", "tag" to "[default]A")))),
                )
                awaitTrue("birth after update") { broker.published("spBv1.0/MG/NBIRTH/ME").size == 3 }
                val second = SparkplugCodec.decode(broker.published("spBv1.0/MG/NBIRTH/ME").last().payload)
                assertEquals(2L, second.metrics.first { it.name == "bdSeq" }.value, "bdSeq keeps counting across a config update")
                assertEquals("B", second.metrics.last().name)

                val deathsBeforeInactive = broker.published("spBv1.0/MG/NDEATH/ME").size
                module.setEdgeActive(false)
                // setEdgeActive only stores a flag. Wait for the IO thread to end the session, or a quick
                // inactive-then-active pulse can pass before it looks and the session never ends.
                awaitTrue("line ends its session while inactive") {
                    broker.published("spBv1.0/MG/NDEATH/ME").size > deathsBeforeInactive && module.edgeNode("line")?.connected() == false
                }
                store.put(
                    Scope.GATEWAY,
                    Resource(EDGE_NODE_KIND, Metadata("standby", id), spec + mapOf("groupId" to "SG", "edgeNodeId" to "SE")),
                )
                awaitTrue("standby node exists") { module.edgeNode("standby") != null }
                assertEquals(false, module.edgeNode("standby")!!.active(), "a node created while inactive starts inactive")
                module.setEdgeActive(true)
                awaitTrue("standby node births once active") { broker.published("spBv1.0/SG/NBIRTH/SE").isNotEmpty() }
                awaitTrue("line births again") { broker.published("spBv1.0/MG/NBIRTH/ME").size == 4 && module.edgeNode("line")?.online() == true }
                store.delete(org.oip.config.ResourceId(id, Scope.GATEWAY, EDGE_NODE_KIND, "standby"))

                val deaths = broker.published("spBv1.0/MG/NDEATH/ME").size
                store.delete(org.oip.config.ResourceId(id, Scope.GATEWAY, EDGE_NODE_KIND, "line"))
                awaitTrue("NDEATH on delete") { broker.published("spBv1.0/MG/NDEATH/ME").size > deaths }
                assertNull(module.edgeNode("line"))
            } finally {
                host.stop(id)
                host.uninstall(id)
            }
        }
    }
}
