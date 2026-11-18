package org.oip.historian

import org.oip.config.ChangeType
import org.oip.config.ConfigEvent
import org.oip.config.FileConfigStore
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import java.nio.file.Path
import java.time.Clock

const val HISTORY_PROVIDER_KIND = "HistoryProvider"

/** Gateway-wide store-and-forward engine settings ([StoreAndForwardSettings]); the one resource is named [STORE_AND_FORWARD_NAME]. */
const val STORE_AND_FORWARD_KIND = "StoreAndForward"
const val STORE_AND_FORWARD_NAME = "engine"

/** Provider types of a [HISTORY_PROVIDER_KIND] resource. */
enum class HistoryProviderType(val wire: String) {
    SQL("sql"), EMBEDDED("embedded"), CSV("csv"), SIMULATOR("simulator"), SPLITTER("splitter"), TABLE("table");

    companion object {
        fun fromWire(text: String): HistoryProviderType = entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("type must be one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * History providers are config resources: kind [HISTORY_PROVIDER_KIND] in module [HISTORIAN_MODULE], at
 * `<configRoot>/historian/gateway/HistoryProvider/<name>.yaml`. The resource name is the provider name that
 * [HistorySettings.storageProvider] refers to. Types ([HistoryProviderType], doc source: FEATURES HIS-006):
 *  - `sql` (default): `{ connection: plant-db, table: oip_history, timescale: auto }`, where `connection` names a
 *    database connection (the `db/Connection` resource; the reference product's "Data Source"). [connectionSource]
 *    turns that name into JDBC connections. Building a provider does no I/O; it connects on first use, so applying
 *    config never blocks on the database.
 *  - `csv`: `{ file: plant/line1.csv, relativeTimestamps: false }`, read-only history from a file under [csvRoot]
 *    ([CsvHistoryProvider]).
 *  - `table`: an existing PostgreSQL table read as history, read only ([DbTableHistoryProvider], FEATURES HIS-008):
 *    `{ connection: plant-db, sourceTable: public.readings, timestampColumn: ts, layout: narrow, tagColumn: tag,
 *    valueColumn: val, qualityColumn: q }`, or `layout: wide` with `tagColumns: { Plant/Speed: speed }`.
 *  - `simulator`: synthetic history named by the tag path ([SimulatorHistoryProvider], FEATURES HIS-009).
 *  - `splitter`: `{ first: plant-pg, second: local, limitFirstQuery: { length: 7, units: days } }`, writes to both and
 *    reads from the first ([SplitterHistoryProvider]).
 *  - `embedded`: only for the name `local`, the built-in [EmbeddedHistorian]: `{ partitionInterval: week,
 *    deduplication: true, maintenance: { mode: prune, age: 12, ageUnits: month } }` (FEATURES HIS-007). Deleting
 *    the resource restores the defaults. Any other type under the name `local` is refused.
 *
 * Every non-local provider may carry `storeAndForward: { forwardRateMillis, batchSize, retryMillis, maxPending,
 * whenFull }` ([ForwardPolicy], FEATURES HIS-024); it applies even while the provider is disabled, so a cap still
 * bounds its queue. The gateway-wide engine settings are the [STORE_AND_FORWARD_KIND] resource named
 * [STORE_AND_FORWARD_NAME], handed to [onEngine].
 */
class HistoryProviderBinding(
    val router: HistoryRouter,
    val store: FileConfigStore,
    val registry: SchemaRegistry,
    private val local: EmbeddedHistorian? = null,
    /** Folder CSV provider files are read from; null refuses csv providers. */
    private val csvRoot: Path? = null,
    /** Folder holding the embedded historian's archive folders; null refuses archive mode. */
    private val archiveRoot: Path? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val onEngine: (StoreAndForwardSettings) -> Unit = {},
    private val connectionSource: (connection: String) -> JdbcConnectionSource,
) : AutoCloseable {
    private val problems = ArrayList<String>()
    private var subscription: AutoCloseable? = null

    @Synchronized
    fun problems(): List<String> = problems.toList()

    fun attach() {
        registerHistorySchemas(registry)
        store.revalidate(HISTORIAN_MODULE)
        store.list(HISTORIAN_MODULE, kind = STORE_AND_FORWARD_KIND).forEach { apply(it.id, it.resource) }
        store.list(HISTORIAN_MODULE, kind = HISTORY_PROVIDER_KIND).forEach { apply(it.id, it.resource) }
        subscription = store.subscribe { event -> onEvent(event) }
    }

    override fun close() {
        subscription?.close()
        subscription = null
    }

    private fun onEvent(event: ConfigEvent) {
        val id = event.id ?: return
        if (id.module != HISTORIAN_MODULE || (id.kind != HISTORY_PROVIDER_KIND && id.kind != STORE_AND_FORWARD_KIND)) return
        when (event.type) {
            ChangeType.DELETED -> remove(id)
            ChangeType.CREATED, ChangeType.UPDATED -> event.resource?.let { apply(id, it) }
            ChangeType.INVALID -> synchronized(this) { problems += "$id: ${event.error}" }
        }
    }

    @Synchronized
    private fun remove(id: ResourceId) {
        if (id.kind == STORE_AND_FORWARD_KIND) {
            if (id.name == STORE_AND_FORWARD_NAME) onEngine(StoreAndForwardSettings())
            return
        }
        if (id.name == LOCAL_PROVIDER) {
            local?.configure(EmbeddedHistorianSettings())
            return
        }
        router.unregister(id.name)
        router.setPolicy(id.name, null)
    }

    @Synchronized
    private fun apply(id: ResourceId, resource: Resource) {
        try {
            if (id.kind == STORE_AND_FORWARD_KIND) {
                require(id.name == STORE_AND_FORWARD_NAME) { "the store-and-forward engine resource must be named '$STORE_AND_FORWARD_NAME'" }
                onEngine(engineSettings(resource.spec))
                return
            }
            val name = id.name
            val spec = resource.spec
            val type = HistoryProviderType.fromWire(spec["type"] as? String ?: HistoryProviderType.SQL.wire)
            if (name == LOCAL_PROVIDER) {
                require(type == HistoryProviderType.EMBEDDED) { "'$LOCAL_PROVIDER' is the built-in provider and cannot be redefined; only type embedded settings apply to it" }
                val historian = local ?: throw IllegalArgumentException("this gateway has no embedded historian")
                historian.configure(embeddedSettings(spec))
                return
            }
            require(type != HistoryProviderType.EMBEDDED) { "type embedded is only the built-in '$LOCAL_PROVIDER' provider" }
            router.setPolicy(name, (spec["storeAndForward"] as? Map<*, *>)?.let { forwardPolicy(it) })
            if (spec["enabled"] == false) {
                router.unregister(name)
                return
            }
            router.register(
                when (type) {
                    HistoryProviderType.SQL -> {
                        val connection = spec["connection"] as? String ?: throw IllegalArgumentException("connection is required")
                        SqlHistoryProvider(
                            name = name,
                            connections = connectionSource(connection),
                            table = spec["table"] as? String ?: SqlHistoryProvider.DEFAULT_TABLE,
                            timescale = TimescaleMode.fromWire(spec["timescale"] as? String ?: TimescaleMode.AUTO.wire),
                        )
                    }
                    HistoryProviderType.CSV -> {
                        val root = csvRoot ?: throw IllegalArgumentException("csv providers are not available on this gateway")
                        val file = spec["file"] as? String ?: throw IllegalArgumentException("file is required")
                        CsvHistoryProvider(name, resolveInside(root, file), relativeTimestamps = spec["relativeTimestamps"] == true)
                    }
                    HistoryProviderType.TABLE -> tableProvider(name, spec)
                    HistoryProviderType.SIMULATOR -> SimulatorHistoryProvider(name)
                    HistoryProviderType.SPLITTER -> {
                        val first = spec["first"] as? String ?: throw IllegalArgumentException("first is required")
                        val second = spec["second"] as? String ?: throw IllegalArgumentException("second is required")
                        val limit = (spec["limitFirstQuery"] as? Map<*, *>)?.let { durationMillis(it) } ?: 0L
                        SplitterHistoryProvider(name, first, second, limit, clock) { router.reader(it) }
                    }
                    HistoryProviderType.EMBEDDED -> error("unreachable")
                }
            )
        } catch (e: RuntimeException) {
            problems += "$id: ${e.message}"
        }
    }

    private fun tableProvider(name: String, spec: Map<String, Any?>): DbTableHistoryProvider {
        fun text(key: String) = spec[key] as? String ?: throw IllegalArgumentException("$key is required for type table")
        val layout = when (val kind = spec["layout"] as? String ?: "narrow") {
            "narrow" -> TableLayout.Narrow(
                tagColumn = text("tagColumn"),
                valueColumn = text("valueColumn"),
                qualityColumn = spec["qualityColumn"] as? String,
                tagIds = (spec["tagIds"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                    (k as String) to (v as? Number ?: v as? String ?: throw IllegalArgumentException("tagIds values must be numbers or text"))
                } ?: emptyMap(),
            )
            "wide" -> TableLayout.Wide(
                (spec["tagColumns"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                    (k as String) to (v as? String ?: throw IllegalArgumentException("tagColumns values must be column names"))
                } ?: throw IllegalArgumentException("tagColumns is required for layout wide"),
            )
            else -> throw IllegalArgumentException("layout must be narrow or wide, not '$kind'")
        }
        return DbTableHistoryProvider(
            name = name,
            connections = connectionSource(spec["connection"] as? String ?: throw IllegalArgumentException("connection is required")),
            table = text("sourceTable"),
            timestampColumn = text("timestampColumn"),
            layout = layout,
            timestampUnit = TimestampUnit.fromWire(spec["timestampUnit"] as? String ?: TimestampUnit.TIMESTAMP.wire),
        )
    }

    private fun embeddedSettings(spec: Map<String, Any?>): EmbeddedHistorianSettings {
        val interval = PartitionInterval.fromWire(spec["partitionInterval"] as? String ?: PartitionInterval.WEEK.wire)
        val m = spec["maintenance"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val mode = MaintenanceMode.fromWire(m["mode"] as? String ?: MaintenanceMode.NONE.wire)
        val folder = if (mode != MaintenanceMode.ARCHIVE) null else {
            val root = archiveRoot ?: throw IllegalArgumentException("archive mode is not available on this gateway")
            resolveInside(root, m["archiveFolder"] as? String ?: LOCAL_PROVIDER)
        }
        return EmbeddedHistorianSettings(
            partitionInterval = interval,
            deduplication = spec["deduplication"] as? Boolean ?: true,
            maintenanceMode = mode,
            maintenanceAge = (m["age"] as? Number)?.toInt() ?: 0,
            maintenanceAgeUnits = (m["ageUnits"] as? String)?.let { PartitionInterval.fromWire(it) } ?: interval,
            archiveFolder = folder,
        )
    }

    companion object {
        /** [StoreAndForwardSettings] from a [STORE_AND_FORWARD_KIND] spec that passed schema validation. */
        fun engineSettings(spec: Map<String, Any?>): StoreAndForwardSettings {
            val d = StoreAndForwardSettings()
            return StoreAndForwardSettings(
                memoryBufferSize = (spec["memoryBufferSize"] as? Number)?.toInt() ?: d.memoryBufferSize,
                timeThresholdMillis = (spec["timeThresholdMillis"] as? Number)?.toLong() ?: d.timeThresholdMillis,
                dataThreshold = (spec["dataThreshold"] as? Number)?.toInt() ?: d.dataThreshold,
                batchSize = (spec["batchSize"] as? Number)?.toInt() ?: d.batchSize,
            )
        }

        /** [ForwardPolicy] from a provider's `storeAndForward` block. */
        fun forwardPolicy(spec: Map<*, *>): ForwardPolicy {
            val d = ForwardPolicy()
            return ForwardPolicy(
                forwardRateMillis = (spec["forwardRateMillis"] as? Number)?.toLong() ?: d.forwardRateMillis,
                batchSize = (spec["batchSize"] as? Number)?.toInt() ?: d.batchSize,
                retryMillis = (spec["retryMillis"] as? Number)?.toLong() ?: d.retryMillis,
                maxPending = (spec["maxPending"] as? Number)?.toLong() ?: d.maxPending,
                whenFull = (spec["whenFull"] as? String)?.let { WhenFull.fromWire(it) } ?: d.whenFull,
            )
        }

        private fun durationMillis(spec: Map<*, *>): Long {
            val length = (spec["length"] as? Number)?.toLong() ?: throw IllegalArgumentException("limitFirstQuery.length is required")
            val unit = when ((spec["units"] as? String ?: "days").lowercase()) {
                "minutes" -> 60_000L
                "hours" -> 3_600_000L
                "days" -> 86_400_000L
                "weeks" -> 604_800_000L
                else -> throw IllegalArgumentException("limitFirstQuery.units must be minutes, hours, days or weeks")
            }
            return Math.multiplyExact(length, unit)
        }

        /** [relative] under [root]; anything that would leave [root] is refused. */
        internal fun resolveInside(root: Path, relative: String): Path {
            require(RELATIVE_FILE.matches(relative) && relative.split('/').none { it == "." || it == ".." }) {
                "'$relative' must be a relative path of letters, digits, '.', '_' and '-' segments"
            }
            val base = root.toAbsolutePath().normalize()
            val resolved = base.resolve(relative).normalize()
            require(resolved.startsWith(base) && resolved != base) { "'$relative' leaves its folder" }
            return resolved
        }

        internal val RELATIVE_FILE = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}(/[A-Za-z0-9_][A-Za-z0-9._-]{0,127}){0,7}$")
    }
}

internal fun historyProviderSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "properties" to linkedMapOf(
        "type" to mapOf("type" to "string", "enum" to HistoryProviderType.entries.map { it.wire }, "default" to "sql",
            "description" to "sql: PostgreSQL or TimescaleDB through store-and-forward. csv: read-only history from a CSV file. " +
                "simulator: synthetic history named by the tag path. splitter: writes to two providers. embedded: settings of the built-in 'local' provider."),
        "connection" to mapOf("type" to "string", "pattern" to PROVIDER_NAME.pattern,
            "description" to "sql: name of the db/Connection resource (the data source) to store history in."),
        "table" to mapOf("type" to "string", "pattern" to SqlHistoryProvider.TABLE_NAME.pattern, "default" to SqlHistoryProvider.DEFAULT_TABLE),
        "timescale" to mapOf("type" to "string", "enum" to TimescaleMode.entries.map { it.wire }, "default" to TimescaleMode.AUTO.wire,
            "description" to "auto: hypertable when the timescaledb extension is installed; off: plain table; required: fail without the extension."),
        "enabled" to mapOf("type" to "boolean", "default" to true,
            "description" to "When false, nothing is forwarded; records for this provider stay buffered."),
        "file" to mapOf("type" to "string", "pattern" to HistoryProviderBinding.RELATIVE_FILE.pattern,
            "description" to "csv: file under the gateway's history-csv folder. Header: timestamp column, then one tag path per column."),
        "relativeTimestamps" to mapOf("type" to "boolean", "default" to false,
            "description" to "csv: the first row's timestamp is a point in time and later rows hold milliseconds after it."),
        "sourceTable" to mapOf("type" to "string", "pattern" to "^[A-Za-z_][A-Za-z0-9_$]{0,62}(\\.[A-Za-z_][A-Za-z0-9_$]{0,62})?$",
            "description" to "table: the existing PostgreSQL table (or schema.table) to read. It is never written."),
        "timestampColumn" to mapOf("type" to "string", "description" to "table: column holding each sample's time."),
        "timestampUnit" to mapOf("type" to "string", "enum" to TimestampUnit.entries.map { it.wire }, "default" to TimestampUnit.TIMESTAMP.wire,
            "description" to "table: timestamp and timestamptz columns are read as UTC; epochMillis and epochSeconds read a number."),
        "layout" to mapOf("type" to "string", "enum" to listOf("narrow", "wide"), "default" to "narrow",
            "description" to "table: narrow has one row per sample; wide has one column per tag."),
        "tagColumn" to mapOf("type" to "string", "description" to "table, narrow: column holding the tag path, or a tag id when tagIds is set."),
        "valueColumn" to mapOf("type" to "string", "description" to "table, narrow: column holding the value."),
        "qualityColumn" to mapOf("type" to "string", "description" to "table, narrow: optional column holding quality (a name or a code). Without it every sample is Good."),
        "tagIds" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to listOf("string", "number")),
            "description" to "table, narrow: tag path to the id stored in tagColumn."),
        "tagColumns" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string"),
            "description" to "table, wide: tag path to the column holding that tag's values."),
        "first" to mapOf("type" to "string", "pattern" to PROVIDER_NAME.pattern, "description" to "splitter: provider that is written and queried."),
        "second" to mapOf("type" to "string", "pattern" to PROVIDER_NAME.pattern,
            "description" to "splitter: provider that is written, and queried when the first cannot be or for times outside its limit."),
        "limitFirstQuery" to mapOf(
            "type" to "object",
            "required" to listOf("length"),
            "description" to "splitter: the first provider answers only the most recent length x units; older times come from the second.",
            "properties" to mapOf(
                "length" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100_000),
                "units" to mapOf("type" to "string", "enum" to listOf("minutes", "hours", "days", "weeks"), "default" to "days"),
            ),
        ),
        "partitionInterval" to mapOf("type" to "string", "enum" to PartitionInterval.entries.map { it.wire }, "default" to PartitionInterval.WEEK.wire,
            "description" to "embedded: time span of one partition (UTC; weeks start on Monday)."),
        "deduplication" to mapOf("type" to "boolean", "default" to true,
            "description" to "embedded: a row identical to a stored row (same tag, timestamp, value and quality) is not stored again."),
        "maintenance" to mapOf(
            "type" to "object",
            "description" to "embedded: what happens to partitions older than age x ageUnits.",
            "properties" to mapOf(
                "mode" to mapOf("type" to "string", "enum" to MaintenanceMode.entries.map { it.wire }, "default" to MaintenanceMode.NONE.wire),
                "age" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100_000),
                "ageUnits" to mapOf("type" to "string", "enum" to PartitionInterval.entries.map { it.wire },
                    "description" to "Must be a whole multiple of the partition interval. Defaults to the partition interval."),
                "archiveFolder" to mapOf("type" to "string", "pattern" to PROVIDER_NAME.pattern, "default" to LOCAL_PROVIDER,
                    "description" to "archive: folder under the gateway's history-archive folder."),
            ),
        ),
        "storeAndForward" to mapOf(
            "type" to "object",
            "description" to "Store-and-forward settings of this provider.",
            "properties" to mapOf(
                "forwardRateMillis" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 86_400_000, "default" to ForwardPolicy.DEFAULT_FORWARD_RATE_MILLIS,
                    "description" to "How often queued records are eligible to forward."),
                "batchSize" to mapOf("type" to "integer", "minimum" to 1, "maximum" to ForwardPolicy.MAX_BATCH, "default" to HistoryRouter.DEFAULT_BATCH,
                    "description" to "Records per write to the provider."),
                "retryMillis" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 86_400_000, "default" to HistoryRouter.DEFAULT_RETRY_MILLIS,
                    "description" to "Wait after the provider was unreachable."),
                "maxPending" to mapOf("type" to "integer", "minimum" to 0, "default" to 0,
                    "description" to "Most records the disk cache holds for this provider; 0 for no limit."),
                "whenFull" to mapOf("type" to "string", "enum" to WhenFull.entries.map { it.wire }, "default" to WhenFull.PREVENT_NEW_DATA.wire,
                    "description" to "preventNewData refuses new samples; evictOldestData deletes the oldest queued records."),
            ),
        ),
    ),
)

