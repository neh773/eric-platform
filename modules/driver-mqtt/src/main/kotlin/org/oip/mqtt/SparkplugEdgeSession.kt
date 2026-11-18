package org.oip.mqtt

import java.time.Instant
import java.util.Date

internal const val BD_SEQ = "bdSeq"
internal const val NODE_REBIRTH = "Node Control/Rebirth"

/**
 * Sparkplug B 3.0 edge-node protocol state for one [SparkplugEdgeConfig]. No IO and no threads:
 * [SparkplugEdgeNode] owns the socket and calls this from its one IO thread.
 *
 * https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf
 *
 * - Every MQTT CONNECT carries a new NDEATH will. Its `bdSeq` (UInt64, 0..255, wraps) is one more than
 *   the previous connect's. NBIRTH repeats that `bdSeq`, so a host can match the death to the birth.
 * - NBIRTH has `seq` 0. DBIRTH, NDATA, DDATA and DDEATH take the next `seq`, modulo 256.
 *   NDEATH carries no `seq`.
 * - NBIRTH lists `bdSeq`, `Node Control/Rebirth` (false) and every node metric with a value, a datatype
 *   and a timestamp. Each device then publishes one DBIRTH with its metrics.
 * - NDATA and DDATA carry only metrics whose value changed since they were last published
 *   (report by exception). A tag whose quality is not good is published with `is_null`.
 * - NCMD `Node Control/Rebirth` = true repeats NBIRTH and every DBIRTH with the same `bdSeq`.
 *   Other NCMD and DCMD metrics become tag writes when the metric is configured writable, NBIRTH has
 *   been published, and the value converts to the birth datatype ([commandValue]). Null commands
 *   are rejected. Integer values outside the datatype's width are never wrapped.
 * - With a primary host id, births wait until `spBv1.0/STATE/<id>` reports online. A later offline
 *   STATE (same or newer timestamp) ends the session: NDEATH, then disconnect.
 */
internal class SparkplugEdgeSession(val config: SparkplugEdgeConfig, firstBdSeq: Long = 0) {
    private var nextBdSeq = firstBdSeq and 0xFF
    private var seq = 0
    private val metrics: List<EdgeMetric> =
        config.nodeMetrics.map { EdgeMetric("", it) } + config.devices.flatMap { d -> d.metrics.map { EdgeMetric(d.id, it) } }
    private val byKey = metrics.associateBy { it.key }
    private val datatypes = HashMap<String, Int>()
    private val published = HashMap<String, Last>()
    private var lastStateTimestamp = Long.MIN_VALUE

    /** bdSeq of the current MQTT session. -1 before the first [beginConnect]. */
    var bdSeq: Long = -1
        private set

    var birthed: Boolean = false
        private set

    var hostOnline: Boolean = false
        private set

    /** A metric that had no datatype at birth now has one. The caller publishes births again. */
    var rebirthNeeded: Boolean = false
        private set

    private val unresolved = HashSet<String>()

    /** Every configured metric, node metrics first. */
    fun metrics(): List<EdgeMetric> = metrics

    fun topic(type: String, device: String = ""): String =
        if (device.isEmpty()) "spBv1.0/${config.groupId}/$type/${config.edgeNodeId}"
        else "spBv1.0/${config.groupId}/$type/${config.edgeNodeId}/$device"

    /** Starts a new MQTT session: next bdSeq, not born, primary host not yet seen online. */
    fun beginConnect(nowMs: Long): MqttWill {
        bdSeq = nextBdSeq
        nextBdSeq = (nextBdSeq + 1) and 0xFF
        birthed = false
        hostOnline = config.primaryHostId == null
        return MqttWill(topic("NDEATH"), deathPayload(nowMs), qos = 1, retain = false)
    }

    /** NCMD, DCMD, and the primary host's STATE. Subscribed before NBIRTH. */
    fun subscriptions(): List<String> {
        val filters = mutableListOf(topic("NCMD"), "spBv1.0/${config.groupId}/DCMD/${config.edgeNodeId}/+")
        config.primaryHostId?.let { filters += stateTopic(it) }
        return filters
    }

