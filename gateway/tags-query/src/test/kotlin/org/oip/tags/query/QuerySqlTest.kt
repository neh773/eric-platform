package org.oip.tags.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.SchemaRegistry
import org.oip.config.ValidationException
import org.oip.tags.DataType
import org.oip.tags.TAG_KIND
import org.oip.tags.TAGS_MODULE
import org.oip.tags.TagConfig
import org.oip.tags.TagType
import org.oip.tags.registerTagSchemas

/**
 * `{tag path}` becomes a placeholder. The tag value is not written into the SQL.
 */
class QuerySqlTest {
    @Test
    fun tagReferencesBecomePlaceholdersAndQuotedBracesStayLiteral() {
        val prepared = QuerySql.prepare(
            "SELECT '{not a tag}' AS name, {[default]Line/Id} AS id -- {skip}\n" +
                "/* {also skip} */ , {[.]Min}",
        )
        assertEquals("SELECT '{not a tag}' AS name, ? AS id -- {skip}\n/* {also skip} */ , ?", prepared.sql)
        assertEquals(listOf("[default]Line/Id", "[.]Min"), prepared.references)
    }

    @Test
    fun dollarQuotesAndEscapedStringsAreNotParameters() {
        val prepared = QuerySql.prepare("SELECT \$tag\$ {not} \$tag\$, E'a\\'{b}', {[default]A}")
        assertEquals("SELECT \$tag\$ {not} \$tag\$, E'a\\'{b}', ?", prepared.sql)
        assertEquals(listOf("[default]A"), prepared.references)
    }

    @Test
    fun aSecondStatementAWriteOrABarePlaceholderIsRejected() {
        assertThrows<QueryTagException> { QuerySql.prepare("SELECT 1; DROP TABLE t") }
        assertThrows<QueryTagException> { QuerySql.prepare("DELETE FROM t WHERE id = {[default]A}") }
        assertThrows<QueryTagException> { QuerySql.prepare("SELECT ?") }
        assertThrows<QueryTagException> { QuerySql.prepare("   ") }
        assertThrows<QueryTagException> { QuerySql.prepare("SELECT {") }
    }

    @Test
    fun queryFieldsRoundTripThroughTheTagSpec() {
        val cfg = TagConfig.fromSpec(mapOf(
            "path" to "Line/Speed",
            "type" to "query",
            "dataType" to "Document",
            "query" to "SELECT {[default]Line/Id}",
            "datasource" to "plant",
            "queryDataset" to true,
        ))
        assertEquals(TagType.QUERY, cfg.type)
        assertEquals(DataType.DOCUMENT, cfg.dataType)
        assertEquals("SELECT {[default]Line/Id}", cfg.query)
        assertEquals("plant", cfg.datasource)
        assertTrue(cfg.queryDataset)
        val again = TagConfig.fromSpec(cfg.toSpec())
        assertEquals(cfg.query, again.query)
        assertEquals(cfg.datasource, again.datasource)
        assertTrue(again.queryDataset)

        val registry = SchemaRegistry()
        registerTagSchemas(registry)
        registry.check(Resource(TAG_KIND, Metadata("speed", TAGS_MODULE), cfg.toSpec()))
        assertThrows<ValidationException> {
            registry.check(Resource(TAG_KIND, Metadata("bad", TAGS_MODULE), cfg.toSpec() + mapOf("datasource" to "Not A Name")))
        }
    }
}
