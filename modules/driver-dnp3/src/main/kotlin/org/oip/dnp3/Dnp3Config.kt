package org.oip.dnp3

import org.oip.config.SchemaRegistry

const val OUTSTATION_KIND = "Outstation"

enum class CommandMode(val wire: String) {
    SELECT_BEFORE_OPERATE("selectBeforeOperate"),
    DIRECT_OPERATE("directOperate");

    companion object {
        fun parse(text: String): CommandMode = when (text.trim().lowercase()) {
            "selectbeforeoperate", "sbo", "select" -> SELECT_BEFORE_OPERATE
            "directoperate", "direct" -> DIRECT_OPERATE
            else -> throw IllegalArgumentException("command mode '$text' is not selectBeforeOperate or directOperate")
        }
    }
}

/** How the master sets the outstation's clock when the outstation asks for time (IIN1.4). */
enum class TimeSync(val wire: String) {
    NONE("none"),
    /** Record current time, then write the recorded time (group 50 variation 3). */
    LAN("lan"),
    /** Delay measurement, then write the absolute time plus half the round trip (group 50 variation 1). */
    NON_LAN("nonLan");

    companion object {
        fun parse(text: String): TimeSync = entries.firstOrNull { it.wire.equals(text.trim(), true) }
            ?: throw IllegalArgumentException("timeSync '$text' is not none, lan or nonLan")
    }
}

/**
 * A DNP3 outstation the gateway polls as a master over TCP (DEV-014).
 *
 * On connect the master disables unsolicited reporting, runs an integrity poll (classes 1, 2, 3 then 0)
 * and, with [unsolicited], enables unsolicited responses for [eventClasses]. It then runs an integrity
 * poll every [integrityPeriodMs] (0: only at connect and after an outstation restart) and an event poll
 * of [eventClasses] every [eventPeriodMs] (0: none; events then arrive only unsolicited or when the
 * outstation's IIN says it has some). [responseTimeoutMs] bounds the wait for each response fragment;
 * a response that does not come closes the connection, which is opened again after [reconnectMs].
 * Commands (DEV-013) use [commandMode] unless a write or script names one.
 * doc source: FEATURES DEV-013, DEV-014
 */
data class OutstationConfig(
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val masterAddress: Int = 1,
    val outstationAddress: Int = 1024,
    val description: String = "",
    val responseTimeoutMs: Long = 3_000,
    val connectTimeoutMs: Long = 5_000,
    val reconnectMs: Long = 5_000,
    val integrityPeriodMs: Long = 3_600_000,
    val eventPeriodMs: Long = 5_000,
    val eventClasses: Set<Int> = setOf(1, 2, 3),
    val unsolicited: Boolean = true,
    val commandMode: CommandMode = CommandMode.SELECT_BEFORE_OPERATE,
    /** Group 41 variation for analog output commands: 1 (32-bit), 2 (16-bit), 3 (float), 4 (double), 0 picks 1 or 3 from the value. */
    val analogOutputVariation: Int = 0,
    val timeSync: TimeSync = TimeSync.LAN,
) {
    init {
        require(name.isNotBlank()) { "outstation name is required" }
        require(host.isNotBlank()) { "host is required" }
        require(port in 1..65535) { "port $port is outside 1..65535" }
        require(masterAddress in 0..0xFFEF) { "masterAddress $masterAddress is outside 0..65519" }
        require(outstationAddress in 0..0xFFEF) { "outstationAddress $outstationAddress is outside 0..65519" }
        require(masterAddress != outstationAddress) { "masterAddress and outstationAddress must differ" }
        require(responseTimeoutMs in 10..120_000) { "responseTimeoutMs $responseTimeoutMs is outside 10..120000" }
        require(connectTimeoutMs in 10..120_000) { "connectTimeoutMs $connectTimeoutMs is outside 10..120000" }
        require(reconnectMs >= 10) { "reconnectMs must be at least 10" }
        require(integrityPeriodMs == 0L || integrityPeriodMs >= 100) { "integrityPeriodMs must be 0 or at least 100" }
        require(eventPeriodMs == 0L || eventPeriodMs >= 10) { "eventPeriodMs must be 0 or at least 10" }
        require(eventClasses.all { it in 1..3 }) { "eventClasses must be 1, 2 or 3" }
        require(analogOutputVariation in 0..4) { "analogOutputVariation $analogOutputVariation is outside 0..4" }
    }

    companion object {
        const val DEFAULT_PORT = 20000
    }
}

/** Config kind `Outstation` for module `org.oip.driver-dnp3`. doc source: FEATURES DEV-014 */
fun outstationSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "required" to listOf("host"),
    "properties" to linkedMapOf(
        "description" to mapOf("type" to "string", "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "host" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 253),
        "port" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535, "default" to OutstationConfig.DEFAULT_PORT),
        "masterAddress" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 0xFFEF, "default" to 1),
        "outstationAddress" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 0xFFEF, "default" to 1024),
        "responseTimeoutMs" to mapOf("type" to "integer", "minimum" to 10, "maximum" to 120000, "default" to 3000),
        "connectTimeoutMs" to mapOf("type" to "integer", "minimum" to 10, "maximum" to 120000, "default" to 5000),
        "reconnectMs" to mapOf("type" to "integer", "minimum" to 10, "default" to 5000),
        "integrityPeriodMs" to mapOf("type" to "integer", "minimum" to 0, "default" to 3600000),
        "eventPeriodMs" to mapOf("type" to "integer", "minimum" to 0, "default" to 5000),
        "eventClasses" to mapOf(
            "type" to "array", "items" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 3),
            "uniqueItems" to true, "default" to listOf(1, 2, 3),
        ),
        "unsolicited" to mapOf("type" to "boolean", "default" to true),
        "commandMode" to mapOf("type" to "string", "enum" to CommandMode.entries.map { it.wire }, "default" to CommandMode.SELECT_BEFORE_OPERATE.wire),
        "analogOutputVariation" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 4, "default" to 0),
        "timeSync" to mapOf("type" to "string", "enum" to TimeSync.entries.map { it.wire }, "default" to TimeSync.LAN.wire),
    ),
)

