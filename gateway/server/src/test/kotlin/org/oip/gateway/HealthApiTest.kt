package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class HealthApiTest {
    @TempDir lateinit var tmp: Path

    @Test fun healthReportsTheGatewayStartInstant() {
        val dir = tmp.resolve("gw").createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        val before = Instant.now()
        Gateway(dir, port = 0).use { g ->
            g.start()
            val http = Http(g.port())
            val first = http.get("/api/v1/health")
            assertEquals(200, first.status, first.body)
            val raw = first.json["startedAt"]
            assertTrue(raw is String, "startedAt must be a string: ${first.body}")
            val startedAt = Instant.parse(raw as String)
            assertFalse(startedAt.isAfter(Instant.now()), "startedAt $startedAt is in the future")
            assertFalse(startedAt.isBefore(before), "startedAt $startedAt is before the gateway was created ($before)")
            assertEquals(g.startedAt, startedAt)
            // stable across requests: it is the start time, not the request time
            Thread.sleep(5)
            assertEquals(raw, http.get("/api/v1/health").json["startedAt"])
        }
    }
}
