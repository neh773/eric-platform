package org.oip.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class ResourceCodecTest {
    private val res = Resource(
        kind = "ModuleConfig",
        metadata = Metadata("default", "org.oip.hello-world", mapOf("env" to "lab"), 3, Instant.parse("2026-10-25T20:00:00Z"), "admin"),
        spec = mapOf("greeting" to "hi", "n" to 2, "nested" to mapOf("list" to listOf(1, "a", true, null))),
    )

    @Test fun yamlRoundTrip() { assertEquals(res, ResourceCodec.decode(ResourceCodec.encode(res, Format.YAML), Format.YAML)) }

    /** Hex ids and hashes are strings. Unquoted, YAML loads some of them as numbers and a reload drops the resource. */
    @Test fun numericLookingStringsStayStrings() {
        val spec = mapOf(
            "keyId" to "1234567890123456",
            "keyHash" to "9".repeat(64),
            "sci" to "123e456789012345",
            "sci64" to "1e" + "1".repeat(62),
            "exp" to "1.5e10",
            "negExp" to "1e-2",
            "hex" to "0x10",
            "plus" to "+12",
            "dot" to ".5",
            "inf" to ".inf",
            "nan" to ".nan",
            "leading" to "0000000000000000",
            "host" to "10.0.0.8",
            "item" to "3.HR51",
            "when" to "2026-10-27T06:21:54.191Z",
            "scope" to "tags.read",
        )
        val r = Resource("ApiKey", Metadata("ci", "org.oip.security"), spec)
        assertEquals(spec, ResourceCodec.decode(ResourceCodec.encode(r, Format.YAML), Format.YAML).spec)
    }
    @Test fun jsonRoundTrip() { assertEquals(res, ResourceCodec.decode(ResourceCodec.encode(res, Format.JSON), Format.JSON)) }
    @Test fun yamlAndJsonDecodeToSameResource() {
        assertEquals(ResourceCodec.decode(ResourceCodec.encode(res, Format.JSON), Format.JSON),
                     ResourceCodec.decode(ResourceCodec.encode(res, Format.YAML), Format.YAML))
    }
    @Test fun minimalResourceDefaults() {
        val r = ResourceCodec.decode("apiVersion: oip/v1\nkind: Thing\nmetadata:\n  name: a\n  module: m\n", Format.YAML)
        assertEquals(0L, r.metadata.version); assertEquals(emptyMap<String, Any?>(), r.spec); assertNull(r.metadata.modified)
    }
    @Test fun unknownTopLevelKeyRejected() {
        val ex = assertThrows(ResourceFormatException::class.java) {
            ResourceCodec.decode("apiVersion: oip/v1\nkind: Thing\nmetadata: {name: a, module: m}\nspec: {}\nstatus: {}\n", Format.YAML) }
        assertTrue(ex.message!!.contains("status"))
    }
    @Test fun unknownMetadataKeyRejected() {
        assertThrows(ResourceFormatException::class.java) {
            ResourceCodec.decode("""{"apiVersion":"oip/v1","kind":"Thing","metadata":{"name":"a","module":"m","owner":"x"}}""", Format.JSON) }
    }
    @Test fun missingKindRejected() {
        assertThrows(ResourceFormatException::class.java) { ResourceCodec.decode("apiVersion: oip/v1\nmetadata: {name: a, module: m}\n", Format.YAML) }
    }
    @Test fun wrongApiVersionRejected() {
        assertThrows(ResourceFormatException::class.java) { ResourceCodec.decode("apiVersion: v9\nkind: Thing\nmetadata: {name: a, module: m}\n", Format.YAML) }
    }
    @Test fun malformedRejected() {
        assertThrows(ResourceFormatException::class.java) { ResourceCodec.decode("{not json", Format.JSON) }
        assertThrows(ResourceFormatException::class.java) { ResourceCodec.decode("- a\n- b\n", Format.YAML) }
    }

    @ParameterizedTest @ValueSource(strings = ["", "A", "-a", "a-", "a_b", "a/b", "..", "a b", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"])
    fun invalidNamesRejected(name: String) {
        assertThrows(IllegalArgumentException::class.java) { ResourceId("m", Scope.GATEWAY, "Thing", name) }
    }
    @ParameterizedTest @ValueSource(strings = ["a", "a1", "my-tag-01", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"])
    fun validNamesAccepted(name: String) { assertEquals(name, ResourceId("m", Scope.GATEWAY, "Thing", name).name) }

    @Test fun invalidKindModuleScopeRejected() {
        assertThrows(IllegalArgumentException::class.java) { ResourceId("m", Scope.GATEWAY, "thing", "a") }
        assertThrows(IllegalArgumentException::class.java) { ResourceId("Bad_Mod", Scope.GATEWAY, "Thing", "a") }
        assertThrows(IllegalArgumentException::class.java) { Scope.parse("project/") }
        assertThrows(IllegalArgumentException::class.java) { Scope.parse("site/a") }
    }
    @Test fun invalidNameInDocumentRejected() {
        assertThrows(ResourceFormatException::class.java) { ResourceCodec.decode("apiVersion: oip/v1\nkind: Thing\nmetadata: {name: Bad Name, module: m}\n", Format.YAML) }
    }
    @Test fun scopeAndPathRoundTrip() {
        for (s in listOf("gateway", "project/line-1", "mode/dev")) assertEquals(s, Scope.parse(s).path)
        val id = ResourceId("org.oip.x", Scope.parse("project/p1"), "View", "main")
        assertEquals("org.oip.x/project/p1/View/main.yaml", id.relativePath)
        assertEquals(id, ResourceId.fromRelativePath(id.relativePath))
    }
}
