package org.oip.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import java.net.InetAddress
import java.time.Instant

/**
 * SEC-016 and SEC-017: the security level tree, its hierarchy, and the rules that grant levels at login.
 * Doc source: FEATURES SEC-016.
 * Doc source: FEATURES SEC-017.
 * SEC-019: user grants place one named user in levels directly.
 * Doc source: FEATURES SEC-019.
 */
class SecurityLevelsTest {
    private fun facts(
        source: String = SecurityLevels.SOURCE_INTERNAL,
        username: String = "alice",
        roles: Set<String> = emptySet(),
        attributes: Map<String, List<String>> = emptyMap(),
        address: String? = "10.1.2.3",
        zone: String = "Default",
        secure: Boolean = false,
        at: String = "2026-11-09T12:00:00Z",
    ) = LoginFacts(source, username, roles, attributes, address?.let { InetAddress.getByName(it) }, zone, secure, Instant.parse(at))

    private fun config(levels: List<String>, vararg rules: Map<String, Any?>) =
        SecurityLevelConfig.of(mapOf("levels" to levels.map { mapOf("path" to it) }, "rules" to rules.toList()), 1)

    @Test fun everyLoginHoldsPublicAndAuthenticatedAndAChildGrantsItsParents() {
        val cfg = config(listOf("Authenticated/Roles/operator", "Shift/Night"))
        assertEquals(setOf("Public", "Authenticated"), cfg.evaluate(facts()).levels)
        // The role level: a user with role operator holds it, and with it every level above.
        val op = cfg.evaluate(facts(roles = setOf("operator"))).levels
        assertEquals(setOf("Public", "Authenticated", "Authenticated/Roles", "Authenticated/Roles/operator"), op)
        // A requirement of a parent is met by any level below it; a parent never meets a child requirement.
        assertTrue(SecurityLevels.satisfies(op, listOf("Authenticated/Roles")))
        assertFalse(SecurityLevels.satisfies(cfg.evaluate(facts()).levels, listOf("Authenticated/Roles/operator")))
        assertFalse(SecurityLevels.satisfies(op, listOf("Authenticated/Roles/operator", "Shift/Night"), requireAll = true))
        assertTrue(SecurityLevels.satisfies(op, listOf("Authenticated/Roles/operator", "Shift/Night"), requireAll = false))
        assertTrue(SecurityLevels.satisfies(setOf("Public"), emptyList()))
        // A role whose level the tree does not define grants nothing.
        assertEquals(setOf("Public", "Authenticated"), cfg.evaluate(facts(roles = setOf("viewer"))).levels)
    }

