package org.oip.db.groups

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/** Result of one tick. [executed] is false when a trigger kept the group from running. */
data class RunOutcome(
    val executed: Boolean,
    val rowsWritten: Int = 0,
    val tagWrites: Int = 0,
    val warning: String? = null,
)

/**
 * One group's execution and the little state it keeps between ticks (trigger edge, last synced values).
 * Not thread-safe; the owning [TransactionGroups] serialises ticks per group.
 *
 * Every tick runs in three phases so the tag engine is never waiting on the database (P2-14):
 * 1. read the trigger and items from tags (each read is its own short tag call),
 * 2. talk to the database with no tag call at all, producing a list of tag writes,
 * 3. apply those tag writes and the trigger reset.
 */
internal class GroupRunner(
    val name: String,
    val spec: GroupSpec,
    private val tags: GroupTags,
    private val db: GroupDatabase,
    private val clock: Clock,
    meterStore: MeterStore = MeterStore.memory(),
) {
    /** Hour and event meter items (DAT-007). They watch their tags on every tick, trigger or not. */
    private val meters = GroupMeters(name, spec.items, meterStore)

    /** Save meter totals that have not been written yet. */
    fun flushMeters() = meters.flush()

    /** Last trigger state seen; a rising edge is inactive here and active now. */
    private var triggerWasActive = false
    private var triggerSeen = false
    private var lastTriggerValue: Any? = null

    /** Per item, the value both sides agreed on after the last successful bidirectional run. Null before the first. */
    private var synced: Map<String, Any?>? = null

    /** Test hook: runs on the group thread after phase 1 and before the database is touched. */
    @Volatile var beforeDatabase: (() -> Unit)? = null

    fun tick(): RunOutcome {
        meters.update(clock.millis()) { tags.read(it) }
        val trigger = spec.trigger
        if (trigger != null) {
            val sample = tags.read(trigger.tag)
            val active = truthy(sample)
            val value = sample.value
            val fire = when (trigger.mode) {
                TriggerMode.RISING -> active && !triggerWasActive
                TriggerMode.FALLING -> triggerSeen && !active && triggerWasActive
                TriggerMode.WHILE -> active
                TriggerMode.CHANGE -> triggerSeen && value != lastTriggerValue
            }
            triggerWasActive = active
            triggerSeen = true
            lastTriggerValue = value
            if (!fire) return RunOutcome(executed = false)
        }

        // Phase 1: tags only.
        val inputs = readItems()
        val block = if (spec.groupType == GroupType.BLOCK) readBlock() else emptyList()

        beforeDatabase?.invoke()

        // Phase 2: database only.
        val now = Timestamp.from(Instant.ofEpochMilli(clock.millis()))
        val plan = try {
            db.withConnection(spec.connection) { conn -> database(conn, inputs, block, now) }
        } catch (e: SQLException) {
            throw GroupRunFailed(redacted(e.message ?: "database error"))
        } catch (e: GroupDatabaseUnavailable) {
            throw GroupRunFailed(redacted(e.message ?: "database unavailable"))
        }

        // Phase 3: tags only.
        val failures = ArrayList<String>()
        for ((path, value) in plan.tagWrites) {
            tags.write(path, value)?.let { failures += "$path: $it" }
        }
        if (failures.isNotEmpty()) throw GroupRunFailed("tag write failed (${failures.take(3).joinToString("; ")})")
        if (trigger != null && trigger.reset) {
            val current = tags.read(trigger.tag).value
            val reset = trigger.resetValue ?: if (current is Boolean) false else 0
            tags.write(trigger.tag, reset)?.let { throw GroupRunFailed("trigger reset failed: $it") }
            // The reset is ours; the next set from the device is a fresh rising edge.
            triggerWasActive = false
        }
        if (trigger?.handshakeTag != null) {
            tags.write(trigger.handshakeTag, trigger.handshakeValue)?.let { throw GroupRunFailed("handshake write failed: $it") }
        }
        plan.synced?.let { synced = it }
        val warnings = listOfNotNull(inputs.warning, plan.warning)
        return RunOutcome(true, plan.rowsWritten, plan.tagWrites.size, warnings.joinToString("; ").ifEmpty { null })
    }

    /** The database port has already removed passwords (see [GroupDatabase]); this only bounds the length. */
    private fun redacted(message: String): String = message.take(300)

    // ---- phase 1 ----

    private class Inputs(val values: Map<String, Any?>, val reads: Map<String, TagRead>, val warning: String?)

    private fun readItems(): Inputs {
        val values = LinkedHashMap<String, Any?>()
        val reads = LinkedHashMap<String, TagRead>()
        val bad = ArrayList<String>()
        for (item in spec.items) {
            // An out-only parameter's tag only receives a value; reading it would just add noise.
            if (spec.groupType == GroupType.PROCEDURE && !item.direction.sends) continue
            val read = when {
                meters.owns(item.name) -> TagRead(meters.value(item.name), true)
                else -> readSource(item, reads)
            }
            if (!read.good) bad += item.name
            reads[item.name] = read
            values[item.name] = read.value
        }
        val warning = if (bad.isEmpty()) null else "bad quality: ${bad.take(5).joinToString(",")}"
        return Inputs(values, reads, warning)
    }

    private fun readSource(item: GroupItem, reads: Map<String, TagRead>): TagRead = when (item.source) {
        ItemSource.TAG -> tags.read(item.tag!!)
        ItemSource.STATIC -> TagRead(item.value, true)
        ItemSource.EXPRESSION -> tags.evaluate(item.expression!!) { ref ->
            val earlier = reads.keys.firstOrNull { it.equals(ref, ignoreCase = true) }
            if (earlier != null) reads.getValue(earlier) else tags.read(ref)
        }
    }

    /** Current values of block entries, one list per block item. Constant items return their constants. */
    private fun readBlock(): List<List<Any?>> = spec.blockItems.map { item ->
        item.values ?: item.tags.map { tags.read(it).value }
    }

    // ---- phase 2 ----

    private class Plan(
        val rowsWritten: Int,
        val tagWrites: List<Pair<String, Any?>>,
        val warning: String? = null,
        val synced: Map<String, Any?>? = null,
    )

    private fun database(conn: Connection, inputs: Inputs, block: List<List<Any?>>, now: Timestamp): Plan = when (spec.groupType) {
        GroupType.HISTORICAL -> Plan(insertRow(conn, inputs.values, now, extra = emptyMap()), emptyList())
        GroupType.STANDARD -> if (spec.tableAction == TableAction.INSERT) {
            Plan(insertRow(conn, inputs.values, now, extra = emptyMap()), emptyList())
        } else {
            standardUpdate(conn, inputs, now)
        }
        GroupType.BLOCK -> if (spec.tableAction == TableAction.INSERT) blockInsert(conn, inputs, block, now) else blockUpdate(conn, inputs, block, now)
        GroupType.PROCEDURE -> callProcedure(conn, inputs)
    }

    private val call: org.oip.db.ProcedureCall? by lazy {
        if (spec.groupType == GroupType.PROCEDURE) GroupSpecs.toCall(spec) else null
    }

    /**
     * Bind in and in/out items to their parameters, call the routine, and plan tag writes for the out and
     * in/out values and the return value. The writes happen in phase 3, after the connection is returned.
     */
    private fun callProcedure(conn: Connection, inputs: Inputs): Plan {
        val call = call!!
        val values = LinkedHashMap<String, Any?>()
        for (item in spec.items) {
            if (!item.direction.sends) continue
            values[item.parameter!!] = inputs.values[item.name]
        }
        val bound = try {
            org.oip.db.StoredProcedures.coerce(call, values)
        } catch (e: org.oip.db.QueryRejected) {
            throw GroupRunFailed(e.message ?: "invalid parameter value")
        }
        val outcome = try {
            org.oip.db.StoredProcedures.execute(conn, call, bound, read = { rs, i -> Sql.read(rs, i) })
        } catch (e: org.oip.db.QueryRejected) {
            throw GroupRunFailed(e.message ?: "procedure call refused")
        } catch (e: org.oip.db.QueryFailed) {
            throw GroupRunFailed(e.message ?: "procedure call failed")
        }
        val writes = ArrayList<Pair<String, Any?>>()
        for (item in spec.items) {
            if (!item.direction.receives) continue
            writes += item.tag!! to outcome.outputs[item.parameter!!]
        }
        spec.procedure?.returnTag?.let { writes += it to outcome.returnValue }
        return Plan(0, writes)
    }

    private val table: String get() = Sql.table(spec.table)
    private val dbItems: List<GroupItem> get() = spec.items.filter { it.column != null }

    private fun insertRow(conn: Connection, values: Map<String, Any?>, now: Timestamp, extra: Map<String, Any?>): Int {
        val columns = LinkedHashMap<String, Any?>()
        for (item in dbItems) columns[item.column!!] = values[item.name]
        columns.putAll(extra)
        spec.timestampColumn?.let { columns[it] = now }
        val sql = "INSERT INTO $table (${columns.keys.joinToString(", ") { Sql.ident(it) }}) VALUES (${columns.keys.joinToString(", ") { "?" }})"
        conn.prepareStatement(sql).use { st ->
            bindAll(st, columns.values)
            return st.executeUpdate()
        }
    }

    /** Filter for the selected row: SQL text and its bound values. */
    private class Filter(val sql: String, val args: List<Any?>)

    private fun standardUpdate(conn: Connection, inputs: Inputs, now: Timestamp): Plan {
        val row = spec.row
        val columns = dbItems.map { it.column!! }
        val select = buildString {
            append("SELECT ")
            val selected = if (row.mode == RowMode.FIRST || row.mode == RowMode.LAST) listOf(row.indexColumn) + columns else columns
            append(selected.joinToString(", ") { Sql.ident(it) })
            append(" FROM ").append(table)
        }
        val keyValue = if (row.mode == RowMode.KEY) {
            inputs.values[row.keyItem!!] ?: throw GroupRunFailed("key item ${row.keyItem} has no value")
        } else null
        val (query, args) = when (row.mode) {
            RowMode.FIRST -> "$select ORDER BY ${Sql.ident(row.indexColumn)} ASC LIMIT 1" to emptyList()
            RowMode.LAST -> "$select ORDER BY ${Sql.ident(row.indexColumn)} DESC LIMIT 1" to emptyList()
            RowMode.KEY -> "$select WHERE ${Sql.ident(row.keyColumn!!)} = ? LIMIT 1" to listOf(keyValue)
            RowMode.WHERE -> "$select WHERE (${row.where}) LIMIT 1" to emptyList()
        }
        var filter: Filter? = null
        var current: Map<String, Any?>? = null
        conn.prepareStatement(query).use { st ->
            bindAll(st, args)
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    var offset = 1
                    filter = when (row.mode) {
                        RowMode.FIRST, RowMode.LAST -> Filter("${Sql.ident(row.indexColumn)} = ?", listOf(Sql.read(rs, 1))).also { offset = 2 }
                        RowMode.KEY -> Filter("${Sql.ident(row.keyColumn!!)} = ?", listOf(keyValue))
                        RowMode.WHERE -> Filter("(${row.where})", emptyList())
                    }
                    current = columns.withIndex().associate { (i, c) -> c to Sql.read(rs, offset + i) }
                }
            }
        }
        val found = current
        val where = filter
        if (found == null || where == null) {
            if (!row.insertIfMissing) return Plan(0, emptyList(), warning = "no row matched")
            val extra = if (row.mode == RowMode.KEY) mapOf(row.keyColumn!! to keyValue) else emptyMap()
            val inserted = insertRow(conn, inputs.values, now, extra)
            val baseline = if (spec.updateMode.bidirectional) dbItems.associate { it.name to inputs.values[it.name] } else null
            return Plan(inserted, emptyList(), synced = baseline)
        }

        val dbWrites = LinkedHashMap<String, Any?>()
        val tagWrites = ArrayList<Pair<String, Any?>>()
        var nextSynced: MutableMap<String, Any?>? = null
        when (spec.updateMode) {
            UpdateMode.TAG_TO_DB -> for (item in dbItems) dbWrites[item.column!!] = inputs.values[item.name]
            UpdateMode.DB_TO_TAG -> for (item in dbItems) {
                if (item.source != ItemSource.TAG) continue
                val dbValue = found[item.column]
                if (!Values.same(dbValue, inputs.values[item.name])) tagWrites += item.tag!! to dbValue
            }
            UpdateMode.BIDIRECTIONAL_TAG_WINS, UpdateMode.BIDIRECTIONAL_DB_WINS -> {
                val tagWins = spec.updateMode == UpdateMode.BIDIRECTIONAL_TAG_WINS
                val last = synced
                val agreed = LinkedHashMap<String, Any?>()
                for (item in dbItems) {
                    val tagValue = inputs.values[item.name]
                    val dbValue = found[item.column]
                    if (item.source != ItemSource.TAG) {
                        // Expressions and constants have nothing to receive a database value; they only write.
                        if (!Values.same(tagValue, dbValue)) dbWrites[item.column!!] = tagValue
                        agreed[item.name] = tagValue
                        continue
                    }
                    if (inputs.reads[item.name]?.good != true) {
                        // A bad or missing tag is not a change. Keep the previous baseline and touch neither side.
                        if (last != null && last.containsKey(item.name)) agreed[item.name] = last[item.name]
                        continue
                    }
                    if (Values.same(tagValue, dbValue)) {
                        agreed[item.name] = tagValue
                        continue
                    }
                    val tagChanged = last == null || !last.containsKey(item.name) || !Values.same(tagValue, last[item.name])
                    val dbChanged = last == null || !last.containsKey(item.name) || !Values.same(dbValue, last[item.name])
                    val useTag = when {
                        tagChanged && dbChanged -> tagWins
                        tagChanged -> true
                        else -> false
                    }
                    if (useTag) {
                        dbWrites[item.column!!] = tagValue
                        agreed[item.name] = tagValue
                    } else {
                        tagWrites += item.tag!! to dbValue
                        agreed[item.name] = dbValue
                    }
                }
                nextSynced = agreed
            }
        }
        var rows = 0
        if (dbWrites.isNotEmpty()) {
            spec.timestampColumn?.let { dbWrites[it] = now }
            val sql = "UPDATE $table SET ${dbWrites.keys.joinToString(", ") { "${Sql.ident(it)} = ?" }} WHERE ${where.sql}"
            conn.prepareStatement(sql).use { st ->
                bindAll(st, dbWrites.values + where.args)
                rows = st.executeUpdate()
            }
        }
        return Plan(rows, tagWrites, synced = nextSynced)
    }

    private fun blockInsert(conn: Connection, inputs: Inputs, block: List<List<Any?>>, now: Timestamp): Plan {
        val size = spec.blockItems.first().size
        return inTransaction(conn) {
            val blockId = spec.blockIdColumn?.let { column ->
                conn.prepareStatement("SELECT COALESCE(MAX(${Sql.ident(column)}), 0) + 1 FROM $table").use { st ->
                    st.executeQuery().use { rs -> rs.next(); (rs.getObject(1) as Number).toLong() }
                }
            }
            val columns = ArrayList<String>()
            spec.blockItems.forEach { columns += it.column }
            dbItems.forEach { columns += it.column!! }
            spec.timestampColumn?.let { columns += it }
            spec.rowIdColumn?.let { columns += it }
            spec.blockIdColumn?.let { columns += it }
            val sql = "INSERT INTO $table (${columns.joinToString(", ") { Sql.ident(it) }}) VALUES (${columns.joinToString(", ") { "?" }})"
            conn.prepareStatement(sql).use { st ->
                for (i in 0 until size) {
                    val row = ArrayList<Any?>()
                    block.forEach { row += it[i] }
                    dbItems.forEach { row += inputs.values[it.name] }
                    if (spec.timestampColumn != null) row += now
                    if (spec.rowIdColumn != null) row += i + 1
                    if (blockId != null) row += blockId
                    bindAll(st, row)
                    st.addBatch()
                }
                st.executeBatch()
            }
            Plan(size, emptyList())
        }
    }

    private fun blockUpdate(conn: Connection, inputs: Inputs, block: List<List<Any?>>, now: Timestamp): Plan {
        val size = spec.blockItems.first().size
        val index = Sql.ident(spec.row.indexColumn)
        val blockColumns = spec.blockItems.map { it.column }
        val where = if (spec.row.mode == RowMode.WHERE) " WHERE (${spec.row.where})" else ""
        val select = "SELECT $index, ${blockColumns.joinToString(", ") { Sql.ident(it) }} FROM $table$where ORDER BY $index ASC LIMIT $size"
        val rows = ArrayList<Pair<Any?, List<Any?>>>()
        conn.prepareStatement(select).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) rows += Sql.read(rs, 1) to blockColumns.indices.map { Sql.read(rs, it + 2) }
            }
        }
        val short = if (rows.size < size) "only ${rows.size} of $size block rows matched" else null
        if (spec.updateMode == UpdateMode.DB_TO_TAG) {
            val writes = ArrayList<Pair<String, Any?>>()
            rows.forEachIndexed { r, (_, values) ->
                spec.blockItems.forEachIndexed { c, item ->
                    if (!Values.same(values[c], block[c][r])) writes += item.tags[r] to values[c]
                }
            }
            return Plan(0, writes, warning = short)
        }
        return inTransaction(conn) {
            val sets = ArrayList<String>()
            blockColumns.forEach { sets += it }
            dbItems.forEach { sets += it.column!! }
            spec.timestampColumn?.let { sets += it }
            spec.rowIdColumn?.let { sets += it }
            val sql = "UPDATE $table SET ${sets.joinToString(", ") { "${Sql.ident(it)} = ?" }} WHERE $index = ?"
            var written = 0
            conn.prepareStatement(sql).use { st ->
                rows.forEachIndexed { r, (id, _) ->
                    val args = ArrayList<Any?>()
                    block.forEach { args += it[r] }
                    dbItems.forEach { args += inputs.values[it.name] }
                    if (spec.timestampColumn != null) args += now
                    if (spec.rowIdColumn != null) args += r + 1
                    args += id
                    bindAll(st, args)
                    written += st.executeUpdate()
                }
            }
            Plan(written, emptyList(), warning = short)
        }
    }

    private fun <T> inTransaction(conn: Connection, body: () -> T): T {
        val auto = conn.autoCommit
        conn.autoCommit = false
        try {
            val out = body()
            conn.commit()
            return out
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            runCatching { conn.autoCommit = auto }
        }
    }

    private fun bindAll(st: PreparedStatement, values: Collection<Any?>) {
        values.forEachIndexed { i, v -> Sql.bind(st, i + 1, v) }
    }

}

