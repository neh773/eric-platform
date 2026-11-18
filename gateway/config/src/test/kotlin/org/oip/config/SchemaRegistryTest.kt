package org.oip.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class SchemaRegistryTest {
    private fun spec(json: String): Map<String, Any?> = ResourceCodec.parseValue(json) as Map<String, Any?>
    private fun reg(schema: String) = SchemaRegistry().apply { register("m", "Thing", spec(schema)) }
    private fun res(specJson: String) = Resource("Thing", Metadata("a", "m"), spec(specJson))

    companion object {
        /** keyword, schema, valid spec, invalid spec. One fixture pair per keyword the platform uses. */
        @JvmStatic fun keywords() = listOf(
            Arguments.of("type", """{"type":"object","properties":{"x":{"type":"string"}}}""", """{"x":"a"}""", """{"x":1}"""),
            Arguments.of("properties", """{"properties":{"n":{"type":"integer"}}}""", """{"n":2}""", """{"n":2.5}"""),
            Arguments.of("required", """{"properties":{"x":{}},"required":["x"]}""", """{"x":1}""", """{}"""),
            Arguments.of("additionalProperties", """{"properties":{"x":{}},"additionalProperties":false}""", """{"x":1}""", """{"y":1}"""),
            Arguments.of("enum", """{"properties":{"m":{"enum":["a","b"]}}}""", """{"m":"a"}""", """{"m":"c"}"""),
            Arguments.of("const", """{"properties":{"m":{"const":"a"}}}""", """{"m":"a"}""", """{"m":"b"}"""),
            Arguments.of("minLength", """{"properties":{"s":{"type":"string","minLength":1}}}""", """{"s":"a"}""", """{"s":""}"""),
            Arguments.of("maxLength", """{"properties":{"s":{"type":"string","maxLength":2}}}""", """{"s":"ab"}""", """{"s":"abc"}"""),
            Arguments.of("pattern", """{"properties":{"s":{"type":"string","pattern":"^[a-z]+$"}}}""", """{"s":"abc"}""", """{"s":"A1"}"""),
            Arguments.of("minimum", """{"properties":{"n":{"type":"number","minimum":0}}}""", """{"n":0}""", """{"n":-1}"""),
            Arguments.of("maximum", """{"properties":{"n":{"type":"number","maximum":10}}}""", """{"n":10}""", """{"n":11}"""),
            Arguments.of("items", """{"properties":{"l":{"type":"array","items":{"type":"integer"}}}}""", """{"l":[1,2]}""", """{"l":[1,"x"]}"""),
            Arguments.of("prefixItems (2020-12)", """{"properties":{"t":{"type":"array","prefixItems":[{"type":"string"},{"type":"integer"}]}}}""", """{"t":["a",1]}""", """{"t":[1,"a"]}"""),
            Arguments.of("\$defs/\$ref", """{"${'$'}defs":{"p":{"type":"integer"}},"properties":{"n":{"${'$'}ref":"#/${'$'}defs/p"}}}""", """{"n":1}""", """{"n":"x"}"""),
            Arguments.of("oneOf", """{"properties":{"v":{"oneOf":[{"type":"string"},{"type":"integer"}]}}}""", """{"v":1}""", """{"v":true}"""),
        )
    }

    @ParameterizedTest(name = "{0}") @MethodSource("keywords")
    fun keywordFixtures(keyword: String, schema: String, valid: String, invalid: String) {
        val r = reg(schema)
        assertEquals(emptyList<String>(), r.validate(res(valid)), "valid fixture for $keyword")
        assertTrue(r.validate(res(invalid)).isNotEmpty(), "invalid fixture for $keyword must fail")
    }

    @Test fun unknownFieldsRejectedByDefault() {
        val r = reg("""{"type":"object","properties":{"x":{"type":"object","properties":{"y":{}}}}}""")
        assertTrue(r.validate(res("""{"z":1}""")).isNotEmpty())
        assertTrue(r.validate(res("""{"x":{"q":1}}""")).isNotEmpty())
        assertEquals(emptyList<String>(), r.validate(res("""{"x":{"y":1}}""")))
    }

    @Test fun unregisteredKindRejected() {
        val r = SchemaRegistry()
        val ex = assertThrows(UnknownKindException::class.java) { r.validate(res("{}")) }
        assertTrue(ex.message!!.contains("Thing"))
        assertThrows(UnknownKindException::class.java) { r.check(res("{}")) }
    }

    @Test fun checkThrowsValidationException() {
        val ex = assertThrows(ValidationException::class.java) { reg("""{"properties":{"x":{"type":"string"}}}""").check(res("""{"x":1}""")) }
        assertTrue(ex.errors.isNotEmpty())
    }

    @Test fun otherDraftRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SchemaRegistry().register("m", "Thing", spec("""{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object"}"""))
        }
    }
    @Test fun invalidSchemaRejected() {
        assertThrows(IllegalArgumentException::class.java) { SchemaRegistry().register("m", "Thing", spec("""{"type":"nope"}""")) }
    }

    @Test fun kindsAreKeyedPerModuleAndUnregisterable() {
        val r = SchemaRegistry()
        r.register("m", "Thing", spec("""{"properties":{"x":{}}}""")); r.register("n", "Thing", spec("""{"properties":{"y":{}}}"""))
        assertEquals(emptyList<String>(), r.validate(res("""{"x":1}""")))
        r.unregisterModule("m")
        assertFalse(r.isRegistered("m", "Thing")); assertTrue(r.isRegistered("n", "Thing"))
    }

    @Test fun manifestConfigSchemaRegistersModuleConfigKind() {
        val r = SchemaRegistry()
        r.registerModuleConfig("org.oip.hello-world", mapOf("type" to "object", "properties" to mapOf("greeting" to mapOf("type" to "string", "default" to "hi"))))
        assertTrue(r.isRegistered("org.oip.hello-world", SchemaRegistry.MODULE_CONFIG_KIND))
        assertEquals(mapOf("greeting" to "hi"), r.defaults("org.oip.hello-world", SchemaRegistry.MODULE_CONFIG_KIND))
    }

    @Test fun defaultsAppliedRecursivelyWithoutOverwriting() {
        val r = reg("""{"properties":{"a":{"default":1},"b":{"type":"object","properties":{"c":{"default":"x"}}}}}""")
        assertEquals(mapOf("a" to 5, "b" to mapOf("c" to "x")), r.applyDefaults("m", "Thing", mapOf("a" to 5, "b" to emptyMap<String, Any?>())))
    }
}
