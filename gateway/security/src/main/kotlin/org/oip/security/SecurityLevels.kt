package org.oip.security

import org.oip.config.SchemaRegistry
import java.net.InetAddress
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Security levels and the rules that grant them at login (SEC-016, SEC-017).
 *
 * The levels form one tree under the built-in root `Public`. `Public` is held by every caller, signed in or not;
 * its built-in child `Authenticated` is held by every caller that signed in, whichever login path it used. An
 * administrator adds levels below either one, as slash paths written from below `Public`:
 * `Authenticated/Roles/Operator` or `Shift/Night`. A path names its parents, so `Authenticated/Roles/Operator` also
 * defines `Authenticated/Roles`.
 *
 * The hierarchy: holding a level grants every level above it. A user granted `Authenticated/Roles/Operator` also
 * holds `Authenticated/Roles`, `Authenticated` and `Public`, so a requirement of `Authenticated/Roles` is met by any
 * level below it. A parent never grants its children: holding `Authenticated` does not meet a requirement of
 * `Authenticated/Roles/Operator`.
 *
 * Levels are granted at login by:
 * - the user's gateway roles: a user with role `operator` holds `Authenticated/Roles/operator` when the tree defines
 *   that level (role names are matched exactly);
 * - the rules: each enabled rule whose sources include the login's source and whose conditions hold grants its
 *   levels. A condition reads an identity-provider attribute (an OIDC claim, a SAML attribute, or for a password login
 *   the account's username, roles, display name and email), a gateway role, the username, or the request context: the
 *   security zone the connection matched, the source address, whether the connection is secure, and the time of the
 *   login in a given time zone.
 *
 * A granted level that the tree does not define is dropped, and a condition that cannot be read (an unknown time
 * zone, an address pattern that does not parse, a missing attribute) does not hold, so a broken rule grants nothing.
 * Configuration is one resource, `org.oip.security/SecurityLevels/levels`, edited through the config API with the same
 * permissions as the other security settings.
 *
 * Doc source: FEATURES SEC-016.
 * Doc source: FEATURES SEC-017.
 */
object SecurityLevels {
    const val KIND = "SecurityLevels"
    const val RESOURCE = "levels"
    const val PUBLIC = "Public"
    const val AUTHENTICATED = "Authenticated"
    const val ROLES = "Authenticated/Roles"

    /** Login sources a rule can name besides an identity provider's name. */
    const val SOURCE_ANY = "*"
    const val SOURCE_INTERNAL = "internal"
    const val SOURCE_API_KEY = "api-key"

    const val MAX_LEVELS = 512
    const val MAX_DEPTH = 8
    const val MAX_RULES = 256
    const val MAX_USER_GRANTS = 1024
    const val MAX_CONDITIONS = 32

    private const val SEGMENT = "[A-Za-z0-9][A-Za-z0-9 _.-]{0,62}[A-Za-z0-9_.-]|[A-Za-z0-9]"
    /** A level path below `Public`, or `Public` itself. */
    val PATH = Regex("^($SEGMENT)(/($SEGMENT)){0,${MAX_DEPTH - 1}}$")
    private const val RULE_NAME = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"
    private const val SOURCE = "^(\\*|[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)$"
    private const val CLOCK = "^([01][0-9]|2[0-3]):[0-5][0-9]$"
    val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    val CONDITION_TYPES = listOf("attribute", "role", "username", "zone", "address", "secure", "time")
    val OPERATORS = listOf("equals", "contains", "startsWith", "endsWith", "present")

    fun schema(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "levels" to mapOf(
                "type" to "array",
                "maxItems" to MAX_LEVELS,
                "default" to emptyList<Any>(),
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "maxLength" to 520, "pattern" to PATH.pattern),
                        "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
                    ),
                    "required" to listOf("path"),
                ),
            ),
            // SEC-019: a specific user placed directly in levels, whatever the rules say.
            "userGrants" to mapOf(
                "type" to "array",
                "maxItems" to MAX_USER_GRANTS,
                "default" to emptyList<Any>(),
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "source" to mapOf("type" to "string", "pattern" to "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", "default" to SOURCE_INTERNAL),
                        "username" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 200),
                        "grant" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string", "maxLength" to 520, "pattern" to PATH.pattern),
                            "uniqueItems" to true, "minItems" to 1, "maxItems" to 64,
                        ),
                    ),
                    "required" to listOf("username", "grant"),
                ),
            ),
            "rules" to mapOf(
                "type" to "array",
                "maxItems" to MAX_RULES,
                "default" to emptyList<Any>(),
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "pattern" to RULE_NAME),
                        "description" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
                        "enabled" to mapOf("type" to "boolean", "default" to true),
                        "sources" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string", "pattern" to SOURCE),
                            "uniqueItems" to true, "minItems" to 1, "maxItems" to 64, "default" to listOf(SOURCE_ANY),
                        ),
                        "match" to mapOf("type" to "string", "enum" to listOf("all", "any"), "default" to "all"),
                        "conditions" to mapOf(
                            "type" to "array",
                            "maxItems" to MAX_CONDITIONS,
                            "default" to emptyList<Any>(),
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "type" to mapOf("type" to "string", "enum" to CONDITION_TYPES),
                                    "name" to mapOf("type" to "string", "maxLength" to 200, "default" to ""),
                                    "operator" to mapOf("type" to "string", "enum" to OPERATORS, "default" to "equals"),
                                    "value" to mapOf("type" to "string", "maxLength" to 500, "default" to ""),
                                    "days" to mapOf(
                                        "type" to "array", "items" to mapOf("type" to "string", "enum" to DAYS),
                                        "uniqueItems" to true, "maxItems" to 7, "default" to emptyList<String>(),
                                    ),
                                    "from" to mapOf("type" to "string", "pattern" to CLOCK, "default" to "00:00"),
                                    "to" to mapOf("type" to "string", "pattern" to CLOCK, "default" to "00:00"),
                                    "timeZone" to mapOf("type" to "string", "maxLength" to 64, "default" to "UTC"),
                                    "negate" to mapOf("type" to "boolean", "default" to false),
                                ),
                                "required" to listOf("type"),
                            ),
                        ),
                        "grant" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string", "maxLength" to 520, "pattern" to PATH.pattern),
                            "uniqueItems" to true, "minItems" to 1, "maxItems" to 64,
                        ),
                    ),
                    "required" to listOf("name", "grant"),
                ),
            ),
        ),
    )

    fun register(registry: SchemaRegistry) {
        registry.register(SECURITY_MODULE, KIND, schema())
        registry.registerSemantic(SECURITY_MODULE, KIND) { spec -> SecurityLevelConfig.of(spec, 0L).problems() }
    }

    /** [paths] with every level above each of them, `Public` included. */
    fun withAncestors(paths: Collection<String>): Set<String> {
        val out = sortedSetOf(PUBLIC)
        for (p in paths) {
            if (p == PUBLIC) continue
            val parts = p.split('/')
            for (i in 1..parts.size) out += parts.subList(0, i).joinToString("/")
        }
        return out
    }

    /**
     * Whether [held] (already expanded with [withAncestors]) meets [required]: every required level when [requireAll],
     * otherwise any one of them. An empty requirement is met by anyone.
     */
    fun satisfies(held: Set<String>, required: Collection<String>, requireAll: Boolean = false): Boolean {
        if (required.isEmpty()) return true
        return if (requireAll) required.all { it in held } else required.any { it in held }
    }
}

