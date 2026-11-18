package org.oip.gateway

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.oip.alarm.AlarmEngine
import org.oip.alarm.AlarmJournal
import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.db.groups.GROUP_KIND
import org.oip.db.groups.GROUP_MODULE
import org.oip.db.groups.GroupDatabase
import org.oip.db.groups.GroupActivity
import org.oip.db.groups.TransactionGroups
import org.oip.network.GatewayNetwork
import org.oip.network.LinkSettings
import org.oip.redundancy.NodeRole
import org.oip.redundancy.RedundancyRuntime
import org.oip.redundancy.RedundancySettings
import org.oip.security.AuditActions
import org.oip.security.DefaultRoles
import org.oip.security.Permissions
import org.oip.tags.DataType
import org.oip.tags.TagConfig
import org.oip.tags.TagEngine
import org.oip.tags.TagType
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * REST for transaction groups: role matrix, one audit row per config write, and the gateway lifecycle.
 */
class TransactionGroupApiTest {
    @TempDir lateinit var tmp: Path
    private lateinit var gw: Gateway
    private lateinit var http: Http

    @BeforeEach fun setUp() {
        gw = Gateway(tmp, port = 0).start()
        gw.createUser("admin", setOf(DefaultRoles.ADMINISTRATOR))
        gw.createUser("op", setOf(DefaultRoles.OPERATOR))
        gw.createUser("view", setOf(DefaultRoles.VIEWER))
        http = Http(gw.port())
        assertEquals(200, http.login("admin", TEST_PASSWORD).status)
    }

    @AfterEach fun tearDown() = gw.close()

