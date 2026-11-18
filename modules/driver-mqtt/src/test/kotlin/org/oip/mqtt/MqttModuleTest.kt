package org.oip.mqtt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import org.oip.host.ModuleHost
import org.oip.host.ModuleState
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class MqttModuleTest {
    @TempDir
    lateinit var tmp: Path

    @Test fun moduleInstallStartStopUninstallLeavesNoResources() {
        val id = MqttDriverModule.MODULE_ID
        val manifest = javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n")
        val modules = tmp.resolve("modules")
        modules.resolve(id).createDirectories().resolve("module.yaml").writeText(manifest)
        val config = tmp.resolve("config")
        val store = FileConfigStore(config, SchemaRegistry())
        val host = ModuleHost(modules, config, store = store)
        host.installAll()
        assertEquals(ModuleState.INSTALLED, host.state(id))
        host.start(id)
        val module = host.instance(id) as MqttDriverModule
        assertEquals(ModuleState.RUNNING, host.state(id))
        host.stop(id)
        host.uninstall(id)
        assertTrue(host.installed().isEmpty())
        assertEquals(0, store.list(module = id).size)
        assertFalse(config.resolve(id).exists())
        assertTrue(module.connection("none") == null)
    }

    @Test fun plaintextPasswordIsRejectedAndAReferenceIsStored() {
        val id = MqttDriverModule.MODULE_ID
        val modules = tmp.resolve("modules")
        modules.resolve(id).createDirectories().resolve("module.yaml").writeText(javaClass.getResource("/module.yaml")!!.readText().replace("\r\n", "\n"))
        val config = tmp.resolve("config")
        val store = FileConfigStore(config, SchemaRegistry())
        val host = ModuleHost(modules, config, store = store)
        host.installAll()
        assertThrows(ValidationException::class.java) {
            store.put(
                Scope.GATEWAY,
                Resource(CONNECTION_KIND, Metadata("line", id), mapOf("host" to "127.0.0.1", "password" to "s3cret-pass-mqtt-9f3a")),
            )
        }
        store.put(
            Scope.GATEWAY,
            Resource(
                CONNECTION_KIND,
                Metadata("line", id),
                mapOf("host" to "127.0.0.1", "username" to "secret://mqtt-user", "password" to "secret://mqtt-pass"),
            ),
        )
        val text = java.nio.file.Files.readString(config.resolve(id).resolve("gateway/Connection/line.yaml"))
        assertTrue(text.contains("secret://mqtt-pass"))
        assertFalse(text.contains("s3cret-pass-mqtt-9f3a"))
    }

    @Test fun missingSecretDoesNotConnectAndIsNotLoggedAsPlaintext() {
        val records = CopyOnWriteArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record.message.orEmpty() + record.thrown?.message.orEmpty()
            }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger("org.oip.mqtt")
        logger.addHandler(handler)
        logger.level = Level.ALL
        val broker = ScriptedBroker()
        val module = MqttDriverModule()
        try {
            module.secretSource = SecretSource { reference ->
                if (secretName(reference) == "mqtt-user") "operator" else throw IllegalStateException("unavailable")
            }
            module.start()
            val connection = module.connect(
                mqttConnectionConfig(
                    "line",
                    mapOf(
                        "host" to "127.0.0.1",
                        "port" to broker.port,
                        "username" to "secret://mqtt-user",
                        "password" to "secret://mqtt-pass",
                        "reconnectInitialMs" to 50,
                        "reconnectMaxMs" to 100,
                    ),
                ),
            )
            Thread.sleep(300)
            assertFalse(connection.connected())
            assertEquals(0, broker.connects.size)
            assertTrue(records.any { it.contains("mqtt-pass") })
            assertTrue(records.none { it.contains("s3cret") })
        } finally {
            module.stop()
            logger.removeHandler(handler)
            broker.close()
        }
    }
}
