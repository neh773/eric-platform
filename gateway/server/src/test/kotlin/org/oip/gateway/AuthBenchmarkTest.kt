package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import java.nio.file.Path

/** Ceilings are loose so a busy CI runner still passes. The printed times are the baseline. */
class AuthBenchmarkTest {
    @TempDir lateinit var tmp: Path

    @Test fun sessionAndBearerChecksStayCheap() {
        val gw = Gateway(tmp, port = 0).start()
        try {
            gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
            val http = Http(gw.port())
            assertEquals(200, http.login("admin", TEST_PASSWORD).status)
            val n = 200
            val started = System.nanoTime()
            repeat(n) { assertEquals(200, http.get("/api/v1/auth/session").status) }
            val ms = (System.nanoTime() - started) / 1_000_000.0
            println("BENCH session auth n=$n totalMs=${"%.1f".format(ms)} avgMs=${"%.2f".format(ms / n)}")
            assertTrue(ms / n < 30.0, "session check averaged ${ms / n}ms")

            val key = gw.security.apiKeys.create("bench", setOf(Permissions.AUDIT_READ), secureConnectionRequired = false, actor = "test")
            val bearer = Http(gw.port())
            bearer.authorizeBearer(key.token)
            val calls = 100
            val keyStart = System.nanoTime()
            repeat(calls) { assertEquals(200, bearer.get("/api/v1/audit?limit=1").status) }
            val keyMs = (System.nanoTime() - keyStart) / 1_000_000.0
            println("BENCH bearer auth n=$calls totalMs=${"%.1f".format(keyMs)} avgMs=${"%.2f".format(keyMs / calls)}")
            assertTrue(keyMs / calls < 40.0, "bearer check averaged ${keyMs / calls}ms")
        } finally { gw.close() }
    }
}
