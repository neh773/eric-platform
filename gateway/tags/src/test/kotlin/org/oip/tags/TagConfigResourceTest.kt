package org.oip.tags

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import java.nio.file.Files

/**
 * Tags and UDT definitions are typed resources validated by JSON Schema (SPEC §4).
 */
class TagConfigResourceTest {
    private fun num(v: Any?): Double = (v as Number).toDouble()

    @Test
    fun resourcesLoadValidateAndFollowTheStore() {
        val dir = Files.createTempDirectory("oip-tags")
        val registry = SchemaRegistry()
        val store = FileConfigStore(dir, registry)
        val engine = TagEngine()
        val binding = TagConfigBinding(engine, store, registry)
        binding.attach()
        try {
            assertThrows<ValidationException> {
                store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("bad", TAGS_MODULE), mapOf(
                    "path" to "A", "type" to "memory", "nope" to 1,
                )))
            }
            val speed = store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("plant-speed", TAGS_MODULE), mapOf(
                "path" to "Plant/Speed", "type" to "memory", "dataType" to "Double", "value" to 3.5,
            )))
            assertEquals(3.5, num(engine.read("[default]Plant/Speed").value))
            store.put(Scope.GATEWAY, Resource(UDT_KIND, Metadata("motor", TAGS_MODULE), mapOf(
                "path" to "Types/Motor",
                "parameters" to listOf(mapOf("name" to "Gain", "dataType" to "Double", "default" to 2)),
                "members" to listOf(
                    mapOf("name" to "In", "type" to "memory", "dataType" to "Double", "value" to 3),
                    mapOf("name" to "Out", "type" to "expression", "dataType" to "Double", "expression" to "{[.]In} * {Gain}"),
                ),
            )))
            store.put(Scope.GATEWAY, Resource(TAG_KIND, Metadata("motor-1", TAGS_MODULE), mapOf(
                "path" to "Line/M1", "type" to "udt", "typeId" to "Types/Motor",
                "parameters" to mapOf("Gain" to 4),
            )))
            assertEquals(12.0, num(engine.read("Line/M1/Out").value))
            store.put(Scope.GATEWAY, speed.copy(spec = speed.spec + mapOf("value" to 8)))
            assertEquals(8.0, num(engine.read("Plant/Speed").value))
            store.delete(speed.id(Scope.GATEWAY))
            assertEquals(QualityCode.BAD_NOT_FOUND, engine.read("Plant/Speed").quality.code)
            assertEquals(12.0, num(engine.read("Line/M1/Out").value))
        } finally {
            binding.close()
            store.close()
        }
    }
}
