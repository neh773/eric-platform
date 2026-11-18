package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.oip.security.AuditActions
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import kotlin.io.path.*

class ModulesApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http
    private val id = "org.oip.hello-world"
    private val jar = Path.of(System.getProperty("oip.helloJar"))
    private val manifest = ZipFile(jar.toFile()).use { z -> z.getInputStream(z.getEntry("module.yaml")).readBytes().decodeToString() }

    private fun pkg(version: String = "1.0.0") = ByteArrayOutputStream().also { bo -> ZipOutputStream(bo).use { z ->
        z.putNextEntry(ZipEntry("module.yaml")); z.write(manifest.replace(Regex("(?m)^version: .*$"), "version: $version").toByteArray()); z.closeEntry()
        z.putNextEntry(ZipEntry("hello-world.jar")); z.write(jar.readBytes()); z.closeEntry() } }.toByteArray()
    private fun install(v: String = "1.0.0") = http.send("POST", "/api/v1/modules", pkg(v), "application/zip")
    private fun snapshot(): Set<String> = Files.walk(tmp).use { s ->
        s.map { tmp.relativize(it).toString() }
            .filter { p -> p != "db" && !p.startsWith("db/") && p != "secrets" && !p.startsWith("secrets/") }
            .toList().toSet()
    }

    private val builtIn = setOf(
        "org.oip.driver-modbus",
        "org.oip.driver-opcua-client",
        "org.oip.driver-mqtt",
        "org.oip.driver-s7",
        "org.oip.driver-logix",
        "org.oip.driver-simulator",
        "org.oip.driver-udp-tcp",
        "org.oip.driver-bacnet",
        "org.oip.driver-dnp3",
    )
    private fun ids(body: Map<String, Any?>) =
        (body["items"] as List<*>).map { (it as Map<*, *>)["id"] as String }.toSet()

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }
    @AfterEach fun tearDown() = gw.close()

    @Test fun listEmptyThenInstallListUninstallWithNoResidue() {
        assertEquals(builtIn, ids(http.get("/api/v1/modules").json))
        val before = snapshot()
        val r = install()
        assertEquals(201, r.status, r.body)
        assertEquals("/api/v1/modules/$id", r.header("Location"))
        assertEquals("RUNNING", r.json["state"]); assertEquals("1.0.0", r.json["version"])
        assertEquals(listOf("log.write"), r.json["permissions"])
        assertEquals(builtIn + id, ids(http.get("/api/v1/modules").json))
        assertEquals(200, http.get("/api/v1/modules/$id").status)
        assertEquals(200, http.get("/api/v1/config/$id/gateway/ModuleConfig/default").status)
        assertEquals(builtIn.size + 1, (http.get("/api/v1/health").json["modules"] as Number).toInt())

        assertEquals(204, http.delete("/api/v1/modules/$id").status)
        assertEquals(404, http.get("/api/v1/modules/$id").status)
        assertEquals(404, http.get("/api/v1/config/$id/gateway/ModuleConfig/default").status)
        val after = snapshot() - setOf("installed-modules.tsv")
        assertEquals(before - setOf("installed-modules.tsv"), after, "residue after uninstall")
        val registry = tmp.resolve("installed-modules.tsv").readText()
        assertFalse(registry.contains(id))
        assertTrue(builtIn.all { registry.contains(it) })
    }

    @Test fun installTwiceIs409AndBadPackageIs422() {
        assertEquals(201, install().status)
        val dup = install("2.0.0"); assertEquals(409, dup.status); assertTrue(dup.header("Content-Type")!!.startsWith("application/problem+json"))
        val bad = http.send("POST", "/api/v1/modules", "nope".toByteArray(), "application/zip")
        assertEquals(422, bad.status, bad.body)
    }

    @Test fun upgradeViaPutKeepsConfigAndRefusesDowngrade() {
        install("1.0.0")
        val cfg = "/api/v1/config/$id/gateway/ModuleConfig/default"
        assertEquals(200, http.send("PATCH", cfg, """{"greeting":"Hi"}""".toByteArray(), "application/merge-patch+json").status)
        val up = http.send("PUT", "/api/v1/modules/$id", pkg("1.1.0"), "application/zip")
        assertEquals(200, up.status, up.body); assertEquals("1.1.0", up.json["version"]); assertEquals("RUNNING", up.json["state"])
        assertEquals("Hi", (http.get(cfg).json["spec"] as Map<*, *>)["greeting"])
        assertEquals(409, http.send("PUT", "/api/v1/modules/$id", pkg("1.0.0"), "application/zip").status)
        assertEquals(422, http.send("PUT", "/api/v1/modules/other.id", pkg("1.2.0"), "application/zip").status)
    }

    @Test fun moduleActionsAreAuditedOnce() {
        assertEquals(0, gw.auditCount(AuditActions.MODULE_INSTALLED))
        assertEquals(0, gw.auditCount(AuditActions.MODULE_UPGRADED))
        assertEquals(0, gw.auditCount(AuditActions.MODULE_UNINSTALLED))
        assertEquals(201, install().status)
        assertEquals(1, gw.auditCount(AuditActions.MODULE_INSTALLED, "admin", AuditStatus.SUCCESS))
        val installed = gw.security.audit.query(AuditQuery(action = AuditActions.MODULE_INSTALLED)).records.single()
        assertEquals("admin", installed.actor)
        assertEquals(id, installed.target)
        assertTrue(installed.timestampMillis > 0)
        assertEquals("version=1.0.0", installed.detail)
        assertFalse(installed.detail.contains("hash"))
        assertEquals(409, install("2.0.0").status)
        assertEquals(422, http.send("POST", "/api/v1/modules", "nope".toByteArray(), "application/zip").status)
        assertEquals(1, gw.auditCount(AuditActions.MODULE_INSTALLED))

        assertEquals(200, http.send("PUT", "/api/v1/modules/$id", pkg("1.1.0"), "application/zip").status)
        assertEquals(1, gw.auditCount(AuditActions.MODULE_UPGRADED, "admin", AuditStatus.SUCCESS))
        val upgraded = gw.security.audit.query(AuditQuery(action = AuditActions.MODULE_UPGRADED)).records.single()
        assertEquals(id, upgraded.target)
        assertEquals("admin", upgraded.actor)
        assertTrue(upgraded.timestampMillis > 0)
        assertEquals("from=1.0.0;version=1.1.0", upgraded.detail)

        val listed = http.get("/api/v1/audit?action=module%20upgraded&actor=admin")
        assertEquals(200, listed.status, listed.body)
        assertEquals(1, (listed.json["total"] as Number).toInt())
        @Suppress("UNCHECKED_CAST")
        val item = (listed.json["items"] as List<Map<String, Any?>>).single()
        assertEquals(id, item["target"])
        assertEquals("admin", item["actor"])
        assertTrue((item["timestamp"] as String).isNotBlank())

        assertEquals(204, http.delete("/api/v1/modules/$id").status)
        assertEquals(1, gw.auditCount(AuditActions.MODULE_UNINSTALLED, "admin", AuditStatus.SUCCESS))
        val removed = gw.security.audit.query(AuditQuery(action = AuditActions.MODULE_UNINSTALLED)).records.single()
        assertEquals(id, removed.target)
        assertEquals("version=1.1.0", removed.detail)
        assertEquals(404, http.delete("/api/v1/modules/$id").status)
        assertEquals(1, gw.auditCount(AuditActions.MODULE_UNINSTALLED))
    }

    @Test fun unknownModuleIs404() {
        assertEquals(404, http.delete("/api/v1/modules/nope").status)
        assertEquals(404, http.send("PUT", "/api/v1/modules/$id", pkg("1.1.0"), "application/zip").status)
    }

    @Test fun installedModuleSurvivesGatewayRestart() {
        install()
        gw.close()
        gw = Gateway(tmp, port = 0).start(); http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
        assertEquals("RUNNING", http.get("/api/v1/modules/$id").json["state"])
    }

    private fun restart() {
        gw.close()
        gw = Gateway(tmp, port = 0).start(); http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }
    @Suppress("UNCHECKED_CAST")
    private fun available(): Set<String> =
        ((http.get("/api/v1/modules").json["available"] as List<*>?) ?: emptyList<Any>()).map { (it as Map<*, *>)["id"] as String }.toSet()

    /** Review-1 F5: an uninstalled built-in driver must not come back when the gateway restarts. */
    @Test fun uninstalledBuiltInDriverStaysUninstalledAcrossRestartAndCanBeReinstalled() {
        val modbus = "org.oip.driver-modbus"
        assertEquals(builtIn, ids(http.get("/api/v1/modules").json))
        assertEquals(emptySet<String>(), available())
        assertEquals(204, http.delete("/api/v1/modules/$modbus").status)
        assertFalse(tmp.resolve("config").resolve(modbus).exists())

        restart()
        assertEquals(404, http.get("/api/v1/modules/$modbus").status, "built-in module came back after restart")
        assertEquals(builtIn - modbus, ids(http.get("/api/v1/modules").json))
        assertFalse(tmp.resolve("config").resolve(modbus).exists(), "config dir of the removed module was recreated")
        assertFalse(tmp.resolve("installed-modules.tsv").readText().contains(modbus))
        assertEquals(setOf(modbus), available())

        val again = http.send("POST", "/api/v1/modules/$modbus/install")
        assertEquals(201, again.status, again.body)
        assertEquals("RUNNING", again.json["state"])
        assertEquals("/api/v1/modules/$modbus", again.header("Location"))
        assertEquals(1, gw.auditCount(AuditActions.MODULE_INSTALLED, "admin", AuditStatus.SUCCESS))
        assertEquals(builtIn, ids(http.get("/api/v1/modules").json))
        assertEquals(emptySet<String>(), available())

        restart()
        assertEquals("RUNNING", http.get("/api/v1/modules/$modbus").json["state"])
        assertEquals(builtIn, ids(http.get("/api/v1/modules").json))
    }

    @Test fun builtInInstallRouteRefusesUnknownAndInstalledModules() {
        assertEquals(404, http.send("POST", "/api/v1/modules/org.oip.nope/install").status)
        assertEquals(404, http.send("POST", "/api/v1/modules/$id/install").status)
        assertEquals(409, http.send("POST", "/api/v1/modules/org.oip.driver-mqtt/install").status)
    }

    /** A fresh data dir still gets every built-in driver installed and running. */
    @Test fun freshDataDirInstallsEveryBuiltInDriver() {
        for (m in builtIn) assertEquals("RUNNING", http.get("/api/v1/modules/$m").json["state"], m)
        assertEquals(emptySet<String>(), available())
    }
}
