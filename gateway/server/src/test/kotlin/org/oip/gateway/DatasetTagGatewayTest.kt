package org.oip.gateway

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.stack.core.NodeIds
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.tags.Dataset
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * A dataset memory tag round-trips through REST, the OPC UA server and a gateway script (TAG-014).
 * Column types are kept. A write the type cannot hold is refused and the previous value stays.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class DatasetTagGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun datasetRoundTripsThroughRestOpcUaAndScripts() {
        tmp.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: true
              bindAddress: 127.0.0.1
              port: 0
              hostname: 127.0.0.1
            """.trimIndent() + "\n",
        )
        val gw = Gateway(tmp, port = 0)
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            val created = http.put("/api/v1/config/tags/gateway/Tag/table", tagBody(sample(1, "line")))
            assertEquals(201, created.status, created.body)

            val read = readValue(http)
            assertEquals("Int32", columnType(read, "id"))
            assertEquals("Float", columnType(read, "rate"))
            assertEquals("DateTime", columnType(read, "when"))
            assertEquals("line", cell(read, 0, 1))
            assertEquals("2026-10-27T12:34:56.789Z", cell(read, 0, 4))

            val replaced = write(http, sample(9, "pump"))
            assertEquals(true, replaced["ok"], "$replaced")
            assertEquals("Good", replaced["quality"])
            val after = readValue(http)
            assertEquals("String", columnType(after, "name"))
            assertEquals("pump", cell(after, 0, 1))
            assertEquals(9, (cell(after, 0, 0) as Number).toInt())

            val refused = write(http, mapOf(
                "columns" to listOf(mapOf("name" to "id", "type" to "Int32")),
                "rows" to listOf(listOf("nope")),
            ))
            assertEquals(false, refused["ok"], "$refused")
            assertEquals("Error_TypeConversion", refused["quality"])
            assertEquals("pump", cell(readValue(http), 0, 1))

            val server = g.opcUa!!
            val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(20, TimeUnit.SECONDS)
            val client = connect(server.boundPort, endpoints)
            try {
                val node = walk(client, "Tags", "default", "Plant", "Table")
                val opc = client.readValues(0.0, TimestampsToReturn.Both, listOf(node)).single()
                assertTrue(opc.statusCode.isGood, "opc read ${opc.statusCode}")
                val text = opc.value.value as String
                val parsed = Json.mapper.readValue(text, Map::class.java) as Map<*, *>
                assertEquals("Float", columnType(parsed, "rate"))
                assertEquals("pump", cell(parsed, 0, 1))

                val edited = text.replace("\"pump\"", "\"opc\"")
                val written = client.writeValues(listOf(node), listOf(DataValue(Variant(edited)))).single()
                assertTrue(written.isGood, "opc write $written")
                val engine = g.tags.read("[default]Plant/Table").value as Dataset
                assertEquals("opc", engine.rows[0][1])
                assertEquals("Float", engine.columns[2].type.label())

                val bad = client.writeValues(listOf(node), listOf(DataValue(Variant("not-a-dataset")))).single()
                assertFalse(bad.isGood, "refused opc write was accepted: $bad")
                assertEquals("opc", (g.tags.read("[default]Plant/Table").value as Dataset).rows[0][1])
            } finally {
                runCatching { client.disconnect() }
            }

            val script = g.scripts.run(
                """
                ds = system.dataset.toDataset(
                    [{'name': 'id', 'type': 'Int32'}, {'name': 'name', 'type': 'String'}],
                    [[4, 'script']],
                )
                wrote = system.tag.writeBlocking('[default]Plant/Table', ds)[0]
                back = system.tag.readBlocking('[default]Plant/Table')[0].value
                bad = system.tag.writeBlocking('[default]Plant/Table', {'columns': [{'name': 'id', 'type': 'Int32'}], 'rows': [[1, 2]]})[0]
                _result = {'wrote': wrote, 'type': back.getColumnType('name'), 'name': back.getValueAt(0, 'name'), 'bad': bad, 'kept': back.getValueAt(0, 'id')}
                """.trimIndent(),
            )
            assertTrue(script.ok, "${script.failure}: ${script.error}\n${script.output}")
            val result = script.value as Map<*, *>
            assertEquals("Good", result["wrote"])
            assertEquals("String", result["type"])
            assertEquals("script", result["name"])
            assertEquals("Error_TypeConversion", result["bad"])
            assertEquals(4, (result["kept"] as Number).toInt())
        }
    }

    private fun sample(id: Int, name: String) = mapOf(
        "columns" to listOf(
            mapOf("name" to "id", "type" to "Int32"),
            mapOf("name" to "name", "type" to "String"),
            mapOf("name" to "rate", "type" to "Float"),
            mapOf("name" to "on", "type" to "Boolean"),
            mapOf("name" to "when", "type" to "DateTime"),
        ),
        "rows" to listOf(listOf(id, name, 1.5, true, "2026-10-27T12:34:56.789Z")),
    )

    private fun tagBody(value: Map<String, Any?>) = Json.write(mapOf(
        "apiVersion" to "oip/v1",
        "kind" to "Tag",
        "metadata" to mapOf("name" to "table", "module" to "tags", "version" to 0),
        "spec" to mapOf("provider" to "default", "path" to "Plant/Table", "type" to "memory", "dataType" to "Dataset", "value" to value),
    ))

    private fun readValue(http: Http): Map<*, *> {
        val res = http.post("/api/v1/tags/read", Json.write(mapOf("paths" to listOf("[default]Plant/Table"))))
        assertEquals(200, res.status, res.body)
        val row = (res.json["values"] as List<*>).single() as Map<*, *>
        assertEquals("Good", row["quality"], "$row")
        return row["value"] as Map<*, *>
    }

    private fun write(http: Http, value: Map<String, Any?>): Map<*, *> {
        val res = http.post("/api/v1/tags/write", Json.write(mapOf("writes" to listOf(mapOf("path" to "[default]Plant/Table", "value" to value)))))
        assertEquals(200, res.status, res.body)
        return (res.json["results"] as List<*>).single() as Map<*, *>
    }

    private fun columnType(value: Map<*, *>, name: String): String {
        val columns = value["columns"] as List<*>
        val column = columns.map { it as Map<*, *> }.single { it["name"] == name }
        return column["type"] as String
    }

    private fun cell(value: Map<*, *>, row: Int, column: Int): Any? {
        val rows = value["rows"] as List<*>
        return (rows[row] as List<*>)[column]
    }

    private fun connect(port: Int, endpoints: List<org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription>): OpcUaClient {
        val selected = endpoints.first { ep ->
            val url = ep.endpointUrl ?: ""
            ep.securityPolicyUri == SecurityPolicy.None.uri &&
                url.contains("/oip") &&
                !url.contains("discovery")
        }
        val config = OpcUaClientConfig.builder()
            .setEndpoint(EndpointUtil.updateUrl(selected, "127.0.0.1", port))
            .setApplicationName(LocalizedText.english("oip-dataset-test"))
            .setApplicationUri("urn:oip:test:dataset-tag")
            .setProductUri("urn:oip:test")
            .setRequestTimeout(uint(15_000))
            .setSessionTimeout(uint(60_000))
            .setIdentityProvider(UsernameProvider("ada", TEST_PASSWORD))
            .build()
        val client = OpcUaClient(config, OpcTcpClientTransport(OpcTcpClientTransportConfig.newBuilder().build()))
        try {
            client.connect()
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
        return client
    }

    private fun walk(client: OpcUaClient, vararg names: String): NodeId {
        var node = NodeIds.ObjectsFolder
        for (name in names) {
            val refs = client.addressSpace.browse(node)
            val match = refs.firstOrNull { it.browseName.name.equals(name, ignoreCase = true) }
                ?: error("no '$name' under $node; saw ${refs.map { it.browseName.name }}")
            node = match.nodeId.toNodeId(client.namespaceTable).orElseThrow()
        }
        return node
    }
}
