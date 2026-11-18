package org.oip.db

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Duration

/**
 * A PostgreSQL server for tests: the compose Timescale service when it already answers on
 * `OIP_SIM_POSTGRES_PORT` (default 25432), otherwise one container per test JVM started here (CI has
 * Docker). With neither, [require] skips the test. The image is the one pinned in `test-infra/compose.yaml`.
 */
object PostgresServer {
    private const val IMAGE =
        "timescale/timescaledb:2.31.0-pg16-oss@sha256:1bfcf994089b9c97b4ddfa07f2ff9eec430709715e419d9a6957d1a4b4614cd1"
    const val USER = "oip"
    const val PASSWORD = "oip"

    private fun reachable(url: String): Boolean =
        runCatching { DriverManager.getConnection(url, USER, PASSWORD).use { it.isValid(2) } }.getOrDefault(false)

    /** JDBC URL of a running server, or null when there is none and Docker is not available. */
    val url: String? by lazy {
        val port = System.getenv("OIP_SIM_POSTGRES_PORT")?.toIntOrNull() ?: 25432
        val compose = "jdbc:postgresql://127.0.0.1:$port/oip"
        if (reachable(compose)) return@lazy compose
        val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!docker) return@lazy null
        val container = GenericContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withExposedPorts(5432)
            .withEnv(mapOf("POSTGRES_USER" to USER, "POSTGRES_PASSWORD" to PASSWORD, "POSTGRES_DB" to "oip", "TIMESCALEDB_TELEMETRY" to "off"))
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2).withStartupTimeout(Duration.ofMinutes(3)))
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { container.stop() } })
        "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/oip"
    }

    fun require(): String {
        val found = url
        assumeTrue(found != null, "no PostgreSQL: start the compose stack or run where Docker is available")
        return found!!
    }
}
