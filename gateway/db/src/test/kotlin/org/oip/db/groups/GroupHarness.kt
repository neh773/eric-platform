package org.oip.db.groups

import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.Scope
import org.oip.db.DbHarness
import org.oip.db.PasswordUpdate
import org.oip.db.PostgresServer
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/** Map-backed tags. Writes are recorded so tests can count them. */
class FakeTags : GroupTags {
    val values = ConcurrentHashMap<String, Any>()
    val bad = ConcurrentHashMap.newKeySet<String>()
    val writes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Any?>>()

    operator fun set(path: String, value: Any?) {
        if (value == null) values.remove(path) else values[path] = value
    }

    operator fun get(path: String): Any? = values[path]

    override fun read(path: String): TagRead =
        if (path in bad || !values.containsKey(path)) TagRead(values[path], false) else TagRead(values[path], true)

    override fun write(path: String, value: Any?): String? {
        writes += path to value
        set(path, value)
        return null
    }

    /** Just enough expression support for the tests: `{ref}` or `{ref} * n`. The real language is tested in the server. */
    override fun evaluate(expression: String, ref: (String) -> TagRead): TagRead {
        val times = Regex("^\\{(.+)}\\s*\\*\\s*([0-9.]+)$").matchEntire(expression.trim())
        if (times != null) {
            val base = ref(times.groupValues[1])
            val n = (base.value as? Number)?.toDouble() ?: return TagRead(null, false)
            return TagRead(n * times.groupValues[2].toDouble(), base.good)
        }
        val plain = Regex("^\\{(.+)}$").matchEntire(expression.trim()) ?: return TagRead(null, false)
        return ref(plain.groupValues[1])
    }
}

/** Where a contract test runs: SQLite always, Postgres when reachable. */
interface Backend {
    val name: String
    fun connect(h: DbHarness)
    val idColumn: String
    val available: Boolean get() = true
}

object SqliteBackend : Backend {
    override val name = "sqlite"
    override fun connect(h: DbHarness) {
        h.connection("plant", url = "jdbc:sqlite:groups.db")
    }
    override val idColumn = "id INTEGER PRIMARY KEY AUTOINCREMENT"
}

/**
 * A PostgreSQL server started for the test (the same container helper stored-procedure tests use).
 * The build job has Docker, so the contract runs there instead of being skipped.
 */
object PostgresBackend : Backend {
    override val name = "postgres"
    override val available: Boolean = true

    override fun connect(h: DbHarness) {
        h.connection(
            "plant",
            driver = "postgres",
            url = PostgresServer.require(),
            password = PasswordUpdate.Set(PostgresServer.PASSWORD),
            fields = mapOf("username" to PostgresServer.USER),
        )
    }
    override val idColumn = "id SERIAL PRIMARY KEY"
}

class GroupHarness(dir: Path, val backend: Backend, activity: GroupActivity = GroupActivity.ALWAYS) : AutoCloseable {
    val h = DbHarness(dir)
    val tags = FakeTags()
    val database = PooledConnections(h.db)
    val groups = TransactionGroups(h.store, tags, database, activity = activity)
    private val tables = ArrayList<String>()
    private val suffix = "_" + java.lang.Long.toHexString(System.nanoTime() and 0xffffff)

    init {
        if (h.db.connections().none { it["name"] == "plant" }) backend.connect(h)
        groups.attach()
    }

    /** A table name unique to this run, so Postgres runs do not collide. */
    fun table(base: String): String = (base + suffix).also { tables += it }

    fun sql(statement: String) {
        database.withConnection("plant") { c: Connection -> c.createStatement().use { it.execute(statement) } }
    }

    fun rows(query: String): List<List<Any?>> = database.withConnection("plant") { c ->
        c.createStatement().use { st ->
            st.executeQuery(query).use { rs ->
                val out = ArrayList<List<Any?>>()
                while (rs.next()) out += (1..rs.metaData.columnCount).map { Sql.read(rs, it) }
                out
            }
        }
    }

    fun put(name: String, spec: Map<String, Any?>): Resource {
        val id = ResourceId(GROUP_MODULE, Scope.GATEWAY, GROUP_KIND, name)
        val version = h.store.get(id)?.metadata?.version ?: 0L
        return h.store.put(Scope.GATEWAY, Resource(GROUP_KIND, Metadata(name, GROUP_MODULE, version = version), spec), "test")
    }

    fun run(name: String): RunOutcome = groups.runOnce(name)

    override fun close() {
        groups.close()
        if (backend is PostgresBackend) for (t in tables) runCatching { sql("DROP TABLE IF EXISTS \"$t\"") }
        h.close()
    }
}

fun num(v: Any?): Double = (v as Number).toDouble()
