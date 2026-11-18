package org.oip.reporting

import org.oip.db.DatabaseService
import org.oip.db.QueryResult
import org.oip.historian.HistoryCollector
import org.oip.historian.HistoryReadException
import org.oip.historian.HistoryReader
import org.oip.historian.HistoryRouter
import org.oip.historian.LOCAL_PROVIDER

/**
 * Named queries from `gateway/db` (#51). A user caller is checked against the query's role list by
 * [DatabaseService.execute]. A [ReportCaller.system] caller (scheduled run) is given the query's own roles, so the
 * role check passes; role lists still apply to every manual run.
 */
class DatabaseQueries(private val db: DatabaseService) : NamedQueries {
    override fun queryType(name: String): String = db.query(name)["queryType"] as String

    override fun execute(name: String, parameters: Map<String, Any?>, caller: ReportCaller): QueryResult {
        val roles = if (caller.system) {
            (db.query(name)["roles"] as? List<*>).orEmpty().map { it.toString() }.toSet()
        } else caller.roles
        return db.execute(name, parameters, roles)
    }

    override fun readSql(connection: String, sql: String, parameters: Map<String, Any?>): QueryResult =
        db.scriptQuery(connection, sql, parameters, prepared = true, mode = "rows", tx = null, allowWrite = false)
}

/**
 * Tag history through the [HistoryRouter] (#52/#64), the same path `HistoryApi` takes: flush samples still queued
 * in the collector, pick the tag's configured provider, and for a store-and-forward provider attempt a forward
 * (never waiting on one already running) so the report sees what has reached the provider.
 */
class RouterHistoryAccess(private val collector: HistoryCollector, private val router: HistoryRouter) : HistoryAccess {
    override fun reader(tagPath: String): HistoryReader {
        runCatching { collector.flush() }
        val provider = router.providerFor(tagPath)
        val reader = router.reader(provider) ?: throw HistoryReadException("unknown history provider '$provider'")
        if (provider != LOCAL_PROVIDER) runCatching { router.tryForward(provider) }
        return reader
    }
}
