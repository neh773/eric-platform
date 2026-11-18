package org.oip.gateway

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.backup.ZipIo
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.io.DataInputStream
import java.io.StringReader
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * GAT-031 / GAT-032 / GAT-014: CSR with the key kept on the gateway, installing a CA reply, TLS protocol
 * and cipher policy, saved web-server settings, and the certificate surviving backup, wipe, and restore.
 */
class HttpsCsrTlsTest {
    @TempDir lateinit var tmp: Path

    @Test fun csrSignedByTestCaInstallsAndTheHandshakePresentsThatChain() {
        writeYaml()
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            val bodies = ArrayList<String>()
            assertEquals(404, http.get("/api/v1/https/csr").status)

            val created = http.post(
                "/api/v1/https/csr",
                Json.write(mapOf(
                    "commonName" to "gw.example.test",
                    "organization" to "Test Plant",
                    "country" to "us",
                    "sans" to listOf("gw.example.test", "IP:10.1.2.3"),
                )),
            )
            assertEquals(200, created.status, created.body)
            bodies += created.body
            val csrPem = created.json["csrPem"] as String
            assertTrue(csrPem.startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
            assertEquals(listOf("DNS:gw.example.test", "IP:10.1.2.3"), created.json["sans"])
            assertTrue(PrivateFiles.ownerOnly(tmp.resolve("https/pending.key")))
            val pendingMarker = keyMarker(tmp.resolve("https/pending.key").readText())
            val download = http.get("/api/v1/https/csr?download=true")
            assertEquals(200, download.status)
            assertEquals(csrPem, download.body)
            bodies += download.body
            bodies += http.get("/api/v1/https").body

            val ca = TestCa()
            val request = PEMParser(StringReader(csrPem)).use { it.readObject() as PKCS10CertificationRequest }
            val leaf = ca.issue(request)
            // A reply that does not belong to the pending key is refused and leaves the live certificate alone.
            val before = gateway.https.fingerprint()
            val stranger = ca.issueFor(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())
            val wrong = http.post("/api/v1/https/csr/install", Json.write(mapOf("certificatePem" to pem(stranger, ca.intermediate))))
            assertEquals(422, wrong.status, wrong.body)
            assertEquals(before, gateway.https.fingerprint())
            assertEquals(1L, gateway.auditCount(HTTPS_CSR_INSTALLED_ACTION, status = AuditStatus.FAILURE))

            // CA replies often arrive root-first or unordered.
            val installed = http.post(
                "/api/v1/https/csr/install",
                Json.write(mapOf("certificatePem" to pem(ca.root, leaf, ca.intermediate))),
            )
            assertEquals(200, installed.status, installed.body)
            bodies += installed.body
            val fingerprint = TlsCertificates.fingerprint(leaf)
            assertEquals(fingerprint, (installed.json["certificate"] as Map<*, *>)["sha256Fingerprint"])
            assertEquals("csr", (installed.json["certificate"] as Map<*, *>)["source"])
            val session = handshake(gateway.httpsListenPort()!!, ca.root)
            val presented = session.peerCertificates.map { it as X509Certificate }
            assertArrayEquals(leaf.encoded, presented[0].encoded)
            assertArrayEquals(ca.intermediate.encoded, presented[1].encoded)
            assertFalse(tmp.resolve("https/pending.key").exists())
            assertEquals(404, http.get("/api/v1/https/csr").status)
            assertEquals(1L, gateway.auditCount(HTTPS_CSR_CREATED_ACTION, status = AuditStatus.SUCCESS))
            assertEquals(1L, gateway.auditCount(HTTPS_CSR_INSTALLED_ACTION, status = AuditStatus.SUCCESS))
            assertEquals(1L, gateway.auditCount(HTTPS_CERTIFICATE_ACTION))

            val liveMarker = keyMarker(tmp.resolve("https/server.key").readText())
            assertEquals(pendingMarker, liveMarker, "the installed key is the one generated for the request")
            val audit = gateway.security.audit.query(AuditQuery(limit = 200)).records
            for (text in bodies + audit.map { it.detail } + tmp.resolve("logs/gateway.jsonl").readText()) {
                assertFalse(text.contains("PRIVATE KEY"), "private key in a response, audit row, or log")
                assertFalse(text.contains(pendingMarker), "private key bytes in a response, audit row, or log")
            }
        }
    }

