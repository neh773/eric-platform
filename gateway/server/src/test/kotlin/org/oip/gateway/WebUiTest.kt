package org.oip.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** H-1: session page URLs under `/hmi/<project>/` load the shell on reload, even with a dot in the last segment. */
class WebUiTest {
    @TempDir lateinit var tmp: Path

    @Test fun pageUrlsBelowAProjectLoadTheShellEvenWithADot() {
        val dist = tmp.resolve("dist").createDirectories()
        dist.resolve("assets").createDirectories().resolve("index-abc.js").writeText("console.log('oip-hmi')")
        dist.resolve("index.html").writeText("<!doctype html><div id=\"root\" data-oip-web=\"1\"></div>")
        val dir = tmp.resolve("gw").createDirectories()
        dir.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n")
        Gateway(dir, port = 0, adminDist = null, webDist = dist).use { gw ->
            gw.start()
            val http = Http(gw.port())
            for (path in listOf("/hmi/plant/line/3.5", "/hmi/plant/v1.2", "/hmi/plant/v1.2/", "/hmi/plant/pumps?x=1.5", "/hmi/_sample/symbols")) {
                val page = http.get(path)
                assertEquals(200, page.status, "$path ${page.body}")
                assertTrue(page.body.contains("data-oip-web"), path)
                assertTrue(page.header("Content-Type")!!.contains("text/html"), path)
            }
            // A file-like name directly under /hmi, or anywhere under /studio, is still a missing file.
            assertEquals(404, http.get("/hmi/missing.png").status)
            assertEquals(404, http.get("/studio/missing.js").status)
            assertEquals(404, http.get("/studio/a/b.css").status)
            val designer = http.get("/designer")
            assertEquals(200, designer.status)
            assertTrue(designer.body.contains("data-oip-web"))
            assertEquals(404, http.get("/designer/missing.js").status)
            assertEquals(200, http.get("/assets/index-abc.js").status)
        }
    }
}
