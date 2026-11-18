package org.oip.testinfra

import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Starts the test-infra compose stack, or attaches to one that is already up.
 *
 * Set OIP_SIM_STACK=external to attach to the documented host ports (after
 * `docker compose up`). Otherwise [start] uses Testcontainers' ComposeContainer,
 * which runs the repo's compose file with the local Docker Compose v2 binary
 * (`localCompose`, the File constructor) so image builds see test-infra/sim.
 */
class SimulatorStack internal constructor(
    val endpoints: SimEndpoints,
    private val stop: () -> Unit,
) : AutoCloseable {
    fun modbusControl() = SimControl("http://${endpoints.host}:${endpoints.modbusControl}")
    fun opcUaControl() = SimControl("http://${endpoints.host}:${endpoints.opcUaControl}")
    fun sparkplugControl() = SimControl("http://${endpoints.host}:${endpoints.sparkplugControl}")

    fun openModbus(direct: Boolean = false, timeoutMs: Int = 2_000) = ModbusTcpClient(
        endpoints.host,
        if (direct) endpoints.modbusDirect else endpoints.modbusProxy,
        timeoutMs = timeoutMs,
    )

    fun openMqtt(direct: Boolean = false, timeoutMs: Int = 5_000) = MqttSession(
        endpoints.host,
        if (direct) endpoints.mqttDirect else endpoints.mqttProxy,
        timeoutMs = timeoutMs,
    )

    fun toxiproxy() = ToxiproxyClient("http://${endpoints.host}:${endpoints.toxiproxy}")

    fun mailpit(direct: Boolean = false) = MailpitClient(
        httpBase = "http://${endpoints.host}:${endpoints.mailpitHttp}",
        smtpHost = endpoints.host,
        smtpPort = if (direct) endpoints.smtp else endpoints.smtpProxy,
    )

    fun cut(service: SimService) = toxiproxy().cut(service.proxyName)
    fun restore(service: SimService) = toxiproxy().restore(service.proxyName)

    /**
     * Stop the compose service that owns [publishedPort] (the simulator process, not Toxiproxy).
     * Returns the container id so [startComposeService] can bring the same container back.
     */
    fun stopComposeService(service: String, publishedPort: Int): String {
        val id = composeServiceId(service, publishedPort)
        docker("stop", "-t", "15", id)
        return id
    }

    fun startComposeService(id: String) {
        docker("start", id)
    }

    override fun close() = stop()

    companion object {
        fun fromEnvironment(): SimulatorStack {
            val mode = System.getenv("OIP_SIM_STACK")?.lowercase()
            val host = System.getenv("OIP_SIM_HOST") ?: "localhost"
            return if (mode == "external") attach(SimEndpoints.standard(host)) else start()
        }

        fun attach(endpoints: SimEndpoints = SimEndpoints.standard()): SimulatorStack =
            SimulatorStack(endpoints) {}

        fun start(): SimulatorStack {
            val composeFile = File(repoRoot(), "test-infra/compose.yaml")
            require(composeFile.isFile) { "missing $composeFile" }
            val timeout = Duration.ofMinutes(8)
            val compose = ComposeContainer(composeFile)
                .withBuild(true)
                .withRemoveVolumes(true)
                .withStartupTimeout(Duration.ofMinutes(12))
                .withExposedService("modbus", 5020, Wait.forListeningPort().withStartupTimeout(timeout))
                .withExposedService("modbus", 8080, Wait.forHttp("/health").forPort(8080).forStatusCode(200).withStartupTimeout(timeout))
                .withExposedService("opcua", 4840, Wait.forListeningPort().withStartupTimeout(timeout))
                .withExposedService("opcua", 8080, Wait.forHttp("/health").forPort(8080).forStatusCode(200).withStartupTimeout(timeout))
                .withExposedService("mosquitto", 1883, Wait.forListeningPort().withStartupTimeout(timeout))
                .withExposedService("sparkplug", 8080, Wait.forHttp("/health").forPort(8080).forStatusCode(200).withStartupTimeout(timeout))
                .withExposedService(
                    "timescale",
                    5432,
                    Wait.forLogMessage(".*database system is ready to accept connections.*", 2).withStartupTimeout(timeout),
                )
                .withExposedService("mailpit", 8025, Wait.forHttp("/livez").forPort(8025).forStatusCode(200).withStartupTimeout(timeout))
                .withExposedService("mailpit", 1025, Wait.forListeningPort().withStartupTimeout(timeout))
                .withExposedService("toxiproxy", 8474, Wait.forHttp("/version").forPort(8474).forStatusCode(200).withStartupTimeout(timeout))
                .withExposedService("toxiproxy", 5020, Wait.forListeningPort().withStartupTimeout(timeout))
                .withExposedService("toxiproxy", 4840, Wait.forListeningPort().withStartupTimeout(timeout))
                .withExposedService("toxiproxy", 1883, Wait.forListeningPort().withStartupTimeout(timeout))
                .withExposedService("toxiproxy", 5432, Wait.forListeningPort().withStartupTimeout(timeout))
                .withExposedService("toxiproxy", 1025, Wait.forListeningPort().withStartupTimeout(timeout))
            compose.start()
            val host = compose.getServiceHost("modbus", 8080)
            val endpoints = SimEndpoints(
                host = host,
                modbusProxy = compose.getServicePort("toxiproxy", 5020),
                modbusDirect = compose.getServicePort("modbus", 5020),
                modbusControl = compose.getServicePort("modbus", 8080),
                opcUaProxy = compose.getServicePort("toxiproxy", 4840),
                opcUaDirect = compose.getServicePort("opcua", 4840),
                opcUaControl = compose.getServicePort("opcua", 8080),
                mqttProxy = compose.getServicePort("toxiproxy", 1883),
                mqttDirect = compose.getServicePort("mosquitto", 1883),
                sparkplugControl = compose.getServicePort("sparkplug", 8080),
                toxiproxy = compose.getServicePort("toxiproxy", 8474),
                postgres = compose.getServicePort("timescale", 5432),
                postgresProxy = compose.getServicePort("toxiproxy", 5432),
                smtp = compose.getServicePort("mailpit", 1025),
                smtpProxy = compose.getServicePort("toxiproxy", 1025),
                mailpitHttp = compose.getServicePort("mailpit", 8025),
            )
            return SimulatorStack(endpoints) { compose.stop() }
        }
    }
}

