package org.oip.db

import org.oip.config.SchemaRegistry

private const val NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
private const val SECRET = "^secret://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
private const val PARAM = "^[A-Za-z_][A-Za-z0-9_-]{0,62}$"
private const val ROUTINE = "^[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?$"
private const val QUERY_CONNECTION = "^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?|<default>|<parameter>)$"

fun registerDatabaseSchemas(registry: SchemaRegistry) {
    registry.register(DB_MODULE, CONNECTION_KIND, connectionSchema())
    registry.register(DB_MODULE, QUERY_KIND, querySchema())
    registry.register(DB_MODULE, DRIVER_SETTINGS_KIND, driverSettingsSchema())
    registry.register(DB_MODULE, TRANSLATOR_KIND, translatorSchema())
}

private val EXTRA_PROPERTIES = mapOf(
    "type" to "object",
    "additionalProperties" to mapOf("type" to "string", "maxLength" to 500),
    "maxProperties" to 32,
)

private fun driverSettingsSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "description" to "Overrides for what a connection of one driver kind (or one uploaded driver) gets when it omits a field. The resource name is the kind or the uploaded driver id.",
    "properties" to mapOf(
        "urlTemplate" to mapOf("type" to "string", "maxLength" to 500, "description" to "jdbc: URL with {host}, {port} and {database}."),
        "defaultPort" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 65535),
        "translator" to mapOf("type" to "string", "maxLength" to 63),
        "validationQuery" to mapOf("type" to "string", "maxLength" to 500),
        "driverId" to mapOf("type" to "string", "pattern" to NAME, "description" to "Uploaded driver a connection of this kind uses by default."),
        "extraProperties" to EXTRA_PROPERTIES,
    ),
)

private fun translatorSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("pageTemplate", "limitTemplate", "currentTimestamp", "columnTypes"),
    "properties" to mapOf(
        "quoteOpen" to mapOf("type" to "string", "maxLength" to 4, "default" to "\""),
        "quoteClose" to mapOf("type" to "string", "maxLength" to 4, "default" to "\""),
        "pageTemplate" to mapOf("type" to "string", "maxLength" to 500, "description" to "Uses {sql}, {limit} and {offset}."),
        "limitTemplate" to mapOf("type" to "string", "maxLength" to 500, "description" to "Uses {sql} and {limit}."),
        "currentTimestamp" to mapOf("type" to "string", "maxLength" to 100),
        "validationQuery" to mapOf("type" to "string", "maxLength" to 500, "default" to "SELECT 1"),
        "createTableTemplate" to mapOf("type" to "string", "maxLength" to 500, "default" to "CREATE TABLE {table} ({columns})"),
        "columnTypes" to mapOf(
            "type" to "object",
            "additionalProperties" to mapOf("type" to "string", "maxLength" to 100),
            "description" to "string, text, integer, long, double, boolean and datetime; {length} sizes a string.",
        ),
        "trueLiteral" to mapOf("type" to "string", "maxLength" to 20, "default" to "TRUE"),
        "falseLiteral" to mapOf("type" to "string", "maxLength" to 20, "default" to "FALSE"),
    ),
)

private fun connectionSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("driver"),
    "properties" to mapOf(
        "driver" to mapOf("type" to "string", "enum" to DriverSettings.KINDS),
        "url" to mapOf("type" to "string", "minLength" to 12, "maxLength" to 2000),
        "driverId" to mapOf(
            "type" to "string",
            "pattern" to NAME,
            "description" to "Uploaded JDBC driver (oracle, db2, snowflake and custom connections).",
        ),
        "username" to mapOf("type" to "string", "maxLength" to 128, "default" to ""),
        "password" to mapOf(
            "type" to "string",
            "pattern" to SECRET,
            "description" to "secret:// reference. The password itself is not stored in the config file.",
        ),
        "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "defaultConnection" to mapOf("type" to "boolean", "default" to false),
        "translator" to mapOf("type" to "string", "pattern" to "^[A-Za-z0-9-]{1,63}$"),
        "extraProperties" to EXTRA_PROPERTIES + ("default" to emptyMap<String, Any?>()),
        "failoverConnection" to mapOf("type" to "string", "pattern" to "^$|$NAME", "default" to ""),
        "failoverMode" to mapOf("type" to "string", "enum" to listOf("standard", "sticky"), "default" to "standard"),
        "slowQueryMillis" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 3_600_000, "default" to 60_000),
        "validationTimeoutMillis" to mapOf("type" to "integer", "minimum" to 250, "maximum" to 60_000, "default" to 10_000),
        "validationQuery" to mapOf("type" to "string", "minLength" to 6, "maxLength" to 500, "default" to "SELECT 1"),
        "initialSize" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 32, "default" to 0),
        "maxActive" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 32, "default" to 8),
        "maxIdle" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 32, "default" to 8),
        "minIdle" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 32, "default" to 0),
        "maxWaitMillis" to mapOf("type" to "integer", "minimum" to 250, "maximum" to 120_000, "default" to 5_000),
        "allowBrowserWrites" to mapOf(
            "type" to "boolean",
            "default" to false,
            "description" to "Lets the admin query browser run statements that change data on this connection.",
        ),
    ),
)

private fun querySchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("connection", "queryType"),
    "properties" to mapOf(
        "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
        "enabled" to mapOf("type" to "boolean", "default" to true),
        "connection" to mapOf("type" to "string", "pattern" to QUERY_CONNECTION),
        "queryType" to mapOf("type" to "string", "enum" to listOf("query", "update", "scalar", "procedure")),
        "procedure" to mapOf("type" to "string", "pattern" to ROUTINE, "description" to "Procedure or function name, for queryType procedure."),
        "routine" to mapOf("type" to "string", "enum" to listOf("procedure", "function")),
        "returnType" to mapOf(
            "type" to "string",
            "enum" to listOf("string", "integer", "long", "float", "double", "boolean", "datetime"),
        ),
        "sql" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 100_000),
        "parameters" to mapOf(
            "type" to "array",
            "maxItems" to 64,
            "default" to emptyList<Any>(),
            "items" to mapOf(
                "type" to "object",
                "required" to listOf("name", "dataType"),
                "properties" to mapOf(
                    "name" to mapOf("type" to "string", "pattern" to PARAM),
                    "mode" to mapOf("type" to "string", "enum" to listOf("in", "out", "inout")),
                    "dataType" to mapOf(
                        "type" to "string",
                        "enum" to listOf("string", "integer", "long", "float", "double", "boolean", "datetime"),
                    ),
                    "queryString" to mapOf(
                        "type" to "boolean",
                        "default" to false,
                        "description" to "When true, {name} in the SQL is replaced with an identifier. Otherwise :name is bound.",
                    ),
                ),
            ),
        ),
        "roles" to mapOf(
            "type" to "array",
            "maxItems" to 32,
            "default" to emptyList<Any>(),
            "items" to mapOf("type" to "string", "pattern" to NAME),
        ),
        "cacheEnabled" to mapOf("type" to "boolean", "default" to false),
        "cacheTtlMillis" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 86_400_000, "default" to 0),
        "fallback" to emptyMap<String, Any?>(),
        "maxRows" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100_000, "default" to 10_000),
    ),
)
