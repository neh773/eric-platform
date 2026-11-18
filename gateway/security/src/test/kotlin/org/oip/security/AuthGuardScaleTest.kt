package org.oip.security

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * GatewayAuth.guard -> PermissionAuthorizer -> ZoneService.match -> SecurityDirectory.zones -> ConfigDocs.list.
 * That list used to walk every config head under the store lock. 1k tags and 200k tags have to land in a small factor.
 */
@Tag("benchmark")
class AuthGuardScaleTest {
    @TempDir lateinit var tmp: Path

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun authorizeCostIsIndependentOfTagCount() {
        val (store, svc) = openSecurity(tmp.resolve("auth-scale"))
        try {
            svc.users.create(CreateUser("ada", "Abcdefg1!".toCharArray(), setOf("administrator")), "boss")
            store.registry.register(
                "bench",
                "Item",
                mapOf("type" to "object", "properties" to mapOf("n" to mapOf("type" to "integer"))),
            )
            fun items(from: Int, until: Int): List<Resource> {
                val out = ArrayList<Resource>(until - from)
                for (i in from until until) {
                    out += Resource("Item", Metadata("n%06d".format(i), "bench"), mapOf("n" to i))
                }
                return out
            }
            store.putAll(Scope.GATEWAY, items(0, 1_000), actor = "bench", syncEach = false)
            val ctx = ConnectionContext(
                java.net.InetAddress.getByName("10.0.0.8"),
                null,
                null,
                true,
                true,
                RequestScope.GATEWAY,
            )
            val subject = Subject.User("ada", setOf("administrator"), true)
            val small = bestAuthorizeMillis(svc, subject, ctx)
            val started = System.nanoTime()
            store.putAll(Scope.GATEWAY, items(1_000, 200_000), actor = "bench", syncEach = false)
            val batchSec = (System.nanoTime() - started) / 1e9
            val large = bestAuthorizeMillis(svc, subject, ctx)
            val factor = if (small <= 0.0) large else large / small
            println(
                "SCALE auth guard 1k=${"%.3f".format(small)} ms 200k=${"%.3f".format(large)} ms " +
                    "factor=${"%.2f".format(factor)} bulkSec=${"%.3f".format(batchSec)}",
            )
            assertTrue(large < 5.0, "auth guard at 200k tags was ${"%.3f".format(large)} ms")
            assertTrue(factor < 3.0, "auth guard scaled with tag count: 1k=$small ms 200k=$large ms factor=$factor")
        } finally {
            svc.close()
            store.close()
        }
    }

    private fun bestAuthorizeMillis(svc: SecurityService, subject: Subject, ctx: ConnectionContext): Double {
        val perBatch = 40
        val batches = 7
        repeat(perBatch) {
            check(svc.authorization.authorize(subject, Permissions.GATEWAY_READ, ctx).allowed)
        }
        val times = DoubleArray(batches)
        for (b in times.indices) {
            val started = System.nanoTime()
            repeat(perBatch) {
                check(svc.authorization.authorize(subject, Permissions.GATEWAY_READ, ctx).allowed)
            }
            times[b] = (System.nanoTime() - started) / 1_000_000.0 / perBatch
        }
        return times.min()
    }
}
