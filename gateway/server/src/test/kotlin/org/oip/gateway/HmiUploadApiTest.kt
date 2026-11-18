package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.security.DefaultRoles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

/**
 * HMP-047: a session uploads a file to a project script hook that declares an `upload` block. The gateway enforces the
 * size limit, the allowed content types and the signed-in user, and writes the file nowhere itself.
 */
class HmiUploadApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway

    @BeforeEach fun setUp() {
        tmp.createDirectories()
        tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
        gw = Gateway(tmp, port = 0, adminDist = null, webDist = null)
        val id = ResourceId(DESIGNER_MODULE, Scope.project("plant"), SCRIPT_HOOKS_KIND, SCRIPT_HOOKS_RESOURCE)
        val hooks = mapOf(
            "importCsv" to mapOf(
                "source" to "return {'name': event.payload.filename, 'type': event.payload.contentType, 'size': event.payload.size, 'encoded': len(event.payload.data), 'by': component.user}",
                "upload" to mapOf("maxBytes" to 100, "contentTypes" to listOf("text/csv", "image/*")),
            ),
            "plain" to mapOf("source" to "return 1"),
            "noTypes" to mapOf("source" to "return 1", "upload" to mapOf("maxBytes" to 100)),
            "huge" to mapOf("source" to "return event.payload.size", "upload" to mapOf("maxBytes" to 999_999_999, "contentTypes" to listOf("application/octet-stream"))),
            "boom" to mapOf("source" to "raise ValueError('bad file')", "upload" to mapOf("contentTypes" to listOf("text/plain"))),
        )
        gw.store.put(Scope.project("plant"), Resource(SCRIPT_HOOKS_KIND, Metadata(SCRIPT_HOOKS_RESOURCE, DESIGNER_MODULE, version = gw.store.get(id)?.metadata?.version ?: 0L), mapOf("hooks" to hooks)))
        gw.start()
        gw.createUser("ada", setOf(DefaultRoles.OPERATOR))
    }

    @AfterEach fun tearDown() = gw.close()

    private fun signedIn(): Http = Http(gw.port()).also { assertEquals(200, it.login("ada", TEST_PASSWORD).status) }

    private fun Http.upload(hook: String, bytes: ByteArray, type: String, name: String? = "data.csv", project: String = "plant"): Http.Res =
        send("POST", "/api/v1/hmi/projects/$project/upload/$hook", bytes, type, if (name == null) emptyMap() else mapOf("X-File-Name" to name))

    private fun filesUnder(root: Path): Set<Path> = Files.walk(root).use { s -> s.filter { it.isRegularFile() }.toList().toSet() }

    @Test fun anAllowedFileReachesTheHandlerAndNothingIsWrittenByTheGateway() {
        val http = signedIn()
        val marker = "MARKER-8f31c2-line"
        val body = "a,b\n$marker,2\n".toByteArray()
        val before = filesUnder(tmp)
        val ok = http.upload("importCsv", body, "text/csv; charset=utf-8", name = "..\\..\\evil/../report.csv")
        assertEquals(200, ok.status, ok.body)
        @Suppress("UNCHECKED_CAST") val value = ok.json["value"] as Map<String, Any?>
        assertEquals("report.csv", value["name"], "the handler sees a plain file name, never a path")
        assertEquals("text/csv", value["type"])
        assertEquals(body.size, (value["size"] as Number).toInt())
        assertEquals("ada", value["by"])
        assertTrue((value["encoded"] as Number).toInt() >= body.size)
        val after = filesUnder(tmp) - before
        assertTrue(after.none { runCatching { String(it.readBytes()).contains(marker) }.getOrDefault(false) }, "the file is not stored: $after")
        assertEquals(200, http.upload("importCsv", ByteArray(10) { 1 }, "image/png", name = "x.png").status, "type/* matches")
    }

    @Test fun sizeTypeAndHandlerLimitsAreEnforcedOnTheGateway() {
        val http = signedIn()
        val big = ByteArray(101) { 'a'.code.toByte() }
        assertEquals(413, http.upload("importCsv", big, "text/csv").status)
        assertEquals(200, http.upload("importCsv", big.copyOf(100), "text/csv").status, "exactly the limit passes")
        assertEquals(415, http.upload("importCsv", "x".toByteArray(), "application/x-msdownload").status)
        assertEquals(415, http.upload("importCsv", "x".toByteArray(), "text/csv2").status)
        assertEquals(400, http.upload("importCsv", ByteArray(0), "text/csv").status)
        assertEquals(400, http.upload("importCsv", "x".toByteArray(), "text/csv", name = null).status)
        assertEquals(404, http.upload("plain", "x".toByteArray(), "text/plain").status, "a hook without an upload block takes no file")
        assertEquals(404, http.upload("noTypes", "x".toByteArray(), "text/plain").status, "an upload block needs allowed types")
        assertEquals(404, http.upload("missing", "x".toByteArray(), "text/plain").status)
        assertEquals(404, http.upload("importCsv", "x".toByteArray(), "text/csv", project = "nope").status)
        // A hook's own maxBytes never lifts the gateway ceiling.
        assertEquals(413, http.upload("huge", ByteArray((HmiUploadApi.CEILING + 1).toInt()), "application/octet-stream").status)
        val failed = http.upload("boom", "x".toByteArray(), "text/plain")
        assertEquals(422, failed.status, failed.body)
        assertTrue(failed.body.contains("bad file"), failed.body)
    }

    @Test fun anAnonymousCallerAnApiKeyAndAMissingCsrfTokenAreRefused() {
        assertEquals(401, Http(gw.port()).upload("importCsv", "x".toByteArray(), "text/csv").status)
        val http = signedIn()
        http.sendCsrf = false
        assertEquals(403, http.upload("importCsv", "x".toByteArray(), "text/csv").status)
        http.sendCsrf = true
        assertEquals(200, http.upload("importCsv", "x".toByteArray(), "text/csv").status)
        assertEquals("tags.read", GatewayAuth.explicitRule("POST", "/api/v1/hmi/projects/plant/upload/importCsv"))
    }
}