/** Errors JSON Schema cannot state: the fields each provider type needs. */
internal fun historyProviderSemantics(spec: Map<String, Any?>): List<String> {
    val type = runCatching { HistoryProviderType.fromWire(spec["type"] as? String ?: "sql") }.getOrNull() ?: return emptyList()
    val need = when (type) {
        HistoryProviderType.SQL -> listOf("connection")
        HistoryProviderType.CSV -> listOf("file")
        HistoryProviderType.SPLITTER -> listOf("first", "second")
        HistoryProviderType.TABLE -> listOf("connection", "sourceTable", "timestampColumn") +
            if (spec["layout"] == "wide") listOf("tagColumns") else listOf("tagColumn", "valueColumn")
        else -> emptyList()
    }
    val out = need.filter { spec[it] == null }.map { "$it is required for type ${type.wire}" }.toMutableList()
    (spec["file"] as? String)?.let { f -> if (f.split('/').any { it == "." || it == ".." }) out += "file must not contain '.' or '..' segments" }
    if (type == HistoryProviderType.SPLITTER && spec["first"] != null && spec["first"] == spec["second"]) out += "first and second must be different providers"
    if (type == HistoryProviderType.EMBEDDED) {
        val m = spec["maintenance"] as? Map<*, *>
        val mode = m?.get("mode") as? String
        if (mode != null && mode != MaintenanceMode.NONE.wire && m["age"] == null) out += "maintenance.age is required for mode $mode"
    }
    return out
}

