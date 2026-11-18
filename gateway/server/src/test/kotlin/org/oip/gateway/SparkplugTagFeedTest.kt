package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.mqtt.EdgeTagValue
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.util.concurrent.CopyOnWriteArrayList

/** The edge node's view of the tag engine: values, data types, change listeners, and writes. */
class SparkplugTagFeedTest {
    @Test fun readsSubscribesAndWritesThroughTheEngine() {
        val engine = TagEngine()
        engine.configure(TagConfig(path = "Line/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 12.5, valueSet = true))
        val feed = SparkplugTagFeed(engine)

        val read = feed.read("[default]Line/Speed")
        assertEquals(12.5, read.value)
        assertTrue(read.good)
        assertEquals("DOUBLE", read.dataType)
        assertTrue(read.epochMillis > 0)

        val seen = CopyOnWriteArrayList<EdgeTagValue>()
        val sub = feed.subscribe("[default]Line/Speed") { value ->
            seen += value
        }
        assertTrue(feed.write("[default]Line/Speed", 14.0))
        assertEquals(14.0, engine.read("[default]Line/Speed").value)
        assertEquals(14.0, seen.last().value)
        assertNull(seen.last().dataType, "listeners do not look up the data type")
        sub.close()
        feed.write("[default]Line/Speed", 15.0)
        assertEquals(14.0, seen.last().value, "closed subscription is not called")

        val missing = feed.read("[default]Line/Nope")
        assertFalse(missing.good)
        assertNull(missing.dataType)
        assertFalse(feed.write("[default]Line/Nope", 1))
    }

    /**
     * Review-2 F14: a Sparkplug command writes as the fixed user `sparkplug:<edge>` (no roles, default zone), so a tag
     * whose write permissions name a role refuses it, and every attempt is audited with the edge as the actor.
     */
    @Test fun edgeCommandsWriteAsTheEdgeNodeAndAreAudited() {
        val engine = TagEngine()
        val audit = org.oip.security.ChainedAuditLog(
            org.oip.security.MemoryAuditSink(), ByteArray(32) { 1 },
            org.oip.security.MacAuditAnchor(org.oip.security.MemoryAuditAnchorStore(), ByteArray(32) { 2 }),
        )
        engine.audit = audit
        engine.configure(TagConfig(path = "Line/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 12.5, valueSet = true))
        engine.configure(TagConfig(path = "Line/Recipe", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true,
            writeAccess = org.oip.tags.TagAccess(listOf("operator"), emptyList())))
        val feed = SparkplugTagFeed(engine)

        assertTrue(feed.write("[default]Line/Speed", 14.0, "edge-1"))
        assertEquals(14.0, engine.read("[default]Line/Speed").value)
        assertFalse(feed.write("[default]Line/Recipe", 9.0, "edge-1"), "write permissions apply to the edge identity")
        assertEquals(1.0, engine.read("[default]Line/Recipe").value)

        val rows = audit.query(org.oip.security.AuditQuery(action = org.oip.tags.TAG_WRITE_ACTION)).records
        assertEquals(2, rows.size, "$rows")
        assertTrue(rows.all { it.actor == "sparkplug:edge-1" && it.context == SparkplugTagFeed.AUDIT_CONTEXT }, "$rows")
        assertEquals(setOf(org.oip.security.AuditStatus.SUCCESS, org.oip.security.AuditStatus.FAILURE), rows.map { it.status }.toSet())
    }
}
