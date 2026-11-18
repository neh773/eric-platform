package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tag/UDT export and import (TAG-005): -
 */
class TagExchangeTest {
    private fun num(v: Any?): Double = (v as Number).toDouble()

    private fun names(exported: String, section: String): List<String> =
        (com.fasterxml.jackson.databind.ObjectMapper().readValue(exported, Map::class.java)[section] as List<*>)
            .map { (it as Map<*, *>)["name"] as String }
    private fun tagId(name: String) = ResourceId(TAGS_MODULE, Scope.GATEWAY, TAG_KIND, name)
    private fun udtId(name: String) = ResourceId(TAGS_MODULE, Scope.GATEWAY, UDT_KIND, name)

    private class Harness : AutoCloseable {
        val registry = SchemaRegistry()
        val store = FileConfigStore(Files.createTempDirectory("oip-tag-exchange"), registry)
        val engine = TagEngine()
        val binding = TagConfigBinding(engine, store, registry).also { it.attach() }
        val events = CopyOnWriteArrayList<ConfigEvent>().also { list -> store.subscribe { list += it } }

        fun wipe() {
            store.list(TAGS_MODULE, Scope.GATEWAY, TAG_KIND).forEach { store.delete(it.id) }
            store.list(TAGS_MODULE, Scope.GATEWAY, UDT_KIND).forEach { store.delete(it.id) }
        }

        override fun close() {
            binding.close()
            store.close()
        }
    }

