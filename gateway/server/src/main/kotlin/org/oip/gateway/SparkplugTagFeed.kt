package org.oip.gateway

import org.oip.mqtt.EdgeTagFeed
import org.oip.mqtt.EdgeTagValue
import org.oip.security.AuditContext
import org.oip.security.SecurityZone
import org.oip.tags.Level
import org.oip.tags.TagCaller
import org.oip.tags.QualifiedValue
import org.oip.tags.TagEngine
import org.oip.tags.TagPath

/**
 * Gateway tags for Sparkplug edge nodes. The subscribe listener runs on the tag-engine writer thread
 * and only converts the value; the edge node enqueues it and publishes on its own thread (P2-14).
 */
class SparkplugTagFeed(private val engine: TagEngine) : EdgeTagFeed {
    companion object {
        /** Audit `context` of Sparkplug command writes. */
        const val AUDIT_CONTEXT = "gateway-sparkplug"

        /** The identity an edge node's commands write as. */
        fun edgeCaller(edge: String): TagCaller.User =
            TagCaller.User("sparkplug:$edge", emptySet(), SecurityZone.DEFAULT_NAME)
    }

    override fun read(path: String): EdgeTagValue = convert(engine.read(path), dataType(path))

    /** The data type is fixed at birth from [read], so the listener does not look it up (no lock taken here). */
    override fun subscribe(path: String, listener: (EdgeTagValue) -> Unit): AutoCloseable =
        engine.subscribe(path) { change -> listener(convert(change.value, null)) }

    override fun write(path: String, value: Any?): Boolean = engine.write(path, value).ok

    /**
     * A Sparkplug command write (review-2 F14). It runs as the fixed user `sparkplug:<edge>`, with no roles, in the
     * default security zone: a tag whose write permissions name roles or zones refuses it, and every attempt is
     * audited with context `gateway-sparkplug`. The metric must also be configured `writable` on the edge node.
     */
    override fun write(path: String, value: Any?, edge: String): Boolean {
        val caller = edgeCaller(edge)
        return AuditContext.call(caller.actor, "", AUDIT_CONTEXT) { engine.write(path, value, caller) }.ok
    }

    private fun dataType(path: String): String? = try {
        val parsed = TagPath.parse(path, "default")
        if (parsed.property != null || parsed.indexes.isNotEmpty() || parsed.segments.isEmpty()) null
        else engine.childMeta(parsed.provider ?: "default", parsed.folder.joinToString("/"), parsed.segments.last())?.dataType?.name
    } catch (_: RuntimeException) {
        null
    }

    private fun convert(qv: QualifiedValue, type: String?): EdgeTagValue {
        val stamp = qv.sourceTimestamp ?: qv.timestamp
        return EdgeTagValue(qv.value, qv.quality.level == Level.GOOD, stamp.toEpochMilli(), type)
    }
}
