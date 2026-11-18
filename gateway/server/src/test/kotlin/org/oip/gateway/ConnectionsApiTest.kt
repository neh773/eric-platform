package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * GAT-022: the Connections list covers device, database, service and OPC connections with live status, and the
 * Diagnostics environment page (GAT-021) reads runtime facts. Both need gateway.read.
 * Doc source: FEATURES GAT-022, GAT-021.
 */
class ConnectionsApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    @Suppress("UNCHECKED_CAST")
    private fun items(): List<Map<String, Any?>> {
        val response = http.get("/api/v1/connections")
        assertEquals(200, response.status, response.body)
        return response.json["items"] as List<Map<String, Any?>>
    }

    private fun item(id: String) = items().single { it["id"] == id }

    @Test fun listsDevicesDatabasesServicesAndOpcConnectionsWithStatus() {
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/plc", """
            {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"plc","module":"org.oip.driver-modbus","version":0},
             "spec":{"name":"plc","host":"127.0.0.1","port":$closed,"mode":"TCP","unitId":1,"timeoutMs":300}}
        """.trimIndent()).status)
        assertEquals(201, http.put("/api/v1/config/org.oip.driver-modbus/gateway/Device/parked", """
            {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"parked","module":"org.oip.driver-modbus","version":0},
             "spec":{"name":"parked","host":"127.0.0.1","port":$closed,"mode":"TCP","unitId":1,"enabled":false}}
        """.trimIndent()).status)
        assertEquals(201, http.post("/api/v1/db/connections",
            """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:","username":"","description":"plant"}""").status)

        val rows = items()
        val kinds = rows.map { it["kind"] }.toSet()
        assertTrue(kinds.containsAll(setOf("device", "database", "service")), kinds.toString())

        val plc = item("device:modbus:plc")
        assertTrue(plc["health"] == "pending" || plc["health"] == "fault", plc.toString())
        assertEquals("config", plc["section"])
        assertEquals("disabled", item("device:modbus:parked")["health"])
        val db = item("database:sql:plant")
        assertEquals("ok", db["health"])
        assertEquals("Valid", db["status"])
        assertEquals("databases", db["section"])
        assertEquals("service", item("service:opcua-server:OPC UA Server")["kind"])

        @Suppress("UNCHECKED_CAST")
        val summary = http.get("/api/v1/connections").json["summary"] as Map<String, Any?>
        assertEquals(rows.size, (summary["total"] as Number).toInt())
        assertEquals(rows.count { it["health"] == "ok" }, (summary["ok"] as Number).toInt())
    }

    @Test fun statusFollowsTheDatabaseConnectionWhenItIsDisabled() {
        assertEquals(201, http.post("/api/v1/db/connections",
            """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:","username":"","enabled":false}""").status)
        assertEquals("disabled", item("database:sql:plant")["health"])
        assertEquals("Disabled", item("database:sql:plant")["status"])
    }

    @Test fun readsNeedGatewayReadAndNeverReturnSecrets() {
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/connections"))
        assertEquals(null, GatewayAuth.explicitRule("POST", "/api/v1/connections"))
        val secret = "Conn-db-secret-77"
        assertEquals(201, http.post("/api/v1/db/connections",
            """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite::memory:","username":"","password":"$secret"}""").status)
        assertFalse(http.get("/api/v1/connections").body.contains(secret))
        assertEquals(401, Http(gw.port()).get("/api/v1/connections").status)
        val viewer = Http(gw.port())
        assertEquals(200, viewer.login("view", TEST_PASSWORD).status)
        assertEquals(403, viewer.get("/api/v1/connections").status)
        assertEquals(403, viewer.get("/api/v1/diagnostics/environment").status)
    }

    @Test fun environmentReportsRuntimeFactsWithoutEnvironmentVariables() {
        val response = http.get("/api/v1/diagnostics/environment")
        assertEquals(200, response.status, response.body)
        val body = response.json
        assertEquals("standard", body["profile"])
        assertEquals(System.getProperty("java.version"), body["javaVersion"])
        assertEquals(tmp.toAbsolutePath().toString(), body["dataDirectory"])
        assertTrue((body["processors"] as Number).toInt() >= 1)
        assertFalse(response.body.contains("JAVA_TOOL_OPTIONS"))
    }
}