    @Test fun cipherOutsideTheListFailsAndTls11IsRefused() {
        writeYaml()
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            val port = gateway.httpsListenPort()!!
            val cert = readCert(tmp)

            // Default policy: TLS 1.1 is already off on the listener.
            assertRefusesTls11(port)

            val floor = http.put("/api/v1/https/tls", Json.write(mapOf("protocols" to listOf("TLSv1.1", "TLSv1.2"))))
            assertEquals(422, floor.status, floor.body)
            assertTrue(floor.body.contains("floor"), floor.body)
            val weak = http.put(
                "/api/v1/https/tls",
                Json.write(mapOf("protocols" to listOf("TLSv1.2"), "cipherSuites" to listOf("TLS_RSA_WITH_AES_128_CBC_SHA"))),
            )
            assertEquals(422, weak.status, weak.body)
            assertTrue(weak.body.contains("weak"), weak.body)
            val mismatched = http.put(
                "/api/v1/https/tls",
                Json.write(mapOf("protocols" to listOf("TLSv1.3"), "cipherSuites" to listOf(ALLOWED))),
            )
            assertEquals(422, mismatched.status, mismatched.body)
            assertEquals(3L, gateway.auditCount(HTTPS_TLS_ACTION, status = AuditStatus.FAILURE))

            val ok = http.put(
                "/api/v1/https/tls",
                Json.write(mapOf("protocols" to listOf("TLSv1.2"), "cipherSuites" to listOf(ALLOWED))),
            )
            assertEquals(200, ok.status, ok.body)
            assertEquals(listOf("TLSv1.2"), ok.json["protocols"])
            assertEquals(listOf(ALLOWED), ok.json["cipherSuites"])
            assertEquals(1L, gateway.auditCount(HTTPS_TLS_ACTION, status = AuditStatus.SUCCESS))
            assertFalse((ok.json["availableCipherSuites"] as List<*>).any { TlsPolicy.weak(it as String) })

            // Applied to the running listener without a restart.
            val good = handshake(port, cert, protocols = arrayOf("TLSv1.2"), suites = arrayOf(ALLOWED))
            assertEquals(ALLOWED, good.cipherSuite)
            assertThrows(SSLHandshakeException::class.java) {
                handshake(port, cert, protocols = arrayOf("TLSv1.2"), suites = arrayOf(OUTSIDE))
            }
            assertThrows(SSLHandshakeException::class.java) {
                handshake(port, cert, protocols = arrayOf("TLSv1.3"), suites = arrayOf("TLS_AES_128_GCM_SHA256"))
            }
            assertRefusesTls11(port)
        }
        Gateway(tmp, port = 0).use { again ->
            again.start()
            val port = again.httpsListenPort()!!
            assertThrows(SSLHandshakeException::class.java) {
                handshake(port, readCert(tmp), protocols = arrayOf("TLSv1.2"), suites = arrayOf(OUTSIDE))
            }
            assertEquals(ALLOWED, handshake(port, readCert(tmp), protocols = arrayOf("TLSv1.2"), suites = arrayOf(ALLOWED)).cipherSuite)
        }
    }

    @Test fun mutatingRoutesNeedGatewayWriteAndAreAudited() {
        writeYaml()
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            gateway.createUser("oli", setOf(DefaultRoles.OPERATOR))
            gateway.createUser("vera", setOf(DefaultRoles.VIEWER))
            val operator = Http(gateway.port())
            operator.login("oli", TEST_PASSWORD)
            assertEquals(200, operator.get("/api/v1/https").status)
            assertEquals(403, operator.post("/api/v1/https/csr", Json.write(mapOf("commonName" to "x.example.test"))).status)
            assertEquals(403, operator.post("/api/v1/https/csr/install", Json.write(mapOf("certificatePem" to "x"))).status)
            assertEquals(403, operator.delete("/api/v1/https/csr").status)
            assertEquals(403, operator.put("/api/v1/https/tls", Json.write(mapOf("protocols" to listOf("TLSv1.3")))).status)
            assertEquals(403, operator.put("/api/v1/https/settings", Json.write(mapOf("publicAddress" to "a.example.test"))).status)
            assertEquals(403, operator.put("/api/v1/https/certificate", Json.write(mapOf("format" to "pem", "certificatePem" to "x", "privateKeyPem" to "y"))).status)
            assertEquals(403, operator.put("/api/v1/https/tls/", Json.write(mapOf("protocols" to listOf("TLSv1.3")))).status, "trailing slash")
            assertFalse(tmp.resolve("https/pending.key").exists())
            for (action in listOf(HTTPS_CERTIFICATE_ACTION, HTTPS_CSR_CREATED_ACTION, HTTPS_CSR_INSTALLED_ACTION, HTTPS_CSR_DISCARDED_ACTION, HTTPS_SETTINGS_ACTION)) {
                assertEquals(1L, gateway.auditCount(action, actor = "oli", status = AuditStatus.FAILURE), action)
            }
            assertEquals(2L, gateway.auditCount(HTTPS_TLS_ACTION, actor = "oli", status = AuditStatus.FAILURE))
            val denial = gateway.security.audit.query(AuditQuery(action = HTTPS_CSR_CREATED_ACTION, limit = 5)).records.single()
            assertEquals("denied: ${Permissions.GATEWAY_WRITE}", denial.detail)
            assertEquals("https/pending.csr", denial.target)
            assertTrue(denial.actorHost.isNotEmpty(), "the denial records the caller's address")
            val viewer = Http(gateway.port())
            viewer.login("vera", TEST_PASSWORD)
            assertEquals(403, viewer.get("/api/v1/https/csr").status)

            // An API key without gateway.write is refused too; one with it is allowed.
            val reader = gateway.security.apiKeys.create("reader", setOf(Permissions.GATEWAY_READ), secureConnectionRequired = false, actor = "test")
            val keyClient = Http(gateway.port()).also { it.authorizeBearer(reader.token) }
            assertEquals(403, keyClient.post("/api/v1/https/csr", Json.write(mapOf("commonName" to "x.example.test"))).status)
            assertEquals(1L, gateway.auditCount(HTTPS_CSR_CREATED_ACTION, actor = "reader", status = AuditStatus.FAILURE))
            assertEquals(200, keyClient.get("/api/v1/https").status)
            val writer = gateway.security.apiKeys.create("writer", setOf(Permissions.GATEWAY_WRITE), secureConnectionRequired = false, actor = "test")
            val writerClient = Http(gateway.port()).also { it.authorizeBearer(writer.token) }
            val made = writerClient.post("/api/v1/https/csr", Json.write(mapOf("commonName" to "x.example.test")))
            assertEquals(200, made.status, made.body)
            val dropped = writerClient.delete("/api/v1/https/csr")
            assertEquals(200, dropped.status, dropped.body)
            assertEquals(1L, gateway.auditCount(HTTPS_CSR_DISCARDED_ACTION, actor = "writer", status = AuditStatus.SUCCESS))
        }
    }

    @Test fun csrCountryMustBeExactlyTwoLetters() {
        writeYaml()
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            for (bad in listOf("USA", "U", "u1", "ß", "ıt", "U S", 42)) {
                val refused = http.post("/api/v1/https/csr", Json.write(mapOf("commonName" to "gw.example.test", "country" to bad)))
                assertEquals(422, refused.status, "country $bad: ${refused.body}")
                assertFalse(tmp.resolve("https/pending.key").exists(), "country $bad")
            }
            assertEquals(7L, gateway.auditCount(HTTPS_CSR_CREATED_ACTION, status = AuditStatus.FAILURE))
            val made = http.post("/api/v1/https/csr", Json.write(mapOf("commonName" to "gw.example.test", "country" to " de ")))
            assertEquals(200, made.status, made.body)
            val request = PEMParser(StringReader(made.json["csrPem"] as String)).use { it.readObject() as PKCS10CertificationRequest }
            assertTrue(request.subject.toString().contains("C=DE"), request.subject.toString())
        }
    }

    @Test fun malformedIpv6SanIsRefusedAndAudited() {
        writeYaml()
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            for (san in listOf("IP:1:2:3", "1:2:3", "IP:::::::::1")) {
                val refused = http.post("/api/v1/https/csr", Json.write(mapOf("commonName" to "gw.example.test", "sans" to listOf(san))))
                assertEquals(422, refused.status, "$san: ${refused.body}")
            }
            assertEquals(3L, gateway.auditCount(HTTPS_CSR_CREATED_ACTION, status = AuditStatus.FAILURE))
            assertFalse(tmp.resolve("https/pending.key").exists())
            val ok = http.post("/api/v1/https/csr", Json.write(mapOf("commonName" to "gw.example.test", "sans" to listOf("IP:fd00::1"))))
            assertEquals(200, ok.status, ok.body)
        }
    }

    @Test fun settingsAreValidatedAndSavedToGatewayYaml() {
        writeYaml()
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            val yamlBefore = tmp.resolve("gateway.yaml").readText()
            val bad = http.put("/api/v1/https/settings", Json.write(mapOf("publicAddress" to "https://gw.example.test/")))
            assertEquals(422, bad.status, bad.body)
            val redirectWithoutHttps = http.put(
                "/api/v1/https/settings",
                Json.write(mapOf("httpsEnabled" to false, "forceHttpsRedirect" to true)),
            )
            assertEquals(422, redirectWithoutHttps.status, redirectWithoutHttps.body)
            val unknown = http.put("/api/v1/https/settings", Json.write(mapOf("certificate" to "/etc/x")))
            assertEquals(422, unknown.status, unknown.body)
            assertEquals(yamlBefore, tmp.resolve("gateway.yaml").readText())

            val saved = http.put(
                "/api/v1/https/settings",
                Json.write(mapOf("publicAddress" to "gw2.example.test", "forceHttpsRedirect" to true, "publicHttpsPort" to 443)),
            )
            assertEquals(200, saved.status, saved.body)
            val savedView = saved.json["savedSettings"] as Map<*, *>
            assertEquals("gw2.example.test", savedView["publicAddress"])
            assertEquals(true, savedView["restartRequired"])
            assertEquals(false, saved.json["forceHttpsRedirect"], "the running listener keeps its settings until restart")
            val reread = GatewaySettings.load(tmp.resolve("gateway.yaml"), emptyMap())
            assertEquals("gw2.example.test", reread.publicAddress)
            assertTrue(reread.forceHttpsRedirect)
            assertEquals(443, reread.publicHttpsPort)
            assertEquals(false, reread.opcua.enabled, "other gateway.yaml sections are kept")
            assertEquals(1L, gateway.auditCount(HTTPS_SETTINGS_ACTION, status = AuditStatus.SUCCESS))
        }
    }

    @Test fun backupWipeRestoreKeepsTheFingerprintOnTheNextHandshake() {
        writeYaml()
        var archive = ByteArray(0)
        var fingerprint = ""
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            fingerprint = TlsCertificates.fingerprint(readCert(tmp))
            val marker = keyMarker(tmp.resolve("https/server.key").readText())
            val backup = http.createBackup()
            assertEquals(200, backup.status, backup.body)
            archive = backup.raw
            val entries = ZipIo.read(openBackup(archive))
            assertTrue(entries.containsKey("sealed/https/server.key"))
            assertTrue(entries.containsKey("certs/https/server.crt"))
            for (bytes in entries.values + listOf(archive)) {
                val text = String(bytes, Charsets.ISO_8859_1)
                assertFalse(text.contains("PRIVATE KEY"))
                assertFalse(text.contains(marker))
            }
            gateway.backups.wipe()
            assertFalse(tmp.resolve("https").exists())
        }
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val fresh = TlsCertificates.fingerprint(readCert(tmp))
            assertNotEquals(fingerprint, fresh, "a wiped gateway mints a new certificate")
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            val restored = http.restoreBackup(archive)
            assertEquals(200, restored.status, restored.body)
            assertEquals(fingerprint, TlsCertificates.fingerprint(readCert(tmp)))
            val live = handshake(gateway.httpsListenPort()!!, readCert(tmp))
            assertEquals(fingerprint, TlsCertificates.fingerprint(live.peerCertificates[0] as X509Certificate))
        }
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val again = handshake(gateway.httpsListenPort()!!, readCert(tmp))
            assertEquals(fingerprint, TlsCertificates.fingerprint(again.peerCertificates[0] as X509Certificate))
        }
    }

    /**
     * An archive with certificates but no sealed keys (made without a secrets key, or stripped) must not
     * delete the live key: the restore keeps `https/`, and the next start still serves HTTPS.
     */
    @Test fun certificateOnlyRestoreKeepsHttpsWorkingAfterRestart() {
        writeYaml()
        var fingerprint = ""
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            fingerprint = TlsCertificates.fingerprint(readCert(tmp))
            val backup = http.createBackup()
            assertEquals(200, backup.status, backup.body)
            val stripped = ZipIo.read(openBackup(backup.raw)).filterKeys { !it.startsWith("sealed/") }
            assertTrue(stripped.containsKey("certs/https/server.crt"))
            val restored = http.postRaw("/api/v1/backups/restore", ZipIo.write(stripped.toList()), "application/zip")
            assertEquals(200, restored.status, restored.body)
            assertTrue(tmp.resolve("https/server.key").exists(), "the live key is kept")
        }
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            assertNotNull(gateway.httpsListenPort(), "HTTPS is up after the restart")
            val port = gateway.httpsListenPort()!!
            val seen = handshake(port, readCert(tmp))
            assertEquals(fingerprint, TlsCertificates.fingerprint(seen.peerCertificates[0] as X509Certificate))
        }
    }

    /**
     * A backup from gateway A restored into gateway B's new, empty data directory: B presents A's HTTPS
     * certificate and reads A's stored secret. Both depend on the archive carrying the real gateway secrets
     * key (`secrets/gateway.key`), which opens the sealed web key and the secret store.
     */
    @Test fun backupFromOneGatewayRestoresHttpsAndSecretsIntoAnEmptyDataDir() {
        val dirA = tmp.resolve("a")
        val dirB = tmp.resolve("b")
        writeYaml(dirA)
        var archive = ByteArray(0)
        var fingerprintA = ""
        var keyA = ByteArray(0)
        Gateway(dirA, port = 0).use { a ->
            a.start()
            a.security.secrets.putText("plc-password", "s3cret-from-a")
            fingerprintA = TlsCertificates.fingerprint(readCert(dirA))
            val seen = handshake(a.httpsListenPort()!!, readCert(dirA))
            assertEquals(fingerprintA, TlsCertificates.fingerprint(seen.peerCertificates[0] as X509Certificate))
            keyA = Files.readAllBytes(dirA.resolve("secrets/gateway.key"))
            val http = Http(a.port())
            a.loginAdmin(http)
            val backup = http.createBackup()
            assertEquals(200, backup.status, backup.body)
            archive = backup.raw
        }
        val entries = ZipIo.read(openBackup(archive))
        assertTrue(entries.containsKey("sealed/https/server.key"))
        assertArrayEquals(keyA, entries.getValue("secrets.key"), "the archive carries the real key, secrets/gateway.key")

        assertFalse(dirB.exists())
        writeYaml(dirB)
        Gateway(dirB, port = 0).use { b ->
            b.start()
            assertNotEquals(fingerprintA, TlsCertificates.fingerprint(readCert(dirB)), "B starts with its own certificate")
            val http = Http(b.port())
            b.loginAdmin(http)
            val restored = http.restoreBackup(archive)
            assertEquals(200, restored.status, restored.body)
        }
        assertArrayEquals(keyA, Files.readAllBytes(dirB.resolve("secrets/gateway.key")), "B has A's secrets key")
        assertFalse(dirB.resolve("secrets.key").exists())
        Gateway(dirB, port = 0).use { b ->
            b.start()
            val presented = handshake(b.httpsListenPort()!!, readCert(dirB))
            assertEquals(fingerprintA, TlsCertificates.fingerprint(presented.peerCertificates[0] as X509Certificate))
            assertEquals("s3cret-from-a", b.security.secrets.getText("plc-password"))
        }
    }

    /**
     * Send a hand-built TLS 1.1 ClientHello. The JDK client refuses to offer TLS 1.1 itself, so this is the
     * only way to see the server's answer. A refusal is an alert record (type 21) or a closed socket, never
     * a ServerHello (handshake record, type 22).
     */
    private fun assertRefusesTls11(port: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write(tls11ClientHello())
            socket.getOutputStream().flush()
            val input = DataInputStream(socket.getInputStream())
            val first = runCatching { input.read() }.getOrDefault(-1)
            assertNotEquals(22, first, "server answered a TLS 1.1 hello with a handshake record")
            if (first == 21) {
                input.readUnsignedShort()
                input.readUnsignedShort()
                assertEquals(2, input.read(), "fatal alert")
                val description = input.read()
                assertTrue(description == 70 || description == 40, "alert $description")
            }
        }
    }

    private fun tls11ClientHello(): ByteArray {
        val suites = intArrayOf(0xC013, 0xC014, 0x002F, 0x0035)
        val body = java.io.ByteArrayOutputStream()
        body.write(byteArrayOf(0x03, 0x02))
        body.write(ByteArray(32).also { SecureRandom().nextBytes(it) })
        body.write(0)
        body.write(byteArrayOf(0, (suites.size * 2).toByte()))
        for (s in suites) body.write(byteArrayOf((s shr 8).toByte(), s.toByte()))
        body.write(byteArrayOf(1, 0))
        val hello = body.toByteArray()
        val handshake = byteArrayOf(1, 0, (hello.size shr 8).toByte(), hello.size.toByte()) + hello
        return byteArrayOf(22, 0x03, 0x02, (handshake.size shr 8).toByte(), handshake.size.toByte()) + handshake
    }

    private fun writeYaml(dir: Path = tmp) {
        Files.createDirectories(dir)
        dir.resolve("gateway.yaml").writeText(
            """
            profile: standard
            http:
              host: 127.0.0.1
              https: true
              httpsPort: 0
            opcua:
              enabled: false
            gatewayNetwork:
              enabled: false
            """.trimIndent() + "\n",
        )
    }

    private fun readCert(dir: Path): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificates(dir.resolve("https/server.crt").toFile().inputStream())
            .first() as X509Certificate

    private fun keyMarker(pem: String): String = pem.lineSequence().first { it.length > 40 && !it.startsWith("-") }

    private fun pem(vararg certs: X509Certificate): String = TlsCertificates.certificatePem(certs.toList())

    private fun handshake(
        port: Int,
        trust: X509Certificate,
        protocols: Array<String>? = null,
        suites: Array<String>? = null,
    ): javax.net.ssl.SSLSession {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(trust)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.none { it.encoded.contentEquals(trust.encoded) }) throw java.security.cert.CertificateException("untrusted")
            }
        }
        val context = SSLContext.getInstance("TLS").also { it.init(null, arrayOf<TrustManager>(manager), SecureRandom()) }
        val socket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.use {
            it.soTimeout = 5_000
            protocols?.let { p -> it.enabledProtocols = p }
            suites?.let { s -> it.enabledCipherSuites = s }
            it.startHandshake()
            return it.session
        }
    }

    /** A two-level test CA: a self-signed root and an intermediate that signs leaves. */
    private class TestCa {
        private val rootKeys = rsa()
        private val intermediateKeys = rsa()
        val root: X509Certificate = build(X500Name("CN=OIP Test Root"), X500Name("CN=OIP Test Root"), rootKeys.public, rootKeys.private, ca = true)
        val intermediate: X509Certificate =
            build(X500Name("CN=OIP Test Intermediate"), X500Name("CN=OIP Test Root"), intermediateKeys.public, rootKeys.private, ca = true)

        fun issue(request: PKCS10CertificationRequest): X509Certificate {
            val jca = JcaPKCS10CertificationRequest(request)
            val ext = request.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest).firstOrNull()
                ?.let { Extensions.getInstance(it.attrValues.getObjectAt(0)) }
            return build(request.subject, X500Name("CN=OIP Test Intermediate"), jca.publicKey, intermediateKeys.private, ca = false, requested = ext)
        }

        fun issueFor(keys: KeyPair): X509Certificate =
            build(X500Name("CN=stranger"), X500Name("CN=OIP Test Intermediate"), keys.public, intermediateKeys.private, ca = false)

        private fun build(
            subject: X500Name,
            issuer: X500Name,
            key: java.security.PublicKey,
            signer: PrivateKey,
            ca: Boolean,
            requested: Extensions? = null,
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                issuer, BigInteger(64, SecureRandom()).add(BigInteger.ONE), Date(now - 60_000), Date(now + 86_400_000L), subject, key,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            if (ca) builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            requested?.getExtension(Extension.subjectAlternativeName)?.let { builder.addExtension(it) }
            requested?.getExtension(Extension.keyUsage)?.let { builder.addExtension(it) }
            requested?.getExtension(Extension.extendedKeyUsage)?.let { builder.addExtension(it) }
            val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(signer)
            return JcaX509CertificateConverter().getCertificate(builder.build(contentSigner))
        }

        private companion object {
            fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        }
    }

    private companion object {
        const val ALLOWED = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
        const val OUTSIDE = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
    }
}
