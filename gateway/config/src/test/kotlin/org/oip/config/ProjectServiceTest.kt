package org.oip.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * PRO-002 project inheritance and PRO-004 project CRUD at the store level.
 */
class ProjectServiceTest {
    @TempDir lateinit var tmp: Path
    private lateinit var store: FileConfigStore
    private lateinit var projects: ProjectService

    @BeforeEach fun setUp() {
        val registry = SchemaRegistry()
        registry.register("m", "View", mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))))
        ProjectService.register(registry)
        store = FileConfigStore(tmp.resolve("config"), registry)
        projects = ProjectService(store).also { it.attach() }
    }

    private fun view(project: String, name: String, x: Int) {
        val id = ResourceId("m", Scope.project(project), "View", name)
        val version = store.get(id)?.metadata?.version ?: 0L
        store.put(Scope.project(project), Resource("View", Metadata(name, "m", version = version), mapOf("x" to x)), "test")
    }

    private fun x(project: String, name: String): Any? =
        projects.resolve(ResourceId("m", Scope.project(project), "View", name))?.resource?.spec?.get("x")

    private fun settings(parent: String = "", inheritable: Boolean = false) =
        ProjectService.Settings(parent = parent, inheritable = inheritable)

    @Test fun resolutionIsChildFirstThenUpTheParentChain() {
        projects.create("base", settings(inheritable = true), "test")
        projects.create("area", settings(parent = "base", inheritable = true), "test")
        projects.create("line", settings(parent = "area"), "test")
        view("base", "home", 1)
        view("base", "alarms", 1)
        view("area", "home", 2)
        view("line", "local", 3)

        assertEquals(listOf("line", "area", "base"), projects.chain("line"))
        // home: area overrides base; alarms: only base has it; local: the child's own.
        assertEquals(2, x("line", "home"))
        assertEquals(1, x("line", "alarms"))
        assertEquals(3, x("line", "local"))
        assertNull(x("base", "local"))
        val listed = projects.resolveList("line", "m", "View").associate { it.id.name to it.project }
        assertEquals(mapOf("alarms" to "base", "home" to "area", "local" to "line"), listed)
        // The settings resource is the project's own, never a parent's.
        assertEquals(listOf("line"), projects.resolveList("line", GATEWAY_MODULE, ProjectService.KIND).map { it.project })
    }

    @Test fun childOverrideWinsAndDeletingItShowsTheParentAgain() {
        projects.create("base", settings(inheritable = true), "test")
        projects.create("plant", settings(parent = "base"), "test")
        view("base", "home", 1)
        assertEquals(1, x("plant", "home"))
        view("plant", "home", 9)
        assertEquals(9, x("plant", "home"))
        assertEquals(1, x("base", "home"))
        store.delete(ResourceId("m", Scope.project("plant"), "View", "home"))
        assertEquals(1, x("plant", "home"))
    }

    @Test fun cyclesAndSelfParentAreRefused() {
        projects.create("a", settings(inheritable = true), "test")
        projects.create("b", settings(parent = "a", inheritable = true), "test")
        projects.create("c", settings(parent = "b", inheritable = true), "test")
        val cycle = assertThrows<ValidationException> { projects.update("a", mapOf("parent" to "c"), null, "test") }
        assertTrue(cycle.errors.single().contains("cycle"), cycle.errors.toString())
        assertTrue(cycle.errors.single().contains("a -> c -> b -> a"), cycle.errors.toString())
        val self = assertThrows<ValidationException> { projects.update("a", mapOf("parent" to "a"), null, "test") }
        assertTrue(self.errors.single().contains("itself"))
        assertEquals("", projects.settings("a").parent)
    }

    @Test fun missingOrNonInheritableParentIsRefused() {
        val missing = assertThrows<ValidationException> { projects.create("child", settings(parent = "nowhere"), "test") }
        assertTrue(missing.errors.single().contains("does not exist"))
        assertFalse(projects.exists("child"))
        projects.create("plain", settings(), "test")
        val plain = assertThrows<ValidationException> { projects.create("child", settings(parent = "plain"), "test") }
        assertTrue(plain.errors.single().contains("not inheritable"))
        // A parent in use stays inheritable.
        projects.update("plain", mapOf("inheritable" to true), null, "test")
        projects.create("child", settings(parent = "plain"), "test")
        val stays = assertThrows<ValidationException> { projects.update("plain", mapOf("inheritable" to false), null, "test") }
        assertTrue(stays.errors.single().contains("parent of child"))
    }

    @Test fun deletingAParentInUseIsRefusedAndDeleteRemovesOnlyThatProject() {
        projects.create("base", settings(inheritable = true), "test")
        projects.create("plant", settings(parent = "base"), "test")
        view("base", "home", 1)
        view("plant", "home", 2)
        store.put(Scope.GATEWAY, Resource("View", Metadata("shared", "m"), mapOf("x" to 5)), "test")
        val inUse = assertThrows<ConflictException> { projects.delete("base") }
        assertTrue(inUse.message!!.contains("plant"))
        assertTrue(projects.exists("base"))

        projects.delete("plant")
        assertFalse(projects.exists("plant"))
        assertTrue(store.list(scope = Scope.project("plant")).isEmpty())
        assertEquals(1, x("base", "home"))
        assertNotNull(store.get(ResourceId("m", Scope.GATEWAY, "View", "shared")))
        projects.delete("base")
        assertEquals(emptyList<String>(), projects.names())
        assertThrows<NoSuchElementException> { projects.delete("base") }
    }

    @Test fun renameMovesEveryResourceAndRepointsChildren() {
        projects.create("base", ProjectService.Settings(title = "Base", inheritable = true), "test")
        projects.create("plant", settings(parent = "base"), "test")
        view("base", "home", 1)
        view("base", "alarms", 2)
        val renamed = projects.rename("base", "core", "test")
        assertEquals("core", renamed.name)
        assertEquals("Base", renamed.settings.title)
        assertEquals(3, renamed.resources)
        assertFalse(projects.exists("base"))
        assertEquals("core", projects.settings("plant").parent)
        assertEquals(1, x("plant", "home"))
        assertEquals(listOf("plant"), projects.info("core")!!.children)
        assertThrows<ConflictException> { projects.rename("core", "plant", "test") }
        assertThrows<NoSuchElementException> { projects.rename("base", "other", "test") }
    }

    @Test fun copyCarriesEveryResourceAndLeavesTheSource() {
        projects.create("base", settings(inheritable = true), "test")
        projects.create("plant", ProjectService.Settings(title = "Plant", parent = "base"), "test")
        view("plant", "home", 4)
        val copied = projects.copy("plant", "plant-b", "test", title = "Plant B")
        assertEquals("Plant B", copied.settings.title)
        assertEquals("base", copied.settings.parent)
        assertEquals(4, x("plant-b", "home"))
        assertEquals(4, x("plant", "home"))
        assertEquals("Plant", projects.settings("plant").title)
        assertEquals(listOf("plant", "plant-b"), projects.info("base")!!.children)
    }

    @Test fun aProjectWithoutSettingsReadsWithDefaults() {
        view("legacy", "home", 1)
        val info = projects.info("legacy")!!
        assertEquals(0L, info.version)
        assertTrue(info.settings.enabled)
        assertTrue(info.settings.launchable)
        assertEquals("", info.settings.parent)
        assertEquals(listOf("legacy"), projects.names())
        // A settings change creates the resource.
        projects.update("legacy", mapOf("enabled" to false), null, "test")
        assertFalse(projects.info("legacy")!!.settings.launchable)
        assertThrows<IllegalArgumentException> { projects.update("legacy", mapOf("color" to "red"), null, "test") }
        assertThrows<IllegalArgumentException> { projects.update("legacy", mapOf("enabled" to "no"), null, "test") }
        assertThrows<ConflictException> { projects.update("legacy", mapOf("title" to "x"), 99, "test") }
    }

    @Test fun aBatchMayCreateAParentAndItsChildTogether() {
        val parent = Resource(ProjectService.KIND, Metadata(ProjectService.RESOURCE, GATEWAY_MODULE), settings(inheritable = true).toSpec())
        val child = Resource(ProjectService.KIND, Metadata(ProjectService.RESOURCE, GATEWAY_MODULE), settings(parent = "p").toSpec())
        projects.check(listOf(ProjectService.id("p") to parent, ProjectService.id("c") to child))
        assertThrows<ValidationException> { projects.check(listOf(ProjectService.id("c") to child)) }
    }
}