/** One administrator-defined level. */
data class SecurityLevelDef(val path: String, val description: String)

/** One condition of a [SecurityLevelRule]. */
data class LevelCondition(
    val type: String,
    val name: String = "",
    val operator: String = "equals",
    val value: String = "",
    val days: List<String> = emptyList(),
    val from: String = "00:00",
    val to: String = "00:00",
    val timeZone: String = "UTC",
    val negate: Boolean = false,
) {
    /** Why this condition can never be evaluated, or null when it can. */
    fun problem(): String? = when (type) {
        "attribute" -> if (name.isBlank()) "an attribute condition needs a name" else if (operator != "present" && value.isEmpty()) "an attribute condition needs a value" else null
        "role", "zone" -> if (value.isBlank()) "a $type condition needs a value" else null
        "username" -> if (operator != "present" && value.isEmpty()) "a username condition needs a value" else null
        "address" -> if (IpPattern.parse(value) == null) "'$value' is not an address pattern" else null
        "secure" -> null
        "time" -> when {
            zone() == null -> "'$timeZone' is not a time zone"
            days.any { it !in SecurityLevels.DAYS } -> "days must be mon..sun"
            clock(from) == null || clock(to) == null -> "from and to must be HH:MM"
            else -> null
        }
        else -> "unknown condition type '$type'"
    }

    /** A condition that cannot be evaluated never holds, negated or not (fail closed). */
    fun holds(facts: LoginFacts): Boolean {
        if (problem() != null) return false
        return test(facts) != negate
    }

    private fun test(facts: LoginFacts): Boolean = when (type) {
        "attribute" -> compare(facts.attributes[name].orEmpty())
        "role" -> value in facts.roles
        "username" -> compare(listOf(facts.username))
        "zone" -> facts.zone.equals(value, ignoreCase = true)
        "address" -> {
            val addr = facts.address?.let { IpLiteral.normalize(it) }
            addr != null && IpPattern.parse(value)?.matches(addr) == true
        }
        "secure" -> facts.secure
        "time" -> inWindow(facts.at)
        else -> false
    }

    private fun compare(values: List<String>): Boolean = when (operator) {
        "present" -> values.any { it.isNotEmpty() }
        "equals" -> values.any { it == value }
        "contains" -> values.any { it.contains(value) }
        "startsWith" -> values.any { it.startsWith(value) }
        "endsWith" -> values.any { it.endsWith(value) }
        else -> false
    }

    /** [from] to [to] on one of [days] (all days when empty); a window whose end is not after its start wraps midnight. */
    private fun inWindow(at: Instant): Boolean {
        val z = zone() ?: return false
        val local = at.atZone(z)
        val start = clock(from) ?: return false
        val end = clock(to) ?: return false
        val now = local.toLocalTime()
        val day = local.dayOfWeek
        fun dayOk(d: DayOfWeek) = days.isEmpty() || dayName(d) in days
        if (start == end) return dayOk(day)
        return if (start < end) dayOk(day) && now >= start && now < end
        // Wrapping window: the part after midnight belongs to the day the window started.
        else (now >= start && dayOk(day)) || (now < end && dayOk(day.minus(1)))
    }

    private fun zone(): ZoneId? = runCatching { if (timeZone.isBlank()) ZoneOffset.UTC else ZoneId.of(timeZone) }.getOrNull()
    private fun clock(text: String): LocalTime? = runCatching { LocalTime.parse(text) }.getOrNull()
    private fun dayName(d: DayOfWeek) = SecurityLevels.DAYS[d.value - 1]
}

