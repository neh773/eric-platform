package org.oip.opcua

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.eclipse.milo.opcua.sdk.core.AccessLevel
import org.eclipse.milo.opcua.sdk.core.Reference
import org.eclipse.milo.opcua.sdk.server.EndpointConfig
import org.eclipse.milo.opcua.sdk.server.Lifecycle
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle
import org.eclipse.milo.opcua.sdk.server.OpcUaServer
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator
import org.eclipse.milo.opcua.sdk.server.identity.CompositeValidator
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator
import org.eclipse.milo.opcua.sdk.server.identity.X509IdentityValidator
import org.eclipse.milo.opcua.sdk.server.items.DataItem
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem
import org.eclipse.milo.opcua.sdk.server.methods.AbstractMethodInvocationHandler
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaMethodNode
import org.eclipse.milo.opcua.stack.core.types.structured.Argument
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel
import org.eclipse.milo.opcua.stack.core.NodeIds
import org.eclipse.milo.opcua.stack.core.StatusCodes
import org.eclipse.milo.opcua.stack.core.UaException
import org.eclipse.milo.opcua.stack.core.security.DefaultApplicationGroup
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager
import org.eclipse.milo.opcua.stack.core.security.DefaultServerCertificateValidator
import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager
import org.eclipse.milo.opcua.stack.core.security.KeyStoreCertificateStore
import org.eclipse.milo.opcua.stack.core.security.MemoryTrustListManager
import org.eclipse.milo.opcua.stack.core.security.RsaSha256CertificateFactory
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.SchemaRegistry
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.KeyPair
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * In-process Eclipse Milo server (EPL-2.0). The server is the simulator, not the driver under test.
 * Throughput numbers are in-process loopback, not a network device.
 * A TCP proxy drops the channel while the server process stays up. Milo reactivates a session that
 * is still alive. TransferSubscriptions runs when that session is gone and its subscriptions were
 * kept (Part 4 §5.14.7). A server restart deletes them, so the client creates the subscription again.
 */
class OpcUaDriverTest {
    @TempDir lateinit var tmp: Path

    @Test fun discoversNoneAndSecureEndpoints() {
        SimServer(tmp.resolve("pki-discover")).use { server ->
            val found = OpcUaConnection.discover(server.endpointUrl)
            assertTrue(found.any {
                it.securityPolicyUri == SecurityPolicy.None.uri && it.securityMode == "None" &&
                    "Anonymous" in it.userTokenTypes && "UserName" in it.userTokenTypes && "Certificate" in it.userTokenTypes
            })
            assertTrue(found.any {
                it.securityPolicyUri == SecurityPolicy.Basic256Sha256.uri && it.securityMode == "SignAndEncrypt"
            })
            assertTrue(found.any { it.endpointUrl.contains("/oip") })
        }
    }

    @Test fun readsWritesAndBrowses() {
        SimServer(tmp.resolve("pki-rw")).use { server ->
            val conn = connection(server)
            conn.connect()
            assertEquals(ConnectionState.CONNECTED, conn.connectionStatus().state)
            val root = conn.browse("")
            val folder = root.single { it.displayName == "Sim" }
            val children = conn.browse(folder.nodeId)
            assertTrue(children.any { it.displayName == "Counter" })
            val counter = server.nodeId("Counter")
            val read = conn.read(listOf(counter, "not a node id"))
            assertEquals(0, read[0].value)
            assertEquals(QualityCode.Good, read[0].quality.code)
            assertEquals(QualityCode.Bad_ConfigurationError, read[1].quality.code)
            assertEquals(QualityCode.Good, conn.write(counter, 42).quality.code)
            assertEquals(42, conn.read(listOf(counter)).single().value)
            // Milo's access controller rejects a write when AccessLevel has no CurrentWrite
            // and reports Bad_UserAccessDenied (0x801F0000). The value stays put.
            val staticWrite = conn.write(server.nodeId("Static"), 1)
            assertEquals(QualityCode.Bad_UserAccessDenied, staticWrite.quality.code)
            assertEquals(0x801F0000L, staticWrite.quality.statusCode)
            assertEquals(1, conn.read(listOf(server.nodeId("Static"))).single().value)
            assertEquals(QualityCode.Bad_NotFound, conn.read(listOf(server.nodeId("missing"))).single().quality.code)
            conn.close()
        }
    }

    @Test fun callsAMethodWithInputsConvertedToTheDeclaredTypes() {
        SimServer(tmp.resolve("pki-method")).use { server ->
            val conn = connection(server)
            conn.connect()
            val folder = conn.browse("").single { it.displayName == "Sim" }
            val method = conn.browse(folder.nodeId).single { it.displayName == "Add" }
            assertEquals("Method", method.nodeClass)
            // Longs from a script are Int32 for an Int32 argument.
            val result = conn.callMethod(folder.nodeId, method.nodeId, listOf(40L, 2L))
            assertTrue(result.good, "status 0x" + java.lang.Long.toHexString(result.statusCode) + " inputs " + result.inputResults)
            assertEquals(listOf<Any?>(42), result.outputs)
            assertThrows(IllegalArgumentException::class.java) { conn.callMethod(folder.nodeId, method.nodeId, listOf(1L)) }
            val missing = conn.callMethod(folder.nodeId, server.nodeId("NoSuchMethod"), emptyList())
            assertFalse(missing.good)
            conn.close()
        }
    }

    @Test fun usernameAndCertificateAuth() {
        SimServer(tmp.resolve("pki-auth")).use { server ->
            val ok = connection(server, auth = OpcUaAuth.Username("user", "password1"))
            ok.connect()
            assertEquals(1, ok.read(listOf(server.nodeId("Static"))).single().value)
            ok.close()

            val bad = connection(server, auth = OpcUaAuth.Username("user", "nope"))
            val denied = runCatching { bad.connect() }.exceptionOrNull()
            assertTrue(denied is UaException, "expected UaException, got $denied")
            // ActivateSession rejects a bad username/password as Bad_IdentityTokenInvalid.
            assertEquals(StatusCodes.Bad_IdentityTokenInvalid, (denied as UaException).statusCode.value)
            bad.close()

            val userCert = OpcUaCertificates.generate("urn:oip:user", "oip-user")
            val certConn = connection(
                server,
                auth = OpcUaAuth.Certificate(userCert.certificate, userCert.keyPair.private),
            )
            certConn.connect()
            assertEquals(QualityCode.Good, certConn.read(listOf(server.nodeId("Static"))).single().quality.code)
            certConn.close()
        }
    }

