package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import kotlin.io.path.readText
import kotlin.io.path.writeText

class HttpsMaterialTest {
    @TempDir lateinit var tmp: Path

    @Test fun generatedCertHasLocalSansAndTheKeyIsOwnerOnly() {
        val material = TlsCertificates.generate(listOf("gateway.example.test", "10.1.2.3"))
        val labels = TlsCertificates.sanLabels(material.leaf)
        assertTrue(labels.contains("DNS:localhost"), labels.toString())
        assertTrue(labels.contains("IP:127.0.0.1"), labels.toString())
        assertTrue(labels.contains("DNS:gateway.example.test"), labels.toString())
        assertTrue(labels.contains("IP:10.1.2.3"), labels.toString())
        val dir = tmp.resolve("https")
        PrivateFiles.directory(dir)
        PrivateFiles.writeKey(dir.resolve("server.key"), TlsCertificates.privateKeyPem(material.privateKey).toByteArray())
        assertTrue(PrivateFiles.ownerOnly(dir.resolve("server.key")))
        val view = TlsCertificates.view(material, "generated")
        val rendered = Json.write(view)
        assertFalse(rendered.contains("PRIVATE"))
        assertFalse(rendered.contains(keyMarker(material)))
        assertEquals("generated", view["source"])
    }

    @Test fun pemAndPkcs12RoundTripAndAMismatchIsRejected() {
        val material = TlsCertificates.generate(emptyList())
        val parsed = TlsCertificates.parsePem(
            TlsCertificates.certificatePem(material.chain),
            TlsCertificates.privateKeyPem(material.privateKey),
        )
        assertEquals(material.fingerprint(), parsed.fingerprint())
        val password = "pkcs-secret".toCharArray()
        val encoded = java.io.ByteArrayOutputStream().use { out ->
            material.toKeyStore(password).store(out, password)
            out.toByteArray()
        }
        val fromStore = TlsCertificates.parsePkcs12(encoded, password)
        assertEquals(material.fingerprint(), fromStore.fingerprint())
        password.fill('\u0000')
        val other = TlsCertificates.generate(emptyList())
        val rejected = assertThrows<IllegalArgumentException> {
            TlsCertificates.parsePem(TlsCertificates.certificatePem(material.chain), TlsCertificates.privateKeyPem(other.privateKey))
        }
        assertEquals("private key does not match the certificate", rejected.message)
        assertFalse(rejected.message!!.contains(keyMarker(other)))
        val marker = "NOT-A-KEY-TOKEN-e2f5"
        val broken = assertThrows<IllegalArgumentException> {
            TlsCertificates.parsePem("-----BEGIN CERTIFICATE-----\n$marker\n-----END CERTIFICATE-----\n", "not a key $marker")
        }
        assertFalse(broken.message!!.contains(marker))
    }

    @Test fun pkcs12WithTheWrongPasswordDoesNotEchoIt() {
        val material = TlsCertificates.generate(emptyList())
        val encoded = java.io.ByteArrayOutputStream().use { out ->
            material.toKeyStore("correct-secret".toCharArray()).store(out, "correct-secret".toCharArray())
            out.toByteArray()
        }
        val rejected = assertThrows<IllegalArgumentException> {
            TlsCertificates.parsePkcs12(encoded, "wrong-secret".toCharArray())
        }
        assertFalse(rejected.message!!.contains("wrong-secret"))
        assertFalse(rejected.message!!.contains("correct-secret"))
    }

    @Test fun redirectUsesThePublicAddressAndOmitsPort443() {
        assertEquals(
            "https://gateway.example.test/api/v1/health?ready=1",
            HttpsRedirect.url("gateway.example.test", 443, 8043, "evil.example", "127.0.0.1", "/api/v1/health", "ready=1"),
        )
        assertEquals(
            "https://127.0.0.1:8443/api/v1/health",
            HttpsRedirect.url(null, null, 8443, "not a host", "127.0.0.1", "/api/v1/health", null),
        )
        assertNull(HttpsRedirect.requestHostName("https://evil.example/path"))
    }

