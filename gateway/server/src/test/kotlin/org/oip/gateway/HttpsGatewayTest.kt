package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.AuditQuery
import org.oip.security.DefaultRoles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * TLS integration for the gateway web server.
 */
class HttpsGatewayTest {
    @TempDir lateinit var tmp: Path

    @Test fun selfSignedHandshakeReusesTheCertAndLocksTheKey() {
        writeYaml(
            """
            https: true
            httpsPort: 0
            publicAddress: gateway.example.test
            """.trimIndent(),
        )
        Gateway(tmp, port = 0).use { first ->
            first.start()
            val port = first.httpsListenPort()!!
            assertNotEquals(first.port(), port)
            val certificate = readCert(tmp)
            val session = handshake(port, certificate)
            assertTrue(session.protocol == "TLSv1.2" || session.protocol == "TLSv1.3", session.protocol)
            assertEquals(listOf("TLSv1.2", "TLSv1.3"), tlsProtocols(first))
            val labels = TlsCertificates.sanLabels(certificate)
            assertTrue(labels.contains("DNS:localhost"), labels.toString())
            assertTrue(labels.contains("IP:127.0.0.1"), labels.toString())
            assertTrue(labels.contains("DNS:gateway.example.test"), labels.toString())
            assertTrue(PrivateFiles.ownerOnly(tmp.resolve("https/server.key")))
            val marker = keyMarker(tmp)
            val health = tlsSend(port, certificate, "GET", "/api/v1/health", null)
            assertEquals(200, health.statusCode())
            assertNoSecret(health.body(), marker)
            assertNoSecret(tmp.resolve("logs/gateway.jsonl").readText(), marker)
            assertNoSecret(tmp.resolve("gateway.yaml").readText(), marker)
            first.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val login = tlsSend(
                port, certificate, "POST", "/api/v1/auth/login",
                """{"username":"admin","password":"$TEST_PASSWORD"}""",
            )
            assertEquals(200, login.statusCode(), login.body())
            val cookie = login.headers().allValues("set-cookie").joinToString("\n")
            assertTrue(cookie.contains("Secure"), cookie)
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie)
            val fingerprint = TlsCertificates.fingerprint(certificate)
            val bytes = Files.readAllBytes(tmp.resolve("https/server.crt"))
            first.close()
            Gateway(tmp, port = 0).use { second ->
                second.start()
                assertEquals(fingerprint, TlsCertificates.fingerprint(readCert(tmp)))
                assertTrue(bytes.contentEquals(Files.readAllBytes(tmp.resolve("https/server.crt"))))
                val again = handshake(second.httpsListenPort()!!, readCert(tmp))
                assertEquals(fingerprint, TlsCertificates.fingerprint(again.peerCertificates[0] as X509Certificate))
            }
        }
    }

    @Test fun httpStaysUpUnlessRedirectIsForced() {
        writeYaml("https: true\nhttpsPort: 0\npublicAddress: gateway.example.test\n")
        Gateway(tmp, port = 0).use { open ->
            open.start()
            val http = Http(open.port())
            val health = http.get("/api/v1/health")
            assertEquals(200, health.status, health.body)
            assertEquals("UP", health.json["status"])
            val tls = tlsSend(open.httpsListenPort()!!, readCert(tmp), "GET", "/api/v1/health", null)
            assertEquals(200, tls.statusCode())
        }
        writeYaml(
            """
            https: true
            httpsPort: 0
            publicAddress: gateway.example.test
            forceHttpsRedirect: true
            """.trimIndent(),
        )
        Gateway(tmp, port = 0).use { forced ->
            forced.start()
            val httpsPort = forced.httpsListenPort()!!
            val http = Http(forced.port())
            val health = http.get("/api/v1/health?ready=1")
            assertEquals(308, health.status, health.body)
            assertEquals("https://gateway.example.test:$httpsPort/api/v1/health?ready=1", health.header("Location"))
            val hidden = http.get("/api/v1/diagnostics/memory")
            assertEquals(308, hidden.status, hidden.body)
            assertTrue(hidden.header("Location")!!.endsWith("/api/v1/diagnostics/memory"))
            val tls = tlsSend(httpsPort, readCert(tmp), "GET", "/api/v1/health", null)
            assertEquals(200, tls.statusCode(), tls.body())
        }
    }

    @Test fun plainHttpDoesNotListenOnTheHttpsPort() {
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            assertFalse(gateway.settings.httpsEnabled)
            assertNull(gateway.httpsListenPort())
            assertEquals(1, gateway.app.jettyServer().server().connectors.size)
            assertFalse(tmp.resolve("https/server.key").toFile().exists())
        }
    }

    @Test fun uploadReplacesTheLiveCertificateAndAMismatchDoesNot() {
        writeYaml("https: true\nhttpsPort: 0\npublicAddress: gateway.example.test\n")
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            gateway.loginAdmin(Http(gateway.port()))
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            val original = readCert(tmp)
            val marker = keyMarker(tmp)
            assertEquals(401, Http(gateway.port()).get("/api/v1/https").status)
            val other = TlsCertificates.generate(listOf("other.example.test"))
            val mismatch = http.put(
                "/api/v1/https/certificate",
                Json.write(mapOf(
                    "format" to "pem",
                    "certificatePem" to TlsCertificates.certificatePem(listOf(original)),
                    "privateKeyPem" to TlsCertificates.privateKeyPem(other.privateKey),
                )),
            )
            assertEquals(422, mismatch.status, mismatch.body)
            assertFalse(mismatch.body.contains(keyMarker(other)))
            assertFalse(mismatch.body.contains("BEGIN PRIVATE KEY"))
            assertEquals(TlsCertificates.fingerprint(original), TlsCertificates.fingerprint(readCert(tmp)))
            assertEquals(0, gateway.auditCount(HTTPS_CERTIFICATE_ACTION))
            val replacement = TlsCertificates.generate(listOf("upload.example.test"))
            val uploaded = http.put(
                "/api/v1/https/certificate",
                Json.write(mapOf(
                    "format" to "pem",
                    "certificatePem" to TlsCertificates.certificatePem(replacement.chain),
                    "privateKeyPem" to TlsCertificates.privateKeyPem(replacement.privateKey),
                )),
            )
            assertEquals(200, uploaded.status, uploaded.body)
            assertEquals(replacement.fingerprint(), uploaded.json["certificate"].let { (it as Map<*, *>)["sha256Fingerprint"] })
            assertNoSecret(uploaded.body, keyMarker(replacement))
            assertNoSecret(tmp.resolve("logs/gateway.jsonl").readText(), keyMarker(replacement))
            assertNoSecret(tmp.resolve("gateway.yaml").readText(), marker)
            val seen = handshake(gateway.httpsListenPort()!!, readCert(tmp))
            assertEquals(replacement.fingerprint(), TlsCertificates.fingerprint(seen.peerCertificates[0] as X509Certificate))
            assertEquals(1, gateway.auditCount(HTTPS_CERTIFICATE_ACTION))
            val audit = gateway.security.audit.query(AuditQuery(action = HTTPS_CERTIFICATE_ACTION, limit = 5))
            assertFalse(audit.records[0].detail.contains("PRIVATE"))
            assertTrue(audit.records[0].detail.contains(replacement.fingerprint()))
            val password = "pkcs12-secret"
            val encoded = java.io.ByteArrayOutputStream().use { out ->
                val third = TlsCertificates.generate(listOf("pkcs.example.test"))
                third.toKeyStore(password.toCharArray()).store(out, password.toCharArray())
                out.toByteArray() to third.fingerprint()
            }
            val pkcs = http.put(
                "/api/v1/https/certificate",
                Json.write(mapOf(
                    "format" to "pkcs12",
                    "pkcs12Base64" to Base64.getEncoder().encodeToString(encoded.first),
                    "password" to password,
                )),
            )
            assertEquals(200, pkcs.status, pkcs.body)
            assertEquals(encoded.second, (pkcs.json["certificate"] as Map<*, *>)["sha256Fingerprint"])
            assertFalse(pkcs.body.contains(password))
            assertNoSecret(tmp.resolve("logs/gateway.jsonl").readText(), password)
            val after = handshake(gateway.httpsListenPort()!!, readCert(tmp))
            assertEquals(encoded.second, TlsCertificates.fingerprint(after.peerCertificates[0] as X509Certificate))
        }
    }

    @Test fun fileDropAndConfiguredPemReplaceTheCertificate() {
        writeYaml("https: true\nhttpsPort: 0\n")
        val dropped = TlsCertificates.generate(listOf("drop.example.test"))
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val incoming = tmp.resolve("https/incoming")
            Files.createDirectories(incoming)
            incoming.resolve("certificate.pem").writeText(TlsCertificates.certificatePem(dropped.chain))
            incoming.resolve("private-key.pem").writeText(TlsCertificates.privateKeyPem(dropped.privateKey))
            val droppedKey = incoming.resolve("private-key.pem").toFile()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && (gateway.https.fingerprint() != dropped.fingerprint() || droppedKey.exists())) {
                Thread.sleep(100)
            }
            assertEquals(dropped.fingerprint(), gateway.https.fingerprint())
            val seen = handshake(gateway.httpsListenPort()!!, readCert(tmp))
            assertEquals(dropped.fingerprint(), TlsCertificates.fingerprint(seen.peerCertificates[0] as X509Certificate))
            assertFalse(droppedKey.exists())
            assertNoSecret(tmp.resolve("logs/gateway.jsonl").readText(), keyMarker(dropped))
        }
        val external = TlsCertificates.generate(listOf("configured.example.test"))
        val certPath = tmp.resolve("external.crt")
        val keyPath = tmp.resolve("external.key")
        certPath.writeText(TlsCertificates.certificatePem(external.chain))
        keyPath.writeText(TlsCertificates.privateKeyPem(external.privateKey))
        tmp.resolve("gateway.yaml").writeText(
            """
            profile: standard
            http:
              host: 127.0.0.1
              https: true
              httpsPort: 0
              certificate: ${certPath.toAbsolutePath()}
              privateKey: ${keyPath.toAbsolutePath()}
            """.trimIndent() + "\n",
        )
        Gateway(tmp, port = 0).use { configured ->
            configured.start()
            assertEquals(external.fingerprint(), TlsCertificates.fingerprint(readCert(tmp)))
            assertTrue(PrivateFiles.ownerOnly(tmp.resolve("https/server.key")))
            assertNoSecret(tmp.resolve("gateway.yaml").readText(), keyMarker(external))
            val configDir = tmp.resolve("config")
            if (configDir.toFile().exists()) {
                Files.walk(configDir).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.forEach { file ->
                        assertNoSecret(Files.readString(file), keyMarker(external))
                    }
                }
            }
        }
    }

    @Test fun rolesGateTheCertificateAndEdgeStaysOnLoopback() {
        writeYaml("https: true\nhttpsPort: 0\n")
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            gateway.createUser("oli", setOf(DefaultRoles.OPERATOR))
            gateway.createUser("vera", setOf(DefaultRoles.VIEWER))
            val operator = Http(gateway.port())
            operator.login("oli", TEST_PASSWORD)
            assertEquals(200, operator.get("/api/v1/https").status)
            val denied = operator.put(
                "/api/v1/https/certificate",
                Json.write(mapOf("format" to "pem", "certificatePem" to "x", "privateKeyPem" to "y")),
            )
            assertEquals(403, denied.status, denied.body)
            val viewer = Http(gateway.port())
            viewer.login("vera", TEST_PASSWORD)
            assertEquals(403, viewer.get("/api/v1/https").status)
        }
        val edgeDir = tmp.resolve("edge")
        Files.createDirectories(edgeDir)
        edgeDir.resolve("gateway.yaml").writeText(
            """
            profile: edge
            http:
              https: true
              httpsPort: 0
              publicAddress: edge.example.test
            """.trimIndent() + "\n",
        )
        Gateway(edgeDir, port = 0).use { edge ->
            edge.start()
            assertEquals("127.0.0.1", edge.settings.host)
            handshake(edge.httpsListenPort()!!, readCert(edgeDir))
            assertTrue(TlsCertificates.sanLabels(readCert(edgeDir)).contains("DNS:edge.example.test"))
        }
    }

    private fun writeYaml(httpBody: String) {
        val indented = httpBody.trimIndent().trim().lines().joinToString("\n") { "  $it" }
        tmp.resolve("gateway.yaml").writeText("profile: standard\nhttp:\n  host: 127.0.0.1\n$indented\n")
    }

    private fun readCert(dir: Path): X509Certificate =
        dir.resolve("https/server.crt").toFile().inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun keyMarker(dir: Path): String = keyMarker(dir.resolve("https/server.key").readText())

    private fun keyMarker(material: TlsMaterial): String = keyMarker(TlsCertificates.privateKeyPem(material.privateKey))

    private fun keyMarker(pem: String): String =
        pem.lineSequence().first { it.length > 40 && !it.startsWith("-") }

    private fun assertNoSecret(text: String, marker: String) {
        assertFalse(text.contains("BEGIN PRIVATE KEY"), "private key was written")
        assertFalse(text.contains(marker), "private key bytes were written")
    }

    private fun tlsProtocols(gateway: Gateway): List<String> =
        gateway.app.jettyServer().server().connectors
            .filterIsInstance<org.eclipse.jetty.server.ServerConnector>()
            .flatMap { it.connectionFactories }
            .filterIsInstance<org.eclipse.jetty.server.SslConnectionFactory>()
            .single().sslContextFactory.includeProtocols.toList()

    private fun handshake(port: Int, trust: X509Certificate): javax.net.ssl.SSLSession {
        val context = sslContext(trust)
        val socket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.use {
            it.startHandshake()
            return it.session
        }
    }

    private fun tlsSend(port: Int, trust: X509Certificate, method: String, path: String, body: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port$path"))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (body != null) request.header("Content-Type", "application/json")
        return HttpClient.newBuilder().sslContext(sslContext(trust)).followRedirects(HttpClient.Redirect.NEVER).build()
            .send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun sslContext(trust: X509Certificate): SSLContext {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(trust)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (!chain[0].encoded.contentEquals(trust.encoded)) throw java.security.cert.CertificateException("unexpected certificate")
            }
        }
        return SSLContext.getInstance("TLS").also { it.init(null, arrayOf<TrustManager>(manager), SecureRandom()) }
    }
}
