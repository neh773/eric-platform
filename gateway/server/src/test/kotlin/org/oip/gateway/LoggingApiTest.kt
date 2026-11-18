package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * GAT-017: runtime logger level changes take effect without a restart, and logs can be downloaded.
 */
class LoggingApiTest {
    @TempDir lateinit var tmp: Path

    @Test fun levelChangeTakesEffectWithoutRestartAndDownloadContainsStructuredLines() {
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            assertEquals(emptyList<Any>(), records(http, "logger=org.oip.http"))
            val changed = http.put("/api/v1/logs/levels", """{"logger":"org.oip.http","level":"DEBUG"}""")
            assertEquals(200, changed.status)
            assertEquals("DEBUG", changed.json["level"])
            http.get("/api/v1/health")
            val after = records(http, "logger=org.oip.http&q=/api/v1/health")
            assertEquals(1, after.size)
            assertEquals("DEBUG", after[0]["level"])
            assertEquals("request", after[0]["message"])
            @Suppress("UNCHECKED_CAST")
            val fields = after[0]["fields"] as Map<String, Any?>
            assertEquals("/api/v1/health", fields["path"])
            assertEquals("GET", fields["method"])
            val raised = http.put("/api/v1/logs/levels", """{"logger":"org.oip.http","level":"ERROR"}""")
            assertEquals(200, raised.status)
            http.get("/api/v1/health")
            assertEquals(1, records(http, "logger=org.oip.http&q=/api/v1/health").size, "ERROR level must drop the next DEBUG request line")
            val bad = http.put("/api/v1/logs/levels", """{"logger":"org.oip.http","level":"LOUD"}""")
            assertEquals(422, bad.status)
            assertTrue(bad.header("Content-Type")!!.contains("problem+json"))
            val download = http.get("/api/v1/logs/download")
            assertEquals(200, download.status)
            assertTrue(download.header("Content-Type")!!.contains("application/x-ndjson"))
            assertTrue(download.body.contains("\"logger\":\"org.oip.gateway\""))
            assertTrue(download.body.contains("\"message\":\"gateway started\""))
        }
        Gateway(tmp, port = 0).use { g ->
            g.start()
            val levels = Http(g.port()).also { g.loginAdmin(it) }.get("/api/v1/logs/levels")
            assertEquals(200, levels.status)
            assertTrue(levels.body.contains("\"org.oip.http\":\"ERROR\""))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun records(http: Http, query: String): List<Map<String, Any?>> =
        http.get("/api/v1/logs?$query").json["records"] as List<Map<String, Any?>>
}