    @Test fun httpsSettingsLoadFromYamlAndEnv() {
        val file = tmp.resolve("gateway.yaml")
        file.writeText(
            """
            profile: standard
            http:
              port: 8090
              https: true
              httpsPort: 8443
              publicAddress: gateway.example.test
              publicHttpsPort: 443
              forceHttpsRedirect: true
              certificate: /tmp/cert.pem
              privateKey: /tmp/key.pem
            """.trimIndent() + "\n",
        )
        val settings = GatewaySettings.load(file, emptyMap())
        assertTrue(settings.httpsEnabled)
        assertEquals(8443, settings.httpsPort)
        assertEquals("gateway.example.test", settings.publicAddress)
        assertEquals(443, settings.publicHttpsPort)
        assertTrue(settings.forceHttpsRedirect)
        assertEquals("/tmp/cert.pem", settings.certificatePath)
        assertEquals("/tmp/key.pem", settings.privateKeyPath)
        assertNull(settings.pkcs12Path)
        val conflict = assertThrows<IllegalArgumentException> {
            GatewaySettings.load(file, mapOf("OIP_HTTP_HTTPS" to "false"))
        }
        assertTrue(conflict.message!!.contains("forceHttpsRedirect"))
        val overridden = GatewaySettings.load(file, mapOf(
            "OIP_HTTP_HTTPS" to "false",
            "OIP_HTTP_FORCE_REDIRECT" to "false",
            "OIP_HTTP_HTTPS_PORT" to "9443",
            "OIP_HTTP_PUBLIC_ADDRESS" to "other.example.test",
            "OIP_HTTPS_PKCS12" to "/tmp/store.p12",
        ))
        assertFalse(overridden.httpsEnabled)
        assertEquals(9443, overridden.httpsPort)
        assertEquals("other.example.test", overridden.publicAddress)
        assertEquals("/tmp/store.p12", overridden.pkcs12Path)
        assertNull(overridden.certificatePath)
    }

    @Test fun unsafeHttpsSettingsAreRejected() {
        val file = tmp.resolve("gateway.yaml")
        file.writeText("profile: standard\nhttp:\n  https: false\n  forceHttpsRedirect: true\n")
        val redirect = assertThrows<IllegalArgumentException> { GatewaySettings.load(file, emptyMap()) }
        assertTrue(redirect.message!!.contains("forceHttpsRedirect"))
        file.writeText("profile: standard\nhttp:\n  port: 8043\n  https: true\n  httpsPort: 8043\n")
        val same = assertThrows<IllegalArgumentException> { GatewaySettings.load(file, emptyMap()) }
        assertTrue(same.message!!.contains("must differ"))
        file.writeText("profile: standard\nhttp:\n  https: true\n  privateKey: |\n    -----BEGIN PRIVATE KEY-----\n    abc\n")
        val pasted = assertThrows<IllegalArgumentException> { GatewaySettings.load(file, emptyMap()) }
        assertTrue(pasted.message!!.contains("file path"))
        assertFalse(pasted.message!!.contains("BEGIN PRIVATE KEY"))
        file.writeText("profile: standard\nhttp:\n  nope: 1\n")
        assertThrows<IllegalArgumentException> { GatewaySettings.load(file, emptyMap()) }
        file.writeText("profile: edge\n")
        assertEquals("127.0.0.1", GatewaySettings.load(file, emptyMap()).host)
        assertEquals(GatewaySettings.DEFAULT_HTTPS_PORT, GatewaySettings.load(file, emptyMap()).httpsPort)
        assertFalse(GatewaySettings.load(file, emptyMap()).httpsEnabled)
    }

    @Test fun pkcs12Base64RoundTripKeepsTheFingerprint() {
        val material = TlsCertificates.generate(emptyList())
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, password)
        store.setKeyEntry("gateway", material.privateKey, password, material.chain.toTypedArray())
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            store.store(out, password)
            out.toByteArray()
        }
        val text = Base64.getEncoder().encodeToString(bytes)
        assertEquals(material.fingerprint(), TlsCertificates.parsePkcs12(Base64.getMimeDecoder().decode(text), password).fingerprint())
    }

    private fun keyMarker(material: TlsMaterial): String =
        TlsCertificates.privateKeyPem(material.privateKey).lineSequence().first { it.length > 40 && !it.startsWith("-") }
}
