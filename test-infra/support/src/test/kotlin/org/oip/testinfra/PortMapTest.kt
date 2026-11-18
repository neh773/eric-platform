package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PortMapTest {
    @Test fun composePublishesTheDocumentedPortMap() {
        val text = File(repoRoot(), "test-infra/compose.yaml").readText()
        val published = parsePublishedPorts(text)
        assertEquals(listOf(15020 to 5020, 15021 to 8080), published["modbus"])
        assertEquals(listOf(14840 to 4840, 14841 to 8080), published["opcua"])
        assertEquals(listOf(11883 to 1883), published["mosquitto"])
        assertEquals(listOf(15030 to 8080), published["sparkplug"])
        assertEquals(listOf(25432 to 5432), published["timescale"])
        assertEquals(listOf(1025 to 1025, 8025 to 8025), published["mailpit"])
        assertEquals(
            listOf(8474 to 8474, 5020 to 5020, 4840 to 4840, 1883 to 1883, 15432 to 5432, 11025 to 1025),
            published["toxiproxy"],
        )
        assertEquals(SimPorts.MODBUS_PROXY, 5020)
        assertEquals(SimPorts.OPCUA_PROXY, 4840)
        assertEquals(SimPorts.MQTT_PROXY, 1883)
        assertEquals(SimPorts.POSTGRES, 25432)
        assertEquals(25432, SimPorts.postgresHostPort { null })
        assertEquals(25432, SimPorts.postgresHostPort { "" })
        assertEquals(56000, SimPorts.postgresHostPort { if (it == "OIP_SIM_POSTGRES_PORT") "56000" else null })
        assertEquals(SimPorts.SMTP, 1025)
        assertEquals(SimPorts.MAILPIT_HTTP, 8025)
        assertEquals(SimPorts.TOXIPROXY, 8474)
    }

    @Test fun everyPublishedHostPortIsOutsideTheEphemeralRange() {
        // Linux hands 32768-60999 to outbound connections; a published port there can already be held by a client
        // socket on the CI runner, and the stack then fails to start with "address already in use".
        val published = parsePublishedPorts(File(repoRoot(), "test-infra/compose.yaml").readText())
        val inRange = published.flatMap { (service, ports) -> ports.map { service to it.first } }
            .filter { (_, host) -> host >= EPHEMERAL_LOW }
        assertEquals(emptyList<Pair<String, Int>>(), inRange)
        assertTrue(SimPorts.POSTGRES < EPHEMERAL_LOW)
    }

    private companion object {
        const val EPHEMERAL_LOW = 32768
    }
}

/** Parses the `ports:` lists in test-infra/compose.yaml. The file's layout is part of the contract. */
internal fun parsePublishedPorts(compose: String): Map<String, List<Pair<Int, Int>>> {
    val services = linkedMapOf<String, MutableList<Pair<Int, Int>>>()
    var current: String? = null
    var inPorts = false
    val service = Regex("^  ([a-z0-9-]+):\\s*$")
    val portsHeader = Regex("^    ports:\\s*$")
    // Plain "15020:5020", or "${OIP_SIM_POSTGRES_PORT:-25432}:5432" (the default is the contract).
    val port = Regex("^      - \"(?:\\$\\{[A-Z0-9_]+:-)?(\\d+)}?:(\\d+)\"\\s*$")
    for (line in compose.lineSequence()) {
        val serviceMatch = service.matchEntire(line)
        if (serviceMatch != null) {
            current = serviceMatch.groupValues[1]
            inPorts = false
            services.putIfAbsent(current, mutableListOf())
            continue
        }
        if (current != null && portsHeader.matches(line)) {
            inPorts = true
            continue
        }
        if (!inPorts) continue
        val portMatch = port.matchEntire(line)
        if (portMatch != null) {
            services.getValue(current!!).add(portMatch.groupValues[1].toInt() to portMatch.groupValues[2].toInt())
        } else if (line.startsWith("    ") && !line.startsWith("      ")) {
            inPorts = false
        }
    }
    return services
}
