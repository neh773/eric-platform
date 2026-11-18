package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.db.PasswordUpdate
import org.oip.testinfra.SimPorts
import org.oip.testinfra.SimulatorStack
import java.net.URLEncoder
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * Zero sample loss on a network cut (PLAN phase 3 acceptance) against the compose TimescaleDB, with the cut
 * made by Toxiproxy (`postgres` proxy: live connections reset, new ones refused). Runs in the simulators CI
 * job (`:gateway:server:simulatorCommsTest`), not in `build`.
 *
 * A real gateway historizes an on-change memory tag to a SQL history provider on a `db/Connection` through
 * the proxy. Values are written before, during, and after the cut; afterwards every value must be in the
 * table exactly once, in order, the table must be a hypertable, and the REST aggregate must count them all.
 * The gateway's own timers (history writer, forwarder, db-health retest) run on the wall clock, so this test
 * waits on conditions with deadlines instead of driving a fake clock.
 * Docs: - (Store and Forward).
 */
@Tag("simulator")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class SimulatorHistoryTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun aPostgresNetworkCutMidStreamLosesNoSampleAndDuplicatesNone() {
        SimulatorStack.fromEnvironment().use { stack ->
            val toxi = stack.toxiproxy()
            toxi.restore("postgres")
            val table = "oip_sim_history_" + System.nanoTime().toString().takeLast(8)
            tmp.resolve("gateway.yaml").writeText("profile: standard\nopcua:\n  enabled: false\n")
            Gateway(tmp, port = 0).use { gw ->
                gw.databases.saveConnection(
                    "sim-pg",
                    mapOf(
                        "driver" to "postgres",
                        "url" to stack.endpoints.postgresProxyJdbcUrl(),
                        "username" to SimPorts.DB_USER,
                        "maxWaitMillis" to 2_000,
                        "validationTimeoutMillis" to 1_000,
                        "extraProperties" to mapOf("connectTimeout" to "2", "socketTimeout" to "15"),
                    ),
                    0,
                    PasswordUpdate.Set(SimPorts.DB_PASSWORD),
                )
                gw.store.put(Scope.GATEWAY, Resource("HistoryProvider", Metadata("sim-pg", "historian"),
                    mapOf("connection" to "sim-pg", "table" to table, "timescale" to "required")))
                gw.store.put(Scope.GATEWAY, Resource("Tag", Metadata("count", "tags"),
                    mapOf("path" to "Line/Count", "type" to "memory", "dataType" to "Int32", "value" to 0)))
                gw.store.put(Scope.GATEWAY, Resource("HistorySettings", Metadata("count", "historian"),
                    mapOf("tagPath" to "Line/Count", "storageProvider" to "sim-pg")))
                gw.start()

                var n = 0
                fun produce(k: Int) = repeat(k) {
                    n++
                    gw.tags.write("[default]Line/Count", n)
                    Thread.sleep(2)
                }
                fun pending() = gw.historyRouter.buffer.pendingCounts()["sim-pg"] ?: 0L
                fun awaitTrue(what: String, budgetMs: Long, check: () -> Boolean) = awaitTrue(what, budgetMs, {
                    "history=${gw.historyRouter.status()} db=${gw.databases.connection("sim-pg")}"
                }, check)

                produce(100)
                awaitTrue("forwarded before the cut", 60_000) { gw.historyCollector.pending == 0 && pending() == 0L }

                toxi.cut("postgres")
                try {
                    produce(300)
                    awaitTrue("the outage is noticed", 60_000) {
                        gw.historyRouter.status().single { it["name"] == "sim-pg" }["connected"] == false
                    }
                    produce(100)
                    awaitTrue("samples accumulate in the buffer", 30_000) { pending() > 0 }
                } finally {
                    toxi.restore("postgres")
                }
                produce(100)
                awaitTrue("everything forwarded after reconnect", 120_000) { gw.historyCollector.pending == 0 && pending() == 0L }
                assertEquals(0, gw.historyRouter.buffer.quarantined().size)

                DriverManager.getConnection(stack.endpoints.postgresJdbcUrl, SimPorts.DB_USER, SimPorts.DB_PASSWORD).use { c ->
                    val values = c.createStatement().use { st ->
                        st.executeQuery("SELECT value_text FROM $table WHERE tag_path = '[default]Line/Count' ORDER BY ts, source_id, seq").use { rs ->
                            val out = ArrayList<Int>()
                            while (rs.next()) out += rs.getString(1).toInt()
                            out
                        }
                    }
                    assertEquals((0..n).toList(), values, "every sample exactly once, in order")
                    val hyper = c.createStatement().use { st ->
                        st.executeQuery("SELECT 1 FROM timescaledb_information.hypertables WHERE hypertable_name = '$table'").use { it.next() }
                    }
                    assertTrue(hyper, "TimescaleDB is installed, so the table is a hypertable")
                }

                val http = Http(gw.port())
                gw.loginAdmin(http)
                val path = URLEncoder.encode("[default]Line/Count", Charsets.UTF_8)
                val now = System.currentTimeMillis()
                val count = http.get("/api/v1/history/aggregate?tagPath=$path&from=${now - 3_600_000}&to=$now&interval=PT2H&aggregate=Count")
                assertEquals(200, count.status, count.body)
                @Suppress("UNCHECKED_CAST")
                val windows = count.json["windows"] as List<Map<String, Any?>>
                assertEquals(n + 1, (windows.single()["value"] as Number).toInt(), "the REST aggregate reads the provider")
                DriverManager.getConnection(stack.endpoints.postgresJdbcUrl, SimPorts.DB_USER, SimPorts.DB_PASSWORD).use { c ->
                    c.createStatement().use { it.execute("DROP TABLE $table") }
                }
            }
        }
    }

    private fun awaitTrue(what: String, budgetMs: Long, state: () -> String, check: () -> Boolean) {
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting: $what; ${state()}")
    }
}
