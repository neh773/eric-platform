package org.oip.gateway

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription
import org.eclipse.milo.opcua.stack.core.AttributeId
import org.eclipse.milo.opcua.stack.core.NodeIds
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.UaException
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagType
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

class OpcUaServerTest {
    @TempDir lateinit var tmp: Path

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun browsesReadsWritesAndNotifies() {
        val gw = gateway(tmp.resolve("live"))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-speed", "tags"), mapOf(
            "path" to "Plant/Speed",
            "type" to "memory",
            "dataType" to "Double",
            "value" to 3.5,
        )))
        gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-locked", "tags"), mapOf(
            "path" to "Plant/Locked",
            "type" to "memory",
            "dataType" to "Int32",
            "value" to 1,
            "writable" to false,
        )))
        gw.use {
            it.start()
            val server = it.opcUa!!
            assertTrue(tmp.resolve("live").resolve("opcua").resolve("server.crt").exists())
            val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
            assertTrue(endpoints.any { ep -> ep.securityPolicyUri == SecurityPolicy.Basic256Sha256.uri })
            val client = connect(server.boundPort, endpoints, "ada", TEST_PASSWORD)
            try {
                val speed = walk(client, "Tags", "default", "Plant", "Speed")
                val read = client.readValues(0.0, TimestampsToReturn.Both, listOf(speed)).single()
                assertTrue(read.statusCode.isGood, "read ${read.statusCode}")
                assertEquals(3.5, (read.value.value as Number).toDouble(), 0.0)
                assertNotNull(read.sourceTime)
                assertFalse(read.sourceTime!!.isNull)

                val written = client.writeValues(listOf(speed), listOf(DataValue(Variant(11.5)))).single()
                assertTrue(written.isGood, "write $written")
                val after = client.readValues(0.0, TimestampsToReturn.Both, listOf(speed)).single()
                assertEquals(11.5, (after.value.value as Number).toDouble(), 0.0)

                val seen = java.util.concurrent.CompletableFuture<DataValue>()
                val sub = OpcUaSubscription(client, 100.0)
                sub.create()
                val item = OpcUaMonitoredItem.newDataItem(speed)
                item.samplingInterval = 0.0
                item.setDataValueListener { _, value ->
                    val number = (value.value.value as? Number)?.toDouble()
                    if (number == 19.0 && value.statusCode.isGood) seen.complete(value)
                }
                sub.addMonitoredItem(item)
                sub.synchronizeMonitoredItems()
                val changed = client.writeValues(listOf(speed), listOf(DataValue(Variant(19.0)))).single()
                assertTrue(changed.isGood, "change $changed")
                val note = seen.get(15, TimeUnit.SECONDS)
                assertNotNull(note.sourceTime)
                assertFalse(note.sourceTime!!.isNull)
                assertTrue(note.statusCode.isGood)

                val system = walk(client, "Tags", "system", "Gateway", "Name")
                val denied = client.writeValues(listOf(system), listOf(DataValue(Variant("nope")))).single()
                assertEquals(StatusCodes.Bad_UserAccessDenied, denied.value)
                val name = client.readValues(0.0, TimestampsToReturn.Both, listOf(system)).single()
                assertEquals("open-industrial-platform", name.value.value)

                val locked = walk(client, "Tags", "default", "Plant", "Locked")
                val rejected = client.writeValues(listOf(locked), listOf(DataValue(Variant(2)))).single()
                assertEquals(StatusCodes.Bad_NotWritable, rejected.value)
                val lockedValue = client.readValues(0.0, TimestampsToReturn.Both, listOf(locked)).single()
                assertEquals(1, (lockedValue.value.value as Number).toInt())
            } finally {
                runCatching { client.disconnect() }
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun badCredentialsAreRejected() {
        val gw = gateway(tmp.resolve("auth"))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.use {
            it.start()
            val endpoints = DiscoveryClient.getEndpoints(it.opcUa!!.endpointUrl).get(15, TimeUnit.SECONDS)
            val thrown = assertThrows<Exception> {
                connect(it.opcUa!!.boundPort, endpoints, "ada", "not-the-password")
            }
            val ua = generateSequence(thrown as Throwable) { it.cause }.filterIsInstance<UaException>().firstOrNull()
            assertNotNull(ua, "expected UaException, got $thrown")
            assertEquals(StatusCodes.Bad_IdentityTokenInvalid, ua!!.statusCode.value)
        }
    }

    /** C-1: each session reads, writes and monitors as its own user against per-tag permissions (TAG-013). */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun sessionsActAsTheirUserForReadsWritesAndMonitoredItems() {
        val gw = gateway(tmp.resolve("perms"))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.createUser("vic", setOf(DefaultRoles.VIEWER))
        gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-recipe", "tags"), mapOf(
            "path" to "Plant/Recipe", "type" to "memory", "dataType" to "String", "value" to "mix",
            "readPermissions" to listOf("operator"),
        )))
        gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-setpoint", "tags"), mapOf(
            "path" to "Plant/Setpoint", "type" to "memory", "dataType" to "Double", "value" to 1.0,
            "writePermissions" to listOf("operator"),
        )))
        gw.use {
            it.start()
            val server = it.opcUa!!
            val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
            val vic = connect(server.boundPort, endpoints, "vic", TEST_PASSWORD)
            val ada = connect(server.boundPort, endpoints, "ada", TEST_PASSWORD)
            try {
                val recipe = walk(vic, "Tags", "default", "Plant", "Recipe")
                val setpoint = walk(vic, "Tags", "default", "Plant", "Setpoint")

                // vic (viewer) may not read the recipe: no value, Bad_UserAccessDenied.
                val hidden = vic.readValues(0.0, TimestampsToReturn.Both, listOf(recipe)).single()
                assertEquals(StatusCodes.Bad_UserAccessDenied, hidden.statusCode.value)
                assertEquals(null, hidden.value.value)
                val shown = ada.readValues(0.0, TimestampsToReturn.Both, listOf(recipe)).single()
                assertTrue(shown.statusCode.isGood, "operator read ${shown.statusCode}")
                assertEquals("mix", shown.value.value)

                // Monitored items: vic's item never carries the value; ada's sees the change.
                val vicNotes = java.util.concurrent.CopyOnWriteArrayList<DataValue>()
                val adaSaw = java.util.concurrent.CompletableFuture<DataValue>()
                val vicSub = OpcUaSubscription(vic, 50.0).also { s -> s.create() }
                val vicItem = OpcUaMonitoredItem.newDataItem(recipe).also { i -> i.samplingInterval = 0.0 }
                vicItem.setDataValueListener { _, value -> vicNotes += value }
                vicSub.addMonitoredItem(vicItem)
                vicSub.synchronizeMonitoredItems()
                val adaSub = OpcUaSubscription(ada, 50.0).also { s -> s.create() }
                val adaItem = OpcUaMonitoredItem.newDataItem(recipe).also { i -> i.samplingInterval = 0.0 }
                adaItem.setDataValueListener { _, value -> if (value.value.value == "blend") adaSaw.complete(value) }
                adaSub.addMonitoredItem(adaItem)
                adaSub.synchronizeMonitoredItems()
                it.tags.write("Plant/Recipe", "blend")
                adaSaw.get(15, TimeUnit.SECONDS)
                Thread.sleep(300)
                assertTrue(vicNotes.isNotEmpty(), "vic's item is still told something")
                assertTrue(vicNotes.all { n -> n.statusCode.value == StatusCodes.Bad_UserAccessDenied && n.value.value == null }, "$vicNotes")

                // Writes: vic is refused and audited; ada writes and is audited.
                val refused = vic.writeValues(listOf(setpoint), listOf(DataValue(Variant(9.0)))).single()
                assertEquals(StatusCodes.Bad_UserAccessDenied, refused.value)
                assertEquals(1.0, (it.tags.read("Plant/Setpoint").value as Number).toDouble(), 0.0)
                val failure = it.security.audit.query(org.oip.security.AuditQuery(action = org.oip.tags.TAG_WRITE_ACTION, actor = "vic")).records.single()
                assertEquals(org.oip.security.AuditStatus.FAILURE, failure.status)
                assertEquals("gateway-opcua", failure.context)
                assertEquals("127.0.0.1", failure.actorHost)
                val written = ada.writeValues(listOf(setpoint), listOf(DataValue(Variant(4.5)))).single()
                assertTrue(written.isGood, "operator write $written")
                assertEquals(4.5, (it.tags.read("Plant/Setpoint").value as Number).toDouble(), 0.0)
                val success = it.security.audit.query(org.oip.security.AuditQuery(action = org.oip.tags.TAG_WRITE_ACTION, actor = "ada")).records.single()
                assertEquals(org.oip.security.AuditStatus.SUCCESS, success.status)
                assertTrue(success.detail.contains("new=4.5"), success.detail)
            } finally {
                runCatching { vic.disconnect() }
                runCatching { ada.disconnect() }
            }
        }
    }

    /**
     * Review-2 F7: a user without the gateway `tags.write` permission (the default viewer role) may not write over
     * OPC UA, even a tag with no `writePermissions`. The write is refused with Bad_UserAccessDenied, the value is
     * unchanged, the refusal is audited, and UserAccessLevel has no CurrentWrite for that session.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun aSessionWithoutTagsWriteMayNotWriteAnyTag() {
        val gw = gateway(tmp.resolve("viewer"))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.createUser("vic", setOf(DefaultRoles.VIEWER))
        gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-open", "tags"), mapOf(
            "path" to "Plant/Open", "type" to "memory", "dataType" to "Double", "value" to 1.0,
        )))
        gw.use {
            it.start()
            val server = it.opcUa!!
            val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
            val vic = connect(server.boundPort, endpoints, "vic", TEST_PASSWORD)
            val ada = connect(server.boundPort, endpoints, "ada", TEST_PASSWORD)
            fun userAccess(client: OpcUaClient, node: NodeId): Int {
                val dv = client.read(0.0, TimestampsToReturn.Neither,
                    listOf(ReadValueId(node, AttributeId.UserAccessLevel.uid(), null, QualifiedName.NULL_VALUE))).results!!.single()
                assertTrue(dv.statusCode.isGood, "UserAccessLevel ${dv.statusCode}")
                return (dv.value.value as Number).toInt()
            }
            try {
                val open = walk(vic, "Tags", "default", "Plant", "Open")
                // The viewer still reads the tag.
                val read = vic.readValues(0.0, TimestampsToReturn.Both, listOf(open)).single()
                assertTrue(read.statusCode.isGood, "viewer read ${read.statusCode}")
                // CurrentWrite (0x02) is off for the viewer and on for the operator.
                assertEquals(0, userAccess(vic, open) and 0x02, "viewer UserAccessLevel")
                assertEquals(0x02, userAccess(ada, open) and 0x02, "operator UserAccessLevel")

                val refused = vic.writeValues(listOf(open), listOf(DataValue(Variant(9.0)))).single()
                assertEquals(StatusCodes.Bad_UserAccessDenied, refused.value)
                assertEquals(1.0, (it.tags.read("Plant/Open").value as Number).toDouble(), 0.0)
                val failure = it.security.audit.query(org.oip.security.AuditQuery(action = org.oip.tags.TAG_WRITE_ACTION, actor = "vic")).records.single()
                assertEquals(org.oip.security.AuditStatus.FAILURE, failure.status)
                assertEquals("gateway-opcua", failure.context)

                val written = ada.writeValues(listOf(open), listOf(DataValue(Variant(2.5)))).single()
                assertTrue(written.isGood, "operator write $written")
                assertEquals(2.5, (it.tags.read("Plant/Open").value as Number).toDouble(), 0.0)
            } finally {
                runCatching { vic.disconnect() }
                runCatching { ada.disconnect() }
            }
        }
    }

    /**
     * S-1: a session that may not read a tag still browses to it (names and kinds are structure), but its DataType
     * and the other variable attributes are refused, so the type does not leak; an admitted session reads them.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun attributesOfATagTheSessionMayNotReadAreRefused() {
        val gw = gateway(tmp.resolve("attrs"))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.createUser("root", setOf(DefaultRoles.ADMINISTRATOR))
        gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-secret", "tags"), mapOf(
            "path" to "Plant/Secret", "type" to "memory", "dataType" to "Int64", "value" to 7,
            "readPermissions" to listOf(DefaultRoles.ADMINISTRATOR),
        )))
        gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-open", "tags"), mapOf(
            "path" to "Plant/Open", "type" to "memory", "dataType" to "Double", "value" to 1.0,
        )))
        gw.use {
            it.start()
            val server = it.opcUa!!
            val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
            val ada = connect(server.boundPort, endpoints, "ada", TEST_PASSWORD)
            val root = connect(server.boundPort, endpoints, "root", TEST_PASSWORD)
            fun attr(client: OpcUaClient, node: NodeId, attribute: AttributeId): DataValue =
                client.read(0.0, TimestampsToReturn.Neither, listOf(ReadValueId(node, attribute.uid(), null, QualifiedName.NULL_VALUE)))
                    .results!!.single()
            try {
                // Browse: both sessions find the tag by name.
                val secret = walk(ada, "Tags", "default", "Plant", "Secret")
                assertEquals(secret, walk(root, "Tags", "default", "Plant", "Secret"))
                val open = walk(ada, "Tags", "default", "Plant", "Open")

                // The operator gets the browse attributes, and nothing that describes the value.
                assertEquals("Secret", (attr(ada, secret, AttributeId.BrowseName).value.value as QualifiedName).name)
                assertTrue(attr(ada, secret, AttributeId.NodeClass).statusCode.isGood)
                for (refused in listOf(AttributeId.DataType, AttributeId.ValueRank, AttributeId.AccessLevel, AttributeId.UserAccessLevel,
                    AttributeId.MinimumSamplingInterval, AttributeId.Historizing, AttributeId.Value)) {
                    val dv = attr(ada, secret, refused)
                    assertEquals(StatusCodes.Bad_UserAccessDenied, dv.statusCode.value, "$refused")
                    assertEquals(null, dv.value.value, "$refused")
                }
                assertEquals(NodeIds.Double, attr(ada, open, AttributeId.DataType).value.value, "a tag without read permissions is unaffected")

                // The administrator role is listed on the tag: its type and value are readable.
                assertEquals(NodeIds.Int64, attr(root, secret, AttributeId.DataType).value.value)
                assertTrue(attr(root, secret, AttributeId.AccessLevel).statusCode.isGood)
                assertEquals(7L, (attr(root, secret, AttributeId.Value).value.value as Number).toLong())

                // Deleted and re-created with other permissions: the server reads the new tag, not a cached old one.
                it.store.delete(org.oip.config.ResourceId("tags", Scope.GATEWAY, "Tag", "plant-secret"))
                it.store.put(Scope.GATEWAY, Resource("Tag", Metadata("plant-secret", "tags"), mapOf(
                    "path" to "Plant/Secret", "type" to "memory", "dataType" to "Int64", "value" to 8,
                    "readPermissions" to listOf(DefaultRoles.OPERATOR),
                )))
                assertEquals(8L, (attr(ada, secret, AttributeId.Value).value.value as Number).toLong())
                assertEquals(NodeIds.Int64, attr(ada, secret, AttributeId.DataType).value.value)
                assertEquals(StatusCodes.Bad_UserAccessDenied, attr(root, secret, AttributeId.Value).statusCode.value)
            } finally {
                runCatching { ada.disconnect() }
                runCatching { root.disconnect() }
            }
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun thousandsOfMonitoredItemsDeliverChanges() {
        val count = 4_000
        val gw = gateway(tmp.resolve("bulk"))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        for (i in 0 until count) {
            gw.tags.configure(TagConfig(
                path = "Bulk/n$i",
                type = TagType.MEMORY,
                dataType = DataType.INT32,
                value = 0,
                valueSet = true,
            ))
        }
        gw.use {
            it.start()
            assertTrue(it.opcUa!!.deliveryStripes >= 4, "stripes ${it.opcUa!!.deliveryStripes}")
            val endpoints = DiscoveryClient.getEndpoints(it.opcUa!!.endpointUrl).get(15, TimeUnit.SECONDS)
            val client = connect(it.opcUa!!.boundPort, endpoints, "ada", TEST_PASSWORD, requestTimeout = 120_000)
            try {
                val ns = it.opcUa!!.namespaceIndex
                val sub = OpcUaSubscription(client, 100.0)
                sub.create()
                val arrived = AtomicInteger(0)
                val flags = AtomicIntegerArray(count)
                for (i in 0 until count) {
                    val item = OpcUaMonitoredItem.newDataItem(NodeId(ns, "Tags/default/Bulk/n$i"))
                    item.samplingInterval = 0.0
                    item.setDataValueListener { _, value ->
                        val number = (value.value.value as? Number)?.toInt() ?: return@setDataValueListener
                        if (number == i + 1 && value.statusCode.isGood && flags.compareAndSet(i, 0, 1)) {
                            arrived.incrementAndGet()
                        }
                    }
                    sub.addMonitoredItem(item)
                }
                sub.synchronizeMonitoredItems()
                val pool = Executors.newFixedThreadPool(8)
                try {
                    val tasks = (0 until count).map { i ->
                        pool.submit { it.tags.write("[default]Bulk/n$i", i + 1) }
                    }
                    tasks.forEach { task -> task.get(60, TimeUnit.SECONDS) }
                } finally {
                    pool.shutdown()
                }
                val deadline = System.nanoTime() + 30_000_000_000L
                while (arrived.get() < count && System.nanoTime() < deadline) Thread.sleep(20)
                assertEquals(count, arrived.get(), "notifications")
            } finally {
                runCatching { client.disconnect() }
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun certificateIsReusedAcrossStarts() {
        val dir = tmp.resolve("cert")
        val first = gateway(dir)
        val bytes = first.use {
            it.start()
            it.opcUa!!.certificate.encoded
        }
        val second = gateway(dir)
        second.use {
            it.start()
            assertTrue(dir.resolve("opcua").resolve("server.crt").readBytes().contentEquals(bytes))
            assertTrue(it.opcUa!!.certificate.encoded.contentEquals(bytes))
        }
    }

    private fun gateway(dir: Path): Gateway {
        dir.toFile().mkdirs()
        dir.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: true
              bindAddress: 127.0.0.1
              port: 0
              hostname: 127.0.0.1
            """.trimIndent() + "\n",
        )
        return Gateway(dir, port = 0)
    }

    private fun connect(
        port: Int,
        endpoints: List<org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription>,
        username: String,
        password: String,
        requestTimeout: Long = 15_000,
    ): OpcUaClient {
        val selected = endpoints.first { ep ->
            ep.securityPolicyUri == SecurityPolicy.None.uri &&
                ep.endpointUrl.contains("/oip") &&
                !ep.endpointUrl.contains("discovery")
        }
        val config = OpcUaClientConfig.builder()
            .setEndpoint(EndpointUtil.updateUrl(selected, "127.0.0.1", port))
            .setApplicationName(LocalizedText.english("oip-opcua-test"))
            .setApplicationUri("urn:oip:test:opcua-server")
            .setProductUri("urn:oip:test")
            .setRequestTimeout(uint(requestTimeout))
            .setSessionTimeout(uint(60_000))
            .setIdentityProvider(UsernameProvider(username, password))
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
        for (name in names) node = child(client, node, name)
        return node
    }

    private fun child(client: OpcUaClient, parent: NodeId, name: String): NodeId {
        val refs = client.addressSpace.browse(parent)
        val match = refs.firstOrNull { it.browseName.name.equals(name, ignoreCase = true) }
            ?: error("no '$name' under $parent; saw ${refs.map { it.browseName.name }}")
        return match.nodeId.toNodeId(client.namespaceTable).orElseThrow()
    }
}
