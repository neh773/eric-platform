package org.oip.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

class DeploymentModeTest {
    @TempDir lateinit var tmp: Path
    private val registry = SchemaRegistry().apply {
        register("m", "Database", ResourceCodec.parseValue("""{"type":"object","required":["url"],"properties":{
            "url":{"type":"string","minLength":1},"debug":{"type":"boolean"},
            "pool":{"type":"object","properties":{"min":{"type":"integer"},"max":{"type":"integer"}}},
            "tags":{"type":"array","items":{"type":"string"}}}}""") as Map<String, Any?>)
    }
    private val id = ResourceId("m", Scope.GATEWAY, "Database", "main")
    private val fixtures = Paths.get(javaClass.getResource("/overlays")!!.toURI())

    @OptIn(ExperimentalPathApi::class)
    private fun store(): FileConfigStore { fixtures.resolve("config").copyToRecursively(tmp.resolve("config"), followLinks = false, overwrite = true)
        return FileConfigStore(tmp.resolve("config"), registry) }
    private fun expected(name: String) = ResourceCodec.parseValue(fixtures.resolve("expected/$name.yaml").readText())

    @ParameterizedTest @ValueSource(strings = ["none", "dev", "prod"])
    fun overlayFixturesResolveToExpectedSpec(mode: String) {
        val s = store()
        s.setActiveMode(if (mode == "none") null else mode)
        assertEquals(expected(mode), s.resolve(id)!!.spec)
        assertTrue(s.loadErrors.isEmpty(), s.loadErrors.toString())
    }

    @Test fun rawGetIsUnaffectedByMode() {
        val s = store(); s.setActiveMode("prod")
        assertEquals(expected("none"), s.get(id)!!.spec)
    }

    @Test fun activeModePersistsAndIsSwitchable() {
        store().setActiveMode("dev")
        val s = FileConfigStore(tmp.resolve("config"), registry)
        assertEquals("dev", s.activeMode())
        assertEquals(expected("dev"), s.resolve(id)!!.spec)
        s.setActiveMode("prod"); assertEquals(expected("prod"), s.resolve(id)!!.spec)
        s.setActiveMode(null); assertNull(s.activeMode()); assertEquals(expected("none"), s.resolve(id)!!.spec)
        assertThrows(IllegalArgumentException::class.java) { s.setActiveMode("Bad Mode") }
    }

    @Test fun modeWithoutOverlayResolvesToBase() {
        val s = store(); s.setActiveMode("test")
        assertEquals(expected("none"), s.resolve(id)!!.spec)
    }

    @Test fun overlayProducingInvalidSpecIsRejectedOnRead() {
        val s = store()
        s.put(Scope.mode("bad"), Resource("Database", Metadata("main", "m"), mapOf("url" to null)))
        s.setActiveMode("bad")
        assertThrows(ValidationException::class.java) { s.resolve(id) }
    }

    @Test fun mergePatchRfc7386() {
        assertEquals(mapOf("a" to "z", "c" to mapOf("d" to "e")),
            JsonMergePatch.apply(mapOf("a" to "b", "c" to mapOf("d" to "e", "f" to "g")), mapOf("a" to "z", "c" to mapOf("f" to null))))
        assertEquals(listOf(1), JsonMergePatch.apply(mapOf("a" to 1), listOf(1)))
    }
}