/**
 * One user grant (SEC-019): the user named [username] on [source] (a user source or identity provider name) is granted
 * [grant], with ancestors, at every login, whatever the rules and roles say. The user name matches exactly.
 */
data class UserLevelGrant(val source: String, val username: String, val grant: List<String>) {
    fun appliesTo(facts: LoginFacts): Boolean = source == facts.source && username == facts.username
}

/** One rule: when its [conditions] hold for a login from one of its [sources], the user is granted [grant]. */
data class SecurityLevelRule(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val sources: List<String>,
    val matchAll: Boolean,
    val conditions: List<LevelCondition>,
    val grant: List<String>,
) {
    fun appliesTo(source: String): Boolean = SecurityLevels.SOURCE_ANY in sources || source in sources

    fun matches(facts: LoginFacts): Boolean {
        if (!enabled || !appliesTo(facts.source)) return false
        if (conditions.isEmpty()) return true
        return if (matchAll) conditions.all { it.holds(facts) } else conditions.any { it.holds(facts) }
    }
}

/**
 * What a rule can read about one login. [source] is [SecurityLevels.SOURCE_INTERNAL] for a password login,
 * [SecurityLevels.SOURCE_API_KEY] for an API key, or the identity provider's name. [attributes] are the provider's
 * claims or assertion attributes (strings, numbers and booleans as text, lists flattened).
 */
data class LoginFacts(
    val source: String,
    val username: String,
    val roles: Set<String>,
    val attributes: Map<String, List<String>>,
    val address: InetAddress?,
    val zone: String,
    val secure: Boolean,
    val at: Instant,
)

/** The result of [SecurityLevelConfig.evaluate]: [levels] with their ancestors, and the rules that granted any. */
data class LevelGrant(val levels: Set<String>, val rules: List<String>)