    fun readyToBirth(): Boolean = !birthed && hostOnline

    fun deathPayload(nowMs: Long): ByteArray = SparkplugCodec.encode(
        SparkplugCodec.Payload(nowMs, null, listOf(SparkplugCodec.Metric(BD_SEQ, null, SparkplugCodec.UINT64, null, false, bdSeq))),
    )

    /**
     * NBIRTH then one DBIRTH per device, from [snapshot]. Resets `seq` to 0.
     * Datatypes are fixed here for the life of this birth.
     */
    fun births(nowMs: Long, snapshot: (EdgeMetric) -> EdgeTagValue): List<EdgePublish> {
        seq = 0
        datatypes.clear()
        published.clear()
        unresolved.clear()
        rebirthNeeded = false
        val values = metrics.associateWith { snapshot(it) }
        for ((metric, value) in values) {
            val datatype = resolveDatatype(metric.config, value)
            if (datatype == null) unresolved += metric.key else datatypes[metric.key] = datatype
        }
        val out = ArrayList<EdgePublish>(1 + config.devices.size)
        val nodeMetrics = ArrayList<SparkplugCodec.Metric>()
        nodeMetrics += SparkplugCodec.Metric(BD_SEQ, null, SparkplugCodec.UINT64, nowMs, false, bdSeq)
        nodeMetrics += SparkplugCodec.Metric(NODE_REBIRTH, null, SparkplugCodec.BOOLEAN, nowMs, false, false)
        metrics.filter { it.device.isEmpty() && it.key !in unresolved }.forEach { nodeMetrics += birthMetric(it, values.getValue(it), nowMs) }
        out += EdgePublish(topic("NBIRTH"), SparkplugCodec.encode(SparkplugCodec.Payload(nowMs, nextSeq(), nodeMetrics)))
        for (device in config.devices) {
            val deviceMetrics = metrics.filter { it.device == device.id && it.key !in unresolved }.map { birthMetric(it, values.getValue(it), nowMs) }
            out += EdgePublish(topic("DBIRTH", device.id), SparkplugCodec.encode(SparkplugCodec.Payload(nowMs, nextSeq(), deviceMetrics)))
        }
        birthed = true
        return out
    }

