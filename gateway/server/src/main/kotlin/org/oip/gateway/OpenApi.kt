package org.oip.gateway

/** Hand-maintained OpenAPI 3.1 description of `/api/v1` (ADR 0007), served at `/api/docs`. */
object OpenApi {
    private fun problem(desc: String) = mapOf("description" to desc,
        "content" to mapOf("application/problem+json" to mapOf("schema" to ref("Problem"))))
    private fun ref(n: String) = mapOf("\$ref" to "#/components/schemas/$n")
    private fun json(schema: Any, desc: String) = mapOf("description" to desc, "content" to mapOf("application/json" to mapOf("schema" to schema)))
    private fun p(name: String, where: String = "path", required: Boolean = where == "path") =
        mapOf("name" to name, "in" to where, "required" to required, "schema" to mapOf("type" to "string"))
    private val ifMatch = mapOf("name" to "If-Match", "in" to "header", "required" to false, "schema" to mapOf("type" to "string"),
        "description" to "Expected resource version (ETag)")
    private val csrf = mapOf("\$ref" to "#/components/parameters/CsrfToken")
    private val errors = mapOf(
        "400" to problem("Malformed request"), "401" to problem("Authentication required"),
        "403" to problem("Forbidden"), "404" to problem("Not found"),
        "409" to problem("Version conflict / already exists"), "422" to problem("Validation failed"))
    private fun op(vararg entries: Pair<String, Any?>, public: Boolean = false): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        m["security"] = if (public) emptyList<Any>() else listOf(
            mapOf("sessionCookie" to emptyList<String>()), mapOf("bearerAuth" to emptyList<String>()))
        for ((k, v) in entries) m[k] = v
        return m
    }

    private fun q(name: String) = mapOf(
        "name" to name, "in" to "query", "required" to false, "schema" to mapOf("type" to "string"),
    )

    private fun qLimit() = mapOf(
        "name" to "limit", "in" to "query", "required" to false,
        "schema" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 1000, "default" to 100),
    )

    private fun itemOps(params: List<Any>) = mapOf(
        "parameters" to params,
        "description" to "Config resource. Requires gateway.read (GET) or gateway.write. org.oip.security User, Role, and ApiKey resources: written only through /api/v1/security (403 here), read with users.admin, roles.admin, or apikeys.admin, never with passwordHash, passwordHistory, or keyHash.",
        "get" to mapOf("summary" to "Get a resource", "responses" to mapOf("200" to json(ref("Resource"), "Resource")) + errors),
        "put" to mapOf("summary" to "Create (metadata.version 0) or update (current version) a resource",
            "parameters" to listOf(csrf),
            "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("Resource")))),
            "responses" to mapOf("200" to json(ref("Resource"), "Updated"), "201" to json(ref("Resource"), "Created")) + errors),
        "patch" to mapOf("summary" to "RFC 7386 merge patch of spec", "parameters" to listOf(ifMatch, csrf),
            "requestBody" to mapOf("required" to true, "content" to mapOf("application/merge-patch+json" to mapOf("schema" to mapOf("type" to "object")))),
            "responses" to mapOf("200" to json(ref("Resource"), "Patched")) + errors),
        "delete" to mapOf("summary" to "Delete a resource", "parameters" to listOf(ifMatch, csrf),
            "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors))
    private fun collectionOps(params: List<Any>) = mapOf(
        "parameters" to params,
        "description" to "Config resource collection. Requires gateway.read (GET) or gateway.write. org.oip.security User, Role, and ApiKey resources: written only through /api/v1/security (403 here), read with users.admin, roles.admin, or apikeys.admin, never with passwordHash, passwordHistory, or keyHash.",
        "get" to mapOf("summary" to "List resources of a kind", "responses" to mapOf("200" to json(ref("ResourceList"), "Resources")) + errors),
        "post" to mapOf("summary" to "Create a resource", "parameters" to listOf(csrf),
            "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("Resource")))),
            "responses" to mapOf("201" to json(ref("Resource"), "Created")) + errors))

    private val bin = mapOf("required" to true, "content" to mapOf("application/zip" to mapOf("schema" to mapOf("type" to "string", "contentMediaType" to "application/zip"))))
    private val sealedBackup = mapOf("description" to "Encrypted backup (.oipbk)", "content" to mapOf("application/octet-stream" to
        mapOf("schema" to mapOf("type" to "string", "contentMediaType" to "application/octet-stream"))))
    private val modErr = mapOf("401" to problem("Authentication required"), "403" to problem("Forbidden"),
        "404" to problem("Unknown module"), "409" to problem("Conflict (already installed, dependents, downgrade)"),
        "422" to problem("Invalid package"))
    private fun dbPaths(): Map<String, Any?> = buildMap {
        put("/api/v1/db/connections", mapOf(
                    "get" to op("summary" to "List database connections and health. Requires gateway.read. Passwords are not returned.",
                        "responses" to mapOf("200" to json(ref("DatabaseConnectionList"), "Connections")) + errors),
                    "post" to op("summary" to "Create a database connection (Postgres, SQLite, SQL Server, MariaDB/MySQL, or Oracle, DB2, Snowflake and custom drivers through an uploaded JAR). Requires gateway.write. The password is stored as a secret and is not returned.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("DatabaseConnectionWrite")))),
                        "responses" to mapOf("201" to json(ref("DatabaseConnection"), "Created")) + errors),
                ))
        put("/api/v1/db/connections/{name}", mapOf("parameters" to listOf(p("name")),
                    "get" to op("summary" to "Get one database connection and its health. Requires gateway.read.",
                        "responses" to mapOf("200" to json(ref("DatabaseConnection"), "Connection")) + errors),
                    "put" to op("summary" to "Update a database connection. Requires gateway.write. Omit password to keep the stored secret.",
                        "parameters" to listOf(csrf, ifMatch),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("DatabaseConnectionWrite")))),
                        "responses" to mapOf("200" to json(ref("DatabaseConnection"), "Updated")) + errors),
                    "delete" to op("summary" to "Delete a database connection. Requires gateway.write.",
                        "parameters" to listOf(csrf, ifMatch),
                        "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors),
                ))
        put("/api/v1/db/connections/{name}/refresh", mapOf("parameters" to listOf(p("name")),
                    "post" to op("summary" to "Run the validation query now. Requires gateway.write.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("200" to json(ref("DatabaseConnection"), "Status")) + errors),
                ))
        // C-17: admin query browser.
        put("/api/v1/db/connections/{name}/browse", mapOf("parameters" to listOf(p("name")),
                    "post" to op("summary" to "Run one ad hoc SQL statement on a connection. Requires gateway.write.",
                        "description" to "Reads (SELECT, WITH, VALUES, TABLE, SHOW, EXPLAIN) run in a read-only transaction that is rolled back; this keeps table data unchanged but does not stop functions with effects outside the transaction, so use a read-only login role for browse-only connections. Any other statement is refused with 403 unless the connection used (including a failover connection) sets allowBrowserWrites. One statement per request, and no transaction statements (422); nothing is bound.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("DatabaseBrowseRequest")))),
                        "responses" to mapOf(
                            "200" to json(mapOf("oneOf" to listOf(ref("QueryRows"), ref("QueryUpdate"))), "Result"),
                            "503" to problem("Connection unavailable"),
                        ) + errors),
                ))
        // DAT-016, DAT-027: uploaded JDBC drivers, per-driver defaults and SQL translators.
        put("/api/v1/db/drivers", mapOf(
                    "get" to op("summary" to "List uploaded JDBC drivers and the connections that use them. Requires gateway.read.",
                        "responses" to mapOf("200" to json(ref("JdbcDriverList"), "Drivers")) + errors),
                    "post" to op("summary" to "Upload a JDBC driver JAR as the request body. Requires modules.admin; audited.",
                        "description" to "The id query parameter names the driver (a lowercase resource name). driverClass is needed only when the JAR has no META-INF/services/java.sql.Driver entry. The body must be a ZIP of at most 128 MiB holding the driver class. 422 for a bad JAR, id or class. The driver runs in its own class loader once a connection opens it.",
                        "parameters" to listOf(csrf, mapOf("name" to "id", "in" to "query", "required" to true, "schema" to mapOf("type" to "string")),
                            mapOf("name" to "driverClass", "in" to "query", "required" to false, "schema" to mapOf("type" to "string"))),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/java-archive" to mapOf("schema" to mapOf("type" to "string", "contentMediaType" to "application/java-archive")))),
                        "responses" to mapOf("201" to json(ref("JdbcDriver"), "Stored"), "413" to problem("Too large")) + errors),
                ))
        put("/api/v1/db/drivers/{id}", mapOf("parameters" to listOf(p("id")),
                    "delete" to op("summary" to "Remove an uploaded driver that no connection uses. Requires modules.admin; audited.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Removed")) + errors),
                ))
        put("/api/v1/db/driver-settings", mapOf(
                    "get" to op("summary" to "List the defaults a connection of each driver kind (and each overridden uploaded driver) gets. Requires gateway.read.",
                        "responses" to mapOf("200" to json(ref("DriverSettingsList"), "Settings")) + errors),
                ))
        put("/api/v1/db/driver-settings/{name}", mapOf("parameters" to listOf(p("name")),
                    "put" to op("summary" to "Override fields of the defaults of a driver kind or an uploaded driver. Requires gateway.write.",
                        "parameters" to listOf(csrf, ifMatch),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("DriverSettingsWrite")))),
                        "responses" to mapOf("200" to json(ref("DriverSettings"), "Stored")) + errors),
                    "delete" to op("summary" to "Remove the override, so the built-in defaults apply again. Requires gateway.write.",
                        "parameters" to listOf(csrf, ifMatch),
                        "responses" to mapOf("204" to mapOf("description" to "Removed")) + errors),
                ))
        put("/api/v1/db/translators", mapOf(
                    "get" to op("summary" to "List the built-in and custom SQL dialect translators. Requires gateway.read.",
                        "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("SqlTranslator")))), "Translators")) + errors),
                ))
        put("/api/v1/db/translators/{name}", mapOf("parameters" to listOf(p("name")),
                    "put" to op("summary" to "Create or replace a custom translator (lower-case name). Requires gateway.write.",
                        "parameters" to listOf(csrf, ifMatch),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("SqlTranslatorWrite")))),
                        "responses" to mapOf("200" to json(ref("SqlTranslator"), "Stored")) + errors),
                    "delete" to op("summary" to "Delete a custom translator that no connection uses. Requires gateway.write.",
                        "parameters" to listOf(csrf, ifMatch),
                        "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors),
                ))
        put("/api/v1/db/queries", mapOf(
                    "get" to op("summary" to "List named queries. Requires gateway.read.",
                        "responses" to mapOf("200" to json(ref("NamedQueryList"), "Queries")) + errors),
                    "post" to op("summary" to "Create a named query. Requires gateway.write.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("NamedQueryWrite")))),
                        "responses" to mapOf("201" to json(ref("NamedQuery"), "Created")) + errors),
                ))
        put("/api/v1/db/queries/convert", mapOf(
                    "post" to op("summary" to "Convert an ad hoc script call or SQL text into a named query (DAT-035). Requires gateway.write. Saves only with create true.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                        "responses" to mapOf("200" to mapOf("description" to "Converted"), "201" to mapOf("description" to "Converted and created")) + errors),
                ))
        put("/api/v1/db/queries/{name}", mapOf("parameters" to listOf(p("name")),
                    "get" to op("summary" to "Get a named query. Requires gateway.read.",
                        "responses" to mapOf("200" to json(ref("NamedQuery"), "Query")) + errors),
                    "put" to op("summary" to "Update a named query. Requires gateway.write. Saving clears that query's cache.",
                        "parameters" to listOf(csrf, ifMatch),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("NamedQueryWrite")))),
                        "responses" to mapOf("200" to json(ref("NamedQuery"), "Updated")) + errors),
                    "delete" to op("summary" to "Delete a named query. Requires gateway.write.",
                        "parameters" to listOf(csrf, ifMatch),
                        "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors),
                ))
        put("/api/v1/db/queries/{name}/execute", mapOf("parameters" to listOf(p("name")),
                    "post" to op("summary" to "Run a named query. Authenticated callers must also hold one of the query's roles (an empty role list allows every authenticated caller).",
                        "description" to "Parameters are bound, not concatenated.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("NamedQueryRequest")))),
                        "responses" to mapOf(
                            "200" to json(mapOf("oneOf" to listOf(ref("QueryRows"), ref("QueryScalar"), ref("QueryUpdate"), ref("QueryProcedure"))), "Result"),
                            "503" to problem("Connection unavailable"),
                        ) + errors),
                ))
        put("/api/v1/db/queries/{name}/cache/clear", mapOf("parameters" to listOf(p("name")),
                    "post" to op("summary" to "Drop cached results for one named query. Requires gateway.write.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Cache cleared")) + errors),
                ))
    }

    private fun body(name: String) = mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref(name))))
    private val thumb = mapOf("name" to "thumbprint", "in" to "path", "required" to true,
        "schema" to mapOf("type" to "string", "pattern" to "^[0-9a-fA-F]{40}$"), "description" to "SHA-1 certificate thumbprint, hex")

    /** OPC UA client-certificate trust and Modbus address-range generation (C-7). */
    private fun c7Paths(): Map<String, Any?> = buildMap {
        put("/api/v1/opcua/certificates", mapOf(
            "get" to op("summary" to "Trusted and quarantined OPC UA client certificates. Requires gateway.read.",
                "description" to "A client that opens a secured endpoint with an untrusted certificate is refused and its certificate is quarantined.",
                "responses" to mapOf("200" to json(ref("OpcUaTrustList"), "Trust list")) + errors)))
        put("/api/v1/opcua/certificates/trusted", mapOf(
            "post" to op("summary" to "Import a client certificate into the trust list. Requires gateway.write. Audited.",
                "description" to "Send PEM text or base64 DER in certificate. A private key is refused.",
                "parameters" to listOf(csrf),
                "requestBody" to body("OpcUaCertificateImport"),
                "responses" to mapOf("201" to json(ref("OpcUaCertificate"), "Trusted")) + errors)))
        put("/api/v1/opcua/certificates/trusted/{thumbprint}", mapOf("parameters" to listOf(thumb),
            "delete" to op("summary" to "Remove a certificate from the trust list. Requires gateway.write. Audited.",
                "description" to "The client is refused on its next secure-channel attempt and is quarantined again.",
                "parameters" to listOf(csrf),
                "responses" to mapOf("204" to mapOf("description" to "Removed")) + errors)))
        put("/api/v1/opcua/certificates/quarantined/{thumbprint}", mapOf("parameters" to listOf(thumb),
            "delete" to op("summary" to "Delete a quarantined certificate without trusting it. Requires gateway.write. Audited.",
                "parameters" to listOf(csrf),
                "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors)))
        put("/api/v1/opcua/certificates/quarantined/{thumbprint}/trust", mapOf("parameters" to listOf(thumb),
            "post" to op("summary" to "Trust a quarantined certificate. Requires gateway.write. Audited.",
                "description" to "The next secure channel from that client is accepted without a restart.",
                "parameters" to listOf(csrf),
                "responses" to mapOf("200" to json(ref("OpcUaCertificate"), "Trusted")) + errors)))
        put("/api/v1/modbus/address-ranges/preview", mapOf(
            "post" to op("summary" to "Expand Modbus address ranges into named addresses without storing anything. Requires gateway.read.",
                "parameters" to listOf(csrf),
                "requestBody" to body("ModbusAddressMapRequest"),
                "responses" to mapOf("200" to json(ref("ModbusAddressMapResult"), "Generated items")) + errors)))
        put("/api/v1/modbus/address-ranges/tags", mapOf(
            "post" to op("summary" to "Create OPC tags for Modbus address ranges through the config store. Requires gateway.write.",
                "description" to "Tags are Tag resources in module tags, named <device>-<item>, written as one batch. An existing tag is a 409 unless overwrite is true.",
                "parameters" to listOf(csrf),
                "requestBody" to body("ModbusAddressMapRequest"),
                "responses" to mapOf("200" to json(ref("ModbusAddressMapResult"), "Counts and generated items")) + errors)))
    }

    private fun c7Schemas(): Map<String, Any?> = buildMap {
        val str = mapOf("type" to "string")
        put("OpcUaCertificate", mapOf("type" to "object", "required" to listOf("thumbprint", "subject", "notBefore", "notAfter", "expired"), "properties" to mapOf(
            "thumbprint" to str, "subject" to str, "commonName" to mapOf("type" to listOf("string", "null")), "issuer" to str,
            "applicationUri" to mapOf("type" to listOf("string", "null")),
            "notBefore" to mapOf("type" to "string", "format" to "date-time"), "notAfter" to mapOf("type" to "string", "format" to "date-time"),
            "expired" to mapOf("type" to "boolean"))))
        put("OpcUaTrustList", mapOf("type" to "object", "required" to listOf("trusted", "quarantined"), "properties" to mapOf(
            "serverEnabled" to mapOf("type" to "boolean"), "serverRunning" to mapOf("type" to "boolean"),
            "endpointUrl" to mapOf("type" to listOf("string", "null")),
            "trusted" to mapOf("type" to "array", "items" to ref("OpcUaCertificate")),
            "quarantined" to mapOf("type" to "array", "items" to ref("OpcUaCertificate")))))
        put("OpcUaCertificateImport", mapOf("type" to "object", "required" to listOf("certificate"), "properties" to mapOf(
            "certificate" to mapOf("type" to "string", "description" to "PEM, or base64 of the DER bytes"))))
        put("ModbusAddressRange", mapOf("type" to "object", "required" to listOf("prefix", "start", "end", "modbusType", "modbusAddress"), "properties" to mapOf(
            "prefix" to mapOf("type" to "string", "pattern" to "^[A-Za-z0-9_]{1,64}$"),
            "start" to mapOf("type" to "string", "description" to "Written in radix"),
            "end" to mapOf("type" to "string", "description" to "Written in radix; inclusive"),
            "radix" to mapOf("type" to "integer", "minimum" to 2, "maximum" to 36, "default" to 10),
            "step" to mapOf("type" to "boolean", "default" to false, "description" to "Advance by the type's register width"),
            "unitId" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 255),
            "modbusType" to mapOf("type" to "string", "description" to "Designator such as HR, HRUS, HRI, HRF, HRD, IR, C, DI"),
            "modbusAddress" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 65535))))
        put("ModbusAddressMapRequest", mapOf("type" to "object", "required" to listOf("device", "ranges"), "properties" to mapOf(
            "device" to str, "folder" to str, "provider" to str,
            "scanPeriodMillis" to mapOf("type" to "integer", "minimum" to 0),
            "overwrite" to mapOf("type" to "boolean", "default" to false),
            "ranges" to mapOf("type" to "array", "items" to ref("ModbusAddressRange")))))
        put("ModbusAddressMapResult", mapOf("type" to "object", "required" to listOf("count", "items"), "properties" to mapOf(
            "count" to mapOf("type" to "integer"), "created" to mapOf("type" to "integer"), "updated" to mapOf("type" to "integer"),
            "items" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                "name" to str, "resourceName" to str, "path" to str, "address" to str, "dataType" to str,
                "writable" to mapOf("type" to "boolean")))))))
    }

    private fun dbSchemas(): Map<String, Any?> = buildMap {
        put("DatabaseConnection", mapOf("type" to "object", "required" to listOf("name", "status", "passwordSet"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "driver" to mapOf("type" to "string", "enum" to listOf("postgres", "sqlite", "sqlserver", "azuresql", "mariadb", "mysql", "oracle", "db2", "snowflake", "custom")),
                    "translator" to mapOf("type" to "string"),
                    "driverId" to mapOf("type" to "string", "description" to "Uploaded JDBC driver (oracle, db2, snowflake, custom)."),
                    "url" to mapOf("type" to "string"),
                    "username" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"),
                    "defaultConnection" to mapOf("type" to "boolean"),
                    "passwordSet" to mapOf("type" to "boolean", "description" to "True when a secret reference is stored. The password is never returned."),
                    "status" to mapOf("type" to "string", "enum" to listOf("Valid", "Faulted", "Disabled")),
                    "error" to mapOf("type" to "string"),
                    "activeConnections" to mapOf("type" to "integer"),
                    "idleConnections" to mapOf("type" to "integer"),
                    "maxConnections" to mapOf("type" to "integer"),
                    "queriesPerSecond" to mapOf("type" to "integer"),
                    "activeQueries" to mapOf("type" to "integer"),
                    "failoverConnection" to mapOf("type" to "string"),
                    "failoverMode" to mapOf("type" to "string"),
                    "validationQuery" to mapOf("type" to "string"),
                    "allowBrowserWrites" to mapOf("type" to "boolean", "description" to "The query browser may run statements that change data."),
                    "version" to mapOf("type" to "integer"))))
        put("DatabaseConnectionWrite", mapOf("type" to "object", "required" to listOf("name", "driver"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "driver" to mapOf("type" to "string", "enum" to listOf("postgres", "sqlite", "sqlserver", "azuresql", "mariadb", "mysql", "oracle", "db2", "snowflake", "custom")),
                    "url" to mapOf("type" to "string", "description" to "Required unless the driver's settings have a url template and host, port and database are given."),
                    "host" to mapOf("type" to "string", "description" to "Fills the driver's url template. Not stored."),
                    "port" to mapOf("type" to "integer", "description" to "Fills the url template; the driver's default port when omitted."),
                    "database" to mapOf("type" to "string", "description" to "Fills the url template. Not stored."),
                    "driverId" to mapOf("type" to "string", "description" to "Uploaded JDBC driver; required for oracle, db2, snowflake and custom unless the driver settings name one."),
                    "translator" to mapOf("type" to "string", "description" to "Built-in (ORACLE, DB2, SNOWFLAKE, ...) or a SqlTranslator resource name. Defaults from the driver settings."),
                    "username" to mapOf("type" to "string"),
                    "password" to mapOf("type" to "string", "description" to "Write-only. Stored as a secret reference."),
                    "description" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"),
                    "defaultConnection" to mapOf("type" to "boolean"),
                    "failoverConnection" to mapOf("type" to "string"),
                    "failoverMode" to mapOf("type" to "string", "enum" to listOf("standard", "sticky")),
                    "validationQuery" to mapOf("type" to "string"),
                    "maxActive" to mapOf("type" to "integer"),
                    "maxWaitMillis" to mapOf("type" to "integer"),
                    "allowBrowserWrites" to mapOf("type" to "boolean", "default" to false))))
        put("JdbcDriver", mapOf("type" to "object", "required" to listOf("id", "driverClass", "sha256"), "properties" to mapOf(
                    "id" to mapOf("type" to "string"),
                    "driverClass" to mapOf("type" to "string"),
                    "driverClasses" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "size" to mapOf("type" to "integer"),
                    "sha256" to mapOf("type" to "string"),
                    "uploadedAt" to mapOf("type" to "string", "format" to "date-time"),
                    "uploadedBy" to mapOf("type" to "string"),
                    "usedBy" to mapOf("type" to "array", "items" to mapOf("type" to "string")))))
        put("JdbcDriverList", mapOf("type" to "object", "properties" to mapOf(
                    "items" to mapOf("type" to "array", "items" to ref("JdbcDriver")),
                    "maxBytes" to mapOf("type" to "integer"))))
        val settingsFields = mapOf(
                    "urlTemplate" to mapOf("type" to "string", "description" to "jdbc: URL with {host}, {port} and {database}."),
                    "defaultPort" to mapOf("type" to "integer"),
                    "translator" to mapOf("type" to "string"),
                    "validationQuery" to mapOf("type" to "string"),
                    "driverId" to mapOf("type" to "string"),
                    "extraProperties" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string")))
        put("DriverSettingsWrite", mapOf("type" to "object", "properties" to settingsFields))
        put("DriverSettings", mapOf("type" to "object", "required" to listOf("name"), "properties" to
                    mapOf("name" to mapOf("type" to "string"), "builtIn" to mapOf("type" to "boolean"), "overridden" to mapOf("type" to "boolean")) + settingsFields))
        put("DriverSettingsList", mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("DriverSettings")))))
        val translatorFields = mapOf(
                    "quoteOpen" to mapOf("type" to "string"), "quoteClose" to mapOf("type" to "string"),
                    "pageTemplate" to mapOf("type" to "string", "description" to "Uses {sql}, {limit}, {offset}."),
                    "limitTemplate" to mapOf("type" to "string", "description" to "Uses {sql}, {limit}."),
                    "currentTimestamp" to mapOf("type" to "string"), "validationQuery" to mapOf("type" to "string"),
                    "createTableTemplate" to mapOf("type" to "string", "description" to "Uses {table}, {columns}."),
                    "columnTypes" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string")),
                    "trueLiteral" to mapOf("type" to "string"), "falseLiteral" to mapOf("type" to "string"))
        put("SqlTranslatorWrite", mapOf("type" to "object", "required" to listOf("pageTemplate", "limitTemplate", "currentTimestamp", "columnTypes"), "properties" to translatorFields))
        put("SqlTranslator", mapOf("type" to "object", "required" to listOf("name"), "properties" to
                    mapOf("name" to mapOf("type" to "string"), "builtIn" to mapOf("type" to "boolean")) + translatorFields))
        put("DatabaseConnectionList", mapOf("type" to "object", "properties" to mapOf(
                    "items" to mapOf("type" to "array", "items" to ref("DatabaseConnection")))))
        put("NamedQuery", mapOf("type" to "object", "required" to listOf("name", "connection", "queryType"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"),
                    "connection" to mapOf("type" to "string"),
                    "queryType" to mapOf("type" to "string", "enum" to listOf("query", "update", "scalar", "procedure")),
                    "sql" to mapOf("type" to "string", "description" to "Required unless queryType is procedure."),
                    "procedure" to mapOf("type" to "string", "description" to "Stored procedure or function name (queryType procedure)."),
                    "routine" to mapOf("type" to "string", "enum" to listOf("procedure", "function")),
                    "returnType" to mapOf("type" to "string", "description" to "Function return value type. Not allowed with out parameters."),
                    "parameters" to mapOf("type" to "array", "items" to ref("NamedQueryParameter")),
                    "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "cacheEnabled" to mapOf("type" to "boolean"),
                    "cacheTtlMillis" to mapOf("type" to "integer"),
                    "fallback" to mapOf("description" to "Scalar queries only. Returned on a database error, not on a security rejection."),
                    "maxRows" to mapOf("type" to "integer"),
                    "version" to mapOf("type" to "integer"))))
        put("NamedQueryParameter", mapOf("type" to "object", "required" to listOf("name", "dataType"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "dataType" to mapOf("type" to "string", "enum" to listOf("string", "integer", "long", "float", "double", "boolean", "datetime")),
                    "mode" to mapOf("type" to "string", "enum" to listOf("in", "out", "inout"), "description" to "Procedure queries only. Default in."),
                    "queryString" to mapOf("type" to "boolean", "description" to "When true, {name} in the SQL is replaced with an identifier. Otherwise :name is bound."))))
        put("NamedQueryWrite", mapOf("type" to "object", "required" to listOf("name", "connection", "queryType"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"),
                    "connection" to mapOf("type" to "string"),
                    "queryType" to mapOf("type" to "string", "enum" to listOf("query", "update", "scalar", "procedure")),
                    "sql" to mapOf("type" to "string", "description" to "Required unless queryType is procedure."),
                    "procedure" to mapOf("type" to "string", "description" to "Stored procedure or function name (queryType procedure)."),
                    "routine" to mapOf("type" to "string", "enum" to listOf("procedure", "function")),
                    "returnType" to mapOf("type" to "string", "description" to "Function return value type. Not allowed with out parameters."),
                    "parameters" to mapOf("type" to "array", "items" to ref("NamedQueryParameter")),
                    "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "cacheEnabled" to mapOf("type" to "boolean"),
                    "cacheTtlMillis" to mapOf("type" to "integer"),
                    "fallback" to mapOf(),
                    "maxRows" to mapOf("type" to "integer"))))
        put("NamedQueryList", mapOf("type" to "object", "properties" to mapOf(
                    "items" to mapOf("type" to "array", "items" to ref("NamedQuery")))))
        put("NamedQueryRequest", mapOf("type" to "object", "properties" to mapOf(
                    "parameters" to mapOf("type" to "object", "additionalProperties" to true),
                    "connection" to mapOf("type" to "string", "description" to "Used when the query connection is <parameter>."))))
        put("QueryRows", mapOf("type" to "object", "required" to listOf("type", "columns", "rows"), "properties" to mapOf(
                    "type" to mapOf("const" to "query"),
                    "columns" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "rows" to mapOf("type" to "array", "items" to mapOf("type" to "array")),
                    "cached" to mapOf("type" to "boolean"),
                    "truncated" to mapOf("type" to "boolean"))))
        put("QueryScalar", mapOf("type" to "object", "required" to listOf("type", "value"), "properties" to mapOf(
                    "type" to mapOf("const" to "scalar"),
                    "value" to mapOf(),
                    "cached" to mapOf("type" to "boolean"),
                    "fallback" to mapOf("type" to "boolean"))))
        put("QueryUpdate", mapOf("type" to "object", "required" to listOf("type", "rowsAffected"), "properties" to mapOf(
                    "type" to mapOf("const" to "update"),
                    "rowsAffected" to mapOf("type" to "integer"))))
        put("QueryProcedure", mapOf("type" to "object", "required" to listOf("type", "outputs"), "properties" to mapOf(
                    "type" to mapOf("const" to "procedure"),
                    "returnValue" to mapOf("description" to "Function return value, or null."),
                    "outputs" to mapOf("type" to "object", "additionalProperties" to true, "description" to "Out and in/out parameter values by name."),
                    "columns" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "rows" to mapOf("type" to "array", "items" to mapOf("type" to "array")),
                    "truncated" to mapOf("type" to "boolean"))))
        put("DatabaseBrowseRequest", mapOf("type" to "object", "required" to listOf("sql"), "additionalProperties" to false, "properties" to mapOf(
                    "sql" to mapOf("type" to "string", "maxLength" to 100_000),
                    "maxRows" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10_000, "default" to 500))))
    }

    val document: Map<String, Any?> by lazy {
        val gw = "/api/v1/config/{module}/gateway/{kind}"; val pj = "/api/v1/config/{module}/project/{project}/{kind}"
        mapOf<String, Any?>(
            "openapi" to "3.1.0",
            "info" to mapOf("title" to "ERIC Gateway API", "version" to Gateway.VERSION,
                "description" to "Browser clients send the oip_session cookie and X-CSRF-Token on POST, PUT, PATCH, and DELETE. Machine clients send Authorization: Bearer and do not send a CSRF token. GET /api/v1/health is unauthenticated. The session cookie is Secure when HTTPS or a trusted proxy is configured. Every /api route has an access rule; one without a rule is refused for every caller (403). An unknown /api path needs credentials (401) and is then 404.",
                "license" to mapOf("name" to "Apache-2.0", "identifier" to "Apache-2.0")),
            "security" to listOf(mapOf("sessionCookie" to emptyList<String>()), mapOf("bearerAuth" to emptyList<String>())),
            "paths" to linkedMapOf(
                "/api/v1/health" to mapOf("get" to op(
                    "summary" to "Gateway health",
                    "description" to "Public. When http.forceHttpsRedirect is false (the default), this returns 200 on the HTTP port and on the HTTPS port. When the redirect is on, every plain-HTTP request, including this one, returns 308 to the HTTPS URL. The container health check uses HTTP and leaves the redirect off.",
                    "responses" to mapOf(
                        "200" to json(ref("Health"), "Healthy"),
                        "308" to mapOf("description" to "Redirect to HTTPS when http.forceHttpsRedirect is true"),
                    ), public = true)),
                "/api/v1/https" to mapOf("get" to op(
                    "summary" to "HTTPS listener and certificate details",
                    "description" to "Requires gateway.read. The private key is not included.",
                    "responses" to mapOf("200" to json(ref("HttpsStatus"), "HTTPS status")) + errors)),
                "/api/v1/gateway-network" to mapOf("get" to op(
                    "summary" to "Gateway network status, certificates, and connections",
                    "description" to "Requires gateway.read. Incoming connections stay pending until approved. The private key is not included.",
                    "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Gateway network status")) + errors)),
                "/api/v1/gateway-network/outgoing" to mapOf("post" to op(
                    "summary" to "Create an outgoing gateway connection",
                    "description" to "Requires gateway.write. Host and port identify the remote peer network listener.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("OutgoingConnectionRequest")))),
                    "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Connection saved")) + errors)),
                "/api/v1/gateway-network/outgoing/{name}/approve" to mapOf("post" to op(
                    "summary" to "Approve the remote certificate for an outgoing connection",
                    "parameters" to listOf(p("name"), csrf),
                    "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Certificate approved")) + errors)),
                "/api/v1/gateway-network/outgoing/{name}/deny" to mapOf("post" to op(
                    "summary" to "Deny the remote certificate for an outgoing connection",
                    "parameters" to listOf(p("name"), csrf),
                    "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Certificate denied")) + errors)),
                "/api/v1/gateway-network/incoming/{fingerprint}/approve" to mapOf("post" to op(
                    "summary" to "Approve a pending incoming gateway certificate",
                    "parameters" to listOf(p("fingerprint"), csrf),
                    "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Incoming connection approved")) + errors)),
                "/api/v1/gateway-network/incoming/{fingerprint}/deny" to mapOf("post" to op(
                    "summary" to "Deny or revoke an incoming gateway certificate",
                    "parameters" to listOf(p("fingerprint"), csrf),
                    "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Incoming connection denied")) + errors)),
                "/api/v1/gateway-network/policies" to mapOf(
                    "put" to op(
                        "summary" to "Allow or deny a remote service for a peer",
                        "description" to "Requires gateway.write.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("ServicePolicyRequest")))),
                        "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Policy saved")) + errors),
                    "delete" to op(
                        "summary" to "Remove a service policy",
                        "parameters" to listOf(
                            mapOf("name" to "peer", "in" to "query", "required" to true, "schema" to mapOf("type" to "string")),
                            mapOf("name" to "service", "in" to "query", "required" to true, "schema" to mapOf("type" to "string")),
                            csrf,
                        ),
                        "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Policy removed")) + errors),
                ),
                "/api/v1/gateway-network/providers" to mapOf("post" to op(
                    "summary" to "Add a remote tag provider",
                    "description" to "Requires gateway.write. The provider reads and subscribes to tags on the named outgoing connection.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("RemoteProviderRequest")))),
                    "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Provider saved")) + errors)),
                "/api/v1/gateway-network/certificate/regenerate" to mapOf("post" to op(
                    "summary" to "Regenerate the local gateway-network certificate",
                    "description" to "Requires gateway.write. Peers must approve the new certificate. The private key is not returned.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(ref("GatewayNetworkStatus"), "Certificate regenerated")) + errors)),
                "/api/v1/architectures" to mapOf("get" to op(
                    "summary" to "Reference architectures evaluated against this gateway",
                    "description" to "Requires gateway.read. Each row is a check of this gateway's profile, peers, redundancy, edge sync, zones, and service policies.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Architecture catalog")) + errors)),
                "/api/v1/connections" to mapOf("get" to op(
                    "summary" to "Device, OPC, database and service connections with live status",
                    "description" to "Requires gateway.read. Status is read from the driver, pool or link that tracks it; nothing is probed. Each item has kind, type, name, status, health (ok, pending, fault, disabled) and the admin section that edits it.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Connections list")) + errors)),
                "/api/v1/diagnostics/environment" to mapOf("get" to op(
                    "summary" to "Runtime, operating system, profile and data directory of this gateway",
                    "description" to "Requires gateway.read. No environment variable values or secrets.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Environment")) + errors)),
                "/api/v1/designer/sessions" to mapOf(
                    "get" to op(
                        "summary" to "Active Designer sessions with the Designer launch link and shortcut download",
                        "description" to "Requires gateway.read. A session drops out when its heartbeat stops for ttlSeconds.",
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Designer sessions")) + errors),
                    "post" to op(
                        "summary" to "Register a Designer session",
                        "description" to "Requires gateway.write and designer.login. Returns the session id and the heartbeat period.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("201" to json(mapOf("type" to "object"), "Session registered")) + errors)),
                "/api/v1/designer/sessions/{id}" to mapOf(
                    "put" to op(
                        "summary" to "Keep a Designer session alive",
                        "description" to "Requires gateway.write and designer.login. Only the user who opened the session may send it.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Heartbeat recorded")) + errors),
                    "delete" to op(
                        "summary" to "End a Designer session",
                        "description" to "Requires gateway.write and designer.login. Only the user who opened the session may end it.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Session ended")) + errors)),
                "/api/v1/designer/launcher" to mapOf("get" to op(
                    "summary" to "Download an internet shortcut to the Designer",
                    "description" to "Requires gateway.write. The shortcut points at the address the request used.",
                    "responses" to mapOf("200" to mapOf("description" to "Shortcut file")) + errors)),
                "/api/v1/designer/projects/{project}/access" to mapOf("get" to op(
                    "summary" to "What the caller may do with a project in the Designer",
                    "description" to "Requires gateway.read. allowed and requiredRoles for view, save, delete and protect; breakLocks; the protected resources and the live resource locks (SEC-020).",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Project access")) + errors)),
                "/api/v1/designer/projects/{project}/locks" to mapOf(
                    "get" to op(
                        "summary" to "Live resource locks of a project",
                        "description" to "Requires gateway.read. A lock lasts while its Designer session is live.",
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Locks")) + errors),
                    "post" to op(
                        "summary" to "Take a resource lock for one of the caller's Designer sessions",
                        "description" to "Requires gateway.write, designer.login and the project's save roles (and protect roles for a protected resource). Body {module, kind, name, session}. 201 taken, 200 already held by this session, 423 held by another Designer.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf(
                            "201" to json(mapOf("type" to "object"), "Lock taken"),
                            "200" to json(mapOf("type" to "object"), "Lock already held by this session"),
                            "423" to problem("Held by another Designer"),
                        ) + errors)),
                "/api/v1/designer/projects/{project}/locks/{module}/{kind}/{name}" to mapOf("delete" to op(
                    "summary" to "Release a resource lock, or break another user's",
                    "description" to "Requires gateway.write. The holder releases with designer.login; ?break=true needs users.admin and is audited.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("204" to mapOf("description" to "Lock released")) + errors)),
                "/api/v1/designer/projects/{project}/protected/{module}/{kind}/{name}" to mapOf(
                    "put" to op(
                        "summary" to "Protect a project resource",
                        "description" to "Requires gateway.write, designer.login and the project's protect roles. A protected resource is written only by holders of the protect roles.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Protected")) + errors),
                    "delete" to op(
                        "summary" to "Remove a project resource's protection",
                        "description" to "Requires gateway.write, designer.login and the project's protect roles.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Unprotected")) + errors)),
                "/api/v1/email/profiles" to mapOf("get" to op(
                    "summary" to "SMTP email profiles shared by alarms, reports and scripts",
                    "description" to "Requires gateway.read. A profile reports passwordSet, clientSecretSet and refreshTokenSet, never a secret.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Email profiles")) + errors)),
                "/api/v1/email/profiles/{name}" to mapOf(
                    "get" to op(
                        "summary" to "One SMTP email profile",
                        "description" to "Requires gateway.read.",
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Email profile")) + errors),
                    "put" to op(
                        "summary" to "Create or replace an SMTP email profile",
                        "description" to "Requires gateway.write. password, oauthClientSecret and oauthRefreshToken are stored in the secret store and kept as references; a missing field keeps the stored secret, an empty string clears it.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Profile saved"), "201" to json(mapOf("type" to "object"), "Profile created")) + errors),
                    "delete" to op(
                        "summary" to "Delete an SMTP email profile and the secrets stored for it",
                        "description" to "Requires gateway.write.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Profile deleted")) + errors)),
                "/api/v1/email/profiles/{name}/test" to mapOf("post" to op(
                    "summary" to "Send a test message through a profile",
                    "description" to "Requires gateway.write. Body: {to}. A failed send is 502 with a message that holds no secret.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Test message sent")) + errors)),
                "/api/v1/security/oauth2-clients" to mapOf("get" to op(
                    "summary" to "OAuth2 client registrations used by outbound services",
                    "description" to "Requires gateway.read. Each reports clientSecretSet, refreshTokenSet, its redirectUri, the email profiles that use it and when its cached token expires; never a secret or token (SEC-029).",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "OAuth2 clients")) + errors)),
                "/api/v1/security/oauth2-clients/{name}" to mapOf(
                    "get" to op(
                        "summary" to "One OAuth2 client registration",
                        "description" to "Requires gateway.read.",
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "OAuth2 client")) + errors),
                    "put" to op(
                        "summary" to "Create or replace an OAuth2 client registration",
                        "description" to "Requires gateway.write. clientSecret and refreshToken are plain text stored in the secret store; clientSecretRef names a stored secret instead. A missing secret field keeps the stored one; an empty string clears it.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Updated"), "201" to json(mapOf("type" to "object"), "Created")) + errors),
                    "delete" to op(
                        "summary" to "Delete an OAuth2 client registration",
                        "description" to "Requires gateway.write. 409 while an email profile uses it.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors)),
                "/api/v1/security/oauth2-clients/{name}/verify-token" to mapOf("post" to op(
                    "summary" to "Request a token once with a chosen grant",
                    "description" to "Requires gateway.write. Body {grantType, scope?, code?, redirectUri?, refreshToken?, parameters?}. Answers the token type, lifetime, scope and whether a refresh token came back, never a token; a refused request is 502.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Token response summary")) + errors)),
                "/api/v1/security/oauth2-clients/{name}/authorize" to mapOf("post" to op(
                    "summary" to "Start the authorization code flow",
                    "description" to "Requires gateway.write and an authorizationUrl. Body {scope?, parameters?}. Answers the authorizationUrl to open; the state expires after 10 minutes.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Authorization URL")) + errors)),
                "/api/v1/security/oauth2-clients/{name}/authorize/callback" to mapOf("get" to op(
                    "summary" to "Identity provider redirect after authorization",
                    "description" to "Public; accepted only with the single-use state of an authorization started on this gateway. Exchanges the code and stores the refresh token. Answers an HTML page.",
                    "responses" to mapOf("200" to mapOf("description" to "Authorized"), "400" to mapOf("description" to "Unknown state or refused by the provider")), public = true)),
                "/api/v1/redundancy" to mapOf("get" to op(
                    "summary" to "Redundancy role, activity, peer, and sync status",
                    "description" to "Requires gateway.read. Activity is undecided, cold, warm, or active.",
                    "responses" to mapOf("200" to json(ref("RedundancyStatus"), "Redundancy status")) + errors)),
                "/api/v1/redundancy/failover" to mapOf("post" to op(
                    "summary" to "Request a failover to the other node",
                    "description" to "Requires gateway.write. The node that is active asks its peer to take control.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(ref("RedundancyStatus"), "Failover requested")) + errors)),
                "/api/v1/redundancy/assume" to mapOf("post" to op(
                    "summary" to "Master assumes control",
                    "description" to "Requires gateway.write. Used when recovery mode is manual and the backup is active.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(ref("RedundancyStatus"), "Master active")) + errors)),
                "/api/v1/redundancy/resync" to mapOf("post" to op(
                    "summary" to "Force a full configuration sync from the master",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(ref("RedundancyStatus"), "Full sync requested")) + errors)),
                "/api/v1/edge-sync" to mapOf("get" to op(
                    "summary" to "Edge sync status",
                    "description" to "Requires gateway.read. History, audit, and alarm journal forwarding over the peer network.",
                    "responses" to mapOf("200" to json(ref("EdgeSyncStatus"), "Edge sync status")) + errors)),
                "/api/v1/edge-sync/history" to mapOf("get" to op(
                    "summary" to "Local history buffer and hub copies",
                    "description" to "Requires gateway.read.",
                    "parameters" to listOf(q("provider"), q("source"), qLimit()),
                    "responses" to mapOf("200" to json(ref("EdgeSyncHistory"), "History rows")) + errors)),
                "/api/v1/edge-sync/audit" to mapOf("get" to op(
                    "summary" to "Audit copies stored by this gateway",
                    "description" to "Requires gateway.read. These rows are not part of the local audit chain.",
                    "parameters" to listOf(q("profile"), q("source"), qLimit()),
                    "responses" to mapOf("200" to json(ref("EdgeSyncAudit"), "Audit copies")) + errors)),
                "/api/v1/edge-sync/alarms" to mapOf("get" to op(
                    "summary" to "Alarm journal copies stored by this gateway",
                    "description" to "Requires gateway.read. These rows are not part of the local alarm journal.",
                    "parameters" to listOf(q("journal"), q("source"), qLimit()),
                    "responses" to mapOf("200" to json(ref("EdgeSyncAlarms"), "Alarm journal copies")) + errors)),
                "/api/v1/edge-sync/sync" to mapOf("post" to op(
                    "summary" to "Run one edge sync pass",
                    "description" to "Requires gateway.write. Sends at most one batch per enabled stream and still honors the schedule and the link.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(ref("EdgeSyncStatus"), "One sync pass")) + errors)),
                "/api/v1/eam" to mapOf("get" to op(
                    "summary" to "Enterprise administration status",
                    "description" to "Requires gateway.read. This gateway's role (none, controller, agent) and the agent or controller summary.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Status")) + errors)),
                "/api/v1/eam/settings" to mapOf("put" to op(
                    "summary" to "Set the enterprise administration role and settings",
                    "description" to "Requires gateway.write. Role, controller connection, backup controller connection, check-in period, auto-approve, kept backups, thresholds.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Set the enterprise administration role and settings")) + errors)),
                "/api/v1/eam/check-in" to mapOf("post" to op(
                    "summary" to "Agent checks in now",
                    "description" to "Requires gateway.write. Agent role only.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Agent checks in now")) + errors)),
                "/api/v1/eam/agents" to mapOf("get" to op(
                    "summary" to "Agents and certificates waiting for approval",
                    "description" to "Requires gateway.read. Status, group, maintenance, last check-in, inventory, and threshold state per agent.",
                    "parameters" to emptyList<Any>(),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Agents and certificates waiting for approval")) + errors)),
                "/api/v1/eam/agents/{name}" to mapOf(
                    "put" to op("summary" to "Change an agent's group, maintenance, or description", "parameters" to listOf(csrf),
                        "description" to "Requires gateway.write.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Agent")) + errors),
                    "delete" to op("summary" to "Remove an agent", "parameters" to listOf(csrf),
                        "description" to "Requires gateway.write. Queued runs for the agent are cancelled.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Removed")) + errors)),
                "/api/v1/eam/agents/{name}/approve" to mapOf("post" to op(
                    "summary" to "Approve an agent",
                    "description" to "Requires gateway.write.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Approve an agent")) + errors)),
                "/api/v1/eam/agents/{name}/recover" to mapOf("post" to op(
                    "summary" to "Recover an agent onto its replacement gateway",
                    "description" to "Requires gateway.write and backups.admin. Binds the waiting certificate and queues a restore of the latest stored backup.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Recover an agent onto its replacement gateway")) + errors)),
                "/api/v1/eam/agents/{name}/backups" to mapOf("get" to op(
                    "summary" to "Stored agent backups",
                    "description" to "Requires gateway.read. Encrypted archives the controller keeps for the agent.",
                    "parameters" to emptyList<Any>(),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Stored agent backups")) + errors)),
                "/api/v1/eam/groups" to mapOf("get" to op(
                    "summary" to "Agent groups",
                    "description" to "Requires gateway.read.",
                    "parameters" to emptyList<Any>(),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Agent groups")) + errors)),
                "/api/v1/eam/inventory" to mapOf("get" to op(
                    "summary" to "Agent inventory",
                    "description" to "Requires gateway.read. format=csv returns text/csv.",
                    "parameters" to listOf(q("format")),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Agent inventory")) + errors)),
                "/api/v1/eam/events" to mapOf("get" to op(
                    "summary" to "Agent events",
                    "description" to "Requires gateway.read. Enrollment, approval, task failures, threshold changes.",
                    "parameters" to listOf(q("agent"), qLimit()),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Agent events")) + errors)),
                "/api/v1/eam/tasks" to mapOf(
                    "get" to op("summary" to "Task definitions", "description" to "Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Tasks")) + errors),
                    "post" to op("summary" to "Create a task", "parameters" to listOf(csrf),
                        "description" to "Requires gateway.write; backup and restore also need backups.admin, module-install and upgrade modules.admin. Without a schedule it runs at once.",
                        "responses" to mapOf("201" to json(mapOf("type" to "object"), "Task and runs")) + errors)),
                "/api/v1/eam/tasks/{id}" to mapOf("delete" to op("summary" to "Delete a task", "parameters" to listOf(csrf),
                    "description" to "Requires gateway.write.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Removed")) + errors)),
                "/api/v1/eam/tasks/{id}/run" to mapOf("post" to op(
                    "summary" to "Run a task now",
                    "description" to "Requires gateway.write.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Run a task now")) + errors)),
                "/api/v1/eam/runs" to mapOf("get" to op(
                    "summary" to "Task runs",
                    "description" to "Requires gateway.read.",
                    "parameters" to listOf(q("agent"), q("task"), qLimit()),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Task runs")) + errors)),
                "/api/v1/eam/runs/{id}/retry" to mapOf("post" to op(
                    "summary" to "Retry a failed run",
                    "description" to "Requires gateway.write.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Retry a failed run")) + errors)),
                "/api/v1/eam/runs/{id}/cancel" to mapOf("post" to op(
                    "summary" to "Cancel a queued run",
                    "description" to "Requires gateway.write.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Cancel a queued run")) + errors)),
                "/api/v1/eam/payloads" to mapOf(
                    "get" to op("summary" to "Uploaded packages", "description" to "Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Packages")) + errors),
                    "post" to op("summary" to "Upload a module or upgrade package", "parameters" to listOf(csrf, q("label")),
                        "description" to "Requires gateway.write and modules.admin. Body is the package bytes.",
                        "responses" to mapOf("201" to json(mapOf("type" to "object"), "Package id, size and SHA-256")) + errors)),
                "/api/v1/https/certificate" to mapOf("put" to op(
                    "summary" to "Replace the HTTPS certificate",
                    "description" to "Requires gateway.write. Send a PEM chain plus an unencrypted private key, or a base64 PKCS#12 file and password. A mismatch returns 422 and leaves the live certificate in place. A valid upload is used on the next handshake, without a process restart, when the HTTPS listener is on. The private key is not returned.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf(
                        "oneOf" to listOf(ref("HttpsPemUpload"), ref("HttpsPkcs12Upload")))))),
                    "responses" to mapOf("200" to json(ref("HttpsStatus"), "Certificate replaced")) + errors)),
                "/api/v1/https/csr" to mapOf(
                    "get" to op(
                        "summary" to "The pending certificate signing request",
                        "description" to "Requires gateway.read. Add download=true for the PEM file. The request's private key stays on the gateway and is never returned.",
                        "parameters" to listOf(p("download", "query")),
                        "responses" to mapOf("200" to json(ref("HttpsCsr"), "Pending request")) + errors),
                    "post" to op(
                        "summary" to "Create a key pair and certificate signing request",
                        "description" to "Requires gateway.write; audited. Replaces any pending request. The key is written to https/pending.key (mode 0600) and is not returned.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("HttpsCsrRequest")))),
                        "responses" to mapOf("200" to json(ref("HttpsCsr"), "Request created")) + errors),
                    "delete" to op(
                        "summary" to "Discard the pending request and its key",
                        "description" to "Requires gateway.write; audited.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("200" to json(ref("HttpsStatus"), "Request discarded")) + errors)),
                "/api/v1/https/csr/install" to mapOf("post" to op(
                    "summary" to "Install the CA-signed reply for the pending request",
                    "description" to "Requires gateway.write; audited. certificatePem holds the leaf and any intermediates in any order. The leaf must belong to the pending key. The listener uses it on the next handshake.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf(
                        "type" to "object", "required" to listOf("certificatePem"),
                        "properties" to mapOf("certificatePem" to mapOf("type" to "string")))))),
                    "responses" to mapOf("200" to json(ref("HttpsStatus"), "Certificate installed")) + errors)),
                "/api/v1/https/tls" to mapOf("put" to op(
                    "summary" to "Set TLS protocols and cipher suites",
                    "description" to "Requires gateway.write; audited. Protocols are TLSv1.2 and/or TLSv1.3; older protocols and suites on the weak list are refused with 422. Omit cipherSuites for the JDK defaults minus the weak list. Applied to the running listener.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf(
                        "type" to "object", "required" to listOf("protocols"),
                        "properties" to mapOf(
                            "protocols" to mapOf("type" to "array", "items" to mapOf("enum" to listOf("TLSv1.2", "TLSv1.3"))),
                            "cipherSuites" to mapOf("type" to listOf("array", "null"), "items" to mapOf("type" to "string"))))))),
                    "responses" to mapOf("200" to json(ref("HttpsStatus"), "Policy applied")) + errors)),
                "/api/v1/https/settings" to mapOf("put" to op(
                    "summary" to "Save web-server ports, public address, and redirect",
                    "description" to "Requires gateway.write; audited. Written to gateway.yaml after the same validation the gateway applies at start; applied on the next start. Environment variables still override the file.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "httpPort" to mapOf("type" to "integer"),
                            "httpsEnabled" to mapOf("type" to "boolean"),
                            "httpsPort" to mapOf("type" to "integer"),
                            "publicAddress" to mapOf("type" to listOf("string", "null")),
                            "publicHttpsPort" to mapOf("type" to listOf("integer", "null")),
                            "forceHttpsRedirect" to mapOf("type" to "boolean")))))),
                    "responses" to mapOf("200" to json(ref("HttpsStatus"), "Settings saved")) + errors)),
                "/api/v1/alarms" to mapOf("get" to op(
                    "summary" to "Query live alarms (active, or cleared and still unacknowledged)",
                    "description" to "Requires tags.read. Shelved alarms are hidden until the shelf expires. A remote tag provider adds that peer's live alarms when the alarm service is readable: the source uses the local provider name, and the event id is prefixed with it. state=shelved instead lists this gateway's shelved alarms (one entry per alarm) with shelvedUntil, shelvedAt and shelvedBy; entries without a live event have a null eventId. Alarms whose source tag the caller may not read are left out.",
                    "parameters" to listOf(
                        mapOf("name" to "state", "in" to "query", "required" to false,
                            "schema" to mapOf("type" to "string", "enum" to listOf("live", "shelved"))),
                        mapOf("name" to "active", "in" to "query", "required" to false, "schema" to mapOf("type" to "boolean")),
                        mapOf("name" to "minPriority", "in" to "query", "required" to false, "schema" to mapOf("type" to "string")),
                        mapOf("name" to "source", "in" to "query", "required" to false, "schema" to mapOf("type" to "string"),
                            "description" to "Comma-separated source patterns. * is a wildcard."),
                        mapOf("name" to "displayPath", "in" to "query", "required" to false, "schema" to mapOf("type" to "string")),
                    ),
                    "responses" to mapOf("200" to json(mapOf("oneOf" to listOf(ref("AlarmStatus"), ref("ShelvedAlarmStatus"))),
                        "Live alarms, or shelved alarms with state=shelved")) + errors)),
                "/api/v1/alarms/ack" to mapOf("post" to op(
                    "summary" to "Acknowledge alarm events by id",
                    "description" to "Requires tags.write. An event id prefixed with a remote provider name is acknowledged on the gateway that owns the alarm.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("AlarmAck")))),
                    "responses" to mapOf("200" to json(ref("AlarmAckResult"), "Acknowledged")) + errors)),
                "/api/v1/alarms/shelve" to mapOf("post" to op(
                    "summary" to "Shelve alarms until durationSeconds elapses",
                    "description" to "Requires tags.write. A shelved alarm is hidden and does not open a new visible event until the shelf expires. A source prefixed with a remote provider name is shelved on the owning gateway when that peer allows it.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("AlarmShelve")))),
                    "responses" to mapOf("200" to json(ref("AlarmShelveResult"), "Shelved")) + errors)),
                "/api/v1/alarms/unshelve" to mapOf("post" to op(
                    "summary" to "Unshelve alarms and re-evaluate them",
                    "description" to "Requires tags.write.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("AlarmUnshelve")))),
                    "responses" to mapOf("200" to json(ref("AlarmUnshelveResult"), "Unshelved")) + errors)),
                "/api/v1/alarms/journal" to mapOf("get" to op(
                    "summary" to "Query the alarm journal",
                    "description" to "Requires tags.read. Newest first unless order=asc. start is inclusive and end is exclusive. source and displayPath take comma-separated case-sensitive patterns where * is a wildcard. eventType is a comma-separated list. Rows whose source tag the caller may not read are left out before paging, so total counts only rows the caller may see.",
                    "parameters" to listOf(
                        mapOf("name" to "start", "in" to "query", "required" to false, "schema" to mapOf("type" to "string", "format" to "date-time")),
                        mapOf("name" to "end", "in" to "query", "required" to false, "schema" to mapOf("type" to "string", "format" to "date-time")),
                        mapOf("name" to "source", "in" to "query", "required" to false, "schema" to mapOf("type" to "string")),
                        mapOf("name" to "displayPath", "in" to "query", "required" to false, "schema" to mapOf("type" to "string")),
                        mapOf("name" to "minPriority", "in" to "query", "required" to false, "schema" to mapOf("type" to "string"),
                            "description" to "Priority name or code 0-4"),
                        mapOf("name" to "maxPriority", "in" to "query", "required" to false, "schema" to mapOf("type" to "string")),
                        mapOf("name" to "eventType", "in" to "query", "required" to false, "schema" to mapOf("type" to "string"),
                            "description" to "active, clear, ack, shelve, unshelve (comma-separated)"),
                        mapOf("name" to "offset", "in" to "query", "required" to false, "schema" to mapOf("type" to "integer", "minimum" to 0)),
                        mapOf("name" to "limit", "in" to "query", "required" to false, "schema" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 1000)),
                        mapOf("name" to "order", "in" to "query", "required" to false, "schema" to mapOf("type" to "string", "enum" to listOf("asc", "desc"))),
                    ),
                    "responses" to mapOf("200" to json(ref("AlarmJournalPage"), "Journal rows")) + errors)),
                "/api/v1/alarms/journal/settings" to mapOf(
                    "get" to op(
                        "summary" to "Alarm journal retention",
                        "description" to "Requires tags.read. maxAgeDays null means rows are kept forever (pruning off).",
                        "responses" to mapOf("200" to json(ref("AlarmJournalSettings"), "Retention")) + errors),
                    "put" to op(
                        "summary" to "Set alarm journal retention",
                        "description" to "Requires gateway.write. Audited. A positive maxAgeDays deletes older rows hourly; null or 0 turns pruning off.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("AlarmJournalSettings")))),
                        "responses" to mapOf("200" to json(ref("AlarmJournalSettings"), "Retention")) + errors)),
                "/api/v1/alarms/journal/purge" to mapOf("post" to op(
                    "summary" to "Delete alarm journal rows older than a time",
                    "description" to "Requires gateway.write. Audited. Without olderThan, uses now minus the retention age (400 when retention is off).",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to false, "content" to mapOf("application/json" to mapOf("schema" to ref("AlarmJournalPurge")))),
                    "responses" to mapOf("200" to json(ref("AlarmJournalPurgeResult"), "Purged")) + errors)),
                "/api/v1/alarms/email-ack/{token}" to mapOf(
                    "get" to op(
                        "summary" to "Describe the alarm named by an email acknowledgement link",
                        "description" to "Public. The token in the two-way email is the credential. Accept text/html returns the landing page.",
                        "parameters" to listOf(p("token")),
                        "responses" to mapOf("200" to json(ref("EmailAck"), "Alarm described by the link")) + errors,
                        public = true,
                    ),
                    "post" to op(
                        "summary" to "Acknowledge the alarm named by an email acknowledgement link",
                        "description" to "Public. Optional notes. Notes are required when the alarm asks for them.",
                        "parameters" to listOf(p("token")),
                        "requestBody" to mapOf("required" to false, "content" to mapOf("application/json" to mapOf("schema" to ref("EmailAckSubmit")))),
                        "responses" to mapOf("200" to json(ref("EmailAck"), "Acknowledged")) + errors,
                        public = true,
                    ),
                ),
                "/api/v1/notifications/rosters/{name}/on-call" to mapOf("get" to op(
                    "summary" to "Users on a roster whose schedule is active",
                    "description" to "Requires gateway.read.",
                    "parameters" to listOf(p("name")),
                    "responses" to mapOf("200" to json(ref("OnCall"), "On-call users in roster order")) + errors)),
                "/api/v1/notifications/schedules/{name}" to mapOf("get" to op(
                    "summary" to "Whether a schedule is active at the gateway clock",
                    "description" to "Requires gateway.read. The built-in schedule 'always' is active.",
                    "parameters" to listOf(p("name")),
                    "responses" to mapOf("200" to json(ref("ScheduleState"), "Schedule state")) + errors)),
                "/api/v1/notifications/pipelines" to mapOf("get" to op(
                    "summary" to "Pipelines that currently have events in flight",
                    "description" to "Requires gateway.read.",
                    "responses" to mapOf("200" to json(ref("PipelineStatus"), "In-flight counts")) + errors)),
                "/api/v1/tags/read" to mapOf("post" to op(
                    "summary" to "Read tag values, quality, and timestamps",
                    "description" to "Requires tags.read. A tag whose read permissions do not admit the caller's roles and security zone returns a null value with quality Bad_AccessDenied.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("TagReadRequest")))),
                    "responses" to mapOf("200" to json(ref("TagReadResponse"), "Values in request order")) + errors)),
                "/api/v1/tags/diagnostics" to mapOf("get" to op(
                    "summary" to "Diagnostics for one tag",
                    "description" to "Requires tags.read. Current value, quality and timestamp, subscriber count, references in and out " +
                        "(expression, derived and reference tags, and view bindings), the tag group with its last execution, duration and overruns, " +
                        "and deadband counters (values evaluated versus published). A tag the caller may not read returns a null value and quality Bad_AccessDenied.",
                    "parameters" to listOf(mapOf("name" to "path", "in" to "query", "required" to true, "schema" to mapOf("type" to "string"),
                        "description" to "Tag path, for example [default]Plant/Level")),
                    "responses" to mapOf("200" to json(ref("TagDiagnostics"), "Tag diagnostics")) + errors)),
                "/api/v1/tags/udts" to mapOf("get" to op(
                    "summary" to "List UDT definitions with parameters resolved through inheritance",
                    "description" to "Requires tags.read. Used by the admin UDT instance wizard (TAG-024).",
                    "parameters" to listOf(p("provider", "query")),
                    "responses" to mapOf("200" to json(ref("UdtTypeList"), "UDT definitions")) + errors)),
                "/api/v1/tags/udt-instances" to mapOf("post" to op(
                    "summary" to "Create many instances of one UDT in one config batch",
                    "description" to "Requires tags.write. Names come from rows[].name or from pattern (every {n} becomes start + i * step, zero-padded to pad digits). " +
                        "Every row is validated first (name, path, parameters against the UDT's inherited parameter list, collisions, Tag schema). " +
                        "If any row or the request is invalid the response is 422 with the per-row plan and nothing is created. " +
                        "Otherwise all instances are written as one config batch. onCollision: fail (default) makes a collision a row error; " +
                        "skip leaves that row out; rename appends the first free _2, _3, ... suffix. dryRun returns the plan without writing.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("UdtInstanceRequest")))),
                    "responses" to mapOf(
                        "200" to json(ref("UdtInstancePlan"), "Dry run, or nothing to create (every row skipped)"),
                        "201" to json(ref("UdtInstancePlan"), "Instances created"),
                        "422" to mapOf("description" to "Invalid request or rows. Nothing was created. The body is problem+json with the plan members (rows, requestErrors).",
                            "content" to mapOf("application/problem+json" to mapOf("schema" to ref("Problem")))),
                    ) + errors.filterKeys { it != "422" })),
                "/api/v1/tags/export" to mapOf("get" to op(
                    "summary" to "Export tag configuration as JSON",
                    "description" to "Requires gateway.read. Returns {version, provider, udts, tags}, each entry {name, spec} sorted by resource name, so two exports of the same configuration are byte-identical. With folder, only that subtree and the UDT definitions its tags need.",
                    "parameters" to listOf(q("provider"), q("folder")),
                    "responses" to mapOf("200" to mapOf("description" to "Export document", "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object"))))) + errors)),
                "/api/v1/tags/import" to mapOf("post" to op(
                    "summary" to "Import tag configuration from JSON",
                    "description" to "Requires gateway.write. Applies an export document. policy decides what happens to an existing tag or UDT: overwrite (default) replaces it, merge applies the imported keys as a JSON merge patch, ignore keeps it; new resources are added under every policy. All or nothing: if any entry is invalid the response is 422 listing every problem in failed, and nothing is written. With provider, the document's provider must match.",
                    "parameters" to listOf(csrf, mapOf("name" to "policy", "in" to "query", "required" to false,
                        "schema" to mapOf("type" to "string", "enum" to listOf("overwrite", "merge", "ignore"), "default" to "overwrite")), q("provider")),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                    "responses" to mapOf(
                        "200" to mapOf("description" to "Imported: created, updated and skipped resource ids", "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                        "422" to mapOf("description" to "Invalid document or entries; nothing was written. failed lists each problem.", "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                    ) + errors.filterKeys { it != "422" })),
                "/api/v1/tags/write" to mapOf("post" to op(
                    "summary" to "Write tag values",
                    "description" to "Requires tags.write. Writes run in request order and each returns its own quality. A tag whose write permissions do not admit the caller's roles and security zone is left unchanged and returns Bad_AccessDenied; a read-only tag returns Bad_NotWritable. Every write, accepted or rejected, appends one 'tag write' audit record while the audit profile is enabled.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("TagWriteRequest")))),
                    "responses" to mapOf("200" to json(ref("TagWriteResponse"), "One result per write, in request order")) + errors)),
                "/api/v1/history/data" to mapOf("get" to op(
                    "summary" to "Raw tag history in a time range",
                    "description" to "Stored samples for one tag, oldest first, with no interpolation or aggregation. from and to are inclusive and accept epoch milliseconds or an ISO-8601 instant. Which tags are collected, and to which provider, is the historian/HistorySettings config resource. 503 when the provider cannot be read. A tag the caller may not read answers with quality Bad_AccessDenied and no samples.",
                    "parameters" to listOf(
                        p("tagPath", "query", required = true),
                        mapOf("name" to "from", "in" to "query", "required" to true, "schema" to mapOf("type" to "string"), "description" to "Start (inclusive): epoch milliseconds or ISO-8601 instant"),
                        mapOf("name" to "to", "in" to "query", "required" to true, "schema" to mapOf("type" to "string"), "description" to "End (inclusive), not before from"),
                        mapOf("name" to "provider", "in" to "query", "required" to false, "schema" to mapOf("type" to "string"),
                            "description" to "History provider to read: local or a historian/HistoryProvider name. Defaults to the tag's storageProvider."),
                        mapOf("name" to "limit", "in" to "query", "required" to false, "schema" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100000, "default" to 10000)),
                    ),
                    "responses" to mapOf("200" to json(ref("HistoryData"), "Samples in time order"), "503" to problem("History provider unreachable")) + errors)),
                "/api/v1/history/aggregate" to mapOf("get" to op(
                    "summary" to "Aggregated tag history over fixed intervals",
                    "description" to "The range [from, to] is cut into windows of interval starting at from (at most 10000 windows); each window gets one value. Modes: Average (time-weighted), SimpleAverage, Sum, Minimum, Maximum, Range, LastValue, Count. Numeric modes use Good-quality numeric rows; a window with nothing to aggregate is null. interpolation=None (default) uses only stored rows; Step holds each value until the next row and Linear draws a line between Good numeric rows: each window then reports startValue and endValue, Average with Linear integrates the line, Minimum/Maximum/Range include the edge values, and an empty window's LastValue is the value in effect at its end. A tag the caller may not read answers with quality Bad_AccessDenied and no windows.",
                    "parameters" to listOf(
                        p("tagPath", "query", required = true),
                        mapOf("name" to "from", "in" to "query", "required" to true, "schema" to mapOf("type" to "string"), "description" to "Start (inclusive): epoch milliseconds or ISO-8601 instant"),
                        mapOf("name" to "to", "in" to "query", "required" to true, "schema" to mapOf("type" to "string"), "description" to "End (inclusive), not before from"),
                        mapOf("name" to "provider", "in" to "query", "required" to false, "schema" to mapOf("type" to "string"),
                            "description" to "History provider to read: local or a historian/HistoryProvider name. Defaults to the tag's storageProvider."),
                        mapOf("name" to "interval", "in" to "query", "required" to true, "schema" to mapOf("type" to "string"),
                            "description" to "Window length: milliseconds or an ISO-8601 duration such as PT1M"),
                        mapOf("name" to "aggregate", "in" to "query", "required" to false,
                            "schema" to mapOf("type" to "string", "enum" to listOf("Average", "SimpleAverage", "Sum", "Minimum", "Maximum", "Range", "LastValue", "Count"), "default" to "Average"),
                            "description" to "Aggregation mode (case-insensitive)"),
                        mapOf("name" to "interpolation", "in" to "query", "required" to false,
                            "schema" to mapOf("type" to "string", "enum" to listOf("None", "Step", "Linear"), "default" to "None"),
                            "description" to "How values between rows are estimated (case-insensitive)"),
                    ),
                    "responses" to mapOf("200" to json(ref("HistoryAggregate"), "One value per window"), "503" to problem("History provider unreachable")) + errors)),
                "/api/v1/diagnostics/alarms/journal" to mapOf("get" to op(
                    "summary" to "Alarm journal write health",
                    "description" to "Requires gateway.read. queued (rows and state updates waiting, including a batch being retried), retrying, failedWriteAttempts, lostRows (rows that will never be stored: the queue was full, or the journal closed while a write was failing), lostStateUpdates, lastWriteError and lastWriteErrorAt. A failed write is retried with backoff, not dropped.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Journal status")) + errors)),
                "/api/v1/diagnostics/history/providers" to mapOf("get" to op(
                    "summary" to "History providers and store-and-forward status",
                    // Store rate: (doc source: FEATURES HIS-025)
                    "description" to "Requires gateway.read. The collector's counters (queued, dropped, consecutive write failures and the last error, samples diverted to quarantine after repeated failures) and, per provider: pending (buffered) and quarantined record counts, stored (datapoints written into the disk cache), storeRate (those datapoints per second over the last 10 seconds), connection state, last error, retry time. Store rate is the status page's Store Rate.",
                    "responses" to mapOf("200" to json(ref("HistoryProviders"), "Provider status")) + errors)),
                "/api/v1/diagnostics/history/quarantine" to mapOf("get" to op(
                    "summary" to "Quarantined history records",
                    "description" to "Requires gateway.read and tags.read (the records carry tag values; 403 without tags.read). Records a provider rejected permanently (for example a constraint or data error), with the error. They stay until retried or deleted.",
                    "parameters" to listOf(
                        mapOf("name" to "provider", "in" to "query", "required" to false, "schema" to mapOf("type" to "string")),
                        mapOf("name" to "limit", "in" to "query", "required" to false, "schema" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100000, "default" to 1000)),
                    ),
                    "responses" to mapOf("200" to json(ref("HistoryQuarantine"), "Quarantined records, oldest first")) + errors)),
                "/api/v1/diagnostics/history/quarantine/retry" to mapOf("post" to op(
                    "summary" to "Put quarantined records back on their provider's queue",
                    "description" to "Requires gateway.write. Selects by ids, by provider, or both. Records go to the tail of the queue.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("QuarantineSelection")))),
                    "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("retried" to mapOf("type" to "integer"))), "Count retried")) + errors)),
                "/api/v1/diagnostics/history/quarantine/delete" to mapOf("post" to op(
                    "summary" to "Delete quarantined records",
                    "description" to "Requires gateway.write. Selects by ids, by provider, or both.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("QuarantineSelection")))),
                    "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("deleted" to mapOf("type" to "integer"))), "Count deleted")) + errors)),
                "/api/v1/diagnostics/history/quarantine/export" to mapOf("get" to op(
                    "summary" to "Download quarantined history records",
                    "description" to "Requires gateway.write. Also requires tags.read, because each record carries a tag value (403 without it). format is json (default) or csv. The file is the quarantine list for the same provider and limit, oldest first, optionally narrowed with ids. Records the caller may not read are left out. Id 0 matches nothing.",
                    "parameters" to listOf(
                        mapOf("name" to "format", "in" to "query", "required" to false,
                            "schema" to mapOf("type" to "string", "enum" to listOf("json", "csv"), "default" to "json")),
                        mapOf("name" to "provider", "in" to "query", "required" to false, "schema" to mapOf("type" to "string")),
                        mapOf("name" to "limit", "in" to "query", "required" to false,
                            "schema" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100000, "default" to 1000)),
                        mapOf("name" to "ids", "in" to "query", "required" to false, "schema" to mapOf("type" to "string"),
                            "description" to "Comma-separated record ids. When set, the file contains only those listed records."),
                    ),
                    "responses" to mapOf("200" to mapOf(
                        "description" to "Quarantined records as a file",
                        "content" to mapOf(
                            "application/json" to mapOf("schema" to ref("HistoryQuarantine")),
                            "text/csv" to mapOf("schema" to mapOf("type" to "string")),
                        ),
                    )) + errors)),
                "/api/v1/reports/components" to mapOf("get" to op(
                    "summary" to "Report component catalog",
                    "description" to "Requires tags.read. The component types a report layout may hold (text, table, bar, timeSeries), each with its editable properties and the values a new one starts with, plus the page and header settings of the report object (page size, orientation, margins).",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Component catalog")) + errors)),
                "/api/v1/reports/preview" to mapOf("post" to op(
                    "summary" to "Run a draft report definition",
                    "description" to "Requires gateway.write (a draft may name any SQL or named query). Body {spec, parameters?}: spec is a reporting/Report spec that is checked like a save (422 with the messages when it would not save). Runs it as the caller with the same engine and permission checks as POST /api/v1/reports/{name}/run, without recording a run. Response: report result, contractVersion 1.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                    "responses" to mapOf(
                        "200" to json(ref("ReportResult"), "Report result"),
                        "502" to problem("A named query failed"),
                        "503" to problem("A data source is unavailable"),
                    ) + errors)),
                "/api/v1/reports/preview/pdf" to mapOf("post" to op(
                    "summary" to "Run a draft report definition and render it as PDF",
                    "description" to "Requires gateway.write. Like POST /api/v1/reports/preview, then renders the result with the draft's layout. A layout that cannot be rendered is refused with 422 before anything runs.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                    "responses" to mapOf(
                        "200" to mapOf("description" to "Rendered report", "content" to mapOf("application/pdf" to mapOf("schema" to mapOf("type" to "string", "contentMediaType" to "application/pdf")))),
                        "502" to problem("A named query failed"),
                        "503" to problem("A data source is unavailable"),
                    ) + errors)),
                QUARANTINE_IMPORT_PATH to mapOf("post" to op(
                    "summary" to "Import a quarantine export and retry its records",
                    // (doc source: FEATURES HIS-025)
                    "description" to "Requires gateway.write, the same as retry and delete. The body is a file from GET .../quarantine/export (JSON, the default, or CSV). Each record is placed on its provider's store-and-forward queue and a forward runs immediately. A malformed file or one over 16 MiB or 100000 records is rejected and writes nothing.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf(
                        "application/json" to mapOf("schema" to ref("HistoryQuarantine")),
                        "text/csv" to mapOf("schema" to mapOf("type" to "string")),
                    )),
                    "responses" to mapOf(
                        "200" to json(mapOf("type" to "object", "properties" to mapOf("retried" to mapOf("type" to "integer"))), "Count placed back on the queue"),
                        "413" to problem("The file is larger than 16 MiB or has more than 100000 records; nothing was imported"),
                    ) + errors)),
                DISK_CACHE_ARCHIVE_PATH to mapOf("post" to op(
                    "summary" to "Archive a provider's store-and-forward disk cache",
                    // (doc source: FEATURES HIS-025)
                    "description" to "Requires gateway.write. Locks that provider's pending disk cache, syncs it to a file under the gateway data directory, and only then removes those rows. Quarantine rows are not included. There is no record cap. The response names the archive; download it with GET .../cache/archives/{name}.",
                    "parameters" to listOf(
                        csrf,
                        mapOf("name" to "provider", "in" to "query", "required" to true, "schema" to mapOf("type" to "string")),
                    ),
                    "responses" to mapOf(
                        "200" to json(ref("DiskCacheArchiveInfo"), "The archive that was written"),
                    ) + errors)),
                DISK_CACHE_ARCHIVES_PATH to mapOf("get" to op(
                    "summary" to "List gateway-side disk-cache archives",
                    "description" to "Requires gateway.read. Each entry is name, provider, record count, bytes, and created time. Listing does not change the live cache.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "Archives, newest first")) + errors)),
                "/api/v1/diagnostics/history/cache/archives/{name}" to mapOf(
                    "parameters" to listOf(mapOf("name" to "name", "in" to "path", "required" to true, "schema" to mapOf("type" to "string"))),
                    "get" to op(
                        "summary" to "Download a gateway-side disk-cache archive",
                        "description" to "Requires gateway.read and tags.read. Returns the archive file. Does not change the live cache.",
                        "responses" to mapOf("200" to mapOf("description" to "Archive file, one JSON object per line")) + errors),
                    "delete" to op(
                        "summary" to "Delete a gateway-side disk-cache archive",
                        "description" to "Requires gateway.write. Deletes the file only. Pending cache rows are not changed.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors)),
                DISK_CACHE_LOAD_PATH to mapOf("post" to op(
                    "summary" to "Load a disk-cache archive back onto the store-and-forward queues",
                    // (doc source: FEATURES HIS-025)
                    "description" to "Requires gateway.write. Body {\"name\": \"...\"} loads a gateway-side archive in pages. Any other body is an uploaded archive file: it is validated completely, then written in one transaction. A provider that is not registered, or a malformed or oversized upload, is rejected and that request writes nothing. Records keep sourceId and seq.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf(
                        "application/json" to mapOf("schema" to ref("DiskCacheArchive")),
                    )),
                    "responses" to mapOf(
                        "200" to json(mapOf("type" to "object", "properties" to mapOf("loaded" to mapOf("type" to "integer"))), "Count appended to the disk cache"),
                        "413" to problem("The file is larger than 16 MiB or has more than 100000 records; nothing was loaded"),
                    ) + errors)),
                "/api/v1/reports/{name}/run" to mapOf("parameters" to listOf(p("name")), "post" to op(
                    "summary" to "Run a report now",
                    "description" to "Requires tags.read. The definition is the reporting/gateway/Report/{name} config resource (CRUD through /api/v1/config). Runs as the caller: each named query applies its role list, and each tag of a tag-history source is checked for the caller; unreadable tags are left out and listed in excludedTags (403 when none is readable). The run is recorded in run history. Response: report result, contractVersion 1 (docs/ops/report-contract.md).",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to false, "content" to mapOf("application/json" to mapOf("schema" to ref("ReportRunRequest")))),
                    "responses" to mapOf(
                        "200" to json(ref("ReportResult"), "Report result"),
                        "502" to problem("A named query failed"),
                        "503" to problem("A data source is unavailable"),
                    ) + errors)),
                "/api/v1/reports/{name}/pdf" to mapOf("parameters" to listOf(p("name")), "post" to op(
                    "summary" to "Run a report now and download it as PDF",
                    "description" to "Requires tags.read. Runs exactly like POST /api/v1/reports/{name}/run (as the caller, recorded in run history) and renders the result with the definition's layout (layoutVersion 1): tables, time-series and bar charts on A4 or Letter pages. A layout that cannot be rendered is refused with 422 before anything runs.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to false, "content" to mapOf("application/json" to mapOf("schema" to ref("ReportRunRequest")))),
                    "responses" to mapOf(
                        "200" to mapOf("description" to "Rendered report", "content" to mapOf("application/pdf" to mapOf("schema" to mapOf("type" to "string", "contentMediaType" to "application/pdf")))),
                        "502" to problem("A named query failed"),
                        "503" to problem("A data source is unavailable"),
                    ) + errors)),
                "/api/v1/reports/{name}/files" to mapOf("parameters" to listOf(p("name")), "get" to op(
                    "summary" to "PDFs of scheduled runs",
                    "description" to "Requires gateway.read: scheduled runs execute as the system user, so their output is not filtered for the reader. Each scheduled run is rendered to PDF and kept; the newest 50 per report are kept. Newest first.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "report" to mapOf("type" to "string"),
                            "file" to mapOf("type" to "string"),
                            "generatedAt" to mapOf("type" to "integer", "description" to "Epoch milliseconds"),
                            "size" to mapOf("type" to "integer", "description" to "Bytes"),
                        ),
                    )))), "Archived PDFs")) + errors)),
                "/api/v1/reports/{name}/files/{file}" to mapOf("parameters" to listOf(p("name"), p("file")), "get" to op(
                    "summary" to "Download a scheduled run's PDF",
                    "description" to "Requires gateway.read. file is a name from GET /api/v1/reports/{name}/files.",
                    "responses" to mapOf("200" to mapOf("description" to "Rendered report", "content" to mapOf("application/pdf" to mapOf("schema" to mapOf("type" to "string", "contentMediaType" to "application/pdf"))))) + errors)),
                "/api/v1/reports/{name}/runs" to mapOf("parameters" to listOf(p("name")), "get" to op(
                    "summary" to "Report run history",
                    "description" to "Requires tags.read. Newest first; the last 200 runs per report are kept.",
                    "parameters" to listOf(mapOf("name" to "limit", "in" to "query", "required" to false, "schema" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 200, "default" to 50))),
                    "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("ReportRun")))), "Runs")) + errors)),
                "/api/v1/reports/{name}/status" to mapOf("parameters" to listOf(p("name")), "get" to op(
                    "summary" to "Report schedule and last-run status",
                    "description" to "Requires tags.read.",
                    "responses" to mapOf("200" to json(ref("ReportStatus"), "Status")) + errors)),
                "/api/v1/reports/status" to mapOf("get" to op(
                    "summary" to "Status of every report",
                    "description" to "Requires tags.read. Sorted by report name.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("ReportStatus")))), "Statuses")) + errors)),
                // Tag browser behaviour: -
                "/api/v1/tags/browse" to mapOf("post" to op(
                    "summary" to "List one page of a tag folder's immediate children",
                    "description" to "Requires tags.read. path defaults to [default]. A tag whose read permissions do not admit the caller is listed with readable false and without data type, resource, value, quality and timestamp. more is true when another page follows; request it with offset + nodes.length. An unknown folder returns an empty page.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("TagBrowseRequest")))),
                    "responses" to mapOf("200" to json(ref("TagBrowseResponse"), "One page of child nodes")) + errors)),
                "/api/v1/hmi/projects/{name}/http/{source}" to mapOf("post" to op(
                    "summary" to "HTTP binding fetch: GET a path of a project's stored HTTP source and return the JSON",
                    "description" to "Requires tags.read and a project the caller may run. The gateway builds the address from the project's HttpSource resource (base URL and any credential headers stay on the gateway), sends one GET without following redirects, and returns {ok, status, body} or {ok:false, status, error}. 404 for an unknown project or source; 422 for a path that is not a single relative path.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object", "properties" to mapOf("path" to mapOf("type" to "string"), "query" to mapOf("type" to "object")))))),
                    "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("ok" to mapOf("type" to "boolean"), "status" to mapOf("type" to "integer"), "body" to mapOf<String, Any?>(), "error" to mapOf("type" to "string"))), "Fetch result")) + errors)),
                "/api/v1/tags/search" to mapOf("post" to op(
                    "summary" to "Tag report: search tags by path pattern, name, data type and quality",
                    "description" to "Requires tags.read. Optional filters path (glob), nameContains, dataType, quality (label or prefix) combine with AND; limit defaults to 500, at most 5000. Tags the caller may not read are never listed. truncated is true when a bound cut the walk or the limit was reached.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object", "properties" to mapOf("path" to mapOf("type" to "string"), "nameContains" to mapOf("type" to "string"), "dataType" to mapOf("type" to "string"), "quality" to mapOf("type" to "string"), "limit" to mapOf("type" to "integer")))))),
                    "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("columns" to mapOf("type" to "array", "items" to mapOf("type" to "string")), "rows" to mapOf("type" to "array", "items" to mapOf("type" to "object")), "truncated" to mapOf("type" to "boolean"))), "Matching tags")) + errors)),
                // C-16 admin tag browser. -
                TagDeviceApi.PROVIDERS to mapOf("get" to op(
                    "summary" to "List tag providers",
                    "description" to "Requires tags.read. The default provider comes first.",
                    "responses" to mapOf("200" to json(ref("TagProviderList"), "Providers")) + errors)),
                TagDeviceApi.DEVICES to mapOf("get" to op(
                    "summary" to "List Modbus devices and OPC UA connections a tag can be bound to",
                    "description" to "Requires gateway.read. state is the driver connection state, or NOT_RUNNING when the driver module is not running.",
                    "responses" to mapOf("200" to json(ref("TagDeviceList"), "Devices")) + errors)),
                TagDeviceApi.BROWSE to mapOf("post" to op(
                    "summary" to "Browse one page of a device's nodes for tag creation",
                    "description" to "Requires gateway.write: it talks to the device and returns raw device values. An OPC UA connection is browsed on the server (Objects folder at the root). Modbus has no browse service: the root lists the address areas, and each area pages through addresses with one probe read per page. A node's nodeId is the opcItemPath of a tag whose opcServer is the device. 404 for an unknown device.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("TagDeviceBrowseRequest")))),
                    "responses" to mapOf("200" to json(ref("TagDeviceBrowseResponse"), "One page of nodes")) + errors)),
                "/api/v1/config/batch" to mapOf("post" to op(
                    "summary" to "Create or update many config resources in one request",
                    "description" to "Requires gateway.write. Security-module (org.oip.security) User, Role, and ApiKey resources are managed through /api/v1/security and are refused here (403) for every caller; reading them needs users.admin, roles.admin, or apikeys.admin, and passwordHash, passwordHistory, and keyHash are never returned. Writing other org.oip.security kinds also needs users.admin, roles.admin, and apikeys.admin.",
                    "parameters" to listOf(csrf),
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("ConfigBatch")))),
                    "responses" to mapOf("200" to json(ref("ConfigBatchResult"), "Counts")) + errors)),
                "/api/v1/auth/login" to mapOf("post" to op(
                    "summary" to "Create a browser session",
                    "description" to "Sets the oip_session cookie (HttpOnly, SameSite=Strict; Secure when HTTPS or a trusted proxy is configured). No CSRF token is required. Absolute lifetime defaults to 12 hours and is sent as Max-Age. Disabling it omits Max-Age (a session cookie).",
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("LoginRequest")))),
                    "responses" to mapOf(
                        "200" to json(ref("LoginResult"), "Session created"),
                        "400" to problem("Malformed credentials"),
                        "401" to problem("Unknown user or wrong password"),
                        "403" to problem("Disabled account or expired password"),
                        "429" to problem("Account locked, or too many login attempts from this address"),
                    ), public = true)),
                "/api/v1/setup" to mapOf("get" to op(
                    "summary" to "First-run status",
                    "description" to "needsAdmin is true while the gateway has no users. fromThisMachine is true when this request may create the first administrator (straight from a loopback address, same origin, no proxy header).",
                    "responses" to mapOf(
                        "200" to json(mapOf("type" to "object", "properties" to mapOf(
                            "needsAdmin" to mapOf("type" to "boolean"),
                            "fromThisMachine" to mapOf("type" to "boolean"),
                        )), "Status"),
                    ), public = true)),
                "/api/v1/setup/admin" to mapOf("post" to op(
                    "summary" to "Create the first administrator",
                    "description" to "Only while the gateway has no users, and only from a browser on the gateway machine. Creates the user with the Administrator role and signs in as it (same answer as login). The password must meet the password policy.",
                    "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("LoginRequest")))),
                    "responses" to mapOf(
                        "201" to json(ref("LoginResult"), "Administrator created and signed in"),
                        "400" to problem("Not JSON, or no password"),
                        "403" to problem("Not from a browser on the gateway machine"),
                        "409" to problem("The gateway already has users"),
                        "422" to problem("The password does not meet the policy"),
                    ), public = true)),
                "/api/v1/auth/logout" to mapOf("post" to op(
                    "summary" to "End the browser session",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf(
                        "204" to mapOf("description" to "Session ended"),
                        "200" to json(mapOf("type" to "object", "properties" to mapOf("endSessionUrl" to mapOf("type" to "string"))),
                            "Session ended; an identity-provider session also gets the provider's end-session URL to visit"),
                        "401" to problem("No session"),
                        "403" to problem("CSRF token missing or rejected"),
                    ))),
                "/api/v1/auth/session" to mapOf("get" to op(
                    "summary" to "Current caller. Cookie sessions include the CSRF token.",
                    "responses" to mapOf(
                        "200" to json(ref("Caller"), "Caller"),
                        "401" to problem("Not authenticated or session expired"),
                    ))),
                "/api/v1/ws" to mapOf("get" to op(
                    "summary" to "HMI session socket (WebSocket upgrade)",
                    "description" to "Requires tags.read; a browser upgrade must come from this gateway's origin. JSON text frames. " +
                        "Client: hello {nonce, resumeToken?}, subscribe {paths}, unsubscribe {paths}, write {requestId, path, value}, heartbeat {time}. " +
                        "Server: welcome {nonce, sessionId, resumeToken, serverTime}, update {updates: [{path, value, quality, code, label, timestamp}]}, " +
                        "writeResult {requestId, ok, error?}, heartbeat {time}, error {message}. " +
                        "Each subscribed path gets its current value, then its changes, coalesced per path. Reads, subscriptions and writes run as the caller: " +
                        "a tag whose read permissions do not admit the caller's roles and security zone is sent with a null value and label Bad_AccessDenied, " +
                        "and each write also needs tags.write and the tag's write permissions. A resumeToken presented by the same user within 60 s of a " +
                        "disconnect restores that session's subscriptions. Every 15 s the login or API key is re-checked; after logout, timeout, a role change, " +
                        "or a revoked, rotated or re-scoped key the socket is closed with code 1008. At most 8 sockets per user or key; a session's writes " +
                        "run in order with at most 64 waiting (more are answered ok false, busy). Values that are not strings, numbers or booleans are sent as JSON text. " +
                        "Client script {requestId, call: transform|event, project, view?, hook, ...} runs one of the project's HMI script hooks as the caller " +
                        "(transform: value, quality, timestamp, nodeId, prop; event: event, payload, nodeId, params, custom) and is answered " +
                        "scriptResult {requestId, ok, value?, actions?, error?, code?} (code unknown_hook when the project has no saved source for it, busy when a cap is full); hook sources come from the project's saved views and its ScriptHooks resource. " +
                        "Scripts use the gateway script limits; at most 4 run at once per session and 8 per user, and a call is answered within 25 s. " +
                        "Each script write needs tags.write at that moment, at most 64 writes per run, and a session's scripts are cancelled when it ends.",
                    "responses" to mapOf(
                        "101" to mapOf("description" to "Switching to the WebSocket protocol"),
                        "401" to problem("Not authenticated"),
                        "403" to problem("tags.read denied, or a cross-origin upgrade"),
                    ))),
                "/api/v1/audit" to mapOf("get" to op(
                    "summary" to "Query the audit log",
                    "parameters" to listOf(
                        p("actor", "query"), p("action", "query"), p("status", "query"), p("target", "query"),
                        p("from", "query"), p("to", "query"),
                        mapOf("name" to "limit", "in" to "query", "required" to false, "schema" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 1000)),
                        mapOf("name" to "offset", "in" to "query", "required" to false, "schema" to mapOf("type" to "integer", "minimum" to 0)),
                    ),
                    "responses" to mapOf("200" to json(ref("AuditPage"), "Audit page")) + errors)),
                "/api/v1/config" to mapOf("get" to op("summary" to "List config resources",
                    "description" to "Requires gateway.read. Security-module User, Role, and ApiKey resources are listed only to holders of users.admin, roles.admin, and apikeys.admin respectively, without passwordHash, passwordHistory, or keyHash.",
                    "parameters" to listOf(p("module", "query"), p("scope", "query"), p("kind", "query")),
                    "responses" to mapOf("200" to json(ref("ResourceList"), "Resources")) + errors)),
                "/api/v1/schemas/{module}/{kind}" to mapOf(
                    "parameters" to listOf(p("module"), p("kind")),
                    "get" to op("summary" to "JSON Schema registered for a resource kind. Requires gateway.read.",
                        "description" to "Draft 2020-12 schema used to validate that kind. additionalProperties is false where the registry injected it.",
                        "responses" to mapOf("200" to json(ref("KindSchema"), "Schema")) + errors)),
                gw to collectionOps(listOf(p("module"), p("kind"))),
                "$gw/{name}" to itemOps(listOf(p("module"), p("kind"), p("name"))),
                pj to collectionOps(listOf(p("module"), p("project"), p("kind"))),
                "$pj/{name}" to itemOps(listOf(p("module"), p("project"), p("kind"), p("name"))),
                "/api/v1/modules" to mapOf(
                    "get" to op("summary" to "List installed modules", "responses" to mapOf("200" to json(ref("ModuleList"), "Modules")) + errors),
                    "post" to op("summary" to "Install a module package (zip)", "parameters" to listOf(csrf), "requestBody" to bin,
                        "responses" to mapOf("201" to json(ref("ModuleInfo"), "Installed")) + modErr),
                ),
                "/api/v1/modules/{id}" to mapOf("parameters" to listOf(p("id")),
                    "get" to op("summary" to "Get a module", "responses" to mapOf("200" to json(ref("ModuleInfo"), "Module")) + modErr),
                    "put" to op("summary" to "Upgrade a module to a newer package version", "parameters" to listOf(csrf), "requestBody" to bin,
                        "responses" to mapOf("200" to json(ref("ModuleInfo"), "Upgraded")) + modErr),
                    "delete" to op("summary" to "Uninstall a module", "parameters" to listOf(csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Uninstalled")) + modErr)),
                "/api/v1/modules/{id}/install" to mapOf("parameters" to listOf(p("id")),
                    "post" to op("summary" to "Install a built-in module again after it was uninstalled. Requires modules.admin.",
                        "description" to "Built-in modules ship inside the gateway. An uninstalled one stays uninstalled across restarts and is listed under `available` in GET /api/v1/modules until this installs it. 404 when the id is not a built-in module, 409 when it is installed.",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("201" to json(ref("ModuleInfo"), "Installed")) + modErr)),
                "/api/v1/deployment-mode" to mapOf(
                    "get" to op("summary" to "Active deployment mode and the modes that have overlays. Requires gateway.read.",
                        "responses" to mapOf("200" to json(ref("DeploymentMode"), "Deployment mode")) + errors),
                    "put" to op("summary" to "Set the active deployment mode, or null for none. Requires gateway.write.",
                        "description" to "Applies to the next effective-config read without a restart. A change writes one config-change audit record. 422 for a body without mode or with an invalid mode name.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("DeploymentModeUpdate")))),
                        "responses" to mapOf("200" to json(ref("DeploymentMode"), "Deployment mode"), "422" to problem("Invalid mode")) + errors)),
                "/api/v1/gateway/settings" to mapOf(
                    "get" to op("summary" to "Gateway-wide settings with defaults filled in. Requires gateway.read.",
                        "description" to "The config resource org.oip.gateway/gateway/GatewaySettings/gateway, which the config API also reads and writes. effectiveSystemName is systemName, or the host name when systemName is blank.",
                        "responses" to mapOf("200" to json(ref("GatewaySettings"), "Gateway settings")) + errors),
                    "put" to op("summary" to "Replace the gateway-wide settings. Requires gateway.write.",
                        "description" to "A missing field takes its default. A change writes one config-change audit record and applies without a restart: the system name to the Gateway/SystemName system tag, launch.defaultProject to the next /hmi launch. 422 for a system name with '|' or over 63 characters, an encoding other than json, a default project that is not a project name, or an unknown field.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("GatewaySettingsUpdate")))),
                        "responses" to mapOf("200" to json(ref("GatewaySettings"), "Gateway settings"), "422" to problem("Invalid settings")) + errors)),
                "/api/v1/backups" to mapOf(
                    "get" to op("summary" to "List stored gateway backup names, sizes, and whether each is encrypted. Requires gateway.read.",
                        "responses" to mapOf("200" to json(ref("BackupList"), "Backup list")) + errors),
                    "post" to op("summary" to "Create a passphrase-encrypted gateway backup. Requires backups.admin. Audited (gateway backup created).",
                        "description" to "The archive (config, runtime rows, module manifest, secrets.key, the secret store, audit log) is sealed with AES-256-GCM under a key derived with Argon2id from the passphrase, then stored under backups/ and returned. Neither the stored file nor the response holds secrets.key in clear. The gateway does not keep the passphrase: a lost passphrase means the backup cannot be restored. bundleModules=true includes module packages. 422 for a missing passphrase or one shorter than 12 characters.",
                        "parameters" to listOf(csrf, mapOf("name" to "bundleModules", "in" to "query", "required" to false, "schema" to mapOf("type" to "boolean"))),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("BackupPassphrase")))),
                        "responses" to mapOf("200" to sealedBackup, "422" to problem("Missing or short passphrase")) + errors)),
                "/api/v1/backups/restore" to mapOf(
                    "post" to op("summary" to "Restore an uploaded gateway backup. Requires backups.admin. Audited (gateway backup restored) after the restore.",
                        "description" to "Send an encrypted backup as application/json with passphrase and the file as base64 in archive. A plaintext archive from an older version can still be sent as application/zip. A wrong passphrase, or an encrypted file sent without one, is refused with 422 and changes nothing.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf(
                            "application/json" to mapOf("schema" to ref("BackupUpload")),
                            "application/zip" to mapOf("schema" to mapOf("type" to "string", "contentMediaType" to "application/zip")))),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Restored"), "422" to problem("Wrong or missing passphrase, or an invalid archive")) + errors)),
                "/api/v1/backups/{name}" to mapOf("parameters" to listOf(p("name")),
                    "get" to op("summary" to "Download a stored encrypted gateway backup. Requires backups.admin.",
                        "description" to "Returns the sealed file as stored. A plaintext .zip stored by an older version is refused with 409 and is never served.",
                        "responses" to mapOf("200" to sealedBackup, "404" to problem("Unknown backup"), "409" to problem("Stored without encryption")) + errors)),
                "/api/v1/backups/{name}/restore" to mapOf("parameters" to listOf(p("name")),
                    "post" to op("summary" to "Restore a stored gateway backup. Requires backups.admin. Audited (gateway backup restored) after the restore.",
                        "description" to "An encrypted backup needs its passphrase in the body. A wrong passphrase is refused with 422 and changes nothing.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to false, "content" to mapOf("application/json" to mapOf("schema" to ref("BackupPassphrase")))),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Restored"), "404" to problem("Unknown backup"), "422" to problem("Wrong or missing passphrase")) + errors)),
                "/api/v1/projects" to mapOf(
                    "get" to op("summary" to "List projects. Requires gateway.read.",
                        "description" to "projects holds the names; items holds each project's settings (title, description, parent, enabled, inheritable, runnable), resource count, children, and inheritance chain.",
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Projects")) + errors),
                    "post" to op("summary" to "Create a project. Requires gateway.write. One audit record (project created).",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                        "responses" to mapOf("201" to json(mapOf("type" to "object"), "Created project"), "409" to problem("Project exists"), "422" to problem("Invalid name or settings, missing or non-inheritable parent")) + errors)),
                "/api/v1/projects/{name}" to mapOf("parameters" to listOf(p("name")),
                    "get" to op("summary" to "Read a project's settings. Requires gateway.read.",
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Project"), "404" to problem("Unknown project")) + errors),
                    "patch" to op("summary" to "Change project settings (title, description, parent, enabled, inheritable, runnable). Requires gateway.write. One audit record (project updated).",
                        "description" to "An inheritance cycle, a missing or non-inheritable parent, or clearing inheritable while a project inherits from this one is refused with 422.",
                        "parameters" to listOf(csrf, p("If-Match", "header")),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Project"), "404" to problem("Unknown project"), "409" to problem("Stale settings version"), "422" to problem("Invalid settings")) + errors),
                    "delete" to op("summary" to "Delete a project and every resource in it. Requires gateway.write. One audit record (project deleted).",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Deleted"), "404" to problem("Unknown project"), "409" to problem("Another project inherits from it")) + errors)),
                "/api/v1/projects/{name}/rename" to mapOf("parameters" to listOf(p("name")),
                    "post" to op("summary" to "Rename a project, moving every resource. Requires gateway.write. Child projects follow. One audit record (project renamed).",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Renamed project"), "404" to problem("Unknown project"), "409" to problem("Target exists")) + errors)),
                "/api/v1/projects/{name}/copy" to mapOf("parameters" to listOf(p("name")),
                    "post" to op("summary" to "Copy a project with every resource. Requires gateway.write. One audit record (project copied).",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                        "responses" to mapOf("201" to json(mapOf("type" to "object"), "Copied project"), "404" to problem("Unknown project"), "409" to problem("Target exists")) + errors)),
                "/api/v1/home" to mapOf(
                    "get" to op("summary" to "Gateway home page data for the signed-in caller: launch links, launchable projects, and first-run steps, filtered by the caller's permissions.",
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Home")) + errors)),
                "/api/v1/home/setup" to mapOf(
                    "put" to op("summary" to "Mark the first-run steps finished or not. Requires gateway.write.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Home")) + errors)),
                "/api/v1/projects/{name}/export" to mapOf("parameters" to listOf(p("name")),
                    "get" to op("summary" to "Export a project. Requires gateway.read. Security-module User, Role, and ApiKey resources are left out.",
                        "responses" to mapOf("200" to mapOf("description" to "Project zip"), "404" to problem("Unknown project")) + errors)),
                "/api/v1/projects/{name}/import" to mapOf("parameters" to listOf(p("name"), mapOf("name" to "policy", "in" to "query", "required" to true, "schema" to mapOf("type" to "string", "enum" to listOf("skip", "overwrite")))),
                    "post" to op("summary" to "Import a project. Requires gateway.write. An org.oip.security entry follows the config API rules (403), and nothing is imported.",
                        "parameters" to listOf(csrf), "requestBody" to bin,
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Import result")) + errors)),
                "/api/v1/logs" to mapOf(
                    "get" to op("summary" to "Query the in-memory log ring. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Log records")) + errors)),
                "/api/v1/logs/download" to mapOf(
                    "get" to op("summary" to "Download the log file. Requires gateway.read.",
                        "responses" to mapOf("200" to mapOf("description" to "Newline-delimited JSON", "content" to mapOf("application/x-ndjson" to mapOf("schema" to mapOf("type" to "string"))))) + errors)),
                "/api/v1/logs/levels" to mapOf(
                    "get" to op("summary" to "Configured log levels. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Levels")) + errors),
                    "put" to op("summary" to "Set a logger level without restart. Requires gateway.write. Audited (log level changed).",
                        "parameters" to listOf(csrf),
                        "responses" to mapOf("200" to json(mapOf("type" to "object"), "Updated")) + errors)),
                "/api/v1/diagnostics/threads" to mapOf("get" to op("summary" to "Thread list. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Threads")) + errors)),
                "/api/v1/diagnostics/threads/dump" to mapOf("get" to op("summary" to "Thread dump. Requires gateway.read.",
                    "responses" to mapOf("200" to mapOf("description" to "Plain-text thread dump", "content" to mapOf("text/plain" to mapOf("schema" to mapOf("type" to "string"))))) + errors)),
                "/api/v1/diagnostics/memory" to mapOf("get" to op("summary" to "Memory. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Memory")) + errors)),
                "/api/v1/diagnostics/modules" to mapOf("get" to op("summary" to "Module states. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Modules")) + errors)),
                "/api/v1/diagnostics/config" to mapOf("get" to op("summary" to "Config store counts. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Config")) + errors)),
                "/api/v1/diagnostics/latency" to mapOf("get" to op("summary" to "Request latency histograms. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Latency")) + errors)),
                "/api/v1/diagnostics/performance" to mapOf("get" to op("summary" to "CPU, heap, disk, and clock drift. clockDrift.drift is (actualWake - expectedWake) / interval for a 1 s sleep. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Performance")) + errors)),
                "/api/v1/diagnostics/metrics" to mapOf("get" to op("summary" to "Named gateway metrics (CPU, heap, disk, tag changes per second, sessions, history queue depth) with the last 120 samples taken 5 s apart. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Metrics")) + errors)),
                "/api/v1/diagnostics/scripts" to mapOf("get" to op("summary" to "Gateway scripts that have run or are running: execution count, last/average/max milliseconds, failures and recent log lines. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Scripts")) + errors)),
                "/api/v1/diagnostics/tasks" to mapOf("get" to op("summary" to "Scheduled tasks. Requires gateway.read.", "responses" to mapOf("200" to json(mapOf("type" to "object"), "Tasks")) + errors)),
                "/api/v1/diagnostics/opcua/connections" to mapOf("get" to op(
                    "summary" to "OPC UA client connections: server state, session, subscription and monitored-item counts. Monitored items are only those the server created. Requires gateway.read.",
                    "responses" to mapOf("200" to json(mapOf("type" to "object"), "OPC UA connections")) + errors)),
                "/api/v1/security/users" to mapOf(
                    "get" to op("summary" to "List users. Requires users.admin. Password hashes are omitted.",
                        "responses" to mapOf("200" to json(ref("UserList"), "Users")) + errors),
                    "post" to op("summary" to "Create a user and assign roles. Requires users.admin. The password hash is never returned.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("UserCreate")))),
                        "responses" to mapOf("201" to json(ref("User"), "Created")) + errors),
                ),
                "/api/v1/security/users/{name}" to mapOf("parameters" to listOf(p("name")),
                    "get" to op("summary" to "Get a user. Requires users.admin. Password hashes are omitted.",
                        "responses" to mapOf("200" to json(ref("User"), "User")) + errors),
                    "put" to op("summary" to "Update profile, enabled, and roles. Requires users.admin. Password changes use the password route.",
                        "parameters" to listOf(ifMatch, csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("UserUpdate")))),
                        "responses" to mapOf("200" to json(ref("User"), "Updated")) + errors),
                    "delete" to op("summary" to "Delete a user. Requires users.admin.",
                        "parameters" to listOf(ifMatch, csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors),
                ),
                "/api/v1/security/users/{name}/password" to mapOf("parameters" to listOf(p("name")),
                    "post" to op("summary" to "Set a user's password. Requires users.admin. The hash is stored and never returned.",
                        "parameters" to listOf(ifMatch, csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("PasswordChange")))),
                        "responses" to mapOf("200" to json(ref("User"), "Updated user")) + errors),
                ),
                "/api/v1/security/roles" to mapOf(
                    "get" to op("summary" to "List roles. Requires roles.admin.",
                        "responses" to mapOf("200" to json(ref("RoleList"), "Roles")) + errors),
                    "post" to op("summary" to "Create a role. Requires roles.admin.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("RoleCreate")))),
                        "responses" to mapOf("201" to json(ref("Role"), "Created")) + errors),
                ),
                "/api/v1/security/roles/{name}" to mapOf("parameters" to listOf(p("name")),
                    "get" to op("summary" to "Get a role. Requires roles.admin.",
                        "responses" to mapOf("200" to json(ref("Role"), "Role")) + errors),
                    "put" to op("summary" to "Update a role's permissions or description. Requires roles.admin.",
                        "parameters" to listOf(ifMatch, csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("RoleUpdate")))),
                        "responses" to mapOf("200" to json(ref("Role"), "Updated")) + errors),
                    "delete" to op("summary" to "Delete a role. Requires roles.admin.",
                        "parameters" to listOf(ifMatch, csrf),
                        "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors),
                ),
                "/api/v1/security/api-keys" to mapOf(
                    "get" to op("summary" to "List API keys. Requires apikeys.admin. Secrets and key hashes are omitted.",
                        "responses" to mapOf("200" to json(ref("ApiKeyList"), "API keys")) + errors),
                    "post" to op("summary" to "Create an API key. Requires apikeys.admin. The secret is returned once in this response.",
                        "parameters" to listOf(csrf),
                        "requestBody" to mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to ref("ApiKeyCreate")))),
                        "responses" to mapOf("201" to json(ref("ApiKeyIssued"), "Created, with secret")) + errors),
                ),
                "/api/v1/security/api-keys/{id}" to mapOf("parameters" to listOf(p("id")),
                    "get" to op("summary" to "Get an API key. Requires apikeys.admin. The secret is not returned.",
                        "responses" to mapOf("200" to json(ref("ApiKey"), "API key")) + errors),
                    "delete" to op("summary" to "Revoke (disable) an API key. Requires apikeys.admin. A revoked key receives 401. The secret is not returned.",
                        "parameters" to listOf(ifMatch, csrf),
                        "responses" to mapOf("200" to json(ref("ApiKey"), "Revoked")) + errors),
                ),
                "/api/v1/security/api-keys/{id}/rotate" to mapOf("parameters" to listOf(p("id")),
                    "post" to op("summary" to "Rotate an API key. Requires apikeys.admin. The new secret is returned once; the previous secret stops working.",
                        "parameters" to listOf(ifMatch, csrf),
                        "responses" to mapOf("200" to json(ref("ApiKeyIssued"), "Rotated, with secret")) + errors),
                ),
            ) + dbPaths() + c7Paths() + m54Paths() + transactionGroupPaths() + idpPaths(),
            "components" to mapOf(
                "securitySchemes" to mapOf(
                    "sessionCookie" to mapOf("type" to "apiKey", "in" to "cookie", "name" to HttpAuth.COOKIE,
                        "description" to "Browser session. HttpOnly, SameSite=Strict, and Secure when the gateway is configured for HTTPS or a trusted proxy."),
                    "bearerAuth" to mapOf("type" to "http", "scheme" to "bearer",
                        "description" to "API token, or an access token from an enabled OIDC identity provider (signature, issuer, audience and expiry checked), for machine clients. Not subject to CSRF."),
                ),
                "parameters" to mapOf(
                    "CsrfToken" to mapOf("name" to HttpAuth.CSRF_HEADER, "in" to "header", "required" to false,
                        "description" to "Required when the caller authenticated with the session cookie. Omitted for bearer tokens.",
                        "schema" to mapOf("type" to "string")),
                ),
                "schemas" to mapOf(
                "Problem" to mapOf("type" to "object", "required" to listOf("type", "title", "status"), "properties" to mapOf(
                    "type" to mapOf("type" to "string"), "title" to mapOf("type" to "string"), "status" to mapOf("type" to "integer"),
                    "detail" to mapOf("type" to "string"), "instance" to mapOf("type" to "string"),
                    "errors" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "retryAfter" to mapOf("type" to "integer", "description" to "Seconds until a locked account can try again"))),
                "LoginRequest" to mapOf("type" to "object", "required" to listOf("username", "password"), "properties" to mapOf(
                    "username" to mapOf("type" to "string"), "password" to mapOf("type" to "string"))),
                "LoginResult" to mapOf("type" to "object", "required" to listOf("username", "roles", "csrfToken"), "properties" to mapOf(
                    "username" to mapOf("type" to "string"),
                    "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "csrfToken" to mapOf("type" to "string"),
                    "expiresAt" to mapOf("type" to "string", "format" to "date-time"),
                    "idleTimeoutSeconds" to mapOf("type" to "integer"))),
                "Caller" to mapOf("type" to "object", "required" to listOf("kind", "principal", "permissions"), "properties" to mapOf(
                    "kind" to mapOf("type" to "string", "enum" to listOf("user", "apiKey")),
                    "principal" to mapOf("type" to "string"),
                    "provider" to mapOf("type" to "string", "description" to "Identity provider of a provider login (principal is provider:username)."),
                    "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "scopes" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "permissions" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "zone" to mapOf("type" to "string"),
                    "csrfToken" to mapOf("type" to "string"),
                    "expiresAt" to mapOf("type" to "string", "format" to "date-time"))),
                "AuditEvent" to mapOf("type" to "object", "properties" to mapOf(
                    "seq" to mapOf("type" to "integer"), "timestamp" to mapOf("type" to "string", "format" to "date-time"),
                    "actor" to mapOf("type" to "string"), "actorHost" to mapOf("type" to "string"),
                    "action" to mapOf("type" to "string"), "target" to mapOf("type" to "string"),
                    "detail" to mapOf("type" to "string"), "status" to mapOf("type" to "string"),
                    "origin" to mapOf("type" to "string"), "context" to mapOf("type" to "string"))),
                "AuditPage" to mapOf("type" to "object", "properties" to mapOf(
                    "items" to mapOf("type" to "array", "items" to ref("AuditEvent")),
                    "total" to mapOf("type" to "integer"), "limit" to mapOf("type" to "integer"), "offset" to mapOf("type" to "integer"))),
                "Resource" to mapOf("type" to "object", "required" to listOf("apiVersion", "kind", "metadata"), "properties" to mapOf(
                    "apiVersion" to mapOf("const" to "oip/v1"), "kind" to mapOf("type" to "string"),
                    "metadata" to mapOf("type" to "object", "required" to listOf("name", "module"), "properties" to mapOf(
                        "name" to mapOf("type" to "string"), "module" to mapOf("type" to "string"), "version" to mapOf("type" to "integer"),
                        "labels" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string")),
                        "modified" to mapOf("type" to "string", "format" to "date-time"), "modifiedBy" to mapOf("type" to "string"))),
                    "spec" to mapOf("type" to "object"))),
                "ResourceList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("Resource")))),
                "KindSchema" to mapOf("type" to "object", "required" to listOf("module", "kind", "schema"), "properties" to mapOf(
                    "module" to mapOf("type" to "string"), "kind" to mapOf("type" to "string"),
                    "schema" to mapOf("type" to "object", "description" to "JSON Schema draft 2020-12 document"))),
                "ConfigBatch" to mapOf("type" to "object", "required" to listOf("items"), "properties" to mapOf(
                    "scope" to mapOf("type" to "string"),
                    "items" to mapOf("type" to "array", "items" to ref("Resource")))),
                "ConfigBatchResult" to mapOf("type" to "object", "properties" to mapOf(
                    "created" to mapOf("type" to "integer"), "updated" to mapOf("type" to "integer"), "count" to mapOf("type" to "integer"))),
                "AlarmStatus" to mapOf("type" to "object", "properties" to mapOf(
                    "alarms" to mapOf("type" to "array", "items" to ref("AlarmEvent")))),
                "AlarmEvent" to mapOf("type" to "object", "required" to listOf("eventId", "source", "state", "priority"), "properties" to mapOf(
                    "eventId" to mapOf("type" to "string"),
                    "source" to mapOf("type" to "string"),
                    "name" to mapOf("type" to "string"),
                    "displayPath" to mapOf("type" to "string"),
                    "tagPath" to mapOf("type" to "string"),
                    "priority" to mapOf("type" to "string"),
                    "priorityCode" to mapOf("type" to "integer"),
                    "state" to mapOf("type" to "string"),
                    "stateCode" to mapOf("type" to "integer"),
                    "active" to mapOf("type" to "boolean"),
                    "acknowledged" to mapOf("type" to "boolean"),
                    "value" to mapOf("description" to "Value that last changed the event"),
                    "activeTime" to mapOf("type" to "string", "format" to "date-time"),
                    "clearTime" to mapOf("type" to "string", "format" to "date-time"),
                    "ackTime" to mapOf("type" to "string", "format" to "date-time"),
                    "ackUser" to mapOf("type" to "string"),
                    "ackNotes" to mapOf("type" to "string"),
                    "setpointA" to mapOf("description" to "Current low or only setpoint"),
                    "setpointB" to mapOf("description" to "Current high setpoint"),
                    "notes" to mapOf("type" to "string"),
                    "associatedData" to mapOf("type" to "object", "description" to "Extra context values attached when the event became active"))),
                "AlarmAck" to mapOf("type" to "object", "required" to listOf("eventIds"), "properties" to mapOf(
                    "eventIds" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "notes" to mapOf("type" to "string"))),
                "AlarmAckResult" to mapOf("type" to "object", "properties" to mapOf(
                    "acknowledged" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "AlarmShelve" to mapOf("type" to "object", "required" to listOf("sources", "durationSeconds"), "properties" to mapOf(
                    "sources" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "durationSeconds" to mapOf("type" to "number", "exclusiveMinimum" to 0))),
                "AlarmShelveResult" to mapOf("type" to "object", "properties" to mapOf(
                    "shelved" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "AlarmUnshelve" to mapOf("type" to "object", "required" to listOf("sources"), "properties" to mapOf(
                    "sources" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "EmailAck" to mapOf("type" to "object", "properties" to mapOf(
                    "eventId" to mapOf("type" to "string"),
                    "username" to mapOf("type" to "string"),
                    "source" to mapOf("type" to "string"),
                    "name" to mapOf("type" to "string"),
                    "displayPath" to mapOf("type" to "string"),
                    "state" to mapOf("type" to "string"),
                    "active" to mapOf("type" to "boolean"),
                    "acknowledged" to mapOf("type" to "boolean"),
                    "notesRequired" to mapOf("type" to "boolean"))),
                "EmailAckSubmit" to mapOf("type" to "object", "properties" to mapOf("notes" to mapOf("type" to "string"))),
                "OnCall" to mapOf("type" to "object", "properties" to mapOf(
                    "roster" to mapOf("type" to "string"),
                    "users" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "ScheduleState" to mapOf("type" to "object", "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "active" to mapOf("type" to "boolean"))),
                "PipelineStatus" to mapOf("type" to "object", "properties" to mapOf(
                    "pipelines" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "name" to mapOf("type" to "string"),
                        "inFlight" to mapOf("type" to "integer")))),
                    "events" to mapOf("type" to "array", "description" to "Alarm events now inside a pipeline", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "eventId" to mapOf("type" to "string"),
                        "pipeline" to mapOf("type" to "string"),
                        "block" to mapOf("type" to "string"),
                        "alarm" to mapOf("type" to "string"),
                        "displayPath" to mapOf("type" to "string"),
                        "state" to mapOf("type" to "string"),
                        "waitingUntil" to mapOf("type" to "string", "format" to "date-time")))))),
                "AlarmUnshelveResult" to mapOf("type" to "object", "properties" to mapOf(
                    "unshelved" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "UdtTypeList" to mapOf("type" to "object", "properties" to mapOf(
                    "provider" to mapOf("type" to "string"),
                    "types" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "path" to mapOf("type" to "string"),
                        "error" to mapOf("type" to "string"),
                        "memberCount" to mapOf("type" to "integer", "description" to "Members after parent inheritance"),
                        "parameters" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                            "name" to mapOf("type" to "string"), "dataType" to mapOf("type" to "string"),
                            "default" to mapOf("description" to "Default value, or null when the instance must supply one"))))))))),
                "UdtInstanceRequest" to mapOf("type" to "object", "required" to listOf("typeId"), "properties" to mapOf(
                    "typeId" to mapOf("type" to "string", "description" to "UDT definition path, e.g. Types/Pump"),
                    "provider" to mapOf("type" to "string", "default" to "default"),
                    "folder" to mapOf("type" to "string", "description" to "Parent folder without the provider, e.g. Line1/Pumps"),
                    "pattern" to mapOf("type" to "object", "required" to listOf("template", "count"), "properties" to mapOf(
                        "template" to mapOf("type" to "string", "description" to "Must contain {n}, e.g. Pump_{n}"),
                        "start" to mapOf("type" to "integer", "minimum" to 0, "default" to 1),
                        "count" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 1000),
                        "step" to mapOf("type" to "integer", "minimum" to 1, "default" to 1),
                        "pad" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 9, "default" to 0))),
                    "rows" to mapOf("type" to "array", "maxItems" to 1000, "items" to mapOf("type" to "object", "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "Leaf name. Overrides the pattern name for this row."),
                        "parameters" to mapOf("type" to "object", "additionalProperties" to true)))),
                    "onCollision" to mapOf("type" to "string", "enum" to listOf("fail", "skip", "rename"), "default" to "fail"),
                    "dryRun" to mapOf("type" to "boolean", "default" to false))),
                "UdtInstancePlan" to mapOf("type" to "object", "properties" to mapOf(
                    "dryRun" to mapOf("type" to "boolean"),
                    "applied" to mapOf("type" to "boolean"),
                    "typeId" to mapOf("type" to "string"),
                    "provider" to mapOf("type" to "string"),
                    "folder" to mapOf("type" to "string"),
                    "onCollision" to mapOf("type" to "string"),
                    "created" to mapOf("type" to "integer", "description" to "Instances created, or that would be created on a dry run"),
                    "skipped" to mapOf("type" to "integer"),
                    "requestErrors" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "rows" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "index" to mapOf("type" to "integer"),
                        "requestedName" to mapOf("type" to "string"),
                        "name" to mapOf("type" to "string"),
                        "path" to mapOf("type" to "string"),
                        "resource" to mapOf("type" to "string"),
                        "status" to mapOf("type" to "string", "enum" to listOf("create", "rename", "skip", "error")),
                        "errors" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "note" to mapOf("type" to "string"),
                        "parameters" to mapOf("type" to "object", "additionalProperties" to true)))))),
                "ShelvedAlarmStatus" to mapOf("type" to "object", "properties" to mapOf(
                    "alarms" to mapOf("type" to "array", "items" to ref("ShelvedAlarm")))),
                "ShelvedAlarm" to mapOf("type" to "object", "required" to listOf("source", "shelved", "shelvedUntil"),
                    "description" to "AlarmEvent fields (null when there is no live event) plus the shelf.",
                    "properties" to mapOf(
                        "eventId" to mapOf("type" to listOf("string", "null")),
                        "source" to mapOf("type" to "string"),
                        "name" to mapOf("type" to "string"),
                        "displayPath" to mapOf("type" to "string"),
                        "tagPath" to mapOf("type" to "string"),
                        "priority" to mapOf("type" to "string"),
                        "priorityCode" to mapOf("type" to "integer"),
                        "state" to mapOf("type" to listOf("string", "null")),
                        "shelved" to mapOf("type" to "boolean"),
                        "shelvedAt" to mapOf("type" to listOf("string", "null"), "format" to "date-time"),
                        "shelvedUntil" to mapOf("type" to "string", "format" to "date-time"),
                        "shelvedBy" to mapOf("type" to listOf("string", "null")),
                        "conditionActive" to mapOf("type" to "boolean"))),
                "AlarmJournalEvent" to mapOf("type" to "object", "properties" to mapOf(
                    "id" to mapOf("type" to "integer"),
                    "eventId" to mapOf("type" to "string"),
                    "source" to mapOf("type" to "string"),
                    "displayPath" to mapOf("type" to "string"),
                    "priority" to mapOf("type" to "string"),
                    "priorityCode" to mapOf("type" to "integer"),
                    "eventType" to mapOf("type" to "string", "enum" to listOf("active", "clear", "ack", "enabled", "disabled", "shelve", "unshelve")),
                    "eventTypeCode" to mapOf("type" to "integer"),
                    "eventFlags" to mapOf("type" to "integer"),
                    "eventTime" to mapOf("type" to "string", "format" to "date-time"),
                    "actor" to mapOf("type" to listOf("string", "null")),
                    "data" to mapOf("type" to "object"))),
                "AlarmJournalPage" to mapOf("type" to "object", "properties" to mapOf(
                    "events" to mapOf("type" to "array", "items" to ref("AlarmJournalEvent")),
                    "total" to mapOf("type" to "integer"),
                    "offset" to mapOf("type" to "integer"),
                    "limit" to mapOf("type" to "integer"))),
                "AlarmJournalSettings" to mapOf("type" to "object", "required" to listOf("maxAgeDays"), "properties" to mapOf(
                    "maxAgeDays" to mapOf("type" to listOf("number", "null"), "minimum" to 0))),
                "AlarmJournalPurge" to mapOf("type" to "object", "properties" to mapOf(
                    "olderThan" to mapOf("type" to "string", "format" to "date-time"))),
                "AlarmJournalPurgeResult" to mapOf("type" to "object", "properties" to mapOf(
                    "deleted" to mapOf("type" to "integer"),
                    "olderThan" to mapOf("type" to "string", "format" to "date-time"))),
                "TagReadRequest" to mapOf("type" to "object", "required" to listOf("paths"), "properties" to mapOf(
                    "paths" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "TagReadResponse" to mapOf("type" to "object", "properties" to mapOf(
                    "values" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "path" to mapOf("type" to "string"), "value" to mapOf("description" to "Current value"), "quality" to mapOf("type" to "string"),
                        "timestamp" to mapOf("type" to "string")))))),
                "TagDiagnostics" to mapOf("type" to "object", "required" to listOf(
                    "path", "type", "value", "quality", "timestamp", "subscriberCount", "referencesIn", "referencesOut", "tagGroup", "deadband",
                ), "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "type" to mapOf("type" to "string"),
                    "value" to mapOf("description" to "Current value, null when the caller may not read the tag"),
                    "quality" to mapOf("type" to "string"),
                    "timestamp" to mapOf("type" to "string"),
                    "sourceTimestamp" to mapOf("type" to listOf("string", "null")),
                    "message" to mapOf("type" to listOf("string", "null")),
                    "subscriberCount" to mapOf("type" to "integer"),
                    "referencesIn" to mapOf("type" to "array", "items" to ref("TagLink")),
                    "referencesOut" to mapOf("type" to "array", "items" to ref("TagLink")),
                    "tagGroup" to ref("TagGroupDiagnostics"),
                    "deadband" to ref("DeadbandDiagnostics"),
                )),
                "TagLink" to mapOf("type" to "object", "required" to listOf("path", "kind"), "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "kind" to mapOf("type" to "string"),
                )),
                "TagGroupDiagnostics" to mapOf("type" to "object", "required" to listOf(
                    "name", "mode", "rateMillis", "valid", "overruns",
                ), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "mode" to mapOf("type" to "string"),
                    "rateMillis" to mapOf("type" to "integer"),
                    "slowRateMillis" to mapOf("type" to listOf("integer", "null")),
                    "drivingExpression" to mapOf("type" to listOf("string", "null")),
                    "valid" to mapOf("type" to "boolean"),
                    "lastExecution" to mapOf("type" to listOf("string", "null")),
                    "durationMillis" to mapOf("type" to listOf("number", "null")),
                    "overruns" to mapOf("type" to "integer"),
                )),
                "DeadbandDiagnostics" to mapOf("type" to "object", "required" to listOf("mode", "limit", "evaluated", "published"), "properties" to mapOf(
                    "mode" to mapOf("type" to "string"),
                    "limit" to mapOf("type" to "number"),
                    "evaluated" to mapOf("type" to "integer"),
                    "published" to mapOf("type" to "integer"),
                )),
                "TagWriteRequest" to mapOf("type" to "object", "required" to listOf("writes"), "properties" to mapOf(
                    "writes" to mapOf("type" to "array", "maxItems" to TagApi.MAX_WRITES, "items" to mapOf("type" to "object", "required" to listOf("path", "value"),
                        "properties" to mapOf("path" to mapOf("type" to "string"), "value" to mapOf("description" to "New value")))))),
                "TagWriteResponse" to mapOf("type" to "object", "required" to listOf("results"), "properties" to mapOf(
                    "results" to mapOf("type" to "array", "items" to mapOf("type" to "object", "required" to listOf("path", "quality", "ok"), "properties" to mapOf(
                        "path" to mapOf("type" to "string"), "quality" to mapOf("type" to "string"), "ok" to mapOf("type" to "boolean"),
                        "message" to mapOf("type" to "string")))))),
                "HistoryAggregate" to mapOf("type" to "object", "required" to listOf("tagPath", "provider", "from", "to", "intervalMillis", "aggregate", "windows"), "properties" to mapOf(
                    "tagPath" to mapOf("type" to "string"), "provider" to mapOf("type" to "string"),
                    "from" to mapOf("type" to "string", "format" to "date-time"), "to" to mapOf("type" to "string", "format" to "date-time"),
                    "intervalMillis" to mapOf("type" to "integer"), "aggregate" to mapOf("type" to "string"),
                    "interpolation" to mapOf("type" to "string"),
                    "windows" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "start" to mapOf("type" to "string", "format" to "date-time"),
                        "end" to mapOf("type" to "string", "format" to "date-time", "description" to "Exclusive"),
                        "value" to mapOf("description" to "Aggregate value, or null when the window has nothing to aggregate"),
                        "rows" to mapOf("type" to "integer", "description" to "Raw rows inside the window"),
                        "startValue" to mapOf("type" to listOf("number", "null"), "description" to "Interpolated value at start; only with Step or Linear, null when no Good numeric value is in effect"),
                        "endValue" to mapOf("type" to listOf("number", "null"), "description" to "Interpolated value at end; only with Step or Linear, null when no Good numeric value is in effect")))))),
                "HistoryProviders" to mapOf("type" to "object", "properties" to mapOf(
                    "collector" to mapOf("type" to "object", "properties" to mapOf(
                        "pending" to mapOf("type" to "integer"), "written" to mapOf("type" to "integer"),
                        "dropped" to mapOf("type" to "integer"),
                        "diverted" to mapOf("type" to "integer", "description" to "Samples moved to quarantine after repeated local write failures"),
                        "consecutiveFlushFailures" to mapOf("type" to "integer"),
                        "lastFlushError" to mapOf("type" to listOf("string", "null")),
                        "lastFlushErrorAt" to mapOf("type" to listOf("string", "null")))),
                    "providers" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "name" to mapOf("type" to "string"), "configured" to mapOf("type" to "boolean"),
                        "storeAndForward" to mapOf("type" to "boolean"), "pending" to mapOf("type" to "integer"),
                        "quarantined" to mapOf("type" to "integer"),
                        "stored" to mapOf("type" to "integer", "description" to "Datapoints written into this provider's disk cache"),
                        "storeRate" to mapOf("type" to "number", "description" to "Datapoints per second stored into the disk cache over the last 10 seconds"),
                        "connected" to mapOf("type" to listOf("boolean", "null")),
                        "forwarded" to mapOf("type" to "integer"), "lastForward" to mapOf("type" to listOf("string", "null")),
                        "lastError" to mapOf("type" to listOf("string", "null")), "lastErrorAt" to mapOf("type" to listOf("string", "null")),
                        "retryAt" to mapOf("type" to listOf("string", "null")), "provider" to mapOf("type" to "object")))))),
                "HistoryQuarantine" to mapOf("type" to "object", "properties" to mapOf(
                    "records" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "id" to mapOf("type" to "integer"), "provider" to mapOf("type" to "string"), "tagPath" to mapOf("type" to "string"),
                        "timestamp" to mapOf("type" to "string", "format" to "date-time"), "value" to mapOf("description" to "Sample value"),
                        "quality" to mapOf("type" to "string"), "error" to mapOf("type" to "string"),
                        "quarantinedAt" to mapOf("type" to "string", "format" to "date-time")))))),
                "DiskCacheArchiveInfo" to mapOf("type" to "object", "required" to listOf("name", "provider", "records", "bytes", "created"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"), "provider" to mapOf("type" to "string"),
                    "records" to mapOf("type" to "integer"), "bytes" to mapOf("type" to "integer"),
                    "created" to mapOf("type" to "string", "format" to "date-time"))),
                "DiskCacheArchive" to mapOf("type" to "object", "required" to listOf("kind", "version", "records"), "properties" to mapOf(
                    "kind" to mapOf("type" to "string", "const" to "oip.store-forward.disk-cache"),
                    "version" to mapOf("type" to "integer", "const" to 1),
                    "records" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "provider" to mapOf("type" to "string"), "tagPath" to mapOf("type" to "string"),
                        "timestamp" to mapOf("type" to "string", "format" to "date-time"),
                        "value" to mapOf("description" to "Sample value"),
                        "quality" to mapOf("type" to "string")))))),
                "QuarantineSelection" to mapOf("type" to "object", "additionalProperties" to false, "properties" to mapOf(
                    "ids" to mapOf("type" to "array", "items" to mapOf("type" to "integer")),
                    "provider" to mapOf("type" to "string"))),
                "HistoryData" to mapOf("type" to "object", "required" to listOf("tagPath", "from", "to", "limit", "truncated", "samples"), "properties" to mapOf(
                    "tagPath" to mapOf("type" to "string"), "provider" to mapOf("type" to "string"), "from" to mapOf("type" to "string", "format" to "date-time"),
                    "to" to mapOf("type" to "string", "format" to "date-time"), "limit" to mapOf("type" to "integer"),
                    "truncated" to mapOf("type" to "boolean", "description" to "More rows matched than limit"),
                    "samples" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "timestamp" to mapOf("type" to "string", "format" to "date-time"), "value" to mapOf("description" to "Stored value"),
                        "quality" to mapOf("type" to "string")))))),
                "ReportRunRequest" to mapOf("type" to "object", "additionalProperties" to false, "properties" to mapOf(
                    "parameters" to mapOf("type" to "object", "description" to "Parameter values by name; omitted parameters use their defaults"))),
                "ReportResult" to mapOf("type" to "object", "required" to listOf("contractVersion", "report", "parameters", "generatedAt", "datasets", "run"), "properties" to mapOf(
                    "contractVersion" to mapOf("type" to "integer", "const" to 1),
                    "report" to mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string"), "name" to mapOf("type" to "string"),
                        "title" to mapOf("type" to "string"), "version" to mapOf("type" to "integer"))),
                    "parameters" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "name" to mapOf("type" to "string"), "type" to mapOf("type" to "string"), "value" to mapOf("description" to "Resolved value; timeRange is {from, to}")))),
                    "generatedAt" to mapOf("type" to "integer", "description" to "Epoch milliseconds"),
                    "datasets" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                        "name" to mapOf("type" to "string"),
                        "sourceType" to mapOf("type" to "string", "enum" to listOf("namedQuery", "tagHistory", "sql")),
                        "columns" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                            "name" to mapOf("type" to "string"), "type" to mapOf("type" to "string", "enum" to listOf("string", "number", "boolean", "timestamp")),
                            "unit" to mapOf("type" to "string")))),
                        "rows" to mapOf("type" to "array", "items" to mapOf("type" to "array")),
                        "truncated" to mapOf("type" to "boolean"),
                        "series" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf(
                            "tagPath" to mapOf("type" to "string"), "label" to mapOf("type" to "string"), "unit" to mapOf("type" to "string"),
                            "points" to mapOf("type" to "array", "items" to mapOf("type" to "array"))))),
                        "excludedTags" to mapOf("type" to "array", "items" to mapOf("type" to "string"))))),
                    "run" to mapOf("type" to "object", "properties" to mapOf(
                        "runId" to mapOf("type" to "string"), "trigger" to mapOf("type" to "string", "enum" to listOf("manual", "schedule")),
                        "user" to mapOf("type" to "string"), "startedAt" to mapOf("type" to "integer"), "durationMillis" to mapOf("type" to "integer"),
                        "status" to mapOf("type" to "string", "enum" to listOf("success", "partial")),
                        "warnings" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                    "layout" to mapOf("type" to "object", "description" to "The definition's layout, verbatim"))),
                "ReportRun" to mapOf("type" to "object", "properties" to mapOf(
                    "runId" to mapOf("type" to "string"), "report" to mapOf("type" to "string"),
                    "trigger" to mapOf("type" to "string", "enum" to listOf("manual", "schedule")), "user" to mapOf("type" to "string"),
                    "startedAt" to mapOf("type" to "integer"), "durationMillis" to mapOf("type" to "integer"),
                    "status" to mapOf("type" to "string", "enum" to listOf("success", "partial", "failure")),
                    "error" to mapOf("type" to listOf("string", "null")), "datasets" to mapOf("type" to "integer"), "rows" to mapOf("type" to "integer"))),
                "ReportStatus" to mapOf("type" to "object", "properties" to mapOf(
                    "report" to mapOf("type" to "string"), "scheduled" to mapOf("type" to "boolean"),
                    "cron" to mapOf("type" to listOf("string", "null")), "timezone" to mapOf("type" to listOf("string", "null")),
                    "nextFireAt" to mapOf("type" to listOf("integer", "null")), "lastRunAt" to mapOf("type" to listOf("integer", "null")),
                    "lastStatus" to mapOf("type" to listOf("string", "null")), "lastError" to mapOf("type" to listOf("string", "null")),
                    "consecutiveFailures" to mapOf("type" to "integer"))),
                "TagBrowseRequest" to mapOf("type" to "object", "properties" to mapOf(
                    "path" to mapOf("type" to "string", "maxLength" to 4096),
                    "offset" to mapOf("type" to "integer", "minimum" to 0),
                    "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10000))),
                "TagBrowseResponse" to mapOf("type" to "object", "required" to listOf("nodes", "more"), "properties" to mapOf(
                    "path" to mapOf("type" to "string"), "offset" to mapOf("type" to "integer"), "more" to mapOf("type" to "boolean"),
                    "nodes" to mapOf("type" to "array", "items" to mapOf("type" to "object", "required" to listOf("name", "path", "kind", "hasChildren"), "properties" to mapOf(
                        "name" to mapOf("type" to "string"), "path" to mapOf("type" to "string"),
                        "kind" to mapOf("type" to "string", "enum" to listOf("folder", "tag", "udt_instance", "udt_definition")),
                        "hasChildren" to mapOf("type" to "boolean"), "dataType" to mapOf("type" to "string"),
                        "value" to mapOf("description" to "Current value (tags only)"), "quality" to mapOf("type" to "string"),
                        "timestamp" to mapOf("type" to "string"),
                        "resource" to mapOf("type" to "string", "description" to "Name of the Tag config resource that defines this node, when one does")))))),
                "TagProviderList" to mapOf("type" to "object", "required" to listOf("providers"), "properties" to mapOf(
                    "providers" to mapOf("type" to "array", "items" to mapOf("type" to "object", "required" to listOf("name"), "properties" to mapOf(
                        "name" to mapOf("type" to "string"), "readOnly" to mapOf("type" to "boolean")))))),
                "TagDeviceList" to mapOf("type" to "object", "required" to listOf("devices"), "properties" to mapOf(
                    "devices" to mapOf("type" to "array", "items" to mapOf("type" to "object", "required" to listOf("name", "driver", "state"), "properties" to mapOf(
                        "name" to mapOf("type" to "string"), "driver" to mapOf("type" to "string", "enum" to listOf("modbus", "opcua")),
                        "state" to mapOf("type" to "string")))))),
                "TagDeviceBrowseRequest" to mapOf("type" to "object", "required" to listOf("device"), "properties" to mapOf(
                    "device" to mapOf("type" to "string"), "nodeId" to mapOf("type" to "string", "default" to ""),
                    "offset" to mapOf("type" to "integer", "minimum" to 0, "default" to 0))),
                "TagDeviceBrowseResponse" to mapOf("type" to "object", "required" to listOf("device", "nodeId", "nodes", "more"), "properties" to mapOf(
                    "device" to mapOf("type" to "string"), "nodeId" to mapOf("type" to "string"), "offset" to mapOf("type" to "integer"),
                    "more" to mapOf("type" to "boolean"),
                    "nodes" to mapOf("type" to "array", "items" to mapOf("type" to "object", "required" to listOf("nodeId", "name", "nodeClass", "readable", "writable", "hasChildren"), "properties" to mapOf(
                        "nodeId" to mapOf("type" to "string", "description" to "Item path for a tag bound to this device"),
                        "name" to mapOf("type" to "string"), "nodeClass" to mapOf("type" to "string"),
                        "readable" to mapOf("type" to "boolean"), "writable" to mapOf("type" to "boolean"), "hasChildren" to mapOf("type" to "boolean"),
                        "value" to mapOf("description" to "Probe-read device value, when read"), "quality" to mapOf("type" to "string")))))),
                "HttpsCertificate" to mapOf("type" to "object", "required" to listOf("subject", "sha256Fingerprint", "source"), "properties" to mapOf(
                    "subject" to mapOf("type" to "string"),
                    "issuer" to mapOf("type" to "string"),
                    "notBefore" to mapOf("type" to "string", "format" to "date-time"),
                    "notAfter" to mapOf("type" to "string", "format" to "date-time"),
                    "sha256Fingerprint" to mapOf("type" to "string"),
                    "sans" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "source" to mapOf("type" to "string"))),
                "GatewayNetworkStatus" to mapOf("type" to "object", "properties" to mapOf(
                    "enabled" to mapOf("type" to "boolean"),
                    "listening" to mapOf("type" to "boolean"),
                    "port" to mapOf("type" to "integer"),
                    "gatewayName" to mapOf("type" to "string"),
                    "protocols" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "certificate" to ref("HttpsCertificate"),
                    "incoming" to mapOf("type" to "array", "items" to ref("GatewayPeer")),
                    "outgoing" to mapOf("type" to "array", "items" to ref("GatewayPeer")),
                    "policies" to mapOf("type" to "array", "items" to ref("ServicePolicyRequest")),
                    "providers" to mapOf("type" to "array", "items" to ref("RemoteProviderRequest")))),
                "RedundancyStatus" to mapOf("type" to "object", "properties" to mapOf(
                    "role" to mapOf("type" to "string", "enum" to listOf("Independent", "Master", "Backup")),
                    "activityLevel" to mapOf("type" to "string", "enum" to listOf("undecided", "cold", "warm", "active")),
                    "standbyActivityLevel" to mapOf("type" to "string", "enum" to listOf("cold", "warm")),
                    "connected" to mapOf("type" to "boolean"),
                    "peerId" to mapOf("type" to "string"),
                    "isActive" to mapOf("type" to "boolean"),
                    "isMaster" to mapOf("type" to "boolean"),
                    "recoveryMode" to mapOf("type" to "string"),
                    "historyMode" to mapOf("type" to "string"),
                    "useActiveUptime" to mapOf("type" to "boolean"),
                    "splitBrain" to mapOf("type" to "boolean"),
                    "configRevision" to mapOf("type" to "integer"),
                    "runtimeRevision" to mapOf("type" to "integer"),
                    "activeUptimeMillis" to mapOf("type" to "integer"),
                    "missedHeartbeats" to mapOf("type" to "integer"),
                    "historyPending" to mapOf("type" to "integer"),
                    "historyLogged" to mapOf("type" to "integer"),
                    "historyStore" to mapOf("type" to "string"),
                    "providers" to mapOf("type" to "array", "items" to mapOf("type" to "object")),
                    "events" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "activeWindows" to mapOf("type" to "array", "items" to mapOf("type" to "object")))),
                "EdgeSyncStream" to mapOf("type" to "object", "properties" to mapOf(
                    "enabled" to mapOf("type" to "boolean"),
                    "remoteName" to mapOf("type" to "string"),
                    "scheduleOpen" to mapOf("type" to "boolean"),
                    "pending" to mapOf("type" to "integer"),
                    "forwarded" to mapOf("type" to "integer"),
                    "lastError" to mapOf("type" to listOf("string", "null")))),
                "EdgeSyncStatus" to mapOf("type" to "object", "properties" to mapOf(
                    "remoteGateway" to mapOf("type" to "string"),
                    "linkUp" to mapOf("type" to "boolean"),
                    "history" to ref("EdgeSyncStream"),
                    "audit" to ref("EdgeSyncStream"),
                    "alarmJournal" to ref("EdgeSyncStream"),
                    "subscriptions" to mapOf("type" to "integer"))),
                "EdgeSyncHistory" to mapOf("type" to "object", "properties" to mapOf(
                    "local" to mapOf("type" to "array", "items" to mapOf("type" to "object")),
                    "remote" to mapOf("type" to "array", "items" to mapOf("type" to "object")))),
                "EdgeSyncAudit" to mapOf("type" to "object", "properties" to mapOf(
                    "remote" to mapOf("type" to "array", "items" to mapOf("type" to "object")))),
                "EdgeSyncAlarms" to mapOf("type" to "object", "properties" to mapOf(
                    "remote" to mapOf("type" to "array", "items" to mapOf("type" to "object")))),
                "GatewayPeer" to mapOf("type" to "object", "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "host" to mapOf("type" to "string"),
                    "port" to mapOf("type" to "integer"),
                    "direction" to mapOf("type" to "string"),
                    "status" to mapOf("type" to "string"),
                    "fingerprint" to mapOf("type" to "string"),
                    "subject" to mapOf("type" to "string"),
                    "certificateState" to mapOf("type" to "string"),
                    "state" to mapOf("type" to "string"),
                    "gatewayName" to mapOf("type" to "string"))),
                "OutgoingConnectionRequest" to mapOf("type" to "object", "required" to listOf("name", "host", "port"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "host" to mapOf("type" to "string"),
                    "port" to mapOf("type" to "integer"),
                    "enabled" to mapOf("type" to "boolean"),
                    "description" to mapOf("type" to "string"))),
                "ServicePolicyRequest" to mapOf("type" to "object", "required" to listOf("peer", "service", "access"), "properties" to mapOf(
                    "peer" to mapOf("type" to "string"),
                    "service" to mapOf("type" to "string"),
                    "access" to mapOf("type" to "string", "enum" to listOf("allow", "deny", "read")),
                    "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "RemoteProviderRequest" to mapOf("type" to "object", "required" to listOf("name", "connection"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "connection" to mapOf("type" to "string"),
                    "provider" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"))),
                "HttpsStatus" to mapOf("type" to "object", "properties" to mapOf(
                    "enabled" to mapOf("type" to "boolean"),
                    "listening" to mapOf("type" to "boolean"),
                    "httpPort" to mapOf("type" to "integer"),
                    "httpsPort" to mapOf("type" to "integer"),
                    "httpsListenPort" to mapOf("type" to "integer"),
                    "publicAddress" to mapOf("type" to "string"),
                    "publicHttpsPort" to mapOf("type" to "integer"),
                    "forceHttpsRedirect" to mapOf("type" to "boolean"),
                    "protocols" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "configuredProtocols" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "cipherSuites" to mapOf("type" to listOf("array", "null"), "items" to mapOf("type" to "string")),
                    "configuredCipherSuites" to mapOf("type" to listOf("array", "null"), "items" to mapOf("type" to "string")),
                    "availableCipherSuites" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "csr" to mapOf("type" to listOf("object", "null")),
                    "savedSettings" to mapOf("type" to listOf("object", "null")),
                    "certificate" to ref("HttpsCertificate"))),
                "HttpsCsr" to mapOf("type" to "object", "properties" to mapOf(
                    "subject" to mapOf("type" to "string"),
                    "sans" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "publicKeySha256" to mapOf("type" to "string"),
                    "csrPem" to mapOf("type" to "string"))),
                "HttpsCsrRequest" to mapOf("type" to "object", "required" to listOf("commonName"), "properties" to mapOf(
                    "commonName" to mapOf("type" to "string"),
                    "sans" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "organization" to mapOf("type" to "string"),
                    "organizationalUnit" to mapOf("type" to "string"),
                    "locality" to mapOf("type" to "string"),
                    "state" to mapOf("type" to "string"),
                    "country" to mapOf("type" to "string"),
                    "keyType" to mapOf("enum" to listOf("RSA", "EC")),
                    "keySize" to mapOf("enum" to listOf(2048, 3072, 4096)))),
                "HttpsPemUpload" to mapOf("type" to "object", "required" to listOf("format", "certificatePem", "privateKeyPem"), "properties" to mapOf(
                    "format" to mapOf("const" to "pem"),
                    "certificatePem" to mapOf("type" to "string", "writeOnly" to true),
                    "privateKeyPem" to mapOf("type" to "string", "writeOnly" to true))),
                "HttpsPkcs12Upload" to mapOf("type" to "object", "required" to listOf("format", "pkcs12Base64"), "properties" to mapOf(
                    "format" to mapOf("const" to "pkcs12"),
                    "pkcs12Base64" to mapOf("type" to "string", "writeOnly" to true),
                    "password" to mapOf("type" to "string", "writeOnly" to true))),
                "Health" to mapOf("type" to "object", "properties" to mapOf("status" to mapOf("type" to "string"),
                    "profile" to mapOf("type" to "string"), "version" to mapOf("type" to "string"), "modules" to mapOf("type" to "integer"),
                    "warnings" to mapOf("type" to "array", "items" to mapOf("type" to "string"),
                        "description" to "Operator-facing warnings, including a plain-HTTP session cookie that is not marked Secure."),
                    "startedAt" to mapOf("type" to "string", "format" to "date-time",
                        "description" to "ISO-8601 instant at which this gateway process started. Clients derive uptime from it."))),
                "ModuleInfo" to mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string"),
                    "version" to mapOf("type" to "string"), "name" to mapOf("type" to "string"), "state" to mapOf("type" to "string"),
                    "permissions" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "ModuleList" to mapOf("type" to "object", "properties" to mapOf(
                    "items" to mapOf("type" to "array", "items" to ref("ModuleInfo")),
                    "available" to mapOf("type" to "array", "description" to "Built-in modules that were uninstalled and can be installed again.",
                        "items" to mapOf("type" to "object", "properties" to mapOf("id" to mapOf("type" to "string"),
                            "version" to mapOf("type" to "string"), "name" to mapOf("type" to "string"), "builtIn" to mapOf("type" to "boolean")))))),
                "DeploymentMode" to mapOf("type" to "object", "required" to listOf("mode", "modes"), "properties" to mapOf(
                    "mode" to mapOf("type" to listOf("string", "null")),
                    "modes" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "BackupPassphrase" to mapOf("type" to "object", "required" to listOf("passphrase"), "additionalProperties" to false, "properties" to mapOf(
                    "passphrase" to mapOf("type" to "string", "minLength" to 1, "writeOnly" to true,
                        "description" to "At least 12 characters to create a backup. Never stored by the gateway."))),
                "BackupUpload" to mapOf("type" to "object", "required" to listOf("archive"), "properties" to mapOf(
                    "passphrase" to mapOf("type" to "string", "minLength" to 1, "writeOnly" to true),
                    "archive" to mapOf("type" to "string", "contentEncoding" to "base64"))),
                "BackupList" to mapOf("type" to "object", "required" to listOf("backups"), "properties" to mapOf(
                    "backups" to mapOf("type" to "array", "items" to mapOf("type" to "object", "required" to listOf("name", "bytes", "encrypted"), "properties" to mapOf(
                        "name" to mapOf("type" to "string"),
                        "bytes" to mapOf("type" to "integer"),
                        "encrypted" to mapOf("type" to "boolean", "description" to "false only for a plaintext .zip stored by an older version")))))),
                "GatewaySettings" to mapOf("type" to "object",
                    "required" to listOf("systemName", "effectiveSystemName", "scriptMessageEncoding", "scriptMessageEncodings", "launch", "version"),
                    "properties" to mapOf(
                        "systemName" to mapOf("type" to "string"),
                        "effectiveSystemName" to mapOf("type" to "string", "readOnly" to true),
                        "scriptMessageEncoding" to mapOf("type" to "string", "enum" to listOf("json")),
                        "scriptMessageEncodings" to mapOf("type" to "array", "readOnly" to true, "items" to mapOf("type" to "string")),
                        "launch" to mapOf("type" to "object", "properties" to mapOf("defaultProject" to mapOf("type" to "string"))),
                        "version" to mapOf("type" to "integer", "readOnly" to true))),
                "GatewaySettingsUpdate" to mapOf("type" to "object", "properties" to mapOf(
                    "systemName" to mapOf("type" to "string", "maxLength" to 63, "pattern" to "^[^|]*$"),
                    "scriptMessageEncoding" to mapOf("type" to "string", "enum" to listOf("json")),
                    "launch" to mapOf("type" to "object", "additionalProperties" to false, "properties" to mapOf(
                        "defaultProject" to mapOf("type" to "string", "pattern" to "^$|^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"))))),
                "DeploymentModeUpdate" to mapOf("type" to "object", "required" to listOf("mode"), "properties" to mapOf(
                    "mode" to mapOf("type" to listOf("string", "null"), "pattern" to "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"))),
                "User" to mapOf("type" to "object", "required" to listOf("username", "enabled", "roles"), "properties" to mapOf(
                    "username" to mapOf("type" to "string"),
                    "displayName" to mapOf("type" to "string"),
                    "email" to mapOf("type" to "string"),
                    "phone" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"),
                    "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "passwordChangedAt" to mapOf("type" to "string", "format" to "date-time"),
                    "passwordExpired" to mapOf("type" to "boolean"),
                    "version" to mapOf("type" to "integer"))),
                "UserCreate" to mapOf("type" to "object", "required" to listOf("username", "password"), "properties" to mapOf(
                    "username" to mapOf("type" to "string"),
                    "password" to mapOf("type" to "string", "writeOnly" to true),
                    "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "displayName" to mapOf("type" to "string"),
                    "email" to mapOf("type" to "string"),
                    "phone" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"))),
                "UserUpdate" to mapOf("type" to "object", "properties" to mapOf(
                    "displayName" to mapOf("type" to "string"),
                    "email" to mapOf("type" to "string"),
                    "phone" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"),
                    "roles" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "PasswordChange" to mapOf("type" to "object", "required" to listOf("password"), "properties" to mapOf(
                    "password" to mapOf("type" to "string", "writeOnly" to true))),
                "UserList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("User")))),
                "Role" to mapOf("type" to "object", "required" to listOf("name", "permissions"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "permissions" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "version" to mapOf("type" to "integer"))),
                "RoleCreate" to mapOf("type" to "object", "required" to listOf("name", "permissions"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "permissions" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "RoleUpdate" to mapOf("type" to "object", "properties" to mapOf(
                    "description" to mapOf("type" to "string"),
                    "permissions" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
                "RoleList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("Role")))),
                "ApiKey" to mapOf("type" to "object", "required" to listOf("name", "enabled", "keyId", "scopes"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "enabled" to mapOf("type" to "boolean"),
                    "keyId" to mapOf("type" to "string"),
                    "scopes" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "secureConnectionRequired" to mapOf("type" to "boolean"),
                    "createdAt" to mapOf("type" to "string", "format" to "date-time"),
                    "version" to mapOf("type" to "integer"))),
                "ApiKeyIssued" to mapOf("allOf" to listOf(ref("ApiKey"), mapOf("type" to "object", "required" to listOf("secret"), "properties" to mapOf(
                    "secret" to mapOf("type" to "string", "description" to "Returned only on create and rotate"))))),
                "ApiKeyCreate" to mapOf("type" to "object", "required" to listOf("name"), "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string"),
                    "scopes" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "secureConnectionRequired" to mapOf("type" to "boolean"))),
                "ApiKeyList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("ApiKey")))),
            ) + dbSchemas() + c7Schemas() + m54Schemas() + transactionGroupSchemas() + idpSchemas()))
    }

    private fun m54Paths(): Map<String, Any?> = buildMap {
        put("/api/v1/sfc/charts", mapOf(
            "get" to op(
                "summary" to "List sequential function charts. Requires tags.read.",
                "responses" to mapOf("200" to json(ref("ChartList"), "Charts")) + errors,
            ),
        ))
        put("/api/v1/sfc/charts/{name}/start", mapOf("parameters" to listOf(p("name")),
            "post" to op(
                "summary" to "Start a callable chart. Requires tags.write.",
                "parameters" to listOf(csrf),
                "requestBody" to mapOf("required" to false, "content" to mapOf("application/json" to mapOf("schema" to ref("ChartStart")))),
                "responses" to mapOf("201" to json(ref("ChartInstance"), "Started")) + errors,
            ),
        ))
        put("/api/v1/sfc/instances", mapOf(
            "get" to op(
                "summary" to "List chart instances, including active steps. Requires tags.read.",
                "responses" to mapOf("200" to json(ref("ChartInstanceList"), "Instances")) + errors,
            ),
        ))
        put("/api/v1/sfc/instances/{id}", mapOf("parameters" to listOf(p("id")),
            "get" to op(
                "summary" to "Get one chart instance. Requires tags.read.",
                "responses" to mapOf("200" to json(ref("ChartInstance"), "Instance")) + errors,
            ),
        ))
        for (action in listOf("pause", "resume", "cancel")) {
            put("/api/v1/sfc/instances/{id}/$action", mapOf("parameters" to listOf(p("id")),
                "post" to op(
                    "summary" to "${action.replaceFirstChar { it.uppercase() }} a chart instance. Requires tags.write.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(ref("ChartInstance"), "Updated")) + errors,
                ),
            ))
        }
        put("/api/v1/event-streams", mapOf(
            "get" to op(
                "summary" to "List event streams and metrics. Requires gateway.read.",
                "responses" to mapOf("200" to json(ref("EventStreamList"), "Streams")) + errors,
            ),
        ))
        put("/api/v1/event-streams/{name}", mapOf("parameters" to listOf(p("name")),
            "get" to op(
                "summary" to "Get one event stream and its metrics. Requires gateway.read.",
                "responses" to mapOf("200" to json(ref("EventStream"), "Stream")) + errors,
            ),
        ))
        put("/system/eventstream/{name}", mapOf("parameters" to listOf(p("name")),
            "post" to op(
                "summary" to "Publish to an HTTP event-stream source. Returns 429 when the buffer drops the event.",
                "description" to "Authentication and HTTPS follow the source resource.",
                "parameters" to listOf(csrf),
                "requestBody" to mapOf("required" to false, "content" to mapOf("application/json" to mapOf("schema" to mapOf("description" to "Event payload")))),
                "responses" to mapOf(
                    "202" to json(ref("EventAccepted"), "Accepted"),
                    "429" to problem("Buffer full"),
                ) + errors,
                public = true,
            ),
            "put" to op(
                "summary" to "Publish to an HTTP event-stream source. Returns 429 when the buffer drops the event.",
                "parameters" to listOf(csrf),
                "requestBody" to mapOf("required" to false, "content" to mapOf("application/json" to mapOf("schema" to mapOf("description" to "Event payload")))),
                "responses" to mapOf(
                    "202" to json(ref("EventAccepted"), "Accepted"),
                    "429" to problem("Buffer full"),
                ) + errors,
                public = true,
            ),
        ))
        put("/system/webdev/{path}", mapOf("parameters" to listOf(p("path")),
            "get" to op(
                "summary" to "Serve a text, static Python, or mounted-folder web dev resource.",
                "description" to "A resource can require a session or HTTPS.",
                "responses" to mapOf(
                    "200" to mapOf("description" to "Resource body"),
                    "501" to problem("Python resource has no script host"),
                ) + errors,
                public = true,
            ),
        ))
    }

    /** SEC-013, SEC-014, SEC-027: identity-provider login, the provider registry, and the user source check. */
    private fun idpPaths(): Map<String, Any?> = buildMap {
        val name = p("name")
        val redirect = mapOf("description" to "Redirect (Location header)")
        put("/api/v1/auth/providers", mapOf("get" to op(
            "summary" to "Enabled identity providers, for the sign-in page. No authentication.",
            "responses" to mapOf("200" to json(ref("LoginProviderList"), "Providers")),
            public = true)))
        put("/api/v1/auth/providers/{name}/login", mapOf("parameters" to listOf(name), "get" to op(
            "summary" to "Start a provider login. OIDC: authorization code with PKCE S256, state and nonce. SAML: AuthnRequest over HTTP-Redirect. No authentication.",
            "parameters" to listOf(p("returnTo", "query")),
            "responses" to mapOf("302" to redirect, "404" to problem("No such enabled provider")),
            public = true)))
        put("/api/v1/auth/providers/{name}/callback", mapOf("parameters" to listOf(name), "get" to op(
            "summary" to "OIDC redirect back. Checks state, browser binding, PKCE exchange, ID token signature, issuer, audience, expiry and nonce, then sets the session cookie. A refused login redirects to /admin/login?error=idp. Audited either way. No authentication.",
            "parameters" to listOf(p("code", "query"), p("state", "query")),
            "responses" to mapOf("302" to redirect),
            public = true)))
        put("/api/v1/auth/providers/{name}/acs", mapOf("parameters" to listOf(name), "post" to op(
            "summary" to "SAML assertion consumer (HTTP-POST binding). Checks the signature against the configured certificates, issuer, audience, recipient, request, and time, then sets the session cookie. Audited either way. No authentication.",
            "requestBody" to mapOf("required" to true, "content" to mapOf("application/x-www-form-urlencoded" to mapOf("schema" to mapOf(
                "type" to "object", "properties" to mapOf("SAMLResponse" to mapOf("type" to "string"), "RelayState" to mapOf("type" to "string")))))),
            "responses" to mapOf("302" to redirect),
            public = true)))
        put(IdentityProviderApi.BASE, mapOf(
            "get" to op("summary" to "List identity providers. Requires users.admin. No secret or secret reference is returned.",
                "responses" to mapOf("200" to json(ref("IdentityProviderList"), "Providers")) + errors),
            "post" to op("summary" to "Create an OIDC or SAML identity provider. Requires users.admin. clientSecret is stored in the secret store, never in the resource or a response.",
                "parameters" to listOf(csrf), "requestBody" to body("IdentityProviderWrite"),
                "responses" to mapOf("201" to json(ref("IdentityProvider"), "Created")) + errors),
        ))
        put("${IdentityProviderApi.BASE}/import", mapOf("post" to op(
            "summary" to "Read an OIDC discovery document (issuer) or SAML metadata (metadataXml or metadataUrl) and return the provider fields it fills. Invalid metadata is 422. Requires users.admin.",
            "parameters" to listOf(csrf), "requestBody" to body("IdentityProviderImport"),
            "responses" to mapOf("200" to json(mapOf("type" to "object", "properties" to mapOf("fields" to mapOf("type" to "object"))), "Fields")) + errors)))
        put("${IdentityProviderApi.BASE}/{name}", mapOf("parameters" to listOf(name),
            "get" to op("summary" to "Get an identity provider. Requires users.admin.", "responses" to mapOf("200" to json(ref("IdentityProvider"), "Provider")) + errors),
            "put" to op("summary" to "Replace an identity provider. Requires users.admin. Omit clientSecret to keep it; an empty clientSecret removes it. Disabling a provider ends its sessions.",
                "parameters" to listOf(ifMatch, csrf), "requestBody" to body("IdentityProviderWrite"),
                "responses" to mapOf("200" to json(ref("IdentityProvider"), "Updated")) + errors),
            "delete" to op("summary" to "Delete an identity provider, its stored secret and its sessions. Requires users.admin.",
                "parameters" to listOf(ifMatch, csrf), "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors),
        ))
        put(UserSourceApi.BASE, mapOf(
            "get" to op("summary" to "List LDAP, database and hybrid user sources, and the system user source gateway logins use. Requires users.admin. No bind password or reference to it is returned.",
                "responses" to mapOf("200" to json(ref("UserSourceList"), "User sources")) + errors),
            "post" to op("summary" to "Create a user source. Requires users.admin. bindPassword is stored in the secret store, never in the resource or a response.",
                "parameters" to listOf(csrf), "requestBody" to body("UserSourceWrite"),
                "responses" to mapOf("201" to json(ref("UserSource"), "Created")) + errors),
        ))
        put("${UserSourceApi.BASE}/{name}", mapOf("parameters" to listOf(name),
            "get" to op("summary" to "Get a user source. Requires users.admin.", "responses" to mapOf("200" to json(ref("UserSource"), "User source")) + errors),
            "put" to op("summary" to "Replace a user source. Requires users.admin. Omit bindPassword to keep it; an empty bindPassword removes it. Disabling a source, or changing how it gives roles, ends its sessions.",
                "parameters" to listOf(ifMatch, csrf), "requestBody" to body("UserSourceWrite"),
                "responses" to mapOf("200" to json(ref("UserSource"), "Updated")) + errors),
            "delete" to op("summary" to "Delete a user source, its stored bind password and its sessions. 409 while it is the system user source. Requires users.admin.",
                "parameters" to listOf(ifMatch, csrf), "responses" to mapOf("204" to mapOf("description" to "Deleted")) + errors),
        ))
        put("/api/v1/security/user-sources/{source}/check", mapOf("parameters" to listOf(p("source")), "post" to op(
            "summary" to "Verify a user on a user source (internal, or an LDAP, database or hybrid source): exists, roles, and whether a password matches. An external source also returns the groups or role values it read, and why it could not be asked. Audited; the password is never recorded or returned. Requires users.admin.",
            "parameters" to listOf(csrf), "requestBody" to body("UserCheckRequest"),
            "responses" to mapOf("200" to json(ref("UserCheck"), "Result")) + errors)))
    }

    private fun idpSchemas(): Map<String, Any?> {
        val str = mapOf("type" to "string")
        val strs = mapOf("type" to "array", "items" to str)
        val fields = linkedMapOf<String, Any?>(
            "type" to mapOf("type" to "string", "enum" to listOf("oidc", "saml")),
            "displayName" to str, "enabled" to mapOf("type" to "boolean"), "redirectBaseUrl" to str,
            "usernameClaim" to str, "rolesClaim" to str,
            "roleMappings" to mapOf("type" to "array", "items" to mapOf("type" to "object", "properties" to mapOf("value" to str, "roles" to strs))),
            "defaultRoles" to strs, "clockSkewSeconds" to mapOf("type" to "integer"),
            "issuer" to str, "authorizationEndpoint" to str, "tokenEndpoint" to str, "jwksUri" to str, "endSessionEndpoint" to str,
            "clientId" to str, "scopes" to strs, "apiAudience" to str, "allowedAlgorithms" to strs,
            "idpEntityId" to str, "ssoUrl" to str, "sloUrl" to str, "spEntityId" to str, "signingCertificates" to strs,
        )
        @Suppress("UNCHECKED_CAST")
        val sourceFields = (org.oip.security.UserSourceSchema.schema()["properties"] as Map<String, Any?>) - "bindPasswordRef"
        return mapOf(
            "LoginProviderList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to mapOf(
                "type" to "object", "properties" to mapOf("name" to str, "displayName" to str, "type" to str, "loginPath" to str))))),
            "IdentityProvider" to mapOf("type" to "object", "properties" to linkedMapOf<String, Any?>("name" to str) + fields + mapOf(
                "clientSecretSet" to mapOf("type" to "boolean"), "callbackPath" to str, "version" to mapOf("type" to "integer"))),
            "IdentityProviderList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("IdentityProvider")))),
            "IdentityProviderWrite" to mapOf("type" to "object", "properties" to linkedMapOf<String, Any?>("name" to str) + fields + mapOf(
                "clientSecret" to mapOf("type" to "string", "writeOnly" to true))),
            "IdentityProviderImport" to mapOf("type" to "object", "required" to listOf("type"), "properties" to mapOf(
                "type" to mapOf("type" to "string", "enum" to listOf("oidc", "saml")), "issuer" to str, "metadataXml" to str, "metadataUrl" to str)),
            "UserCheckRequest" to mapOf("type" to "object", "required" to listOf("username"), "properties" to mapOf(
                "username" to str, "password" to mapOf("type" to "string", "writeOnly" to true))),
            "UserCheck" to mapOf("type" to "object", "properties" to mapOf(
                "source" to str, "username" to str, "exists" to mapOf("type" to "boolean"), "enabled" to mapOf("type" to listOf("boolean", "null")),
                "roles" to strs, "passwordChecked" to mapOf("type" to "boolean"), "passwordValid" to mapOf("type" to listOf("boolean", "null")),
                "displayName" to mapOf("type" to listOf("string", "null")), "email" to mapOf("type" to listOf("string", "null")),
                "groups" to strs, "dn" to mapOf("type" to listOf("string", "null")), "unavailable" to mapOf("type" to listOf("string", "null")))),
            "UserSource" to mapOf("type" to "object", "properties" to linkedMapOf<String, Any?>("name" to str) + sourceFields + mapOf(
                "bindPasswordSet" to mapOf("type" to "boolean"), "inUse" to mapOf("type" to "boolean"), "version" to mapOf("type" to "integer"))),
            "UserSourceList" to mapOf("type" to "object", "properties" to mapOf(
                "items" to mapOf("type" to "array", "items" to ref("UserSource")), "systemUserSource" to str)),
            "UserSourceWrite" to mapOf("type" to "object", "properties" to linkedMapOf<String, Any?>("name" to str) + sourceFields + mapOf(
                "bindPassword" to mapOf("type" to "string", "writeOnly" to true))),
        )
    }

    private fun m54Schemas(): Map<String, Any?> = mapOf(
        "ChartList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("ChartSummary")))),
        "ChartSummary" to mapOf("type" to "object", "properties" to mapOf(
            "name" to mapOf("type" to "string"),
            "executionMode" to mapOf("type" to "string"),
            "valid" to mapOf("type" to "boolean"),
        )),
        "ChartStart" to mapOf("type" to "object", "properties" to mapOf(
            "parameters" to mapOf("type" to "object", "additionalProperties" to true),
        )),
        "ChartInstanceList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("ChartInstance")))),
        "ChartInstance" to mapOf("type" to "object", "properties" to mapOf(
            "id" to mapOf("type" to "string"),
            "chart" to mapOf("type" to "string"),
            "state" to mapOf("type" to "string"),
            "stateCode" to mapOf("type" to "integer"),
            "activeSteps" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "variables" to mapOf("type" to "object", "additionalProperties" to true),
            "startedAt" to mapOf("type" to "string"),
            "runningSeconds" to mapOf("type" to "integer"),
            "abortCause" to mapOf("type" to "string"),
        )),
        "EventStreamList" to mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to ref("EventStream")))),
        "EventStream" to mapOf("type" to "object", "properties" to mapOf(
            "name" to mapOf("type" to "string"),
            "enabled" to mapOf("type" to "boolean"),
            "source" to mapOf("type" to "string"),
            "valid" to mapOf("type" to "boolean"),
            "queued" to mapOf("type" to "integer"),
            "eventsReceived" to mapOf("type" to "integer"),
            "eventsDropped" to mapOf("type" to "integer"),
            "eventsFiltered" to mapOf("type" to "integer"),
            "handlerExecutions" to mapOf("type" to "integer"),
            "handlerErrors" to mapOf("type" to "integer"),
            "sourceConnected" to mapOf("type" to listOf("boolean", "null"), "description" to "Kafka sources only: whether the consumer is open"),
            "sourceError" to mapOf("type" to listOf("string", "null"), "description" to "Kafka sources only: the last connect or poll failure"),
        )),
        "EventAccepted" to mapOf("type" to "object", "required" to listOf("accepted"), "properties" to mapOf(
            "accepted" to mapOf("type" to "boolean"),
        )),
    )
    // Transaction groups (C-10).
    private fun transactionGroupPaths(): Map<String, Any?> = buildMap {
        put("/api/v1/db/groups", mapOf(
            "get" to op("summary" to "List transaction groups with their run status. Requires gateway.read.",
                "description" to "Groups are created and edited through the config API as db-groups/gateway/TransactionGroup resources.",
                "responses" to mapOf("200" to json(ref("TransactionGroupList"), "Groups")) + errors),
        ))
        put("/api/v1/db/groups/{name}", mapOf("parameters" to listOf(p("name")),
            "get" to op("summary" to "Status of one transaction group. Requires gateway.read.",
                "responses" to mapOf("200" to json(ref("TransactionGroupStatus"), "Status")) + errors),
        ))
        for (action in listOf("enable", "disable")) {
            put("/api/v1/db/groups/{name}/$action", mapOf("parameters" to listOf(p("name")),
                "post" to op("summary" to "${action.replaceFirstChar { it.uppercase() }} a transaction group. Requires gateway.write. Writes the resource's enabled flag (one config audit row); no write when it is already in that state.",
                    "parameters" to listOf(csrf),
                    "responses" to mapOf("200" to json(ref("TransactionGroupStatus"), "Status after the change")) + errors),
            ))
        }
    }

    private fun transactionGroupSchemas(): Map<String, Any?> = mapOf(
        "TransactionGroupStatus" to mapOf("type" to "object", "required" to listOf("name", "state", "enabled"), "properties" to mapOf(
            "name" to mapOf("type" to "string"),
            "version" to mapOf("type" to "integer"),
            "enabled" to mapOf("type" to "boolean"),
            "state" to mapOf("type" to "string", "enum" to listOf("Running", "Stopped", "Disabled", "Standby", "Error", "Invalid"),
                "description" to "Standby: this node is not the active node of a redundant pair, so the group does not run"),
            "groupType" to mapOf("type" to listOf("string", "null"), "enum" to listOf("standard", "historical", "block", "storedProcedure", null)),
            "updateMode" to mapOf("type" to listOf("string", "null")),
            "tableAction" to mapOf("type" to listOf("string", "null")),
            "connection" to mapOf("type" to listOf("string", "null")),
            "table" to mapOf("type" to listOf("string", "null")),
            "rateMillis" to mapOf("type" to listOf("integer", "null")),
            "trigger" to mapOf("type" to listOf("string", "null"), "description" to "Trigger tag path, when the group uses one"),
            "lastRunMillis" to mapOf("type" to listOf("integer", "null"), "description" to "Epoch milliseconds of the last execution"),
            "lastDurationMillis" to mapOf("type" to listOf("integer", "null")),
            "rowsWritten" to mapOf("type" to "integer", "description" to "Rows inserted or updated by the last execution"),
            "totalRowsWritten" to mapOf("type" to "integer"),
            "executions" to mapOf("type" to "integer"),
            "errors" to mapOf("type" to "integer"),
            "error" to mapOf("type" to listOf("string", "null"), "description" to "Last failure; cleared by the next successful run"),
            "errorMillis" to mapOf("type" to listOf("integer", "null")),
            "warning" to mapOf("type" to listOf("string", "null")),
        )),
        "TransactionGroupList" to mapOf("type" to "object", "properties" to mapOf(
            "items" to mapOf("type" to "array", "items" to ref("TransactionGroupStatus")))),
    )
}
