package org.oip.mqtt

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The edge-node scenarios on Eclipse Mosquitto 2.0.22 (same image and digest as [MqttBrokerTest]). Needs Docker. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
internal class SparkplugEdgeBrokerTest : SparkplugEdgeScenarios() {
    private lateinit var mosquitto: GenericContainer<*>

    override fun brokerPort(): Int = mosquitto.getMappedPort(1883)

    @BeforeAll fun startBroker() {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(), "Docker is not available; this test needs a container")
        val image = DockerImageName.parse(
            "eclipse-mosquitto:2.0.22@sha256:09b62ff32c2e7b64d4b431b9a89236073289b68db273de2b089edeb9b0d92b5c",
        )
        mosquitto = GenericContainer(image)
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto.conf"), "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)))
        mosquitto.start()
    }

    @AfterAll fun stopBroker() {
        if (this::mosquitto.isInitialized) mosquitto.stop()
    }
}
