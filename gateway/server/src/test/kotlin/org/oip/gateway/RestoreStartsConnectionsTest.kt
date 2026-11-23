package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** A backup restored on a fresh gateway starts its drivers and connections at once, with no gateway restart. */
class RestoreStartsConnectionsTest {
    @TempDir lateinit var tmp: Path

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun restoredDevicesAndConnectionsRunWithoutARestart() {
        val idle = ServerSocket(0).use { it.localPort }
        val source = tmp.resolve("a").createDirectories()
        source.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  port: 0\n")
        val archive = Gateway(source, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            g.security.secrets.putText("mqtt-pass", "not-a-real-password")
            put(http, "/api/v1/config/org.oip.driver-modbus/gateway/Device/ls01", """
                {"apiVersion":"oip/v1","kind":"Device","metadata":{"name":"ls01","module":"org.oip.driver-modbus","version":0},
                 "spec":{"name":"ls01","host":"127.0.0.1","port":$idle,"mode":"TCP","unitId":1,"timeoutMs":400,"reconnectAfterConsecutiveTimeouts":1,"maxRetryCount":0,
                   "backoffInitialMs":30,"backoffMaxMs":80,"backoffMultiplier":2,"defaultScanPeriodMs":200}}
            """.trimIndent())
            put(http, "/api/v1/config/org.oip.driver-mqtt/gateway/Connection/line", """
                {"apiVersion":"oip/v1","kind":"Connection","metadata":{"name":"line","module":"org.oip.driver-mqtt","version":0},
                 "spec":{"host":"127.0.0.1","port":$idle,"clientId":"oip-gw","keepAliveSeconds":10,"cleanSession":true,
                   "username":"secret://mqtt-pass","password":"secret://mqtt-pass","reconnectInitialMs":20,"reconnectMaxMs":80,
                   "defaultScanPeriodMs":200,"connectTimeoutMs":500,"autoCreateTags":false}}
            """.trimIndent())
            val db = source.resolve("plant.db").toString().replace("\\", "/")
            val made = http.post("/api/v1/db/connections", """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite:$db"}""")
            assertEquals(201, made.status, made.body)
            await("source rows") { started(g).keys.containsAll(listOf("device:modbus:ls01", "device:mqtt:line")) && started(g)["database:sql:plant"] == "Valid" }
            g.backups.createEncrypted(TEST_BACKUP_PASSPHRASE.toCharArray())
        }
        val target = tmp.resolve("b").createDirectories()
        target.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  port: 0\n")
        Gateway(target, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            val restored = http.restoreBackup(archive)
            assertEquals(200, restored.status, restored.body)
            await("restored rows started") {
                val rows = started(g)
                rows["device:modbus:ls01"] != null && rows["device:mqtt:line"] != null && rows["database:sql:plant"] == "Valid"
            }
        }
    }

    private fun put(http: Http, path: String, body: String) {
        val res = http.put(path, body)
        assertEquals(201, res.status, res.body)
    }

    /** Rows that are configured and have a driver behind them (status is not "Not Started"), by id. */
    private fun started(g: Gateway): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val items = ConnectionsApi(g).document()["items"] as List<Map<String, Any?>>
        return items.filter { it["status"] != "Not Started" }.associate { it["id"] as String to it["status"] as String }
    }

    private fun await(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < end) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(100)
        }
        fail<Unit>("timed out waiting for $what")
    }
}
