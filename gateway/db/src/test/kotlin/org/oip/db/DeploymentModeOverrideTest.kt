package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import java.nio.file.Path
import java.sql.DriverManager

/**
 * GAT-037: one named connection, `main`, carries a different database URL per deployment mode.
 * The stored resource holds the production URL; `dev` and `test` overlays replace only `url`.
 * Switching the active mode re-points the live connection, and the same override resolves through the config store.
 */
class DeploymentModeOverrideTest {
    @TempDir lateinit var tmp: Path

    private fun seed(file: String, env: String): String {
        val url = "jdbc:sqlite:${tmp.resolve(file)}"
        DriverManager.getConnection(url).use { c -> c.createStatement().use {
            it.execute("CREATE TABLE env (name TEXT)"); it.execute("INSERT INTO env VALUES ('$env')")
        } }
        return url
    }

    private fun overlay(mode: String, url: String, h: DbHarness) = h.store.put(
        Scope.mode(mode),
        Resource(CONNECTION_KIND, Metadata("main", DB_MODULE), mapOf("url" to url)),
    )

    private fun env(h: DbHarness): String = h.db.withConnection("main") { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT name FROM env").use { rs -> rs.next(); rs.getString(1) } }
    }

    @Test fun theOverrideResolvesPerActiveModeAndRepointsTheLiveConnection() {
        val prodUrl = seed("prod.db", "prod")
        val devUrl = seed("dev.db", "dev")
        val testUrl = seed("test.db", "test")
        DbHarness(tmp).use { h ->
            h.connection("main", url = prodUrl, fields = mapOf("description" to "Plant historian database"))
            overlay("dev", devUrl, h)
            overlay("test", testUrl, h)
            val id = ResourceId(DB_MODULE, Scope.GATEWAY, CONNECTION_KIND, "main")

            // No active mode: the stored (production) values apply.
            assertEquals(prodUrl, h.store.resolve(id)!!.spec["url"])
            assertEquals("prod", env(h))

            for ((mode, url) in listOf("dev" to devUrl, "test" to testUrl)) {
                h.store.setActiveMode(mode)
                val resolved = h.store.resolve(id)!!.spec
                assertEquals(url, resolved["url"], "url overridden in $mode")
                assertEquals("Plant historian database", resolved["description"], "fields the overlay does not name stay")
                assertEquals(url, h.db.connection("main")["url"])
                assertEquals(mode, env(h), "the live pool follows the active mode")
            }

            // A mode with no overlay, or no mode, goes back to the stored production URL.
            h.store.setActiveMode("prod")
            assertEquals("prod", env(h))
            h.store.setActiveMode("dev")
            assertEquals("dev", env(h))
            h.store.setActiveMode(null)
            assertEquals(prodUrl, h.store.resolve(id)!!.spec["url"])
            assertEquals("prod", env(h))

            // The stored resource itself never changed.
            assertEquals(prodUrl, h.store.get(id)!!.spec["url"])
            assertTrue(h.store.overlayModes().containsAll(listOf("dev", "test")))
        }
    }

    @Test fun editingTheActiveModesOverlayAppliesWithoutASwitch() {
        val prodUrl = seed("prod.db", "prod")
        val devUrl = seed("dev.db", "dev")
        val otherUrl = seed("dev2.db", "dev-2")
        DbHarness(tmp).use { h ->
            h.connection("main", url = prodUrl)
            overlay("dev", devUrl, h)
            h.store.setActiveMode("dev")
            assertEquals("dev", env(h))
            h.store.put(
                Scope.mode("dev"),
                Resource(CONNECTION_KIND, Metadata("main", DB_MODULE, version = h.store.get(
                    ResourceId(DB_MODULE, Scope.mode("dev"), CONNECTION_KIND, "main"))!!.metadata.version), mapOf("url" to otherUrl)),
            )
            assertEquals("dev-2", env(h))
        }
    }
}
