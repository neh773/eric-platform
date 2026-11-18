package org.oip.db.groups

import org.oip.config.SchemaRegistry

/**
 * JSON Schema for the `TransactionGroup` kind, and the semantic checks the schema cannot express.
 * Field meanings are described on [GroupSpec]. Names and layout are this project's own.
 */
object GroupSpecs {
    private const val NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
    private const val CONNECTION = "^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?|<default>)$"
    private const val ITEM = "^[A-Za-z_][A-Za-z0-9_]{0,62}$"
    private const val PARAM = "^[A-Za-z_][A-Za-z0-9_-]{0,62}$"

    /** Column or table name, optionally schema-qualified. Quoted in SQL, so the pattern is the only gate. */
    private const val IDENT = "^[A-Za-z_][A-Za-z0-9_]{0,62}$"
    private const val TABLE = "^[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?$"

    const val MAX_ITEMS = 200
    const val MAX_BLOCK_ROWS = 1000
    const val MIN_RATE_MILLIS = 100L
    const val MAX_RATE_MILLIS = 86_400_000L

    private val identRegex = Regex(IDENT)
    private val tableRegex = Regex(TABLE)
    private val itemRegex = Regex(ITEM)

    fun register(registry: SchemaRegistry) {
        registry.register(GROUP_MODULE, GROUP_KIND, schema())
    }

