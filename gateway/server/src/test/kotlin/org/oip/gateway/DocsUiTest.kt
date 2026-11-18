package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DocsUiTest {
    @TempDir lateinit var tmp: Path

    @Test fun manualLinksStayInsideTheDocsTree() {
        assertEquals("/docs/manual/getting-started", resolveLink("getting-started.md", "/docs/manual"))
        assertEquals("/docs/install/windows", resolveLink("../install/windows.md", "/docs/manual"))
        assertEquals("https://example.test/a", resolveLink("https://example.test/a", "/docs/manual"))
    }

    @Test fun aMarkdownPageResolvesWithOrWithoutTheExtension() {
        val manual = tmp.resolve("manual")
        java.nio.file.Files.createDirectories(manual)
        manual.resolve("getting-started.md").writeText("# Start\n\nSee [tags](gateway/tags.md).\n")
        val file = resolveDoc(tmp, "manual/getting-started")
        assertEquals(manual.resolve("getting-started.md"), file)
        val html = renderMarkdownPage(file!!.readText(), "/docs/manual/getting-started")
        assertTrue(html.contains("<h1>Start</h1>"))
        assertTrue(html.contains("href=\"/docs/manual/gateway/tags\""))
    }
}