fun outstationConfig(resourceName: String, spec: Map<String, Any?>): OutstationConfig = OutstationConfig(
    name = resourceName,
    host = spec.str("host") ?: throw IllegalArgumentException("host is required"),
    port = spec.int("port") ?: OutstationConfig.DEFAULT_PORT,
    masterAddress = spec.int("masterAddress") ?: 1,
    outstationAddress = spec.int("outstationAddress") ?: 1024,
    description = spec.str("description") ?: "",
    responseTimeoutMs = spec.long("responseTimeoutMs") ?: 3_000,
    connectTimeoutMs = spec.long("connectTimeoutMs") ?: 5_000,
    reconnectMs = spec.long("reconnectMs") ?: 5_000,
    integrityPeriodMs = spec.long("integrityPeriodMs") ?: 3_600_000,
    eventPeriodMs = spec.long("eventPeriodMs") ?: 5_000,
    eventClasses = (spec["eventClasses"] as? List<*>)?.map { (it as Number).toInt() }?.toSet() ?: setOf(1, 2, 3),
    unsolicited = spec["unsolicited"] as? Boolean ?: true,
    commandMode = spec.str("commandMode")?.let { CommandMode.parse(it) } ?: CommandMode.SELECT_BEFORE_OPERATE,
    analogOutputVariation = spec.int("analogOutputVariation") ?: 0,
    timeSync = spec.str("timeSync")?.let { TimeSync.parse(it) } ?: TimeSync.LAN,
)

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
internal fun Map<String, Any?>.enabled(): Boolean = this["enabled"] as? Boolean ?: true

/**
 * Item addresses of an outstation (DEV-014). A point is `<type>:<index>` with type `bi` (binary input),
 * `bo` (binary output status), `ci` (counter), `ai` (analog input) or `ao` (analog output status). Its
 * items are the value (`bi:3`, or `bi:3/Value`), `/Flags` (the DNP3 quality flags as a number),
 * `/Online` and `/Timestamp` (the source time in epoch milliseconds). `iin` is the last internal
 * indications (IIN1 in the low byte, IIN2 in the high byte).
 *
 * Writing `bo:<n>` sends a control relay output block: true is LATCH_ON and false LATCH_OFF, or with
 * `;op=pulse` PULSE_ON / PULSE_OFF, or with `;op=tripclose` a CLOSE / TRIP pulse; `;onMs=` and
 * `;offMs=` set the pulse times. Writing `ao:<n>` sends an analog output block, `;variation=1..4`
 * picking its group 41 variation. `;mode=sbo` or `;mode=direct` overrides the outstation's command mode.
 * doc source: FEATURES DEV-013, DEV-014
 */
data class Dnp3Address(
    val type: PointType?,
    val index: Int,
    val field: String = VALUE,
    val options: Map<String, String> = emptyMap(),
) {
    val isIin: Boolean get() = type == null

    override fun toString(): String = if (type == null) IIN else "${type.prefix}:$index" + (if (field == VALUE) "" else "/$field")

    companion object {
        const val VALUE = "Value"
        const val IIN = "iin"
        val FIELDS = listOf(VALUE, "Flags", "Online", "Timestamp")
        private val OPTIONS = setOf("mode", "op", "onms", "offms", "variation")

        fun parse(text: String): Dnp3Address {
            val parts = text.trim().split(';')
            val body = parts[0].trim()
            val options = LinkedHashMap<String, String>()
            for (raw in parts.drop(1)) {
                val eq = raw.indexOf('=')
                if (eq <= 0) throw AddressException("option '$raw' in '$text' is not name=value")
                val key = raw.substring(0, eq).trim().lowercase()
                if (key !in OPTIONS) throw AddressException("unknown option '$key' in '$text'")
                options[key] = raw.substring(eq + 1).trim()
            }
            if (body.equals(IIN, true)) {
                if (options.isNotEmpty()) throw AddressException("'iin' takes no options")
                return Dnp3Address(null, 0)
            }
            val slash = body.indexOf('/')
            val point = if (slash >= 0) body.substring(0, slash) else body
            val colon = point.indexOf(':')
            if (colon <= 0) throw AddressException("'$text' is not <type>:<index>")
            val type = PointType.byPrefix(point.substring(0, colon)) ?: throw AddressException("unknown point type in '$text' (bi, bo, ci, ai or ao)")
            val index = point.substring(colon + 1).toIntOrNull()?.takeIf { it in 0..65535 } ?: throw AddressException("bad point index in '$text'")
            val field = if (slash < 0) VALUE else FIELDS.firstOrNull { it.equals(body.substring(slash + 1), true) }
                ?: throw AddressException("unknown item in '$text' (Value, Flags, Online or Timestamp)")
            if (options.isNotEmpty() && (field != VALUE || (type != PointType.BINARY_OUTPUT && type != PointType.ANALOG_OUTPUT))) {
                throw AddressException("only a bo or ao value takes command options in '$text'")
            }
            return Dnp3Address(type, index, field, options)
        }
    }
}