/** The security level tree and rules as read, with defaults applied. */
data class SecurityLevelConfig(
    val levels: List<SecurityLevelDef>,
    val rules: List<SecurityLevelRule>,
    val version: Long,
    val userGrants: List<UserLevelGrant> = emptyList(),
) {
    /** Every level the tree defines: the built-ins, each configured path, and each path's parents. */
    val defined: Set<String> by lazy {
        SecurityLevels.withAncestors(levels.map { it.path } + SecurityLevels.AUTHENTICATED)
    }

    fun problems(): List<String> = buildList {
        val seen = HashSet<String>()
        for (l in levels) {
            if (!SecurityLevels.PATH.matches(l.path)) add("'${l.path}' is not a level path")
            if (l.path == SecurityLevels.PUBLIC || l.path == SecurityLevels.AUTHENTICATED) add("'${l.path}' is built in")
            if (l.path.startsWith("${SecurityLevels.PUBLIC}/")) add("level paths start below Public: write '${l.path.removePrefix("${SecurityLevels.PUBLIC}/")}'")
            if (!seen.add(l.path)) add("level '${l.path}' is listed twice")
        }
        val grantKeys = HashSet<String>()
        for (g in userGrants) {
            if (!grantKeys.add("${g.source}\u0000${g.username}")) add("user '${g.username}' on '${g.source}' has two grants")
            for (l in g.grant) if (l !in defined) add("user grant for '${g.username}' names '$l', which is not a defined level")
        }
        val names = HashSet<String>()
        for (r in rules) {
            if (!names.add(r.name)) add("rule '${r.name}' is listed twice")
            for (g in r.grant) if (g !in defined) add("rule '${r.name}' grants '$g', which is not a defined level")
            r.conditions.forEachIndexed { i, c -> c.problem()?.let { add("rule '${r.name}' condition ${i + 1}: $it") } }
        }
    }

    /**
     * The levels one login holds: `Public` and `Authenticated`, the role levels of [LoginFacts.roles], and the grants
     * of every matching rule, each with its ancestors. A level the tree no longer defines is not granted.
     */
    fun evaluate(facts: LoginFacts): LevelGrant {
        val granted = linkedSetOf(SecurityLevels.AUTHENTICATED)
        for (role in facts.roles) {
            val level = "${SecurityLevels.ROLES}/$role"
            if (level in defined) granted += level
        }
        val fired = mutableListOf<String>()
        for (rule in rules) {
            if (!rule.matches(facts)) continue
            val grants = rule.grant.filter { it in defined }
            if (grants.isNotEmpty()) fired += rule.name
            granted += grants
        }
        for (g in userGrants) {
            if (!g.appliesTo(facts)) continue
            val grants = g.grant.filter { it in defined }
            if (grants.isNotEmpty()) fired += "user-grant:${g.username}"
            granted += grants
        }
        return LevelGrant(SecurityLevels.withAncestors(granted), fired)
    }

    /** [held] (levels stored at login) without any level the tree no longer defines; `Public` is always kept. */
    fun current(held: Collection<String>): Set<String> = SecurityLevels.withAncestors(held.filter { it in defined })

    companion object {
        val EMPTY = SecurityLevelConfig(emptyList(), emptyList(), 0L)

        @Suppress("UNCHECKED_CAST")
        fun of(spec: Map<String, Any?>, version: Long): SecurityLevelConfig {
            val levels = (spec["levels"] as? List<*>).orEmpty().mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                SecurityLevelDef(Specs.str(m, "path") ?: return@mapNotNull null, Specs.str(m, "description") ?: "")
            }
            val rules = (spec["rules"] as? List<*>).orEmpty().mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                SecurityLevelRule(
                    name = Specs.str(m, "name") ?: return@mapNotNull null,
                    description = Specs.str(m, "description") ?: "",
                    enabled = Specs.bool(m, "enabled", true),
                    sources = if (m.containsKey("sources")) Specs.strings(m, "sources") else listOf(SecurityLevels.SOURCE_ANY),
                    matchAll = (Specs.str(m, "match") ?: "all") != "any",
                    conditions = (m["conditions"] as? List<*>).orEmpty().mapNotNull { c ->
                        val cm = c as? Map<String, Any?> ?: return@mapNotNull null
                        LevelCondition(
                            type = Specs.str(cm, "type") ?: return@mapNotNull null,
                            name = Specs.str(cm, "name") ?: "",
                            operator = Specs.str(cm, "operator") ?: "equals",
                            value = Specs.str(cm, "value") ?: "",
                            days = Specs.strings(cm, "days"),
                            from = Specs.str(cm, "from") ?: "00:00",
                            to = Specs.str(cm, "to") ?: "00:00",
                            timeZone = Specs.str(cm, "timeZone") ?: "UTC",
                            negate = Specs.bool(cm, "negate", false),
                        )
                    },
                    grant = Specs.strings(m, "grant"),
                )
            }
            val userGrants = (spec["userGrants"] as? List<*>).orEmpty().mapNotNull { raw ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                UserLevelGrant(
                    source = Specs.str(m, "source") ?: SecurityLevels.SOURCE_INTERNAL,
                    username = Specs.str(m, "username") ?: return@mapNotNull null,
                    grant = Specs.strings(m, "grant"),
                )
            }
            return SecurityLevelConfig(levels, rules, version, userGrants)
        }
    }
}

/** Reads the level configuration. Writes go through the config API as for the other security settings. */
interface SecurityLevelDirectory {
    fun config(): SecurityLevelConfig
}

internal class SecurityLevelService(private val docs: ConfigDocs) : SecurityLevelDirectory {
    override fun config(): SecurityLevelConfig {
        val r = runCatching { docs.get(SecurityLevels.KIND, SecurityLevels.RESOURCE) }.getOrNull() ?: return SecurityLevelConfig.EMPTY
        val spec = docs.spec(SecurityLevels.KIND, SecurityLevels.RESOURCE) ?: return SecurityLevelConfig.EMPTY
        return SecurityLevelConfig.of(spec, r.metadata.version)
    }
}