    @Test fun secureChannelWithBasic256Sha256() {
        SimServer(tmp.resolve("pki-secure")).use { server ->
            val pki = tmp.resolve("pki-client-secure")
            trustServer(pki, server.certificate)
            val material = OpcUaCertificates.generate("urn:oip:driver-opcua-client", "ERIC OPC UA Client")
            server.trust(material.certificate)
            val conn = connection(
                server,
                policy = OpcUaSecurityPolicy.BASIC256SHA256,
                mode = OpcUaSecurityMode.SIGN_AND_ENCRYPT,
                keyPair = material.keyPair,
                certificate = material.certificate,
                pkiDir = pki,
            )
            conn.connect()
            assertEquals(ConnectionState.CONNECTED, conn.connectionStatus().state)
            assertEquals(QualityCode.Good, conn.write(server.nodeId("Temperature"), 21.5).quality.code)
            assertEquals(21.5, conn.read(listOf(server.nodeId("Temperature"))).single().value)
            conn.close()
        }
    }

    @Test fun untrustedServerCertificateIsRejectedUntilMovedToTrusted() {
        SimServer(tmp.resolve("pki-server-reject")).use { server ->
            val pki = tmp.resolve("pki-client-reject")
            val material = OpcUaCertificates.generate("urn:oip:driver-opcua-client", "ERIC OPC UA Client")
            server.trust(material.certificate)
            val denied = connection(
                server,
                policy = OpcUaSecurityPolicy.BASIC256SHA256,
                mode = OpcUaSecurityMode.SIGN_AND_ENCRYPT,
                keyPair = material.keyPair,
                certificate = material.certificate,
                pkiDir = pki,
            )
            val error = runCatching { denied.connect() }.exceptionOrNull()
            assertTrue(error is UaException, "expected the untrusted server certificate to be rejected, got $error")
            denied.close()
            val rejected = pki.resolve("rejected/certs").listDirectoryEntries("*.der")
            assertEquals(1, rejected.size, "rejected certificates: $rejected")
            val trustedDir = pki.resolve("trusted/certs")
            trustedDir.createDirectories()
            rejected.single().moveTo(trustedDir.resolve(rejected.single().name))
            val accepted = connection(
                server,
                policy = OpcUaSecurityPolicy.BASIC256SHA256,
                mode = OpcUaSecurityMode.SIGN_AND_ENCRYPT,
                keyPair = material.keyPair,
                certificate = material.certificate,
                pkiDir = pki,
            )
            accepted.connect()
            assertEquals(ConnectionState.CONNECTED, accepted.connectionStatus().state)
            assertEquals(QualityCode.Good, accepted.read(listOf(server.nodeId("Temperature"))).single().quality.code)
            accepted.close()
        }
    }

    @Test fun trustAllServerCertificatesFlagSkipsValidation() {
        SimServer(tmp.resolve("pki-server-trustall")).use { server ->
            val material = OpcUaCertificates.generate("urn:oip:driver-opcua-client", "ERIC OPC UA Client")
            server.trust(material.certificate)
            val conn = connection(
                server,
                policy = OpcUaSecurityPolicy.BASIC256SHA256,
                mode = OpcUaSecurityMode.SIGN_AND_ENCRYPT,
                keyPair = material.keyPair,
                certificate = material.certificate,
                trustAllServerCertificates = true,
            )
            conn.connect()
            assertEquals(ConnectionState.CONNECTED, conn.connectionStatus().state)
            assertEquals(QualityCode.Good, conn.read(listOf(server.nodeId("Flag"))).single().quality.code)
            conn.close()
        }
    }

    @Test fun watchDeliversValuesFromOneSamplingGroup() {
        SimServer(tmp.resolve("pki-watch")).use { server ->
            server.setCounter(11)
            val conn = connection(server)
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            val counter = conn.watch(server.nodeId("Counter"), 100.0) { seen += it }
            val flag = conn.watch(server.nodeId("Flag"), 100.0) { seen += it }
            conn.connect()
            conn.flushWatches()
            await("both good") {
                seen.any { it.address == server.nodeId("Counter") && it.value == 11 && it.quality.code == QualityCode.Good } &&
                    seen.any { it.address == server.nodeId("Flag") && it.quality.code == QualityCode.Good }
            }
            counter.close()
            flag.close()
            conn.close()
        }
    }

    @Test fun benchConnectionSpecValidates() {
        val registry = SchemaRegistry()
        registry.register(OpcUaClientModule.MODULE_ID, CONNECTION_KIND, opcUaConnectionSchema())
        val spec = mapOf(
            "name" to "bench-opc",
            "endpointUrl" to "opc.tcp://127.0.0.1:12686/oip/bench",
            "securityPolicy" to "NONE",
            "securityMode" to "NONE",
            "trustAllServerCertificates" to false,
        )
        val applied = registry.applyDefaults(OpcUaClientModule.MODULE_ID, CONNECTION_KIND, spec)
        val config = opcUaConnectionConfig("bench-opc", applied)
        assertEquals("bench-opc", config.name)
        assertEquals(spec["endpointUrl"], config.endpointUrl)
        assertEquals(OpcUaSecurityPolicy.NONE, config.securityPolicy)
        assertFalse(config.trustAllServerCertificates)
        assertThrows(org.oip.config.ValidationException::class.java) {
            registry.check(org.oip.config.Resource(
                CONNECTION_KIND,
                org.oip.config.Metadata("bench-opc", OpcUaClientModule.MODULE_ID),
                spec + ("password" to "nope"),
            ))
        }
        // A username sign-in keeps only a secret:// reference to the password.
        val signIn = opcUaConnectionConfig("bench-opc", applied + mapOf("username" to "op1", "password" to "secret://opcua-bench-opc"))
        assertEquals(OpcUaAuth.Username("op1", "secret://opcua-bench-opc"), signIn.auth)
        registry.check(org.oip.config.Resource(
            CONNECTION_KIND,
            org.oip.config.Metadata("bench-opc", OpcUaClientModule.MODULE_ID),
            spec + mapOf("username" to "op1", "password" to "secret://opcua-bench-opc"),
        ))
        val file = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n").trim()
        assertEquals(file, OpcUaClientModule.MANIFEST_YAML.trim())
    }

