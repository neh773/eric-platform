package org.oip.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The functional half of [SecurityBenchmarkTest], without a clock: chained audit appends verify, the zone
 * match picks the right zone, an API key authenticates repeatedly, and a production Argon2id hash verifies.
 */
class SecurityHotPathTest {
    @TempDir lateinit var tmp: Path

    @Test fun auditAppendZoneMatchAndApiKeyAreCorrect() {
        val sink = SqliteAuditSink(tmp.resolve("db/audit.sqlite"))
        val secret = SecretKeyLoader.fromBytes(ByteArray(32) { 1 })
        val log = ChainedAuditLog(
            sink,
            AuditKeys.chainKey(secret),
            MacAuditAnchor(FileAuditAnchorStore(tmp.resolve("db/audit.anchor")), AuditKeys.anchorKey(secret)),
        )
        repeat(200) { i ->
            log.append(AuditDraft(1_700_000_000_000L + i, "bench", "tag write", AuditStatus.SUCCESS, target = "tag/$i"))
        }
        val verified = log.verify()
        assertTrue(verified.ok)
        assertEquals(200, verified.records)
        sink.close()

        val zones = (1..40).map { i ->
            SecurityZone("z$i", priority = i, ipAddresses = listOf("10.$i.0.0/16"), hostNames = listOf("host-$i"))
        }
        val ctx = ConnectionContext(java.net.InetAddress.getByName("10.20.1.9"), "host-20", null, true, true, RequestScope.GATEWAY)
        repeat(100) { assertEquals("z20", SecurityZone.select(zones, ctx).name) }

        val (store, svc) = openSecurity(tmp.resolve("gw"))
        try {
            val issued = svc.apiKeys.create("bench", setOf(Permissions.TAGS_READ), actor = "boss")
            repeat(100) { assertTrue(svc.apiKeys.authenticate(issued.token) is AuthenticationResult.Success) }
        } finally { svc.close(); store.close() }
    }

    @Test fun productionArgon2idHashVerifies() {
        val hasher = Argon2idHasher.production()
        val encoded = hasher.hash("Abcdefg1!".toCharArray())
        assertTrue(hasher.verify(encoded, "Abcdefg1!".toCharArray()))
        assertTrue(!hasher.verify(encoded, "Abcdefg1?".toCharArray()))
    }
}
