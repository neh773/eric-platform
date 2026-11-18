package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.*

class GatewayMainTest {
    @TempDir lateinit var tmp: Path

    @Test fun createsDataDirLayoutAndDefaultGatewayYaml() {
        Gateway(tmp, port = 0).use { g ->
            for (d in listOf("config", "modules", "db", "logs")) assertTrue(tmp.resolve(d).isDirectory(), d)
            assertTrue(tmp.resolve("gateway.yaml").readText().contains("profile: standard"))
            assertEquals("standard", g.settings.profile)
        }
    }

    @ParameterizedTest @ValueSource(strings = ["edge", "standard", "hub"])
    fun profileLoadsAndHealthReportsIt(profile: String) {
        tmp.resolve("gateway.yaml").writeText("profile: $profile\n")
        Gateway(tmp, port = 0).use { g ->
            g.start()
            assertEquals(profile, g.settings.profile)
            assertEquals(listOf(profile), g.settings.features)
            val r = Http(g.port()).get("/api/v1/health")
            assertEquals(200, r.status)
            val body = Json.parse(r.body)
            assertEquals("UP", body["status"]); assertEquals(profile, body["profile"])
        }
    }

    @Test fun edgeBindsLoopbackByDefault() {
        tmp.resolve("gateway.yaml").writeText("profile: edge\n")
        Gateway(tmp, port = 0).use { assertEquals("127.0.0.1", it.settings.host) }
    }

    @Test fun gatewayYamlOverridesProfileDefaults() {
        tmp.resolve("gateway.yaml").writeText("profile: hub\nhttp:\n  port: 9123\n  host: 127.0.0.1\n")
        Gateway(tmp).use { assertEquals(9123, it.settings.port); assertEquals("127.0.0.1", it.settings.host) }
    }

    @Test fun unknownProfileRejected() {
        tmp.resolve("gateway.yaml").writeText("profile: galaxy\n")
        val e = assertThrows<IllegalArgumentException> { Gateway(tmp, port = 0) }
        assertTrue(e.message!!.contains("galaxy"))
    }

    @Test fun httpAuthSettingsLoadAndUnknownHttpKeyRejected() {
        tmp.resolve("gateway.yaml").writeText(
            """
            profile: standard
            http:
              port: 8090
              https: true
              secureCookies: false
              trustedProxies: ["10.0.0.0/8"]
              loginRatePerMinute: 30
              lockoutDistinctIps: 4
              authFailureRowCap: 100
              absoluteSessionMinutes: 60
            """.trimIndent()
        )
        Gateway(tmp, port = 0).use { g ->
            assertTrue(g.settings.httpsEnabled)
            assertEquals(false, g.settings.secureCookies)
            assertFalse(g.settings.secureCookiesEffective)
            assertEquals(listOf("10.0.0.0/8"), g.settings.trustedProxies)
            assertEquals(30, g.settings.loginRatePerMinute)
            assertEquals(4, g.settings.lockoutDistinctIps)
            assertEquals(100, g.settings.authFailureRowCap)
            assertEquals(60, g.settings.absoluteSessionMinutes)
            assertEquals(Duration.ofMinutes(60), g.sessionPolicy.absolute)
            assertTrue(g.insecureCookieWarning!!.contains("secureCookies"))
        }
        tmp.resolve("gateway.yaml").writeText("profile: edge\nhttp:\n  nope: 1\n")
        val e = assertThrows<IllegalArgumentException> { Gateway(tmp, port = 0) }
        assertTrue(e.message!!.contains("nope"))
    }

    @Test fun unknownGatewayYamlKeyRejected() {
        tmp.resolve("gateway.yaml").writeText("profile: edge\nbogus: 1\n")
        assertThrows<IllegalArgumentException> { Gateway(tmp, port = 0) }
    }

