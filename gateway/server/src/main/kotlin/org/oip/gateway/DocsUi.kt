package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Serves the packaged manual at `/docs` and `/manual`.
 * Markdown is rendered to HTML. Other files (images, and the pages the manual links) are served as themselves.
 * The tree comes from `OIP_DOCS_DIR`, the `docs` folder beside the admin bundle, or `./docs` when that holds `manual/`.
 */
class DocsUi(private val root: Path?) {
    fun register(app: Javalin) {
        app.get("/docs") { ctx -> page(ctx, "") }
        app.get("/docs/") { ctx -> page(ctx, "") }
        app.get("/docs/<path>") { ctx -> page(ctx, ctx.pathParam("path")) }
        app.get("/manual") { ctx -> page(ctx, "manual") }
        app.get("/manual/") { ctx -> page(ctx, "manual") }
        app.get("/manual/<path>") { ctx -> page(ctx, "manual/" + ctx.pathParam("path")) }
    }

    private fun page(ctx: Context, relative: String) {
        val base = root?.takeIf { it.isDirectory() } ?: throw Problems.notFound("manual is not installed")
        val rel = relative.trim().trim('/')
        val file = resolveDoc(base, if (rel.isEmpty()) "manual/index.md" else rel)
            ?: throw Problems.notFound("not found")
        ctx.header("Cache-Control", "no-cache")
        if (file.fileName.toString().endsWith(".md", ignoreCase = true)) {
            val urlPath = if (relative.startsWith("manual") || relative.isEmpty()) {
                "/docs/" + (if (rel.isEmpty()) "manual/index" else rel.removeSuffix(".md"))
            } else {
                "/docs/" + rel.removeSuffix(".md")
            }
            ctx.status(200).contentType("text/html; charset=utf-8").result(renderMarkdownPage(file.readText(), urlPath))
            return
        }
        ctx.status(200).contentType(AdminUi.contentType(file)).result(file.readBytes())
    }

    companion object {
        fun locate(
            env: Map<String, String> = System.getenv(),
            property: String? = System.getProperty("oip.docs.dir"),
        ): Path? {
            val raw = property?.takeIf { it.isNotBlank() } ?: env["OIP_DOCS_DIR"]?.takeIf { it.isNotBlank() }
            if (!raw.isNullOrBlank()) {
                val path = Path.of(raw).toAbsolutePath().normalize()
                if (path.isDirectory()) return path
            }
            val admin = env["OIP_ADMIN_DIST"]?.takeIf { it.isNotBlank() }
            if (admin != null) {
                val sibling = Path.of(admin).parent?.resolve("docs")?.toAbsolutePath()?.normalize()
                if (sibling != null && sibling.isDirectory()) return sibling
            }
            val local = Path.of("docs").toAbsolutePath().normalize()
            return if (local.resolve("manual").isDirectory()) local else null
        }
    }
}

internal fun resolveDoc(root: Path, relative: String): Path? {
    val cleaned = relative.trim().trim('/')
    val candidates = if (cleaned.isEmpty()) listOf("manual/index.md") else listOf(
        cleaned,
        "$cleaned.md",
        "$cleaned/index.md",
    )
    for (candidate in candidates) {
        val file = AdminUi.resolve(root, candidate) ?: continue
        if (file.isRegularFile()) return file
    }
    return null
}

