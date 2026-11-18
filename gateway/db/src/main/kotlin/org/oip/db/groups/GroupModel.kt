package org.oip.db.groups

/**
 * Transaction groups: move values between tags and a database table on a timer or a trigger.
 *
 * Public pages this follows (behaviour only; wording and structure here are our own):
 * - Overview: -
 * - Group types: -
 * - Item types: -
 * - Update modes: -
 * - Block group: -
 * - Update or insert: -
 * - Module: -
 *
 * Where this implementation chooses behaviour the pages leave open, the tests next to this file record it.
 * - Stored procedures: -
 *
 * A stored-procedure group (`storedProcedure`) calls a routine instead of reading or writing a table: items
 * are bound to its parameters, and out, in/out, and return values are written to tags.
 */

/**
 * Config module for groups. It is not `db` on purpose: the database service re-validates every `db`
 * resource before this kind's schema is registered, which would drop saved groups at startup.
 */
const val GROUP_MODULE = "db-groups"
const val GROUP_KIND = "TransactionGroup"

/** Connection name that picks the connection marked as the default. */
const val GROUP_DEFAULT_CONNECTION = "<default>"

enum class GroupType(val wire: String) {
    STANDARD("standard"), HISTORICAL("historical"), BLOCK("block"), PROCEDURE("storedProcedure");

    companion object {
        fun of(raw: String): GroupType = entries.firstOrNull { it.wire == raw }
            ?: throw GroupRejected("groupType must be standard, historical, block, or storedProcedure")
    }
}

/** Direction of travel. "Tag" covers both device (OPC) tags and memory tags. */
enum class UpdateMode(val wire: String) {
    TAG_TO_DB("tagToDb"), DB_TO_TAG("dbToTag"), BIDIRECTIONAL_TAG_WINS("bidirectionalTagWins"), BIDIRECTIONAL_DB_WINS("bidirectionalDbWins");

    val bidirectional: Boolean get() = this == BIDIRECTIONAL_TAG_WINS || this == BIDIRECTIONAL_DB_WINS
    val readsTags: Boolean get() = this != DB_TO_TAG
    val writesTags: Boolean get() = this != TAG_TO_DB

    companion object {
        fun of(raw: String): UpdateMode = entries.firstOrNull { it.wire == raw }
            ?: throw GroupRejected("updateMode must be tagToDb, dbToTag, bidirectionalTagWins, or bidirectionalDbWins")
    }
}

/** `insert` adds rows each run. `update` selects existing rows (see [RowMode]) and reads or writes them. */
enum class TableAction(val wire: String) {
    INSERT("insert"), UPDATE("update");

    companion object {
        fun of(raw: String): TableAction = entries.firstOrNull { it.wire == raw } ?: throw GroupRejected("tableAction must be insert or update")
    }
}

/** How an update finds its row: lowest or highest index, a key column equal to an item, or a custom WHERE. */
enum class RowMode(val wire: String) {
    FIRST("first"), LAST("last"), KEY("key"), WHERE("where");

    companion object {
        fun of(raw: String): RowMode = entries.firstOrNull { it.wire == raw } ?: throw GroupRejected("row.mode must be first, last, key, or where")
    }
}

enum class ItemSource(val wire: String) {
    TAG("tag"), EXPRESSION("expression"), STATIC("static");

    companion object {
        fun of(raw: String): ItemSource = entries.firstOrNull { it.wire == raw } ?: throw GroupRejected("item source must be tag, expression, or static")
    }
}

/**
 * One value in the group. A tag item reads (and in DB-to-tag modes writes) one tag. An expression item
 * is computed each run; `{name}` refers to an earlier item, anything else in braces is a tag path.
 * A static item is a constant. Only tag items can receive values from the database. An item without a
 * [column] is not written to the table; it can still feed expressions or be the update key.
 *
 * In a stored-procedure group an item binds to [parameter] instead of a column. [direction] says whether
 * the item's value is sent (`in`), the parameter's value is written to the item's tag (`out`), or both
 * (`inout`); [dataType] is the parameter's type.
 */
data class GroupItem(
    val name: String,
    val source: ItemSource,
    val tag: String?,
    val expression: String?,
    val value: Any?,
    val column: String?,
    val parameter: String? = null,
    val direction: org.oip.db.ParamMode = org.oip.db.ParamMode.IN,
    val dataType: String? = null,
    /** Hour or event meter over a boolean tag (DAT-007); null for an ordinary item. */
    val meter: MeterMode? = null,
    val meterUnit: MeterUnit = MeterUnit.SECONDS,
)

/**
 * The routine a stored-procedure group calls. A function's return value goes to [returnTag] when set.
 */
data class ProcedureTarget(
    val name: String,
    val routine: org.oip.db.RoutineKind,
    val returnType: String?,
    val returnTag: String?,
)

/** A column filled from an ordered list of tags (or constants); entry i goes to row i of the block. */
data class BlockItem(
    val name: String,
    val column: String,
    val tags: List<String>,
    val values: List<Any?>?,
) {
    val size: Int get() = values?.size ?: tags.size
}

data class RowSelection(
    val mode: RowMode,
    val indexColumn: String,
    val keyColumn: String?,
    val keyItem: String?,
    val where: String?,
    val insertIfMissing: Boolean,
)

/**
 * Trigger tag settings. With [onlyOnChange] the group runs once when the trigger goes from inactive to
 * active and not again until it has gone inactive. Without it, the group runs on every tick while active.
 * [reset] writes [resetValue] back to the trigger tag after a successful run.
 */
enum class TriggerMode { RISING, FALLING, WHILE, CHANGE }

data class TriggerSpec(
    val tag: String,
    val onlyOnChange: Boolean,
    val reset: Boolean,
    val resetValue: Any?,
    /** Rising edge, falling edge, every tick while active, or any value change. */
    val mode: TriggerMode = if (onlyOnChange) TriggerMode.RISING else TriggerMode.WHILE,
    /** Written after a successful run. Separate from [reset], which writes the trigger tag itself. */
    val handshakeTag: String? = null,
    val handshakeValue: Any? = true,
)

data class GroupSpec(
    val description: String,
    val enabled: Boolean,
    val connection: String,
    val table: String,
    val groupType: GroupType,
    val updateMode: UpdateMode,
    val tableAction: TableAction,
    val row: RowSelection,
    val rateMillis: Long,
    val trigger: TriggerSpec?,
    val timestampColumn: String?,
    val items: List<GroupItem>,
    val blockItems: List<BlockItem>,
    val blockIdColumn: String?,
    val rowIdColumn: String?,
    val procedure: ProcedureTarget? = null,
)

class GroupRejected(message: String) : RuntimeException(message)
class GroupNotFound(message: String) : RuntimeException(message)