internal fun storeAndForwardSchema(): Map<String, Any?> = linkedMapOf(
    "\$schema" to SchemaRegistry.DRAFT,
    "type" to "object",
    "properties" to linkedMapOf(
        "memoryBufferSize" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 50_000_000, "default" to HistoryCollector.DEFAULT_QUEUE_CAPACITY,
            "description" to "Samples held in memory before new ones are dropped."),
        "timeThresholdMillis" to mapOf("type" to "integer", "minimum" to StoreAndForwardSettings.MIN_TIME_THRESHOLD_MILLIS,
            "maximum" to StoreAndForwardSettings.MAX_TIME_THRESHOLD_MILLIS, "default" to StoreAndForwardSettings.DEFAULT_TIME_THRESHOLD_MILLIS,
            "description" to "Longest a sample waits in memory before it is written."),
        "dataThreshold" to mapOf("type" to "integer", "minimum" to 1, "default" to StoreAndForwardSettings.DEFAULT_DATA_THRESHOLD,
            "description" to "Samples in memory that trigger a write before the time threshold."),
        "batchSize" to mapOf("type" to "integer", "minimum" to 1, "maximum" to ForwardPolicy.MAX_BATCH, "default" to HistoryCollector.MAX_BATCH,
            "description" to "Samples per write from memory to disk."),
    ),
)