/**
 * The compose service container for [service] in the project that publishes [publishedPort].
 * Testcontainers may map that port on a socat sidecar that has no compose labels. The sidecar
 * is not the process to stop; the service container in the same project is.
 */
private fun composeServiceId(service: String, publishedPort: Int): String {
    val listed = docker(
        "ps",
        "--format",
        "{{.ID}}\t{{.Ports}}\t{{.Label \"com.docker.compose.project\"}}\t{{.Label \"com.docker.compose.service\"}}",
    )
    return composeServiceIdFromListing(listed, service, publishedPort)
}

internal fun composeServiceIdFromListing(listed: String, service: String, publishedPort: Int): String {
    val rows = listed.lineSequence().map { it.trimEnd('\r', '\n') }.filter { it.isNotEmpty() }.map { line ->
        val fields = line.split('\t', limit = 4)
        PsRow(
            id = fields.getOrElse(0) { "" }.trim(),
            ports = fields.getOrElse(1) { "" },
            project = fields.getOrElse(2) { "" }.trim(),
            service = fields.getOrElse(3) { "" }.trim(),
        )
    }.toList()
    val needle = ":$publishedPort->"
    val publisher = rows.firstOrNull { needle in it.ports }
        ?: error("nothing publishes host port $publishedPort\n$listed")
    if (publisher.service == service && publisher.id.isNotEmpty()) return publisher.id
    val project = publisher.project.ifEmpty {
        val projects = rows.map { it.project }.filter { it.isNotEmpty() }.distinct()
        require(projects.size == 1) {
            "container publishing $publishedPort has no compose project; projects=$projects\n$listed"
        }
        projects.single()
    }
    val ids = rows.filter { it.project == project && it.service == service }.map { it.id }
    require(ids.size == 1) { "expected one $service container in $project, found $ids\n$listed" }
    return ids.single()
}

private data class PsRow(val id: String, val ports: String, val project: String, val service: String)

private fun docker(vararg args: String): String {
    val process = ProcessBuilder(listOf("docker", *args))
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("docker ${args.joinToString(" ")} timed out\n$text")
    }
    val code = process.exitValue()
    if (code != 0) error("docker ${args.joinToString(" ")} exited $code\n$text")
    return text
}

internal fun repoRoot(): File {
    System.getenv("OIP_REPO_ROOT")?.let { return File(it) }
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile && File(dir, "test-infra/compose.yaml").isFile) return dir
        dir = dir.parentFile
    }
    error("repo root not found from ${System.getProperty("user.dir")}")
}