    @Test fun subscriptionPublishesSamplingAndPublishingIntervals() {
        SimServer(tmp.resolve("pki-sub")).use { server ->
            val conn = connection(server, keepAliveIntervalMs = 1_000, keepAliveTimeoutMs = 1_000)
            conn.connect()
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            val sub = conn.subscribe(listOf(server.nodeId("Counter")), 100.0, 100.0) { seen += it }
            assertTrue(sub.samplingIntervalMs > 0.0)
            assertTrue(sub.publishingIntervalMs > 0.0)
            await("initial") { seen.any { it.quality.code == QualityCode.Good } }
            server.setCounter(7)
            await("change") { seen.any { it.value == 7 && it.quality.code == QualityCode.Good } }
            val sample = seen.last { it.value == 7 }
            assertTrue(sample.sourceTimestamp != null)
            sub.close()
            conn.close()
        }
    }

    @Test fun commsLossSetsBadQualityThenRecovers() {
        SimServer(tmp.resolve("pki-loss")).use { server ->
            TcpProxy(server.port).use { proxy ->
                val conn = connection(
                    server,
                    endpointUrl = "opc.tcp://127.0.0.1:${proxy.port}/oip",
                    connectHost = "127.0.0.1",
                    connectPort = proxy.port,
                    keepAliveIntervalMs = 400,
                    keepAliveTimeoutMs = 400,
                    keepAliveFailuresAllowed = 1,
                )
                conn.connect()
                val seen = CopyOnWriteArrayList<QualifiedValue>()
                val sub = conn.subscribe(listOf(server.nodeId("Counter")), 200.0, 200.0) { seen += it }
                await("initial good") { seen.any { it.quality.code == QualityCode.Good && it.value == 0 } }
                proxy.drop()
                await("bad after loss") {
                    seen.any { it.quality.code == QualityCode.Bad_NotConnected && it.value == 0 }
                }
                val during = seen.last { it.quality.code == QualityCode.Bad_NotConnected }
                assertEquals(0, during.value)
                assertEquals(QualityLevel.BAD, during.quality.level)
                assertEquals(0x808A0000L, during.quality.statusCode)
                assertEquals(ConnectionState.RECONNECTING, conn.connectionStatus().state)
                val afterLoss = seen.size
                server.setCounter(9)
                proxy.resume()
                await("recovered") {
                    conn.connectionStatus().state == ConnectionState.CONNECTED &&
                        seen.drop(afterLoss).any { it.quality.code == QualityCode.Good && it.value == 9 }
                }
                println(
                    "OPC UA in-process recovery transfers=${conn.subscriptionTransfers} failures=${conn.subscriptionTransferFailures} recreates=${conn.subscriptionRecreates}",
                )
                // SecurityPolicy.None cannot transfer an anonymous subscription (Bad_UserAccessDenied).
                // Milo reactivates the surviving session, so the subscription is neither transferred nor recreated.
                assertEquals(0, conn.subscriptionTransfers)
                assertEquals(0, conn.subscriptionRecreates)
                sub.close()
                conn.close()
            }
        }
    }

    @Test fun secureChannelDropTransfersTheSubscription() {
        // Part 4 §5.14.7: anonymous TransferSubscriptions requires Sign or SignAndEncrypt and the
        // same ApplicationUri. Milo reactivates a session that is still alive, so this test drops
        // the channel and then closes that session without deleting its subscriptions. The client
        // opens a new session and transfers. The count comes from the TransferSubscriptions result
        // and Milo's subscription listener, not from comparing subscription ids.
        SimServer(tmp.resolve("pki-transfer-server")).use { server ->
            val pki = tmp.resolve("pki-transfer-client")
            trustServer(pki, server.certificate)
            TcpProxy(server.port).use { proxy ->
                val material = OpcUaCertificates.generate("urn:oip:driver-opcua-client", "ERIC OPC UA Client")
                server.trust(material.certificate)
                val conn = connection(
                    server,
                    endpointUrl = "opc.tcp://127.0.0.1:${proxy.port}/oip",
                    connectHost = "127.0.0.1",
                    connectPort = proxy.port,
                    policy = OpcUaSecurityPolicy.BASIC256SHA256,
                    mode = OpcUaSecurityMode.SIGN_AND_ENCRYPT,
                    keyPair = material.keyPair,
                    certificate = material.certificate,
                    pkiDir = pki,
                    keepAliveIntervalMs = 400,
                    keepAliveTimeoutMs = 400,
                    keepAliveFailuresAllowed = 1,
                )
                conn.connect()
                val seen = CopyOnWriteArrayList<QualifiedValue>()
                val node = server.nodeId("Counter")
                val sub = conn.subscribe(listOf(node), 200.0, 200.0) { seen += it }
                await("initial good") { seen.any { it.quality.code == QualityCode.Good && it.value == 0 } }
                proxy.drop()
                await("bad after loss") { seen.any { it.quality.code == QualityCode.Bad_NotConnected } }
                server.closeSessions(deleteSubscriptions = false)
                val afterLoss = seen.size
                server.setCounter(11)
                proxy.resume()
                await("transferred") {
                    conn.connectionStatus().state == ConnectionState.CONNECTED &&
                        conn.subscriptionTransfers == 1 &&
                        seen.drop(afterLoss).any { it.quality.code == QualityCode.Good && it.value == 11 }
                }
                println(
                    "OPC UA in-process transfer transfers=${conn.subscriptionTransfers} failures=${conn.subscriptionTransferFailures} recreates=${conn.subscriptionRecreates}",
                )
                assertEquals(1, conn.subscriptionTransfers)
                assertEquals(0, conn.subscriptionRecreates)
                assertEquals(0, conn.subscriptionTransferFailures)
                sub.close()
                conn.close()
            }
        }
    }

