package org.oip.redundancy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmJournal
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.gateway.Json
import org.oip.tags.DataType
import org.oip.tags.QualityCode
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import org.oip.tags.registerTagSchemas
import java.nio.file.Path
import java.time.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** Node-local (`oip-local`) resources in config and runtime sync, without a network. */
class LocalSyncTest {
    @TempDir
    lateinit var tmp: Path

    private fun store(): FileConfigStore =
        FileConfigStore(tmp.resolve("config"), SchemaRegistry().also { registerTagSchemas(it) })

    /** The store refuses a stale version, so a later put of the same resource carries the stored one. */
    private fun FileConfigStore.save(resource: Resource) {
        val stored = get(ResourceId("tags", Scope.GATEWAY, "Tag", resource.metadata.name))?.metadata?.version ?: 0L
        put(Scope.GATEWAY, resource.copy(metadata = resource.metadata.copy(version = stored)))
    }

    private fun tag(name: String, path: String, local: Boolean, type: String = "memory"): Resource = Resource(
        kind = "Tag",
        metadata = Metadata(name = name, module = "tags", labels = if (local) mapOf(LOCAL_LABEL to "true") else emptyMap()),
        spec = linkedMapOf("path" to path, "type" to type, "dataType" to "Double", "value" to 0),
    )

    /**
     * The runtime mirror neither offers nor applies a node-local tag. Before the fix the master offered it, and a
     * backup with no such tag reported every one of its rows as missed, which starts a config pull each time.
     */
    @Test
    fun runtimeMirrorLeavesNodeLocalTagsOut() {
        store().use { store ->
            store.save(tag("mine", "Line/Mine", local = true))
            store.save(tag("cell", "Cell", local = true, type = "folder"))
            store.save(tag("shared", "Line/Shared", local = false))
            val locals = LocalTags(store)
            locals.start()
            try {
                assertTrue(locals.isLocal("default", "line/mine"), "case and slashes do not matter")
                assertTrue(locals.isLocal("Default", "/Line/Mine/"))
                assertTrue(locals.isLocal("default", "Cell/Inner/Speed"), "under a local folder or instance")
                assertFalse(locals.isLocal("default", "Line/Shared"))
                assertFalse(locals.isLocal("other", "Line/Mine"))
                assertFalse(locals.isLocal("default", "Line"))

                val tags = TagEngine()
                for (path in listOf("Line/Mine", "Line/Shared", "Cell/Inner/Speed")) {
                    tags.configure(TagConfig(path = path, type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 0.0, valueSet = true))
                }
                AlarmEngine(Clock.systemUTC(), AlarmJournal(tmp.resolve("alarms.sqlite"))).use { alarms ->
                    val mirror = RuntimeMirror(tags, alarms, locals::isLocal)
                    assertEquals(listOf("Line/Shared"), mirror.snapshot().map { it.path })

                    fun row(path: String, value: Double) = TagSnap("default", path, Json.write(value), QualityCode.GOOD.label, 1L, "memory")
                    val missed = mirror.apply(listOf(row("Line/Mine", 5.0), row("Line/Shared", 6.0), row("Cell/Inner/Gone", 7.0)), emptyList())
                    assertEquals(emptyList<TagSnap>(), missed, "a peer's node-local tag is not one this node is missing")
                    assertEquals(0.0, (tags.read("[default]Line/Mine").value as Number).toDouble(), "this node's local value stays")
                    assertEquals(6.0, (tags.read("[default]Line/Shared").value as Number).toDouble())

                    // Relabeling follows the store.
                    store.save(tag("mine", "Line/Mine", local = false))
                    assertFalse(locals.isLocal("default", "Line/Mine"))
                    store.delete(ResourceId("tags", Scope.GATEWAY, "Tag", "cell"))
                    assertFalse(locals.isLocal("default", "Cell/Inner/Speed"))
                    assertEquals(setOf("Line/Mine", "Line/Shared", "Cell/Inner/Speed"), mirror.snapshot().map { it.path }.toSet())
                }
            } finally {
                locals.close()
            }
        }
    }

    /**
     * A resource that changes label is a real config change for the backup. Before the fix the config log dropped
     * any event whose old or new resource was node-local, so the backup never saw these until a full pull.
     */
    @Test
    fun relabelingAResourceReachesTheBackupsDeltaPull() {
        store().use { store ->
            val mirror = ConfigMirror(store)
            mirror.start()
            try {
                store.save(tag("mine", "Line/Mine", local = true))
                assertEquals(0L, mirror.revision(), "a node-local resource is not logged")

                store.save(tag("mine", "Line/Mine", local = false))
                val shared = mirror.pull(0)
                assertFalse(shared.full)
                assertEquals(listOf("gateway/tags/Tag/mine" to false), shared.documents.map { it.key to it.deleted })

                store.save(tag("mine", "Line/Mine", local = true))
                val local = mirror.pull(shared.revision)
                assertFalse(local.full)
                assertEquals(listOf("gateway/tags/Tag/mine" to true), local.documents.map { it.key to it.deleted })

                store.save(tag("mine", "Line/Mine", local = true).let { it.copy(spec = it.spec + ("value" to 3)) })
                store.delete(ResourceId("tags", Scope.GATEWAY, "Tag", "mine"))
                assertEquals(local.revision, mirror.revision(), "changes to a resource that stays node-local are not logged")
            } finally {
                mirror.close()
            }
        }
    }
}
