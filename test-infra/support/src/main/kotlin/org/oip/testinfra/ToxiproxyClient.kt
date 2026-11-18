package org.oip.testinfra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Toxiproxy HTTP API. [cut] disables the named proxy (connections reset, new
 * connects refused). [restore] enables it again. A timeout toxic is available
 * for tests that need a hang instead of a reset.
 * https://github.com/Shopify/toxiproxy
 */
class ToxiproxyClient(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val mapper = ObjectMapper()

    fun version(): String {
        val body = get("/version")
        return body.trim().trim('"')
    }

    fun enabled(name: String): Boolean = proxy(name).path("enabled").asBoolean()

    fun cut(name: String) = setEnabled(name, false)

    fun restore(name: String) = setEnabled(name, true)

    fun setEnabled(name: String, enabled: Boolean) {
        val node = proxy(name).deepCopy<ObjectNode>()
        node.put("enabled", enabled)
        node.remove("toxics")
        val response = send("POST", "/proxies/$name", mapper.writeValueAsString(node))
        if (response.statusCode() !in 200..299) {
            error("toxiproxy update $name -> ${response.statusCode()}: ${response.body()}")
        }
    }

    /**
     * Hold bytes for [timeoutMs] (0 waits forever) to imitate a black-hole link.
     * [stream] is `downstream` (server to client) or `upstream` (client to server).
     */
    fun addTimeout(name: String, toxicName: String, timeoutMs: Long, stream: String = "downstream") {
        val body = mapper.createObjectNode()
            .put("name", toxicName)
            .put("type", "timeout")
            .put("stream", stream)
        body.putObject("attributes").put("timeout", timeoutMs)
        val response = send("POST", "/proxies/$name/toxics", mapper.writeValueAsString(body))
        if (response.statusCode() !in 200..299) {
            error("toxiproxy toxic $name -> ${response.statusCode()}: ${response.body()}")
        }
    }

    /** Black-hole both directions. Removing the toxics lets the same TCP session continue. */
    fun blackHole(name: String) {
        addTimeout(name, "block-down", 0, "downstream")
        addTimeout(name, "block-up", 0, "upstream")
    }

    fun clearBlackHole(name: String) {
        removeToxic(name, "block-down")
        removeToxic(name, "block-up")
    }

    fun removeToxic(name: String, toxicName: String) {
        val response = send("DELETE", "/proxies/$name/toxics/$toxicName", null)
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            error("toxiproxy delete toxic -> ${response.statusCode()}: ${response.body()}")
        }
    }

    private fun proxy(name: String): JsonNode {
        val root = mapper.readTree(get("/proxies"))
        return root.get(name) ?: error("toxiproxy has no proxy named $name in $root")
    }

    private fun get(path: String): String {
        val response = send("GET", path, null)
        if (response.statusCode() !in 200..299) error("toxiproxy $path -> ${response.statusCode()}: ${response.body()}")
        return response.body()
    }

    private fun send(method: String, path: String, body: String?): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(5))
            .method(method, publisher)
        if (body != null) builder.header("Content-Type", "application/json")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
