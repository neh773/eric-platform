package org.oip.gateway

import org.oip.security.SecurityLevels

/**
 * Security requirements inside an HMI project (SEC-017, HMP-092), applied on the gateway.
 *
 * A requirement is a `security` object with any of:
 * - `roles`: the caller needs one of these gateway roles (P4-04);
 * - `levels`: security level paths; the caller needs one of them, or every one with `requireAll: true`.
 *   Levels are hierarchical: a caller holding `Authenticated/Roles/Operator` also holds `Authenticated/Roles`,
 *   `Authenticated` and `Public`, so a requirement of a parent level is met by any level below it.
 *
 * Where a requirement can stand:
 * - a project's settings (`sessionLevels`, `sessionRequireAll`): the whole session;
 * - a view document (`security`): the view; a refused view is sent by path only;
 * - a component or container in a view document (`security`): that node and everything inside it. A refused node is
 *   removed from the document before it is sent, so its props, bindings, tag paths and event scripts never reach the
 *   browser, and a script hook defined inside it is refused when a session names it;
 * - a project script hook (`hooks.<hook>.security` in the project's script hooks): that hook.
 *
 * A requirement that cannot be read (a `levels` entry that is not text, `requireAll` that is not a boolean) is
 * treated as unmet: a level path nobody holds, and `requireAll` true.
 *
 * Doc source: FEATURES SEC-017.
 */
object HmiSecurity {
    data class Requirement(val roles: List<String>, val levels: List<String>, val requireAll: Boolean) {
        val empty: Boolean get() = roles.isEmpty() && levels.isEmpty()

        fun allows(viewer: HmiProjectApi.Viewer): Boolean {
            if (roles.isNotEmpty() && roles.none { it in viewer.roles }) return false
            return SecurityLevels.satisfies(viewer.levels, levels, requireAll)
        }

        fun describe(): String = buildList {
            if (roles.isNotEmpty()) add("one of the roles ${roles.joinToString(", ")}")
            if (levels.isNotEmpty()) add("${if (requireAll && levels.size > 1) "every one of" else "one of"} the security levels ${levels.joinToString(", ")}")
        }.joinToString(" and ")
    }

    /** The requirement of a `security` object, or null when it names none. */
    fun requirement(security: Any?): Requirement? {
        val map = security as? Map<*, *> ?: return null
        val roles = (map["roles"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } } ?: emptyList()
        val levels = when (val raw = map["levels"]) {
            null -> emptyList()
            is List<*> -> raw.mapNotNull { v -> if (v is String) v.trim().ifEmpty { null } else UNREADABLE }
            else -> listOf(UNREADABLE)
        }
        val requireAll = when (val raw = map["requireAll"]) {
            null -> false
            is Boolean -> raw
            else -> true
        }
        return Requirement(roles, levels, requireAll).takeIf { !it.empty }
    }

    /** Whether [node] (a view document node) may be sent to [viewer]. */
    fun nodeAllowed(node: Map<*, *>, viewer: HmiProjectApi.Viewer): Boolean =
        requirement(node["security"])?.allows(viewer) ?: true

    /**
     * [document] without the component and container nodes [viewer] may not see. A node is a map with `kind`
     * `component` or `container`; a refused node inside a list is left out of the list, and one held under a key
     * drops that key. Everything else is copied as is.
     */
    fun strip(document: Map<String, Any?>, viewer: HmiProjectApi.Viewer): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return walk(document, viewer, 0) as? Map<String, Any?> ?: emptyMap()
    }

    private fun isNode(value: Any?): Boolean = value is Map<*, *> && (value["kind"] == "component" || value["kind"] == "container")

    private fun walk(value: Any?, viewer: HmiProjectApi.Viewer, depth: Int): Any? {
        if (depth > MAX_DEPTH) return null
        return when (value) {
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in value) {
                    if (isNode(v) && !nodeAllowed(v as Map<*, *>, viewer)) continue
                    out[k.toString()] = walk(v, viewer, depth + 1)
                }
                out
            }
            is List<*> -> value.filter { !(isNode(it) && !nodeAllowed(it as Map<*, *>, viewer)) }.map { walk(it, viewer, depth + 1) }
            else -> value
        }
    }

    /** Every `{kind: script, hook}` entry of [document] with the requirements of the nodes that hold it, outermost first. */
    fun hookRequirements(document: Any?): Map<String, List<Requirement>> {
        val out = LinkedHashMap<String, List<Requirement>>()
        collect(document, emptyList(), out, 0)
        return out
    }

    private fun collect(value: Any?, chain: List<Requirement>, out: MutableMap<String, List<Requirement>>, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (value) {
            is Map<*, *> -> {
                val here = if (isNode(value)) requirement(value["security"])?.let { chain + it } ?: chain else chain
                val hook = value["hook"]
                if (value["kind"] == "script" && hook is String && value["source"] is String) out.putIfAbsent(hook, here)
                for (v in value.values) collect(v, here, out, depth + 1)
            }
            is List<*> -> for (v in value) collect(v, chain, out, depth + 1)
        }
    }

    /** A level path nobody can hold, standing in for a requirement entry that cannot be read. */
    private const val UNREADABLE = "\u0000unreadable"
    private const val MAX_DEPTH = 64
}
