package org.oip.gateway

import org.eclipse.milo.opcua.sdk.client.DiscoveryClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransport
import org.eclipse.milo.opcua.stack.transport.client.tcp.OpcTcpClientTransportConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.opcua.server.OpcUaCertificateTrust
import org.oip.security.DefaultRoles
import java.nio.file.Path
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Client-certificate trust for the gateway OPC UA server, through the REST API.
 */
class OpcUaTrustApiTest {
    @TempDir lateinit var tmp: Path

    private class ClientIdentity(val keys: KeyPair, val certificate: X509Certificate, val uri: String) {
        val thumbprint: String get() = OpcUaCertificateTrust.thumbprintOf(certificate)
        val pem: String get() = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) + "\n-----END CERTIFICATE-----\n"
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun untrustedClientIsQuarantinedTrustedViaRestThenRejectedAgainAfterRemoval() {
        val gw = gateway(tmp.resolve("trust"))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.use {
            it.start()
            val http = Http(it.port())
            it.loginAdmin(http)
            val client = identity("urn:oip:test:trust-client", "Trust Test Client")

            assertFalse(connects(it, client), "an untrusted certificate must be refused")
            val listed = http.get("/api/v1/opcua/certificates")
            assertEquals(200, listed.status, listed.body)
            assertEquals(true, listed.json["serverRunning"])
            val quarantined = certs(listed, "quarantined")
            val entry = quarantined.single { c -> c["thumbprint"] == client.thumbprint }
            assertTrue((entry["subject"] as String).contains("Trust Test Client"), entry.toString())
            assertEquals("Trust Test Client", entry["commonName"])
            assertEquals(client.uri, entry["applicationUri"])
            assertEquals(false, entry["expired"])
            assertTrue((entry["notAfter"] as String).contains("T"))
            assertTrue(certs(listed, "trusted").isEmpty())

            val trusted = http.post("/api/v1/opcua/certificates/quarantined/${client.thumbprint}/trust", "")
            assertEquals(200, trusted.status, trusted.body)
            assertEquals(client.thumbprint, trusted.json["thumbprint"])
            val after = http.get("/api/v1/opcua/certificates")
            assertTrue(certs(after, "trusted").any { c -> c["thumbprint"] == client.thumbprint })
            assertTrue(certs(after, "quarantined").none { c -> c["thumbprint"] == client.thumbprint })
            assertTrue(connects(it, client), "a trusted certificate must connect without a restart")
            assertEquals(1L, it.auditCount(OPCUA_CERT_TRUSTED_ACTION, actor = "admin"))

            assertEquals(204, http.delete("/api/v1/opcua/certificates/trusted/${client.thumbprint}").status)
            assertEquals(1L, it.auditCount(OPCUA_CERT_REMOVED_ACTION, actor = "admin"))
            assertTrue(certs(http.get("/api/v1/opcua/certificates"), "trusted").isEmpty())
            assertFalse(connects(it, client), "a removed certificate must be refused again")
            val again = http.get("/api/v1/opcua/certificates")
            assertTrue(certs(again, "quarantined").any { c -> c["thumbprint"] == client.thumbprint })

            assertEquals(204, http.delete("/api/v1/opcua/certificates/quarantined/${client.thumbprint}").status)
            assertTrue(certs(http.get("/api/v1/opcua/certificates"), "quarantined").isEmpty())
            assertEquals(1L, it.auditCount(OPCUA_CERT_QUARANTINE_DELETED_ACTION, actor = "admin"))
            assertEquals(404, http.delete("/api/v1/opcua/certificates/quarantined/${client.thumbprint}").status)
            assertEquals(404, http.post("/api/v1/opcua/certificates/quarantined/${client.thumbprint}/trust", "").status)
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun importedCertificateConnectsBeforeItWasEverQuarantined() {
        val gw = gateway(tmp.resolve("import"))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        gw.use {
            it.start()
            val http = Http(it.port())
            it.loginAdmin(http)
            val client = identity("urn:oip:test:import-client", "Imported Client")
            val imported = http.post("/api/v1/opcua/certificates/trusted", Json.write(mapOf("certificate" to client.pem)))
            assertEquals(201, imported.status, imported.body)
            assertEquals(client.thumbprint, imported.json["thumbprint"])
            assertEquals(1L, it.auditCount(OPCUA_CERT_IMPORTED_ACTION, actor = "admin"))
            assertTrue(connects(it, client))
            assertTrue(certs(http.get("/api/v1/opcua/certificates"), "quarantined").isEmpty())
        }
    }

    @Test
    fun trustChangesNeedGatewayWriteAndBadInputIsRejected() {
        // The server is off here: the trust list is still managed on disk.
        val dir = tmp.resolve("perm")
        dir.toFile().mkdirs()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        Gateway(dir, port = 0).use { g ->
            g.start()
            assertFalse(dir.resolve("opcua").resolve("pki").exists(), "no pki directory before the trust API is used")
            g.createUser("olly", setOf(DefaultRoles.OPERATOR))
            g.createUser("vic", setOf(DefaultRoles.VIEWER))
            val admin = Http(g.port()).also { g.loginAdmin(it) }
            val operator = Http(g.port()).also { assertEquals(200, it.login("olly", TEST_PASSWORD).status) }
            val viewer = Http(g.port()).also { assertEquals(200, it.login("vic", TEST_PASSWORD).status) }
            val client = identity("urn:oip:test:perm-client", "Perm Client")
            val pem = Json.write(mapOf("certificate" to client.pem))

            val off = operator.get("/api/v1/opcua/certificates")
            assertEquals(200, off.status, off.body)
            assertEquals(false, off.json["serverEnabled"])
            assertEquals(false, off.json["serverRunning"])
            assertEquals(403, viewer.get("/api/v1/opcua/certificates").status)
            assertEquals(403, operator.post("/api/v1/opcua/certificates/trusted", pem).status)
            assertEquals(403, operator.delete("/api/v1/opcua/certificates/trusted/${client.thumbprint}").status)
            assertEquals(403, operator.post("/api/v1/opcua/certificates/quarantined/${client.thumbprint}/trust", "").status)
            assertEquals(403, operator.delete("/api/v1/opcua/certificates/quarantined/${client.thumbprint}").status)
            assertEquals(401, Http(g.port()).get("/api/v1/opcua/certificates").status)

            assertEquals(201, admin.post("/api/v1/opcua/certificates/trusted", pem).status)
            // Import is idempotent.
            assertEquals(201, admin.post("/api/v1/opcua/certificates/trusted", Json.write(mapOf(
                "certificate" to Base64.getEncoder().encodeToString(client.certificate.encoded),
            ))).status)
            assertEquals(1, certs(operator.get("/api/v1/opcua/certificates"), "trusted").size)
            assertEquals(422, admin.post("/api/v1/opcua/certificates/trusted", """{"certificate":"not a certificate"}""").status)
            assertEquals(422, admin.post("/api/v1/opcua/certificates/trusted",
                """{"certificate":"-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"}""").status)
            assertEquals(400, admin.post("/api/v1/opcua/certificates/trusted", """{}""").status)
            assertEquals(400, admin.delete("/api/v1/opcua/certificates/trusted/xyz").status)
            assertEquals(404, admin.delete("/api/v1/opcua/certificates/trusted/${"0".repeat(40)}").status)
            assertEquals(204, admin.delete("/api/v1/opcua/certificates/trusted/${client.thumbprint.uppercase()}").status)
            assertTrue(certs(admin.get("/api/v1/opcua/certificates"), "trusted").isEmpty())
        }
    }

    private fun certs(res: Http.Res, key: String): List<Map<*, *>> {
        @Suppress("UNCHECKED_CAST")
        return res.json[key] as List<Map<*, *>>
    }

    private fun identity(uri: String, commonName: String): ClientIdentity {
        val keys = SelfSignedCertificateGenerator.generateRsaKeyPair(2048)
        val certificate = SelfSignedCertificateBuilder(keys)
            .setCommonName(commonName)
            .setOrganization("OIP test")
            .setApplicationUri(uri)
            .addDnsName("localhost")
            .build()
        return ClientIdentity(keys, certificate, uri)
    }

    /** Open a Basic256Sha256 / SignAndEncrypt session with [client]'s certificate. */
    private fun connects(gw: Gateway, client: ClientIdentity): Boolean {
        val server = gw.opcUa!!
        val endpoints = DiscoveryClient.getEndpoints(server.endpointUrl).get(15, TimeUnit.SECONDS)
        val secure = endpoints.first { ep: EndpointDescription ->
            ep.securityPolicyUri == SecurityPolicy.Basic256Sha256.uri &&
                ep.securityMode == MessageSecurityMode.SignAndEncrypt &&
                !ep.endpointUrl.contains("discovery")
        }
        val config = OpcUaClientConfig.builder()
            .setEndpoint(EndpointUtil.updateUrl(secure, "127.0.0.1", server.boundPort))
            .setApplicationName(LocalizedText.english("oip-trust-test"))
            .setApplicationUri(client.uri)
            .setProductUri("urn:oip:test")
            .setKeyPair(client.keys)
            .setCertificate(client.certificate)
            .setCertificateChain(arrayOf(client.certificate))
            .setCertificateValidator(CertificateValidator.InsecureCertificateValidator())
            .setRequestTimeout(uint(10_000))
            .setIdentityProvider(UsernameProvider("ada", TEST_PASSWORD))
            .build()
        val ua = OpcUaClient(config, OpcTcpClientTransport(OpcTcpClientTransportConfig.newBuilder().build()))
        return try {
            ua.connect()
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { ua.disconnect() }
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
}
