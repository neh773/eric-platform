package org.oip.backup

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import java.nio.file.Path

/**
 * PRO-003: export a project and import it, including skip and overwrite when a resource already exists.
 */
class ProjectExchangeTest {
    @TempDir lateinit var tmp: Path
    private val schema = mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer")))
    private fun registry() = SchemaRegistry().apply { register("m", "Thing", schema) }
    private fun store(root: Path = tmp.resolve("config")) = FileConfigStore(root, registry())
    private fun thing(name: String, x: Int, version: Long = 0) = Resource("Thing", Metadata(name, "m", version = version), mapOf("x" to x))

    @Test fun exportImportIntoEmptyGateway() {
        val src = store()
        src.put(Scope.project("plant"), thing("main", 4))
        src.put(Scope.project("plant"), thing("other", 9))
        src.put(Scope.project("other"), thing("nope", 1))
        src.put(Scope.GATEWAY, thing("gw", 2))
        val zip = ProjectExchange(src).export("plant")
        val names = ZipIo.read(zip).keys
        assertTrue(names.contains("oip-project.json"))
        assertEquals(2, names.count { it.startsWith("files/") })
        assertTrue(names.none { it.contains("/other/") && it.startsWith("files/m/project/other") })

        val destRoot = tmp.resolve("empty")
        val dest = store(destRoot)
        val result = ProjectExchange(dest).import("plant", zip, ConflictPolicy.OVERWRITE)
        assertEquals(ImportResult("plant", "overwrite", created = 2, overwritten = 0, skipped = 0), result)
        assertEquals(listOf("plant"), ProjectExchange(dest).listProjects())
        assertEquals(4, dest.get(ResourceId("m", Scope.project("plant"), "Thing", "main"))!!.spec["x"])
        assertEquals(9, dest.get(ResourceId("m", Scope.project("plant"), "Thing", "other"))!!.spec["x"])
        assertEquals(1L, dest.get(ResourceId("m", Scope.project("plant"), "Thing", "main"))!!.metadata.version)
        assertNull(dest.get(ResourceId("m", Scope.GATEWAY, "Thing", "gw")))
        src.close(); dest.close()
    }

    @Test fun importCanRetargetProjectName() {
        val src = store()
        src.put(Scope.project("plant"), thing("main", 4))
        val dest = store(tmp.resolve("b"))
        ProjectExchange(dest).import("site-2", ProjectExchange(src).export("plant"), ConflictPolicy.SKIP)
        assertEquals(4, dest.get(ResourceId("m", Scope.project("site-2"), "Thing", "main"))!!.spec["x"])
        assertTrue(ProjectExchange(dest).listProjects() == listOf("site-2"))
        src.close(); dest.close()
    }

    @Test fun skipLeavesExistingAndOverwriteReplaces() {
        val src = store()
        src.put(Scope.project("plant"), thing("main", 8))
        src.put(Scope.project("plant"), thing("extra", 2))
        val zip = ProjectExchange(src).export("plant")

        val dest = store(tmp.resolve("b"))
        val exchange = ProjectExchange(dest)
        dest.put(Scope.project("plant"), thing("main", 1))
        dest.put(Scope.project("kept"), thing("z", 5))
        val skipped = exchange.import("plant", zip, ConflictPolicy.SKIP)
        assertEquals(ImportResult("plant", "skip", created = 1, overwritten = 0, skipped = 1), skipped)
        assertEquals(1, dest.get(ResourceId("m", Scope.project("plant"), "Thing", "main"))!!.spec["x"])
        assertEquals(1L, dest.get(ResourceId("m", Scope.project("plant"), "Thing", "main"))!!.metadata.version)
        assertEquals(2, dest.get(ResourceId("m", Scope.project("plant"), "Thing", "extra"))!!.spec["x"])
        assertEquals(5, dest.get(ResourceId("m", Scope.project("kept"), "Thing", "z"))!!.spec["x"])

        val replaced = exchange.import("plant", zip, ConflictPolicy.OVERWRITE)
        assertEquals(ImportResult("plant", "overwrite", created = 0, overwritten = 2, skipped = 0), replaced)
        val main = dest.get(ResourceId("m", Scope.project("plant"), "Thing", "main"))!!
        assertEquals(8, main.spec["x"])
        assertEquals(2L, main.metadata.version)
        assertEquals(5, dest.get(ResourceId("m", Scope.project("kept"), "Thing", "z"))!!.spec["x"])
        src.close(); dest.close()
    }

    @Test fun invalidResourceAppliesNothing() {
        val src = store()
        src.put(Scope.project("plant"), thing("main", 1))
        val good = ZipIo.read(ProjectExchange(src).export("plant"))
        val badYaml = good.keys.first { it.endsWith(".yaml") }
        val broken = good.toMutableMap()
        broken[badYaml] = broken.getValue(badYaml).toString(Charsets.UTF_8).replace("x: 1", "x: nope").toByteArray()
        val dest = store(tmp.resolve("b"))
        dest.put(Scope.project("plant"), thing("stay", 3))
        assertThrows<org.oip.config.ValidationException> { ProjectExchange(dest).import("plant", ZipIo.write(broken.toList()), ConflictPolicy.OVERWRITE) }
        assertEquals(listOf("stay"), dest.list(scope = Scope.project("plant")).map { it.id.name })
        assertEquals(3, dest.get(ResourceId("m", Scope.project("plant"), "Thing", "stay"))!!.spec["x"])
        src.close(); dest.close()
    }

    @Test fun unknownPolicyAndMissingProjectRejected() {
        val s = store()
        assertThrows<IllegalArgumentException> { ConflictPolicy.parse("merge") }
        assertThrows<NoSuchElementException> { ProjectExchange(s).export("missing") }
        s.close()
    }
}