    @Test fun rulesReadAttributesAndRequestContext() {
        val cfg = config(
            listOf("Authenticated/Plant/Operators", "Authenticated/Plant/Remote", "Shift/Night", "Control Room"),
            mapOf("name" to "ops-group", "sources" to listOf("corp"), "grant" to listOf("Authenticated/Plant/Operators"),
                "conditions" to listOf(mapOf("type" to "attribute", "name" to "groups", "value" to "plant-ops"))),
            mapOf("name" to "remote", "grant" to listOf("Authenticated/Plant/Remote"),
                "conditions" to listOf(mapOf("type" to "address", "value" to "10.0.0.0/8", "negate" to true))),
            mapOf("name" to "night", "grant" to listOf("Shift/Night"),
                "conditions" to listOf(mapOf("type" to "time", "from" to "22:00", "to" to "06:00", "timeZone" to "UTC", "days" to listOf("mon")))),
            mapOf("name" to "control-room", "match" to "all", "grant" to listOf("Control Room"),
                "conditions" to listOf(mapOf("type" to "zone", "value" to "ControlRoom"), mapOf("type" to "secure"))),
        )
        // The provider rule applies only to logins through that provider.
        val corp = cfg.evaluate(facts(source = "corp", attributes = mapOf("groups" to listOf("x", "plant-ops"))))
        assertTrue("Authenticated/Plant/Operators" in corp.levels, corp.toString())
        assertEquals(listOf("ops-group"), corp.rules)
        assertFalse("Authenticated/Plant/Operators" in cfg.evaluate(facts(attributes = mapOf("groups" to listOf("plant-ops")))).levels)
        // Negated address: outside 10/8 is remote.
        assertTrue("Authenticated/Plant/Remote" in cfg.evaluate(facts(address = "192.168.1.5")).levels)
        assertFalse("Authenticated/Plant/Remote" in cfg.evaluate(facts(address = "10.9.9.9")).levels)
        // A wrapping time window: Monday 23:00 and Tuesday 05:00 (still Monday's night) hold; Tuesday 23:00 does not.
        assertTrue("Shift/Night" in cfg.evaluate(facts(at = "2026-11-09T23:00:00Z")).levels)
        assertTrue("Shift/Night" in cfg.evaluate(facts(at = "2026-11-10T05:00:00Z")).levels)
        assertFalse("Shift/Night" in cfg.evaluate(facts(at = "2026-11-10T23:00:00Z")).levels)
        assertFalse("Shift/Night" in cfg.evaluate(facts(at = "2026-11-09T12:00:00Z")).levels)
        // Zone and secure connection, both required.
        assertTrue("Control Room" in cfg.evaluate(facts(zone = "ControlRoom", secure = true)).levels)
        assertFalse("Control Room" in cfg.evaluate(facts(zone = "ControlRoom", secure = false)).levels)
        assertFalse("Control Room" in cfg.evaluate(facts(zone = "Default", secure = true)).levels)
    }

    @Test fun aBrokenRuleOrADeletedLevelGrantsNothing() {
        val cfg = config(
            listOf("Authenticated/Ops"),
            mapOf("name" to "bad-zone", "grant" to listOf("Authenticated/Ops"),
                "conditions" to listOf(mapOf("type" to "time", "timeZone" to "Not/AZone", "negate" to true))),
            mapOf("name" to "bad-address", "grant" to listOf("Authenticated/Ops"),
                "conditions" to listOf(mapOf("type" to "address", "value" to "not an address", "negate" to true))),
            mapOf("name" to "missing-attribute", "grant" to listOf("Authenticated/Ops"),
                "conditions" to listOf(mapOf("type" to "attribute", "name" to "department", "value" to "ops"))),
            mapOf("name" to "gone", "grant" to listOf("Authenticated/Removed")),
            mapOf("name" to "off", "enabled" to false, "grant" to listOf("Authenticated/Ops")),
        )
        // A condition that cannot be evaluated never holds, negated or not.
        assertEquals(setOf("Public", "Authenticated"), cfg.evaluate(facts()).levels)
        // Levels stored at an earlier login lose whatever the tree no longer defines.
        assertEquals(setOf("Public", "Authenticated", "Authenticated/Ops"), cfg.current(setOf("Authenticated", "Authenticated/Ops", "Authenticated/Removed")))
        val problems = cfg.problems()
        assertTrue(problems.any { it.contains("Not/AZone") }, problems.toString())
        assertTrue(problems.any { it.contains("not an address") }, problems.toString())
        assertTrue(problems.any { it.contains("Authenticated/Removed") }, problems.toString())
    }

    @Test fun anyMatchAndOperators() {
        val cfg = config(
            listOf("Engineers"),
            mapOf("name" to "eng", "match" to "any", "grant" to listOf("Engineers"), "conditions" to listOf(
                mapOf("type" to "username", "operator" to "endsWith", "value" to ".eng"),
                mapOf("type" to "attribute", "name" to "email", "operator" to "endsWith", "value" to "@eng.example"),
                mapOf("type" to "role", "value" to "engineer"),
            )),
        )
        assertTrue("Engineers" in cfg.evaluate(facts(username = "bob.eng")).levels)
        assertTrue("Engineers" in cfg.evaluate(facts(attributes = mapOf("email" to listOf("c@eng.example")))).levels)
        assertTrue("Engineers" in cfg.evaluate(facts(roles = setOf("engineer"))).levels)
        assertFalse("Engineers" in cfg.evaluate(facts(username = "bob")).levels)
        // An API-key login is a source of its own.
        val keyOnly = config(listOf("Machines"), mapOf("name" to "keys", "sources" to listOf("api-key"), "grant" to listOf("Machines")))
        assertTrue("Machines" in keyOnly.evaluate(facts(source = SecurityLevels.SOURCE_API_KEY)).levels)
        assertFalse("Machines" in keyOnly.evaluate(facts()).levels)
    }

