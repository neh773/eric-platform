package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TAG_KIND
import org.oip.tags.UDT_KIND
import java.nio.file.Path

/**
 * Review-2 F4: tag configuration export and import (TAG-005) through REST.
 */
class TagExchangeApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var admin: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        admin = Http(gw.port())
        gw.loginAdmin(admin)
        gw.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("plant-speed", TAGS_MODULE), mapOf(
            "path" to "Plant/Speed", "type" to "memory", "dataType" to "Double", "value" to 3.5, "documentation" to "line speed",
        )))
        gw.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("motor", TAGS_MODULE), mapOf(
            "path" to "Types/Motor",
            "parameters" to listOf(mapOf("name" to "Gain", "dataType" to "Double", "default" to 2)),
            "members" to listOf(
                mapOf("name" to "In", "type" to "memory", "dataType" to "Double", "value" to 3),
                mapOf("name" to "Out", "type" to "expression", "dataType" to "Double", "expression" to "{[.]In} * {Gain}"),
            ),
        )))
        gw.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("motor-1", TAGS_MODULE), mapOf(
            "path" to "Line/M1", "type" to "udt", "typeId" to "Types/Motor", "parameters" to mapOf("Gain" to 4),
        )))
    }

    @AfterEach fun tearDown() = gw.close()

    private fun tagId(name: String) = ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, name)

    private fun export(): String {
        val res = admin.get("/api/v1/tags/export")
        assertEquals(200, res.status, res.body)
        return res.body
    }

    private fun import(doc: String, policy: String): Http.Res = admin.post("/api/v1/tags/import?policy=$policy", doc)

    private fun wipe() {
        gw.store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND).forEach { gw.store.delete(it.id) }
        gw.store.list(TAGS_MODULE, Scope.GATEWAY, UDT_KIND).forEach { gw.store.delete(it.id) }
    }

    @Test fun exportWipeImportGivesAByteIdenticalExport() {
        val first = export()
        assertEquals(first, export(), "two exports of the same configuration are byte-identical")
        wipe()
        assertTrue(gw.store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND).isEmpty())
        assertNull(gw.tags.read("Plant/Speed").value)

        val imported = import(first, "overwrite")
        assertEquals(200, imported.status, imported.body)
        assertEquals(3, (imported.json["created"] as List<*>).size, imported.body)
        assertEquals(first, export())
        // The imported configuration is live, UDT instance included.
        assertEquals(3.5, (gw.tags.read("Plant/Speed").value as Number).toDouble(), 0.0)
        assertEquals(12.0, (gw.tags.read("Line/M1/Out").value as Number).toDouble(), 0.0)
    }

    /** The read-only system provider exports and imports its alarm configuration (ALA-040). */
    @Test fun systemTagAlarmsExportAndImport() {
        val doc = """{"version":1,"provider":"system","udts":[],"tags":[{"name":"sys-memory","spec":{"provider":"system","path":"Gateway/Performance/Memory Utilization","type":"memory","dataType":"Double","alarms":[{"name":"HighMemory","mode":"AboveValue","setpointA":0.9,"priority":"High"}]}}]}"""
        val imported = admin.post("/api/v1/tags/import?provider=system&policy=overwrite", doc)
        assertEquals(200, imported.status, imported.body)
        val exported = admin.get("/api/v1/tags/export?provider=system")
        assertEquals(200, exported.status, exported.body)
        assertTrue(exported.body.contains("HighMemory"), exported.body)
        assertFalse(export().contains("HighMemory"), "the default provider export does not carry system tag alarms")
        val config = gw.tags.copyTagConfigs().first { it.first == "system" && it.second.path == "Gateway/Performance/Memory Utilization" }.second
        assertTrue((config.raw["alarms"] as List<*>).isNotEmpty())
        assertEquals(422, admin.post("/api/v1/tags/import?provider=system", doc.replace("\"alarms\"", "\"value\":1,\"alarms\"")).status)
        assertEquals(404, admin.get("/api/v1/tags/export?provider=nosuch").status)
    }

    @Test fun eachCollisionPolicy() {
        val doc = export()
        fun edit() = gw.store.put(Scope.GATEWAY, gw.store.get(tagId("plant-speed"))!!.let { r ->
            Resource(TAG_KIND, r.metadata, r.spec + mapOf("documentation" to "edited", "engUnit" to "m/s"))
        })

        // ignore: the existing tag is kept as it is.
        edit()
        val ignored = import(doc, "ignore")
        assertEquals(200, ignored.status, ignored.body)
        assertEquals(3, (ignored.json["skipped"] as List<*>).size, ignored.body)
        assertEquals("edited", gw.store.get(tagId("plant-speed"))!!.spec["documentation"])

        // merge: imported keys replace, keys only the existing tag has stay.
        val merged = import(doc, "merge")
        assertEquals(200, merged.status, merged.body)
        val afterMerge = gw.store.get(tagId("plant-speed"))!!.spec
        assertEquals("line speed", afterMerge["documentation"])
        assertEquals("m/s", afterMerge["engUnit"])

        // overwrite: the tag is the imported one, extra keys gone.
        edit()
        val overwritten = import(doc, "overwrite")
        assertEquals(200, overwritten.status, overwritten.body)
        val afterOverwrite = gw.store.get(tagId("plant-speed"))!!.spec
        assertEquals("line speed", afterOverwrite["documentation"])
        assertNull(afterOverwrite["engUnit"])
        assertEquals(doc, export())

        assertEquals(400, import(doc, "replace-all").status)
    }

    @Test fun oneInvalidEntryWritesNothing() {
        // The instance names a UDT that neither the document nor the gateway defines.
        val doc = Regex("\"typeId\"\\s*:\\s*\"Types/Motor\"").replace(export(), "\"typeId\" : \"Types/Missing\"")
        assertTrue(doc.contains("Types/Missing"), doc)
        wipe()
        val res = import(doc, "overwrite")
        assertEquals(422, res.status, res.body)
        assertTrue((res.json["failed"] as List<*>).isNotEmpty(), res.body)
        assertTrue(gw.store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND).isEmpty())
        assertTrue(gw.store.list(TAGS_MODULE, Scope.GATEWAY, UDT_KIND).isEmpty())
        assertEquals(422, import("not json", "overwrite").status)
    }

    @Test fun exportNeedsGatewayReadAndImportNeedsGatewayWrite() {
        val doc = export()
        gw.createUser("vic", setOf(DefaultRoles.VIEWER))
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
        val vic = Http(gw.port()).also { assertEquals(200, it.login("vic", TEST_PASSWORD).status) }
        val ada = Http(gw.port()).also { assertEquals(200, it.login("ada", TEST_PASSWORD).status) }
        assertEquals(403, vic.get("/api/v1/tags/export").status)
        assertEquals(200, ada.get("/api/v1/tags/export").status)
        assertEquals(403, ada.post("/api/v1/tags/import?policy=overwrite", doc).status)
        assertEquals(401, Http(gw.port()).get("/api/v1/tags/export").status)
        assertEquals("gateway.read", GatewayAuth.explicitRule("GET", "/api/v1/tags/export"))
        assertEquals("gateway.write", GatewayAuth.explicitRule("POST", "/api/v1/tags/import"))
    }
}