    private fun str(max: Int, pattern: String? = null, default: Any? = NO_DEFAULT): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("type" to "string", "maxLength" to max)
        if (pattern != null) m["pattern"] = pattern
        if (default !== NO_DEFAULT) m["default"] = default
        return m
    }

    private val NO_DEFAULT = Any()

    fun schema(): Map<String, Any?> = mapOf(
        "type" to "object",
        // table is required for every type except storedProcedure; parse() checks it.
        "required" to listOf("connection", "groupType"),
        "properties" to mapOf(
            "description" to str(500, default = ""),
            "enabled" to mapOf("type" to "boolean", "default" to true),
            "connection" to str(64, CONNECTION),
            "table" to str(127, TABLE),
            "groupType" to mapOf("type" to "string", "enum" to GroupType.entries.map { it.wire }),
            "updateMode" to mapOf("type" to "string", "enum" to UpdateMode.entries.map { it.wire }, "default" to UpdateMode.TAG_TO_DB.wire),
            "tableAction" to mapOf("type" to "string", "enum" to TableAction.entries.map { it.wire }),
            "row" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "mode" to mapOf("type" to "string", "enum" to RowMode.entries.map { it.wire }, "default" to RowMode.LAST.wire),
                    "indexColumn" to str(63, IDENT, "id"),
                    "keyColumn" to str(63, IDENT),
                    "keyItem" to str(63, ITEM),
                    "where" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 2000),
                    "insertIfMissing" to mapOf("type" to "boolean", "default" to false),
                ),
            ),
            "execution" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "rateMillis" to mapOf("type" to "integer", "minimum" to MIN_RATE_MILLIS, "maximum" to MAX_RATE_MILLIS, "default" to 1000),
                    "trigger" to mapOf(
                        "type" to "object",
                        "required" to listOf("tag"),
                        "properties" to mapOf(
                            "tag" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 1000),
                            // No schema defaults here: they would make an empty trigger object appear on
                            // timer groups. parse() applies onlyOnChange=true and reset=false.
                            "onlyOnChange" to mapOf("type" to "boolean"),
                            "reset" to mapOf("type" to "boolean"),
                            "resetValue" to mapOf("type" to listOf("boolean", "number", "string", "null")),
                            "mode" to mapOf("type" to "string", "enum" to listOf("rising", "falling", "while", "change")),
                            "handshakeTag" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 1000),
                            "handshakeValue" to mapOf("type" to listOf("boolean", "number", "string", "null")),
                        ),
                    ),
                ),
            ),
            "timestampColumn" to str(63, IDENT),
            "items" to mapOf(
                "type" to "array",
                "maxItems" to MAX_ITEMS,
                "default" to emptyList<Any>(),
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("name", "source"),
                    "properties" to mapOf(
                        "name" to str(63, ITEM),
                        "source" to mapOf("type" to "string", "enum" to ItemSource.entries.map { it.wire }),
                        "tag" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 1000),
                        "expression" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 4000),
                        "value" to mapOf("type" to listOf("boolean", "number", "string", "null")),
                        "column" to str(63, IDENT),
                        "parameter" to str(63, PARAM),
                        "direction" to mapOf("type" to "string", "enum" to org.oip.db.ParamMode.entries.map { it.wire }),
                        "dataType" to mapOf("type" to "string", "enum" to org.oip.db.StoredProcedures.TYPES.sorted()),
                        "meter" to mapOf("type" to "string", "enum" to MeterMode.entries.map { it.wire }),
                        "meterUnit" to mapOf("type" to "string", "enum" to MeterUnit.entries.map { it.wire }),
                    ),
                ),
            ),
            "blockItems" to mapOf(
                "type" to "array",
                "maxItems" to MAX_ITEMS,
                "default" to emptyList<Any>(),
                "items" to mapOf(
                    "type" to "object",
                    "required" to listOf("name", "column"),
                    "properties" to mapOf(
                        "name" to str(63, ITEM),
                        "column" to str(63, IDENT),
                        "tags" to mapOf(
                            "type" to "array",
                            "maxItems" to MAX_BLOCK_ROWS,
                            "items" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 1000),
                        ),
                        "pattern" to mapOf("type" to "string", "minLength" to 4, "maxLength" to 1000),
                        "from" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 1_000_000),
                        "to" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 1_000_000),
                        "values" to mapOf(
                            "type" to "array",
                            "maxItems" to MAX_BLOCK_ROWS,
                            "items" to mapOf("type" to listOf("boolean", "number", "string", "null")),
                        ),
                    ),
                ),
            ),
            "blockIdColumn" to str(63, IDENT),
            "rowIdColumn" to str(63, IDENT),
            "procedure" to mapOf(
                "type" to "object",
                "required" to listOf("name"),
                "properties" to mapOf(
                    "name" to str(127, TABLE),
                    "routine" to mapOf("type" to "string", "enum" to org.oip.db.RoutineKind.entries.map { it.wire }),
                    "returnType" to mapOf("type" to "string", "enum" to org.oip.db.StoredProcedures.TYPES.sorted()),
                    "returnTag" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 1000),
                ),
            ),
        ),
    )

    /** Parse a defaults-applied spec. Throws [GroupRejected] with a short reason. */
    fun parse(fields: Map<String, Any?>): GroupSpec {
        val groupType = GroupType.of(string(fields, "groupType") ?: throw GroupRejected("groupType is required"))
        val updateMode = UpdateMode.of(string(fields, "updateMode") ?: UpdateMode.TAG_TO_DB.wire)
        val tableAction = string(fields, "tableAction")?.let { TableAction.of(it) } ?: when (groupType) {
            GroupType.HISTORICAL -> TableAction.INSERT
            GroupType.PROCEDURE -> TableAction.INSERT
            GroupType.STANDARD, GroupType.BLOCK -> if (updateMode == UpdateMode.TAG_TO_DB) TableAction.INSERT else TableAction.UPDATE
        }
        val connection = string(fields, "connection") ?: throw GroupRejected("connection is required")
        val table = if (groupType == GroupType.PROCEDURE) {
            if (fields["table"] != null) throw GroupRejected("a stored-procedure group has no table")
            ""
        } else {
            string(fields, "table") ?: throw GroupRejected("table is required")
        }
        if (groupType != GroupType.PROCEDURE && !tableRegex.matches(table)) {
            throw GroupRejected("table must be a plain or schema-qualified identifier")
        }

        val rowMap = map(fields["row"], "row")
        val row = RowSelection(
            mode = RowMode.of(string(rowMap, "mode") ?: RowMode.LAST.wire),
            indexColumn = ident(string(rowMap, "indexColumn") ?: "id", "row.indexColumn"),
            keyColumn = string(rowMap, "keyColumn")?.let { ident(it, "row.keyColumn") },
            keyItem = string(rowMap, "keyItem"),
            where = string(rowMap, "where")?.also { checkWhere(it) },
            insertIfMissing = rowMap["insertIfMissing"] as? Boolean ?: false,
        )

        val execution = map(fields["execution"], "execution")
        val rate = (execution["rateMillis"] as? Number)?.toLong() ?: 1000L
        if (rate !in MIN_RATE_MILLIS..MAX_RATE_MILLIS) throw GroupRejected("execution.rateMillis is out of range")
        val trigger = (execution["trigger"] as? Map<*, *>)?.let { raw ->
            @Suppress("UNCHECKED_CAST") val t = raw as Map<String, Any?>
            val only = t["onlyOnChange"] as? Boolean ?: true
            val mode = when (val raw = string(t, "mode")) {
                null -> if (only) TriggerMode.RISING else TriggerMode.WHILE
                "rising" -> TriggerMode.RISING
                "falling" -> TriggerMode.FALLING
                "while" -> TriggerMode.WHILE
                "change" -> TriggerMode.CHANGE
                else -> throw GroupRejected("execution.trigger.mode must be rising, falling, while, or change")
            }
            TriggerSpec(
                tag = string(t, "tag")?.takeIf { it.isNotBlank() } ?: throw GroupRejected("execution.trigger.tag is required"),
                onlyOnChange = only,
                reset = t["reset"] as? Boolean ?: false,
                resetValue = t["resetValue"],
                mode = mode,
                handshakeTag = string(t, "handshakeTag")?.takeIf { it.isNotBlank() },
                handshakeValue = if (t.containsKey("handshakeValue")) t["handshakeValue"] else true,
            )
        }

        val items = items(fields["items"])
        val blockItems = blockItems(fields["blockItems"])
        val spec = GroupSpec(
            description = string(fields, "description") ?: "",
            enabled = fields["enabled"] as? Boolean ?: true,
            connection = connection,
            table = table,
            groupType = groupType,
            updateMode = updateMode,
            tableAction = tableAction,
            row = row,
            rateMillis = rate,
            trigger = trigger,
            timestampColumn = string(fields, "timestampColumn")?.let { ident(it, "timestampColumn") },
            items = items,
            blockItems = blockItems,
            blockIdColumn = string(fields, "blockIdColumn")?.let { ident(it, "blockIdColumn") },
            rowIdColumn = string(fields, "rowIdColumn")?.let { ident(it, "rowIdColumn") },
            procedure = procedureTarget(fields["procedure"]),
        )
        check(spec)
        return spec
    }

    private fun check(spec: GroupSpec) {
        val names = HashSet<String>()
        for (item in spec.items) if (!names.add(item.name.lowercase())) throw GroupRejected("duplicate item name ${item.name}")
        for (item in spec.blockItems) if (!names.add(item.name.lowercase())) throw GroupRejected("duplicate item name ${item.name}")
        val columns = HashSet<String>()
        fun claim(column: String?, what: String) {
            if (column != null && !columns.add(column.lowercase())) throw GroupRejected("column $column is used twice ($what)")
        }
        spec.items.forEach { claim(it.column, it.name) }
        spec.blockItems.forEach { claim(it.column, it.name) }
        claim(spec.timestampColumn, "timestampColumn")
        claim(spec.blockIdColumn, "blockIdColumn")
        claim(spec.rowIdColumn, "rowIdColumn")

        if (spec.groupType == GroupType.PROCEDURE) {
            checkProcedure(spec)
            return
        }
        if (spec.procedure != null) throw GroupRejected("procedure needs groupType storedProcedure")
        if (spec.items.any { it.parameter != null || it.dataType != null || it.direction != org.oip.db.ParamMode.IN }) {
            throw GroupRejected("parameter, direction, and dataType are only for storedProcedure groups")
        }
        when (spec.groupType) {
            GroupType.PROCEDURE -> Unit
            GroupType.HISTORICAL -> {
                if (spec.updateMode != UpdateMode.TAG_TO_DB) throw GroupRejected("a historical group only writes tag values to the database")
                if (spec.tableAction != TableAction.INSERT) throw GroupRejected("a historical group only inserts rows")
                if (spec.blockItems.isNotEmpty()) throw GroupRejected("blockItems need groupType block")
            }
            GroupType.STANDARD -> {
                if (spec.blockItems.isNotEmpty()) throw GroupRejected("blockItems need groupType block")
                if (spec.tableAction == TableAction.INSERT && spec.updateMode != UpdateMode.TAG_TO_DB) {
                    throw GroupRejected("tableAction insert only works with updateMode tagToDb")
                }
            }
            GroupType.BLOCK -> {
                if (spec.blockItems.isEmpty()) throw GroupRejected("a block group needs at least one block item")
                if (spec.updateMode.bidirectional) throw GroupRejected("a block group is tagToDb or dbToTag")
                if (spec.tableAction == TableAction.INSERT && spec.updateMode != UpdateMode.TAG_TO_DB) {
                    throw GroupRejected("tableAction insert only works with updateMode tagToDb")
                }
                if (spec.tableAction == TableAction.UPDATE && spec.row.mode != RowMode.FIRST && spec.row.mode != RowMode.WHERE) {
                    throw GroupRejected("a block update selects rows with row.mode first or where")
                }
                val sizes = spec.blockItems.map { it.size }.toSet()
                if (sizes.size != 1) throw GroupRejected("every block item must have the same number of entries")
                if (sizes.single() == 0) throw GroupRejected("block items need at least one entry")
                if (spec.updateMode == UpdateMode.DB_TO_TAG && spec.blockItems.any { it.values != null }) {
                    throw GroupRejected("constant block items cannot receive database values")
                }
            }
        }

        if (spec.items.none { it.column != null } && spec.blockItems.isEmpty()) throw GroupRejected("at least one item needs a column")
        if (spec.tableAction == TableAction.UPDATE) {
            when (spec.row.mode) {
                RowMode.KEY -> {
                    val keyColumn = spec.row.keyColumn ?: throw GroupRejected("row.keyColumn is required for row.mode key")
                    val keyItem = spec.row.keyItem ?: throw GroupRejected("row.keyItem is required for row.mode key")
                    if (spec.items.none { it.name == keyItem }) throw GroupRejected("row.keyItem $keyItem is not an item")
                    if (spec.items.any { it.column.equals(keyColumn, ignoreCase = true) }) {
                        throw GroupRejected("the key column is matched, not written; remove it from the items")
                    }
                }
                RowMode.WHERE -> if (spec.row.where == null) throw GroupRejected("row.where is required for row.mode where")
                RowMode.FIRST, RowMode.LAST -> Unit
            }
            if (spec.row.insertIfMissing && spec.row.mode == RowMode.WHERE) {
                throw GroupRejected("insertIfMissing needs row.mode first, last, or key")
            }
        }
        for (item in spec.items) {
            if (item.meter == null) {
                if (item.meterUnit != MeterUnit.SECONDS) throw GroupRejected("item ${item.name}: meterUnit needs a meter")
                continue
            }
            if (item.source != ItemSource.TAG) throw GroupRejected("item ${item.name}: a meter watches a tag")
            if (spec.groupType == GroupType.PROCEDURE || spec.updateMode != UpdateMode.TAG_TO_DB) {
                throw GroupRejected("item ${item.name}: a meter only works in a tagToDb table group")
            }
        }
        // Items are evaluated top to bottom. `{name}` of an item further down is read as a tag path instead.
        for (item in spec.items) {
            if (item.source != ItemSource.EXPRESSION) continue
            for (ref in ExpressionRefs.of(item.expression!!)) {
                if (ref.equals(item.name, ignoreCase = true)) throw GroupRejected("item ${item.name} refers to itself")
            }
        }
    }

    private fun procedureTarget(raw: Any?): ProcedureTarget? {
        if (raw == null) return null
        val m = map(raw, "procedure")
        val name = string(m, "name") ?: throw GroupRejected("procedure.name is required")
        if (!tableRegex.matches(name)) throw GroupRejected("procedure.name must be a plain or schema-qualified identifier")
        val routine = string(m, "routine")?.let { wire ->
            org.oip.db.RoutineKind.entries.firstOrNull { it.wire == wire } ?: throw GroupRejected("procedure.routine must be procedure or function")
        } ?: org.oip.db.RoutineKind.PROCEDURE
        return ProcedureTarget(name, routine, string(m, "returnType"), string(m, "returnTag")?.takeIf { it.isNotBlank() })
    }

    /**
     * A stored-procedure group: the call shape must be valid ([toCall] builds it), every item binds one
     * parameter, and only tag items can receive out or in/out values.
     */
    private fun checkProcedure(spec: GroupSpec) {
        val target = spec.procedure ?: throw GroupRejected("a stored-procedure group needs procedure.name")
        if (spec.blockItems.isNotEmpty()) throw GroupRejected("blockItems need groupType block")
        if (spec.timestampColumn != null || spec.blockIdColumn != null || spec.rowIdColumn != null) {
            throw GroupRejected("a stored-procedure group has no table columns")
        }
        for (item in spec.items) {
            if (item.column != null) throw GroupRejected("item ${item.name}: a stored-procedure item binds a parameter, not a column")
            if (item.parameter == null) throw GroupRejected("item ${item.name} needs a parameter")
            if (item.dataType == null) throw GroupRejected("item ${item.name} needs a dataType")
            if (item.direction.receives && item.source != ItemSource.TAG) {
                throw GroupRejected("item ${item.name}: only a tag item can receive an output value")
            }
        }
        if (target.returnTag != null && target.returnType == null) throw GroupRejected("procedure.returnTag needs procedure.returnType")
        if (target.returnType != null && target.returnTag == null) throw GroupRejected("procedure.returnType needs procedure.returnTag")
        try {
            toCall(spec)
        } catch (e: org.oip.db.QueryRejected) {
            throw GroupRejected(e.message ?: "invalid procedure call")
        }
    }

    /** The call a stored-procedure group makes, with parameters in item order. */
    internal fun toCall(spec: GroupSpec): org.oip.db.ProcedureCall {
        val target = spec.procedure ?: throw GroupRejected("a stored-procedure group needs procedure.name")
        return org.oip.db.ProcedureCall(
            routine = target.name,
            kind = target.routine,
            parameters = spec.items.map { org.oip.db.ProcedureParam(it.parameter!!, it.direction, it.dataType!!) },
            returnType = target.returnType,
            maxRows = 1,
        )
    }

    private fun items(raw: Any?): List<GroupItem> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: throw GroupRejected("items must be a list")
        if (list.size > MAX_ITEMS) throw GroupRejected("too many items")
        return list.map { entry ->
            @Suppress("UNCHECKED_CAST")
            val m = entry as? Map<String, Any?> ?: throw GroupRejected("each item must be an object")
            val name = string(m, "name") ?: throw GroupRejected("item name is required")
            if (!itemRegex.matches(name)) throw GroupRejected("item name $name is invalid")
            val source = ItemSource.of(string(m, "source") ?: throw GroupRejected("item $name needs a source"))
            val tag = string(m, "tag")
            val expression = string(m, "expression")
            when (source) {
                ItemSource.TAG -> if (tag.isNullOrBlank()) throw GroupRejected("item $name needs a tag")
                ItemSource.EXPRESSION -> if (expression.isNullOrBlank()) throw GroupRejected("item $name needs an expression")
                ItemSource.STATIC -> if (!m.containsKey("value")) throw GroupRejected("item $name needs a value")
            }
            if (source != ItemSource.TAG && tag != null) throw GroupRejected("only a tag item has a tag")
            if (source != ItemSource.EXPRESSION && expression != null) throw GroupRejected("only an expression item has an expression")
            if (source != ItemSource.STATIC && m.containsKey("value")) throw GroupRejected("only a static item has a value")
            val direction = string(m, "direction")?.let { wire ->
                org.oip.db.ParamMode.entries.firstOrNull { it.wire == wire } ?: throw GroupRejected("item $name direction must be in, out, or inout")
            } ?: org.oip.db.ParamMode.IN
            GroupItem(
                name, source, tag, expression, m["value"], string(m, "column")?.let { ident(it, "item $name column") },
                parameter = string(m, "parameter"),
                direction = direction,
                dataType = string(m, "dataType"),
                meter = string(m, "meter")?.let { MeterMode.of(it) },
                meterUnit = string(m, "meterUnit")?.let { MeterUnit.of(it) } ?: MeterUnit.SECONDS,
            )
        }
    }

    private fun blockItems(raw: Any?): List<BlockItem> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: throw GroupRejected("blockItems must be a list")
        if (list.size > MAX_ITEMS) throw GroupRejected("too many block items")
        return list.map { entry ->
            @Suppress("UNCHECKED_CAST")
            val m = entry as? Map<String, Any?> ?: throw GroupRejected("each block item must be an object")
            val name = string(m, "name") ?: throw GroupRejected("block item name is required")
            if (!itemRegex.matches(name)) throw GroupRejected("block item name $name is invalid")
            val column = ident(string(m, "column") ?: throw GroupRejected("block item $name needs a column"), "block item $name column")
            val tags = (m["tags"] as? List<*>)?.map { it as? String ?: throw GroupRejected("block item $name tags must be strings") }
            val pattern = string(m, "pattern")
            val values = m["values"] as? List<*>
            val sources = listOfNotNull(tags, pattern, values).size
            if (sources != 1) throw GroupRejected("block item $name needs exactly one of tags, pattern, or values")
            val expanded = when {
                tags != null -> tags
                pattern != null -> expand(name, pattern, m["from"], m["to"])
                else -> emptyList()
            }
            if (expanded.size > MAX_BLOCK_ROWS || (values?.size ?: 0) > MAX_BLOCK_ROWS) throw GroupRejected("block item $name is too long")
            BlockItem(name, column, expanded, values?.toList())
        }
    }

    /** `Line/Temp{?}` with from 1 to 3 becomes Line/Temp1, Line/Temp2, Line/Temp3. */
    private fun expand(name: String, pattern: String, fromRaw: Any?, toRaw: Any?): List<String> {
        if (pattern.split("{?}").size != 2) throw GroupRejected("block item $name pattern needs exactly one {?}")
        val from = (fromRaw as? Number)?.toInt() ?: throw GroupRejected("block item $name pattern needs from")
        val to = (toRaw as? Number)?.toInt() ?: throw GroupRejected("block item $name pattern needs to")
        if (to < from) throw GroupRejected("block item $name: to is below from")
        if (to - from + 1 > MAX_BLOCK_ROWS) throw GroupRejected("block item $name is too long")
        return (from..to).map { pattern.replace("{?}", it.toString()) }
    }

    /**
     * The WHERE text is gateway configuration written by a `gateway.write` holder, like named-query SQL.
     * It is still limited to one clause: no statement separator and no comments.
     */
    private fun checkWhere(where: String) {
        if (where.contains(';') || where.contains("--") || where.contains("/*")) {
            throw GroupRejected("row.where must be a single condition without ';' or comments")
        }
    }

    private fun ident(value: String, what: String): String {
        if (!identRegex.matches(value)) throw GroupRejected("$what must be a plain identifier")
        return value
    }

    private fun string(fields: Map<String, Any?>, key: String): String? {
        val v = fields[key] ?: return null
        return v as? String ?: throw GroupRejected("$key must be a string")
    }

    @Suppress("UNCHECKED_CAST")
    private fun map(raw: Any?, key: String): Map<String, Any?> = when (raw) {
        null -> emptyMap()
        is Map<*, *> -> raw as Map<String, Any?>
        else -> throw GroupRejected("$key must be an object")
    }
}

/** `{...}` bodies in an expression, in order. Used for validation only; evaluation is the tag port's job. */
internal object ExpressionRefs {
    private val REF = Regex("\\{([^{}]+)}")
    fun of(expression: String): List<String> = REF.findAll(expression).map { it.groupValues[1].trim() }.toList()
}