    /**
     * NDATA for changed node metrics, then one DDATA per device with changes. Changes are applied in
     * order, so a metric that changed twice is sent twice in one payload. Nothing is sent before NBIRTH.
     * A change to a metric left out of the birth (no datatype yet) sets [rebirthNeeded] once its value
     * gives a datatype.
     */
    fun data(nowMs: Long, changes: List<Pair<EdgeMetric, EdgeTagValue>>): List<EdgePublish> {
        if (!birthed || changes.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<SparkplugCodec.Metric>>()
        for ((metric, value) in changes) {
            val datatype = datatypes[metric.key]
            if (datatype == null) {
                if (metric.key in unresolved && resolveDatatype(metric.config, value) != null) rebirthNeeded = true
                continue
            }
            val encoded = encodable(datatype, value)
            val last = published[metric.key]
            if (last != null && last.isNull == (encoded == null) && last.value == encoded) continue
            published[metric.key] = Last(encoded)
            val timestamp = value.epochMillis.takeIf { it > 0 } ?: nowMs
            grouped.getOrPut(metric.device) { ArrayList() } +=
                SparkplugCodec.Metric(metric.config.name, null, datatype, timestamp, encoded == null, encoded)
        }
        val out = ArrayList<EdgePublish>(grouped.size)
        grouped.remove("")?.let { out += EdgePublish(topic("NDATA"), SparkplugCodec.encode(SparkplugCodec.Payload(nowMs, nextSeq(), it))) }
        for (device in config.devices) {
            val list = grouped[device.id] ?: continue
            out += EdgePublish(topic("DDATA", device.id), SparkplugCodec.encode(SparkplugCodec.Payload(nowMs, nextSeq(), list)))
        }
        return out
    }

    /** Inbound NCMD, DCMD, or STATE. Malformed payloads are ignored. */
    fun onMessage(topic: String, payload: ByteArray): List<EdgeAction> {
        val primary = config.primaryHostId
        if (primary != null && topic == stateTopic(primary)) return onState(payload)
        val nodeCommand = topic("NCMD")
        val devicePrefix = "spBv1.0/${config.groupId}/DCMD/${config.edgeNodeId}/"
        val device = when {
            topic == nodeCommand -> ""
            topic.startsWith(devicePrefix) -> topic.removePrefix(devicePrefix).takeIf { it.isNotEmpty() && '/' !in it } ?: return emptyList()
            else -> return emptyList()
        }
        val decoded = try {
            SparkplugCodec.decode(payload)
        } catch (_: RuntimeException) {
            return emptyList()
        }
        val actions = ArrayList<EdgeAction>()
        for (metric in decoded.metrics) {
            val label = if (device.isEmpty()) metric.name else "$device/${metric.name}"
            // Aliases are never assigned by this edge node, so an alias-only metric cannot be resolved.
            if (metric.name.isEmpty()) {
                actions += EdgeAction.Rejected("alias ${metric.alias}", "aliases are not used")
                continue
            }
            if (device.isEmpty() && metric.name == NODE_REBIRTH) {
                if (metric.value == true && EdgeAction.Rebirth !in actions) actions += EdgeAction.Rebirth
                continue
            }
            if (!birthed) {
                actions += EdgeAction.Rejected(label, "not born")
                continue
            }
            val target = byKey[key(device, metric.name)]
            if (target == null) {
                actions += EdgeAction.Rejected(label, "unknown metric")
                continue
            }
            if (!target.config.writable) {
                actions += EdgeAction.Rejected(label, "not writable")
                continue
            }
            if (metric.isNull) {
                actions += EdgeAction.Rejected(label, "null value")
                continue
            }
            val datatype = datatypes[target.key]
            if (datatype == null) {
                actions += EdgeAction.Rejected(label, "not in the birth")
                continue
            }
            val value = commandValue(datatype, metric)
            if (value == null) {
                actions += EdgeAction.Rejected(label, "value does not fit datatype $datatype")
                continue
            }
            actions += EdgeAction.Write(target, value)
        }
        return actions
    }

    private fun onState(payload: ByteArray): List<EdgeAction> {
        val (online, timestamp) = parseState(payload) ?: return emptyList()
        if (timestamp < lastStateTimestamp) return emptyList()
        lastStateTimestamp = timestamp
        if (online) {
            hostOnline = true
            return if (birthed) emptyList() else listOf(EdgeAction.Birth)
        }
        val wasBorn = birthed
        hostOnline = false
        return if (wasBorn) listOf(EdgeAction.HostOffline) else emptyList()
    }

    /** The session ended. Births are needed again on the next connect. */
    fun disconnected() {
        birthed = false
    }

    private fun nextSeq(): Int {
        val current = seq
        seq = (seq + 1) and 0xFF
        return current
    }

    private fun birthMetric(metric: EdgeMetric, value: EdgeTagValue, nowMs: Long): SparkplugCodec.Metric {
        val datatype = datatypes.getValue(metric.key)
        val encoded = encodable(datatype, value)
        published[metric.key] = Last(encoded)
        val timestamp = value.epochMillis.takeIf { it > 0 } ?: nowMs
        return SparkplugCodec.Metric(metric.config.name, null, datatype, timestamp, encoded == null, encoded)
    }

    private class Last(val value: Any?) {
        val isNull = value == null
    }

    companion object {
        fun key(device: String, name: String) = device + "\u0000" + name

        /**
         * Config name, then the tag's data type, then the value's type. Null when none is known
         * (a missing tag or a null value): the metric is left out of the birth until it resolves.
         */
        fun resolveDatatype(config: EdgeMetricConfig, value: EdgeTagValue): Int? {
            config.dataType?.let { name -> EDGE_DATATYPES[name]?.let { return it } }
            when (value.dataType?.lowercase()) {
                "int32", "int", "integer" -> return SparkplugCodec.INT32
                "int64", "long" -> return SparkplugCodec.INT64
                "float" -> return SparkplugCodec.FLOAT
                "double" -> return SparkplugCodec.DOUBLE
                "boolean" -> return SparkplugCodec.BOOLEAN
                "string", "document" -> return SparkplugCodec.STRING
                "datetime" -> return SparkplugCodec.DATETIME
            }
            return when (value.value) {
                is Boolean -> SparkplugCodec.BOOLEAN
                is Byte, is Short, is Int -> SparkplugCodec.INT32
                is Long -> SparkplugCodec.INT64
                is Float -> SparkplugCodec.FLOAT
                is Double, is Number -> SparkplugCodec.DOUBLE
                is Instant, is Date -> SparkplugCodec.DATETIME
                null -> null
                else -> SparkplugCodec.STRING
            }
        }

        /**
         * The value converted for [datatype], or null when it is published as `is_null`:
         * quality is not good, the value is null, or it does not convert.
         */
        fun encodable(datatype: Int, value: EdgeTagValue): Any? {
            if (!value.good) return null
            val raw = value.value ?: return null
            return when (datatype) {
                SparkplugCodec.INT8, SparkplugCodec.INT16, SparkplugCodec.INT32,
                SparkplugCodec.UINT8, SparkplugCodec.UINT16, SparkplugCodec.UINT32,
                SparkplugCodec.INT64, SparkplugCodec.UINT64 -> when (raw) {
                    is Float, is Double -> integral(datatype, (raw as Number).toDouble(), round = true)
                    is Number -> inRange(datatype, raw.toLong())
                    is Boolean -> if (raw) 1L else 0L
                    is String -> raw.trim().toLongOrNull()?.let { inRange(datatype, it) }
                    else -> null
                }
                SparkplugCodec.FLOAT -> when (raw) {
                    is Number -> raw.toFloat().takeIf { it.isFinite() || !raw.toDouble().isFinite() }
                    is Boolean -> if (raw) 1f else 0f
                    is String -> raw.trim().toFloatOrNull()
                    else -> null
                }
                SparkplugCodec.DOUBLE -> when (raw) {
                    is Number -> raw.toDouble()
                    is Boolean -> if (raw) 1.0 else 0.0
                    is String -> raw.trim().toDoubleOrNull()
                    else -> null
                }
                SparkplugCodec.BOOLEAN -> when (raw) {
                    is Boolean -> raw
                    is Number -> raw.toDouble() != 0.0
                    is String -> raw.trim().lowercase().toBooleanStrictOrNull()
                    else -> null
                }
                SparkplugCodec.DATETIME -> when (raw) {
                    is Instant -> raw.toEpochMilli()
                    is Date -> raw.time
                    is Number -> raw.toLong()
                    else -> null
                }
                else -> if (raw is Map<*, *> || raw is List<*>) JsonCodec.write(raw) else raw.toString()
            }
        }

        /** Signed or unsigned bounds of an integer datatype. INT64 and UINT64 use the Long range. */
        private fun bounds(datatype: Int): LongRange = when (datatype) {
            SparkplugCodec.INT8 -> -128L..127L
            SparkplugCodec.INT16 -> -32_768L..32_767L
            SparkplugCodec.INT32 -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            SparkplugCodec.UINT8 -> 0L..255L
            SparkplugCodec.UINT16 -> 0L..65_535L
            SparkplugCodec.UINT32 -> 0L..4_294_967_295L
            SparkplugCodec.UINT64 -> 0L..Long.MAX_VALUE
            else -> Long.MIN_VALUE..Long.MAX_VALUE
        }

        private fun inRange(datatype: Int, value: Long): Long? = value.takeIf { it in bounds(datatype) }

        /** [round] rounds a fraction (tag values); otherwise a fraction does not fit (commands). */
        private fun integral(datatype: Int, value: Double, round: Boolean): Long? {
            if (!value.isFinite()) return null
            val whole = if (round) Math.rint(value) else value
            if (!round && whole != Math.floor(whole)) return null
            val range = bounds(datatype)
            // Compare as doubles first so a huge value does not saturate into range.
            if (whole < range.first.toDouble() || whole > range.last.toDouble()) return null
            return inRange(datatype, whole.toLong())
        }

        /**
         * A command value converted to the birth [datatype], or null when it is rejected: the command's
         * datatype family differs (number, boolean, text), the number is out of range, or an integer
         * metric gets a fraction. Integer metrics yield Int up to 32 bits signed and 16 bits unsigned,
         * Long above; FLOAT yields Float; DATETIME yields epoch milliseconds.
         */
        fun commandValue(datatype: Int, metric: SparkplugCodec.Metric): Any? {
            val raw = metric.value ?: return null
            val sent = metric.datatype
            val integers = setOf(
                SparkplugCodec.INT8, SparkplugCodec.INT16, SparkplugCodec.INT32, SparkplugCodec.INT64,
                SparkplugCodec.UINT8, SparkplugCodec.UINT16, SparkplugCodec.UINT32, SparkplugCodec.UINT64,
            )
            val floats = setOf(SparkplugCodec.FLOAT, SparkplugCodec.DOUBLE)
            val texts = setOf(SparkplugCodec.STRING, SparkplugCodec.TEXT, SparkplugCodec.UUID)
            return when (datatype) {
                in integers -> {
                    val whole = when {
                        sent in integers && raw is Number -> inRange(datatype, raw.toLong())
                        sent in floats && raw is Number -> integral(datatype, raw.toDouble(), round = false)
                        else -> null
                    } ?: return null
                    when (datatype) {
                        SparkplugCodec.INT8, SparkplugCodec.INT16, SparkplugCodec.INT32,
                        SparkplugCodec.UINT8, SparkplugCodec.UINT16 -> whole.toInt()
                        else -> whole
                    }
                }
                SparkplugCodec.FLOAT -> if ((sent in floats || sent in integers) && raw is Number) {
                    raw.toFloat().takeIf { it.isFinite() }
                } else null
                SparkplugCodec.DOUBLE -> if ((sent in floats || sent in integers) && raw is Number) {
                    raw.toDouble().takeIf { it.isFinite() }
                } else null
                SparkplugCodec.BOOLEAN -> if (sent == SparkplugCodec.BOOLEAN && raw is Boolean) raw else null
                SparkplugCodec.DATETIME -> if ((sent == SparkplugCodec.DATETIME || sent in integers) && raw is Number) raw.toLong() else null
                in texts -> if (sent in texts && raw is String) raw else null
                else -> null
            }
        }
    }
}

/** A configured metric. [device] is empty for a node metric. */
class EdgeMetric(val device: String, val config: EdgeMetricConfig) {
    internal val key = SparkplugEdgeSession.key(device, config.name)
    override fun toString(): String = if (device.isEmpty()) config.name else "$device/${config.name}"
}

internal class EdgePublish(val topic: String, val payload: ByteArray, val qos: Int = 0, val retain: Boolean = false)

internal sealed class EdgeAction {
    /** The primary host is online and nothing has been born yet in this session. */
    data object Birth : EdgeAction()
    /** NCMD `Node Control/Rebirth`. */
    data object Rebirth : EdgeAction()
    /** The primary host went offline after births: publish NDEATH and disconnect. */
    data object HostOffline : EdgeAction()
    class Write(val metric: EdgeMetric, val value: Any?) : EdgeAction()
    /** A command metric that is not written. [target] names the metric for the log. */
    data class Rejected(val target: String, val reason: String) : EdgeAction()
}