    @Test fun mainArgsParsed() {
        val a = parseArgs(arrayOf("--data-dir", tmp.toString(), "--port", "0"))
        assertEquals(tmp, a.dataDir); assertEquals(0, a.port)
        assertThrows<IllegalArgumentException> { parseArgs(arrayOf("--nope")) }
    }

    @Test fun envOverridesProfileHostPortAndFeatures() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\n")
        val file = tmp.resolve("gateway.yaml")
        val s = GatewaySettings.load(file, mapOf(
            "OIP_PROFILE" to "edge", "OIP_HTTP_HOST" to "0.0.0.0", "OIP_HTTP_PORT" to "8088", "OIP_FEATURES" to "edge, extra"))
        assertEquals("edge", s.profile)
        assertEquals("0.0.0.0", s.host)
        assertEquals(8088, s.port)
        assertEquals(listOf("edge", "extra"), s.features)
        val defaults = GatewaySettings.load(file, mapOf("OIP_PROFILE" to "edge"))
        assertEquals("127.0.0.1", defaults.host)
        assertEquals(listOf("edge"), defaults.features)
    }

    @Test fun fileHttpStillOverridesTheEnvProfileDefaults() {
        tmp.resolve("gateway.yaml").writeText("profile: edge\nhttp:\n  host: 10.1.2.3\n  port: 9000\n")
        val s = GatewaySettings.load(tmp.resolve("gateway.yaml"), mapOf("OIP_PROFILE" to "standard"))
        assertEquals("standard", s.profile)
        assertEquals("10.1.2.3", s.host)
        assertEquals(9000, s.port)
    }

    @Test fun opcuaSettingsLoadAndUnknownKeyRejected() {
        tmp.resolve("gateway.yaml").writeText(
            """
            profile: standard
            opcua:
              enabled: false
              bindAddress: 127.0.0.1
              port: 0
              hostname: 127.0.0.1
            """.trimIndent() + "\n",
        )
        Gateway(tmp, port = 0).use { g ->
            assertFalse(g.settings.opcua.enabled)
            assertEquals(0, g.settings.opcua.port)
            assertEquals("127.0.0.1", g.settings.opcua.bindAddress)
            assertEquals("127.0.0.1", g.settings.opcua.hostname)
            g.start()
            assertEquals(null, g.opcUa)
        }
        val file = tmp.resolve("gateway.yaml")
        val overridden = GatewaySettings.load(file, mapOf("OIP_OPCUA_ENABLED" to "true", "OIP_OPCUA_PORT" to "49320"))
        assertTrue(overridden.opcua.enabled)
        assertEquals(49320, overridden.opcua.port)
        file.writeText("profile: standard\n")
        val defaults = GatewaySettings.load(file, emptyMap())
        assertTrue(defaults.opcua.enabled)
        assertEquals(4840, defaults.opcua.port)
        assertEquals("127.0.0.1", defaults.opcua.bindAddress)
        file.writeText("profile: standard\nopcua:\n  nope: 1\n")
        val e = assertThrows<IllegalArgumentException> { Gateway(tmp, port = 0) }
        assertTrue(e.message!!.contains("nope"))
    }

    @Test fun badEnvPortAndProfileRejected() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\n")
        val file = tmp.resolve("gateway.yaml")
        assertThrows<IllegalArgumentException> { GatewaySettings.load(file, mapOf("OIP_HTTP_PORT" to "nope")) }
        val e = assertThrows<IllegalArgumentException> { GatewaySettings.load(file, mapOf("OIP_PROFILE" to "galaxy")) }
        assertTrue(e.message!!.contains("galaxy"))
    }

    @Test fun dataDirFromEnvOverriddenByFlag() {
        assertEquals(tmp, parseArgs(emptyArray(), mapOf("OIP_DATA_DIR" to tmp.toString())).dataDir)
        assertEquals(Path.of("other"), parseArgs(arrayOf("--data-dir", "other"), mapOf("OIP_DATA_DIR" to tmp.toString())).dataDir)
    }
}