    @Test fun serverRestartRecreatesTheSubscription() {
        SimServer(tmp.resolve("pki-restart")).use { server ->
            val conn = connection(server, keepAliveIntervalMs = 400, keepAliveTimeoutMs = 400, keepAliveFailuresAllowed = 1)
            conn.connect()
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            val sub = conn.subscribe(listOf(server.nodeId("Counter")), 200.0, 200.0) { seen += it }
            await("initial good") { seen.any { it.quality.code == QualityCode.Good && it.value == 0 } }
            server.restart()
            server.setCounter(6)
            val after = seen.size
            await("recreated") {
                conn.subscriptionRecreates >= 1 &&
                    conn.connectionStatus().state == ConnectionState.CONNECTED &&
                    seen.drop(after).any { it.quality.code == QualityCode.Good && it.value == 6 }
            }
            println(
                "OPC UA in-process restart transfers=${conn.subscriptionTransfers} failures=${conn.subscriptionTransferFailures} recreates=${conn.subscriptionRecreates}",
            )
            assertEquals(0, conn.subscriptionTransfers)
            assertTrue(conn.subscriptionRecreates >= 1, "recreates=${conn.subscriptionRecreates}")
            sub.close()
            conn.close()
        }
    }

    @Test fun lostNotificationIsReadAgainInsteadOfLeftStaleAndGood() {
        // review-2 F6: Milo calls onNotificationDataLost when a missing NotificationMessage cannot
        // be republished. The proxy drops the PublishResponse carrying Counter=42 and answers the
        // Republish with Bad_MessageNotAvailable. Counter never changes again, so only a re-read
        // can deliver 42; before the fix the tag kept 0 with Good quality.
        //
        // Arm the drop only after a subscription notification has been forwarded. Items existing
        // is not that moment: the initial NotificationMessage (sequence 1) may still be in flight,
        // and dropping it lets Counter=42 through on the next publish. The value is then already
        // Good while the re-read is still queued, and notificationResyncs is still 0 (scheduled
        // run 41462239700). The re-read increments the counter before it delivers, so 42 observed
        // with the counter still at 0 did not come from the re-read.
        SimServer(tmp.resolve("pki-lost")).use { server ->
            UascProxy(server.port).use { proxy ->
                val conn = proxied(server, proxy)
                val counter = server.nodeId("Counter")
                val seen = CopyOnWriteArrayList<QualifiedValue>()
                val arrivedBeforeResync = AtomicBoolean(false)
                conn.watch(counter, 100.0) { sample ->
                    if (sample.value == 42 && sample.quality.code == QualityCode.Good && conn.notificationResyncs == 0) {
                        arrivedBeforeResync.set(true)
                    }
                    seen += sample
                }
                conn.watch(server.nodeId("Flag"), 100.0) { }
                conn.connect()
                conn.flushWatches()
                await("initial good") { seen.any { it.quality.code == QualityCode.Good && it.value == 0 } }
                await("items created") { conn.monitoredItemCount == 2 }
                val forwarded = proxy.forwardedDataPublishes
                server.setCounter(1)
                await("subscription caught up") {
                    seen.any { it.quality.code == QualityCode.Good && it.value == 1 } &&
                        proxy.forwardedDataPublishes > forwarded
                }
                proxy.dropNextDataPublish = true
                proxy.faultRepublish = true
                server.setCounter(42)
                await("publish dropped") { proxy.droppedPublishes == 1 }
                server.setFlag(true) // a later NotificationMessage reveals the gap
                await("re-read after lost data") {
                    conn.notificationResyncs >= 1 &&
                        seen.any { it.quality.code == QualityCode.Good && it.value == 42 }
                }
                assertFalse(arrivedBeforeResync.get(), "42 arrived with no re-read; a lost notification was left Good")
                assertTrue(proxy.faultedRepublishes >= 1, "republish faulted=${proxy.faultedRepublishes}")
                assertTrue(conn.notificationResyncs >= 1, "resyncs=${conn.notificationResyncs}")
                assertEquals(ConnectionState.CONNECTED, conn.connectionStatus().state, "the channel stayed up")
                conn.close()
            }
        }
    }

    @Test fun subscriptionWatchdogReadsTheValuesAgain() {
        // review-2 F6: no PublishResponse reaches the client (data or keep-alive) while the
        // channel stays up. Milo's watchdog elapses; the client reads every member again.
        SimServer(tmp.resolve("pki-watchdog")).use { server ->
            UascProxy(server.port).use { proxy ->
                val conn = proxied(server, proxy)
                val counter = server.nodeId("Counter")
                val seen = CopyOnWriteArrayList<QualifiedValue>()
                conn.watch(counter, 100.0) { seen += it }
                conn.connect()
                conn.flushWatches()
                await("initial good") { seen.any { it.quality.code == QualityCode.Good && it.value == 0 } }
                await("items created") { conn.monitoredItemCount == 1 }
                proxy.dropAllPublishes = true
                server.setCounter(77)
                await("re-read after watchdog") {
                    seen.any { it.quality.code == QualityCode.Good && it.value == 77 }
                }
                assertTrue(proxy.droppedPublishes >= 1)
                assertTrue(conn.notificationResyncs >= 1, "resyncs=${conn.notificationResyncs}")
                proxy.dropAllPublishes = false
                conn.close()
            }
        }
    }