    @Test fun theSchemaAndItsChecksRefuseBadConfiguration() {
        val registry = SchemaRegistry()
        SecurityLevels.register(registry)
        fun errors(spec: Map<String, Any?>) = registry.validate(Resource(SecurityLevels.KIND, Metadata("levels", SECURITY_MODULE), spec))
        assertEquals(emptyList<String>(), errors(mapOf("levels" to listOf(mapOf("path" to "Authenticated/Roles/operator")))))
        assertTrue(errors(mapOf("levels" to listOf(mapOf("path" to "/leading")))).isNotEmpty())
        assertTrue(errors(mapOf("levels" to listOf(mapOf("path" to "Authenticated")))).isNotEmpty(), "built-in levels are not listed")
        assertTrue(errors(mapOf("rules" to listOf(mapOf("name" to "x", "grant" to listOf("Nowhere"))))).isNotEmpty(), "grant of an undefined level")
        assertTrue(errors(mapOf("rules" to listOf(mapOf("name" to "x", "grant" to listOf("Authenticated"),
            "conditions" to listOf(mapOf("type" to "script")))))).isNotEmpty(), "unknown condition type")
        assertTrue(errors(mapOf("rules" to listOf(mapOf("name" to "x", "grant" to listOf("Authenticated"), "extra" to 1)))).isNotEmpty(), "unknown field")
    }

    @Test fun aUserGrantPlacesOneNamedUserInALevelWithoutRolesOrRules() {
        val cfg = SecurityLevelConfig.of(
            mapOf(
                "levels" to listOf(mapOf("path" to "Plant/Maintenance")),
                "userGrants" to listOf(
                    mapOf("username" to "alice", "grant" to listOf("Plant/Maintenance")),
                    mapOf("source" to "corp", "username" to "bob", "grant" to listOf("Plant/Maintenance")),
                ),
            ),
            1,
        )
        assertEquals(emptyList<String>(), cfg.problems())
        val alice = cfg.evaluate(facts(username = "alice"))
        assertEquals(setOf("Public", "Authenticated", "Plant", "Plant/Maintenance"), alice.levels)
        assertEquals(listOf("user-grant:alice"), alice.rules)
        // Another user, the same name on another source, and a near name get nothing.
        assertFalse("Plant/Maintenance" in cfg.evaluate(facts(username = "alicia")).levels)
        assertFalse("Plant/Maintenance" in cfg.evaluate(facts(username = "Alice")).levels)
        assertFalse("Plant/Maintenance" in cfg.evaluate(facts(source = "corp", username = "alice")).levels)
        assertTrue("Plant/Maintenance" in cfg.evaluate(facts(source = "corp", username = "bob")).levels)
        assertFalse("Plant/Maintenance" in cfg.evaluate(facts(source = "internal", username = "bob")).levels)
    }

    @Test fun userGrantsNeedDefinedLevelsAndOneGrantPerUser() {
        val cfg = SecurityLevelConfig.of(
            mapOf(
                "levels" to listOf(mapOf("path" to "Ops")),
                "userGrants" to listOf(
                    mapOf("username" to "alice", "grant" to listOf("Nowhere")),
                    mapOf("username" to "alice", "grant" to listOf("Ops")),
                ),
            ),
            1,
        )
        val problems = cfg.problems()
        assertTrue(problems.any { "Nowhere" in it }, problems.toString())
        assertTrue(problems.any { "two grants" in it }, problems.toString())
        // An undefined level is never granted, even by a stored grant.
        assertFalse("Nowhere" in cfg.evaluate(facts(username = "alice")).levels)
    }
}
