package org.oip.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ManifestParserTest {
    private val good = """
        id: org.example.thing
        version: 1.2.3
        name: Thing
        entrypoint: org.example.Thing
        dependencies:
          - id: org.example.base
            version: 1.0.0
        permissions: [tags.read, tags.write]
        configSchema:
          type: object
          properties:
            greeting: { type: string }
    """.trimIndent()

    @Test fun parsesValidManifest() {
        val m = ManifestParser.parse(good)
        assertEquals("org.example.thing", m.id)
        assertEquals(SemVer(1, 2, 3), m.version)
        assertEquals("org.example.Thing", m.entrypoint)
        assertEquals(listOf(Dependency("org.example.base", SemVer(1, 0, 0))), m.dependencies)
        assertEquals(listOf("tags.read", "tags.write"), m.permissions)
        assertEquals("object", m.configSchema["type"])
    }

    @Test fun optionalFieldsDefaultEmpty() {
        val m = ManifestParser.parse("id: a\nversion: 0.1.0\nentrypoint: x.Y\n")
        assertTrue(m.dependencies.isEmpty()); assertTrue(m.permissions.isEmpty()); assertTrue(m.configSchema.isEmpty())
        assertEquals("a", m.name)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "",                                                     // empty
        "- just\n- a list",                                     // not a map
        "version: 1.0.0\nentrypoint: x.Y",                      // missing id
        "id: a\nentrypoint: x.Y",                               // missing version
        "id: a\nversion: 1.0.0",                                // missing entrypoint
        "id: Bad_ID!\nversion: 1.0.0\nentrypoint: x.Y",         // bad id
        "id: a\nversion: one\nentrypoint: x.Y",                 // bad version
        "id: a\nversion: 1.0.0\nentrypoint: x.Y\ndependencies: nope",
        "id: a\nversion: 1.0.0\nentrypoint: x.Y\ndependencies:\n  - version: 1.0.0",
        "id: a\nversion: 1.0.0\nentrypoint: x.Y\ndependencies:\n  - id: a\n    version: 1.0.0", // self dep
        "id: a\nversion: 1.0.0\nentrypoint: x.Y\npermissions: [1, 2]",
        "id: a\nversion: 1.0.0\nentrypoint: x.Y\nconfigSchema: [x]",
        "id: a\nversion: 1.0.0\nentrypoint: x.Y\nconfigSchema: {type: 5}",
        "id: a\nversion: 1.0.0\nentrypoint: x.Y\nbogusKey: 1",   // unknown key
        "id: [unclosed",                                         // invalid yaml
    ])
    fun rejectsBadManifests(yaml: String) {
        assertThrows(ManifestException::class.java) { ManifestParser.parse(yaml) }
    }

    @Test fun semverOrdering() {
        assertTrue(SemVer.parse("1.10.0") > SemVer.parse("1.9.9"))
        assertEquals(SemVer(2, 0, 0, "rc.1"), SemVer.parse("2.0.0-rc.1"))
        assertTrue(SemVer.parse("2.0.0-rc.1") < SemVer.parse("2.0.0"))
    }
}
