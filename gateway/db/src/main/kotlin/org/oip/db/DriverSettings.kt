package org.oip.db

/**
 * Per-driver defaults (DAT-027): what a connection gets when it leaves a field out. A driver here is a connection
 * kind (`oracle`, `postgres`, ...) or the id of an uploaded JAR. Built-in values live in [BUILT_IN]; a
 * `DriverSettings` config resource of the same name overrides any field of them. For a connection that names a JAR,
 * the JAR's settings come first, then the kind's settings, then the built-in values.
 *
 * `urlTemplate` may use `{host}`, `{port}` and `{database}`; a connection supplies those fields instead of a `url`.
 */
data class DriverSettings(
    val urlTemplate: String? = null,
    val defaultPort: Int? = null,
    val translator: String? = null,
    val validationQuery: String? = null,
    val driverId: String? = null,
    val extraProperties: Map<String, String> = emptyMap(),
) {
    /** Fields set on [over] win; extra properties merge with [over] winning per key. */
    fun overlay(over: DriverSettings?): DriverSettings = if (over == null) this else DriverSettings(
        urlTemplate = over.urlTemplate ?: urlTemplate,
        defaultPort = over.defaultPort ?: defaultPort,
        translator = over.translator ?: translator,
        validationQuery = over.validationQuery ?: validationQuery,
        driverId = over.driverId ?: driverId,
        extraProperties = extraProperties + over.extraProperties,
    )

    fun view(name: String, builtIn: Boolean, overridden: Boolean): Map<String, Any?> = linkedMapOf(
        "name" to name, "builtIn" to builtIn, "overridden" to overridden,
        "urlTemplate" to urlTemplate, "defaultPort" to defaultPort, "translator" to translator,
        "validationQuery" to validationQuery, "driverId" to driverId, "extraProperties" to extraProperties,
    )

    companion object {
        /** Connection kinds that load an uploaded JAR instead of a bundled driver. */
        val EXTERNAL_KINDS = setOf("oracle", "db2", "snowflake", "custom")
        val KINDS = listOf("postgres", "sqlite", "sqlserver", "azuresql", "mariadb", "mysql", "oracle", "db2", "snowflake", "custom")

        val BUILT_IN: Map<String, DriverSettings> = mapOf(
            "postgres" to DriverSettings("jdbc:postgresql://{host}:{port}/{database}", 5432, "POSTGRES"),
            "sqlite" to DriverSettings(translator = "SQLITE"),
            "sqlserver" to DriverSettings("jdbc:sqlserver://{host}:{port};databaseName={database}", 1433, "MSSQL"),
            "azuresql" to DriverSettings("jdbc:sqlserver://{host}:{port};databaseName={database};encrypt=true", 1433, "MSSQL"),
            "mariadb" to DriverSettings("jdbc:mariadb://{host}:{port}/{database}", 3306, "MYSQL"),
            "mysql" to DriverSettings("jdbc:mysql://{host}:{port}/{database}", 3306, "MYSQL"),
            "oracle" to DriverSettings("jdbc:oracle:thin:@//{host}:{port}/{database}", 1521, "ORACLE", "SELECT 1 FROM DUAL"),
            "db2" to DriverSettings("jdbc:db2://{host}:{port}/{database}", 50000, "DB2", "SELECT 1 FROM SYSIBM.SYSDUMMY1"),
            "snowflake" to DriverSettings("jdbc:snowflake://{host}/?db={database}", 443, "SNOWFLAKE"),
            "custom" to DriverSettings(),
        )

        private val FIELDS = setOf("urlTemplate", "defaultPort", "translator", "validationQuery", "driverId", "extraProperties")
        private val PLACEHOLDER = Regex("\\{([A-Za-z]+)}")
        private val HOST = Regex("^(\\[[0-9A-Fa-f:.]{2,45}]|[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?)$")
        private val DATABASE = Regex("^[A-Za-z0-9_.$#-]{1,128}$")

        /** Settings from the fields of a `DriverSettings` resource; every field is checked. */
        fun fromFields(fields: Map<String, Any?>): DriverSettings {
            val unknown = fields.keys - FIELDS
            if (unknown.isNotEmpty()) throw QueryRejected("unknown fields: ${unknown.sorted().joinToString(",")}")
            fun text(key: String, max: Int): String? {
                val raw = fields[key] ?: return null
                val s = raw as? String ?: throw QueryRejected("$key must be a string")
                if (s.isEmpty()) return null
                if (s.length > max || s.any { it.isISOControl() }) throw QueryRejected("$key is invalid")
                return s
            }
            val template = text("urlTemplate", 500)?.also { checkTemplate(it) }
            val port = fields["defaultPort"]?.let {
                val n = when (it) {
                    is Int -> it.toLong()
                    is Long -> it
                    is Short -> it.toLong()
                    else -> throw QueryRejected("defaultPort must be an integer")
                }
                if (n !in 1..65_535) throw QueryRejected("defaultPort must be 1 to 65535")
                n.toInt()
            }
            val validation = text("validationQuery", 500)?.trim()?.also {
                if (!it.startsWith("SELECT", ignoreCase = true) || it.contains(';')) throw QueryRejected("validation query must be one SELECT")
            }
            val driverId = text("driverId", 63)?.also { if (!JdbcDriverStore.ID.matches(it)) throw QueryRejected("driverId must be a lowercase resource name") }
            return DriverSettings(template, port, text("translator", 63), validation, driverId, ConnectionProperties.check(fields["extraProperties"]))
        }

        fun checkTemplate(template: String) {
            if (!template.startsWith("jdbc:") || template.any { it == ' ' || it.isISOControl() }) {
                throw QueryRejected("urlTemplate must start with jdbc: and hold no spaces")
            }
            val bad = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet() - setOf("host", "port", "database")
            if (bad.isNotEmpty()) throw QueryRejected("urlTemplate uses unknown placeholder {${bad.first()}}")
            if (Regex("(?i)(password|pwd|[?&;:]user(name)?)\\s*=").containsMatchIn(template)) {
                throw QueryRejected("put the user and password in their fields, not the url")
            }
        }

        /** The URL a connection gets from [template] and its host, port and database. Values are checked so none can add URL parts. */
        fun buildUrl(template: String, defaultPort: Int?, host: String?, port: Int?, database: String?): String {
            checkTemplate(template)
            val used = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()
            val values = HashMap<String, String>()
            if ("host" in used) {
                if (host.isNullOrEmpty()) throw QueryRejected("host is required")
                if (!HOST.matches(host)) throw QueryRejected("host is invalid")
                values["host"] = host
            }
            if ("port" in used) {
                val p = port ?: defaultPort ?: throw QueryRejected("port is required")
                if (p !in 1..65_535) throw QueryRejected("port must be 1 to 65535")
                values["port"] = p.toString()
            }
            if ("database" in used) {
                if (database.isNullOrEmpty()) throw QueryRejected("database is required")
                if (!DATABASE.matches(database)) throw QueryRejected("database is invalid")
                values["database"] = database
            }
            return PLACEHOLDER.replace(template) { values[it.groupValues[1]] ?: it.value }
        }
    }
}

/** The extra-property rules the connection form has always had, shared with driver settings. */
internal object ConnectionProperties {
    private val KEY = Regex("^[A-Za-z][A-Za-z0-9._-]{0,63}$")
    private val BANNED = setOf("password", "pwd", "pass", "user", "username", "secret", "token")

    fun check(raw: Any?): Map<String, String> {
        if (raw == null) return emptyMap()
        val map = raw as? Map<*, *> ?: throw QueryRejected("extraProperties must be an object")
        if (map.size > 32) throw QueryRejected("too many extra properties")
        val out = linkedMapOf<String, String>()
        for ((key, value) in map) {
            val name = key as? String ?: throw QueryRejected("extra property names must be strings")
            if (!KEY.matches(name) || BANNED.contains(name.lowercase())) throw QueryRejected("extra property '$name' is not allowed")
            val text = value as? String ?: throw QueryRejected("extra property '$name' must be a string")
            if (text.length > 500) throw QueryRejected("extra property '$name' is too long")
            out[name] = text
        }
        return out
    }
}