internal fun renderMarkdownPage(markdown: String, pageUrl: String): String {
    val body = renderMarkdown(markdown, pageUrl.substringBeforeLast('/', "/docs"))
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>ERIC manual</title>
          <style>
            body { font: 16px/1.5 Segoe UI, sans-serif; margin: 2rem auto; max-width: 48rem; color: #1c1c1c; }
            code, pre { font-family: Consolas, monospace; background: #f4f4f4; }
            pre { padding: 0.75rem; overflow: auto; }
            table { border-collapse: collapse; }
            td, th { border: 1px solid #ccc; padding: 0.3rem 0.6rem; }
            img { max-width: 100%; }
          </style>
        </head>
        <body>
        $body
        <p><a href="/docs">Manual home</a></p>
        </body>
        </html>
    """.trimIndent()
}

internal fun renderMarkdown(markdown: String, directoryUrl: String): String {
    val out = StringBuilder()
    val lines = markdown.replace("\r\n", "\n").lines()
    var i = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            out.append("<p>").append(inline(paragraph.toString().trim(), directoryUrl)).append("</p>\n")
            paragraph.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("```")) {
            flushParagraph()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) {
                code.append(escape(lines[i])).append('\n')
                i++
            }
            out.append("<pre><code>").append(code).append("</code></pre>\n")
            i++
            continue
        }
        if (line.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("""^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*$"""))) {
            flushParagraph()
            out.append(renderTable(lines, i, directoryUrl))
            while (i < lines.size && lines[i].startsWith("|")) i++
            continue
        }
        val heading = Regex("""^(#{1,6})\s+(.*)$""").find(line)
        if (heading != null) {
            flushParagraph()
            val level = heading.groupValues[1].length
            out.append("<h$level>").append(inline(heading.groupValues[2], directoryUrl)).append("</h$level>\n")
            i++
            continue
        }
        if (line.matches(Regex("""^\s*([-*]|\d+\.)\s+.*"""))) {
            flushParagraph()
            val ordered = line.trimStart().first().isDigit()
            val tag = if (ordered) "ol" else "ul"
            out.append("<$tag>\n")
            while (i < lines.size && lines[i].matches(Regex("""^\s*([-*]|\d+\.)\s+.*"""))) {
                val item = lines[i].replace(Regex("""^\s*([-*]|\d+\.)\s+"""), "")
                out.append("<li>").append(inline(item, directoryUrl)).append("</li>\n")
                i++
            }
            out.append("</$tag>\n")
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(line.trim())
        i++
    }
    flushParagraph()
    return out.toString()
}

private fun renderTable(lines: List<String>, start: Int, directoryUrl: String): String {
    val rows = mutableListOf<List<String>>()
    var i = start
    while (i < lines.size && lines[i].startsWith("|")) {
        if (!lines[i].trim().matches(Regex("""^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*$"""))) {
            rows += lines[i].trim().trim('|').split('|').map { it.trim() }
        }
        i++
    }
    val html = StringBuilder("<table>\n")
    rows.forEachIndexed { index, cells ->
        val tag = if (index == 0) "th" else "td"
        html.append("<tr>")
        cells.forEach { html.append("<$tag>").append(inline(it, directoryUrl)).append("</$tag>") }
        html.append("</tr>\n")
    }
    html.append("</table>\n")
    return html.toString()
}

private fun inline(text: String, directoryUrl: String): String {
    var rest = escape(text)
    rest = Regex("""!\[([^]]*)]\(([^)]+)\)""").replace(rest) { match ->
        val alt = match.groupValues[1]
        val src = resolveLink(unescape(match.groupValues[2]), directoryUrl)
        """<img alt="$alt" src="$src">"""
    }
    rest = Regex("""\[([^]]+)]\(([^)]+)\)""").replace(rest) { match ->
        val label = match.groupValues[1]
        val href = resolveLink(unescape(match.groupValues[2]), directoryUrl)
        """<a href="$href">$label</a>"""
    }
    rest = Regex("""\*\*([^*]+)\*\*""").replace(rest) { "<strong>${it.groupValues[1]}</strong>" }
    rest = Regex("""`([^`]+)`""").replace(rest) { "<code>${it.groupValues[1]}</code>" }
    return rest
}

internal fun resolveLink(raw: String, directoryUrl: String): String {
    val url = raw.trim()
    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:") || url.startsWith("#")) return escape(url)
    val hash = url.substringAfter('#', "")
    val path = url.substringBefore('#')
    val base = if (directoryUrl.endsWith("/")) directoryUrl else "$directoryUrl/"
    val combined = java.net.URI(base).resolve(path).normalize().path
    val stripped = if (combined.endsWith(".md")) combined.removeSuffix(".md") else combined
    val suffix = if (hash.isEmpty()) "" else "#$hash"
    return escape(stripped + suffix)
}

private fun escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun unescape(text: String): String = text
    .replace("&quot;", "\"")
    .replace("&gt;", ">")
    .replace("&lt;", "<")
    .replace("&amp;", "&")