    @Test fun failedMonitoredItemSyncIsRetriedUntilSynchronized() {
        // review-2 F12: the first CreateMonitoredItems fails at the service level. The item must
        // be created by a retry, and the count must include only items the server created.
        SimServer(tmp.resolve("pki-resync")).use { server ->
            UascProxy(server.port).use { proxy ->
                proxy.faultCreateMonitoredItems = 1
                val conn = proxied(server, proxy)
                val counter = server.nodeId("Counter")
                val seen = CopyOnWriteArrayList<QualifiedValue>()
                conn.watch(counter, 100.0) { seen += it }
                conn.connect()
                conn.flushWatches()
                await("create faulted") { proxy.faultedCreates == 1 }
                await("retried until synchronized") { conn.monitoredItemCount == 1 }
                server.setCounter(5)
                await("change delivered by the subscription") {
                    seen.any { it.quality.code == QualityCode.Good && it.value == 5 }
                }
                conn.close()
            }
        }
    }

    @Test fun monitoredItemCountExcludesItemsTheServerRefused() {
        SimServer(tmp.resolve("pki-refused")).use { server ->
            val conn = connection(server)
            val seen = CopyOnWriteArrayList<QualifiedValue>()
            conn.watch(server.nodeId("Counter"), 100.0) { }
            conn.watch(server.nodeId("DoesNotExist"), 100.0) { seen += it }
            conn.connect()
            conn.flushWatches()
            await("refusal reported") { seen.any { it.quality.statusCode == 0x80340000L } }
            await("one item on the server") { conn.monitoredItemCount == 1 }
            Thread.sleep(500)
            assertEquals(1, conn.monitoredItemCount)
            conn.close()
        }
    }

    private fun proxied(server: SimServer, proxy: UascProxy) = connection(
        server,
        endpointUrl = "opc.tcp://127.0.0.1:${proxy.port}/oip",
        connectHost = "127.0.0.1",
        connectPort = proxy.port,
        keepAliveIntervalMs = 2_000,
        keepAliveTimeoutMs = 2_000,
        keepAliveFailuresAllowed = 3,
    )

    @Test fun throughputAgainstTheInProcessServer() {
        SimServer(tmp.resolve("pki-bench"), bulk = 200).use { server ->
            val conn = connection(server, requestTimeoutMs = 10_000)
            conn.connect()
            val ids = (0 until 200).map { server.nodeId("N$it") }
            val warm = conn.read(ids)
            assertEquals(200, warm.size)
            assertEquals(0, warm.first().value)
            assertEquals(199, warm.last().value)
            val start = System.nanoTime()
            var samples = 0L
            var calls = 0
            val deadline = start + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val batch = conn.read(ids)
                assertEquals(200, batch.size)
                assertEquals(QualityCode.Good, batch.first().quality.code)
                assertEquals(QualityCode.Good, batch.last().quality.code)
                samples += batch.size
                calls++
            }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            val rate = samples / seconds
            println("BENCH opcua in-process values/s=${"%.0f".format(rate)} calls=$calls seconds=${"%.2f".format(seconds)}")
            assertTrue(rate >= 1_000.0, "throughput $rate values/s")
            conn.close()
        }
    }

    @Test fun moduleInstallStartStopUninstallLeavesNoResources() {
        val id = "org.oip.driver-opcua-client"
        val manifest = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n")
        val modules = tmp.resolve("modules")
        modules.resolve("driver-opcua-client").createDirectories().resolve("module.yaml").writeText(manifest)
        val config = tmp.resolve("config")
        val store = FileConfigStore(config, SchemaRegistry())
        val host = ModuleHost(modules, config, store = store)
        host.installAll()
        assertEquals(ModuleState.INSTALLED, host.state(id))
        assertEquals(5000, store.list(module = id).single().resource.spec["requestTimeoutMs"])
        host.start(id)
        val module = host.instance(id) as OpcUaClientModule
        assertEquals(ModuleState.RUNNING, host.state(id))
        host.stop(id)
        host.uninstall(id)
        assertTrue(host.installed().isEmpty())
        assertEquals(0, store.list(module = id).size)
        assertFalse(config.resolve(id).exists())
        assertTrue(module.connection("none") == null)
    }

    private fun connection(
        server: SimServer,
        endpointUrl: String = server.endpointUrl,
        auth: OpcUaAuth = OpcUaAuth.Anonymous,
        policy: OpcUaSecurityPolicy = OpcUaSecurityPolicy.NONE,
        mode: OpcUaSecurityMode = OpcUaSecurityMode.NONE,
        keyPair: KeyPair? = null,
        certificate: X509Certificate? = null,
        pkiDir: Path? = null,
        trustAllServerCertificates: Boolean = false,
        connectHost: String? = null,
        connectPort: Int = -1,
        requestTimeoutMs: Long = 5_000,
        keepAliveIntervalMs: Long = 5_000,
        keepAliveTimeoutMs: Long = 5_000,
        keepAliveFailuresAllowed: Int = 1,
    ) = OpcUaConnection(OpcUaConnectionConfig(
        name = "sim",
        endpointUrl = endpointUrl,
        securityPolicy = policy,
        securityMode = mode,
        auth = auth,
        requestTimeoutMs = requestTimeoutMs,
        keepAliveIntervalMs = keepAliveIntervalMs,
        keepAliveTimeoutMs = keepAliveTimeoutMs,
        keepAliveFailuresAllowed = keepAliveFailuresAllowed,
        connectHost = connectHost,
        connectPort = connectPort,
        keyPair = keyPair,
        certificate = certificate,
        pkiDir = pkiDir,
        trustAllServerCertificates = trustAllServerCertificates,
        sessionTimeoutMs = 60_000,
    ))

    private fun trustServer(pki: Path, certificate: X509Certificate) {
        val manager = FileBasedTrustListManager.createAndInitialize(pki)
        try {
            manager.addTrustedCertificate(certificate)
        } finally {
            manager.close()
        }
    }

    private fun await(message: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }
}

/**
 * Milo server bound to 127.0.0.1. None and Basic256Sha256/SignAndEncrypt, with anonymous,
 * username (user/password1) and X.509 user tokens.
 */