    private fun client(user: String) = Http(gw.port()).also { assertEquals(200, it.login(user, TEST_PASSWORD).status) }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun setUpGroup() {
        assertEquals(201, http.post("/api/v1/db/connections", """{"name":"plant","driver":"sqlite","url":"jdbc:sqlite:groups.db"}""").status)
        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"create","connection":"plant","queryType":"update","sql":"CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, speed DOUBLE PRECISION, doubled DOUBLE PRECISION)"}
        """.trimIndent()).status)
        assertEquals(200, http.post("/api/v1/db/queries/create/execute", """{"parameters":{}}""").status)
        gw.tags.configure(TagConfig(path = "Line/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 4.0, valueSet = true))
        val created = http.post("/api/v1/config/$GROUP_MODULE/gateway/$GROUP_KIND", """
            {"apiVersion":"oip/v1","kind":"$GROUP_KIND","metadata":{"name":"line-log","module":"$GROUP_MODULE","version":0},
             "spec":{"connection":"plant","table":"log","groupType":"historical","execution":{"rateMillis":100},
                     "items":[{"name":"speed","source":"tag","tag":"Line/Speed","column":"speed"},
                              {"name":"doubled","source":"expression","expression":"{speed} * 2","column":"doubled"}]}}
        """.trimIndent())
        assertEquals(201, created.status, created.body)
    }

    @Test fun groupsRunAfterStartAndReportStatus() {
        setUpGroup()
        waitFor("a run") { (http.get("/api/v1/db/groups/line-log").json["executions"] as Number).toLong() >= 2 }
        val status = http.get("/api/v1/db/groups/line-log").json
        assertEquals("Running", status["state"])
        assertEquals("historical", status["groupType"])
        assertNotNull(status["lastRunMillis"])
        assertNotNull(status["lastDurationMillis"])
        assertEquals(1, (status["rowsWritten"] as Number).toInt())
        assertNull(status["error"])
        assertEquals(201, http.post("/api/v1/db/queries", """
            {"name":"last","connection":"plant","queryType":"scalar","sql":"SELECT doubled FROM log ORDER BY id DESC LIMIT 1"}
        """.trimIndent()).status)
        val doubled = http.post("/api/v1/db/queries/last/execute", """{"parameters":{}}""")
        assertEquals(8.0, (doubled.json["value"] as Number).toDouble(), "expression item evaluated by the tag expression language")
        assertEquals(404, http.get("/api/v1/db/groups/nope").status)
        val listed = http.get("/api/v1/db/groups").json["items"] as List<*>
        assertEquals(listOf("line-log"), listed.map { (it as Map<*, *>)["name"] })
    }

    @Test fun roleMatrixAndOneAuditRowPerConfigWrite() {
        setUpGroup()
        val anon = Http(gw.port())
        val view = client("view")
        val op = client("op")
        assertEquals(401, anon.get("/api/v1/db/groups").status)
        // Group status is gateway configuration (gateway.read), like database connections; the default viewer
        // role does not hold gateway.read.
        assertEquals(403, view.get("/api/v1/db/groups").status)
        assertEquals(403, view.get("/api/v1/db/groups/line-log").status)
        assertEquals(200, op.get("/api/v1/db/groups").status)
        assertEquals(403, view.post("/api/v1/db/groups/line-log/disable", "").status)
        assertEquals(403, op.post("/api/v1/db/groups/line-log/disable", "").status)
        assertEquals(401, anon.post("/api/v1/db/groups/line-log/disable", "").status)

        val readKey = gw.security.apiKeys.create("groups-read", setOf(Permissions.GATEWAY_READ), secureConnectionRequired = false, actor = "test")
        val writeKey = gw.security.apiKeys.create("groups-write", setOf(Permissions.GATEWAY_WRITE), secureConnectionRequired = false, actor = "test")
        val reader = Http(gw.port()).also { it.authorizeBearer(readKey.token) }
        val writer = Http(gw.port()).also { it.authorizeBearer(writeKey.token) }
        assertEquals(200, reader.get("/api/v1/db/groups").status)
        assertEquals(403, reader.post("/api/v1/db/groups/line-log/disable", "").status)

        http.sendCsrf = false
        assertEquals(403, http.post("/api/v1/db/groups/line-log/disable", "").status, "session writes need the CSRF token")
        http.sendCsrf = true

        val before = gw.auditCount(AuditActions.CONFIG_UPDATED)
        val disabled = http.post("/api/v1/db/groups/line-log/disable", "")
        assertEquals(200, disabled.status, disabled.body)
        assertEquals("Disabled", disabled.json["state"])
        assertEquals(false, disabled.json["enabled"])
        assertEquals(before + 1, gw.auditCount(AuditActions.CONFIG_UPDATED))
        assertEquals(before + 1, gw.auditCount(AuditActions.CONFIG_UPDATED, actor = "admin"))

        assertEquals(200, http.post("/api/v1/db/groups/line-log/disable", "").status)
        assertEquals(before + 1, gw.auditCount(AuditActions.CONFIG_UPDATED), "no change, no write, no audit row")

        val enabled = writer.post("/api/v1/db/groups/line-log/enable", "")
        assertEquals(200, enabled.status, enabled.body)
        assertEquals("Running", enabled.json["state"])
        assertEquals(before + 2, gw.auditCount(AuditActions.CONFIG_UPDATED))
        assertEquals(404, http.post("/api/v1/db/groups/nope/enable", "").status)
    }

    @Test fun openApiListsTheGroupRoutes() {
        val paths = http.get("/api/docs").json["paths"] as Map<*, *>
        for (p in listOf("/api/v1/db/groups", "/api/v1/db/groups/{name}", "/api/v1/db/groups/{name}/enable", "/api/v1/db/groups/{name}/disable")) {
            assertTrue(paths.containsKey(p), p)
        }
    }
}

/**
 * The engine against the real tag engine. P2-14: a group's database work must not hold up tag updates.
 */
class TransactionGroupTagEngineTest {
    @TempDir lateinit var tmp: Path

    /** SQLite file connections; [gate] lets a test hold a run inside the database phase. */
    private inner class FileDatabase : GroupDatabase {
        @Volatile var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)
        /** Runs that found [gate] set and waited on it. */
        val parked = java.util.concurrent.atomic.AtomicInteger()
        val lockHeldInside = AtomicBoolean(false)
        @Volatile var failNext = false
        var tags: TagEngine? = null

        override fun <T> withConnection(connection: String, block: (Connection) -> T): T {
            if (tags?.holdingWriteLock() == true) lockHeldInside.set(true)
            entered.countDown()
            gate?.let { parked.incrementAndGet(); it.await(10, TimeUnit.SECONDS) }
            if (failNext) throw SQLException("database went away")
            return DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("g.db")}").use(block)
        }
    }

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("g.db")}").use { c -> c.createStatement().use { it.execute(statement) } }
    }

    private fun engine(tags: TagEngine, db: GroupDatabase): Pair<FileConfigStore, TransactionGroups> {
        val store = FileConfigStore(tmp.resolve("config"), SchemaRegistry())
        val groups = TransactionGroups(store, TagEngineGroupTags(tags), db).also { it.attach() }
        return store to groups
    }

    private fun put(store: FileConfigStore, name: String, spec: Map<String, Any?>) {
        store.put(Scope.GATEWAY, Resource(GROUP_KIND, Metadata(name, GROUP_MODULE), spec), "test")
    }

    @Test fun updateRawIsNotBlockedWhileAGroupIsInItsDatabasePhase() {
        val tags = TagEngine()
        tags.configure(TagConfig(path = "Dev/Raw", type = TagType.OPC, dataType = DataType.DOUBLE, opcServer = "sim", opcItemPath = "ns=1;s=a"))
        tags.configure(TagConfig(path = "Line/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
        sql("CREATE TABLE log (speed DOUBLE PRECISION)")
        val db = FileDatabase().also { it.tags = tags }
        val (store, groups) = engine(tags, db)
        put(store, "slow", mapOf(
            "connection" to "plant", "table" to "log", "groupType" to "historical",
            "items" to listOf(mapOf("name" to "speed", "source" to "tag", "tag" to "Line/Speed", "column" to "speed")),
        ))
        val release = CountDownLatch(1)
        db.gate = release
        db.failNext = true
        val pool = Executors.newSingleThreadExecutor()
        try {
            val run = pool.submit<Unit> { groups.runOnce("slow") }
            assertTrue(db.entered.await(5, TimeUnit.SECONDS), "group reached its database phase")

            // The group is parked inside its database call. Device samples and tag writes still go through.
            val started = System.nanoTime()
            for (i in 1..200) tags.updateRaw("Dev/Raw", i.toDouble())
            assertEquals(200.0, (tags.read("Dev/Raw").value as Number).toDouble())
            assertTrue(tags.write("Line/Speed", 9.0).ok)
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(millis < 2_000, "tag updates took ${millis}ms while a group was in the database")
            assertFalse(run.isDone, "the group is still waiting on the database")

            release.countDown()
            run.get(10, TimeUnit.SECONDS)
            assertFalse(db.lockHeldInside.get(), "the tag engine lock was held during database work")
            val status = groups.status("slow")
            assertEquals("Error", status["state"])
            assertTrue((status["error"] as String).contains("database went away"))
            // The failure did not leave anything locked.
            tags.updateRaw("Dev/Raw", 5.0)
            assertEquals(5.0, (tags.read("Dev/Raw").value as Number).toDouble())
        } finally {
            release.countDown()
            pool.shutdownNow()
            groups.close()
            store.close()
        }
    }

    @Test fun bidirectionalDbWinsWritesTheRealTag() {
        val tags = TagEngine()
        tags.configure(TagConfig(path = "Line/Setpoint", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
        sql("CREATE TABLE sp (id INTEGER PRIMARY KEY, setpoint DOUBLE PRECISION)")
        sql("INSERT INTO sp (setpoint) VALUES (42)")
        val (store, groups) = engine(tags, FileDatabase())
        try {
            put(store, "sp", mapOf(
                "connection" to "plant", "table" to "sp", "groupType" to "standard", "updateMode" to "bidirectionalDbWins",
                "row" to mapOf("mode" to "first"),
                "items" to listOf(mapOf("name" to "sp", "source" to "tag", "tag" to "Line/Setpoint", "column" to "setpoint")),
            ))
            val outcome = groups.runOnce("sp")
            assertEquals(1, outcome.tagWrites)
            assertEquals(42.0, (tags.read("Line/Setpoint").value as Number).toDouble())
            assertTrue(tags.write("Line/Setpoint", 50.0).ok)
            groups.runOnce("sp")
            DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("g.db")}").use { c ->
                c.createStatement().use { st -> st.executeQuery("SELECT setpoint FROM sp").use { rs -> rs.next(); assertEquals(50.0, rs.getDouble(1)) } }
            }
        } finally {
            groups.close()
            store.close()
        }
    }

    @Test fun triggerFiresOncePerRisingEdgeOnRealTags() {
        val tags = TagEngine()
        tags.configure(TagConfig(path = "Line/Go", type = TagType.MEMORY, dataType = DataType.BOOLEAN, value = false, valueSet = true))
        tags.configure(TagConfig(path = "Line/Count", type = TagType.MEMORY, dataType = DataType.INT32, value = 3, valueSet = true))
        sql("CREATE TABLE ev (n INTEGER)")
        val (store, groups) = engine(tags, FileDatabase())
        try {
            put(store, "ev", mapOf(
                "connection" to "plant", "table" to "ev", "groupType" to "historical",
                "execution" to mapOf("trigger" to mapOf("tag" to "Line/Go", "onlyOnChange" to true, "reset" to true)),
                "items" to listOf(mapOf("name" to "n", "source" to "tag", "tag" to "Line/Count", "column" to "n")),
            ))
            assertFalse(groups.runOnce("ev").executed)
            assertTrue(tags.write("Line/Go", true).ok)
            assertTrue(groups.runOnce("ev").executed)
            assertEquals(false, tags.read("Line/Go").value, "reset wrote the trigger back")
            assertFalse(groups.runOnce("ev").executed)
            assertTrue(tags.write("Line/Go", true).ok)
            assertTrue(groups.runOnce("ev").executed)
            assertEquals(2L, groups.status("ev")["executions"])
        } finally {
            groups.close()
            store.close()
        }
    }
    /**
     * The gateway wiring against a real master redundancy runtime: groups run only while it is active,
     * stop when it goes to warm standby, and resume when it takes control again. The redundancy listener
     * only hands the change off, so it is not held up by a run parked in the database (P2-14).
     */
    @Test fun groupsFollowRedundancyActivity() {
        val tags = TagEngine()
        tags.configure(TagConfig(path = "Line/Speed", type = TagType.MEMORY, dataType = DataType.DOUBLE, value = 1.0, valueSet = true))
        sql("CREATE TABLE log (speed DOUBLE PRECISION)")
        val root = tmp.resolve("redundancy")
        val rstore = FileConfigStore(root.resolve("config"), SchemaRegistry())
        val alarms = AlarmEngine(java.time.Clock.systemUTC(), AlarmJournal(root.resolve("db/alarms.sqlite")))
        val network = GatewayNetwork(root.resolve("network"), tags, LinkSettings(enabled = false, gatewayName = "node"))
        val redundancy = RedundancyRuntime(
            RedundancySettings(mode = NodeRole.MASTER, startupAllowanceMillis = 30_000), network, rstore, tags, alarms,
        )
        val db = FileDatabase()
        val store = FileConfigStore(tmp.resolve("config"), SchemaRegistry())
        val groups = TransactionGroups(store, TagEngineGroupTags(tags), db, activity = GroupActivity { redundancy.isActive() })
            .also { it.attach() }
        val following = TransactionGroupApi.followRedundancy(groups, redundancy)
        fun rows(): Long = DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("g.db")}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM log").use { rs -> rs.next(); rs.getLong(1) } }
        }
        fun state() = groups.status("line")["state"]
        val release = CountDownLatch(1)
        try {
            put(store, "line", mapOf(
                "connection" to "plant", "table" to "log", "groupType" to "historical", "execution" to mapOf("rateMillis" to 100),
                "items" to listOf(mapOf("name" to "speed", "source" to "tag", "tag" to "Line/Speed", "column" to "speed")),
            ))
            groups.start()
            // A master that has not decided yet is not active.
            waitFor("standby while undecided") { state() == "Standby" }
            Thread.sleep(200)
            assertEquals(0L, rows())

            // Backup to active: groups start.
            redundancy.requestFailover()
            waitFor("runs once active") { rows() >= 2 }
            assertEquals("Running", state())

            // Active to backup, with a run parked in the database: the listener still returns at once.
            db.gate = release
            waitFor("a run in the database") { db.parked.get() > 0 }
            val later = CountDownLatch(1)
            val after = redundancy.addListener { _, current -> if (!current.active) later.countDown() }
            redundancy.requestFailover()
            assertTrue(later.await(2, TimeUnit.SECONDS), "a later redundancy listener was held up")
            after.close()
            assertFalse(redundancy.isActive())
            waitFor("standby after failover") { state() == "Standby" }
            release.countDown()
            db.gate = null
            Thread.sleep(200)
            val stopped = rows()
            Thread.sleep(300)
            assertEquals(stopped, rows(), "no runs on the backup")
            assertEquals("Standby", state())

            // Backup to active again: groups resume.
            redundancy.assumeControl()
            waitFor("runs after taking control") { rows() > stopped }
            assertEquals("Running", state())
        } finally {
            release.countDown()
            following.close()
            groups.close()
            store.close()
            redundancy.close()
            alarms.close()
            rstore.close()
            network.close()
        }
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
