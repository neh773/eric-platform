package org.oip.mqtt

/**
 * Sparkplug B 3.0 host-application session state for one MQTT connection.
 *
 * https://sparkplug.eclipse.org/specification/version/3.0/documents/sparkplug-specification-3.0.0.pdf
 *
 * NBIRTH sequence 0 starts an edge node. Later NBIRTH, DBIRTH, NDATA, DDATA and DDEATH increment
 * the sequence modulo 256. NDEATH is not sequenced; it is accepted only when its bdSeq matches the
 * NBIRTH bdSeq. A gap (the received sequence is 1..127 ahead) is held until [reorderTimeoutMs].
 * If the missing sequence arrives, buffered data is applied in order. If the timer expires, one
 * NCMD `Node Control/Rebirth` is requested and nothing further is sent until the next NBIRTH.
 * Births are applied even when the sequence is old or ahead, so an edge that publishes DBIRTH at
 * sequence 0 (the high-point simulator does) still exposes its metrics. DATA is applied only in order.
 * An alias is scoped to the edge node. Alias 0 is absent. `Node Control/Rebirth` is not aliased.
 */
internal class SparkplugSession(val reorderTimeoutMs: Long) {
    private val edges = HashMap<String, Edge>()

    fun onMessage(topic: String, payload: ByteArray, nowMs: Long): List<SpEffect> {
        return try {
            val route = route(topic) ?: return emptyList()
            if (route.type == "STATE") return emptyList()
            val decoded = SparkplugCodec.decode(payload)
            when (route.type) {
                "NBIRTH" -> onNodeBirth(route, decoded, nowMs)
                "DBIRTH" -> onDeviceBirth(route, decoded, nowMs)
                "NDATA" -> onData(route, decoded, nowMs, device = "")
                "DDATA" -> onData(route, decoded, nowMs, device = route.device)
                "NDEATH" -> onNodeDeath(route, decoded, nowMs)
                "DDEATH" -> onDeviceDeath(route, decoded, nowMs)
                else -> emptyList()
            }
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    fun poll(nowMs: Long): List<SpEffect> {
        val effects = mutableListOf<SpEffect>()
        if (reorderTimeoutMs < 0) return effects
        for (edge in edges.values) {
            val since = edge.gapSince ?: continue
            if (edge.rebirthPending) continue
            if (nowMs - since >= reorderTimeoutMs) {
                edge.buffer.clear()
                edge.gapSince = null
                edge.rebirthPending = true
                effects += SpEffect.Rebirth(edge.group, edge.edge)
            }
        }
        return effects
    }

    private fun onNodeBirth(route: Route, payload: SparkplugCodec.Payload, nowMs: Long): List<SpEffect> {
        val edge = edges.getOrPut(route.key) { Edge(route.group, route.edge) }
        val effects = mutableListOf<SpEffect>()
        edge.metrics.filter { it.key.device.isNotEmpty() || payload.metrics.none { metric -> metric.name == it.key.name } }
            .forEach { effects += stale(edge, it.key, it.value.value, it.value.datatype, payload.timestamp, nowMs) }
        edge.aliases.clear()
        edge.buffer.clear()
        edge.gapSince = null
        edge.online = true
        edge.rebirthPending = false
        val seq = payload.seq ?: 0
        edge.expected = (seq + 1) and 0xFF
        if (seq != 0) requestRebirth(edge, effects)
        payload.metrics.forEach { metric ->
            if (metric.name.isEmpty()) return@forEach
            if (metric.name == "bdSeq") edge.bdSeq = (metric.value as? Number)?.toLong()
            register(edge, "", metric)
            effects += sample(edge, "", metric, payload, nowMs, birth = true, status = 0)
        }
        return effects
    }

    private fun onDeviceBirth(route: Route, payload: SparkplugCodec.Payload, nowMs: Long): List<SpEffect> {
        val edge = edges.getOrPut(route.key) { Edge(route.group, route.edge) }
        val effects = mutableListOf<SpEffect>()
        val names = payload.metrics.map { it.name }.toSet()
        edge.metrics.filter { it.key.device == route.device && it.key.name !in names }
            .forEach { effects += stale(edge, it.key, it.value.value, it.value.datatype, payload.timestamp, nowMs) }
        payload.metrics.forEach { metric ->
            if (metric.name.isEmpty()) return@forEach
            register(edge, route.device, metric)
            effects += sample(edge, route.device, metric, payload, nowMs, birth = true, status = 0)
        }
        if (!edge.online) requestRebirth(edge, effects)
        else noteSequence(edge, payload.seq, nowMs, apply = null, effects = effects)
        return effects
    }

    private fun onData(route: Route, payload: SparkplugCodec.Payload, nowMs: Long, device: String): List<SpEffect> {
        val edge = edges[route.key] ?: edges.getOrPut(route.key) { Edge(route.group, route.edge) }
        val effects = mutableListOf<SpEffect>()
        if (!edge.online) {
            requestRebirth(edge, effects)
            return effects
        }
        val resolved = payload.metrics.mapNotNull { resolve(edge, device, it, effects) }
        noteSequence(edge, payload.seq, nowMs, apply = {
            resolved.map { (key, metric) -> sample(edge, key.device, metric, payload, nowMs, birth = false, status = 0) }
        }, effects = effects)
        return effects
    }

    private fun onNodeDeath(route: Route, payload: SparkplugCodec.Payload, nowMs: Long): List<SpEffect> {
        val edge = edges[route.key] ?: return emptyList()
        val bdSeq = payload.metrics.firstOrNull { it.name == "bdSeq" }?.value as? Number ?: return emptyList()
        if (edge.bdSeq == null || edge.bdSeq != bdSeq.toLong()) return emptyList()
        edge.online = false
        edge.buffer.clear()
        edge.gapSince = null
        return edge.metrics.map { stale(edge, it.key, it.value.value, it.value.datatype, payload.timestamp, nowMs) }
    }

    private fun onDeviceDeath(route: Route, payload: SparkplugCodec.Payload, nowMs: Long): List<SpEffect> {
        val edge = edges[route.key] ?: return emptyList()
        val effects = mutableListOf<SpEffect>()
        if (!edge.online) {
            requestRebirth(edge, effects)
            return effects
        }
        noteSequence(edge, payload.seq, nowMs, apply = {
            edge.metrics.filter { it.key.device == route.device }
                .map { stale(edge, it.key, it.value.value, it.value.datatype, payload.timestamp, nowMs) }
        }, effects = effects)
        return effects
    }

    /**
     * @return true when [apply] should be used now. Ahead-of-sequence data is buffered.
     * Births call this with a null [apply] after they have already emitted their metrics.
     */
    private fun noteSequence(
        edge: Edge,
        seq: Int?,
        nowMs: Long,
        apply: (() -> List<SpEffect>)?,
        effects: MutableList<SpEffect>,
    ) {
        val sequence = seq ?: return
        val delta = (sequence - edge.expected) and 0xFF
        when {
            delta == 0 -> {
                if (apply != null) effects += apply()
                advance(edge, effects)
            }
            delta in 1..127 -> {
                if (apply != null) edge.buffer[sequence] = apply()
                if (edge.gapSince == null) edge.gapSince = nowMs
                if (reorderTimeoutMs == 0L && !edge.rebirthPending) {
                    edge.buffer.clear()
                    edge.gapSince = null
                    edge.rebirthPending = true
                    effects += SpEffect.Rebirth(edge.group, edge.edge)
                }
            }
            else -> {
                // 128..255 is an old or duplicate sequence. DATA is ignored. Births were already applied.
            }
        }
    }

    private fun advance(edge: Edge, effects: MutableList<SpEffect>) {
        edge.expected = (edge.expected + 1) and 0xFF
        while (true) {
            val next = edge.buffer.remove(edge.expected) ?: break
            effects += next
            edge.expected = (edge.expected + 1) and 0xFF
        }
        if (edge.buffer.isEmpty()) edge.gapSince = null
    }

    private fun resolve(
        edge: Edge,
        device: String,
        metric: SparkplugCodec.Metric,
        effects: MutableList<SpEffect>,
    ): Pair<MetricKey, SparkplugCodec.Metric>? {
        val name = when {
            metric.name.isNotEmpty() -> metric.name
            metric.alias != null -> edge.aliases[metric.alias]?.name
            else -> null
        }
        if (name == null) {
            requestRebirth(edge, effects)
            return null
        }
        val keyDevice = if (metric.name.isEmpty()) edge.aliases[metric.alias]?.device ?: device else device
        val key = MetricKey(keyDevice, name)
        if (!edge.metrics.containsKey(key)) {
            requestRebirth(edge, effects)
            return null
        }
        if (metric.alias != null && metric.name.isNotEmpty() && metric.name != REBIRTH) edge.aliases[metric.alias] = key
        val named = if (metric.name.isNotEmpty()) metric else SparkplugCodec.Metric(
            name, metric.alias, edge.metrics.getValue(key).datatype, metric.timestamp, metric.isNull, metric.value,
        )
        return key to named
    }

    private fun register(edge: Edge, device: String, metric: SparkplugCodec.Metric) {
        if (metric.name.isEmpty()) return
        val key = MetricKey(device, metric.name)
        edge.metrics[key] = MetricState(metric.value, metric.datatype)
        if (metric.alias != null && metric.name != REBIRTH) edge.aliases[metric.alias] = key
    }

    private fun requestRebirth(edge: Edge, effects: MutableList<SpEffect>) {
        if (edge.rebirthPending) return
        edge.rebirthPending = true
        effects += SpEffect.Rebirth(edge.group, edge.edge)
    }

    private fun sample(
        edge: Edge,
        device: String,
        metric: SparkplugCodec.Metric,
        payload: SparkplugCodec.Payload,
        nowMs: Long,
        birth: Boolean,
        status: Long,
    ): SpEffect.Sample {
        val key = MetricKey(device, metric.name)
        edge.metrics[key] = MetricState(metric.value, metric.datatype)
        return SpEffect.Sample(
            address = Addresses.sparkplug(edge.group, edge.edge, device, metric.name),
            group = edge.group,
            edge = edge.edge,
            device = device,
            metric = metric.name,
            value = metric.value,
            datatype = metric.datatype,
            tagDataType = SparkplugCodec.tagDataType(metric.datatype),
            writable = metric.name != "bdSeq",
            statusCode = status,
            timestamp = metric.timestamp ?: payload.timestamp.takeIf { it != 0L } ?: nowMs,
            birth = birth,
        )
    }

    private fun stale(edge: Edge, key: MetricKey, value: Any?, datatype: Int, payloadTimestamp: Long, nowMs: Long): SpEffect.Sample {
        edge.metrics[key] = MetricState(value, datatype)
        return SpEffect.Sample(
            address = Addresses.sparkplug(edge.group, edge.edge, key.device, key.name),
            group = edge.group,
            edge = edge.edge,
            device = key.device,
            metric = key.name,
            value = value,
            datatype = datatype,
            tagDataType = SparkplugCodec.tagDataType(datatype),
            writable = key.name != "bdSeq",
            statusCode = SparkplugCodec.STALE,
            timestamp = payloadTimestamp.takeIf { it != 0L } ?: nowMs,
            birth = false,
        )
    }

    private fun route(topic: String): Route? {
        if (!topic.startsWith("spBv1.0/")) return null
        val parts = topic.split('/')
        if (parts.size >= 3 && parts[1] == "STATE") return Route("", "STATE", parts.drop(2).joinToString("/"), "")
        if (parts.size < 4) return null
        val device = if (parts.size > 4) parts.drop(4).joinToString("/") else ""
        return Route(parts[1], parts[2], parts[3], device)
    }

    private class Route(val group: String, val type: String, val edge: String, val device: String) {
        val key = "$group/$edge"
    }

    private class Edge(val group: String, val edge: String) {
        var expected: Int = 0
        var online: Boolean = false
        var rebirthPending: Boolean = false
        var gapSince: Long? = null
        var bdSeq: Long? = null
        val aliases = HashMap<Long, MetricKey>()
        val metrics = HashMap<MetricKey, MetricState>()
        val buffer = HashMap<Int, List<SpEffect>>()
    }

    private class MetricState(val value: Any?, val datatype: Int)
    private data class MetricKey(val device: String, val name: String)

    companion object {
        const val REBIRTH = "Node Control/Rebirth"

        fun rebirthPayload(nowMs: Long): ByteArray = SparkplugCodec.encode(
            SparkplugCodec.Payload(
                timestamp = nowMs,
                seq = null,
                metrics = listOf(SparkplugCodec.Metric(REBIRTH, null, SparkplugCodec.BOOLEAN, null, false, true)),
            ),
        )

        fun commandPayload(metric: String, datatype: Int, value: Any?, nowMs: Long): ByteArray {
            val isNull = value == null
            return SparkplugCodec.encode(
                SparkplugCodec.Payload(
                    timestamp = nowMs,
                    seq = null,
                    metrics = listOf(SparkplugCodec.Metric(metric, null, datatype, nowMs, isNull, value)),
                ),
            )
        }
    }
}

internal sealed class SpEffect {
    data object None : SpEffect()
    data class Rebirth(val group: String, val edge: String) : SpEffect()
    data class Sample(
        val address: String,
        val group: String,
        val edge: String,
        val device: String,
        val metric: String,
        val value: Any?,
        val datatype: Int,
        val tagDataType: String?,
        val writable: Boolean,
        val statusCode: Long,
        val timestamp: Long,
        val birth: Boolean,
    ) : SpEffect()
}