class SimServer(private val securityDir: Path, private val bulk: Int = 0) : AutoCloseable {
    val port: Int = ServerSocket(0).use { it.localPort }
    val endpointUrl: String = "opc.tcp://127.0.0.1:$port/oip"
    val trust = MemoryTrustListManager()
    lateinit var certificate: X509Certificate
        private set
    lateinit var counter: UaVariableNode
        private set
    private lateinit var flag: UaVariableNode
    private lateinit var keys: OpcUaCertificate
    private lateinit var store: KeyStoreCertificateStore
    private lateinit var namespace: SimNamespace
    private lateinit var server: OpcUaServer
    private var index: Int = 0

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        securityDir.createDirectories()
        keys = OpcUaCertificates.generate(APP_URI, "OIP Sim Server")
        certificate = keys.certificate
        boot()
    }

    /** Stop the endpoint and start it again on the same port. Subscriptions from the old process are gone. */
    fun restart() {
        stopRuntime()
        boot()
    }

    /** Close every session. [deleteSubscriptions] false leaves them available for TransferSubscriptions. */
    fun closeSessions(deleteSubscriptions: Boolean) {
        server.sessionManager.allSessions.toList().forEach { session ->
            server.sessionManager.killSession(session.sessionId, deleteSubscriptions)
        }
    }

    private fun boot() {
        store = KeyStoreCertificateStore.createAndInitialize(KeyStoreCertificateStore.Settings(
            securityDir.resolve("server.pfx"),
            { "password".toCharArray() },
            { "password".toCharArray() },
        ))
        val quarantine = FileBasedCertificateQuarantine.create(securityDir.resolve("rejected"))
        val factory = object : RsaSha256CertificateFactory() {
            override fun createRsaSha256KeyPair(): KeyPair = keys.keyPair
            override fun createRsaSha256CertificateChain(keyPair: KeyPair): Array<X509Certificate> = arrayOf(keys.certificate)
        }
        val validator = DefaultServerCertificateValidator(trust, quarantine)
        val group = DefaultApplicationGroup.createAndInitialize(trust, store, factory, validator)
        val certificateManager = DefaultCertificateManager(quarantine, group)
        val username = UsernameIdentityValidator { challenge ->
            challenge.username == "user" && challenge.password == "password1"
        }
        val endpoints = endpoints(port, keys.certificate)
        val config = OpcUaServerConfig.builder()
            .setApplicationUri(APP_URI)
            .setApplicationName(LocalizedText.english("ERIC OPC UA Simulator"))
            .setProductUri("urn:oip:sim")
            .setEndpoints(endpoints)
            .setBuildInfo(BuildInfo("urn:oip:sim", "oip", "oip sim", OpcUaServer.SDK_VERSION, "", DateTime.now()))
            .setCertificateManager(certificateManager)
            .setIdentityValidator(CompositeValidator(
                AnonymousIdentityValidator.INSTANCE,
                username,
                X509IdentityValidator { true },
            ))
            .build()
        server = OpcUaServer(config) {
            OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build())
        }
        namespace = SimNamespace(server, bulk)
        namespace.startup()
        counter = namespace.counter
        flag = namespace.flag
        index = namespace.namespaceIndex.toInt()
        server.startup().get()
    }

    fun nodeId(name: String): String = "ns=$index;s=Sim/$name"

    fun trust(certificate: X509Certificate) {
        trust.addTrustedCertificate(certificate)
    }

    fun setCounter(value: Int) {
        counter.setValue(DataValue(Variant(value), StatusCode.GOOD, DateTime.now()))
    }

    fun setFlag(value: Boolean) {
        flag.setValue(DataValue(Variant(value), StatusCode.GOOD, DateTime.now()))
    }

    override fun close() = stopRuntime()

    private fun stopRuntime() {
        if (::namespace.isInitialized) runCatching { namespace.shutdown() }
        if (::server.isInitialized) runCatching { server.shutdown().get(15, TimeUnit.SECONDS) }
        if (::store.isInitialized) runCatching { store.close() }
    }

    private fun endpoints(port: Int, certificate: X509Certificate): Set<EndpointConfig> {
        val tokens = arrayOf(
            OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS,
            OpcUaServerConfig.USER_TOKEN_POLICY_USERNAME,
            OpcUaServerConfig.USER_TOKEN_POLICY_X509,
        )
        fun build(path: String, policy: SecurityPolicy, mode: MessageSecurityMode) =
            EndpointConfig.newBuilder()
                .setBindAddress("127.0.0.1")
                .setHostname("127.0.0.1")
                .setBindPort(port)
                .setPath(path)
                .setCertificate(certificate)
                .setSecurityPolicy(policy)
                .setSecurityMode(mode)
                .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                .addTokenPolicies(*tokens)
                .build()
        return linkedSetOf(
            build("/oip", SecurityPolicy.None, MessageSecurityMode.None),
            build("/oip", SecurityPolicy.Basic256Sha256, MessageSecurityMode.SignAndEncrypt),
            build("/oip/discovery", SecurityPolicy.None, MessageSecurityMode.None),
        )
    }

    companion object {
        const val APP_URI = "urn:oip:sim:server"
    }
}

private class SimNamespace(server: OpcUaServer, private val bulk: Int) : ManagedNamespaceWithLifecycle(server, "urn:oip:sim") {
    lateinit var counter: UaVariableNode
    lateinit var flag: UaVariableNode
    private val subscriptionModel = SubscriptionModel(server, this)

    init {
        lifecycleManager.addLifecycle(subscriptionModel)
        lifecycleManager.addLifecycle(object : Lifecycle {
            override fun startup() { buildNodes() }
            override fun shutdown() {}
        })
    }

