package org.oip.tags

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.nio.file.Files

/**
 * Alarm configuration on the read-only `[system]` tags moves between gateways as an export document and an import.
 * Doc source: FEATURES ALA-040.
 */
class SystemTagAlarmExchangeTest {
    private val path = "Gateway/Performance/CPU Usage"
    private val alarms = listOf(mapOf("name" to "HighCpu", "mode" to "AboveValue", "setpointA" to 90, "priority" to "High"))

    private class Harness : AutoCloseable {
        val registry = SchemaRegistry()
        val store = FileConfigStore(Files.createTempDirectory("oip-system-alarms"), registry)
        val engine = TagEngine()
        val binding = TagConfigBinding(engine, store, registry).also { it.attach() }
        override fun close() {
            binding.close()
            store.close()
        }
    }

    private fun alarmsOnTag(engine: TagEngine, tag: String): Any? =
        engine.copyTagConfigs().firstOrNull { it.first == "system" && it.second.path == tag }?.second?.raw?.get("alarms")

    @Test fun exportThenImportRestoresSystemTagAlarmsOnAPublishedTag() {
        Harness().use { source ->
            source.engine.publishSystem(path, 1.0)
            source.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("sys-cpu", TAGS_MODULE), mapOf(
                "provider" to "system", "path" to path, "type" to "memory", "dataType" to "Double", "alarms" to alarms,
            )))
            assertEquals(alarms, alarmsOnTag(source.engine, path))
            val document = TagExchange.export(source.store, "system")
            assertTrue(document.contains("\"provider\" : \"system\""), document)
            assertTrue(document.contains("HighCpu"), document)

            Harness().use { target ->
                target.engine.publishSystem(path, 2.0)
                assertEquals(null, alarmsOnTag(target.engine, path))
                val report = TagExchange.import(target.store, document, TagExchange.CollisionPolicy.OVERWRITE, "system")
                assertEquals(emptyList<TagExchange.ImportIssue>(), report.failed)
                assertEquals(1, report.created.size)
                assertEquals(alarms, alarmsOnTag(target.engine, path))
                // The tag keeps publishing its value and keeps the alarms.
                target.engine.publishSystem(path, 95.0)
                assertEquals(95.0, target.engine.read("[system]$path").value)
                assertEquals(alarms, alarmsOnTag(target.engine, path))
                // Byte-identical round trip.
                assertEquals(document, TagExchange.export(target.store, "system"))
            }
        }
    }

    @Test fun alarmsImportedBeforeTheTagIsPublishedApplyWhenItIsPublished() {
        Harness().use { h ->
            h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("sys-cpu", TAGS_MODULE), mapOf(
                "provider" to "system", "path" to path, "type" to "memory", "alarms" to alarms,
            )))
            h.engine.publishSystem(path, 1.0)
            assertEquals(alarms, alarmsOnTag(h.engine, path))
        }
    }

    @Test fun deletingTheResourceRemovesTheAlarms() {
        Harness().use { h ->
            h.engine.publishSystem(path, 1.0)
            val id = ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, "sys-cpu")
            h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("sys-cpu", TAGS_MODULE), mapOf(
                "provider" to "system", "path" to path, "type" to "memory", "alarms" to alarms,
            )))
            assertEquals(alarms, alarmsOnTag(h.engine, path))
            h.store.delete(id)
            assertEquals(null, alarmsOnTag(h.engine, path))
        }
    }

    @Test fun aSystemDocumentCarriesAlarmsOnly() {
        Harness().use { h ->
            val document = """{"version":1,"provider":"system","udts":[],"tags":[
                {"name":"sys-x","spec":{"provider":"system","path":"Gateway/X","type":"memory","value":5,"alarms":[]}}]}"""
            val report = TagExchange.import(h.store, document, TagExchange.CollisionPolicy.OVERWRITE, "system")
            assertEquals(1, report.failed.size)
            assertTrue(report.failed[0].message.contains("alarms only"), report.failed.toString())
            assertEquals(0, h.store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND).size)
        }
    }
}