internal fun truthy(read: TagRead): Boolean {
    if (!read.good) return false
    return when (val v = read.value) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.equals("true", ignoreCase = true) || (v.toDoubleOrNull()?.let { it != 0.0 } ?: false)
        else -> false
    }
}

class GroupRunFailed(message: String) : RuntimeException(message)

/** Identifier quoting and value conversion. Identifiers were checked against a strict pattern at parse time. */
internal object Sql {
    fun ident(name: String): String = "\"$name\""
    fun table(name: String): String = name.split('.').joinToString(".") { ident(it) }

    fun bind(st: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> st.setObject(index, null)
            is Instant -> st.setTimestamp(index, Timestamp.from(value))
            is Timestamp -> st.setTimestamp(index, value)
            is Int -> st.setInt(index, value)
            is Long -> st.setLong(index, value)
            is Float -> st.setDouble(index, value.toDouble())
            is Double -> st.setDouble(index, value)
            is Boolean -> st.setBoolean(index, value)
            is String -> st.setString(index, value)
            is Number -> st.setObject(index, value)
            else -> st.setString(index, value.toString())
        }
    }

    /** A column value in the shapes tags understand: Boolean, Int, Long, Double, String, Instant. */
    fun read(rs: java.sql.ResultSet, index: Int): Any? = when (val v = rs.getObject(index)) {
        null -> null
        is Boolean, is Int, is Long, is Double, is String -> v
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Float -> v.toDouble()
        is java.math.BigDecimal -> if (v.scale() <= 0 && v.abs() < java.math.BigDecimal(Long.MAX_VALUE)) v.toLong() else v.toDouble()
        is java.math.BigInteger -> v.toLong()
        is Timestamp -> v.toInstant()
        is java.time.OffsetDateTime -> v.toInstant()
        is java.time.LocalDateTime -> v.toInstant(java.time.ZoneOffset.UTC)
        else -> v.toString()
    }
}

/** Loose equality across the number and boolean shapes tags and drivers return. */
internal object Values {
    fun same(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a == b) return true
        val x = number(a)
        val y = number(b)
        if (x != null && y != null) return x == y
        if (a is Instant || b is Instant) return instant(a) == instant(b)
        return a.toString() == b.toString()
    }

    private fun number(v: Any): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }

    private fun instant(v: Any): Instant? = when (v) {
        is Instant -> v
        is Timestamp -> v.toInstant()
        is Number -> Instant.ofEpochMilli(v.toLong())
        is String -> runCatching { Instant.parse(v) }.getOrNull()
        else -> null
    }
}
