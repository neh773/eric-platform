package org.oip.gateway

import io.javalin.Javalin
import org.oip.config.FileConfigStore
import org.oip.config.GATEWAY_MODULE
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.ResourceId
import org.oip.config.SchemaRegistry
import org.oip.config.Scope
import org.oip.config.ValidationException
import java.net.InetAddress

/**
 * Gateway-wide settings (GAT-035): one config resource, `org.oip.gateway/gateway/GatewaySettings/gateway`, read and
 * written through the config API or through `/api/v1/gateway/settings`, which returns the effective values with
 * defaults filled in. A write goes through the config store, so it is validated against the schema below and
 * recorded as one config-change audit record.
 *
 * - `systemName`: the name this gateway shows (admin overview, system tag `Gateway/SystemName`). Blank means the
 *   host name. A `|` is refused, as the public page describes.
 * - `scriptMessageEncoding`: the format of script message payloads between clients, designer and gateway. Only
 *   `json` exists here; there is no legacy object-serialization format to opt into (DIFFERENCES 235).
 * - `launch.defaultProject`: the project an HMI client opens when it is launched at `/hmi` with no project named.
 *   Launch options for a desktop client runtime (memory, rendering pipeline, fonts) do not apply to a browser client
 *   (DIFFERENCES 235).
 *
 * Doc source: FEATURES GAT-035.
 */
data class PlatformSettings(
    val systemName: String = "",
    val scriptMessageEncoding: String = ENCODING_JSON,
    val defaultProject: String = "",
) {
    fun toSpec(): Map<String, Any?> = linkedMapOf(
        "systemName" to systemName,
        "scriptMessageEncoding" to scriptMessageEncoding,
        "launch" to linkedMapOf("defaultProject" to defaultProject),
    )

    companion object {
        const val KIND = "GatewaySettings"
        const val NAME = "gateway"
        const val ENCODING_JSON = "json"
        val ID = ResourceId(GATEWAY_MODULE, Scope.GATEWAY, KIND, NAME)

        fun schema(): Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "systemName" to mapOf("type" to "string", "maxLength" to 63, "pattern" to "^[^|]*$", "default" to ""),
                "scriptMessageEncoding" to mapOf("type" to "string", "enum" to listOf(ENCODING_JSON), "default" to ENCODING_JSON),
                "launch" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "defaultProject" to mapOf("type" to "string", "maxLength" to 63, "default" to ""),
                    ),
                    "default" to mapOf<String, Any?>(),
                ),
            ),
        )

        /** A non-blank default project must be a valid project name. JSON Schema cannot say "empty or a name". */
        fun semantic(spec: Map<String, Any?>): List<String> {
            val project = (spec["launch"] as? Map<*, *>)?.get("defaultProject") as? String ?: return emptyList()
            val valid = project.isEmpty() || runCatching { Scope.project(project) }.isSuccess
            return if (valid) emptyList()
            else listOf("launch.defaultProject '$project' is not a project name (lowercase letters, digits and '-', 1 to 63 characters)")
        }

        fun register(registry: SchemaRegistry) {
            registry.register(GATEWAY_MODULE, KIND, schema())
            registry.registerSemantic(GATEWAY_MODULE, KIND, ::semantic)
        }

        fun fromSpec(spec: Map<String, Any?>?): PlatformSettings {
            if (spec == null) return PlatformSettings()
            val launch = spec["launch"] as? Map<*, *>
            return PlatformSettings(
                systemName = spec["systemName"] as? String ?: "",
                scriptMessageEncoding = spec["scriptMessageEncoding"] as? String ?: ENCODING_JSON,
                defaultProject = launch?.get("defaultProject") as? String ?: "",
            )
        }
    }
}

/** Reads and writes [PlatformSettings] in [store]. */
class PlatformSettingsService(
    private val store: FileConfigStore,
    private val hostName: () -> String = { defaultHostName() },
) {
    fun get(): PlatformSettings = PlatformSettings.fromSpec(store.get(PlatformSettings.ID)?.spec)

    /** [PlatformSettings.systemName], or the host name when it is blank. */
    fun effectiveSystemName(): String = get().systemName.trim().ifEmpty { hostName() }

    /** Store [next]. Throws [ValidationException] for a value the schema refuses. */
    fun update(next: PlatformSettings, actor: String): PlatformSettings {
        val version = store.get(PlatformSettings.ID)?.metadata?.version ?: 0L
        val stored = store.put(
            Scope.GATEWAY,
            Resource(PlatformSettings.KIND, Metadata(PlatformSettings.NAME, GATEWAY_MODULE, version = version), next.toSpec()),
            actor,
        )
        return PlatformSettings.fromSpec(stored.spec)
    }

    fun document(): Map<String, Any?> {
        val s = get()
        return linkedMapOf(
            "systemName" to s.systemName,
            "effectiveSystemName" to effectiveSystemName(),
            "scriptMessageEncoding" to s.scriptMessageEncoding,
            "scriptMessageEncodings" to listOf(PlatformSettings.ENCODING_JSON),
            "launch" to linkedMapOf("defaultProject" to s.defaultProject),
            "version" to (store.get(PlatformSettings.ID)?.metadata?.version ?: 0L),
        )
    }

    /**
     * `GET /api/v1/gateway/settings` (gateway.read) and `PUT /api/v1/gateway/settings` (gateway.write). PUT takes the
     * full document (`systemName`, `scriptMessageEncoding`, `launch.defaultProject`); a missing field keeps its default.
     */
    fun register(app: Javalin) {
        app.get(PATH) { ctx -> ctx.contentType("application/json").result(Json.write(document())) }
        app.put(PATH) { ctx ->
            val body = try {
                Json.mapper.readValue(ctx.body(), Any::class.java)
            } catch (e: com.fasterxml.jackson.core.JacksonException) {
                throw Problems.badRequest("malformed JSON: ${e.originalMessage}")
            } as? Map<*, *> ?: throw Problems.unprocessable("body must be an object")
            val unknown = body.keys.map { it.toString() } - setOf("systemName", "scriptMessageEncoding", "launch", "effectiveSystemName", "scriptMessageEncodings", "version")
            if (unknown.isNotEmpty()) throw Problems.unprocessable("unknown settings: ${unknown.sorted().joinToString()}")
            val launch = when (val raw = body["launch"]) {
                null -> emptyMap<Any?, Any?>()
                is Map<*, *> -> raw
                else -> throw Problems.unprocessable("launch must be an object")
            }
            val unknownLaunch = launch.keys.map { it.toString() } - setOf("defaultProject")
            if (unknownLaunch.isNotEmpty()) throw Problems.unprocessable("unknown launch settings: ${unknownLaunch.sorted().joinToString()}")
            val next = PlatformSettings(
                systemName = text(body["systemName"], "systemName"),
                scriptMessageEncoding = body["scriptMessageEncoding"]?.let { text(it, "scriptMessageEncoding") } ?: PlatformSettings.ENCODING_JSON,
                defaultProject = text(launch["defaultProject"], "launch.defaultProject"),
            )
            try {
                update(next, org.oip.security.AuditContext.current()?.actor ?: ConfigApi.ACTOR)
            } catch (e: ValidationException) {
                throw Problems.unprocessable(e.message ?: "invalid gateway settings")
            }
            ctx.contentType("application/json").result(Json.write(document()))
        }
    }

    private fun text(value: Any?, field: String): String = when (value) {
        null -> ""
        is String -> value
        else -> throw Problems.unprocessable("$field must be a string")
    }

    companion object {
        const val PATH = "/api/v1/gateway/settings"

        fun defaultHostName(): String =
            runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "gateway"
    }
}
