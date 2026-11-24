package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import org.oip.tags.QualityCode
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import org.oip.tags.UDT_KIND
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * TAG-024 over HTTP: the UDT wizard routes use the tags.write route permission (operators may create
 * instances, viewers may not), report every row, and write nothing when one row is bad.
 */
class UdtWizardHttpTest {
    @TempDir lateinit var tmp: Path

    @Test fun wizardRoutesPlanCreateAndEnforceTagsWrite() {
        // OPC UA off and an ephemeral gateway-network port, so this test does not need 4840 or 8060.
        val dir = tmp.resolve("gw").createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  port: 0\n")
        val gw = Gateway(dir, port = 0).start()
        try {
            gw.createUser("op", setOf(DefaultRoles.OPERATOR))
            gw.createUser("view", setOf(DefaultRoles.VIEWER))
            gw.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("pump", TAGS_MODULE), mapOf(
                "path" to "Types/Pump",
                "parameters" to listOf(
                    mapOf("name" to "Gain", "dataType" to "Double", "default" to 1),
                    mapOf("name" to "Device", "dataType" to "String"),
                ),
                "members" to listOf(
                    mapOf("name" to "In", "type" to "memory", "dataType" to "Double", "value" to 2),
                    mapOf("name" to "Out", "type" to "expression", "dataType" to "Double", "expression" to "{[.]In} * {Gain}"),
                ),
            )), actor = "test")
            fun tagCount() = gw.store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND).size
            val body = """{"typeId":"Types/Pump","folder":"Area1","pattern":{"template":"Pump_{n}","start":1,"count":3,"pad":2},
                "rows":[{"parameters":{"Device":"a","Gain":"3"}},{"parameters":{"Device":"b"}},{"parameters":{"Device":"c"}}]"""

            val view = client(gw, "view")
            val types = view.get("/api/v1/tags/udts")
            assertEquals(200, types.status, types.body)
            val type = (types.json["types"] as List<*>).single() as Map<*, *>
            assertEquals("Types/Pump", type["path"])
            assertEquals(2, type["memberCount"])
            assertEquals(listOf("Gain", "Device"), (type["parameters"] as List<*>).map { (it as Map<*, *>)["name"] })
            val denied = view.post("/api/v1/tags/udt-instances", "$body}")
            assertEquals(403, denied.status, denied.body)
            assertTrue((denied.json["detail"] as String).contains("tags.write"), denied.body)
            val deniedDry = view.post("/api/v1/tags/udt-instances", "$body,\"dryRun\":true}")
            assertEquals(403, deniedDry.status, deniedDry.body)
            assertEquals(0, tagCount())

            val op = client(gw, "op")
            val preview = op.post("/api/v1/tags/udt-instances", "$body,\"dryRun\":true}")
            assertEquals(200, preview.status, preview.body)
            assertEquals(3, preview.json["created"])
            assertEquals(false, preview.json["applied"])
            assertEquals(listOf("[default]Area1/Pump_01", "[default]Area1/Pump_02", "[default]Area1/Pump_03"),
                (preview.json["rows"] as List<*>).map { (it as Map<*, *>)["path"] })
            assertEquals(0, tagCount())

            val bad = op.post("/api/v1/tags/udt-instances",
                """{"typeId":"Types/Pump","folder":"Area2","rows":[{"name":"A","parameters":{"Device":"x"}},{"name":"B","parameters":{"Device":"y","Gain":"nope"}}]}""")
            assertEquals(422, bad.status, bad.body)
            assertTrue(bad.header("Content-Type")!!.startsWith("application/problem+json"))
            val badRows = (bad.json["rows"] as List<*>).map { it as Map<*, *> }
            assertEquals(listOf("create", "error"), badRows.map { it["status"] })
            assertTrue((bad.json["errors"] as List<*>).single().toString().startsWith("row 2 (B): parameter 'Gain'"), bad.body)
            assertEquals(0, tagCount())
            assertEquals(QualityCode.BAD_NOT_FOUND, gw.tags.read("Area2/A").quality.code)

            val created = op.post("/api/v1/tags/udt-instances", "$body}")
            assertEquals(201, created.status, created.body)
            assertEquals(3, created.json["created"])
            assertEquals(3, tagCount())
            assertEquals(6.0, (gw.tags.read("Area1/Pump_01/Out").value as Number).toDouble())
            assertEquals(2.0, (gw.tags.read("Area1/Pump_03/Out").value as Number).toDouble())
            assertEquals("op", gw.store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND).first().resource.metadata.modifiedBy)

            val again = op.post("/api/v1/tags/udt-instances", "$body}")
            assertEquals(422, again.status, again.body)
            assertEquals(3, tagCount())
            val skipped = op.post("/api/v1/tags/udt-instances", "$body,\"onCollision\":\"skip\"}")
            assertEquals(200, skipped.status, skipped.body)
            assertEquals(3, skipped.json["skipped"])
            assertEquals(3, tagCount())
            val renamed = op.post("/api/v1/tags/udt-instances", "$body,\"onCollision\":\"rename\"}")
            assertEquals(201, renamed.status, renamed.body)
            assertEquals(listOf("Pump_01_2", "Pump_02_2", "Pump_03_2"), (renamed.json["rows"] as List<*>).map { (it as Map<*, *>)["name"] })
            assertEquals(6, tagCount())

            val badPolicy = op.post("/api/v1/tags/udt-instances", "$body,\"onCollision\":\"merge\"}")
            assertEquals(422, badPolicy.status, badPolicy.body)

            val docs = Http(gw.port()).get("/api/docs").json["paths"] as Map<*, *>
            assertTrue("/api/v1/tags/udt-instances" in docs.keys)
            assertTrue("/api/v1/tags/udts" in docs.keys)
        } finally {
            gw.close()
        }
    }

    private fun client(gw: Gateway, user: String): Http {
        val http = Http(gw.port())
        assertEquals(200, http.login(user, TEST_PASSWORD).status)
        return http
    }
}
