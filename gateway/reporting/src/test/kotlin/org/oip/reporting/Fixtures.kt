package org.oip.reporting

import org.oip.config.FileConfigStore
import org.oip.config.Metadata
import org.oip.config.Resource
import org.oip.config.Scope
import org.oip.db.QueryResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(var millis: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    override fun millis(): Long = millis
}

/** Records every call; answers from [answer]. */
class FakeQueries(
    val types: Map<String, String> = emptyMap(),
    var answer: (String, Map<String, Any?>) -> QueryResult = { _, _ -> QueryResult.Rows(emptyList(), emptyList(), false, false) },
) : NamedQueries {
    val calls = ArrayList<Triple<String, Map<String, Any?>, ReportCaller>>()
    override fun queryType(name: String): String = types[name] ?: "query"
    override fun execute(name: String, parameters: Map<String, Any?>, caller: ReportCaller): QueryResult {
        calls += Triple(name, parameters, caller)
        return answer(name, parameters)
    }

    val sqlCalls = ArrayList<Triple<String, String, Map<String, Any?>>>()
    var sqlAnswer: (String, String, Map<String, Any?>) -> QueryResult = { _, _, _ ->
        QueryResult.Rows(emptyList(), emptyList(), false, false)
    }

    override fun readSql(connection: String, sql: String, parameters: Map<String, Any?>): QueryResult {
        sqlCalls += Triple(connection, sql, parameters)
        return sqlAnswer(connection, sql, parameters)
    }
}

fun report(name: String, spec: Map<String, Any?>) = Resource(REPORT_KIND, Metadata(name, REPORTING_MODULE), spec)

fun FileConfigStore.putReport(name: String, spec: Map<String, Any?>): Resource {
    val existing = get(report(name, spec).id())
    val r = report(name, spec)
    return put(Scope.GATEWAY, if (existing == null) r else r.copy(metadata = r.metadata.copy(version = existing.metadata.version)))
}

val USER = ReportCaller("ann", setOf("operator"), setOf("tags.read"))