    override fun onDataItemsCreated(dataItems: List<DataItem>) = subscriptionModel.onDataItemsCreated(dataItems)
    override fun onDataItemsModified(dataItems: List<DataItem>) = subscriptionModel.onDataItemsModified(dataItems)
    override fun onDataItemsDeleted(dataItems: List<DataItem>) = subscriptionModel.onDataItemsDeleted(dataItems)
    override fun onMonitoringModeChanged(monitoredItems: List<MonitoredItem>) = subscriptionModel.onMonitoringModeChanged(monitoredItems)

    private fun buildNodes() {
        val folder = UaFolderNode(nodeContext, newNodeId("Sim"), newQualifiedName("Sim"), LocalizedText.english("Sim"))
        nodeManager.addNode(folder)
        val objects = server.addressSpaceManager.getManagedNode(NodeIds.ObjectsFolder).orElseThrow()
        objects.addReference(Reference(objects.nodeId, NodeIds.Organizes, folder.nodeId.expanded(), true))
        counter = variable(folder, "Counter", NodeIds.Int32, 0, writable = true)
        variable(folder, "Temperature", NodeIds.Double, 20.0, writable = true)
        flag = variable(folder, "Flag", NodeIds.Boolean, false, writable = true)
        variable(folder, "Static", NodeIds.Int32, 1, writable = false)
        for (i in 0 until bulk) variable(folder, "N$i", NodeIds.Int32, i, writable = true)
        addMethod(folder)
    }

    /** `Sim/Add(a: Int32, b: Int32) -> sum: Int32`, for the method-call test (SCR-021). */
    private fun addMethod(folder: UaFolderNode) {
        val node = UaMethodNode.builder(nodeContext)
            .setNodeId(newNodeId("Sim/Add"))
            .setBrowseName(newQualifiedName("Add"))
            .setDisplayName(LocalizedText.english("Add"))
            .setExecutable(true)
            .setUserExecutable(true)
            .build()
        val inputs = arrayOf(
            Argument("a", NodeIds.Int32, -1, null, LocalizedText.english("a")),
            Argument("b", NodeIds.Int32, -1, null, LocalizedText.english("b")),
        )
        val outputs = arrayOf(Argument("sum", NodeIds.Int32, -1, null, LocalizedText.english("sum")))
        node.inputArguments = inputs
        node.outputArguments = outputs
        node.invocationHandler = object : AbstractMethodInvocationHandler(node) {
            override fun getInputArguments(): Array<Argument> = inputs
            override fun getOutputArguments(): Array<Argument> = outputs

            override fun invoke(context: InvocationContext, inputValues: Array<Variant>): Array<Variant> {
                val a = inputValues[0].value as Int
                val b = inputValues[1].value as Int
                return arrayOf(Variant(a + b))
            }
        }
        nodeManager.addNode(node)
        folder.addReference(Reference(folder.nodeId, NodeIds.HasComponent, node.nodeId.expanded(), true))
        node.addReference(Reference(node.nodeId, NodeIds.HasComponent, folder.nodeId.expanded(), false))
    }

    private fun variable(folder: UaFolderNode, name: String, dataType: NodeId, initial: Any, writable: Boolean): UaVariableNode {
        val access = if (writable) {
            arrayOf(AccessLevel.CurrentRead, AccessLevel.CurrentWrite)
        } else {
            arrayOf(AccessLevel.CurrentRead)
        }
        val node = UaVariableNode.builder(nodeContext)
            .setNodeId(newNodeId("Sim/$name"))
            .setBrowseName(newQualifiedName(name))
            .setDisplayName(LocalizedText.english(name))
            .setDataType(dataType)
            .setAccessLevel(*access)
            .setUserAccessLevel(*access)
            .setValue(DataValue(Variant(initial), StatusCode.GOOD, DateTime.now()))
            .buildAndAdd()
        folder.addOrganizes(node)
        return node
    }
}

/** Accepts TCP and bridges it upstream until [drop] closes the live sockets. */
class TcpProxy(private val upstreamPort: Int) : AutoCloseable {
    private val listen = ServerSocket(0).apply { reuseAddress = true }
    val port: Int = listen.localPort
    @Volatile private var running = true
    @Volatile private var dropping = false
    private val live = CopyOnWriteArrayList<Socket>()
    private val acceptThread = Thread({
        while (running) {
            val down = try {
                listen.accept()
            } catch (e: Exception) {
                if (!running) break else continue
            }
            if (dropping) {
                runCatching { down.close() }
                continue
            }
            try {
                bridge(down)
            } catch (e: Exception) {
                runCatching { down.close() }
            }
        }
    }, "opcua-proxy").apply { isDaemon = true; start() }

    private fun bridge(down: Socket) {
        val up = Socket()
        up.tcpNoDelay = true
        down.tcpNoDelay = true
        up.connect(InetSocketAddress("127.0.0.1", upstreamPort), 3_000)
        live += down
        live += up
        pump(down, up)
        pump(up, down)
    }

    private fun pump(from: Socket, to: Socket) {
        Thread({
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buf = ByteArray(8_192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }, "opcua-proxy-io").apply { isDaemon = true; start() }
    }

    fun drop() {
        dropping = true
        live.forEach { runCatching { it.close() } }
        live.clear()
    }

    fun resume() { dropping = false }

    override fun close() {
        running = false
        dropping = true
        live.forEach { runCatching { it.close() } }
        runCatching { listen.close() }
        acceptThread.join(1_000)
    }
}

/**
 * SecurityPolicy None proxy that reads the UA Secure Conversation chunks from the server (Part 6
 * §6.7.2) so a test can drop or replace one response while the channel stays valid. When a chunk
 * is dropped, later chunk sequence numbers are renumbered so the client sees no channel gap.
 * Only single-chunk messages are changed.
 */
class UascProxy(private val upstreamPort: Int) : AutoCloseable {
    private val listen = ServerSocket(0).apply { reuseAddress = true }
    val port: Int = listen.localPort
    @Volatile private var running = true
    private val live = CopyOnWriteArrayList<Socket>()

