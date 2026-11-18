package org.oip.tags

import org.oip.security.SecurityZone
import org.oip.security.Subject

/**
 * Who is reading or writing a tag (TAG-013). Engine calls that do not pass one use [System].
 *
 * [System] is gateway-internal code: drivers, expression and reference evaluation, scripts, other
 * modules. It skips role and zone checks but still cannot write a read-only tag, and its writes are
 * not audited. [User] is a person or API key acting through an outside interface (REST, OPC UA,
 * an HMI session); its reads and writes are checked against the tag's permissions and every write
 * it attempts is audited once.
 */
sealed class TagCaller {
    object System : TagCaller()

    data class User(
        val actor: String,
        /** Lowercase role names (`gateway/security` role resource names). */
        val roles: Set<String>,
        /** Security zone the request matched, see [org.oip.security.ZoneDirectory.match]. */
        val zone: String,
        val host: String = "",
        /**
         * The caller holds the permission that edits tag configuration (`gateway.write` in its zone). Only
         * [TagEngine.mayRead] on a path with no configured tag looks at it: history and journal rows of a
         * deleted or renamed tag are readable by such a caller and refused to everyone else (fail closed).
         */
        val configAdmin: Boolean = false,
        /**
         * The caller holds the gateway `tags.write` permission in its zone. [TagEngine.write] refuses a user write
         * without it (`Bad_UserAccessDenied`, audited) before the tag's own `writePermissions` are looked at.
         * REST and HMI writes are already gated on `tags.write` per request and leave it true; the OPC UA server
         * sets it from the user directory when a session activates (review-2 F7).
         */
        val tagWrite: Boolean = true,
    ) : TagCaller()

    companion object {
        /**
         * A user's roles, or none for an API key (API keys carry permission scopes, not roles, so
         * they pass only tags whose permissions list no roles). A disabled subject gets no roles.
         */
        fun of(subject: Subject, zone: SecurityZone, host: String = "", configAdmin: Boolean = false, tagWrite: Boolean = true): User {
            val roles = when (subject) {
                is Subject.User -> if (subject.enabled) subject.roles else emptySet()
                is Subject.ApiKey -> emptySet()
            }
            return User(subject.principal, roles.mapTo(HashSet()) { it.lowercase() }, zone.name, host, configAdmin && subject.enabled,
                tagWrite && subject.enabled)
        }
    }
}

/**
 * Per-tag read or write permission (TAG-013), after
 * - (Security):
 * a [TagCaller.User] passes when it holds at least one of [roles] **and** its zone is one of [zones].
 * An empty list does not restrict that dimension, so `{roles: [operator]}` admits operators from any
 * zone. When both are empty the permission names nobody ([NOBODY]) and no user passes; only a tag
 * with no permission at all (a null [TagAccess]) is unrestricted. Names compare case-insensitively. Administrators get no implicit pass; list the role.
 */
class TagAccess(roles: Collection<String>, zones: Collection<String>) {
    val roles: Set<String> = roles.mapTo(LinkedHashSet()) { it.lowercase() }
    val zones: Set<String> = zones.mapTo(LinkedHashSet()) { it.lowercase() }

    /**
     * The permission is present but names no role and no zone: no [TagCaller.User] passes. This
     * keeps main's rule that an empty `readPermissions` list names nobody (fail closed); only an
     * absent key (a null [TagAccess] on the tag) is unrestricted.
     */
    val namesNobody: Boolean get() = roles.isEmpty() && zones.isEmpty()

    fun allows(caller: TagCaller): Boolean = when (caller) {
        TagCaller.System -> true
        is TagCaller.User -> !namesNobody &&
            (roles.isEmpty() || caller.roles.any { it.lowercase() in roles }) &&
            (zones.isEmpty() || caller.zone.lowercase() in zones)
    }

    fun toSpec(): Map<String, Any?> = linkedMapOf("roles" to roles.toList(), "zones" to zones.toList())

    override fun equals(other: Any?) = other is TagAccess && other.roles == roles && other.zones == zones
    override fun hashCode() = roles.hashCode() * 31 + zones.hashCode()
    override fun toString() = if (namesNobody) "TagAccess.NOBODY" else "TagAccess(roles=$roles, zones=$zones)"

    companion object {
        /** Present but naming nobody; see [namesNobody]. Stored as `{roles: [], zones: []}`. */
        val NOBODY = TagAccess(emptyList(), emptyList())

        /**
         * Role-only access ([TagConfig]'s `readPermissions` shorthand). Null: unrestricted.
         * An empty collection names nobody ([NOBODY]), as on main.
         */
        fun ofRoles(roles: Collection<String>?): TagAccess? = when {
            roles == null -> null
            roles.isEmpty() -> NOBODY
            else -> TagAccess(roles, emptyList())
        }

        /**
         * [key] read from [spec]. Absent: null (unrestricted). Present, it is the `{roles, zones}`
         * object, a plain list of role names, or a comma-separated string of them (roles with no
         * zone restriction). A present value that names no role and no zone (`null`, `[]`, `""`,
         * `{}`, `{roles: [], zones: []}`) is [NOBODY]: fail closed.
         */
        fun fromSpec(spec: Map<String, Any?>, key: String): TagAccess? {
            if (!spec.containsKey(key)) return null
            return when (val raw = spec[key]) {
                null -> NOBODY
                is List<*> -> ofRoles(raw.mapNotNull { (it as? String)?.trim()?.takeIf { n -> n.isNotEmpty() } })
                is String -> ofRoles(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                is Map<*, *> -> {
                    fun names(field: String): List<String> = when (val v = raw[field]) {
                        null -> emptyList()
                        is List<*> -> v.map { it?.toString() ?: throw IllegalArgumentException("$key.$field must not contain null") }
                        else -> throw IllegalArgumentException("$key.$field must be a list")
                    }
                    TagAccess(names("roles"), names("zones")).let { if (it.namesNobody) NOBODY else it }
                }
                else -> throw IllegalArgumentException("$key must be an object")
            }
        }
    }
}

/**
 * The per-tag read decision (TAG-013) that every read path shares: [TagEngine.read], [TagEngine.mayRead],
 * [TagEngine.browse], [TagSample.mayRead] and through them the REST, OPC UA, history and alarm checks. A tag
 * with no read permission ([access] null) is readable by everyone; [TagCaller.System] always passes.
 * Pure: callers read [access] (one field) under the engine lock and evaluate this after releasing it.
 */
fun readAdmits(access: TagAccess?, caller: TagCaller): Boolean = access?.allows(caller) ?: true

/** Audit action for a user tag write. - */
const val TAG_WRITE_ACTION = "tag write"
