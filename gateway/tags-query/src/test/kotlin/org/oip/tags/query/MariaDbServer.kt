package org.oip.tags.query

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Duration

/**
 * A MariaDB server for tests: one already answering on `OIP_SIM_MARIADB_PORT` (default 23306), otherwise one
 * container per test JVM started here (CI has Docker). With neither, [require] skips the test.
 */
object MariaDbServer {
    private const val IMAGE = "mariadb:10.11"
    const val USER = "oip"
    const val PASSWORD = "oippw"

    private fun reachable(url: String): Boolean =
        runCatching { DriverManager.getConnection(url, USER, PASSWORD).use { it.isValid(2) } }.getOrDefault(false)

    /** `host:port` of a running server, or null when there is none and Docker is not available. */
    val hostPort: String? by lazy {
        val port = System.getenv("OIP_SIM_MARIADB_PORT")?.toIntOrNull() ?: 23306
        runCatching { Class.forName("org.drizzle.jdbc.DrizzleDriver") }
        if (reachable("jdbc:mysql:thin://127.0.0.1:$port/oip")) return@lazy "127.0.0.1:$port"
        val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!docker) return@lazy null
        val container = GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(3306)
            .withEnv(mapOf("MARIADB_ROOT_PASSWORD" to "rootpw", "MARIADB_DATABASE" to "oip", "MARIADB_USER" to USER, "MARIADB_PASSWORD" to PASSWORD))
            .waitingFor(Wait.forLogMessage(".*mariadbd: ready for connections.*\\n", 2).withStartupTimeout(Duration.ofMinutes(3)))
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { container.stop() } })
        "${container.host}:${container.getMappedPort(3306)}"
    }

    fun require(): String {
        val found = hostPort
        assumeTrue(found != null, "no MariaDB: start one on OIP_SIM_MARIADB_PORT or run where Docker is available")
        return found!!
    }
}