    private fun seed(h: Harness) {
        h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("plant-speed", TAGS_MODULE), mapOf(
            "path" to "Plant/Speed", "type" to "memory", "dataType" to "Double", "value" to 3.5,
            "documentation" to "line speed",
        )))
        h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("motor", TAGS_MODULE), mapOf(
            "path" to "Types/Motor",
            "parameters" to listOf(mapOf("name" to "Gain", "dataType" to "Double", "default" to 2)),
            "members" to listOf(
                mapOf("name" to "In", "type" to "memory", "dataType" to "Double", "value" to 3),
                mapOf("name" to "Out", "type" to "expression", "dataType" to "Double", "expression" to "{[.]In} * {Gain}"),
            ),
        )))
        h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("motor-1", TAGS_MODULE), mapOf(
            "path" to "Line/M1", "type" to "udt", "typeId" to "Types/Motor",
            "parameters" to mapOf("Gain" to 4),
        )))
    }

    /** Types/VariableMotor inherits Types/BaseMotor and adds a member. */
    private fun seedInheritance(h: Harness) {
        h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("base-motor", TAGS_MODULE), mapOf(
            "path" to "Types/BaseMotor",
            "members" to listOf(mapOf("name" to "Running", "type" to "memory", "dataType" to "Boolean", "value" to true)),
        )))
        h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("variable-motor", TAGS_MODULE), mapOf(
            "path" to "Types/VariableMotor", "parent" to "Types/BaseMotor",
            "members" to listOf(mapOf("name" to "Speed", "type" to "memory", "dataType" to "Double", "value" to 42.0)),
        )))
        h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("vmotor-1", TAGS_MODULE), mapOf(
            "path" to "Drive/V1", "type" to "udt", "typeId" to "Types/VariableMotor",
        )))
    }

    /** Types/Pump nests a Types/Motor instance as one of its members. */
    private fun seedNested(h: Harness) {
        h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("motor", TAGS_MODULE), mapOf(
            "path" to "Types/Motor",
            "members" to listOf(mapOf("name" to "Rpm", "type" to "memory", "dataType" to "Double", "value" to 5.0)),
        )))
        h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("pump", TAGS_MODULE), mapOf(
            "path" to "Types/Pump",
            "members" to listOf(
                mapOf("name" to "Drive", "type" to "udt", "typeId" to "Types/Motor"),
                mapOf("name" to "Pressure", "type" to "memory", "dataType" to "Double", "value" to 1.0),
            ),
        )))
        h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("pump-1", TAGS_MODULE), mapOf(
            "path" to "Line/P1", "type" to "udt", "typeId" to "Types/Pump",
        )))
    }

    private fun assertRoundTrips(h: Harness) {
        val before = TagExchange.export(h.store)
        h.wipe()
        val report = TagExchange.import(h.store, before, TagExchange.CollisionPolicy.OVERWRITE)
        assertTrue(report.failed.isEmpty(), report.failed.toString())
        assertEquals(before, TagExchange.export(h.store))
    }

    @Test
    fun exportWipeImportRoundTripIsByteIdentical() {
        Harness().use { h ->
            seed(h)
            assertEquals(12.0, num(h.engine.read("Line/M1/Out").value))
            val before = TagExchange.export(h.store)
            h.wipe()
            assertEquals(QualityCode.BAD_NOT_FOUND, h.engine.read("Plant/Speed").quality.code)

            val report = TagExchange.import(h.store, before, TagExchange.CollisionPolicy.OVERWRITE)
            assertTrue(report.failed.isEmpty())
            assertEquals(
                listOf("tags/gateway/Udt/motor", "tags/gateway/Tag/motor-1", "tags/gateway/Tag/plant-speed"),
                report.created,
            )
            assertEquals(before, TagExchange.export(h.store))
            assertEquals(3.5, num(h.engine.read("Plant/Speed").value))
            assertEquals(12.0, num(h.engine.read("Line/M1/Out").value), "parameter override survives")
        }
    }

    @Test
    fun udtInheritanceRoundTrips() {
        Harness().use { h ->
            seedInheritance(h)
            assertRoundTrips(h)
            assertEquals(true, h.engine.read("Drive/V1/Running").value, "member inherited from the parent UDT")
            assertEquals(42.0, num(h.engine.read("Drive/V1/Speed").value), "member declared on the child UDT")
        }
    }

    @Test
    fun nestedUdtRoundTrips() {
        Harness().use { h ->
            seedNested(h)
            assertRoundTrips(h)
            assertEquals(5.0, num(h.engine.read("Line/P1/Drive/Rpm").value), "member of the nested UDT")
            assertEquals(1.0, num(h.engine.read("Line/P1/Pressure").value))
        }
    }

    @Test
    fun overwritePolicyReplacesWholeConfig() {
        Harness().use { h ->
            seed(h)
            val doc = """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"plant-speed","spec":{"provider":"default","path":"Plant/Speed","type":"memory","dataType":"Double","value":9.0}}
            ]}"""
            val report = TagExchange.import(h.store, doc, TagExchange.CollisionPolicy.OVERWRITE)
            assertTrue(report.failed.isEmpty())
            assertEquals(listOf("tags/gateway/Tag/plant-speed"), report.updated)
            assertNull(h.store.get(tagId("plant-speed"))!!.spec["documentation"])
            assertEquals(9.0, num(h.engine.read("Plant/Speed").value))
        }
    }

    @Test
    fun mergePolicyKeepsFieldsNotInImport() {
        Harness().use { h ->
            seed(h)
            val doc = """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"plant-speed","spec":{"value":9.0}}
            ]}"""
            val report = TagExchange.import(h.store, doc, TagExchange.CollisionPolicy.MERGE)
            assertTrue(report.failed.isEmpty())
            assertEquals(listOf("tags/gateway/Tag/plant-speed"), report.updated)
            assertEquals("line speed", h.store.get(tagId("plant-speed"))!!.spec["documentation"])
            assertEquals(9.0, num(h.engine.read("Plant/Speed").value))
        }
    }

    @Test
    fun ignorePolicyLeavesExistingUntouchedButAddsNew() {
        Harness().use { h ->
            seed(h)
            val doc = """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"plant-speed","spec":{"provider":"default","path":"Plant/Speed","type":"memory","dataType":"Double","value":9.0}},
                {"name":"new-tag","spec":{"provider":"default","path":"Plant/New","type":"memory","dataType":"Double","value":1.0}}
            ]}"""
            val report = TagExchange.import(h.store, doc, TagExchange.CollisionPolicy.IGNORE)
            assertTrue(report.failed.isEmpty())
            assertEquals(listOf("tags/gateway/Tag/plant-speed"), report.skipped)
            assertEquals(listOf("tags/gateway/Tag/new-tag"), report.created)
            assertEquals(3.5, num(h.engine.read("Plant/Speed").value))
            assertEquals(1.0, num(h.engine.read("Plant/New").value))
        }
    }

    /** Asserts the import failed on exactly [badResource], wrote nothing from the batch, and fired no event. */
    private fun assertRejected(h: Harness, doc: String, badResource: String, messagePart: String, untouched: List<ResourceId>) {
        h.events.clear()
        val report = TagExchange.import(h.store, doc, TagExchange.CollisionPolicy.OVERWRITE)
        assertEquals(listOf(badResource), report.failed.map { it.resource }.distinct(), report.failed.toString())
        assertTrue(report.failed.any { it.message.contains(messagePart) }, report.failed.toString())
        assertTrue(report.created.isEmpty() && report.updated.isEmpty())
        untouched.forEach { assertNull(h.store.get(it), "$it must not be written") }
        assertTrue(h.events.isEmpty(), "a rejected import fires no change event: ${h.events}")
    }

    @Test
    fun badExpressionBlocksTheWholeImport() {
        Harness().use { h ->
            seed(h)
            assertRejected(h, """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"good-tag","spec":{"provider":"default","path":"Plant/Good","type":"memory","dataType":"Double","value":1.0}},
                {"name":"bad-expr","spec":{"provider":"default","path":"Plant/Bad","type":"expression","dataType":"Double","expression":"1 +"}}
            ]}""", "tags/gateway/Tag/bad-expr", "bad expression", listOf(tagId("good-tag"), tagId("bad-expr")))
            assertEquals(QualityCode.BAD_NOT_FOUND, h.engine.read("Plant/Good").quality.code)
        }
    }

    @Test
    fun schemaErrorBlocksTheWholeImport() {
        Harness().use { h ->
            seed(h)
            assertRejected(h, """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"good-tag","spec":{"provider":"default","path":"Plant/Good","type":"memory","dataType":"Double","value":1.0}},
                {"name":"bad-schema","spec":{"provider":"default","path":"Plant/BadSchema","type":"memory","nope":1}}
            ]}""", "tags/gateway/Tag/bad-schema", "nope", listOf(tagId("good-tag"), tagId("bad-schema")))
        }
    }

    @Test
    fun unknownUdtTypeIdBlocksTheWholeImport() {
        Harness().use { h ->
            seed(h)
            assertRejected(h, """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"good-tag","spec":{"provider":"default","path":"Plant/Good","type":"memory","dataType":"Double","value":1.0}},
                {"name":"ghost","spec":{"provider":"default","path":"Plant/Ghost","type":"udt","typeId":"Types/DoesNotExist"}}
            ]}""", "tags/gateway/Tag/ghost", "unknown UDT", listOf(tagId("good-tag"), tagId("ghost")))
        }
    }

    @Test
    fun unknownParentUdtBlocksTheWholeImport() {
        Harness().use { h ->
            seed(h)
            assertRejected(h, """{"version":1,"provider":"default","tags":[
                {"name":"good-tag","spec":{"provider":"default","path":"Plant/Good","type":"memory","dataType":"Double","value":1.0}}
            ],"udts":[
                {"name":"orphan","spec":{"provider":"default","path":"Types/Orphan","parent":"Types/DoesNotExist"}}
            ]}""", "tags/gateway/Udt/orphan", "unknown parent UDT", listOf(tagId("good-tag"), udtId("orphan")))
        }
    }

    @Test
    fun folderExportIncludesTheUdtsItsInstancesNeedAndImportsStandalone() {
        Harness().use { h ->
            seed(h)
            seedInheritance(h)
            h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("pump", TAGS_MODULE), mapOf(
                "path" to "Types/Pump",
                "members" to listOf(mapOf("name" to "Drive", "type" to "udt", "typeId" to "Types/VariableMotor")),
            )))
            h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("pump-1", TAGS_MODULE), mapOf(
                "path" to "Line/P1", "type" to "udt", "typeId" to "Types/Pump",
            )))
            h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("unused", TAGS_MODULE), mapOf(
                "path" to "Types/Unused",
                "members" to listOf(mapOf("name" to "X", "type" to "memory", "dataType" to "Double")),
            )))

            // Line/M1 -> Types/Motor; Line/P1 -> Types/Pump -> (nested) Types/VariableMotor -> (parent) Types/BaseMotor.
            val exported = TagExchange.export(h.store, folderPath = "Line")
            assertEquals(listOf("base-motor", "motor", "pump", "variable-motor"), names(exported, "udts"))
            assertEquals(listOf("motor-1", "pump-1"), names(exported, "tags"))

            Harness().use { empty ->
                val report = TagExchange.import(empty.store, exported, TagExchange.CollisionPolicy.OVERWRITE)
                assertTrue(report.failed.isEmpty(), report.failed.toString())
                assertEquals(exported, TagExchange.export(empty.store, folderPath = "Line"))
                assertEquals(12.0, num(empty.engine.read("Line/M1/Out").value))
                assertEquals(true, empty.engine.read("Line/P1/Drive/Running").value)
                assertEquals(42.0, num(empty.engine.read("Line/P1/Drive/Speed").value))
            }
        }
    }

    @Test
    fun importRejectsADocumentForAnotherProvider() {
        Harness().use { h ->
            seed(h)
            h.events.clear()
            val doc = """{"version":1,"provider":"other","udts":[],"tags":[
                {"name":"x","spec":{"provider":"other","path":"X","type":"memory","dataType":"Double"}}
            ]}"""
            val report = TagExchange.import(h.store, doc, TagExchange.CollisionPolicy.OVERWRITE, provider = "default")
            assertEquals(1, report.failed.size)
            assertTrue(report.failed[0].message.contains("does not match target provider"), report.failed[0].message)
            assertNull(h.store.get(tagId("x")))
            assertTrue(h.events.isEmpty())
        }
    }
    @Test
    fun importRejectsAnEntryThatLandsInAnotherProvider() {
        Harness().use { h ->
            seed(h)
            assertRejected(h, """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"good-tag","spec":{"provider":"default","path":"Plant/Good","type":"memory","dataType":"Double","value":1.0}},
                {"name":"stray","spec":{"provider":"other","path":"Plant/Stray","type":"memory","dataType":"Double","value":1.0}}
            ]}""", "tags/gateway/Tag/stray", "does not match document provider", listOf(tagId("good-tag"), tagId("stray")))
        }
    }

    @Test
    fun importRejectsACollisionWithAResourceOfAnotherProvider() {
        Harness().use { h ->
            h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("shared-name", TAGS_MODULE), mapOf(
                "provider" to "other", "path" to "Other/Speed", "type" to "memory", "dataType" to "Double", "value" to 7.0,
            )))
            val doc = """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"shared-name","spec":{"provider":"default","path":"Plant/Speed","type":"memory","dataType":"Double","value":9.0}}
            ]}"""
            for (policy in TagExchange.CollisionPolicy.entries) {
                h.events.clear()
                val report = TagExchange.import(h.store, doc, policy)
                assertEquals(listOf("tags/gateway/Tag/shared-name"), report.failed.map { it.resource }, "$policy: ${report.failed}")
                assertTrue(report.failed[0].message.contains("belongs to provider 'other'"), report.failed[0].message)
                assertTrue(report.created.isEmpty() && report.updated.isEmpty() && report.skipped.isEmpty())
                assertEquals("other", h.store.get(tagId("shared-name"))!!.spec["provider"], "$policy must not move the resource")
                assertTrue(h.events.isEmpty())
            }
        }
    }

    @Test
    fun mergeCannotMoveAResourceToAnotherProvider() {
        Harness().use { h ->
            // RFC 7386: null removes the key, which would drop the resource back to the default provider.
            h.store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("line2-speed", TAGS_MODULE), mapOf(
                "provider" to "line2", "path" to "Speed", "type" to "memory", "dataType" to "Double", "value" to 1.0,
            )))
            h.events.clear()
            val report = TagExchange.import(h.store, """{"version":1,"provider":"line2","udts":[],"tags":[
                {"name":"line2-speed","spec":{"provider":null,"value":2.0}}
            ]}""", TagExchange.CollisionPolicy.MERGE)
            assertEquals(listOf("tags/gateway/Tag/line2-speed"), report.failed.map { it.resource }, report.failed.toString())
            assertTrue(report.failed[0].message.contains("would land in provider 'default'"), report.failed[0].message)
            assertEquals("line2", h.store.get(tagId("line2-speed"))!!.spec["provider"])
            assertTrue(h.events.isEmpty())
        }
    }

    @Test
    fun udtOfAnotherProviderDoesNotSatisfyAnImportedTypeId() {
        Harness().use { h ->
            h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("other-motor", TAGS_MODULE), mapOf(
                "provider" to "other", "path" to "Types/Motor",
                "members" to listOf(mapOf("name" to "Rpm", "type" to "memory", "dataType" to "Double", "value" to 5.0)),
            )))
            assertRejected(h, """{"version":1,"provider":"default","udts":[],"tags":[
                {"name":"m1","spec":{"provider":"default","path":"Line/M1","type":"udt","typeId":"Types/Motor"}}
            ]}""", "tags/gateway/Tag/m1", "unknown UDT 'Types/Motor'", listOf(tagId("m1")))
            assertRejected(h, """{"version":1,"provider":"default","tags":[],"udts":[
                {"name":"child","spec":{"provider":"default","path":"Types/Child","parent":"Types/Motor"}}
            ]}""", "tags/gateway/Udt/child", "unknown parent UDT 'Types/Motor'", listOf(udtId("child")))
        }
    }

    @Test
    fun importIntoANonDefaultProviderResolvesItsOwnUdts() {
        Harness().use { h ->
            h.store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("line2-motor", TAGS_MODULE), mapOf(
                "provider" to "line2", "path" to "Types/Motor",
                "members" to listOf(mapOf("name" to "Rpm", "type" to "memory", "dataType" to "Double", "value" to 5.0)),
            )))
            val report = TagExchange.import(h.store, """{"version":1,"provider":"line2","udts":[],"tags":[
                {"name":"line2-m1","spec":{"provider":"line2","path":"Line/M1","type":"udt","typeId":"Types/Motor"}}
            ]}""", TagExchange.CollisionPolicy.OVERWRITE, provider = "line2")
            assertTrue(report.failed.isEmpty(), report.failed.toString())
            assertEquals(listOf("tags/gateway/Tag/line2-m1"), report.created)
        }
    }
}