    /** Drop the next PublishResponse that carries notification data. */
    @Volatile var dropNextDataPublish = false
    /** Drop every PublishResponse, data and keep-alive. */
    @Volatile var dropAllPublishes = false
    /** Answer Republish with Bad_MessageNotAvailable. */
    @Volatile var faultRepublish = false
    /** Answer this many CreateMonitoredItems calls with Bad_ServerTooBusy. */
    @Volatile var faultCreateMonitoredItems = 0
    @Volatile var droppedPublishes = 0
        private set
    /** PublishResponses with notification data that were forwarded to the client. */
    @Volatile var forwardedDataPublishes = 0
        private set
    @Volatile var faultedRepublishes = 0
        private set
    @Volatile var faultedCreates = 0
        private set

    private val acceptThread = Thread({
        while (running) {
            val down = try { listen.accept() } catch (e: Exception) { if (!running) break else continue }
            try {
                val up = Socket()
                up.tcpNoDelay = true
                down.tcpNoDelay = true
                up.connect(InetSocketAddress("127.0.0.1", upstreamPort), 3_000)
                live += down
                live += up
                thread(down, up) { input, output ->
                    val buf = ByteArray(8_192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        output.flush()
                    }
                }
                thread(up, down) { input, output -> serverToClient(input, output) }
            } catch (e: Exception) {
                runCatching { down.close() }
            }
        }
    }, "uasc-proxy").apply { isDaemon = true; start() }

    private fun thread(from: Socket, to: Socket, body: (java.io.InputStream, java.io.OutputStream) -> Unit) {
        Thread({
            try {
                body(from.getInputStream(), to.getOutputStream())
            } catch (_: Exception) {
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }, "uasc-proxy-io").apply { isDaemon = true; start() }
    }

    private fun serverToClient(input: java.io.InputStream, output: java.io.OutputStream) {
        var removed = 0L
        val data = java.io.DataInputStream(input)
        while (true) {
            val header = ByteArray(8)
            data.readFully(header)
            val size = le32(header, 4).toInt()
            val chunk = header + ByteArray(size - 8).also { data.readFully(it) }
            val type = String(chunk, 0, 3, Charsets.US_ASCII)
            if (type != "MSG" && type != "OPN") {
                output.write(chunk)
                output.flush()
                continue
            }
            val seqAt = if (type == "MSG") 16 else asymEnd(chunk)
            var out: ByteArray? = chunk
            if (type == "MSG" && chunk[3] == 'F'.code.toByte()) {
                val body = chunk.copyOfRange(seqAt + 8, chunk.size)
                val replaced = transform(body)
                out = when {
                    replaced == null -> null
                    replaced === body -> chunk
                    else -> chunk.copyOfRange(0, seqAt + 8) + replaced
                }
            }
            if (out == null) {
                removed++
                continue
            }
            if (removed != 0L) {
                val seq = (le32(out, seqAt) - removed) and 0xFFFFFFFFL
                putLe32(out, seqAt, seq)
            }
            putLe32(out, 4, out.size.toLong())
            output.write(out)
            output.flush()
        }
    }

    /** null drops the message; the same array forwards it; another array replaces the body. */
    private fun transform(body: ByteArray): ByteArray? {
        if (body.size < 4 || body[0] != 1.toByte() || body[1] != 0.toByte()) return body
        val typeId = (body[2].toInt() and 0xFF) or ((body[3].toInt() and 0xFF) shl 8)
        when (typeId) {
            PUBLISH_RESPONSE -> {
                val data = notificationCount(body) > 0
                if (dropAllPublishes) {
                    droppedPublishes++
                    return null
                }
                if (data && dropNextDataPublish) {
                    dropNextDataPublish = false
                    droppedPublishes++
                    return null
                }
                if (data) forwardedDataPublishes++
            }
            REPUBLISH_RESPONSE -> if (faultRepublish) {
                faultedRepublishes++
                return fault(body, 0x807B0000L)
            }
            CREATE_MONITORED_ITEMS_RESPONSE -> if (faultCreateMonitoredItems > 0) {
                faultCreateMonitoredItems--
                faultedCreates++
                return fault(body, 0x80BC0000L)
            }
        }
        return body
    }

    /** NotificationData entries in a PublishResponse, or -1 when the header is not the simple form. */
    private fun notificationCount(body: ByteArray): Int {
        var at = 4 + 8 + 4 + 4
        if (body[at].toInt() != 0) return -1 // diagnostic info
        at += 1
        val strings = le32(body, at).toInt()
        if (strings > 0) return -1
        at += 4
        if (body[at].toInt() != 0 || body[at + 1].toInt() != 0 || body[at + 2].toInt() != 0) return -1
        at += 3 // null additional header
        at += 4 // subscription id
        val available = le32(body, at).toInt()
        at += 4 + 4 * maxOf(0, available)
        at += 1 // moreNotifications
        at += 4 + 8 // sequence number, publish time
        return le32(body, at).toInt()
    }

    /** A ServiceFault with the original timestamp and request handle (Part 4 §7.33). */
    private fun fault(body: ByteArray, status: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, (SERVICE_FAULT and 0xFF).toByte(), (SERVICE_FAULT shr 8).toByte()))
        out.write(body, 4, 12) // timestamp and request handle
        val code = ByteArray(4).also { putLe32(it, 0, status) }
        out.write(code)
        out.write(byteArrayOf(0, -1, -1, -1, -1, 0, 0, 0))
        return out.toByteArray()
    }

    private fun asymEnd(chunk: ByteArray): Int {
        var at = 12
        repeat(3) {
            val n = le32(chunk, at).toInt()
            at += 4 + maxOf(0, n)
        }
        return at
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v shr (8 * i)).toByte()
    }

    override fun close() {
        running = false
        live.forEach { runCatching { it.close() } }
        runCatching { listen.close() }
        acceptThread.join(1_000)
    }

    private companion object {
        // Binary encoding ids, namespace 0 (OPC UA NodeSet).
        const val SERVICE_FAULT = 397
        const val CREATE_MONITORED_ITEMS_RESPONSE = 754
        const val PUBLISH_RESPONSE = 829
        const val REPUBLISH_RESPONSE = 835
    }
}
