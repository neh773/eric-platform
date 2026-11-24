package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Field report (0.1.0, clean Windows VM): the service ran but no web port opened, because one part of startup
 * failed and took the whole start with it. An optional part that fails must leave the web pages up and say why.
 */
class StartupResilienceTest {
    @TempDir lateinit var tmp: Path

    @Test fun aFailingOptionalPartStillOpensTheWebPortAndSaysWhy() {
        // Another program already holds the OPC UA port: the 0.1.0 start threw here, before the web port opened.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            val dir = Files.createDirectories(tmp.resolve("data"))
            dir.resolve("gateway.yaml").writeText(
                "profile: standard\nopcua:\n  enabled: true\n  bindAddress: 127.0.0.1\n  port: ${taken.localPort}\ngatewayNetwork:\n  enabled: false\n",
            )
            StartupLog.open(dir)
            val gw = Gateway(dir, port = 0).start()
            try {
                val health = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("${gw.clientUrl()}/api/v1/health")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, health.statusCode())
                assertTrue(health.body().contains("OPC UA server did not start"), health.body())
                assertTrue(gw.startupProblems.any { it.startsWith("OPC UA server did not start") })
                val log = dir.resolve("logs").resolve("startup.log").readText()
                assertTrue(log.contains("FAILED OPC UA server"), log)
                assertTrue(log.contains("stage: started, listening on"), log)
            } finally {
                gw.close()
            }
        }
    }

    @Test fun startOptionalRecordsTheProblemAndKeepsGoing() {
        val problems = mutableListOf<String>()
        val warned = mutableListOf<String>()
        var after = false
        startOptional("example", problems, { warned += it }) { throw UnsatisfiedLinkError("no example.dll") }
        after = true
        assertTrue(after)
        assertEquals(listOf("example did not start: no example.dll"), problems)
        assertEquals(problems, warned)
    }
}
