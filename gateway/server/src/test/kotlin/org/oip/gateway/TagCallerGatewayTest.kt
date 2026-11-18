package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.security.AuditProfile
import org.oip.security.AuditQuery
import org.oip.security.AuditStatus
import org.oip.security.DefaultRoles
import org.oip.security.SecurityZone
import org.oip.tags.MemoryTagPersistence
import org.oip.tags.TAG_WRITE_ACTION
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * C-1: the gateway's REST tag routes run the per-tag permission checks (TAG-013) as the signed-in user, user
 * writes are audited, and memory-tag values live across a restart (TAG-016).
 */
class TagCallerGatewayTest {
    @TempDir lateinit var tmp: Path

    private fun gateway(dir: Path): Gateway {
        dir.createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        return Gateway(dir, port = 0)
    }

    private fun Gateway.tag(name: String, vararg spec: Pair<String, Any?>) {
        val version = store.get(ResourceId("tags", Scope.GATEWAY, "Tag", name))?.metadata?.version ?: 0L
        store.put(Scope.GATEWAY, Resource("Tag", Metadata(name, "tags", version = version), mapOf(*spec)))
    }

    private fun session(gw: Gateway, user: String): Http = Http(gw.port()).also {
        val r = it.login(user, TEST_PASSWORD)
        check(r.status == 200) { "login $user: ${r.status} ${r.body}" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Http.readOne(path: String): Map<String, Any?> {
        val r = post("/api/v1/tags/read", Json.write(mapOf("paths" to listOf(path))))
        assertEquals(200, r.status, r.body)
        return (r.json["values"] as List<Map<String, Any?>>).single()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Http.writeOne(path: String, value: Any?): Map<String, Any?> {
        val r = post("/api/v1/tags/write", Json.write(mapOf("writes" to listOf(mapOf("path" to path, "value" to value)))))
        assertEquals(200, r.status, r.body)
        return (r.json["results"] as List<Map<String, Any?>>).single()
    }

    private fun Gateway.tagWrites(actor: String, status: AuditStatus? = null) =
        security.audit.query(AuditQuery(action = TAG_WRITE_ACTION, actor = actor, status = status, limit = 100)).records

    private fun num(v: Any?): Double = (v as Number).toDouble()

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun restReadsAndWritesRunThePerTagChecksAsTheSignedInUser() {
        gateway(tmp.resolve("rest")).use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.createUser("vic", setOf(DefaultRoles.VIEWER))
            gw.createUser("root", setOf(DefaultRoles.ADMINISTRATOR))
            // The test client connects from 127.0.0.1, so its requests are in the floor zone.
            gw.security.zones.create(SecurityZone("floor", ipAddresses = listOf("127.0.0.1"), priority = 10), actor = "test")
            gw.tag("recipe", "path" to "Line/Recipe", "type" to "memory", "dataType" to "String", "value" to "mix",
                "readPermissions" to listOf("operator"))
            gw.tag("setpoint", "path" to "Line/Setpoint", "type" to "memory", "dataType" to "Double", "value" to 10.0,
                "writePermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("floor")))
            gw.tag("remote", "path" to "Line/RemoteOnly", "type" to "memory", "dataType" to "Double", "value" to 1.0,
                "writePermissions" to mapOf("roles" to listOf("operator"), "zones" to listOf("remote")))
            gw.start()
            val vic = session(gw, "vic")
            val ada = session(gw, "ada")
            val root = session(gw, "root")

            // Read: the viewer gets the open tag and is refused the operator-only one.
            val hidden = vic.readOne("[default]Line/Recipe")
            assertNull(hidden["value"])
            assertEquals("Bad_AccessDenied", hidden["quality"])
            assertEquals(10.0, num(vic.readOne("[default]Line/Setpoint")["value"]))
            assertEquals("mix", ada.readOne("[default]Line/Recipe")["value"])
            assertEquals("Bad_AccessDenied", root.readOne("[default]Line/Recipe")["quality"], "administrators get no implicit pass")

            // Write: the route needs tags.write, which the viewer role does not hold.
            val viewerWrite = vic.post("/api/v1/tags/write", """{"writes":[{"path":"Line/Setpoint","value":1}]}""")
            assertEquals(403, viewerWrite.status, viewerWrite.body)

            // An administrator holds tags.write but not the tag's operator role: refused, unchanged, audited.
            val refused = root.writeOne("Line/Setpoint", 99.0)
            assertEquals("Bad_AccessDenied", refused["quality"])
            assertEquals(false, refused["ok"])
            assertEquals(10.0, num(gw.tags.read("Line/Setpoint").value))
            val failure = gw.tagWrites("root", AuditStatus.FAILURE).single()
            assertTrue(failure.detail.contains("rejected Bad_AccessDenied"), failure.detail)
            assertTrue(failure.target.endsWith("Line/Setpoint"), failure.target)

            // The operator in the floor zone writes, once, audited with its host and the HTTP context.
            val ok = ada.writeOne("Line/Setpoint", 12.5)
            assertEquals("Good", ok["quality"])
            assertEquals(true, ok["ok"])
            assertEquals(12.5, num(gw.tags.read("Line/Setpoint").value))
            val success = gw.tagWrites("ada", AuditStatus.SUCCESS).single()
            assertEquals("127.0.0.1", success.actorHost)
            assertEquals("gateway-http", success.context)
            assertTrue(success.detail.contains("old=10.0") && success.detail.contains("new=12.5") && success.detail.contains("zone=floor"), success.detail)

            // The right role from the wrong zone is refused.
            assertEquals("Bad_AccessDenied", ada.writeOne("Line/RemoteOnly", 5.0)["quality"])
            assertEquals(1.0, num(gw.tags.read("Line/RemoteOnly").value))
            assertEquals(1, gw.tagWrites("ada", AuditStatus.FAILURE).size)

            // A batch reports each write on its own, in order.
            val batch = ada.post("/api/v1/tags/write", Json.write(mapOf("writes" to listOf(
                mapOf("path" to "Line/Setpoint", "value" to 13.0),
                mapOf("path" to "Line/Nope", "value" to 1),
            ))))
            assertEquals(200, batch.status, batch.body)
            @Suppress("UNCHECKED_CAST")
            val rows = batch.json["results"] as List<Map<String, Any?>>
            assertEquals(listOf("Good", "Bad_NotFound"), rows.map { it["quality"] })
            assertEquals(422, ada.post("/api/v1/tags/write", """{"writes":[{"path":"Line/Setpoint"}]}""").status)
            assertEquals(422, ada.post("/api/v1/tags/write", """{"writes":"Line/Setpoint"}""").status)

            // With the audit profile disabled, writes are still checked but no longer recorded.
            val before = gw.tagWrites("ada").size
            gw.security.auditProfile.update(AuditProfile("Internal audit profile", 90, false), actor = "test")
            assertEquals("Good", ada.writeOne("Line/Setpoint", 14.0)["quality"])
            assertEquals("Bad_AccessDenied", ada.writeOne("Line/RemoteOnly", 6.0)["quality"])
            gw.security.auditProfile.update(AuditProfile("Internal audit profile", 90, true), actor = "test")
            assertEquals(before, gw.tagWrites("ada").size)
            assertTrue(gw.security.audit.verify().ok)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun memoryTagValuesLiveAcrossAGatewayRestart() {
        val dir = tmp.resolve("restart")
        gateway(dir).use { gw ->
            gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
            gw.tag("speed", "path" to "Line/Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0)
            gw.tag("gone", "path" to "Line/Gone", "type" to "memory", "dataType" to "Int32", "value" to 0)
            gw.start()
            val ada = session(gw, "ada")
            assertEquals("Good", ada.writeOne("Line/Speed", 42.5)["quality"])
            assertEquals("Good", ada.writeOne("Line/Gone", 7)["quality"])
        }
        assertTrue(dir.resolve("db").resolve(MemoryTagPersistence.FILE_NAME).exists())
        // The close flushed both values. Delete one tag's resource while the gateway is down.
        FileConfigStore(dir.resolve("config"), SchemaRegistry()).use { store ->
            store.delete(ResourceId("tags", Scope.GATEWAY, "Tag", "gone"))
        }
        gateway(dir).use { gw ->
            val restored = gw.tags.read("Line/Speed")
            assertEquals(42.5, num(restored.value), "restored when the tag configuration loads, before start")
            gw.start()
            assertEquals(42.5, num(gw.tags.read("Line/Speed").value))
            // Recreating the deleted tag starts it from its seed: the stale row was pruned at load.
            gw.tag("gone", "path" to "Line/Gone", "type" to "memory", "dataType" to "Int32", "value" to 0)
            assertEquals(0, (gw.tags.read("Line/Gone").value as Number).toInt())
            gw.memoryTags.flush()
            assertFalse(gw.memoryTags.failureCount() > 0)
        }
    }
}
