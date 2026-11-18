package org.oip.db

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Duration

/**
 * A MySQL server for tests: one already answering on `OIP_SIM_MYSQL_PORT` (default 23307), otherwise one
 * container per test JVM started here (CI has Docker). The bundled client speaks the native password
 * login, so the server is started with it as the default plugin. With no server, [require] skips the test.
 */
object MySqlServer {
    private const val IMAGE = "mysql:8.0"
    const val USER = "oip"
    const val PASSWORD = "oippw"

    private fun reachable(url: String): Boolean =
        runCatching { DriverManager.getConnection(url, USER, PASSWORD).use { it.isValid(2) } }.getOrDefault(false)

    /** `host:port` of a running server, or null when there is none and Docker is not available. */
    val hostPort: String? by lazy {
        val port = System.getenv("OIP_SIM_MYSQL_PORT")?.toIntOrNull() ?: 23307
        runCatching { Class.forName("org.drizzle.jdbc.DrizzleDriver") }
        if (reachable("jdbc:mysql:thin://127.0.0.1:$port/oip")) return@lazy "127.0.0.1:$port"
        val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!docker) return@lazy null
        val container = GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(3306)
            .withEnv(mapOf("MYSQL_ROOT_PASSWORD" to "rootpw", "MYSQL_DATABASE" to "oip", "MYSQL_USER" to USER, "MYSQL_PASSWORD" to PASSWORD))
            .withCommand("--default-authentication-plugin=mysql_native_password")
            .waitingFor(Wait.forLogMessage(".*mysqld: ready for connections.*port: 3306.*\\n", 1).withStartupTimeout(Duration.ofMinutes(3)))
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { container.stop() } })
        "${container.host}:${container.getMappedPort(3306)}"
    }

    fun require(): String {
        val found = hostPort
        assumeTrue(found != null, "no MySQL: start one on OIP_SIM_MYSQL_PORT or run where Docker is available")
        return found!!
    }
}
