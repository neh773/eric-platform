package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.backup.ZipIo
import org.oip.config.Format
import org.oip.config.ResourceCodec
import java.nio.file.Path

/**
 * PRO-003 over HTTP: export, import into an empty gateway, skip and overwrite.
 */
class ProjectApiTest {
    @TempDir lateinit var tmp: Path

    @Test fun exportImportSkipAndOverwrite() {
        val srcDir = tmp.resolve("src")
        val zip = Gateway(srcDir, port = 0).use { g ->
            g.store.registry.register("m", "Thing", schema())
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            assertEquals(201, http.put("/api/v1/config/m/project/plant/Thing/main", resource(version = 0, x = 4)).status)
            assertEquals(201, http.put("/api/v1/config/m/project/plant/Thing/extra", resource("extra", 0, 2)).status)
            val exported = http.get("/api/v1/projects/plant/export")
            assertEquals(200, exported.status, exported.body)
            assertEquals('P'.code.toByte(), exported.raw[0])
            assertEquals(404, http.get("/api/v1/projects/missing/export").status)
            exported.raw
        }
        assertTrue(ZipIo.read(zip).keys.any { it.startsWith("files/") })

        Gateway(tmp.resolve("dest"), port = 0).use { g ->
            g.store.registry.register("m", "Thing", schema())
            g.start()
            val http = Http(g.port())
            g.loginAdmin(http)
            assertEquals(201, http.put("/api/v1/config/m/project/plant/Thing/main", resource(version = 0, x = 1)).status)
            val missingPolicy = http.postRaw("/api/v1/projects/plant/import", zip, "application/zip")
            assertEquals(422, missingPolicy.status)
            assertTrue(missingPolicy.header("Content-Type")!!.contains("problem+json"))
            val skipped = http.postRaw("/api/v1/projects/plant/import?policy=skip", zip, "application/zip")
            assertEquals(200, skipped.status, skipped.body)
            assertEquals(1, (skipped.json["created"] as Number).toInt())
            assertEquals(1, (skipped.json["skipped"] as Number).toInt())
            assertEquals(0, (skipped.json["overwritten"] as Number).toInt())
            assertEquals(1, ResourceCodec.decode(http.get("/api/v1/config/m/project/plant/Thing/main").body, Format.JSON).spec["x"])
            assertEquals(2, ResourceCodec.decode(http.get("/api/v1/config/m/project/plant/Thing/extra").body, Format.JSON).spec["x"])
            val replaced = http.postRaw("/api/v1/projects/plant/import?policy=overwrite", zip, "application/zip")
            assertEquals(2, (replaced.json["overwritten"] as Number).toInt())
            assertEquals(4, ResourceCodec.decode(http.get("/api/v1/config/m/project/plant/Thing/main").body, Format.JSON).spec["x"])
            assertEquals(listOf("plant"), (http.get("/api/v1/projects").json["projects"] as List<*>))
            // SEC-003: each import that changed something is one audit record naming the project and policy.
            val imports = g.security.audit.query(org.oip.security.AuditQuery(action = org.oip.security.AuditActions.PROJECT_IMPORTED, limit = 10)).records
            assertEquals(listOf("policy=overwrite;created=0,updated=2", "policy=skip;created=1,updated=0"), imports.map { it.detail.substringBefore(",deleted") })
            assertTrue(imports.all { it.actor == "admin" && it.target == "project/plant" }, imports.toString())
        }
    }

    private fun schema() = mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer")))
    private fun resource(name: String = "main", version: Long, x: Int) =
        """{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"$name","module":"m","version":$version},"spec":{"x":$x}}"""
}
