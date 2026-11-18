package org.oip.testinfra

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Holding registers of the Modbus simulator. Addresses are 0-based (PDU addresses). */
object ModbusSimMap {
    const val MODE = 0
    const val AMPLITUDE_X100 = 1
    const val OFFSET_X100 = 2
    const val PERIOD_MS = 3
    const val VALUE_X100 = 4
    const val FROZEN = 5
    const val NOW_MS_HI = 6
    const val NOW_MS_LO = 7
    const val EPOCH_MS_HI = 8
    const val EPOCH_MS_LO = 9
    const val COUNT = 10
}

/** Documented host ports for `docker compose up`. Testcontainers may remap them. */
object SimPorts {
    const val MODBUS_PROXY = 5020
    const val MODBUS_DIRECT = 15020
    const val MODBUS_CONTROL = 15021
    const val OPCUA_PROXY = 4840
    const val OPCUA_DIRECT = 14840
    const val OPCUA_CONTROL = 14841
    const val MQTT_PROXY = 1883
    const val MQTT_DIRECT = 11883
    const val SPARKPLUG_CONTROL = 15030
    const val TOXIPROXY = 8474
    /**
     * Host port for `docker compose up`. Override with OIP_SIM_POSTGRES_PORT. Not 5432, so a local Postgres can stay up.
     * Below 32768: the Linux ephemeral range (32768-60999) hands its ports to outbound connections, and a client
     * socket holding the port makes the publish fail with "address already in use".
     */
    const val POSTGRES = 25432
    const val POSTGRES_PROXY = 15432
    const val SMTP = 1025
    const val SMTP_PROXY = 11025
    const val MAILPIT_HTTP = 8025
    const val DB_NAME = "oip"
    const val DB_USER = "oip"
    const val DB_PASSWORD = "oip"

    /** Host port published for Timescale. Blank or unset uses [POSTGRES]. */
    fun postgresHostPort(env: (String) -> String? = System::getenv): Int {
        val raw = env("OIP_SIM_POSTGRES_PORT")?.trim().orEmpty()
        if (raw.isEmpty()) return POSTGRES
        return raw.toIntOrNull() ?: error("OIP_SIM_POSTGRES_PORT must be an integer, was $raw")
    }
}

object SparkplugNames {
    const val GROUP = "OIP"
    const val EDGE = "SimEdge"
    const val DEVICE = "SimDevice"
    const val NBIRTH = "spBv1.0/OIP/NBIRTH/SimEdge"
    const val NDEATH = "spBv1.0/OIP/NDEATH/SimEdge"
    const val NDATA = "spBv1.0/OIP/NDATA/SimEdge"
    const val DBIRTH = "spBv1.0/OIP/DBIRTH/SimEdge/SimDevice"
    const val DDATA = "spBv1.0/OIP/DDATA/SimEdge/SimDevice"
    const val FILTER = "spBv1.0/OIP/#"
}

enum class SimService(val proxyName: String) {
    MODBUS("modbus"),
    OPCUA("opcua"),
    MQTT("mqtt"),
    POSTGRES("postgres"),
    SMTP("smtp"),
}

data class SimEndpoints(
    val host: String,
    val modbusProxy: Int,
    val modbusDirect: Int,
    val modbusControl: Int,
    val opcUaProxy: Int,
    val opcUaDirect: Int,
    val opcUaControl: Int,
    val mqttProxy: Int,
    val mqttDirect: Int,
    val sparkplugControl: Int,
    val toxiproxy: Int,
    val postgres: Int,
    val postgresProxy: Int,
    val smtp: Int,
    val smtpProxy: Int,
    val mailpitHttp: Int,
) {
    val opcUaEndpoint: String get() = "opc.tcp://$host:$opcUaProxy/oip/sim"
    val postgresJdbcUrl: String
        get() = "jdbc:postgresql://$host:$postgres/${SimPorts.DB_NAME}?sslmode=disable"

    fun postgresProxyJdbcUrl(): String =
        "jdbc:postgresql://$host:$postgresProxy/${SimPorts.DB_NAME}?sslmode=disable"

    companion object {
        fun standard(host: String = "localhost") = SimEndpoints(
            host = host,
            modbusProxy = SimPorts.MODBUS_PROXY,
            modbusDirect = SimPorts.MODBUS_DIRECT,
            modbusControl = SimPorts.MODBUS_CONTROL,
            opcUaProxy = SimPorts.OPCUA_PROXY,
            opcUaDirect = SimPorts.OPCUA_DIRECT,
            opcUaControl = SimPorts.OPCUA_CONTROL,
            mqttProxy = SimPorts.MQTT_PROXY,
            mqttDirect = SimPorts.MQTT_DIRECT,
            sparkplugControl = SimPorts.SPARKPLUG_CONTROL,
            toxiproxy = SimPorts.TOXIPROXY,
            postgres = SimPorts.postgresHostPort(),
            postgresProxy = SimPorts.POSTGRES_PROXY,
            smtp = SimPorts.SMTP,
            smtpProxy = SimPorts.SMTP_PROXY,
            mailpitHttp = SimPorts.MAILPIT_HTTP,
        )
    }
}

data class SimSignal(
    val mode: Waveform.Mode,
    val amplitude: Double,
    val offset: Double,
    val periodMs: Long,
    val epochMs: Long,
    val frozen: Boolean,
    val nowMs: Long,
    val value: Double,
)

/** HTTP control client for the Modbus, OPC UA and Sparkplug simulators. */
class SimControl(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val mapper = ObjectMapper()

    fun healthy(): Boolean {
        val response = send("GET", "/health", null)
        return response.statusCode() == 200 && mapper.readTree(response.body()).path("status").asText() == "ok"
    }

    fun get(): SimSignal = parse(send("GET", "/value", null).body())

    fun set(
        mode: Waveform.Mode? = null,
        amplitude: Double? = null,
        offset: Double? = null,
        periodMs: Long? = null,
        epochMs: Long? = null,
        frozen: Boolean? = null,
        nowMs: Long? = null,
    ): SimSignal {
        val node = mapper.createObjectNode()
        if (mode != null) node.put("mode", mode.json)
        if (amplitude != null) node.put("amplitude", amplitude)
        if (offset != null) node.put("offset", offset)
        if (periodMs != null) node.put("periodMs", periodMs)
        if (epochMs != null) node.put("epochMs", epochMs)
        if (frozen != null) node.put("frozen", frozen)
        if (nowMs != null) node.put("nowMs", nowMs)
        val response = send("POST", "/control", node)
        if (response.statusCode() != 200) {
            error("control ${response.statusCode()}: ${response.body()}")
        }
        return parse(response.body())
    }

    private fun parse(json: String): SimSignal {
        val node = mapper.readTree(json)
        return SimSignal(
            mode = Waveform.Mode.fromJson(node.get("mode").asText()),
            amplitude = node.get("amplitude").asDouble(),
            offset = node.get("offset").asDouble(),
            periodMs = node.get("periodMs").asLong(),
            epochMs = node.get("epochMs").asLong(),
            frozen = node.get("frozen").asBoolean(),
            nowMs = node.get("nowMs").asLong(),
            value = node.get("value").asDouble(),
        )
    }

    private fun send(method: String, path: String, body: ObjectNode?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
        val request = if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
        }
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
